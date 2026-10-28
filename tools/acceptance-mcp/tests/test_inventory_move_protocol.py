from __future__ import annotations

import asyncio
from copy import deepcopy
import time
import unittest
from urllib.parse import urlsplit
from uuid import uuid4

from solo_acceptance_mcp.smoke import require_capabilities
from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_chat_protocol import ACT_PATH, TOOLS, REGISTERED_ACTIONS, ChatProtocolStub, capabilities
from tests.test_npc_protocol import npc_capabilities
from tests.test_npc_reply_protocol import reply_capabilities


ACTIONS = ["chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move"]


def inventory_capabilities() -> dict:
    value = reply_capabilities()
    value["actions"] = ACTIONS[:]
    value["actor_control"]["actions"] = ACTIONS[:]
    return value


class InventoryProtocolStub(ChatProtocolStub):
    """A local HTTP boundary; no native inventory or persistence is simulated."""

    def operation(self, status: str | None = None) -> dict:
        value = super().operation(status)
        value["action"] = "inventory_move"
        value["result"] = {"protocol_fixture": True, "native_dispatch_started": value["status"] == "SUCCEEDED"}
        value["error"] = None
        return value

    def default_response(self, request: dict) -> tuple[int, object]:
        if urlsplit(request["path"]).path == "/solo/acceptance/v1/capabilities":
            return 200, envelope(inventory_capabilities())
        return super().default_response(request)


