from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from threading import Event
import unittest
from uuid import UUID

import httpx

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


RUN_PATH = "/solo/acceptance/v1/script/run"
HELP_PATH = "/solo/acceptance/v1/script/help"
CANCEL_PATH = "/solo/acceptance/v1/script/cancel"
SCRIPT_ARGS = {
    "session_id": "session-script-fixture", "expected_generation": 7,
    "request_id": "script:fixture-1", "kind": "npc", "operation": "start",
    "context_id": None, "options": {"npc_id": 1032110}, "entry": None, "args": [],
    "source": "console.log(parameters.label); ({sum: parameters.a + parameters.b})",
    "parameters": {"label": "原生脚本协议桩", "a": 3, "b": 4}, "timeout_ms": 1000,
}
SCRIPT_HELP = {
    "schema_version": 1, "language": "javascript", "maximum_contexts_per_module": 1,
    "native_environments": [{"kind": "npc", "globals": ["cm"], "host_object": "NPCConversationManager"}],
    "contexts": [{"context_id": "native-context-fixture-1", "kind": "npc", "context_state": "OPEN"}],
}


def script_operation(request_id: str, *, status: str = "SUCCEEDED", error: dict | None = None,
                     closed: bool = False) -> dict:
    """Protocol receipt only; this fixture does not execute a game script or prove engine cleanup."""
    return {
        "schema_version": 1, "operation_id": "operation-script-fixture", "kind": "SCRIPT",
        "request_id": request_id, "session_id": SCRIPT_ARGS["session_id"], "session_generation": 7,
        "runtime_generation": "generation-script-fixture", "status": status,
        "result": {"result": {"sum": 7, "array": [None, True, "中文"]},
                   "stdout": "原生脚本协议桩\n", "stderr": "", "output_truncated": False,
                   "context_id": "native-context-fixture-1", "context_state": "CLOSED" if closed else "OPEN",
                   "engine_exited": True, "context_closed": closed,
                   "invocation_settled": True, "work_settled": True},
        "error": error,
    }


class ScriptStub(BridgeStub):
    def __init__(self) -> None:
        self.script_status = "SUCCEEDED"
        self.script_error: dict | None = None
        super().__init__()

    def default_response(self, request: dict) -> tuple[int, object]:
        if request["path"] == RUN_PATH:
            return 200, envelope(script_operation(request["body"]["requestId"], status=self.script_status,
                                                  error=self.script_error,
                                                  closed=request["body"]["data"]["operation"] == "close"))
        if request["path"] == HELP_PATH:
            return 200, envelope(deepcopy(SCRIPT_HELP))
        if request["path"] == CANCEL_PATH:
            return 200, envelope(script_operation(request["body"]["data"]["target_request_id"],
                                                  status="CANCELLED", closed=True))
        return super().default_response(request)


