from __future__ import annotations

import asyncio
from copy import deepcopy
import time
import unittest
from urllib.parse import urlsplit

from mcp import Client

from solo_acceptance_mcp.bridge import MAX_COMMAND_CHARACTERS
from solo_acceptance_mcp.server import build_server
from tests import test_chat_protocol as chat_protocol
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


COMMAND_PATH = "/solo/acceptance/v1/command/execute"
COMMAND_GENERATION = "command-process-fixture"


class CommandProtocolStub(BridgeStub):
    """Transport fixture only; native permissions and command effects are not simulated."""

    def __init__(self) -> None:
        self.status = "RETURNED"
        super().__init__()

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == COMMAND_PATH:
            invoked = None if self.status == "RUNNING" else self.status in {"UNKNOWN", "RETURNED"}
            returned = None if self.status == "RUNNING" else self.status == "RETURNED"
            return 200, envelope({
                "command_id": "command-fixture", "request_id": request["body"]["requestId"],
                "command_generation": COMMAND_GENERATION, "status": self.status,
                "native_handler_invoked": invoked, "native_handler_returned": returned,
                "command_outcome": "UNREPORTED", "protocol_fixture": True,
            })
        return super().default_response(request)


class CommandProtocolTests(unittest.IsolatedAsyncioTestCase):
    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = CommandProtocolStub().start()
        self.args = {"profile_id": "primary", "request_id": "command:protocol-fixture",
                     "command": "@time  Unchanged ARG", "expected_command_generation": COMMAND_GENERATION}

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    def command_requests(self) -> list[dict]:
        return [request for request in self.stub.requests if request["path"] == COMMAND_PATH]

    async def test_stdio_registers_independent_command_with_strict_text_schema(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(chat_protocol.REGISTERED_TOOLS, set(tools))
        command_tool = tools["game_command"]
        self.assertFalse(command_tool.annotations.read_only_hint)
        self.assertTrue(command_tool.annotations.destructive_hint)
        self.assertTrue(command_tool.annotations.idempotent_hint)
        schema = command_tool.input_schema
        self.assertEqual(set(self.args), set(schema["properties"]))
        self.assertEqual(set(self.args), set(schema["required"]))
        self.assertIs(False, schema["additionalProperties"])
        self.assertEqual("string", schema["properties"]["command"]["type"])
        self.assertEqual(1, schema["properties"]["command"]["minLength"])
        self.assertEqual(MAX_COMMAND_CHARACTERS, schema["properties"]["command"]["maxLength"])
        self.assertNotIn("const", schema["properties"]["command"])
        self.assertNotIn("enum", schema["properties"]["command"])
        for retired in ["parameters", "session_id", "expected_context"]:
            self.assertNotIn(retired, schema["properties"])
        self.assertIn("RETURNED", command_tool.description)
        self.assertEqual([], self.stub.requests)

    async def test_command_uses_native_route_jwt_and_original_envelope(self) -> None:
        async with self.stdio_client() as client:
            value = await client.call_tool("game_command", self.args)
        self.assertFalse(value.is_error)
        self.assertNotIn(PASSWORD, value.model_dump_json())
        self.assertNotIn(TOKEN, value.model_dump_json())
        self.assertEqual(1, len(self.command_requests()))
        request = self.command_requests()[0]
        self.assertEqual("POST", request["method"])
        self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
        self.assertEqual({"requestId": self.args["request_id"],
                          "data": {key: value for key, value in self.args.items() if key != "request_id"}}, request["body"])
        self.assertEqual(self.args["request_id"], value.structured_content["request_id"])
        self.assertEqual(1, self.stub.login_count)

    async def test_any_command_name_prefix_and_spacing_are_forwarded_verbatim(self) -> None:
        commands = ["!item 2000000 1", "/warp 100000000", "@custom Foo  BAR", "give", " @time  A "]
        async with self.stdio_client() as client:
            for command in commands:
                response = await client.call_tool("game_command", {**self.args, "command": command})
                self.assertFalse(response.is_error)
                self.assertEqual("RETURNED", response.structured_content["status"])
                self.assertEqual("UNREPORTED", response.structured_content["command_outcome"])
        self.assertEqual(commands, [request["body"]["data"]["command"] for request in self.command_requests()])

    async def test_unicode_character_boundary_is_preserved_without_utf16_or_prefix_restrictions(self) -> None:
        command = "😀" * MAX_COMMAND_CHARACTERS
        async with Client(build_server(self.stub.config())) as client:
            response = await client.call_tool("game_command", {**self.args, "command": command})
        self.assertFalse(response.is_error)
        self.assertEqual("RETURNED", response.structured_content["status"])
        self.assertEqual(command, self.command_requests()[0]["body"]["data"]["command"])

    async def test_invalid_command_arguments_are_rejected_before_auth_without_echoing_input(self) -> None:
        private = PASSWORD + TOKEN + "private-command-input"
        cases = [{key: value for key, value in self.args.items() if key != missing} for missing in self.args]
        cases.extend({**self.args, key: private} for key in ["player_id", "world_id", "session_id", "raw_packet"])
        cases.extend({**self.args, "command": value} for value in [None, False, 1, [], {}, "", " \n\t",
                      "x" * (MAX_COMMAND_CHARACTERS + 1), {"private": private}, "\ud800", "\udfff"])
        cases.extend({**self.args, "expected_command_generation": value}
                     for value in [None, True, 1, [], {}, "", ".", "..", "old/process", private + "\n"])
        cases.extend({**self.args, "profile_id": value} for value in [None, True, 1, "", "..", "normal/player", private])
        cases.extend({**self.args, "request_id": value} for value in [None, True, 1, "", "request/id", private + "\n"])
        # The old structured give shape is retired, not silently translated.
        cases.extend([{**self.args, "parameters": {"item_id": 2000000, "quantity": 1}},
                      {**self.args, "command": "give", "parameters": {"item_id": 2000000, "quantity": 1}}])
        async with Client(build_server(self.stub.config())) as client:
            for index, arguments in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("game_command", arguments)
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
                    self.assertNotIn(PASSWORD, response.model_dump_json())
                    self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertEqual([], self.stub.requests)


    async def test_statuses_and_native_return_facts_do_not_infer_success_or_deduplicate_locally(self) -> None:
        statuses = ["RUNNING", "FAILED", "REJECTED", "UNKNOWN", "RETURNED"]
        async with Client(build_server(self.stub.config())) as client:
            for status in statuses:
                self.stub.status = status
                response = await client.call_tool("game_command", self.args)
                self.assertFalse(response.is_error)
                data = response.structured_content
                self.assertEqual(status, data["status"])
                self.assertEqual("UNREPORTED", data["command_outcome"])
                self.assertNotIn("SUCCEEDED", data.values())
                self.assertNotIn("native_dispatched", data)
                self.assertNotIn("result", data)
        self.assertEqual(len(statuses), len(self.command_requests()))
        self.assertTrue(all(request["body"]["requestId"] == self.args["request_id"]
                            for request in self.command_requests()))

    async def test_forbidden_conflict_and_disabled_responses_are_safe_and_never_automatically_replayed(self) -> None:
        for status, code in [(403, "ACCESS_DENIED"), (409, "COMMAND_CONFLICT"), (404, "BRIDGE_UNAVAILABLE")]:
            self.stub.requests.clear()

            def respond(request: dict) -> tuple[int, object]:
                if request["path"] == COMMAND_PATH:
                    return status, {"message": PASSWORD + TOKEN}
                return self.stub.default_response(request)

            self.stub.responder = respond
            async with Client(build_server(self.stub.config())) as client:
                response = await client.call_tool("game_command", self.args)
            self.assertTrue(response.is_error)
            self.assertEqual(code, response.structured_content["error"]["code"])
            self.assertFalse(response.structured_content["error"]["retryable"])
            self.assertFalse(response.structured_content["error"]["outcome_unknown"])
            self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
            self.assertNotIn(PASSWORD, response.model_dump_json())
            self.assertNotIn(TOKEN, response.model_dump_json())
            self.assertEqual(1, len(self.command_requests()))

    async def test_timeout_preserves_request_id_and_generation_without_replaying_command(self) -> None:
        def respond(request: dict) -> tuple[int, object]:
            if request["path"] == COMMAND_PATH:
                time.sleep(0.25)
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client(timeout=0.1) as client:
            response = await client.call_tool("game_command", self.args)
        self.assertTrue(response.is_error)
        self.assertEqual("HTTP_TIMEOUT", response.structured_content["error"]["code"])
        self.assertFalse(response.structured_content["error"]["retryable"])
        self.assertTrue(response.structured_content["error"]["outcome_unknown"])
        self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
        self.assertEqual(1, len(self.command_requests()))
        self.assertEqual(COMMAND_GENERATION, self.command_requests()[0]["body"]["data"]["expected_command_generation"])

    async def test_mismatched_response_request_id_is_unknown_without_replay(self) -> None:
        def respond(request: dict) -> tuple[int, object]:
            status, response = self.stub.default_response(request)
            if request["path"] == COMMAND_PATH:
                response = deepcopy(response)
                response["data"]["request_id"] = "unexpected-response-id"
            return status, response

        self.stub.responder = respond
        async with Client(build_server(self.stub.config())) as client:
            response = await client.call_tool("game_command", self.args)
        self.assertTrue(response.is_error)
        self.assertEqual("INVALID_RESPONSE", response.structured_content["error"]["code"])
        self.assertTrue(response.structured_content["error"]["outcome_unknown"])
        self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
        self.assertEqual(1, len(self.command_requests()))
