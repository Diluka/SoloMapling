from __future__ import annotations

import asyncio
import json
import time
import unittest

import httpx

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


class ChatBridgeTests(unittest.IsolatedAsyncioTestCase):
    """Exercise chat writes on local HTTP without a game server or database."""

    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.stub.responder = self.chat_response
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    def chat_response(self, request):
        if request["path"] == "/solo/acceptance/v1/actor/act":
            return 200, envelope({
                "operation_id": "operation-chat-fixture",
                "kind": "ACTION",
                "action": "chat",
                "status": "QUEUED",
                "request_id": request["body"]["requestId"],
            })
        return self.stub.default_response(request)

    async def act(self, **overrides):
        arguments = {
            "session_id": "session-chat-fixture",
            "expected_generation": 17,
            "request_id": "chat:fixture-1",
            "action": "chat",
            "parameters": {"text": "聊天验收 fixture"},
            "expected_context": {
                "scene_ref": "scene-chat-fixture",
                "runtime_generation": "runtime-chat-fixture",
            },
        }
        arguments.update(overrides)
        return await self.client.act(**arguments)

    async def test_chat_uses_authenticated_write_envelope_and_the_callers_request_id(self) -> None:
        result = await self.act(parameters={"text": "  保留原文 fixture  "})

        self.assertEqual("QUEUED", result["status"])
        self.assertEqual("ACTION", result["kind"])
        self.assertEqual("chat", result["action"])
        self.assertEqual("chat:fixture-1", result["request_id"])
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(1, self.stub.resource_count)
        request = self.stub.requests[-1]
        self.assertEqual("POST", request["method"])
        self.assertEqual("/solo/acceptance/v1/actor/act", request["path"])
        self.assertEqual("Bearer " + TOKEN + "-1", request["headers"].get("authorization"))
        self.assertEqual("application/json", request["headers"].get("content-type"))
        self.assertEqual({
            "requestId": "chat:fixture-1",
            "data": {
                "session_id": "session-chat-fixture",
                "expected_generation": 17,
                "action": "chat",
                "parameters": {"text": "  保留原文 fixture  "},
                "expected_context": {
                    "scene_ref": "scene-chat-fixture",
                    "runtime_generation": "runtime-chat-fixture",
                },
            },
        }, request["body"])

    async def test_action_statuses_are_not_promoted_to_success_or_cached_locally(self) -> None:
        for status in ["QUEUED", "RUNNING", "CANCEL_REQUESTED", "INDETERMINATE", "FAILED", "CANCELLED"]:
            with self.subTest(status=status):
                def reply(request):
                    code, payload = self.chat_response(request)
                    if request["path"] != "/auth/v1/login":
                        payload["data"]["status"] = status
                    return code, payload

                self.stub.responder = reply
                result = await self.act()
                self.assertEqual(status, result["status"])
                self.assertEqual("chat:fixture-1", result["request_id"])
        self.assertEqual(6, self.stub.resource_count)
        self.assertEqual(1, self.stub.login_count)

    async def test_missing_backend_request_id_is_restored_and_mismatched_id_is_rejected(self) -> None:
        def without_id(request):
            code, payload = self.chat_response(request)
            if request["path"] != "/auth/v1/login":
                payload["data"].pop("request_id")
            return code, payload

        self.stub.responder = without_id
        self.assertEqual("chat:fixture-1", (await self.act())["request_id"])

        def wrong_id(request):
            code, payload = self.chat_response(request)
            if request["path"] != "/auth/v1/login":
                payload["data"]["request_id"] = "backend-private-id"
            return code, payload

        self.stub.responder = wrong_id
        with self.assertRaises(BridgeError) as caught:
            await self.act()
        data = caught.exception.public_data()
        self.assertEqual("INVALID_RESPONSE", caught.exception.code)
        self.assertEqual("chat:fixture-1", data["request_id"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertFalse(data["error"]["retryable"])
        self.assertNotIn("backend-private-id", json.dumps(data))
        self.assertEqual(2, self.stub.resource_count)

    async def test_valid_text_uses_utf16_limits_without_trimming_or_guessing_native_encoding(self) -> None:
        texts = [
            "x", "汉" * 127, "😀" * 63 + "a", "!普通文本",
            " @保留前导空格", " /保留前导空格", "  保留空白\t ",
        ]
        for index, text in enumerate(texts):
            with self.subTest(case=index):
                await self.act(request_id="text:case-" + str(index), parameters={"text": text})
                self.assertEqual({"text": text}, self.stub.requests[-1]["body"]["data"]["parameters"])
        self.assertEqual(len(texts), self.stub.resource_count)

    async def test_invalid_chat_text_fails_before_authentication_or_http(self) -> None:
        invalid = [
            "", " ", "\t\r\n", "\u3000", "@command", "/command",
            "@private-chat-body-local",
            "a" * 128, "汉" * 128, "😀" * 64,
            None, False, 7, b"text", [], {},
        ]
        for text in invalid:
            with self.subTest(text_type=type(text).__name__), self.assertRaises(BridgeError) as caught:
                await self.act(parameters={"text": text})
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn("private-chat-body-local", str(caught.exception))
            self.assertNotIn("private-chat-body-local", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_only_the_chat_action_is_accepted_before_http(self) -> None:
        for action in [None, False, 7, [], {}, "", "CHAT", "chat ", "npc", "move", "script"]:
            with self.subTest(action=action), self.assertRaises(BridgeError) as caught:
                await self.act(action=action)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_parameters_and_context_require_exact_object_fields_before_http(self) -> None:
        context = {"scene_ref": "scene-chat-fixture", "runtime_generation": "runtime-chat-fixture"}
        invalid = [
            {"parameters": value} for value in [
                None, False, "text", [], {}, {"TEXT": "text"},
                {"text": "text", "show": 0}, {"text": "text", "character_id": 991},
            ]
        ]
        invalid.extend({"expected_context": value} for value in [
            None, False, "scene", [], {},
            {"scene_ref": "scene-chat-fixture"},
            {"runtime_generation": "runtime-chat-fixture"},
            {**context, "map_id": 10000},
        ])
        for params in invalid:
            with self.subTest(params=params), self.assertRaises(BridgeError) as caught:
                await self.act(**params)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_identifier_and_generation_boundaries_are_transmitted_unchanged(self) -> None:
        cases = [
            ("s", "r", "c", "g", 1),
            ("s" * 128, "r:" + "x" * 126, "c" * 128, "g" * 128, 2**63 - 1),
        ]
        for session_id, request_id, scene_ref, generation_ref, generation in cases:
            with self.subTest(generation=generation):
                result = await self.act(
                    session_id=session_id, request_id=request_id, expected_generation=generation,
                    expected_context={"scene_ref": scene_ref, "runtime_generation": generation_ref},
                )
                request = self.stub.requests[-1]["body"]
                self.assertEqual(request_id, request["requestId"])
                self.assertEqual(request_id, result["request_id"])
                self.assertEqual(session_id, request["data"]["session_id"])
                self.assertEqual(generation, request["data"]["expected_generation"])
                self.assertEqual({"scene_ref": scene_ref, "runtime_generation": generation_ref}, request["data"]["expected_context"])

    async def test_invalid_ids_fail_before_authentication_or_http(self) -> None:
        invalid = [None, False, 7, [], {}, "", " ", "bad\nidentifier", "bad/id", ".", "..", "x" * 129]
        for field in ["session_id", "request_id", "scene_ref", "runtime_generation"]:
            values = invalid if field == "request_id" else [*invalid, "bad:identifier"]
            for value in values:
                params = {field: value}
                if field in {"scene_ref", "runtime_generation"}:
                    params = {"expected_context": {
                        "scene_ref": "scene-chat-fixture", "runtime_generation": "runtime-chat-fixture",
                        field: value,
                    }}
                with self.subTest(field=field, value_type=type(value).__name__), self.assertRaises(BridgeError) as caught:
                    await self.act(**params)
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_generation_requires_a_strict_positive_long_before_http(self) -> None:
        for generation in [None, False, True, 0, -1, 2**63, 1.0, "1"]:
            with self.subTest(generation=generation), self.assertRaises(BridgeError) as caught:
                await self.act(expected_generation=generation)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_the_caller_must_supply_a_request_id(self) -> None:
        with self.assertRaises(TypeError):
            await self.client.act(
                session_id="session-chat-fixture", expected_generation=17, action="chat",
                parameters={"text": "普通文本"}, expected_context={
                    "scene_ref": "scene-chat-fixture", "runtime_generation": "runtime-chat-fixture",
                },
            )
        self.assertEqual([], self.stub.requests)

    async def test_401_refresh_reuses_the_entire_original_write_envelope(self) -> None:
        def reply(request):
            if request["path"] != "/auth/v1/login" and self.stub.resource_count == 1:
                return 401, {"message": PASSWORD + TOKEN}
            return self.chat_response(request)

        self.stub.responder = reply
        result = await self.act(request_id="chat:retry-once")
        requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, len(requests))
        self.assertEqual("chat:retry-once", result["request_id"])
        self.assertEqual("chat:retry-once", requests[0]["body"]["requestId"])
        self.assertEqual(requests[0]["body"], requests[1]["body"])
        self.assertEqual(requests[0]["path"], requests[1]["path"])
        self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_repeated_401_does_not_loop_or_lose_the_original_request_id(self) -> None:
        self.stub.responder = lambda request: (
            self.chat_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN})
        )
        with self.assertRaises(BridgeError) as caught:
            await self.act(request_id="chat:auth-rejected")
        data = caught.exception.public_data()
        self.assertEqual(2, self.stub.login_count)
        self.assertEqual(2, self.stub.resource_count)
        self.assertEqual("chat:auth-rejected", data["request_id"])
        self.assertEqual(401, data["error"]["http_status"])
        self.assertFalse(data["error"]["outcome_unknown"])
        self.assertNotIn(PASSWORD, str(caught.exception))
        self.assertNotIn(TOKEN, json.dumps(data))

    async def test_write_timeout_is_unknown_and_is_not_automatically_replayed(self) -> None:
        def reply(request):
            if request["path"] != "/auth/v1/login":
                time.sleep(0.25)
            return self.chat_response(request)

        self.stub.responder = reply
        await self.client.close()
        self.client = BridgeClient(self.stub.config(timeout=0.1))
        with self.assertRaises(BridgeError) as caught:
            await self.act(request_id="chat:timed-out")
        data = caught.exception.public_data()
        self.assertEqual("HTTP_TIMEOUT", data["error"]["code"])
        self.assertEqual("chat:timed-out", data["request_id"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertFalse(data["error"]["retryable"])
        self.assertEqual(1, self.stub.resource_count)

    async def test_connection_failure_is_unknown_without_replay_or_transport_detail_leaks(self) -> None:
        await self.client.capabilities()
        await self.client._http.aclose()
        attempts = []

        def broken_transport(request):
            attempts.append(request)
            raise httpx.ReadError(PASSWORD + TOKEN + "private-chat-body", request=request)

        self.client._http = httpx.AsyncClient(
            base_url=self.stub.base_url, transport=httpx.MockTransport(broken_transport),
        )
        with self.assertRaises(BridgeError) as caught:
            await self.act(request_id="chat:connection-lost")
        data = caught.exception.public_data()
        self.assertEqual(1, len(attempts))
        self.assertEqual("HTTP_UNAVAILABLE", data["error"]["code"])
        self.assertEqual("chat:connection-lost", data["request_id"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertFalse(data["error"]["retryable"])
        for secret in [PASSWORD, TOKEN, "private-chat-body"]:
            self.assertNotIn(secret, str(caught.exception))
            self.assertNotIn(secret, json.dumps(data))

    async def test_http_failures_preserve_status_and_original_id_without_echoing_backend_bodies(self) -> None:
        for status in [400, 403, 404, 409, 410, 429, 503]:
            with self.subTest(status=status):
                self.stub.responder = lambda request, status=status: (
                    self.chat_response(request) if request["path"] == "/auth/v1/login"
                    else (status, {"message": PASSWORD + TOKEN + "private-chat-body", "requestId": "backend-private-id"})
                )
                with self.assertRaises(BridgeError) as caught:
                    await self.act(request_id="chat:http-rejected")
                data = caught.exception.public_data()
                self.assertEqual(status, caught.exception.http_status)
                self.assertEqual(status, data["error"]["http_status"])
                self.assertEqual("chat:http-rejected", data["request_id"])
                self.assertFalse(data["error"]["retryable"])
                if status < 500:
                    self.assertFalse(data["error"]["outcome_unknown"])
                for secret in [PASSWORD, TOKEN, "private-chat-body", "backend-private-id"]:
                    self.assertNotIn(secret, str(caught.exception))
                    self.assertNotIn(secret, json.dumps(data))
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(7, self.stub.resource_count)


if __name__ == "__main__":
    unittest.main()
