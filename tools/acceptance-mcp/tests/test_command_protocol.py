from __future__ import annotations

import asyncio
from copy import deepcopy
import time
import unittest
from urllib.parse import urlsplit

from mcp import Client

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from solo_acceptance_mcp.server import build_server
from tests import test_chat_protocol as chat_protocol
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


HELP_PATH = "/solo/acceptance/v1/command/help"
COMMAND_PATH = "/solo/acceptance/v1/command/execute"
COMMAND_GENERATION = "command-process-fixture"


class CommandProtocolStub(BridgeStub):
    """HTTP transport fixture only; native give and persistence are not simulated."""

    def __init__(self) -> None:
        self.status = "SUCCEEDED"
        super().__init__()

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == HELP_PATH:
            return 200, envelope({
                "command_generation": COMMAND_GENERATION,
                "commands": [{"name": "give", "parameters": ["item_id", "quantity"],
                              "inventory_types": ["USE", "ETC"]}],
            })
        if path == COMMAND_PATH:
            return 200, envelope({
                "command_id": "command-fixture", "request_id": request["body"]["requestId"],
                "command_generation": COMMAND_GENERATION, "status": self.status,
                "native_dispatched": self.status != "RUNNING", "protocol_fixture": True,
                "result": {"before_quantity": 2, "after_quantity": 3} if self.status == "SUCCEEDED" else {},
            })
        return super().default_response(request)


class CommandProtocolTests(unittest.IsolatedAsyncioTestCase):
    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = CommandProtocolStub().start()
        self.args = {
            "profile_id": "primary", "request_id": "give:protocol-fixture", "command": "give",
            "parameters": {"item_id": 2000000, "quantity": 1},
            "expected_command_generation": COMMAND_GENERATION,
        }

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    def command_requests(self) -> list[dict]:
        return [request for request in self.stub.requests if request["path"] == COMMAND_PATH]

    async def test_stdio_registers_two_independent_tools_with_strict_command_schema(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(chat_protocol.TOOLS, set(tools))
        help_tool = tools["game_command_help"]
        self.assertTrue(help_tool.annotations.read_only_hint)
        self.assertTrue(help_tool.annotations.idempotent_hint)
        self.assertFalse(help_tool.annotations.destructive_hint)
        self.assertEqual({"command"}, set(help_tool.input_schema["properties"]))
        self.assertIs(False, help_tool.input_schema["additionalProperties"])
        command_tool = tools["game_command"]
        self.assertFalse(command_tool.annotations.read_only_hint)
        self.assertTrue(command_tool.annotations.idempotent_hint)
        schema = command_tool.input_schema
        self.assertEqual(set(self.args), set(schema["properties"]))
        self.assertEqual(set(self.args), set(schema["required"]))
        self.assertIs(False, schema["additionalProperties"])
        model = schema["$defs"][schema["properties"]["parameters"]["$ref"].split("/")[-1]]
        self.assertIs(False, model["additionalProperties"])
        self.assertEqual({"item_id", "quantity"}, set(model["required"]))
        self.assertEqual((1, 32767), (model["properties"]["quantity"]["minimum"],
                                    model["properties"]["quantity"]["maximum"]))
        self.assertNotIn("session_id", schema["properties"])
        self.assertNotIn("expected_context", schema["properties"])
        self.assertEqual([], self.stub.requests)

    async def test_help_and_command_use_native_routes_jwt_and_original_envelope(self) -> None:
        async with self.stdio_client() as client:
            directory = await client.call_tool("game_command_help", {})
            help_result = await client.call_tool("game_command_help", {"command": "give"})
            response = await client.call_tool("game_command", self.args)
        for value in [directory, help_result, response]:
            self.assertFalse(value.is_error)
            self.assertNotIn(PASSWORD, value.model_dump_json())
            self.assertNotIn(TOKEN, value.model_dump_json())
        self.assertEqual(COMMAND_GENERATION, help_result.structured_content["command_generation"])
        help_requests = [request for request in self.stub.requests if request["path"].startswith(HELP_PATH)]
        self.assertEqual([HELP_PATH, HELP_PATH + "?command=give"], [request["path"] for request in help_requests])
        self.assertTrue(all(request["method"] == "GET" for request in help_requests))
        self.assertEqual(1, len(self.command_requests()))
        request = self.command_requests()[0]
        self.assertEqual("POST", request["method"])
        self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
        self.assertEqual({"requestId": self.args["request_id"],
                          "data": {key: value for key, value in self.args.items() if key != "request_id"}}, request["body"])
        self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
        self.assertEqual(1, self.stub.login_count)

    async def test_invalid_command_arguments_are_rejected_before_auth_without_echoing_input(self) -> None:
        private = PASSWORD + TOKEN + "private-command-input"
        parameters = self.args["parameters"]
        cases = [{key: value for key, value in self.args.items() if key != missing} for missing in self.args]
        cases.extend({**self.args, key: private} for key in ["player_id", "world_id", "session_id", "raw_packet"])
        cases.extend({**self.args, "command": value} for value in [None, False, 1, [], {}, "", "Give", "@give", private])
        cases.extend({**self.args, "expected_command_generation": value}
                     for value in [None, True, 1, [], {}, "", ".", "..", "old/process", private + "\n"])
        cases.extend({**self.args, "profile_id": value} for value in [None, True, 1, "", "..", "normal/player", private])
        cases.extend({**self.args, "request_id": value} for value in [None, True, 1, "", "request/id", private + "\n"])
        invalid_parameters = [None, False, [], "parameters", {}, {"item_id": 2000000}, {"quantity": 1},
                              {**parameters, "player_id": private}, {**parameters, "type": 11}]
        for field, maximum in [("item_id", 2**31 - 1), ("quantity", 32767)]:
            invalid_parameters.extend({**parameters, field: value}
                                      for value in [None, False, True, 0, -1, maximum + 1, 1.0, "1", [], {}, private])
        cases.extend({**self.args, "parameters": value} for value in invalid_parameters)
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

    async def test_help_rejects_unsupported_or_extra_arguments_without_echo_or_http(self) -> None:
        private = PASSWORD + TOKEN + "private-help-input"
        cases = [{"command": value} for value in [True, 1, [], {}, "", "Give", "@give", private]]
        cases.extend([{"command": "give", "profile_id": private}, {"request_id": private}])
        async with Client(build_server(self.stub.config())) as client:
            for arguments in cases:
                with self.subTest(arguments=arguments):
                    response = await client.call_tool("game_command_help", arguments)
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_bridge_itself_validates_commands_before_network(self) -> None:
        async with BridgeClient(self.stub.config()) as bridge:
            for changes in [{"parameters": {"item_id": 2000000, "quantity": True}},
                            {"parameters": {"item_id": 2000000, "quantity": 1, "player_id": 0}},
                            {"expected_command_generation": ".."}, {"command": "shutdown"}]:
                with self.subTest(changes=changes), self.assertRaises(BridgeError) as raised:
                    await bridge.command(**{**self.args, **changes})
                self.assertEqual("INVALID_ARGUMENTS", raised.exception.code)
            with self.assertRaises(BridgeError):
                await bridge.command_help("shutdown")
        self.assertEqual([], self.stub.requests)

    async def test_statuses_are_forwarded_without_local_success_or_deduplication(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            for status in ["RUNNING", "FAILED", "UNKNOWN", "SUCCEEDED"]:
                self.stub.status = status
                response = await client.call_tool("game_command", self.args)
                self.assertFalse(response.is_error)
                self.assertEqual(status, response.structured_content["status"])
        self.assertEqual(4, len(self.command_requests()))
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

