from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from urllib.parse import urlsplit

from mcp import Client, StdioServerParameters

from solo_acceptance_mcp.server import build_server
from tests.helpers import BridgeStub, CAPABILITIES, PASSWORD, TOKEN, envelope


TOOLS = {
    "game_capabilities", "game_observe", "actor_open", "actor_close",
    "operation_get", "operation_cancel",
}
READ_ONLY_TOOLS = {"game_capabilities", "game_observe", "operation_get"}
SESSION_ID = "session-actor-fixture"
OPEN_ID = "operation-open-fixture"
CLOSE_ID = "operation-close-fixture"


def operation(kind: str, status: str) -> dict:
    """Native outcomes are supplied by the HTTP boundary, never inferred by MCP."""
    opened = kind == "OPEN" and status == "SUCCEEDED"
    closed = kind == "CLOSE" and status == "SUCCEEDED"
    actor = {
        "character_id": 42, "account_id": 12, "profile_id": "primary",
        "logged_in_world": True, "client_logged_in": True, "is_current": True,
        "persisted": True, "is_bot": False, "level": 1, "exp": 0,
        "map_id": 10000, "world_id": 0, "channel_id": 1,
        "packet_events": {"items": [], "next_cursor": 0, "cursor_expired": False},
    }
    result = {}
    if opened:
        result = {"observed_outcome": "native_actor_online", "actor": actor}
    elif closed:
        result = {
            "observed_outcome": "native_actor_closed",
            "native_cleanup_verified": True, "persisted_state_verified": True,
        }
    return {
        "schema_version": 1,
        "operation_id": OPEN_ID if kind == "OPEN" else CLOSE_ID,
        "kind": kind, "status": status, "step": "native-fixture-result",
        "session_id": SESSION_ID, "session_generation": 1, "profile_id": "primary",
        "runtime_generation": "generation-fixture-1",
        "cancel_requested": status == "CANCEL_REQUESTED",
        "result": result, "error": None,
        "session": {
            "session_id": SESSION_ID, "session_generation": 1, "profile_id": "primary",
            "runtime_generation": "generation-fixture-1",
            "state": "OPEN" if opened else "CLOSED" if closed else "OPENING",
            "actor_current": opened, "actor": actor if opened else None,
        },
        "events": {
            "items": [{"seq": 1, "at": "2026-10-27T00:00:00Z", "kind": status}],
            "next_cursor": 1, "oldest_cursor": 1, "latest_cursor": 1,
            "cursor_expired": False, "truncated": False,
        },
    }


class ActorProtocolStub(BridgeStub):
    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            capabilities = deepcopy(CAPABILITIES)
            capabilities.update(implementation_round="R2a", enabled_tools=sorted(TOOLS))
            return 200, envelope(capabilities)
        if path == "/solo/acceptance/v1/actor/open":
            return 200, envelope(operation("OPEN", "QUEUED"))
        if path == "/solo/acceptance/v1/actor/close":
            return 200, envelope(operation("CLOSE", "QUEUED"))
        if path.endswith("/cancel"):
            return 200, envelope(operation("OPEN", "CANCEL_REQUESTED"))
        if path == "/solo/acceptance/v1/operation/" + OPEN_ID:
            return 200, envelope(operation("OPEN", "SUCCEEDED"))
        if path == "/solo/acceptance/v1/operation/" + CLOSE_ID:
            return 200, envelope(operation("CLOSE", "SUCCEEDED"))
        return super().default_response(request)


class ActorProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.stub = ActorProtocolStub().start()

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_seven_tools_have_explicit_mutation_and_request_contracts(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(TOOLS | {"game_act"}, set(tools))
        for name, tool in tools.items():
            self.assertEqual(name in READ_ONLY_TOOLS, tool.annotations.read_only_hint, name)
            self.assertTrue(tool.annotations.idempotent_hint, name)
        self.assertTrue(tools["actor_close"].annotations.destructive_hint)
        for name in ["actor_open", "actor_close", "operation_cancel"]:
            self.assertIn("request_id", tools[name].input_schema["required"], name)
        self.assertTrue(tools["game_act"].annotations.destructive_hint)

    async def test_queued_and_cancel_requested_results_are_not_promoted_to_completion(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            opened = await client.call_tool("actor_open", {"profile_id": "primary", "request_id": "open-1"})
            current = await client.call_tool("operation_get", {"operation_id": OPEN_ID, "since_cursor": 0, "limit": 10})
            cancelled = await client.call_tool("operation_cancel", {"operation_id": OPEN_ID, "request_id": "cancel-1"})
            closing = await client.call_tool("actor_close", {
                "session_id": SESSION_ID, "expected_generation": 1, "request_id": "close-1",
            })
        self.assertFalse(opened.is_error)
        self.assertEqual("QUEUED", opened.structured_content["status"])
        self.assertEqual({}, opened.structured_content["result"])
        self.assertEqual("OPENING", opened.structured_content["session"]["state"])
        self.assertEqual("open-1", opened.structured_content["request_id"])
        self.assertEqual("native_actor_online", current.structured_content["result"]["observed_outcome"])
        self.assertTrue(current.structured_content["session"]["actor"]["logged_in_world"])
        self.assertEqual("CANCEL_REQUESTED", cancelled.structured_content["status"])
        self.assertTrue(cancelled.structured_content["cancel_requested"])
        self.assertEqual("QUEUED", closing.structured_content["status"])
        self.assertEqual({}, closing.structured_content["result"])

    async def test_invalid_actor_arguments_do_not_reach_auth_or_game_http(self) -> None:
        cases = [
            ("actor_open", {"profile_id": "primary"}),
            ("actor_open", {"profile_id": "primary", "request_id": "bad-world", "world": True}),
            ("actor_open", {"profile_id": "primary", "request_id": "bad-channel", "channel": 0}),
            ("actor_close", {"session_id": SESSION_ID, "expected_generation": True, "request_id": "bad-gen"}),
            ("actor_close", {"session_id": SESSION_ID, "expected_generation": 1}),
            ("operation_get", {"operation_id": OPEN_ID, "since_cursor": -1}),
            ("operation_get", {"operation_id": OPEN_ID, "limit": True}),
            ("operation_cancel", {"operation_id": OPEN_ID}),
        ]
        async with Client(build_server(self.stub.config())) as client:
            results = [await client.call_tool(name, args) for name, args in cases]
        for case, result in zip(cases, results):
            self.assertTrue(result.is_error, case)
        self.assertEqual([], self.stub.requests)

    async def test_write_failure_is_safe_and_retains_the_explicit_request_id(self) -> None:
        def reject(request: dict) -> tuple[int, object]:
            if request["path"] == "/auth/v1/login":
                return self.stub.default_response(request)
            return 403, {"message": PASSWORD + TOKEN}
        self.stub.responder = reject
        async with Client(build_server(self.stub.config())) as client:
            response = await client.call_tool("actor_open", {"profile_id": "primary", "request_id": "known-write-1"})
        self.assertTrue(response.is_error)
        self.assertEqual("known-write-1", response.structured_content["request_id"])
        self.assertEqual("ACCESS_DENIED", response.structured_content["error"]["code"])
        self.assertFalse(response.structured_content["error"]["retryable"])
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())

    async def test_official_stdio_client_opens_observes_and_closes_through_http(self) -> None:
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
                names = {tool.name for tool in (await client.list_tools()).tools}
                opened = await client.call_tool("actor_open", {"profile_id": "primary", "request_id": "stdio-open"})
                online = await client.call_tool("operation_get", {"operation_id": OPEN_ID})
                closing = await client.call_tool("actor_close", {
                    "session_id": SESSION_ID, "expected_generation": 1, "request_id": "stdio-close",
                })
                closed = await client.call_tool("operation_get", {"operation_id": CLOSE_ID})
        self.assertEqual(TOOLS | {"game_act"}, names)
        self.assertTrue(all(not result.is_error for result in [opened, online, closing, closed]))
        self.assertEqual("QUEUED", opened.structured_content["status"])
        self.assertEqual(42, online.structured_content["session"]["actor"]["character_id"])
        self.assertEqual("CLOSED", closed.structured_content["session"]["state"])
        self.assertTrue(closed.structured_content["result"]["native_cleanup_verified"])
        self.assertTrue(closed.structured_content["result"]["persisted_state_verified"])
        writes = [request for request in self.stub.requests if request["path"].startswith("/solo/acceptance/v1/actor/")]
        self.assertEqual(2, len(writes))
        self.assertEqual(["stdio-open", "stdio-close"], [request["body"]["requestId"] for request in writes])
        self.assertEqual({"profile_id": "primary"}, writes[0]["body"]["data"])
        self.assertEqual({"session_id": SESSION_ID, "expected_generation": 1}, writes[1]["body"]["data"])
        self.assertEqual(1, self.stub.login_count)
        public = json.dumps([result.structured_content for result in [opened, online, closing, closed]])
        self.assertNotIn(PASSWORD, public)
        self.assertNotIn(TOKEN, public)
