from __future__ import annotations

import asyncio
import json
import unittest

import httpx

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


NPC_CASES = [
    ("npc_center_open", {}),
    ("npc_cancel", {"dialogue_version": 23}),
]


class NpcBridgeTests(unittest.IsolatedAsyncioTestCase):
    """Exercise NPC writes on local HTTP without a game server or database."""

    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.stub.responder = self.npc_response
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    def npc_response(self, request):
        if request["path"] == "/solo/acceptance/v1/actor/act":
            return 200, envelope({
                "operation_id": "operation-npc-fixture",
                "kind": "ACTION",
                "action": request["body"]["data"]["action"],
                "status": "QUEUED",
                "request_id": request["body"]["requestId"],
            })
        return self.stub.default_response(request)

    async def act(self, action, parameters, **overrides):
        arguments = {
            "session_id": "session-npc-fixture",
            "expected_generation": 17,
            "request_id": "npc:fixture-1",
            "action": action,
            "parameters": parameters,
            "expected_context": {
                "scene_ref": "scene-npc-fixture",
                "runtime_generation": "runtime-npc-fixture",
            },
        }
        arguments.update(overrides)
        return await self.client.act(**arguments)

    async def test_both_npc_actions_use_the_exact_authenticated_write_envelope(self) -> None:
        for action, parameters in NPC_CASES:
            with self.subTest(action=action):
                request_id = "npc:" + action
                result = await self.act(action, parameters, request_id=request_id)
                self.assertEqual("ACTION", result["kind"])
                self.assertEqual(action, result["action"])
                self.assertEqual("QUEUED", result["status"])
                self.assertEqual(request_id, result["request_id"])
                request = self.stub.requests[-1]
                self.assertEqual("POST", request["method"])
                self.assertEqual("/solo/acceptance/v1/actor/act", request["path"])
                self.assertEqual("Bearer " + TOKEN + "-1", request["headers"].get("authorization"))
                self.assertEqual("application/json", request["headers"].get("content-type"))
                self.assertEqual({
                    "requestId": request_id,
                    "data": {
                        "session_id": "session-npc-fixture",
                        "expected_generation": 17,
                        "action": action,
                        "parameters": parameters,
                        "expected_context": {
                            "scene_ref": "scene-npc-fixture",
                            "runtime_generation": "runtime-npc-fixture",
                        },
                    },
                }, request["body"])
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(2, self.stub.resource_count)

    async def test_npc_operation_states_remain_native_and_are_not_cached_or_promoted(self) -> None:
        for action, parameters in NPC_CASES:
            for status in ["QUEUED", "RUNNING", "CANCEL_REQUESTED", "CANCELLED", "FAILED", "INDETERMINATE"]:
                with self.subTest(action=action, status=status):
                    def reply(request):
                        code, payload = self.npc_response(request)
                        if request["path"] != "/auth/v1/login":
                            payload["data"]["status"] = status
                        return code, payload

                    self.stub.responder = reply
                    result = await self.act(action, parameters)
                    self.assertEqual(action, result["action"])
                    self.assertEqual(status, result["status"])
                    self.assertEqual("npc:fixture-1", result["request_id"])
        self.assertEqual(12, self.stub.resource_count)

    async def test_missing_backend_request_ids_are_restored_for_both_npc_actions(self) -> None:
        def reply(request):
            code, payload = self.npc_response(request)
            if request["path"] != "/auth/v1/login":
                payload["data"].pop("request_id")
            return code, payload

        self.stub.responder = reply
        for action, parameters in NPC_CASES:
            with self.subTest(action=action):
                result = await self.act(action, parameters, request_id="npc:caller-id")
                self.assertEqual("npc:caller-id", result["request_id"])

    async def test_cancel_versions_and_context_identifier_boundaries_are_not_coerced(self) -> None:
        for generation, dialogue_version, identifier in [
            (1, 1, "x"),
            (2**63 - 1, 2**63 - 1, "x" * 124 + "_.-9"),
        ]:
            for action, parameters in [
                ("npc_center_open", {}),
                ("npc_cancel", {"dialogue_version": dialogue_version}),
            ]:
                with self.subTest(action=action, generation=generation):
                    await self.act(
                        action, parameters, session_id=identifier, expected_generation=generation,
                        request_id="r:" + "x" * 126, expected_context={
                            "scene_ref": identifier, "runtime_generation": identifier,
                        },
                    )
                    body = self.stub.requests[-1]["body"]
                    self.assertEqual("r:" + "x" * 126, body["requestId"])
                    self.assertEqual(identifier, body["data"]["session_id"])
                    self.assertEqual(generation, body["data"]["expected_generation"])
                    self.assertEqual(parameters, body["data"]["parameters"])
                    self.assertEqual({"scene_ref": identifier, "runtime_generation": identifier}, body["data"]["expected_context"])

    async def test_open_requires_an_empty_object_and_cancel_requires_only_a_version(self) -> None:
        cases = [
            ("npc_center_open", value) for value in [None, False, 0, "", [], [()], {"dialogue_version": 23}]
        ]
        cases.extend(("npc_cancel", value) for value in [
            None, False, 0, "23", [], {}, {"dialogueVersion": 23},
        ])
        for action, parameters in cases:
            with self.subTest(action=action, parameters=parameters), self.assertRaises(BridgeError) as caught:
                await self.act(action, parameters)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_npc_templates_scripts_choices_and_raw_payloads_are_rejected_before_http(self) -> None:
        prohibited = {
            "npc_id": 9900001,
            "npc_template_id": 9900001,
            "npc_oid": 991,
            "oid": 991,
            "character_id": 991,
            "script": "private-npc-input",
            "script_path": "private-npc-input.js",
            "path": "private-npc-input.js",
            "selection": 3,
            "next": True,
            "last_msg": 4,
            "lastMsg": 4,
            "action": -1,
            "show": 0,
            "payload": "private-npc-input",
            "packet": [4, 255],
            "raw_packet": [4, 255],
            "text": "private-npc-input",
        }
        for action, parameters in NPC_CASES:
            for field, value in prohibited.items():
                with self.subTest(action=action, field=field), self.assertRaises(BridgeError) as caught:
                    await self.act(action, {**parameters, field: value})
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
                self.assertNotIn("private-npc-input", str(caught.exception))
                self.assertNotIn("private-npc-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_dialogue_versions_are_strict_positive_longs_before_authentication_or_http(self) -> None:
        for version in [None, False, True, 0, -1, 2**63, 1.0, "1", [], {}, "private-npc-input"]:
            with self.subTest(version=version), self.assertRaises(BridgeError) as caught:
                await self.act("npc_cancel", {"dialogue_version": version})
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn("private-npc-input", str(caught.exception))
            self.assertNotIn("private-npc-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_npc_contexts_require_exact_object_fields_before_http(self) -> None:
        invalid = [
            None, False, "private-npc-input", [], {},
            {"scene_ref": "scene-npc-fixture"},
            {"runtime_generation": "runtime-npc-fixture"},
            {"scene_ref": "scene-npc-fixture", "runtime_generation": "runtime-npc-fixture", "selection": 3},
            {"scene_ref": "scene-npc-fixture", "runtime_generation": "runtime-npc-fixture", "script": "private-npc-input"},
        ]
        for action, parameters in NPC_CASES:
            for context in invalid:
                with self.subTest(action=action, context=context), self.assertRaises(BridgeError) as caught:
                    await self.act(action, parameters, expected_context=context)
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
                self.assertNotIn("private-npc-input", str(caught.exception))
                self.assertNotIn("private-npc-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_npc_session_request_and_context_identifiers_are_validated_before_http(self) -> None:
        invalid = [None, False, 7, [], {}, "", " ", ".", "..", "bad/id", "bad\nid", "x" * 129]
        for action, parameters in NPC_CASES:
            for field in ["session_id", "request_id", "scene_ref", "runtime_generation"]:
                values = invalid if field == "request_id" else [*invalid, "bad:identifier"]
                for value in values:
                    overrides = {field: value}
                    if field in {"scene_ref", "runtime_generation"}:
                        overrides = {"expected_context": {
                            "scene_ref": "scene-npc-fixture", "runtime_generation": "runtime-npc-fixture",
                            field: value,
                        }}
                    with self.subTest(action=action, field=field, value_type=type(value).__name__), self.assertRaises(BridgeError) as caught:
                        await self.act(action, parameters, **overrides)
                    self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_npc_session_generations_are_strict_positive_longs_before_http(self) -> None:
        for action, parameters in NPC_CASES:
            for generation in [None, False, True, 0, -1, 2**63, 1.0, "1"]:
                with self.subTest(action=action, generation=generation), self.assertRaises(BridgeError) as caught:
                    await self.act(action, parameters, expected_generation=generation)
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_npc_writes_require_the_callers_explicit_request_id(self) -> None:
        for action, parameters in NPC_CASES:
            with self.subTest(action=action), self.assertRaises(TypeError):
                await self.client.act(
                    session_id="session-npc-fixture", expected_generation=17,
                    action=action, parameters=parameters, expected_context={
                        "scene_ref": "scene-npc-fixture", "runtime_generation": "runtime-npc-fixture",
                    },
                )
        self.assertEqual([], self.stub.requests)

    async def test_each_npc_action_refreshes_401_once_with_the_same_entire_envelope(self) -> None:
        for action, parameters in NPC_CASES:
            with self.subTest(action=action):
                await self.client.close()
                self.client = BridgeClient(self.stub.config())
                self.stub.requests.clear()
                self.stub.login_count = 0
                self.stub.resource_count = 0

                def reply(request):
                    if request["path"] != "/auth/v1/login" and self.stub.resource_count == 1:
                        return 401, {"message": PASSWORD + TOKEN}
                    return self.npc_response(request)

                self.stub.responder = reply
                result = await self.act(action, parameters, request_id="npc:retry-once")
                requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
                self.assertEqual(2, self.stub.login_count)
                self.assertEqual(2, len(requests))
                self.assertEqual("npc:retry-once", result["request_id"])
                self.assertEqual("npc:retry-once", requests[0]["body"]["requestId"])
                self.assertEqual(requests[0]["body"], requests[1]["body"])
                self.assertEqual(requests[0]["path"], requests[1]["path"])
                self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_repeated_npc_401s_stop_after_one_refresh_and_keep_the_original_id(self) -> None:
        self.stub.responder = lambda request: (
            self.npc_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN + "private-npc-body"})
        )
        for action, parameters in NPC_CASES:
            with self.subTest(action=action):
                before_logins = self.stub.login_count
                before_requests = self.stub.resource_count
                with self.assertRaises(BridgeError) as caught:
                    await self.act(action, parameters, request_id="npc:auth-rejected")
                data = caught.exception.public_data()
                # The second action can reuse the last refreshed token for its first attempt.
                self.assertEqual(2 if before_logins == 0 else 1, self.stub.login_count - before_logins)
                self.assertEqual(2, self.stub.resource_count - before_requests)
                self.assertEqual("npc:auth-rejected", data["request_id"])
                self.assertEqual(401, data["error"]["http_status"])
                self.assertFalse(data["error"]["outcome_unknown"])
                for secret in [PASSWORD, TOKEN, "private-npc-body"]:
                    self.assertNotIn(secret, str(caught.exception))
                    self.assertNotIn(secret, json.dumps(data))

    async def test_transport_failures_do_not_replay_npc_writes_and_report_unknown_outcomes(self) -> None:
        await self.client.capabilities()
        await self.client._http.aclose()
        attempts = []
        failure_type = httpx.ReadTimeout

        def broken_transport(request):
            attempts.append(request)
            raise failure_type(PASSWORD + TOKEN + "private-npc-body", request=request)

        self.client._http = httpx.AsyncClient(
            base_url=self.stub.base_url, transport=httpx.MockTransport(broken_transport),
        )
        for action, parameters in NPC_CASES:
            for failure_type, code in [(httpx.ReadTimeout, "HTTP_TIMEOUT"), (httpx.ReadError, "HTTP_UNAVAILABLE")]:
                with self.subTest(action=action, code=code):
                    before = len(attempts)
                    with self.assertRaises(BridgeError) as caught:
                        await self.act(action, parameters, request_id="npc:unknown-result")
                    data = caught.exception.public_data()
                    self.assertEqual(1, len(attempts) - before)
                    self.assertEqual("npc:unknown-result", data["request_id"])
                    self.assertEqual(code, data["error"]["code"])
                    self.assertTrue(data["error"]["outcome_unknown"])
                    self.assertFalse(data["error"]["retryable"])
                    for secret in [PASSWORD, TOKEN, "private-npc-body"]:
                        self.assertNotIn(secret, str(caught.exception))
                        self.assertNotIn(secret, json.dumps(data))

    async def test_npc_http_failures_keep_status_and_original_id_without_backend_body_leaks(self) -> None:
        for action, parameters in NPC_CASES:
            for status in [400, 403, 404, 409, 410, 429, 503]:
                with self.subTest(action=action, status=status):
                    self.stub.responder = lambda request, status=status: (
                        self.npc_response(request) if request["path"] == "/auth/v1/login"
                        else (status, {"message": PASSWORD + TOKEN + "private-npc-body", "requestId": "backend-private-id"})
                    )
                    with self.assertRaises(BridgeError) as caught:
                        await self.act(action, parameters, request_id="npc:http-rejected")
                    data = caught.exception.public_data()
                    self.assertEqual(status, caught.exception.http_status)
                    self.assertEqual(status, data["error"]["http_status"])
                    self.assertEqual("npc:http-rejected", data["request_id"])
                    self.assertFalse(data["error"]["retryable"])
                    if status < 500:
                        self.assertFalse(data["error"]["outcome_unknown"])
                    for secret in [PASSWORD, TOKEN, "private-npc-body", "backend-private-id"]:
                        self.assertNotIn(secret, str(caught.exception))
                        self.assertNotIn(secret, json.dumps(data))
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(14, self.stub.resource_count)


if __name__ == "__main__":
    unittest.main()
