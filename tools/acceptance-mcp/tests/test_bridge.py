from __future__ import annotations

import asyncio
import json
from pathlib import Path
import tempfile
import time
import unittest
from uuid import UUID

from solo_acceptance_mcp.bridge import (
    BridgeClient, BridgeConfig, BridgeError, ConfigurationError, load_config,
)
from tests.helpers import BridgeStub, CAPABILITIES, PASSWORD, TOKEN, USERNAME, envelope


class ConfigTests(unittest.TestCase):
    def test_config_repr_and_validation_do_not_expose_credentials(self) -> None:
        config = BridgeConfig(username=USERNAME, password=PASSWORD)
        self.assertNotIn(USERNAME, repr(config))
        self.assertNotIn(PASSWORD, repr(config))
        for url in ["file:///secret", "http://user:secret@localhost", "http://localhost/?secret=1", "http://localhost/path", " http://localhost", "http://localhost\n"]:
            with self.subTest(url=url), self.assertRaises(ConfigurationError):
                BridgeConfig(username=USERNAME, password=PASSWORD, base_url=url)
        for timeout in [True, 0, 31, float("inf")]:
            with self.subTest(timeout=timeout), self.assertRaises(ConfigurationError):
                BridgeConfig(username=USERNAME, password=PASSWORD, timeout_seconds=timeout)

    def test_file_defaults_and_malformed_content(self) -> None:
        temporary_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as directory:
            path = Path(directory) / "private.json"
            path.write_text(json.dumps({"username": USERNAME, "password": PASSWORD}))
            config = load_config(path)
            self.assertEqual("http://127.0.0.1:8686", config.base_url)
            self.assertEqual(10, config.timeout_seconds)
            path.write_text(PASSWORD)
            with self.assertRaises(ConfigurationError) as error:
                load_config(path)
            self.assertNotIn(PASSWORD, str(error.exception))
            path.write_text(json.dumps({"username": USERNAME, "password": PASSWORD, "extra": TOKEN}))
            with self.assertRaises(ConfigurationError):
                load_config(path)


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    async def test_normal_auth_header_envelope_and_token_reuse(self) -> None:
        self.assertEqual(CAPABILITIES, await self.client.capabilities())
        await self.client.observe()
        await self.client.observe("scene", "scene-fixture-1", 25)
        self.assertEqual(1, self.stub.login_count)
        login, capabilities, world, scene = self.stub.requests
        self.assertEqual({"username": USERNAME, "password": PASSWORD}, login["body"]["data"])
        self.assertNotIn("authorization", login["headers"])
        self.assertEqual("Bearer " + TOKEN + "-1", capabilities["headers"]["authorization"])
        self.assertEqual("GET", capabilities["method"])
        self.assertEqual({"scope": "world", "limit": 100}, world["body"]["data"])
        self.assertEqual({"scope": "scene", "scene_ref": "scene-fixture-1", "limit": 25}, scene["body"]["data"])
        UUID(login["body"]["requestId"])
        UUID(world["body"]["requestId"])

    async def test_401_refreshes_once_with_the_same_observation_request_id(self) -> None:
        def reply(request):
            if request["path"] != "/auth/v1/login" and self.stub.resource_count == 1:
                return 401, {"message": PASSWORD + TOKEN}
            return self.stub.default_response(request)
        self.stub.responder = reply
        await self.client.observe()
        self.assertEqual(2, self.stub.login_count)
        resources = [r for r in self.stub.requests if r["path"] != "/auth/v1/login"]
        self.assertEqual(2, len(resources))
        self.assertEqual(resources[0]["body"]["requestId"], resources[1]["body"]["requestId"])
        self.assertNotEqual(resources[0]["headers"]["authorization"], resources[1]["headers"]["authorization"])

    async def test_repeated_401_does_not_loop(self) -> None:
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN})
        )
        with self.assertRaises(BridgeError) as error:
            await self.client.capabilities()
        self.assertEqual("AUTHENTICATION_FAILED", error.exception.code)
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, self.stub.resource_count)
        self.assertNotIn(PASSWORD, json.dumps(error.exception.public_data()))

    async def test_concurrent_expired_token_requests_share_a_refresh(self) -> None:
        await self.client.capabilities()
        def reply(request):
            if request["headers"].get("authorization") == "Bearer " + TOKEN + "-1":
                return 401, {"message": "expired"}
            return self.stub.default_response(request)
        self.stub.responder = reply
        await asyncio.gather(self.client.capabilities(), self.client.capabilities())
        self.assertEqual(2, self.stub.login_count)

    async def test_authentication_business_failure_is_sanitized(self) -> None:
        self.stub.responder = lambda request: (200, {"code": 50001, "message": PASSWORD + TOKEN})
        with self.assertRaises(BridgeError) as error:
            await self.client.capabilities()
        self.assertEqual("BACKEND_REJECTED", error.exception.code)
        self.assertEqual(50001, error.exception.backend_code)
        self.assertEqual(0, self.stub.resource_count)
        self.assertNotIn(PASSWORD, str(error.exception))

    async def test_http_failures_and_backend_error_bodies_are_not_echoed(self) -> None:
        for status, code in [(400, "INVALID_ARGUMENTS"), (403, "ACCESS_DENIED"), (404, "BRIDGE_UNAVAILABLE"),
                             (409, "STALE_OBSERVATION"), (410, "STALE_SCENE"), (503, "SERVER_OFFLINE"), (302, "HTTP_ERROR")]:
            with self.subTest(status=status):
                self.stub.responder = lambda request, status=status: (
                    self.stub.default_response(request) if request["path"] == "/auth/v1/login"
                    else (status, {"message": PASSWORD + TOKEN})
                )
                with self.assertRaises(BridgeError) as error:
                    await self.client.capabilities()
                self.assertEqual(code, error.exception.code)
                self.assertEqual(status, error.exception.http_status)
                encoded = json.dumps(error.exception.public_data())
                self.assertNotIn(PASSWORD, encoded)
                self.assertNotIn(TOKEN, encoded)
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(7, self.stub.resource_count)

    async def test_invalid_backend_payloads_are_sanitized(self) -> None:
        for payload in [PASSWORD.encode(), [], {"code": 40001, "message": PASSWORD}, envelope([])]:
            with self.subTest(payload=type(payload).__name__):
                self.stub.responder = lambda request, payload=payload: (
                    self.stub.default_response(request) if request["path"] == "/auth/v1/login" else (200, payload)
                )
                with self.assertRaises(BridgeError) as error:
                    await self.client.capabilities()
                self.assertNotIn(PASSWORD, str(error.exception))

    async def test_missing_or_invalid_login_token_is_not_accepted(self) -> None:
        for token in [None, "bad\ntoken", 7]:
            with self.subTest(token=type(token).__name__):
                self.stub.responder = lambda request, token=token: (200, envelope({"token": token}))
                with self.assertRaises(BridgeError) as error:
                    await self.client.capabilities()
                self.assertEqual("INVALID_RESPONSE", error.exception.code)
                self.assertEqual(0, self.stub.resource_count)

    async def test_timeout_is_bounded_and_does_not_automatically_replay(self) -> None:
        def reply(request):
            if request["path"] != "/auth/v1/login":
                time.sleep(0.25)
            return self.stub.default_response(request)
        self.stub.responder = reply
        await self.client.close()
        self.client = BridgeClient(self.stub.config(timeout=0.1))
        with self.assertRaises(BridgeError) as error:
            await self.client.capabilities()
        self.assertEqual("HTTP_TIMEOUT", error.exception.code)
        self.assertTrue(error.exception.retryable)
        self.assertEqual(1, self.stub.resource_count)

    async def test_invalid_observation_parameters_fail_before_auth_or_http(self) -> None:
        invalid = [
            {"scope": "unknown"}, {"scope": []}, {"scope": "scene"},
            {"scene_ref": "unexpected"}, {"scope": "scene", "scene_ref": ""},
            {"scope": "scene", "scene_ref": "x\n"}, {"limit": True}, {"limit": 0}, {"limit": 501},
        ]
        for params in invalid:
            with self.subTest(params=params), self.assertRaises(BridgeError) as error:
                await self.client.observe(**params)
            self.assertEqual("INVALID_ARGUMENTS", error.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_response_size_is_bounded(self) -> None:
        self.client.MAX_RESPONSE_BYTES = 256
        self.stub.responder = lambda request: (
            self.stub.default_response(request) if request["path"] == "/auth/v1/login"
            else (200, envelope({"large": "x" * 300}))
        )
        with self.assertRaises(BridgeError) as error:
            await self.client.capabilities()
        self.assertEqual("INVALID_RESPONSE", error.exception.code)

    async def test_disconnected_service_returns_safe_connection_error(self) -> None:
        # A second local socket is closed before the call, so this cannot reach
        # the game service or an unrelated process listening on a fixed port.
        unused = BridgeStub().start()
        config = unused.config(timeout=0.1)
        await asyncio.to_thread(unused.stop)
        async with BridgeClient(config) as disconnected:
            with self.assertRaises(BridgeError) as error:
                await disconnected.capabilities()
            self.assertEqual("HTTP_UNAVAILABLE", error.exception.code)
            self.assertNotIn(PASSWORD, str(error.exception))