class InventoryMoveProtocolTests(unittest.IsolatedAsyncioTestCase):
    stdio_client = chat_protocol.ChatProtocolTests.stdio_client
    action_requests = chat_protocol.ChatProtocolTests.action_requests

    async def asyncSetUp(self) -> None:
        self.stub = InventoryProtocolStub().start()
        self.args = {
            "session_id": self.stub.session_id, "expected_generation": 7,
            "request_id": "inventory:" + uuid4().hex, "action": "inventory_move",
            "parameters": {"inventory_type": "USE", "source_slot": 1, "target_slot": 2},
            "expected_context": {"scene_ref": "scene-" + uuid4().hex,
                                 "runtime_generation": self.stub.runtime_generation},
        }

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_schema_binds_inventory_action_to_exact_typed_parameters(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(TOOLS, set(tools))
        schema = tools["game_act"].input_schema
        self.assertEqual(REGISTERED_ACTIONS, schema["properties"]["action"]["enum"])
        branch = next(branch for branch in schema["allOf"][0]["oneOf"]
                      if branch["properties"]["action"]["const"] == "inventory_move")
        model = schema["$defs"][branch["properties"]["parameters"]["$ref"].split("/")[-1]]
        self.assertIs(False, model["additionalProperties"])
        self.assertEqual(set(self.args["parameters"]), set(model["properties"]))
        self.assertEqual(set(self.args["parameters"]), set(model["required"]))
        self.assertEqual(["EQUIP", "USE", "SETUP", "ETC", "CASH"], model["properties"]["inventory_type"]["enum"])
        for slot in ["source_slot", "target_slot"]:
            self.assertEqual("integer", model["properties"][slot]["type"])
            self.assertEqual((1, 127), (model["properties"][slot]["minimum"], model["properties"][slot]["maximum"]))
        self.assertEqual([], self.stub.requests)

    async def test_each_inventory_type_and_slot_boundaries_preserve_request_context_and_jwt(self) -> None:
        async with self.stdio_client() as client:
            for index, inventory_type in enumerate(["EQUIP", "USE", "SETUP", "ETC", "CASH"]):
                args = {**self.args, "request_id": "inventory:type-" + str(index),
                        "parameters": {"inventory_type": inventory_type, "source_slot": 1, "target_slot": 127}}
                response = await client.call_tool("game_act", args)
                self.assertFalse(response.is_error)
                self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
                request = self.action_requests()[-1]
                self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
                self.assertEqual({"requestId": args["request_id"],
                                  "data": {key: value for key, value in args.items() if key != "request_id"}}, request["body"])
                self.assertNotIn(PASSWORD, response.model_dump_json())
                self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertEqual(5, len(self.action_requests()))
        self.assertEqual(1, self.stub.login_count)

    async def test_invalid_inventory_parameters_are_rejected_before_authentication_without_echoing_input(self) -> None:
        valid = self.args["parameters"]
        private = PASSWORD + TOKEN + "private-inventory-payload"
        cases = [None, False, [], "parameters", {}, {"text": "chat"}, {"dialogue_version": 29},
                 {**valid, "character_id": private}, {**valid, "raw_packet": private},
                 {**valid, "source_slot": 2}]
        cases.extend({key: value for key, value in valid.items() if key != missing} for missing in valid)
        cases.extend({**valid, "inventory_type": value}
                     for value in [None, False, 1, [], {}, "use", "EQUIPPED", "USE ", private])
        for slot in ["source_slot", "target_slot"]:
            cases.extend({**valid, slot: value} for value in [None, False, True, 0, -1, 128, 1.0, "1", [], {}, private])
        async with self.stdio_client() as client:
            for index, parameters in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("game_act", {**self.args, "parameters": parameters})
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    for secret in [PASSWORD, TOKEN, private]:
                        self.assertNotIn(secret, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_inventory_action_does_not_accept_other_action_parameters_or_missing_context(self) -> None:
        cases = [{**self.args, "action": action} for action in ["chat", "npc_center_open", "npc_cancel", "npc_reply"]]
        cases.extend({key: value for key, value in self.args.items() if key != missing}
                     for missing in ["request_id", "expected_context", "session_id", "expected_generation"])
        cases.extend({**self.args, "expected_context": value} for value in [
            None, {}, {"scene_ref": "scene-only"}, {**self.args["expected_context"], "map_id": 10000}])
        async with self.stdio_client() as client:
            for index, args in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("game_act", args)
                    self.assertTrue(response.is_error)
        self.assertEqual([], self.stub.requests)

    async def test_native_statuses_and_results_are_forwarded_without_local_success_or_cache(self) -> None:
        statuses = ["QUEUED", "RUNNING", "CANCEL_REQUESTED", "CANCELLED", "FAILED", "INDETERMINATE", "SUCCEEDED"]
        async with self.stdio_client() as client:
            for status in statuses:
                self.stub.status = status
                response = await client.call_tool("game_act", self.args)
                self.assertFalse(response.is_error)
                self.assertEqual(status, response.structured_content["status"])
                self.assertEqual(self.stub.operation()["result"], response.structured_content["result"])
        self.assertEqual(len(statuses), len(self.action_requests()))

    async def test_timeout_and_connection_loss_preserve_original_request_id_without_replaying_mutation(self) -> None:
        for mode in ["timeout", "disconnect"]:
            self.stub.requests.clear()
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

    async def test_authenticated_401_refresh_keeps_identical_inventory_request_once(self) -> None:
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

    async def test_registered_inventory_schema_does_not_promote_old_backend_capabilities(self) -> None:
        self.stub.responder = lambda request: (
            (200, envelope(reply_capabilities())) if urlsplit(request["path"]).path == "/solo/acceptance/v1/capabilities"
            else self.stub.default_response(request))
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
            response = await client.call_tool("game_capabilities", {})
        self.assertIn("inventory_move", tools["game_act"].input_schema["properties"]["action"]["enum"])
        self.assertNotIn("inventory_move", response.structured_content["actions"])

    def test_capability_consumer_accepts_new_inventory_and_preserves_historical_action_sets(self) -> None:
        for value in [capabilities(TOOLS), npc_capabilities(), reply_capabilities(), inventory_capabilities()]:
            with self.subTest(actions=value["actions"]):
                self.assertEqual(sorted(TOOLS), require_capabilities(value, sorted(TOOLS), actors=True))
        invalid = inventory_capabilities()
        invalid["actions"].append("inventory_move")
        with self.assertRaises(RuntimeError):
            require_capabilities(invalid, sorted(TOOLS))
        mismatched = deepcopy(inventory_capabilities())
        mismatched["actor_control"]["actions"] = ACTIONS[:-1]
        with self.assertRaises(RuntimeError):
            require_capabilities(mismatched, sorted(TOOLS))
