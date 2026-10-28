from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import tempfile
import unittest
from uuid import UUID, uuid4

from mcp import Client, StdioServerParameters

from solo_acceptance_mcp.bridge import BridgeClient
from tests.helpers import BridgeStub, CAPABILITIES, PASSWORD, SCENE, SCENE_CONTEXT, TOKEN, WORLD, envelope


TOOLS = {
    "game_capabilities", "game_observe", "actor_open", "actor_close",
    "operation_get", "operation_cancel",
}
READ_ONLY_TOOLS = {"game_capabilities", "game_observe", "operation_get"}
ACTOR_PATH = "/solo/acceptance/v1/actor/observe"
MAX_LONG = 2**63 - 1


class ActorObserveProtocolTests(unittest.IsolatedAsyncioTestCase):
    """Official SDK stdio to an isolated HTTP stub, never a game server or DB."""

    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.session_id = "session-" + uuid4().hex
        self.generation = 7
        self.actor_args = {
            "scope": "actor", "session_id": self.session_id,
            "expected_generation": self.generation,
        }
        self.native = {
            "schema_version": 1,
            "captured_at": datetime.now(timezone.utc).isoformat(),
            "scope": "actor",
            "runtime_generation": "runtime-" + uuid4().hex,
            "information_level": "server_diagnostic",
            "snapshot_consistency": "best_effort",
            "session_id": self.session_id,
            "session_generation": self.generation,
            "profile_id": "primary",
            "actor": {
                "character_id": 472, "is_bot": False, "logged_in_world": True,
                "scene": deepcopy(SCENE_CONTEXT),
                "packet_events": {"items": [{"sequence": 18, "opcode": 68, "length": 29}]},
            },
            "events": {
                "items": [{
                    "sequence": 18, "received_at": datetime.now(timezone.utc).isoformat(),
                    "opcode": 68, "length": 29,
                    "event_type": "server_message", "decode_status": "decoded",
                    "information_level": "player_visible", "message_type": 4,
                    "ticker_enabled": True, "text": "本地协议桩：独立的包事件游标。",
                    "text_truncated": False,
                }],
                "next_cursor": 18, "oldest_cursor": 18, "latest_cursor": 41,
                "cursor_expired": True, "truncated": True, "capacity": 256,
                "dropped_events_total": 17, "malformed_events_total": 0,
            },
        }
        self.native["actor"]["scene"]["runtime_generation"] = self.native["runtime_generation"]
        self.stub.responder = self.respond

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    def respond(self, request: dict) -> tuple[int, object]:
        if request["path"] == ACTOR_PATH:
            return 200, envelope(self.native)
        return self.stub.default_response(request)

    @asynccontextmanager
    async def stdio_client(self):
        temporary_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as directory:
            config = self.stub.write_config(Path(directory) / "private.json")
            params = StdioServerParameters(
                command=sys.executable,
                args=["-m", "solo_acceptance_mcp", "--config", str(config)],
                cwd=Path(__file__).resolve().parents[1],
                env={"PYTHONPATH": str(Path(__file__).resolve().parents[1] / "src")},
            )
            async with Client(params, read_timeout_seconds=10) as client:
                yield client

    async def test_stdio_lists_implemented_tools_and_keeps_observation_read_only(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(TOOLS | {"game_act", "game_command", "script_run"}, set(tools))
        for name, tool in tools.items():
            self.assertEqual(name in READ_ONLY_TOOLS, tool.annotations.read_only_hint, name)
            self.assertTrue(tool.annotations.idempotent_hint, name)
        observe = tools["game_observe"]
        self.assertFalse(observe.annotations.destructive_hint)
        props = observe.input_schema["properties"]
        self.assertEqual(["world", "scene", "actor"], props["scope"]["enum"])
        self.assertEqual("world", props["scope"]["default"])
        self.assertEqual(
            {"scope", "scene_ref", "limit", "session_id", "expected_generation", "since_cursor"},
            set(props),
        )
        self.assertEqual(100, props["limit"]["default"])
        self.assertEqual(1, props["limit"]["minimum"])
        self.assertEqual(500, props["limit"]["maximum"])
        self.assertEqual(0, props["since_cursor"]["default"])
        self.assertEqual(MAX_LONG, props["since_cursor"]["maximum"])
        self.assertEqual([], self.stub.requests)

    async def test_stdio_actor_route_preserves_native_data_and_independent_packet_cursor(self) -> None:
        async with self.stdio_client() as client:
            default = await client.call_tool("game_observe", self.actor_args)
            args = {**self.actor_args, "since_cursor": 17, "limit": 1}
            first = await client.call_tool("game_observe", args)
            repeated = await client.call_tool("game_observe", args)
        for response in [default, first, repeated]:
            self.assertFalse(response.is_error)
            self.assertEqual(self.native, response.structured_content)
            self.assertEqual(self.native, json.loads(response.content[0].text))
            self.assertNotIn("operation_id", response.structured_content)
            self.assertNotIn("request_id", response.structured_content)
            self.assertNotIn(PASSWORD, response.model_dump_json())
            self.assertNotIn(TOKEN, response.model_dump_json())
        requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual([ACTOR_PATH] * 3, [request["path"] for request in requests])
        request_ids = []
        for index, request in enumerate(requests):
            self.assertEqual("POST", request["method"])
            self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
            self.assertEqual({"requestId", "data"}, set(request["body"]))
            self.assertEqual({
                "session_id": self.session_id, "expected_generation": self.generation,
                "since_cursor": 0 if index == 0 else 17, "limit": 100 if index == 0 else 1,
            }, request["body"]["data"])
            request_id = request["body"]["requestId"]
            self.assertEqual(request_id, str(UUID(request_id)))
            request_ids.append(request_id)
        self.assertEqual(3, len(set(request_ids)))
        self.assertEqual(1, self.stub.login_count)

    async def test_stdio_world_and_scene_keep_the_original_route_payloads_and_native_results(self) -> None:
        args_and_results = [
            ({}, {"scope": "world", "limit": 100}, WORLD),
            ({"scope": "world", "since_cursor": 0, "limit": 500},
             {"scope": "world", "limit": 500}, WORLD),
            ({"scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "limit": 23},
             {"scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "limit": 23}, SCENE),
        ]
        async with self.stdio_client() as client:
            for args, payload, native in args_and_results:
                with self.subTest(args=args):
                    response = await client.call_tool("game_observe", args)
                    self.assertFalse(response.is_error)
                    self.assertEqual(native, response.structured_content)
                    self.assertEqual("/solo/acceptance/v1/observe", self.stub.requests[-1]["path"])
                    self.assertEqual(payload, self.stub.requests[-1]["body"]["data"])
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(3, self.stub.resource_count)

    async def test_stdio_scope_combinations_are_rejected_before_authentication_or_http(self) -> None:
        cases = [
            {"scope": "unsupported"}, {"scope": True},
            {"scope": "world", "scene_ref": SCENE_CONTEXT["scene_ref"]},
            {"scope": "world", "session_id": self.session_id},
            {"scope": "world", "expected_generation": self.generation},
            {"scope": "world", "since_cursor": 1},
            {"scope": "scene"},
            {"scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "session_id": self.session_id},
            {"scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "expected_generation": self.generation},
            {"scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "since_cursor": 1},
            {"scope": "actor"},
            {"scope": "actor", "session_id": self.session_id},
            {"scope": "actor", "expected_generation": self.generation},
            {**self.actor_args, "scene_ref": SCENE_CONTEXT["scene_ref"]},
        ]
        async with self.stdio_client() as client:
            for args in cases:
                with self.subTest(args=args):
                    response = await client.call_tool("game_observe", args)
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)

    async def test_stdio_all_actor_integer_fields_reject_coercion_and_out_of_range_values_before_http(self) -> None:
        invalid = {
            "limit": [True, False, 1.0, "1", 0, 501, None],
            "expected_generation": [True, False, 1.0, "1", 0, MAX_LONG + 1, None],
            "since_cursor": [True, False, 1.0, "1", -1, MAX_LONG + 1, None],
        }
        async with self.stdio_client() as client:
            for field, values in invalid.items():
                for value in values:
                    with self.subTest(field=field, value=value):
                        response = await client.call_tool("game_observe", {**self.actor_args, field: value})
                        self.assertTrue(response.is_error)
                        self.assertEqual([], self.stub.requests)
            # Even a false/zero-looking value must not bypass non-actor scope validation.
            for scope in ["world", "scene"]:
                args = {"scope": scope}
                if scope == "scene":
                    args["scene_ref"] = SCENE_CONTEXT["scene_ref"]
                for value in [False, 0.0, "0"]:
                    with self.subTest(scope=scope, since_cursor=value):
                        response = await client.call_tool("game_observe", {**args, "since_cursor": value})
                        self.assertTrue(response.is_error)
                        self.assertEqual([], self.stub.requests)

    async def test_stdio_session_identifiers_are_validated_before_http(self) -> None:
        async with self.stdio_client() as client:
            for session_id in [None, "", 472, True, ".", "..", "../session", "session/other",
                               "session?all=true", "session\nother", "x" * 129]:
                with self.subTest(session_id=session_id):
                    response = await client.call_tool("game_observe", {**self.actor_args, "session_id": session_id})
                    self.assertTrue(response.is_error)
                    self.assertEqual([], self.stub.requests)

    async def test_stdio_accepts_and_forwards_each_legal_integer_boundary(self) -> None:
        async with self.stdio_client() as client:
            for generation, cursor, limit in [(1, 0, 1), (MAX_LONG, MAX_LONG, 500)]:
                with self.subTest(generation=generation, cursor=cursor, limit=limit):
                    args = {**self.actor_args, "expected_generation": generation,
                            "since_cursor": cursor, "limit": limit}
                    response = await client.call_tool("game_observe", args)
                    self.assertFalse(response.is_error)
                    self.assertEqual({key: value for key, value in args.items() if key != "scope"},
                                     self.stub.requests[-1]["body"]["data"])
        self.assertEqual(2, self.stub.resource_count)

    async def test_stdio_backend_errors_preserve_http_status_and_do_not_echo_body_secrets(self) -> None:
        private_body = "backend-packet-payload-" + uuid4().hex
        status = 400

        def reject(request: dict) -> tuple[int, object]:
            if request["path"] == "/auth/v1/login":
                return self.stub.default_response(request)
            return status, {"message": PASSWORD + TOKEN + private_body, "requestId": private_body}

        self.stub.responder = reject
        async with self.stdio_client() as client:
            for status in [400, 403, 404, 409, 410, 429, 503]:
                with self.subTest(status=status):
                    response = await client.call_tool("game_observe", self.actor_args)
                    self.assertTrue(response.is_error)
                    self.assertEqual(status, response.structured_content["error"]["http_status"])
                    self.assertNotIn("events", response.structured_content)
                    self.assertNotIn("request_id", response.structured_content)
                    self.assertNotIn(PASSWORD, response.model_dump_json())
                    self.assertNotIn(TOKEN, response.model_dump_json())
                    self.assertNotIn(private_body, response.model_dump_json())
                    self.assertEqual(ACTOR_PATH, self.stub.requests[-1]["path"])
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(7, self.stub.resource_count)

    async def test_stdio_actor_401_refreshes_only_once_and_preserves_read_request_body(self) -> None:
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN})
        )
        async with self.stdio_client() as client:
            response = await client.call_tool("game_observe", self.actor_args)
        self.assertTrue(response.is_error)
        self.assertEqual(401, response.structured_content["error"]["http_status"])
        self.assertEqual(2, self.stub.login_count)
        requests = [request for request in self.stub.requests if request["path"] == ACTOR_PATH]
        self.assertEqual(2, len(requests))
        self.assertEqual(requests[0]["body"], requests[1]["body"])
        self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())

    async def test_stdio_actor_410_requires_a_new_actor_session_without_replaying_or_echoing_backend_body(self) -> None:
        private = "private-expired-actor-" + uuid4().hex
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (410, {"message": private + PASSWORD + TOKEN, "requestId": private})
        )
        async with self.stdio_client() as client:
            response = await client.call_tool("game_observe", self.actor_args)
        self.assertTrue(response.is_error)
        error = response.structured_content["error"]
        self.assertEqual("SESSION_EXPIRED", error["code"])
        self.assertEqual(410, error["http_status"])
        self.assertFalse(error["retryable"])
        self.assertIn("重新打开角色", error["message"])
        self.assertIn("session_id", error["message"])
        self.assertIn("expected_generation", error["message"])
        self.assertNotIn("world", error["message"])
        self.assertNotIn("scene_ref", error["message"])
        for sensitive in [private, PASSWORD, TOKEN]:
            self.assertNotIn(sensitive, response.model_dump_json())
        self.assertNotIn("request_id", response.structured_content)
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual([ACTOR_PATH], [request["path"] for request in self.stub.requests
                                      if request["path"] != "/auth/v1/login"])

    async def test_stdio_scene_410_keeps_stale_scene_and_world_recovery_instead_of_actor_recovery(self) -> None:
        private = "private-stale-scene-" + uuid4().hex
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (410, {"message": private + PASSWORD + TOKEN})
        )
        async with self.stdio_client() as client:
            response = await client.call_tool("game_observe", {
                "scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "limit": 13,
            })
        self.assertTrue(response.is_error)
        error = response.structured_content["error"]
        self.assertEqual("STALE_SCENE", error["code"])
        self.assertEqual(410, error["http_status"])
        self.assertEqual("场景引用已失效，请重新观察 world。", error["message"])
        self.assertFalse(error["retryable"])
        self.assertNotIn("session_id", error["message"])
        self.assertNotIn("expected_generation", error["message"])
        for sensitive in [private, PASSWORD, TOKEN]:
            self.assertNotIn(sensitive, response.model_dump_json())
        resources = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual(["/solo/acceptance/v1/observe"], [request["path"] for request in resources])
        self.assertEqual({"scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "limit": 13}, resources[0]["body"]["data"])

    async def test_stdio_actor_401_refresh_then_410_retains_session_semantics_and_identical_request_body(self) -> None:
        private = "private-refreshed-actor-" + uuid4().hex

        def respond(request: dict) -> tuple[int, object]:
            if request["path"] == "/auth/v1/login":
                return self.stub.default_response(request)
            count = sum(row["path"] == ACTOR_PATH for row in self.stub.requests)
            return 401 if count == 1 else 410, {"message": private + PASSWORD + TOKEN}

        self.stub.responder = respond
        args = {**self.actor_args, "since_cursor": 17, "limit": 1}
        async with self.stdio_client() as client:
            response = await client.call_tool("game_observe", args)
        self.assertTrue(response.is_error)
        error = response.structured_content["error"]
        self.assertEqual("SESSION_EXPIRED", error["code"])
        self.assertEqual(410, error["http_status"])
        self.assertIn("重新打开角色", error["message"])
        self.assertIn("session_id", error["message"])
        self.assertIn("expected_generation", error["message"])
        self.assertNotIn("world", error["message"])
        requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual([ACTOR_PATH, ACTOR_PATH], [request["path"] for request in requests])
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(requests[0]["body"], requests[1]["body"])
        self.assertEqual({"session_id": self.session_id, "expected_generation": self.generation,
                          "since_cursor": 17, "limit": 1}, requests[1]["body"]["data"])
        self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])
        for sensitive in [private, PASSWORD, TOKEN]:
            self.assertNotIn(sensitive, response.model_dump_json())

    async def test_stdio_actor_non_410_errors_keep_their_existing_public_codes_and_messages(self) -> None:
        status = 400
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (status, {"message": PASSWORD + TOKEN})
        )
        cases = [
            (400, "INVALID_ARGUMENTS", "验收桥拒绝了请求参数。"),
            (403, "ACCESS_DENIED", "当前管理账号无权调用验收桥。"),
            (404, "BRIDGE_UNAVAILABLE", "验收桥未启用或此接口不可用。"),
            (409, "STALE_OBSERVATION", "读取期间游戏运行代次变化，请重新观察 world。"),
            (429, "CAPACITY_REACHED", "验收操作或角色会话已达到当前限额。"),
            (503, "SERVER_OFFLINE", "游戏后端当前不可观察。"),
        ]
        async with self.stdio_client() as client:
            for status, code, message in cases:
                with self.subTest(status=status):
                    response = await client.call_tool("game_observe", self.actor_args)
                    self.assertTrue(response.is_error)
                    error = response.structured_content["error"]
                    self.assertEqual(code, error["code"])
                    self.assertEqual(status, error["http_status"])
                    self.assertEqual(message, error["message"])
                    self.assertEqual(status == 503, error["retryable"])
                    self.assertNotIn(PASSWORD, response.model_dump_json())
                    self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(len(cases), self.stub.resource_count)

    async def test_stdio_client_schema_does_not_invent_disabled_backend_actor_capabilities(self) -> None:
        capabilities = deepcopy(CAPABILITIES)
        capabilities.update(implementation_round="R2a", enabled_tools=sorted(TOOLS),
                            scopes=["world", "scene"], actor_control={"enabled": False})
        self.stub.responder = lambda request: (
            (200, envelope(capabilities)) if request["path"].endswith("/capabilities")
            else self.stub.default_response(request)
        )
        async with self.stdio_client() as client:
            response = await client.call_tool("game_capabilities", {})
        self.assertFalse(response.is_error)
        self.assertEqual(capabilities, response.structured_content)
        self.assertNotIn("actor", response.structured_content["scopes"])
        self.assertNotIn("packet_observation", response.structured_content["actor_control"])
        self.assertEqual(1, self.stub.resource_count)

    async def test_bridge_keeps_the_documented_observation_positional_arguments(self) -> None:
        # SDK schemas do not prove the direct client's positional compatibility.
        async with BridgeClient(self.stub.config()) as bridge:
            self.assertEqual(WORLD, await bridge.observe("world", None, 13))
            self.assertEqual(SCENE, await bridge.observe("scene", SCENE_CONTEXT["scene_ref"], 23))
        self.assertEqual(2, self.stub.resource_count)
        self.assertEqual({"scope": "world", "limit": 13}, self.stub.requests[1]["body"]["data"])
        self.assertEqual({"scope": "scene", "scene_ref": SCENE_CONTEXT["scene_ref"], "limit": 23},
                         self.stub.requests[2]["body"]["data"])
