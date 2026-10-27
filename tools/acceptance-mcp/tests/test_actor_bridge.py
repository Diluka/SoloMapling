from __future__ import annotations

import asyncio
import json
import time
import unittest
from urllib.parse import parse_qs, urlsplit

import httpx

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


class ActorBridgeTests(unittest.IsolatedAsyncioTestCase):
    """Exercise actor writes against an isolated HTTP boundary, never a game server."""

    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.stub.responder = self.actor_response
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    def actor_response(self, request):
        path = urlsplit(request["path"]).path
        if path in {"/auth/v1/login", "/solo/acceptance/v1/capabilities"}:
            return self.stub.default_response(request)
        if path in {"/solo/acceptance/v1/actor/open", "/solo/acceptance/v1/actor/close"}:
            return 200, envelope({
                "operation_id": "operation-fixture-1",
                "status": "QUEUED",
                "request_id": request["body"]["requestId"],
            })
        if path == "/solo/acceptance/v1/operation/operation-fixture-1/cancel":
            return 200, envelope({
                "operation_id": "operation-fixture-1",
                "status": "CANCEL_REQUESTED",
                "request_id": request["body"]["requestId"],
            })
        if path == "/solo/acceptance/v1/operation/operation-fixture-1":
            return 200, envelope({
                "operation_id": "operation-fixture-1",
                "status": "RUNNING",
                "events": {"items": [], "next_cursor": 4},
            })
        return 404, {"message": PASSWORD + TOKEN}

    async def test_actor_lifecycle_requests_preserve_authentication_envelopes_and_pending_results(self) -> None:
        opened = await self.client.actor_open("primary", request_id="open:fixture-1", world=0, channel=1)
        closed = await self.client.actor_close("session-fixture-1", 7, request_id="close:fixture-1")
        progress = await self.client.operation_get("operation-fixture-1", since_cursor=4, limit=7)
        cancelled = await self.client.operation_cancel("operation-fixture-1", request_id="cancel:fixture-1")

        self.assertEqual("QUEUED", opened["status"])
        self.assertEqual("QUEUED", closed["status"])
        self.assertEqual("RUNNING", progress["status"])
        self.assertEqual("CANCEL_REQUESTED", cancelled["status"])
        self.assertEqual(1, self.stub.login_count)
        resources = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual(4, len(resources))
        self.assertTrue(all(request["headers"].get("authorization") == "Bearer " + TOKEN + "-1" for request in resources))
        self.assertEqual({"requestId": "open:fixture-1", "data": {"profile_id": "primary", "world": 0, "channel": 1}}, resources[0]["body"])
        self.assertEqual({"requestId": "close:fixture-1", "data": {"session_id": "session-fixture-1", "expected_generation": 7}}, resources[1]["body"])
        self.assertEqual("GET", resources[2]["method"])
        self.assertEqual({"since_cursor": ["4"], "limit": ["7"]}, parse_qs(urlsplit(resources[2]["path"]).query))
        self.assertIsNone(resources[2]["body"])
        self.assertEqual({"requestId": "cancel:fixture-1", "data": {}}, resources[3]["body"])

    async def test_open_omits_unselected_world_and_channel_and_restores_the_explicit_request_id(self) -> None:
        def reply(request):
            status, payload = self.actor_response(request)
            if urlsplit(request["path"]).path == "/solo/acceptance/v1/actor/open":
                payload["data"].pop("request_id")
            return status, payload

        self.stub.responder = reply
        result = await self.client.actor_open("primary", request_id="open-with-default-route")
        self.assertEqual("open-with-default-route", result["request_id"])
        self.assertEqual({"profile_id": "primary"}, self.stub.requests[-1]["body"]["data"])

    async def test_401_refresh_replays_each_write_once_with_the_identical_request_body(self) -> None:
        calls = [
            lambda: self.client.actor_open("primary", request_id="open-retry"),
            lambda: self.client.actor_close("session-fixture-1", 7, request_id="close-retry"),
            lambda: self.client.operation_cancel("operation-fixture-1", request_id="cancel-retry"),
        ]
        for call in calls:
            await self.client.close()
            self.client = BridgeClient(self.stub.config())
            self.stub.requests.clear()
            self.stub.login_count = 0
            self.stub.resource_count = 0

            def reply(request):
                if request["path"] != "/auth/v1/login" and self.stub.resource_count == 1:
                    return 401, {"message": PASSWORD + TOKEN}
                return self.actor_response(request)

            self.stub.responder = reply
            await call()
            writes = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
            self.assertEqual(2, self.stub.login_count)
            self.assertEqual(2, len(writes))
            self.assertEqual(writes[0]["body"], writes[1]["body"])
            self.assertNotEqual(writes[0]["headers"]["authorization"], writes[1]["headers"]["authorization"])

    async def test_repeated_401_does_not_loop_or_lose_the_write_request_id(self) -> None:
        self.stub.responder = lambda request: (
            self.actor_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN})
        )
        with self.assertRaises(BridgeError) as caught:
            await self.client.actor_open("primary", request_id="auth-rejected-open")
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, self.stub.resource_count)
        self.assertEqual("auth-rejected-open", caught.exception.public_data()["request_id"])
        self.assertNotIn(PASSWORD, json.dumps(caught.exception.public_data()))

    async def test_write_timeout_is_not_replayed_and_keeps_the_unknown_outcome_request_id(self) -> None:
        def reply(request):
            if request["path"] != "/auth/v1/login":
                time.sleep(0.25)
            return self.actor_response(request)

        self.stub.responder = reply
        await self.client.close()
        self.client = BridgeClient(self.stub.config(timeout=0.1))
        with self.assertRaises(BridgeError) as caught:
            await self.client.actor_open("primary", request_id="timed-out-open")
        data = caught.exception.public_data()
        self.assertEqual("HTTP_TIMEOUT", data["error"]["code"])
        self.assertFalse(data["error"]["retryable"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertEqual("timed-out-open", data["request_id"])
        self.assertEqual(1, self.stub.resource_count)
        self.assertNotIn(PASSWORD, json.dumps(data))

    async def test_write_connection_failure_is_not_replayed_and_does_not_expose_transport_details(self) -> None:
        # Keep the real authentication path, then isolate a connection failure at
        # the HTTP transport boundary after the write has been handed to it.
        await self.client.capabilities()
        await self.client._http.aclose()
        attempts = []

        def broken_transport(request):
            attempts.append(request)
            raise httpx.ReadError(PASSWORD + TOKEN, request=request)

        self.client._http = httpx.AsyncClient(
            base_url=self.stub.base_url, transport=httpx.MockTransport(broken_transport)
        )
        with self.assertRaises(BridgeError) as caught:
            await self.client.operation_cancel("operation-fixture-1", request_id="connection-lost-cancel")
        data = caught.exception.public_data()
        self.assertEqual(1, len(attempts))
        self.assertEqual("HTTP_UNAVAILABLE", data["error"]["code"])
        self.assertFalse(data["error"]["retryable"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertEqual("connection-lost-cancel", data["request_id"])
        self.assertNotIn(PASSWORD, json.dumps(data))
        self.assertNotIn(TOKEN, json.dumps(data))

    async def test_actor_http_failures_preserve_status_without_echoing_sensitive_backend_bodies(self) -> None:
        for status in [400, 403, 404, 409, 410, 429, 503]:
            with self.subTest(status=status):
                self.stub.responder = lambda request, status=status: (
                    self.actor_response(request) if request["path"] == "/auth/v1/login"
                    else (status, {"message": PASSWORD + TOKEN, "requestId": "backend-internal"})
                )
                with self.assertRaises(BridgeError) as caught:
                    await self.client.actor_close("session-fixture-1", 7, request_id="rejected-close")
                data = caught.exception.public_data()
                self.assertEqual(status, caught.exception.http_status)
                self.assertEqual("rejected-close", data["request_id"])
                self.assertNotIn(PASSWORD, json.dumps(data))
                self.assertNotIn(TOKEN, json.dumps(data))
                self.assertNotIn("backend-internal", json.dumps(data))
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(7, self.stub.resource_count)

    async def test_invalid_actor_identifiers_and_request_ids_fail_before_http(self) -> None:
        invalid = [
            lambda: self.client.actor_open("../primary", request_id="safe-id"),
            lambda: self.client.actor_open("x" * 65, request_id="safe-id"),
            lambda: self.client.actor_open("primary", request_id=""),
            lambda: self.client.actor_close("session/other", 7, request_id="safe-id"),
            lambda: self.client.operation_get("operation?all=true"),
            lambda: self.client.operation_cancel("operation-fixture-1", request_id="unsafe\nrequest"),
        ]
        for call in invalid:
            with self.assertRaises(BridgeError) as caught:
                await call()
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_actor_numeric_boundaries_are_strict_and_write_ids_are_required(self) -> None:
        invalid = [
            lambda: self.client.actor_open("primary", request_id="safe-id", world=True),
            lambda: self.client.actor_open("primary", request_id="safe-id", channel=0),
            lambda: self.client.actor_close("session-fixture-1", 0, request_id="safe-id"),
            lambda: self.client.actor_close("session-fixture-1", 2**63, request_id="safe-id"),
            lambda: self.client.operation_get("operation-fixture-1", since_cursor=-1),
            lambda: self.client.operation_get("operation-fixture-1", limit=True),
            lambda: self.client.operation_get("operation-fixture-1", limit=501),
        ]
        for call in invalid:
            with self.assertRaises(BridgeError) as caught:
                await call()
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        for call in [
            lambda: self.client.actor_open("primary"),
            lambda: self.client.actor_close("session-fixture-1", 7),
            lambda: self.client.operation_cancel("operation-fixture-1"),
        ]:
            with self.assertRaises(TypeError):
                await call()
        self.assertEqual([], self.stub.requests)


if __name__ == "__main__":
    unittest.main()
