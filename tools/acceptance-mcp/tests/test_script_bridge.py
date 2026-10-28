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
    "request_id": "script:fixture-1", "source": "console.log(parameters.label); ({sum: parameters.a + parameters.b})",
    "parameters": {"label": "脚本协议桩", "a": 3, "b": 4}, "timeout_ms": 1000,
}


def script_operation(request_id: str, *, status: str = "SUCCEEDED", error: dict | None = None) -> dict:
    """Only protocol fixture data; no script engine or game result is simulated."""
    return {
        "schema_version": 1, "operation_id": "operation-script-fixture", "kind": "SCRIPT",
        "request_id": request_id, "session_id": SCRIPT_ARGS["session_id"], "session_generation": 7,
        "runtime_generation": "generation-script-fixture", "status": status,
        "result": {"value": {"sum": 7, "array": [None, True, "中文"]},
                   "output": [{"stream": "stdout", "text": "脚本协议桩\n"}],
                   "engine_exited": True, "context_closed": True, "work_settled": True},
        "error": error,
    }


class ScriptStub(BridgeStub):
    def __init__(self) -> None:
        self.script_status = "SUCCEEDED"
        self.script_error: dict | None = None
        super().__init__()

    def default_response(self, request: dict) -> tuple[int, object]:
        if request["path"] == RUN_PATH:
            return 200, envelope(script_operation(request["body"]["requestId"],
                                                  status=self.script_status, error=self.script_error))
        if request["path"] == HELP_PATH:
            names = request["body"]["data"]["names"]
            return 200, envelope({"schema_version": 1, "language": "javascript", "functions": [],
                                  "unavailable": list(names)})
        if request["path"] == CANCEL_PATH:
            return 200, envelope(script_operation(request["body"]["requestId"], status="CANCELLED"))
        return super().default_response(request)


class ScriptBridgeTests(unittest.IsolatedAsyncioTestCase):
    """HTTP protocol boundary only; real engine termination belongs to isolated acceptance."""

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

    async def test_run_and_empty_help_use_real_authenticated_envelopes_without_local_execution(self) -> None:
        response = await self.run_script()
        help_result = await self.client.script_help(SCRIPT_ARGS["session_id"], 7)
        self.assertEqual(script_operation(SCRIPT_ARGS["request_id"]), response)
        self.assertEqual({"schema_version": 1, "language": "javascript", "functions": [], "unavailable": []}, help_result)
        request = self.requests(RUN_PATH)[0]
        self.assertEqual({"requestId": SCRIPT_ARGS["request_id"],
                          "data": {key: value for key, value in SCRIPT_ARGS.items() if key != "request_id"}}, request["body"])
        self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
        self.assertEqual({"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7, "names": []},
                         self.requests(HELP_PATH)[0]["body"]["data"])
        UUID(self.requests(HELP_PATH)[0]["body"]["requestId"])
        self.assertEqual(1, self.stub.login_count)
        self.assertNotIn(PASSWORD, json.dumps(response))
        self.assertNotIn(TOKEN, json.dumps(response))

    async def test_parameters_and_deadline_defaults_are_explicit_and_help_preserves_unknown_names(self) -> None:
        await self.client.script_run(SCRIPT_ARGS["session_id"], 7, request_id="script:defaults", source="1 + 2")
        self.assertEqual({"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7,
                          "source": "1 + 2", "parameters": {}, "timeout_ms": 1000}, self.requests(RUN_PATH)[0]["body"]["data"])
        help_result = await self.client.script_help(SCRIPT_ARGS["session_id"], 7, names=["missing", "module_fn"])
        self.assertEqual([], help_result["functions"])
        self.assertEqual(["missing", "module_fn"], help_result["unavailable"])

    async def test_script_errors_and_settlement_flags_are_backend_facts_and_are_forwarded(self) -> None:
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
        cases = [{"source": value} for value in [None, False, 1, [], {}, "", "x" * 65537, "\ud800"]]
        cases.extend({"timeout_ms": value} for value in [None, False, 0, 5001, 1.0, "1000", private])
        cases.extend({"parameters": value} for value in [False, 3, [], "string", {"nan": float("nan")},
                                                               {"inf": float("inf")}, {"text": "x" * 65537}])
        cases.extend([{"session_id": "session/other"}, {"expected_generation": True}, {"request_id": "bad\nrequest"}])
        for index, changes in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(BridgeError) as caught:
                await self.run_script(**changes)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn(private, json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_help_names_are_strictly_bounded_before_authentication(self) -> None:
        for names in [False, "missing", {}, [None], [True], ["bad/name"], [""], ["f"] * 65,
                      ["module.fn"], ["$function"], ["0function"], ["a" * 65]]:
            with self.subTest(names=names), self.assertRaises(BridgeError) as caught:
                await self.client.script_help(SCRIPT_ARGS["session_id"], 7, names=names)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_help_names_accept_exactly_the_runtime_identifier_boundaries(self) -> None:
        names = ["f", "_", "_function42", "a" * 64]
        result = await self.client.script_help(SCRIPT_ARGS["session_id"], 7, names=names)
        self.assertEqual(names, result["unavailable"])
        self.assertEqual(names, self.requests(HELP_PATH)[0]["body"]["data"]["names"])

    async def test_parameters_accept_16k_utf8_and_depth_16_without_altering_json(self) -> None:
        unicode_boundary = {"x": "汉" * 5458 + "aa"}
        ascii_boundary = {"x": "a" * 16376}
        depth_boundary = None
        for _ in range(16):
            depth_boundary = {"nested": depth_boundary}
        for parameters in [unicode_boundary, ascii_boundary, depth_boundary]:
            with self.subTest(kind="depth" if parameters == depth_boundary else "size"):
                if parameters != depth_boundary:
                    self.assertEqual(16384, len(json.dumps(parameters, ensure_ascii=False, separators=(",", ":")).encode("utf-8")))
                await self.run_script(parameters=parameters)
                self.assertEqual(parameters, self.requests(RUN_PATH)[-1]["body"]["data"]["parameters"])
        self.assertEqual(3, len(self.requests(RUN_PATH)))

    async def test_parameters_over_16k_or_depth_16_are_rejected_before_authentication(self) -> None:
        too_deep = None
        for _ in range(17):
            too_deep = {"nested": too_deep}
        invalid = [{"x": "a" * 16377}, {"x": "汉" * 5458 + "aaa"}, too_deep]
        for parameters in invalid:
            with self.subTest(kind="depth" if parameters == too_deep else "size"), self.assertRaises(BridgeError) as caught:
                await self.run_script(parameters=parameters)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_client_cancellation_waits_for_cancel_response_and_preserves_original_fingerprint(self) -> None:
        run_started, cancel_started, cleanup_complete = Event(), Event(), Event()

        def respond(request: dict):
            if request["path"] == RUN_PATH:
                run_started.set()
                cleanup_complete.wait(3)
                return 200, envelope(script_operation(SCRIPT_ARGS["request_id"], status="CANCELLED"))
            if request["path"] == CANCEL_PATH:
                cancel_started.set()
                cleanup_complete.wait(3)
                return self.stub.default_response(request)
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

    async def test_transport_failure_does_not_replay_run_and_cleanup_failure_stays_unknown(self) -> None:
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

    async def test_script_http_deadline_includes_execution_and_cleanup_budget(self) -> None:
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

