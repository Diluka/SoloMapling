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
from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_actor_events_smoke import PACKET_CAPABILITIES
from tests.test_chat_protocol import ACTOR_TOOLS, ACT_PATH, MAX_LONG, OBSERVATION_TOOLS, TOOLS, capabilities
from tests.test_npc_protocol import ACTIONS as CENTER_ACTIONS, NpcProtocolStub, npc_capabilities


ACTIONS = [*CENTER_ACTIONS, "npc_reply"]
MAX_INT = 2**31 - 1


def reply_capabilities() -> dict:
    value = npc_capabilities(actions=ACTIONS)
    value["actor_control"]["packet_observation"]["decoded_packets"][-1]["message_types"] = [4, 12]
    value["actor_control"]["npc_dialogue"] = {"reply_kinds": ["select", "decline"]}
    return value


class NpcReplyProtocolStub(NpcProtocolStub):
    """Opaque native outcomes come from local HTTP; no menus or scripts execute."""

    def operation(self, status: str | None = None) -> dict:
        value = super().operation(status)
        if self.current_action != "npc_reply":
            return value
        # The protocol preserves backend facts without inventing dialogue ABI.
        value["result"] = {"native_dispatch_started": True} if value["status"] == "SUCCEEDED" else {}
        value["error"] = {"reason": "fixture_native_failure"} if value["status"] in {"FAILED", "INDETERMINATE"} else None
        return value

    def default_response(self, request: dict) -> tuple[int, object]:
        if urlsplit(request["path"]).path == "/solo/acceptance/v1/capabilities":
            return 200, envelope(reply_capabilities())
        return super().default_response(request)


