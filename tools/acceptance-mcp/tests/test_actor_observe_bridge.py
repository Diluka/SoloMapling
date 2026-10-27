from __future__ import annotations

import asyncio
import json
import unittest
from uuid import UUID

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import BridgeStub, PASSWORD, SCENE, TOKEN, WORLD, envelope


ACTOR = {
    "schema_version": 1,
    "scope": "actor",
    "runtime_generation": "runtime-http-fixture",
    "session_id": "session-http-fixture",
    "session_generation": 17,
    "profile_id": "primary",
    "actor": {"character_id": 991, "is_bot": False},
    "events": {
        "items": [{
            "sequence": 6,
            "opcode": 68,
            "length": 38,
            "event_type": "server_message",
            "message_type": 4,
            "ticker_enabled": True,
            "text": "测试频道公告",
        }],
        "next_cursor": 6,
        "oldest_cursor": 1,
        "latest_cursor": 9,
        "cursor_expired": False,
        "truncated": True,
        "capacity": 256,
    },
}


class ActorObserveBridgeTests(unittest.IsolatedAsyncioTestCase):
    """Exercise actor observations on local HTTP, without a game or database."""

    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.stub.responder = self.actor_response
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    def actor_response(self, request):
        if request["path"] == "/solo/acceptance/v1/actor/observe":
            return 200, envelope(ACTOR)
        return self.stub.default_response(request)

    async def test_actor_observation_uses_authenticated_readonly_envelopes_and_native_results(self) -> None:
        default_result = await self.client.observe(
            "actor", session_id="session-http-fixture", expected_generation=17,
        )
        page_result = await self.client.observe(
            "actor", None, 3, session_id="session-http-fixture",
            expected_generation=17, since_cursor=5,
        )

        self.assertEqual(ACTOR, default_result)
        self.assertEqual(ACTOR, page_result)
        self.assertNotIn("request_id", default_result)
        self.assertEqual(1, self.stub.login_count)
        requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual(2, len(requests))
        expected_data = [
            {"session_id": "session-http-fixture", "expected_generation": 17, "since_cursor": 0, "limit": 100},
            {"session_id": "session-http-fixture", "expected_generation": 17, "since_cursor": 5, "limit": 3},
        ]
        request_ids = []
        for request, data in zip(requests, expected_data):
            self.assertEqual("POST", request["method"])
            self.assertEqual("/solo/acceptance/v1/actor/observe", request["path"])
            self.assertEqual("Bearer " + TOKEN + "-1", request["headers"].get("authorization"))
            self.assertEqual("application/json", request["headers"].get("content-type"))
            self.assertEqual({"requestId", "data"}, set(request["body"]))
            self.assertEqual(data, request["body"]["data"])
            request_ids.append(UUID(request["body"]["requestId"]))
        self.assertNotEqual(request_ids[0], request_ids[1])

    async def test_world_and_scene_keep_the_original_route_body_and_positional_arguments(self) -> None:
        self.assertEqual(WORLD, await self.client.observe())
        self.assertEqual(SCENE, await self.client.observe("scene", "scene-fixture-1", 25))
        self.assertEqual(WORLD, await self.client.observe(
            "world", None, 500, session_id=None, expected_generation=None, since_cursor=0,
        ))
        self.assertEqual(SCENE, await self.client.observe(
            "scene", "scene-fixture-1", 1, session_id=None, expected_generation=None, since_cursor=0,
        ))

        requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual(4, len(requests))
        for request, data in zip(requests, [
            {"scope": "world", "limit": 100},
            {"scope": "scene", "scene_ref": "scene-fixture-1", "limit": 25},
            {"scope": "world", "limit": 500},
            {"scope": "scene", "scene_ref": "scene-fixture-1", "limit": 1},
        ]):
            self.assertEqual("POST", request["method"])
            self.assertEqual("/solo/acceptance/v1/observe", request["path"])
            self.assertEqual({"requestId", "data"}, set(request["body"]))
            UUID(request["body"]["requestId"])
            self.assertEqual(data, request["body"]["data"])

    async def test_actor_integer_and_session_boundaries_are_transmitted_without_coercion(self) -> None:
        cases = [
            ("s", 1, 0, 1),
            ("a" * 124 + "_.-9", 2**63 - 1, 2**63 - 1, 500),
        ]
        for session_id, generation, cursor, limit in cases:
            with self.subTest(generation=generation, cursor=cursor, limit=limit):
                await self.client.observe(
                    "actor", limit=limit, session_id=session_id,
                    expected_generation=generation, since_cursor=cursor,
                )
                self.assertEqual({
                    "session_id": session_id,
                    "expected_generation": generation,
                    "since_cursor": cursor,
                    "limit": limit,
                }, self.stub.requests[-1]["body"]["data"])

    async def test_actor_parameters_remain_keyword_only(self) -> None:
        with self.assertRaises(TypeError):
            await self.client.observe("actor", None, 100, "session-http-fixture", 17, 0)
        self.assertEqual([], self.stub.requests)

    async def test_invalid_scope_and_parameter_combinations_fail_before_authentication_or_http(self) -> None:
        invalid = [
            {"scope": "unknown"}, {"scope": None}, {"scope": []},
            {"scope": "actor"},
            {"scope": "actor", "session_id": "session-http-fixture"},
            {"scope": "actor", "expected_generation": 17},
            {"scope": "actor", "session_id": "session-http-fixture", "expected_generation": 17, "scene_ref": "scene-fixture-1"},
            {"scope": "actor", "session_id": "session-http-fixture", "expected_generation": 17, "scene_ref": ""},
            {"scope": "world", "scene_ref": "scene-fixture-1"},
            {"scope": "scene"},
            {"scope": "scene", "scene_ref": ""},
            {"scope": "scene", "scene_ref": "scene\nfixture"},
        ]
        for scope, scene_ref in [("world", None), ("scene", "scene-fixture-1")]:
            base = {"scope": scope, "scene_ref": scene_ref}
            invalid.extend([
                {**base, "session_id": "session-http-fixture"},
                {**base, "session_id": ""},
                {**base, "expected_generation": 17},
                {**base, "expected_generation": 0},
                {**base, "since_cursor": 1},
            ])
        for params in invalid:
            with self.subTest(params=params), self.assertRaises(BridgeError) as caught:
                await self.client.observe(**params)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_non_integer_or_out_of_range_numeric_parameters_fail_before_http(self) -> None:
        invalid_values = {
            "limit": [None, False, True, 0, 501, 1.0, "1"],
            "expected_generation": [False, True, 0, 2**63, 1.0, "1"],
            "since_cursor": [None, False, True, -1, 2**63, 0.0, "0"],
        }
        for field, values in invalid_values.items():
            for value in values:
                params = {"scope": "actor", "session_id": "session-http-fixture", "expected_generation": 17}
                params[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(BridgeError) as caught:
                    await self.client.observe(**params)
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        for scope, scene_ref in [("world", None), ("scene", "scene-fixture-1")]:
            for value in [False, 0.0, "0"]:
                with self.subTest(scope=scope, value=value), self.assertRaises(BridgeError) as caught:
                    await self.client.observe(scope, scene_ref, since_cursor=value)
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_invalid_session_identifiers_fail_before_http(self) -> None:
        for session_id in [
            "", " ", "session\nfixture", "session/fixture", "session:fixture",
            "session?all=true", ".", "..", "x" * 129, 991, True, [], {},
        ]:
            with self.subTest(session_id=session_id), self.assertRaises(BridgeError) as caught:
                await self.client.observe("actor", session_id=session_id, expected_generation=17)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_401_refreshes_once_and_reuses_the_identical_readonly_envelope(self) -> None:
        def reply(request):
            if request["path"] != "/auth/v1/login" and self.stub.resource_count == 1:
                return 401, {"message": PASSWORD + TOKEN}
            return self.actor_response(request)

        self.stub.responder = reply
        self.assertEqual(ACTOR, await self.client.observe(
            "actor", limit=7, session_id="session-http-fixture", expected_generation=17, since_cursor=5,
        ))
        requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, len(requests))
        self.assertEqual(requests[0]["method"], requests[1]["method"])
        self.assertEqual(requests[0]["path"], requests[1]["path"])
        self.assertEqual(requests[0]["body"], requests[1]["body"])
        UUID(requests[0]["body"]["requestId"])
        self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_repeated_401_stops_after_one_refresh_and_preserves_the_safe_http_error(self) -> None:
        self.stub.responder = lambda request: (
            self.actor_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN})
        )
        with self.assertRaises(BridgeError) as caught:
            await self.client.observe("actor", session_id="session-http-fixture", expected_generation=17)
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, self.stub.resource_count)
        self.assertEqual(401, caught.exception.http_status)
        self.assertEqual(401, caught.exception.public_data()["error"]["http_status"])
        self.assertNotIn(PASSWORD, str(caught.exception))
        self.assertNotIn(TOKEN, json.dumps(caught.exception.public_data()))

    async def test_410_distinguishes_expired_actor_sessions_from_existing_scene_and_world_errors(self) -> None:
        self.stub.responder = lambda request: (
            self.actor_response(request) if request["path"] == "/auth/v1/login"
            else (410, {"message": PASSWORD + TOKEN, "detail": "private-expired-observation"})
        )
        cases = [
            ("actor", {"session_id": "session-http-fixture", "expected_generation": 17}, "SESSION_EXPIRED"),
            ("scene", {"scene_ref": "scene-fixture-1"}, "STALE_SCENE"),
            ("world", {}, "STALE_SCENE"),
        ]
        for scope, parameters, expected_code in cases:
            with self.subTest(scope=scope), self.assertRaises(BridgeError) as caught:
                await self.client.observe(scope, **parameters)
            failure = caught.exception
            data = failure.public_data()
            self.assertEqual(expected_code, failure.code)
            self.assertEqual(expected_code, data["error"]["code"])
            self.assertEqual(410, failure.http_status)
            self.assertEqual(410, data["error"]["http_status"])
            self.assertFalse(failure.retryable)
            self.assertFalse(data["error"]["retryable"])
            self.assertEqual(str(failure), data["error"]["message"])
            if scope == "actor":
                for instruction in ["重新", "打开", "角色", "session_id", "expected_generation"]:
                    self.assertIn(instruction, str(failure))
                self.assertNotIn("world", str(failure).lower())
            else:
                self.assertEqual("场景引用已失效，请重新观察 world。", str(failure))
            for secret in [PASSWORD, TOKEN, "private-expired-observation"]:
                self.assertNotIn(secret, str(failure))
                self.assertNotIn(secret, json.dumps(data))
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(3, self.stub.resource_count)

    async def test_actor_401_then_410_preserves_session_expiry_after_one_identical_envelope_retry(self) -> None:
        def reply(request):
            if request["path"] == "/auth/v1/login":
                return self.actor_response(request)
            status = 401 if self.stub.resource_count == 1 else 410
            return status, {"message": PASSWORD + TOKEN, "detail": "private-expired-observation"}

        self.stub.responder = reply
        with self.assertRaises(BridgeError) as caught:
            await self.client.observe(
                "actor", limit=7, session_id="session-http-fixture", expected_generation=17, since_cursor=5,
            )
        failure = caught.exception
        data = failure.public_data()
        self.assertEqual("SESSION_EXPIRED", failure.code)
        self.assertEqual("SESSION_EXPIRED", data["error"]["code"])
        self.assertEqual(410, failure.http_status)
        self.assertEqual(410, data["error"]["http_status"])
        self.assertFalse(failure.retryable)
        self.assertFalse(data["error"]["retryable"])
        for instruction in ["重新", "打开", "角色", "session_id", "expected_generation"]:
            self.assertIn(instruction, str(failure))
        self.assertNotIn("world", str(failure).lower())
        for secret in [PASSWORD, TOKEN, "private-expired-observation"]:
            self.assertNotIn(secret, str(failure))
            self.assertNotIn(secret, json.dumps(data))
        requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, len(requests))
        self.assertTrue(all(request["path"] == "/solo/acceptance/v1/actor/observe" for request in requests))
        self.assertEqual(requests[0]["method"], requests[1]["method"])
        self.assertEqual(requests[0]["body"], requests[1]["body"])
        UUID(requests[0]["body"]["requestId"])
        self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_actor_http_failures_preserve_status_without_echoing_backend_content(self) -> None:
        for status in [400, 403, 404, 409, 410, 429, 503, 302]:
            with self.subTest(status=status):
                self.stub.responder = lambda request, status=status: (
                    self.actor_response(request) if request["path"] == "/auth/v1/login"
                    else (status, {"message": PASSWORD + TOKEN, "detail": "backend-private-content"})
                )
                with self.assertRaises(BridgeError) as caught:
                    await self.client.observe("actor", session_id="session-http-fixture", expected_generation=17)
                data = caught.exception.public_data()
                self.assertEqual(status, caught.exception.http_status)
                self.assertEqual(status, data["error"]["http_status"])
                for secret in [PASSWORD, TOKEN, "backend-private-content"]:
                    self.assertNotIn(secret, str(caught.exception))
                    self.assertNotIn(secret, json.dumps(data))
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(8, self.stub.resource_count)

    async def test_rejected_or_malformed_actor_responses_are_not_observation_success(self) -> None:
        payloads = [
            PASSWORD.encode(), [], envelope([]),
            {"code": 40001, "message": PASSWORD + TOKEN},
        ]
        for payload in payloads:
            with self.subTest(payload=type(payload).__name__):
                self.stub.responder = lambda request, payload=payload: (
                    self.actor_response(request) if request["path"] == "/auth/v1/login"
                    else (200, payload)
                )
                with self.assertRaises(BridgeError) as caught:
                    await self.client.observe("actor", session_id="session-http-fixture", expected_generation=17)
                for secret in [PASSWORD, TOKEN]:
                    self.assertNotIn(secret, str(caught.exception))
                    self.assertNotIn(secret, json.dumps(caught.exception.public_data()))


if __name__ == "__main__":
    unittest.main()