class ScriptBridgeTests(unittest.IsolatedAsyncioTestCase):
    """HTTP boundary only; native engine termination is checked separately in isolated acceptance."""

    async def asyncSetUp(self) -> None:
        self.stub = ScriptStub().start()
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    async def run_script(self, **changes) -> dict:
        return await self.client.script_run(**{**deepcopy(SCRIPT_ARGS), **changes})

    def requests(self, path: str) -> list[dict]:
        return [request for request in self.stub.requests if request["path"] == path]

    async def test_native_run_and_help_use_authenticated_flat_envelopes_without_local_execution(self) -> None:
        response = await self.run_script()
        help_result = await self.client.script_help(SCRIPT_ARGS["session_id"], 7)
        self.assertEqual(script_operation(SCRIPT_ARGS["request_id"]), response)
        self.assertEqual(SCRIPT_HELP, help_result)
        request = self.requests(RUN_PATH)[0]
        self.assertEqual({"requestId": SCRIPT_ARGS["request_id"],
                          "data": {key: value for key, value in SCRIPT_ARGS.items() if key != "request_id"}}, request["body"])
        self.assertNotIn("environment", request["body"]["data"])
        self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
        self.assertEqual({"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7},
                         self.requests(HELP_PATH)[0]["body"]["data"])
        UUID(self.requests(HELP_PATH)[0]["body"]["requestId"])
        self.assertEqual(1, self.stub.login_count)
        self.assertNotIn(PASSWORD, json.dumps(response))
        self.assertNotIn(TOKEN, json.dumps(response))

    async def test_source_and_native_request_defaults_are_explicit(self) -> None:
        await self.client.script_run(SCRIPT_ARGS["session_id"], 7, request_id="script:defaults",
                                     kind="npc", operation="start")
        self.assertEqual({"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7,
                          "kind": "npc", "operation": "start", "context_id": None, "options": {},
                          "entry": None, "args": [], "source": "", "parameters": {}, "timeout_ms": 1000},
                         self.requests(RUN_PATH)[0]["body"]["data"])

    async def test_script_errors_and_settlement_flags_are_forwarded_as_backend_facts(self) -> None:
        for status, error in [("FAILED", {"kind": "PARSE_ERROR", "line": 2, "column": 3}),
                              ("FAILED", {"kind": "RUNTIME_ERROR", "line": 1, "column": 5}),
                              ("CANCELLED", {"kind": "TIMEOUT"}), ("INDETERMINATE", {"kind": "CLEANUP_UNVERIFIED"})]:
            with self.subTest(error=error):
                self.stub.script_status, self.stub.script_error = status, error
                response = await self.run_script()
                self.assertEqual(script_operation(SCRIPT_ARGS["request_id"], status=status, error=error), response)
        self.assertEqual(4, len(self.requests(RUN_PATH)))

    async def test_invalid_script_data_is_rejected_before_auth_without_echoing_private_values(self) -> None:
        private = PASSWORD + TOKEN + "private-script-input"
        cases = [{"source": value} for value in [None, False, 1, [], {}, "x" * 65537, "\ud800"]]
        cases.extend({"timeout_ms": value} for value in [None, False, 0, 5001, 1.0, "1000", private])
        cases.extend({"parameters": value} for value in [False, 3, [], "string", {"nan": float("nan")},
                                                               {"inf": float("inf")}])
        cases.extend([{"session_id": "session/other"}, {"expected_generation": True}, {"request_id": "bad\nrequest"}])
        for index, changes in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(BridgeError) as caught:
                await self.run_script(**changes)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn(private, json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_help_validates_only_the_role_target_before_authentication(self) -> None:
        for session_id, generation in [("bad/session", 7), (None, 7), (SCRIPT_ARGS["session_id"], True),
                                       (SCRIPT_ARGS["session_id"], 0)]:
            with self.subTest(session_id=session_id, generation=generation), self.assertRaises(BridgeError) as caught:
                await self.client.script_help(session_id, generation)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_json_depth_boundary_remains_client_validation(self) -> None:
        depth_boundary = None
        for _ in range(16):
            depth_boundary = {"nested": depth_boundary}
        await self.run_script(parameters=depth_boundary)
        self.assertEqual(depth_boundary, self.requests(RUN_PATH)[0]["body"]["data"]["parameters"])
        with self.assertRaises(BridgeError) as caught:
            await self.run_script(parameters={"nested": depth_boundary})
        self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual(1, len(self.requests(RUN_PATH)))

    async def test_json_byte_budget_is_authoritative_on_the_server_without_replay(self) -> None:
        # This fixture models the server response, not Jackson's number encoding.
        # The bridge forwards both finite JSON values without its own byte ruling.
        rejected = {"n": 1e20, "padding": "x" * 16340}

        def respond(request: dict):
            if request["path"] == RUN_PATH and request["body"]["data"]["parameters"] == rejected:
                return 400, {"message": "invalid_script_parameters"}
            return self.stub.default_response(request)

        self.stub.responder = respond
        with self.assertRaises(BridgeError) as caught:
            await self.run_script(parameters=rejected)
        self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual(400, caught.exception.http_status)
        self.assertFalse(caught.exception.retryable)
        self.assertEqual(rejected, self.requests(RUN_PATH)[0]["body"]["data"]["parameters"])
        accepted = {"n": 1e20, "padding": "x" * 16339}
        self.assertEqual(script_operation(SCRIPT_ARGS["request_id"]), await self.run_script(parameters=accepted))
        self.assertEqual(2, len(self.requests(RUN_PATH)))
        self.assertEqual([], self.requests(CANCEL_PATH))

    async def test_client_cancellation_waits_for_cleanup_and_preserves_flat_fingerprint(self) -> None:
        run_started, cancel_started, cleanup_complete = Event(), Event(), Event()

        def respond(request: dict):
            if request["path"] == RUN_PATH:
                run_started.set()
                cleanup_complete.wait(3)
                return 200, envelope(script_operation(SCRIPT_ARGS["request_id"], status="CANCELLED", closed=True))
            if request["path"] == CANCEL_PATH:
                cancel_started.set()
                cleanup_complete.wait(3)
            return self.stub.default_response(request)

        self.stub.responder = respond
        task = asyncio.create_task(self.run_script())
        try:
            self.assertTrue(await asyncio.to_thread(run_started.wait, 2))
            task.cancel()
            self.assertTrue(await asyncio.to_thread(cancel_started.wait, 2))
            await asyncio.sleep(0.03)
            self.assertFalse(task.done(), "取消请求已发出仍不能替代后端真实收尾响应。")
            cancel_request = self.requests(CANCEL_PATH)[0]
            self.assertNotEqual(SCRIPT_ARGS["request_id"], cancel_request["body"]["requestId"])
            self.assertEqual({**{key: value for key, value in SCRIPT_ARGS.items() if key != "request_id"},
                              "target_request_id": SCRIPT_ARGS["request_id"]}, cancel_request["body"]["data"])
            self.assertEqual(1, len(self.requests(RUN_PATH)))
        finally:
            cleanup_complete.set()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 3)

    async def test_transport_failure_does_not_replay_and_cleanup_failure_stays_unknown(self) -> None:
        await self.client.capabilities()
        await self.client._http.aclose()
        attempts: list[httpx.Request] = []

        def broken_transport(request: httpx.Request):
            attempts.append(request)
            raise httpx.ReadError(PASSWORD + TOKEN, request=request)

        self.client._http = httpx.AsyncClient(base_url=self.stub.base_url, transport=httpx.MockTransport(broken_transport))
        with self.assertRaises(BridgeError) as caught:
            await self.run_script()
        public = caught.exception.public_data()
        self.assertEqual(SCRIPT_ARGS["request_id"], public["request_id"])
        self.assertTrue(public["error"]["outcome_unknown"])
        self.assertFalse(public["error"]["retryable"])
        self.assertEqual(1, sum(request.url.path == RUN_PATH for request in attempts))
        self.assertEqual(1, sum(request.url.path == CANCEL_PATH for request in attempts))
        self.assertNotIn(PASSWORD, json.dumps(public))
        self.assertNotIn(TOKEN, json.dumps(public))

    async def test_script_http_budget_includes_execution_and_cleanup(self) -> None:
        await self.client.close()
        self.client = BridgeClient(self.stub.config(timeout=30))
        await self.client.capabilities()
        await self.client._http.aclose()
        timeouts: list[dict] = []

        def inspect_transport(request: httpx.Request):
            timeouts.append(request.extensions["timeout"])
            return httpx.Response(200, json=envelope(script_operation(SCRIPT_ARGS["request_id"])))

        self.client._http = httpx.AsyncClient(base_url=self.stub.base_url, transport=httpx.MockTransport(inspect_transport))
        await self.run_script(timeout_ms=5000)
        self.assertEqual(10, timeouts[0]["read"])