class NpcReplyProtocolTests(unittest.IsolatedAsyncioTestCase):
    stdio_client = chat_protocol.ChatProtocolTests.stdio_client
    action_requests = chat_protocol.ChatProtocolTests.action_requests

    async def asyncSetUp(self) -> None:
        self.stub = NpcReplyProtocolStub().start()
        self.args = {
            "session_id": self.stub.session_id, "expected_generation": 7,
            "request_id": "reply:" + uuid4().hex, "action": "npc_reply",
            "parameters": {"dialogue_version": 29, "kind": "select", "selection": 0},
            "expected_context": {"scene_ref": "scene-" + uuid4().hex,
                                 "runtime_generation": self.stub.runtime_generation},
        }

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_reply_seven_tools_schema_preserves_two_strict_kind_branches(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(TOOLS, set(tools))
        tool = tools["game_act"]
        self.assertFalse(tool.annotations.read_only_hint)
        self.assertFalse(tool.annotations.destructive_hint)
        self.assertTrue(tool.annotations.idempotent_hint)
        self.assertTrue(tool.annotations.open_world_hint)
        schema = tool.input_schema
        self.assertIs(False, schema.get("additionalProperties"))
        self.assertEqual(ACTIONS + ["inventory_move"], schema["properties"]["action"]["enum"])
        self.assertEqual(set(self.args), set(schema["required"]))
        tagged = schema["allOf"][0]["oneOf"]
        self.assertEqual(5, len(tagged))
        by_action = {branch["properties"]["action"]["const"]: branch["properties"]["parameters"] for branch in tagged}
        self.assertEqual(set(ACTIONS) | {"inventory_move"}, set(by_action))

        def resolve(value: dict) -> dict:
            return schema["$defs"][value["$ref"].split("/")[-1]] if "$ref" in value else value

        reply = resolve(by_action["npc_reply"])
        variants = reply.get("oneOf", reply.get("anyOf", []))
        self.assertEqual(2, len(variants))
        branches = [resolve(variant) for variant in variants]
        by_kind = {branch["properties"]["kind"]["const"]: branch for branch in branches}
        self.assertEqual({"select", "decline"}, set(by_kind))
        for kind, branch in by_kind.items():
            expected = {"dialogue_version", "kind", "selection"} if kind == "select" else {"dialogue_version", "kind"}
            self.assertEqual(expected, set(branch["properties"]))
            self.assertEqual(expected, set(branch["required"]))
            self.assertIs(False, branch.get("additionalProperties"))
            version = branch["properties"]["dialogue_version"]
            self.assertEqual((1, MAX_LONG), (version["minimum"], version["maximum"]))
        selection = by_kind["select"]["properties"]["selection"]
        self.assertEqual((0, MAX_INT), (selection["minimum"], selection["maximum"]))
        self.assertEqual([], self.stub.requests)

    async def test_reply_select_and_decline_preserve_exact_jwt_envelope_and_native_operation(self) -> None:
        async with self.stdio_client() as client:
            for kind in ["select", "decline"]:
                parameters = {"dialogue_version": 29, "kind": kind}
                if kind == "select":
                    parameters["selection"] = 0
                args = {**self.args, "request_id": "reply-" + kind, "parameters": parameters}
                response = await client.call_tool("game_act", args)
                self.assertFalse(response.is_error)
                self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
                self.assertEqual(response.structured_content, json.loads(response.content[0].text))
                request = self.action_requests()[-1]
                self.assertEqual("POST", request["method"])
                self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
                self.assertEqual({"requestId": args["request_id"],
                                  "data": {key: value for key, value in args.items() if key != "request_id"}}, request["body"])
        self.assertEqual(2, len(self.action_requests()))
        self.assertEqual(1, self.stub.login_count)

    async def test_reply_kind_and_parameter_branches_reject_accept_and_cross_action_objects_before_http(self) -> None:
        invalid = [None, True, [], "reply", {}, {"dialogue_version": 29},
                   {"kind": "select", "selection": 0}, {"dialogue_version": 29, "kind": "select"},
                   {"dialogue_version": 29, "kind": "decline", "selection": None},
                   {"dialogue_version": 29, "kind": "decline", "selection": 0},
                   {"text": "safe"}]
        invalid += [{"dialogue_version": 29, "kind": kind} for kind in [None, True, 0, [], {}, "accept", "SELECT", "cancel", ""]]
        async with self.stdio_client() as client:
            for parameters in invalid:
                with self.subTest(parameters=parameters):
                    response = await client.call_tool("game_act", {**self.args, "parameters": parameters})
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)
            for action in CENTER_ACTIONS:
                with self.subTest(action=action):
                    response = await client.call_tool("game_act", {**self.args, "action": action})
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)

    async def test_reply_version_and_selection_are_strict_with_all_legal_numeric_boundaries(self) -> None:
        invalid = {
            "dialogue_version": [None, True, False, 1.0, "1", 0, -1, MAX_LONG + 1, [], {}],
            "selection": [None, True, False, 0.0, "0", -1, MAX_INT + 1, MAX_LONG, [], {}],
        }
        async with self.stdio_client() as client:
            for field, values in invalid.items():
                for value in values:
                    with self.subTest(field=field, value=value):
                        response = await client.call_tool("game_act", {
                            **self.args, "parameters": {**self.args["parameters"], field: value},
                        })
                        self.assertTrue(response.is_error)
                        self.assertEqual([], self.stub.requests)
            for version, selection in [(1, 0), (MAX_LONG, MAX_INT)]:
                parameters = {"dialogue_version": version, "kind": "select", "selection": selection}
                response = await client.call_tool("game_act", {**self.args, "parameters": parameters})
                self.assertFalse(response.is_error)
                self.assertEqual(parameters, self.action_requests()[-1]["body"]["data"]["parameters"])
        self.assertEqual(2, len(self.action_requests()))

    async def test_reply_extra_fields_and_invalid_nested_objects_are_rejected_without_sensitive_echo(self) -> None:
        nonce = "private-reply-" + uuid4().hex
        private = " ".join([nonce, PASSWORD, TOKEN])
        prohibited = ["text", "npc_template_id", "script", "path", "lastMsg", "action_byte", "raw_packet", "character_id", "password"]
        cases = [{**self.args, key: private} for key in prohibited]
        cases += [{**self.args, "parameters": {**self.args["parameters"], key: private}} for key in prohibited]
        cases += [{**self.args, "expected_context": {**self.args["expected_context"], key: private}} for key in prohibited]
        cases += [{**self.args, "parameters": {"dialogue_version": 29, "kind": {"secret": private}, "selection": 0}},
                  {**self.args, "parameters": {**self.args["parameters"], "selection": {"secret": private}}},
                  {**self.args, "expected_context": {"scene_ref": {"secret": private},
                                                   "runtime_generation": self.stub.runtime_generation}}]
        async with self.stdio_client() as client:
            for index, args in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("game_act", args)
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    for sensitive in [nonce, PASSWORD, TOKEN]:
                        self.assertNotIn(sensitive, response.model_dump_json())
                    self.assertEqual([], self.stub.requests)

    async def test_reply_common_session_and_context_requirements_cannot_be_bypassed_before_http(self) -> None:
        invalid = {
            "session_id": [None, True, 1, "", "../other"],
            "expected_generation": [None, True, 1.0, "1", 0, MAX_LONG + 1],
            "request_id": [None, True, "", "unsafe/request"],
            "action": [None, True, 1, {}, "NPC_REPLY", "npc_accept", "npc_select"],
            "expected_context": [None, [], {}, {"scene_ref": "scene-valid"},
                                 {"scene_ref": 10000, "runtime_generation": self.stub.runtime_generation},
                                 {"scene_ref": "scene-valid", "runtime_generation": True}],
        }
        cases = [{key: value for key, value in self.args.items() if key != missing} for missing in self.args]
        cases += [{**self.args, key: value} for key, values in invalid.items() for value in values]
        async with self.stdio_client() as client:
            for index, args in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("game_act", args)
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)

    async def test_reply_pending_cancel_failed_and_unknown_states_preserve_original_native_facts(self) -> None:
        async with self.stdio_client() as client:
            for status in ["QUEUED", "RUNNING", "WAITING", "CANCEL_REQUESTED", "CANCELLED", "FAILED", "INDETERMINATE", "SUCCEEDED"]:
                with self.subTest(status=status):
                    self.stub.status = status
                    args = {**self.args, "request_id": "reply-state-" + status}
                    response = await client.call_tool("game_act", args)
                    operation = await client.call_tool("operation_get", {"operation_id": self.stub.operation_id})
                    self.assertFalse(response.is_error)
                    self.assertFalse(operation.is_error)
                    self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
                    self.assertEqual(self.stub.operation(), operation.structured_content)
                    self.assertEqual("OPEN", operation.structured_content["session"]["state"])
            cancelled = await client.call_tool("operation_cancel", {
                "operation_id": self.stub.operation_id, "request_id": "reply-cancel-operation",
            })
        self.assertFalse(cancelled.is_error)
        self.assertEqual("CANCEL_REQUESTED", cancelled.structured_content["status"])
        self.assertEqual({}, cancelled.structured_content["result"])
        self.assertTrue(cancelled.structured_content["session"]["actor_current"])

    async def test_reply_http_errors_preserve_request_id_status_and_unknown_outcome_without_body(self) -> None:
        status = 400
        private = "reply-backend-" + uuid4().hex
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (status, {"message": private + PASSWORD + TOKEN})
        )
        async with self.stdio_client() as client:
            for status in [400, 403, 404, 409, 410, 429, 503]:
                with self.subTest(status=status):
                    response = await client.call_tool("game_act", self.args)
                    self.assertTrue(response.is_error)
                    self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
                    error = response.structured_content["error"]
                    self.assertEqual(status, error["http_status"])
                    self.assertEqual(status >= 500, error["outcome_unknown"])
                    self.assertFalse(error["retryable"])
                    for sensitive in [private, PASSWORD, TOKEN]:
                        self.assertNotIn(sensitive, response.model_dump_json())
        self.assertEqual(7, len(self.action_requests()))

    async def test_reply_401_refresh_retries_identical_id_and_parameters_only_once(self) -> None:
        def respond(request: dict) -> tuple[int, object]:
            if request["path"] == ACT_PATH and len(self.action_requests()) == 1:
                return 401, {"message": PASSWORD + TOKEN}
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client() as client:
            response = await client.call_tool("game_act", self.args)
        self.assertFalse(response.is_error)
        self.assertEqual(2, self.stub.login_count)
        requests = self.action_requests()
        self.assertEqual(2, len(requests))
        self.assertEqual(requests[0]["body"], requests[1]["body"])
        self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_reply_timeout_and_connection_loss_do_not_replay_the_selection(self) -> None:
        for mode in ["timeout", "disconnect"]:
            self.stub.requests.clear()
            self.stub.login_count = self.stub.resource_count = 0
            self.stub.disconnect_action = mode == "disconnect"

            def respond(request: dict) -> tuple[int, object]:
                if request["path"] == ACT_PATH and mode == "timeout":
                    time.sleep(0.25)
                return self.stub.default_response(request)

            self.stub.responder = respond
            async with self.stdio_client(timeout=0.1 if mode == "timeout" else 2) as client:
                response = await client.call_tool("game_act", self.args)
            self.assertTrue(response.is_error)
            self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
            self.assertTrue(response.structured_content["error"]["outcome_unknown"])
            self.assertFalse(response.structured_content["error"]["retryable"])
            self.assertEqual(1, len(self.action_requests()))

    def test_reply_capability_helper_accepts_each_old_tool_subset_and_new_four_action_backend(self) -> None:
        values = [capabilities(OBSERVATION_TOOLS), capabilities(OBSERVATION_TOOLS | ACTOR_TOOLS),
                  npc_capabilities(actions=["chat"], npc_decoder=False), npc_capabilities(), reply_capabilities()]
        for value in values:
            with self.subTest(actions=value["actions"]):
                self.assertEqual(sorted(value["enabled_tools"]), require_capabilities(value, sorted(TOOLS)))
                if "game_act" in value["enabled_tools"]:
                    self.assertEqual(sorted(TOOLS), require_capabilities(value, sorted(TOOLS), actors=True, chat=True))
        for actions in [ACTIONS + ["npc_accept"], ACTIONS + ["npc_reply"], ["chat", "npc_reply"]]:
            invalid = reply_capabilities()
            invalid["actions"] = actions
            invalid["actor_control"]["actions"] = actions[:]
            with self.subTest(actions=actions), self.assertRaises(RuntimeError):
                require_capabilities(invalid, sorted(TOOLS))

    async def test_reply_capabilities_preserve_type_four_and_twelve_and_only_select_decline_kinds(self) -> None:
        async with self.stdio_client() as client:
            response = await client.call_tool("game_capabilities", {})
        self.assertFalse(response.is_error)
        self.assertEqual(reply_capabilities(), response.structured_content)
        self.assertEqual(["select", "decline"], response.structured_content["actor_control"]["npc_dialogue"]["reply_kinds"])
        packet = require_packet_capability(response.structured_content)
        self.assertEqual(PACKET_CAPABILITIES["decoded_packets"], packet["decoded_packets"][:3])
        self.assertEqual({"event_type": "npc_dialogue", "opcode": 304, "message_types": [4, 12]}, packet["decoded_packets"][3])
        self.assertEqual([4], require_packet_capability(npc_capabilities())["decoded_packets"][-1]["message_types"])
        for types in [[4, True], [4, 12.0], [4, "12"], [12], [4, 4], [4, 12, 0], []]:
            invalid = reply_capabilities()
            invalid["actor_control"]["packet_observation"]["decoded_packets"][-1]["message_types"] = types
            with self.subTest(types=types), self.assertRaises(RuntimeError):
                require_packet_capability(invalid)

    async def test_reply_schema_does_not_invent_reply_capabilities_on_the_old_center_backend(self) -> None:
        old = npc_capabilities()
        self.stub.responder = lambda request: (
            (200, envelope(old)) if request["path"].endswith("/capabilities")
            else self.stub.default_response(request)
        )
        async with self.stdio_client() as client:
            response = await client.call_tool("game_capabilities", {})
        self.assertFalse(response.is_error)
        self.assertEqual(old, response.structured_content)
        self.assertNotIn("npc_reply", response.structured_content["actions"])
        self.assertNotIn("npc_dialogue", response.structured_content["actor_control"])
        self.assertEqual([], self.action_requests())
