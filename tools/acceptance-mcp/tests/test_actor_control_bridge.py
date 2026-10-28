from __future__ import annotations

import asyncio
import json
import time
import unittest

import httpx

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError, MAX_LONG
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


CONTROL_PATH = "/solo/acceptance/v1/actor/module/control"


class ActorControlBridgeTests(unittest.IsolatedAsyncioTestCase):
    """The HTTP boundary supplies module facts; the client does not infer draining."""

    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.stub.responder = self.control_response
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    def control_response(self, request: dict) -> tuple[int, object]:
        if request["path"] == "/auth/v1/login":
            return self.stub.default_response(request)
        if request["path"] == CONTROL_PATH:
            return 200, envelope({
                "operation_id": "operation-module-fixture",
                "kind": "MODULE_CONTROL",
                "status": "QUEUED",
                "request_id": request["body"]["requestId"],
                "result": {},
                "session": {"runtime": {"module": {"state": "PAUSING", "generation": 3}}},
            })
        return self.stub.default_response(request)

    def control(self, operation: str = "pause", request_id: str = "module:fixture", **overrides):
        arguments = {
            "session_id": "session-module-fixture", "expected_generation": 7,
            "expected_module_generation": 3, "operation": operation,
            "request_id": request_id,
        }
        arguments.update(overrides)
        return self.client.actor_control(**arguments)

    def writes(self) -> list[dict]:
        return [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]

    async def test_pause_and_resume_preserve_auth_envelope_generation_and_pending_native_facts(self) -> None:
        paused = await self.control(request_id="pause:fixture")
        resumed = await self.control("resume", "resume:fixture", expected_module_generation=4)

        self.assertEqual(1, self.stub.login_count)
        writes = self.writes()
        self.assertEqual(2, len(writes))
        for write, operation, module_generation, request_id in [
            (writes[0], "pause", 3, "pause:fixture"),
            (writes[1], "resume", 4, "resume:fixture"),
        ]:
            self.assertEqual("POST", write["method"])
            self.assertEqual(CONTROL_PATH, write["path"])
            self.assertEqual("Bearer " + TOKEN + "-1", write["headers"]["authorization"])
            self.assertEqual({"requestId": request_id, "data": {
                "session_id": "session-module-fixture", "expected_generation": 7,
                "expected_module_generation": module_generation, "operation": operation,
            }}, write["body"])
        for result in [paused, resumed]:
            self.assertEqual("QUEUED", result["status"])
            self.assertEqual({}, result["result"])
            self.assertEqual("PAUSING", result["session"]["runtime"]["module"]["state"])

    async def test_explicit_retry_preserves_request_key_and_backend_deduplication_receipt(self) -> None:
        first = await self.control(request_id="pause:explicit-recovery")
        replay = await self.control(request_id="pause:explicit-recovery")
        self.assertEqual(first, replay)
        self.assertEqual(2, len(self.writes()))
        self.assertEqual(self.writes()[0]["body"], self.writes()[1]["body"])
        self.assertEqual("pause:explicit-recovery", first["request_id"])

    async def test_cancel_requested_receipt_is_not_promoted_to_paused_or_success(self) -> None:
        def pending(request):
            status, value = self.control_response(request)
            if request["path"] == CONTROL_PATH:
                value["data"].update(status="CANCEL_REQUESTED", cancel_requested=True)
            return status, value

        self.stub.responder = pending
        result = await self.control()
        self.assertEqual("CANCEL_REQUESTED", result["status"])
        self.assertTrue(result["cancel_requested"])
        self.assertEqual({}, result["result"])
        self.assertEqual("PAUSING", result["session"]["runtime"]["module"]["state"])
        self.assertEqual(1, len(self.writes()))

    async def test_strict_identifiers_generations_and_supported_operations_fail_before_http(self) -> None:
        cases = [
            {"session_id": "../foreign"}, {"session_id": "session/foreign"},
            {"request_id": ""}, {"request_id": "unsafe\nrequest"},
            *[{field: value} for field in ["expected_generation", "expected_module_generation"]
              for value in [True, False, 0, -1, MAX_LONG + 1, 1.0, "1", None]],
            *[{"operation": value} for value in ["unload", "attach", "PAUSE", "", True, None]],
        ]
        for arguments in cases:
            with self.subTest(arguments=arguments), self.assertRaises(BridgeError) as caught:
                await self.control(**arguments)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_all_control_fields_are_required_and_arbitrary_targets_are_not_accepted(self) -> None:
        valid = {
            "session_id": "session-module-fixture", "expected_generation": 7,
            "expected_module_generation": 3, "operation": "pause", "request_id": "pause-required",
        }
        for omitted in valid:
            arguments = {key: value for key, value in valid.items() if key != omitted}
            with self.subTest(omitted=omitted), self.assertRaises(TypeError):
                await self.client.actor_control(**arguments)
        for field in ["runtime_id", "character_id", "module_id", "handler", "code"]:
            with self.subTest(field=field), self.assertRaises(TypeError):
                await self.client.actor_control(**valid, **{field: PASSWORD})
        self.assertEqual([], self.stub.requests)

    async def test_signed_long_upper_boundary_is_preserved_without_numeric_coercion(self) -> None:
        await self.control(expected_generation=MAX_LONG, expected_module_generation=MAX_LONG)
        data = self.writes()[0]["body"]["data"]
        self.assertIs(type(data["expected_generation"]), int)
        self.assertIs(type(data["expected_module_generation"]), int)
        self.assertEqual(MAX_LONG, data["expected_generation"])
        self.assertEqual(MAX_LONG, data["expected_module_generation"])

    async def test_401_refresh_is_bounded_and_reuses_identical_control_request(self) -> None:
        def stale_once(request):
            if request["path"] == CONTROL_PATH and self.stub.resource_count == 1:
                return 401, {"message": PASSWORD + TOKEN}
            return self.control_response(request)

        self.stub.responder = stale_once
        await self.control(request_id="pause-refresh")
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, len(self.writes()))
        self.assertEqual(self.writes()[0]["body"], self.writes()[1]["body"])
        self.assertNotEqual(self.writes()[0]["headers"]["authorization"], self.writes()[1]["headers"]["authorization"])

        self.stub.responder = lambda request: self.control_response(request) if request["path"] == "/auth/v1/login" else (401, {"message": PASSWORD})
        previous_writes, previous_logins = len(self.writes()), self.stub.login_count
        with self.assertRaises(BridgeError) as caught:
            await self.control(request_id="pause-auth-rejected")
        self.assertEqual(2, len(self.writes()) - previous_writes)
        self.assertEqual(1, self.stub.login_count - previous_logins)
        self.assertEqual("pause-auth-rejected", caught.exception.public_data()["request_id"])
        self.assertFalse(caught.exception.outcome_unknown)

    async def test_timeout_is_not_replayed_and_preserves_unknown_outcome_checkpoint(self) -> None:
        def slow(request):
            if request["path"] == CONTROL_PATH:
                time.sleep(0.25)
            return self.control_response(request)

        self.stub.responder = slow
        await self.client.close()
        self.client = BridgeClient(self.stub.config(timeout=0.1))
        with self.assertRaises(BridgeError) as caught:
            await self.control(request_id="pause-timeout")
        data = caught.exception.public_data()
        self.assertEqual("HTTP_TIMEOUT", data["error"]["code"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertFalse(data["error"]["retryable"])
        self.assertEqual("pause-timeout", data["request_id"])
        self.assertEqual(1, len(self.writes()))

    async def test_connection_failure_after_auth_does_not_replay_or_disclose_transport_data(self) -> None:
        await self.client.capabilities()
        await self.client._http.aclose()
        attempts = []

        def broken(request):
            attempts.append(request)
            raise httpx.ReadError(PASSWORD + TOKEN, request=request)

        self.client._http = httpx.AsyncClient(base_url=self.stub.base_url, transport=httpx.MockTransport(broken))
        with self.assertRaises(BridgeError) as caught:
            await self.control("resume", "resume-disconnected")
        data = caught.exception.public_data()
        self.assertEqual(1, len(attempts))
        self.assertEqual("HTTP_UNAVAILABLE", data["error"]["code"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertFalse(data["error"]["retryable"])
        self.assertEqual("resume-disconnected", data["request_id"])
        self.assertNotIn(PASSWORD, json.dumps(data))
        self.assertNotIn(TOKEN, json.dumps(data))

    async def test_http_rejections_keep_status_request_and_known_or_unknown_outcome(self) -> None:
        expected = {400: "INVALID_ARGUMENTS", 403: "ACCESS_DENIED", 404: "BRIDGE_UNAVAILABLE",
                    409: "OPERATION_CONFLICT", 410: "SESSION_EXPIRED", 429: "CAPACITY_REACHED",
                    503: "SERVER_OFFLINE"}
        for status, code in expected.items():
            self.stub.responder = lambda request, status=status: self.control_response(request) if request["path"] == "/auth/v1/login" else (status, {"message": PASSWORD + TOKEN})
            before = len(self.writes())
            with self.subTest(status=status), self.assertRaises(BridgeError) as caught:
                await self.control(request_id="pause-rejected")
            data = caught.exception.public_data()
            self.assertEqual(code, data["error"]["code"])
            self.assertEqual(status, data["error"]["http_status"])
            self.assertEqual(status >= 500, data["error"]["outcome_unknown"])
            self.assertFalse(data["error"]["retryable"])
            self.assertEqual("pause-rejected", data["request_id"])
            self.assertEqual(1, len(self.writes()) - before)
            self.assertNotIn(PASSWORD, json.dumps(data))
            self.assertNotIn(TOKEN, json.dumps(data))

    async def test_mismatched_request_receipt_is_unknown_and_does_not_replace_original_id(self) -> None:
        def mismatched(request):
            status, value = self.control_response(request)
            if request["path"] == CONTROL_PATH:
                value["data"]["request_id"] = "foreign-request"
            return status, value

        self.stub.responder = mismatched
        with self.assertRaises(BridgeError) as caught:
            await self.control(request_id="pause-original")
        data = caught.exception.public_data()
        self.assertEqual("INVALID_RESPONSE", data["error"]["code"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertEqual("pause-original", data["request_id"])
        self.assertNotIn("foreign-request", json.dumps(data))
        self.assertEqual(1, len(self.writes()))


if __name__ == "__main__":
    unittest.main()
