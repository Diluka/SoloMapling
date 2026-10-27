from __future__ import annotations

import asyncio
from copy import deepcopy
import json
import time
import unittest
from urllib.parse import urlsplit
from uuid import uuid4

from solo_acceptance_mcp.actor_events_smoke import require_packet_capability
from solo_acceptance_mcp.smoke import require_capabilities
from tests.helpers import PASSWORD, TOKEN, envelope
from tests import test_chat_protocol as chat_protocol
from tests.test_actor_events_smoke import PACKET_CAPABILITIES
from tests.test_chat_protocol import (
    ACTOR_TOOLS, ACT_PATH, MAX_LONG, OBSERVATION_TOOLS, TOOLS,
    ChatProtocolStub, capabilities,
)


ACTIONS = ["chat", "npc_center_open", "npc_cancel"]
NPC_ACTIONS = ACTIONS[1:]
NPC_DECODER = {"event_type": "npc_dialogue", "opcode": 304, "message_types": [4]}


def npc_capabilities(*, actions: list[str] | None = None, npc_decoder: bool = True) -> dict:
    value = capabilities(TOOLS)
    value["actions"] = ACTIONS[:] if actions is None else actions[:]
    packet = deepcopy(PACKET_CAPABILITIES)
    if npc_decoder:
        packet["decoded_packets"].append(deepcopy(NPC_DECODER))
    value.update(scopes=["world", "scene", "actor"], event_stream=False)
    value["actor_control"].update(actions=value["actions"][:], packet_observation=packet)
    return value


class NpcProtocolStub(ChatProtocolStub):
    """Native facts are local HTTP fixtures, never actual NPC scripts or CM state."""

    def __init__(self) -> None:
        self.current_action = "npc_center_open"
        self.dialogue_version = 29
        super().__init__()

    def operation(self, status: str | None = None) -> dict:
        value = super().operation(status)
        if self.current_action == "chat":
            return value
        value.update(action=self.current_action)
        if value["status"] == "SUCCEEDED":
            value["result"] = {
                "observed_outcome": "center_dialogue_opened" if self.current_action == "npc_center_open" else "dialogue_cancelled",
                "native_dispatch_started": True,
                "dialogue_version": self.dialogue_version,
                "packet_sequence": self.dialogue_version,
                "npc_template_id": 9900001, "message_type": 4,
                "active": self.current_action == "npc_center_open",
                "native_dialogue_cleanup_verified": self.current_action == "npc_cancel",
                "packet_cursor_before": self.dialogue_version - 1,
                "packet_cursor_after": self.dialogue_version,
                "cancel_too_late": False,
            }
        elif value["status"] in {"FAILED", "INDETERMINATE"}:
            value["result"] = {}
            prefix = "native_center" if self.current_action == "npc_center_open" else "native_dialogue"
            value["error"] = {"reason": prefix + ("_outcome_unknown" if value["status"] == "INDETERMINATE"
                                                  else "_not_opened" if self.current_action == "npc_center_open" else "_not_cancelled")}
        elif value["status"] == "CANCELLED":
            value["result"] = {"native_dispatch_started": False}
        return value

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            return 200, envelope(npc_capabilities())
        if path == ACT_PATH:
            self.current_action = request["body"]["data"]["action"]
        return super().default_response(request)


