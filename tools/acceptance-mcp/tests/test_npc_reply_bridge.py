from __future__ import annotations

import asyncio
import json
import unittest

import httpx

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import BridgeStub, PASSWORD, TOKEN, envelope


REPLY_CASES = [
    {"dialogue_version": 23, "kind": "select", "selection": 0},
    {"dialogue_version": 23, "kind": "decline"},
]


class NpcReplyBridgeTests(unittest.IsolatedAsyncioTestCase):
    """Exercise finite NPC replies on local HTTP, never a game or database."""

    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()
        self.stub.responder = self.reply_response
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    def reply_response(self, request):
        if request["path"] == "/solo/acceptance/v1/actor/act":
            return 200, envelope({
                "operation_id": "operation-reply-fixture",
                "kind": "ACTION",
                "action": "npc_reply",
                "status": "QUEUED",
                "request_id": request["body"]["requestId"],
            })
        return self.stub.default_response(request)

    async def act(self, parameters, **overrides):
        arguments = {
            "session_id": "session-reply-fixture",
            "expected_generation": 17,
            "request_id": "reply:fixture-1",
            "action": "npc_reply",
            "parameters": parameters,
            "expected_context": {
                "scene_ref": "scene-reply-fixture",
                "runtime_generation": "runtime-reply-fixture",
            },
        }
        arguments.update(overrides)
        return await self.client.act(**arguments)

    async def test_select_and_decline_send_exact_authenticated_write_envelopes(self) -> None:
        for parameters in REPLY_CASES:
            with self.subTest(kind=parameters["kind"]):
                request_id = "reply:" + parameters["kind"]
                result = await self.act(parameters, request_id=request_id)
                self.assertEqual("ACTION", result["kind"])
                self.assertEqual("npc_reply", result["action"])
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
                        "session_id": "session-reply-fixture",
                        "expected_generation": 17,
                        "action": "npc_reply",
                        "parameters": parameters,
                        "expected_context": {
                            "scene_ref": "scene-reply-fixture",
                            "runtime_generation": "runtime-reply-fixture",
                        },
                    },
                }, request["body"])
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(2, self.stub.resource_count)

    async def test_reply_states_are_not_promoted_to_success_or_cached_locally(self) -> None:
        for parameters in REPLY_CASES:
            for status in ["QUEUED", "RUNNING", "CANCEL_REQUESTED", "CANCELLED", "FAILED", "INDETERMINATE"]:
                with self.subTest(kind=parameters["kind"], status=status):
                    def reply(request):
                        code, payload = self.reply_response(request)
                        if request["path"] != "/auth/v1/login":
                            payload["data"]["status"] = status
                        return code, payload

                    self.stub.responder = reply
                    result = await self.act(parameters)
                    self.assertEqual(status, result["status"])
                    self.assertEqual("reply:fixture-1", result["request_id"])
        self.assertEqual(12, self.stub.resource_count)

    async def test_versions_and_selection_integer_boundaries_reach_http_without_coercion(self) -> None:
        for version, selection in [(1, 0), (2**63 - 1, 2**31 - 1)]:
            for parameters in [
                {"dialogue_version": version, "kind": "select", "selection": selection},
                {"dialogue_version": version, "kind": "decline"},
            ]:
                with self.subTest(kind=parameters["kind"], version=version, selection=selection):
                    await self.act(parameters)
                    transmitted = self.stub.requests[-1]["body"]["data"]["parameters"]
                    self.assertEqual(parameters, transmitted)
                    self.assertIs(int, type(transmitted["dialogue_version"]))
                    if parameters["kind"] == "select":
                        self.assertIs(int, type(transmitted["selection"]))

    async def test_reply_parameters_are_exact_mutually_exclusive_objects_before_http(self) -> None:
        invalid = [
            None, False, 0, "private-reply-input", [], {},
            {"dialogue_version": 23},
            {"kind": "decline"},
            {"kind": "select", "selection": 0},
            {"dialogue_version": 23, "kind": "select"},
            {"dialogue_version": 23, "kind": "decline", "selection": 0},
            {"dialogue_version": 23, "kind": "decline", "selection": None},
            {"dialogue_version": 23, "kind": "select", "selection": 0, "text": "private-reply-input"},
        ]
        for parameters in invalid:
            with self.subTest(parameters_type=type(parameters).__name__), self.assertRaises(BridgeError) as caught:
                await self.act(parameters)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn("private-reply-input", str(caught.exception))
            self.assertNotIn("private-reply-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_accept_and_other_unsupported_reply_kinds_are_rejected_before_http(self) -> None:
        for kind in [None, False, 7, [], {}, "", "accept", "next", "back", "yes", "no", "SELECT", "select ", "private-reply-input"]:
            for include_selection in [False, True]:
                parameters = {"dialogue_version": 23, "kind": kind}
                if include_selection:
                    parameters["selection"] = 0
                with self.subTest(kind_type=type(kind).__name__, include_selection=include_selection), self.assertRaises(BridgeError) as caught:
                    await self.act(parameters)
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
                self.assertNotIn("private-reply-input", str(caught.exception))
                self.assertNotIn("private-reply-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_templates_scripts_raw_payloads_and_native_reply_bytes_are_not_parameters(self) -> None:
        prohibited = {
            "npc_id": 9900001,
            "npc_template_id": 9900001,
            "oid": 991,
            "script": "private-reply-input",
            "script_path": "private-reply-input.js",
            "path": "private-reply-input.js",
            "lastMsg": 4,
            "last_msg": 4,
            "action": -1,
            "action_byte": 0,
            "show": 0,
            "payload": "private-reply-input",
            "raw_packet": [4, 0],
        }
        for parameters in REPLY_CASES:
            for field, value in prohibited.items():
                with self.subTest(kind=parameters["kind"], field=field), self.assertRaises(BridgeError) as caught:
                    await self.act({**parameters, field: value})
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
                self.assertNotIn("private-reply-input", str(caught.exception))
                self.assertNotIn("private-reply-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_reply_versions_are_strict_positive_longs_before_http(self) -> None:
        for parameters in REPLY_CASES:
            for version in [None, False, True, 0, -1, 2**63, 1.0, "1", [], {}, "private-reply-input"]:
                with self.subTest(kind=parameters["kind"], version_type=type(version).__name__), self.assertRaises(BridgeError) as caught:
                    await self.act({**parameters, "dialogue_version": version})
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
                self.assertNotIn("private-reply-input", str(caught.exception))
                self.assertNotIn("private-reply-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_selections_are_strict_nonnegative_ints_before_http(self) -> None:
        for selection in [None, False, True, -1, 2**31, 0.0, "0", [], {}, "private-reply-input"]:
            with self.subTest(selection_type=type(selection).__name__), self.assertRaises(BridgeError) as caught:
                await self.act({"dialogue_version": 23, "kind": "select", "selection": selection})
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn("private-reply-input", str(caught.exception))
            self.assertNotIn("private-reply-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_reply_contexts_and_session_fields_keep_strict_existing_validation(self) -> None:
        invalid = [
            {"session_id": "bad/session"}, {"session_id": "x" * 129},
            {"request_id": ""}, {"request_id": "private-reply-input\n"},
            {"expected_generation": False}, {"expected_generation": 0},
            {"expected_generation": 2**63}, {"expected_generation": "1"},
            {"expected_context": None}, {"expected_context": []}, {"expected_context": {}},
            {"expected_context": {"scene_ref": "scene-reply-fixture"}},
            {"expected_context": {"scene_ref": "scene-reply-fixture", "runtime_generation": "runtime-reply-fixture", "selection": 0}},
        ]
        for field in ["scene_ref", "runtime_generation"]:
            for value in [None, False, 7, [], {}, "", ".", "..", "bad:identifier", "x" * 129, "private-reply-input\n"]:
                invalid.append({"expected_context": {
                    "scene_ref": "scene-reply-fixture", "runtime_generation": "runtime-reply-fixture",
                    field: value,
                }})
        for parameters in REPLY_CASES:
            for overrides in invalid:
                with self.subTest(kind=parameters["kind"], field=next(iter(overrides))), self.assertRaises(BridgeError) as caught:
                    await self.act(parameters, **overrides)
                self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
                self.assertNotIn("private-reply-input", str(caught.exception))
                self.assertNotIn("private-reply-input", json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_reply_writes_require_the_callers_explicit_request_id(self) -> None:
        for parameters in REPLY_CASES:
            with self.subTest(kind=parameters["kind"]), self.assertRaises(TypeError):
                await self.client.act(
                    session_id="session-reply-fixture", expected_generation=17,
                    action="npc_reply", parameters=parameters, expected_context={
                        "scene_ref": "scene-reply-fixture", "runtime_generation": "runtime-reply-fixture",
                    },
                )
        self.assertEqual([], self.stub.requests)

    async def test_both_reply_kinds_refresh_401_once_with_the_identical_original_envelope(self) -> None:
        for parameters in REPLY_CASES:
            with self.subTest(kind=parameters["kind"]):
                await self.client.close()
                self.client = BridgeClient(self.stub.config())
                self.stub.requests.clear()
                self.stub.login_count = 0
                self.stub.resource_count = 0

                def reply(request):
                    if request["path"] != "/auth/v1/login" and self.stub.resource_count == 1:
                        return 401, {"message": PASSWORD + TOKEN}
                    return self.reply_response(request)

                self.stub.responder = reply
                result = await self.act(parameters, request_id="reply:retry-once")
                requests = [request for request in self.stub.requests if request["path"] != "/auth/v1/login"]
                self.assertEqual(2, self.stub.login_count)
                self.assertEqual(2, len(requests))
                self.assertEqual("reply:retry-once", result["request_id"])
                self.assertEqual("reply:retry-once", requests[0]["body"]["requestId"])
                self.assertEqual(requests[0]["body"], requests[1]["body"])
                self.assertEqual(requests[0]["path"], requests[1]["path"])
                self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_repeated_401s_stop_after_one_refresh_with_original_id_and_safe_error(self) -> None:
        self.stub.responder = lambda request: (
            self.reply_response(request) if request["path"] == "/auth/v1/login"
            else (401, {"message": PASSWORD + TOKEN + "private-reply-body"})
        )
        for parameters in REPLY_CASES:
            with self.subTest(kind=parameters["kind"]):
                await self.client.close()
                self.client = BridgeClient(self.stub.config())
                self.stub.requests.clear()
                self.stub.login_count = 0
                self.stub.resource_count = 0
                with self.assertRaises(BridgeError) as caught:
                    await self.act(parameters, request_id="reply:auth-rejected")
                data = caught.exception.public_data()
                self.assertEqual(2, self.stub.login_count)
                self.assertEqual(2, self.stub.resource_count)
                self.assertEqual("reply:auth-rejected", data["request_id"])
                self.assertEqual(401, data["error"]["http_status"])
                self.assertFalse(data["error"]["outcome_unknown"])
                for secret in [PASSWORD, TOKEN, "private-reply-body"]:
                    self.assertNotIn(secret, str(caught.exception))
                    self.assertNotIn(secret, json.dumps(data))

    async def test_transport_failures_report_unknown_without_replaying_either_reply_kind(self) -> None:
        await self.client.capabilities()
        await self.client._http.aclose()
        attempts = []
        failure_type = httpx.ReadTimeout

        def broken_transport(request):
            attempts.append(request)
            raise failure_type(PASSWORD + TOKEN + "private-reply-body", request=request)

        self.client._http = httpx.AsyncClient(
            base_url=self.stub.base_url, transport=httpx.MockTransport(broken_transport),
        )
        for parameters in REPLY_CASES:
            for failure_type, code in [(httpx.ReadTimeout, "HTTP_TIMEOUT"), (httpx.ReadError, "HTTP_UNAVAILABLE")]:
                with self.subTest(kind=parameters["kind"], code=code):
                    before = len(attempts)
                    with self.assertRaises(BridgeError) as caught:
                        await self.act(parameters, request_id="reply:unknown-result")
                    data = caught.exception.public_data()
                    self.assertEqual(1, len(attempts) - before)
                    self.assertEqual("reply:unknown-result", data["request_id"])
                    self.assertEqual(code, data["error"]["code"])
                    self.assertTrue(data["error"]["outcome_unknown"])
                    self.assertFalse(data["error"]["retryable"])
                    for secret in [PASSWORD, TOKEN, "private-reply-body"]:
                        self.assertNotIn(secret, str(caught.exception))
                        self.assertNotIn(secret, json.dumps(data))

    async def test_reply_http_errors_keep_status_and_original_id_without_backend_body_leaks(self) -> None:
        for parameters in REPLY_CASES:
            for status in [400, 403, 404, 409, 410, 429, 503]:
                with self.subTest(kind=parameters["kind"], status=status):
                    self.stub.responder = lambda request, status=status: (
                        self.reply_response(request) if request["path"] == "/auth/v1/login"
                        else (status, {"message": PASSWORD + TOKEN + "private-reply-body", "requestId": "backend-private-id"})
                    )
                    with self.assertRaises(BridgeError) as caught:
                        await self.act(parameters, request_id="reply:http-rejected")
                    data = caught.exception.public_data()
                    self.assertEqual(status, caught.exception.http_status)
                    self.assertEqual(status, data["error"]["http_status"])
                    self.assertEqual("reply:http-rejected", data["request_id"])
                    self.assertFalse(data["error"]["retryable"])
                    if status < 500:
                        self.assertFalse(data["error"]["outcome_unknown"])
                    for secret in [PASSWORD, TOKEN, "private-reply-body", "backend-private-id"]:
                        self.assertNotIn(secret, str(caught.exception))
                        self.assertNotIn(secret, json.dumps(data))
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(14, self.stub.resource_count)


if __name__ == "__main__":
    unittest.main()
