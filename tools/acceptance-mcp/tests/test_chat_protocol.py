from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from copy import deepcopy
import json
from pathlib import Path
import socket
import sys
import tempfile
import time
import unittest
from urllib.parse import urlsplit
from uuid import uuid4

from mcp import Client, StdioServerParameters

from solo_acceptance_mcp.smoke import require_capabilities
from tests.helpers import BridgeStub, CAPABILITIES, PASSWORD, TOKEN, envelope


OBSERVATION_TOOLS = {"game_capabilities", "game_observe"}
ACTOR_TOOLS = {"actor_open", "actor_close", "operation_get", "operation_cancel"}
TOOLS = OBSERVATION_TOOLS | ACTOR_TOOLS | {"game_act"}
ACT_PATH = "/solo/acceptance/v1/actor/act"
MAX_LONG = 2**63 - 1


def capabilities(enabled: set[str]) -> dict:
    value = deepcopy(CAPABILITIES)
    value.update(
        implementation_round="R2a" if ACTOR_TOOLS <= enabled else "R1",
        enabled_tools=sorted(enabled), actions=["chat"] if "game_act" in enabled else [], recipes=[],
    )
    if "game_act" in enabled:
        value["actor_control"] = {"actions": ["chat"]}
    return value


class ChatProtocolStub(BridgeStub):
    """Only a local HTTP boundary; it does not execute native chat or contact a DB."""

    def __init__(self) -> None:
        self.status = "QUEUED"
        self.disconnect_action = False
        self.session_id = "session-" + uuid4().hex
        self.operation_id = "operation-" + uuid4().hex
        self.runtime_generation = "generation-" + uuid4().hex
        super().__init__()
        owner = self
        original_handler = self.http.RequestHandlerClass

        class DisconnectableHandler(original_handler):
            def respond(self) -> None:
                if self.path == ACT_PATH and owner.disconnect_action:
                    body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                    owner.requests.append({
                        "method": self.command, "path": self.path,
                        "headers": {key.lower(): value for key, value in self.headers.items()},
                        "body": json.loads(body),
                    })
                    owner.resource_count += 1
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                super().respond()

        self.http.RequestHandlerClass = DisconnectableHandler

    def operation(self, status: str | None = None) -> dict:
        status = self.status if status is None else status
        result, error = {}, None
        if status == "SUCCEEDED":
            result = {
                "observed_outcome": "native_chat_broadcast", "native_dispatch_started": True,
                "echo_matched": True, "sender_character_id": 472, "echo_sequence": 219,
                "packet_cursor_before": 218, "packet_cursor_after": 219, "cancel_too_late": False,
            }
        elif status == "CANCELLED":
            result = {"observed_outcome": "chat_cancelled_before_dispatch", "native_dispatch_started": False}
        elif status in {"FAILED", "INDETERMINATE"}:
            reason = "native_chat_not_broadcast" if status == "FAILED" else "native_chat_outcome_unknown"
            result = {"observed_outcome": reason, "native_dispatch_started": True, "echo_matched": False}
            error = {"reason": reason}
        return {
            "schema_version": 1, "operation_id": self.operation_id, "kind": "ACTION", "action": "chat",
            "status": status, "step": "local_protocol_fixture", "profile_id": "primary",
            "session_id": self.session_id, "session_generation": 7,
            "runtime_generation": self.runtime_generation,
            "cancel_requested": status in {"CANCEL_REQUESTED", "CANCELLED"},
            "result": result, "error": error,
            "session": {"session_id": self.session_id, "session_generation": 7, "state": "OPEN",
                        "profile_id": "primary", "runtime_generation": self.runtime_generation,
                        "actor_current": True, "actor": {"character_id": 472, "is_bot": False}},
            "events": {"items": [{"seq": 3, "kind": status}], "next_cursor": 3, "latest_cursor": 3,
                       "oldest_cursor": 1, "cursor_expired": False, "truncated": False},
        }

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            return 200, envelope(capabilities(TOOLS))
        if path == ACT_PATH or path == "/solo/acceptance/v1/operation/" + self.operation_id:
            return 200, envelope(self.operation())
        if path == "/solo/acceptance/v1/operation/" + self.operation_id + "/cancel":
            return 200, envelope(self.operation("CANCEL_REQUESTED"))
        return super().default_response(request)


class ChatProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.stub = ChatProtocolStub().start()
        self.args = {
            "session_id": self.stub.session_id, "expected_generation": 7,
            "request_id": "chat:" + uuid4().hex, "action": "chat",
            "parameters": {"text": "聊天协议桩 " + uuid4().hex},
            "expected_context": {"scene_ref": "scene-" + uuid4().hex,
                                 "runtime_generation": self.stub.runtime_generation},
        }

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    @asynccontextmanager
    async def stdio_client(self, *, timeout: float = 2):
        root = Path(__file__).resolve().parents[1]
        temporary_root = root / ".test-tmp"
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as directory:
            config = self.stub.write_config(Path(directory) / "private.json", timeout=timeout)
            params = StdioServerParameters(
                command=sys.executable, args=["-m", "solo_acceptance_mcp", "--config", str(config)], cwd=root,
                env={"PYTHONPATH": str(root / "src")},
            )
            async with Client(params, read_timeout_seconds=10) as client:
                yield client

    def action_requests(self) -> list[dict]:
        return [request for request in self.stub.requests if request["path"] == ACT_PATH]

    async def test_stdio_registers_exactly_seven_tools_with_explicit_chat_mutation_annotations(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
            caps = await client.call_tool("game_capabilities", {})
        self.assertEqual(TOOLS, set(tools))
        annotations = tools["game_act"].annotations
        self.assertFalse(annotations.read_only_hint)
        self.assertFalse(annotations.destructive_hint)
        self.assertTrue(annotations.idempotent_hint)
        self.assertTrue(annotations.open_world_hint)
        for name in OBSERVATION_TOOLS | {"operation_get"}:
            self.assertTrue(tools[name].annotations.read_only_hint)
        self.assertEqual(set(self.args), set(tools["game_act"].input_schema["properties"]))
        self.assertEqual(set(self.args), set(tools["game_act"].input_schema["required"]))
        self.assertIs(False, tools["game_act"].input_schema.get("additionalProperties"))
        action_schema = tools["game_act"].input_schema["properties"]["action"]
        self.assertEqual(["chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move"], action_schema["enum"])
        self.assertFalse(caps.is_error)
        self.assertEqual(capabilities(TOOLS), caps.structured_content)
        self.assertEqual(sorted(TOOLS), require_capabilities(caps.structured_content, sorted(tools), actors=True))

    async def test_stdio_chat_preserves_normal_jwt_envelope_context_and_untrimmed_text(self) -> None:
        args = {**self.args, "parameters": {"text": "  原样保留 中文 nonce  "}}
        async with self.stdio_client() as client:
            response = await client.call_tool("game_act", args)
        self.assertFalse(response.is_error)
        self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
        self.assertEqual(response.structured_content, json.loads(response.content[0].text))
        requests = self.action_requests()
        self.assertEqual(1, len(requests))
        request = requests[0]
        self.assertEqual("POST", request["method"])
        self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
        self.assertEqual({"requestId": args["request_id"],
                          "data": {key: value for key, value in args.items() if key != "request_id"}}, request["body"])
        self.assertNotIn("request_id", request["body"]["data"])
        self.assertNotIn(args["parameters"]["text"], response.model_dump_json())
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertEqual(1, self.stub.login_count)

    async def test_stdio_utf16_boundaries_and_ordinary_exclamation_text_are_forwarded_unchanged(self) -> None:
        # These are local length checks; the stub does not claim GBK can encode emoji.
        texts = ["a" * 127, "😀" * 63 + "a", "!普通玩家文本", " @首字符是空格", " /原样普通文本"]
        async with self.stdio_client() as client:
            for index, text in enumerate(texts):
                with self.subTest(text=text):
                    response = await client.call_tool("game_act", {
                        **self.args, "request_id": "text-boundary-" + str(index), "parameters": {"text": text},
                    })
                    self.assertFalse(response.is_error)
                    self.assertEqual(text, self.action_requests()[-1]["body"]["data"]["parameters"]["text"])
        self.assertEqual(len(texts), len(self.action_requests()))

    async def test_stdio_chat_rejects_non_strings_empty_whitespace_commands_and_utf16_overflow_before_http(self) -> None:
        invalid = [None, True, 1, 1.0, [], {}, "", " \t\r\n", "\u3000", "\u2003",
                   "@help", "/hello", "a" * 128, "😀" * 64]
        async with self.stdio_client() as client:
            for text in invalid:
                with self.subTest(text=text):
                    response = await client.call_tool("game_act", {**self.args, "parameters": {"text": text}})
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)

    async def test_stdio_all_required_arguments_and_top_level_extra_fields_are_rejected_before_http(self) -> None:
        cases = [{key: value for key, value in self.args.items() if key != missing} for missing in self.args]
        cases.extend({**self.args, extra: value} for extra, value in [
            ("character_id", 472), ("password", PASSWORD), ("map_id", 10000), ("show", 0),
            ("script", "scripts/anything.js"), ("unexpected", True),
        ])
        async with self.stdio_client() as client:
            for args in cases:
                with self.subTest(args=args):
                    response = await client.call_tool("game_act", args)
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)

    async def test_stdio_parameters_and_expected_context_are_exact_strict_objects_before_http(self) -> None:
        invalid_parameters = [None, True, "text", ["text"], {}, {"text": "safe", "show": 0},
                              {"text": "safe", "character_id": 472}, {"text": "safe", "script": "anything"}]
        context = self.args["expected_context"]
        invalid_context = [None, True, "context", [], {}, {"scene_ref": context["scene_ref"]},
                           {"runtime_generation": context["runtime_generation"]},
                           {**context, "map_id": 10000}, {**context, "character_id": 472}]
        for field in ["scene_ref", "runtime_generation"]:
            invalid_context.extend({**context, field: value} for value in [None, True, 472, [], {}, "", "..", "bad/ref", "x" * 129])
        async with self.stdio_client() as client:
            for field, values in [("parameters", invalid_parameters), ("expected_context", invalid_context)]:
                for value in values:
                    with self.subTest(field=field, value=value):
                        response = await client.call_tool("game_act", {**self.args, field: value})
                        self.assertTrue(response.is_error)
                        self.assertEqual([], self.stub.requests)

    async def test_stdio_invalid_nested_objects_never_echo_sensitive_input_before_http(self) -> None:
        nonce = "private-chat-" + uuid4().hex
        private = " ".join([nonce, PASSWORD, TOKEN])
        context = self.args["expected_context"]
        cases = [
            {**self.args, "parameters": {"text": private, "unexpected": private}},
            {**self.args, "parameters": {"text": {"private": private}}},
            {**self.args, "parameters": [{"text": private}]},
            {**self.args, "parameters": {"text": private}, "expected_context": {**context, "unexpected": private}},
            {**self.args, "expected_context": {**context, "scene_ref": {"private": private}}},
            {**self.args, "expected_context": {**context, "runtime_generation": [private]}},
        ]
        async with self.stdio_client() as client:
            for index, args in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("game_act", args)
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    public = response.model_dump_json()
                    for sensitive in [nonce, PASSWORD, TOKEN]:
                        self.assertNotIn(sensitive, public)
                    self.assertEqual([], self.stub.requests)

    async def test_stdio_session_generation_request_and_action_types_are_strict_before_http(self) -> None:
        invalid = {
            "session_id": [None, True, 472, [], "", ".", "../session", "x" * 129],
            "request_id": [None, True, 472, "", "request/unsafe", "bad\nrequest", "x" * 129],
            "expected_generation": [None, True, False, 1.0, "1", 0, MAX_LONG + 1],
            "action": [None, True, 1, [], {}, "", "move", "npc", "CHAT"],
        }
        async with self.stdio_client() as client:
            for field, values in invalid.items():
                for value in values:
                    with self.subTest(field=field, value=value):
                        response = await client.call_tool("game_act", {**self.args, field: value})
                        self.assertTrue(response.is_error)
                        self.assertEqual([], self.stub.requests)

    async def test_stdio_action_pending_cancel_failed_and_unknown_outcomes_are_never_promoted(self) -> None:
        states = ["QUEUED", "RUNNING", "WAITING", "CANCEL_REQUESTED", "CANCELLED", "FAILED", "INDETERMINATE", "SUCCEEDED"]
        async with self.stdio_client() as client:
            for index, status in enumerate(states):
                with self.subTest(status=status):
                    self.stub.status = status
                    args = {**self.args, "request_id": "status-" + str(index)}
                    response = await client.call_tool("game_act", args)
                    queried = await client.call_tool("operation_get", {"operation_id": self.stub.operation_id})
                    self.assertFalse(response.is_error)
                    self.assertFalse(queried.is_error)
                    self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
                    self.assertEqual(self.stub.operation(), queried.structured_content)
                    self.assertEqual("ACTION", queried.structured_content["kind"])
                    self.assertEqual("OPEN", queried.structured_content["session"]["state"])
                    self.assertNotIn(self.args["parameters"]["text"], response.model_dump_json())
            cancelled = await client.call_tool("operation_cancel", {
                "operation_id": self.stub.operation_id, "request_id": "cancel-chat-operation",
            })
        self.assertFalse(cancelled.is_error)
        self.assertEqual("CANCEL_REQUESTED", cancelled.structured_content["status"])
        self.assertEqual({}, cancelled.structured_content["result"])
        self.assertTrue(cancelled.structured_content["session"]["actor_current"])
        self.assertFalse(any(request["path"].endswith("/actor/close") for request in self.stub.requests))

    async def test_stdio_http_errors_preserve_request_id_status_and_safe_known_or_unknown_outcome(self) -> None:
        status = 400
        secret = self.args["parameters"]["text"] + PASSWORD + TOKEN + "private-native-exception"

        def reject(request: dict) -> tuple[int, object]:
            if request["path"] == "/auth/v1/login":
                return self.stub.default_response(request)
            return status, {"message": secret, "requestId": "backend-private-request"}

        self.stub.responder = reject
        async with self.stdio_client() as client:
            for status in [400, 403, 404, 409, 410, 429, 503]:
                with self.subTest(status=status):
                    response = await client.call_tool("game_act", self.args)
                    self.assertTrue(response.is_error)
                    self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
                    error = response.structured_content["error"]
                    self.assertEqual(status, error["http_status"])
                    self.assertFalse(error["retryable"])
                    self.assertEqual(status >= 500, error["outcome_unknown"])
                    for private in [self.args["parameters"]["text"], PASSWORD, TOKEN, "private-native-exception", "backend-private-request"]:
                        self.assertNotIn(private, response.model_dump_json())
        self.assertEqual(7, len(self.action_requests()))
        self.assertEqual(1, self.stub.login_count)

    async def test_stdio_gbk_encoding_refusal_is_backend_400_and_not_synthetic_local_success(self) -> None:
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (400, {"reason": "chat_text_not_encodable", "text": "😀", "message": PASSWORD + TOKEN})
        )
        async with self.stdio_client() as client:
            response = await client.call_tool("game_act", {**self.args, "parameters": {"text": "😀"}})
        self.assertTrue(response.is_error)
        self.assertEqual(400, response.structured_content["error"]["http_status"])
        self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
        self.assertFalse(response.structured_content["error"]["outcome_unknown"])
        self.assertEqual(1, len(self.action_requests()))
        self.assertNotIn("😀", response.model_dump_json())

    async def test_stdio_401_refresh_replays_the_identical_chat_id_context_and_text_once(self) -> None:
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
        self.assertEqual(self.args["request_id"], requests[1]["body"]["requestId"])
        self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_stdio_repeated_401_stops_after_one_refresh_with_the_original_request_id(self) -> None:
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN + self.args["parameters"]["text"]})
        )
        async with self.stdio_client() as client:
            response = await client.call_tool("game_act", self.args)
        self.assertTrue(response.is_error)
        self.assertEqual(401, response.structured_content["error"]["http_status"])
        self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, len(self.action_requests()))
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())

    async def test_stdio_action_timeout_is_not_replayed_and_keeps_unknown_outcome_id(self) -> None:
        def respond(request: dict) -> tuple[int, object]:
            if request["path"] == ACT_PATH:
                time.sleep(0.25)
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client(timeout=0.1) as client:
            response = await client.call_tool("game_act", self.args)
        self.assertTrue(response.is_error)
        self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
        self.assertEqual("HTTP_TIMEOUT", response.structured_content["error"]["code"])
        self.assertTrue(response.structured_content["error"]["outcome_unknown"])
        self.assertFalse(response.structured_content["error"]["retryable"])
        self.assertEqual(1, len(self.action_requests()))
        self.assertNotIn(self.args["parameters"]["text"], response.model_dump_json())

    async def test_stdio_connection_loss_after_receiving_chat_is_not_replayed_or_reported_as_sent(self) -> None:
        self.stub.disconnect_action = True
        async with self.stdio_client() as client:
            response = await client.call_tool("game_act", self.args)
        self.assertTrue(response.is_error)
        self.assertEqual(self.args["request_id"], response.structured_content["request_id"])
        self.assertEqual("HTTP_UNAVAILABLE", response.structured_content["error"]["code"])
        self.assertTrue(response.structured_content["error"]["outcome_unknown"])
        self.assertFalse(response.structured_content["error"]["retryable"])
        self.assertEqual(1, len(self.action_requests()))
        self.assertEqual(1, self.stub.login_count)

    def test_capability_helper_accepts_only_complete_supported_backend_tool_subsets(self) -> None:
        for enabled in [OBSERVATION_TOOLS, OBSERVATION_TOOLS | ACTOR_TOOLS, TOOLS]:
            with self.subTest(enabled=enabled):
                self.assertEqual(sorted(enabled), require_capabilities(capabilities(enabled), sorted(TOOLS)))
                if "game_act" in enabled:
                    self.assertEqual(sorted(enabled), require_capabilities(capabilities(enabled), sorted(TOOLS), chat=True))
                else:
                    with self.assertRaises(RuntimeError):
                        require_capabilities(capabilities(enabled), sorted(TOOLS), chat=True)
        for enabled in [OBSERVATION_TOOLS | {"game_act"}, OBSERVATION_TOOLS | {"actor_open"},
                        TOOLS - {"actor_close"}, TOOLS | {"recipe_run"}]:
            with self.subTest(enabled=enabled), self.assertRaises(RuntimeError):
                require_capabilities(capabilities(enabled), sorted(TOOLS))
        for field in ["actions", "actor_control"]:
            for value in [None, [], {}, ["npc"]]:
                invalid = capabilities(TOOLS)
                invalid[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                    require_capabilities(invalid, sorted(TOOLS))

    async def test_stdio_backend_with_chat_disabled_is_not_promoted_by_the_registered_game_act_tool(self) -> None:
        old_capabilities = capabilities(OBSERVATION_TOOLS | ACTOR_TOOLS)
        self.stub.responder = lambda request: (
            (200, envelope(old_capabilities)) if request["path"].endswith("/capabilities")
            else self.stub.default_response(request)
        )
        async with self.stdio_client() as client:
            names = [tool.name for tool in (await client.list_tools()).tools]
            response = await client.call_tool("game_capabilities", {})
        self.assertEqual(TOOLS, set(names))
        self.assertEqual(old_capabilities, response.structured_content)
        self.assertNotIn("game_act", require_capabilities(response.structured_content, names, actors=True))
        self.assertEqual([], response.structured_content["actions"])
        self.assertEqual([], self.action_requests())