class NpcProtocolTests(unittest.IsolatedAsyncioTestCase):
    # Reuse only the official stdio/local HTTP fixture helpers, not chat test cases.
    stdio_client = chat_protocol.ChatProtocolTests.stdio_client
    action_requests = chat_protocol.ChatProtocolTests.action_requests

    async def asyncSetUp(self) -> None:
        self.stub = NpcProtocolStub().start()
        self.args = {
            "session_id": self.stub.session_id, "expected_generation": 7,
            "request_id": "npc:" + uuid4().hex, "action": "npc_center_open", "parameters": {},
            "expected_context": {"scene_ref": "scene-" + uuid4().hex,
                                 "runtime_generation": self.stub.runtime_generation},
        }

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    @staticmethod
    def parameters(action: str, version: int = 29) -> dict:
        return {"text": "普通聊天协议桩"} if action == "chat" else {"dialogue_version": version} if action == "npc_cancel" else {}

    async def test_npc_seven_tools_action_schema_and_tagged_parameter_branches_are_explicit(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(TOOLS, set(tools))
        schema = tools["game_act"].input_schema
        self.assertIs(False, schema.get("additionalProperties"))
        self.assertEqual(set(self.args), set(schema["required"]))
        self.assertEqual(ACTIONS + ["npc_reply"], schema["properties"]["action"]["enum"])
        annotations = tools["game_act"].annotations
        self.assertFalse(annotations.read_only_hint)
        self.assertFalse(annotations.destructive_hint)
        self.assertTrue(annotations.idempotent_hint)
        self.assertTrue(annotations.open_world_hint)
        parameter_schema = schema["properties"]["parameters"]
        variants = parameter_schema.get("anyOf", parameter_schema.get("oneOf", []))
        branches = [schema["$defs"][variant["$ref"].split("/")[-1]] if "$ref" in variant else variant
                    for variant in variants]
        self.assertEqual(5, len(branches))
        self.assertEqual({frozenset(), frozenset({"text"}), frozenset({"dialogue_version"}),
                          frozenset({"dialogue_version", "kind"}), frozenset({"dialogue_version", "kind", "selection"})},
                         {frozenset(branch.get("properties", {})) for branch in branches})
        self.assertTrue(all(branch.get("additionalProperties") is False for branch in branches))
        tagged = schema["allOf"][0]["oneOf"]
        self.assertEqual(4, len(tagged))
        self.assertEqual({
            "chat": frozenset({"text"}), "npc_center_open": frozenset(),
            "npc_cancel": frozenset({"dialogue_version"}),
        }, {branch["properties"]["action"]["const"]: frozenset(
            schema["$defs"][branch["properties"]["parameters"]["$ref"].split("/")[-1]].get("properties", {})
        ) for branch in tagged if "$ref" in branch["properties"]["parameters"]})
        self.assertEqual([], self.stub.requests)

    async def test_npc_and_chat_actions_preserve_jwt_envelope_and_exact_parameter_payload(self) -> None:
        async with self.stdio_client() as client:
            for index, action in enumerate(ACTIONS):
                with self.subTest(action=action):
                    args = {**self.args, "action": action, "parameters": self.parameters(action),
                            "request_id": "action-" + str(index)}
                    response = await client.call_tool("game_act", args)
                    self.assertFalse(response.is_error)
                    self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
                    self.assertEqual(response.structured_content, json.loads(response.content[0].text))
                    request = self.action_requests()[-1]
                    self.assertEqual("POST", request["method"])
                    self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
                    self.assertEqual({"requestId": args["request_id"],
                                      "data": {key: value for key, value in args.items() if key != "request_id"}}, request["body"])
                    self.assertNotIn(PASSWORD, response.model_dump_json())
                    self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertEqual(3, len(self.action_requests()))
        self.assertEqual(1, self.stub.login_count)

    async def test_npc_parameters_cannot_be_mixed_between_action_branches_before_http(self) -> None:
        cases = {
            "npc_center_open": [None, True, [], "", {"text": "safe"}, {"dialogue_version": 29}],
            "npc_cancel": [None, True, [], "", {}, {"text": "safe"}, {"dialogue_version": 29, "text": "safe"}],
            "chat": [None, {}, {"dialogue_version": 29}, {"text": "safe", "dialogue_version": 29}],
        }
        async with self.stdio_client() as client:
            for action, invalid in cases.items():
                for parameters in invalid:
                    with self.subTest(action=action, parameters=parameters):
                        response = await client.call_tool("game_act", {**self.args, "action": action, "parameters": parameters})
                        self.assertTrue(response.is_error)
                        self.assertEqual([], self.stub.requests)

    async def test_npc_dialogue_version_is_strict_positive_long_and_both_legal_boundaries_forward(self) -> None:
        async with self.stdio_client() as client:
            for version in [None, True, False, 1.0, "1", 0, -1, MAX_LONG + 1, [], {}]:
                with self.subTest(version=version):
                    response = await client.call_tool("game_act", {
                        **self.args, "action": "npc_cancel", "parameters": {"dialogue_version": version},
                    })
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)
            for version in [1, MAX_LONG]:
                with self.subTest(version=version):
                    response = await client.call_tool("game_act", {
                        **self.args, "action": "npc_cancel", "parameters": {"dialogue_version": version},
                        "request_id": "version-" + str(version),
                    })
                    self.assertFalse(response.is_error)
                    self.assertEqual({"dialogue_version": version}, self.action_requests()[-1]["body"]["data"]["parameters"])
        self.assertEqual(2, len(self.action_requests()))

    async def test_npc_arbitrary_templates_scripts_selections_and_raw_packets_are_rejected_without_echo(self) -> None:
        nonce = "private-npc-" + uuid4().hex
        private = " ".join([nonce, PASSWORD, TOKEN])
        prohibited = ["npc_id", "npc_template_id", "oid", "script", "path", "selection", "lastMsg", "show",
                      "raw_packet", "character_id", "password"]
        async with self.stdio_client() as client:
            for action in NPC_ACTIONS:
                base = {**self.args, "action": action, "parameters": self.parameters(action)}
                cases = [{**base, key: private} for key in prohibited]
                cases += [{**base, "parameters": {**base["parameters"], key: private}} for key in prohibited]
                cases += [{**base, "expected_context": {**base["expected_context"], key: private}} for key in prohibited]
                cases += [{**base, "parameters": {"dialogue_version": {"secret": private}}},
                          {**base, "expected_context": {"scene_ref": {"secret": private},
                                                       "runtime_generation": self.stub.runtime_generation}}]
                for index, args in enumerate(cases):
                    with self.subTest(action=action, case=index):
                        response = await client.call_tool("game_act", args)
                        self.assertTrue(response.is_error)
                        for sensitive in [nonce, PASSWORD, TOKEN]:
                            self.assertNotIn(sensitive, response.model_dump_json())
                        self.assertEqual([], self.stub.requests)

    async def test_npc_common_identity_context_and_action_requirements_cannot_be_bypassed_before_http(self) -> None:
        invalid = {
            "session_id": [None, True, 1, "", "../other"],
            "expected_generation": [None, True, 1.0, "1", 0, MAX_LONG + 1],
            "request_id": [None, True, "", "unsafe/request"],
            "action": [None, True, 1, {}, "NPC_CANCEL", "npc_select", "9900001.js", "move"],
            "expected_context": [None, [], {}, {"scene_ref": "scene-valid"},
                                 {"scene_ref": 10000, "runtime_generation": self.stub.runtime_generation},
                                 {"scene_ref": "scene-valid", "runtime_generation": True}],
        }
        async with self.stdio_client() as client:
            for action in NPC_ACTIONS:
                base = {**self.args, "action": action, "parameters": self.parameters(action)}
                cases = [{key: value for key, value in base.items() if key != missing} for missing in base]
                cases += [{**base, key: value} for key, values in invalid.items() for value in values]
                for index, args in enumerate(cases):
                    with self.subTest(action=action, case=index):
                        response = await client.call_tool("game_act", args)
                        self.assertTrue(response.is_error)
                        self.assertEqual([], self.stub.requests)

    async def test_npc_action_states_and_operation_cancel_do_not_imply_the_dialogue_was_closed(self) -> None:
        async with self.stdio_client() as client:
            for action in NPC_ACTIONS:
                for status in ["QUEUED", "RUNNING", "WAITING", "CANCEL_REQUESTED", "CANCELLED", "FAILED", "INDETERMINATE", "SUCCEEDED"]:
                    with self.subTest(action=action, status=status):
                        self.stub.status = status
                        args = {**self.args, "action": action, "parameters": self.parameters(action),
                                "request_id": action + "-" + status}
                        response = await client.call_tool("game_act", args)
                        operation = await client.call_tool("operation_get", {"operation_id": self.stub.operation_id})
                        self.assertFalse(response.is_error)
                        self.assertFalse(operation.is_error)
                        self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
                        self.assertEqual(self.stub.operation(), operation.structured_content)
                        self.assertEqual("OPEN", operation.structured_content["session"]["state"])
                cancelled = await client.call_tool("operation_cancel", {
                    "operation_id": self.stub.operation_id, "request_id": action + "-cancel-request",
                })
                self.assertFalse(cancelled.is_error)
                self.assertEqual("CANCEL_REQUESTED", cancelled.structured_content["status"])
                self.assertEqual({}, cancelled.structured_content["result"])
                self.assertTrue(cancelled.structured_content["session"]["actor_current"])
        self.assertFalse(any(request["path"].endswith("/actor/close") for request in self.stub.requests))

    async def test_npc_http_failures_keep_status_and_unknown_write_ids_without_sensitive_bodies(self) -> None:
        status = 400
        private = "private-native-npc-" + uuid4().hex
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (status, {"message": private + PASSWORD + TOKEN})
        )
        async with self.stdio_client() as client:
            for action in NPC_ACTIONS:
                for status in [400, 403, 404, 409, 410, 429, 503]:
                    with self.subTest(action=action, status=status):
                        args = {**self.args, "action": action, "parameters": self.parameters(action)}
                        response = await client.call_tool("game_act", args)
                        self.assertTrue(response.is_error)
                        self.assertEqual(args["request_id"], response.structured_content["request_id"])
                        error = response.structured_content["error"]
                        self.assertEqual(status, error["http_status"])
                        self.assertEqual(status >= 500, error["outcome_unknown"])
                        self.assertFalse(error["retryable"])
                        for sensitive in [private, PASSWORD, TOKEN]:
                            self.assertNotIn(sensitive, response.model_dump_json())
        self.assertEqual(14, len(self.action_requests()))

    async def test_npc_401_refresh_preserves_the_identical_action_request_and_only_retries_once(self) -> None:
        for action in NPC_ACTIONS:
            self.stub.requests.clear()
            self.stub.login_count = self.stub.resource_count = 0

            def respond(request: dict) -> tuple[int, object]:
                if request["path"] == ACT_PATH and len(self.action_requests()) == 1:
                    return 401, {"message": PASSWORD + TOKEN}
                return self.stub.default_response(request)

            self.stub.responder = respond
            args = {**self.args, "action": action, "parameters": self.parameters(action)}
            async with self.stdio_client() as client:
                response = await client.call_tool("game_act", args)
            self.assertFalse(response.is_error)
            self.assertEqual(2, self.stub.login_count)
            requests = self.action_requests()
            self.assertEqual(2, len(requests))
            self.assertEqual(requests[0]["body"], requests[1]["body"])

    async def test_npc_timeout_and_connection_loss_do_not_replay_the_action(self) -> None:
        for action, mode in [("npc_center_open", "timeout"), ("npc_cancel", "disconnect")]:
            self.stub.requests.clear()
            self.stub.login_count = self.stub.resource_count = 0
            self.stub.disconnect_action = mode == "disconnect"

            def respond(request: dict) -> tuple[int, object]:
                if request["path"] == ACT_PATH and mode == "timeout":
                    time.sleep(0.25)
                return self.stub.default_response(request)

            self.stub.responder = respond
            args = {**self.args, "action": action, "parameters": self.parameters(action)}
            async with self.stdio_client(timeout=0.1 if mode == "timeout" else 2) as client:
                response = await client.call_tool("game_act", args)
            self.assertTrue(response.is_error)
            self.assertEqual(args["request_id"], response.structured_content["request_id"])
            self.assertTrue(response.structured_content["error"]["outcome_unknown"])
            self.assertFalse(response.structured_content["error"]["retryable"])
            self.assertEqual(1, len(self.action_requests()))

    def test_npc_capability_consumer_accepts_old_backends_and_new_three_action_backend(self) -> None:
        values = [capabilities(OBSERVATION_TOOLS), capabilities(OBSERVATION_TOOLS | ACTOR_TOOLS),
                  npc_capabilities(actions=["chat"], npc_decoder=False), npc_capabilities()]
        for value in values:
            with self.subTest(actions=value["actions"]):
                self.assertEqual(sorted(value["enabled_tools"]), require_capabilities(value, sorted(TOOLS)))
                if "game_act" in value["enabled_tools"]:
                    self.assertEqual(sorted(TOOLS), require_capabilities(value, sorted(TOOLS), actors=True, chat=True))
        for invalid_actions in [["chat", "npc_center_open", "npc_cancel", "npc_select"], ["chat", "chat"]]:
            with self.subTest(actions=invalid_actions), self.assertRaises(RuntimeError):
                require_capabilities(npc_capabilities(actions=invalid_actions), sorted(TOOLS))
        mismatched = npc_capabilities()
        mismatched["actor_control"]["actions"] = ["chat"]
        with self.assertRaises(RuntimeError):
            require_capabilities(mismatched, sorted(TOOLS))

    async def test_npc_capabilities_add_only_type_four_decoder_and_preserve_original_packet_types(self) -> None:
        async with self.stdio_client() as client:
            response = await client.call_tool("game_capabilities", {})
        self.assertFalse(response.is_error)
        self.assertEqual(npc_capabilities(), response.structured_content)
        actual = require_packet_capability(response.structured_content)
        self.assertEqual(PACKET_CAPABILITIES["decoded_packets"], actual["decoded_packets"][:3])
        self.assertEqual(NPC_DECODER, actual["decoded_packets"][3])
        self.assertFalse(response.structured_content["event_stream"])
        self.assertEqual([], response.structured_content["recipes"])
        self.assertEqual(PACKET_CAPABILITIES,
                         require_packet_capability(npc_capabilities(actions=["chat"], npc_decoder=False)))
        for types in [[True], [4.0], ["4"], [], [0], [4, 5]]:
            invalid = npc_capabilities()
            invalid["actor_control"]["packet_observation"]["decoded_packets"][-1]["message_types"] = types
            with self.subTest(types=types), self.assertRaises(RuntimeError):
                require_packet_capability(invalid)
