from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from threading import Event
import unittest

from mcp import Client

from solo_acceptance_mcp.server import build_server

from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.script_fixture import CANCEL_PATH, HELP_PATH, RUN_PATH, SCRIPT_ARGS, SCRIPT_HELP, ScriptStub, script_operation


class ScriptProtocolTests(unittest.IsolatedAsyncioTestCase):
    """Official SDK stdio against an isolated HTTP fixture, with no script engine or game DB."""

    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = ScriptStub().start()

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_stdio_schema_has_native_fields_only_and_preserves_tool_annotations(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(chat_protocol.REGISTERED_TOOLS, set(tools))
        self.assertFalse(tools["script_run"].annotations.read_only_hint)
        self.assertTrue(tools["script_run"].annotations.idempotent_hint)
        self.assertTrue(tools["script_help"].annotations.read_only_hint)
        schema = tools["script_run"].input_schema
        self.assertIs(False, schema["additionalProperties"])
        self.assertEqual(set(SCRIPT_ARGS), set(schema["properties"]))
        self.assertEqual({"session_id", "expected_generation", "request_id", "kind", "operation"}, set(schema["required"]))
        self.assertEqual("", schema["properties"]["source"]["default"])
        self.assertNotIn("environment", schema["properties"])
        self.assertEqual((1, 5000), (schema["properties"]["timeout_ms"]["minimum"],
                                    schema["properties"]["timeout_ms"]["maximum"]))
        help_schema = tools["script_help"].input_schema
        self.assertIs(False, help_schema["additionalProperties"])
        self.assertEqual({"session_id", "expected_generation"}, set(help_schema["properties"]))
        self.assertEqual([], self.stub.requests)

    async def test_real_stdio_native_output_and_help_are_backend_facts(self) -> None:
        async with self.stdio_client() as client:
            help_result = await client.call_tool("script_help", {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7})
            run_result = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
        self.assertFalse(help_result.is_error)
        self.assertEqual(SCRIPT_HELP, help_result.structured_content)
        self.assertNotIn("functions", help_result.structured_content)
        self.assertNotIn("unavailable", help_result.structured_content)
        self.assertFalse(run_result.is_error)
        self.assertEqual(script_operation(SCRIPT_ARGS["request_id"]), run_result.structured_content)
        self.assertFalse(run_result.structured_content["result"]["context_closed"])
        self.assertTrue(run_result.structured_content["result"]["invocation_settled"])
        self.assertEqual(run_result.structured_content, json.loads(run_result.content[0].text))
        resources = [request for request in self.stub.requests if request["path"] in {HELP_PATH, RUN_PATH}]
        self.assertEqual(2, len(resources))
        for request in resources:
            self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
        public = help_result.model_dump_json() + run_result.model_dump_json()
        self.assertNotIn(PASSWORD, public)
        self.assertNotIn(TOKEN, public)

    async def test_parse_runtime_and_cancellation_outcomes_keep_backend_error_details(self) -> None:
        async with self.stdio_client() as client:
            for status, error in [("FAILED", {"kind": "PARSE_ERROR", "line": 1, "column": 5}),
                                  ("FAILED", {"kind": "RUNTIME_ERROR", "line": 2, "column": 9}),
                                  ("CANCELLED", {"kind": "TIMEOUT"}),
                                  ("INDETERMINATE", {"kind": "CLEANUP_UNVERIFIED"})]:
                with self.subTest(error=error):
                    self.stub.script_status, self.stub.script_error = status, error
                    response = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
                    self.assertTrue(response.is_error)
                    self.assertEqual(script_operation(SCRIPT_ARGS["request_id"], status=status, error=error), response.structured_content)

    async def test_stdio_preserves_emoji_and_safely_replaced_result_error_text(self) -> None:
        operation = script_operation(SCRIPT_ARGS["request_id"])
        operation["result"]["result"] = {"emoji": "😀🚀", "nested": ["中文🙂"]}
        operation["result"]["stdout"] = "计算完成 😀\n"

        def respond(request: dict):
            if request["path"] == RUN_PATH:
                return 200, envelope(operation)
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client() as client:
            response = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
            self.assertFalse(response.is_error)
            self.assertEqual(operation, response.structured_content)
            self.assertEqual(operation, json.loads(response.content[0].text))
            operation["status"] = "FAILED"
            operation["result"]["result"] = None
            operation["error"] = {"kind": "RESULT_ERROR", "message": "结果含不可序列化字符 �；原输出 😀 已保留"}
            response = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
            self.assertTrue(response.is_error)
            self.assertEqual(operation, response.structured_content)
            self.assertEqual(operation, json.loads(response.content[0].text))
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())

    async def test_malformed_backend_unicode_or_nonfinite_json_is_safe_and_keeps_cancel_available(self) -> None:
        private = PASSWORD + TOKEN + "private-invalid-backend"
        malformed = []
        for location in ["value", "key", "stdout", "error", "nonfinite"]:
            operation = script_operation(SCRIPT_ARGS["request_id"])
            if location == "value":
                operation["result"]["result"] = {"text": private + "\ud800"}
            elif location == "key":
                operation["result"]["result"] = {private + "\udfff": "value"}
            elif location == "stdout":
                operation["result"]["stdout"] = private + "\ud800"
            elif location == "error":
                operation["status"] = "FAILED"
                operation["error"] = {"kind": "RESULT_ERROR", "message": private + "\udfff"}
            else:
                operation["result"]["result"] = {"number": float("nan")}
            malformed.append(operation)
        current = malformed[0]

        def respond(request: dict):
            if request["path"] == RUN_PATH:
                return 200, envelope(current)
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client() as client:
            for index, operation in enumerate(malformed):
                with self.subTest(location=index):
                    current = operation
                    response = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_RESPONSE", response.structured_content["error"]["code"])
                    self.assertEqual(SCRIPT_ARGS["request_id"], response.structured_content["request_id"])
                    self.assertTrue(response.structured_content["error"]["outcome_unknown"])
                    self.assertFalse(response.structured_content["error"]["retryable"])
                    self.assertNotIn(private, response.model_dump_json())
            help_result = await client.call_tool("script_help", {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7})
            self.assertFalse(help_result.is_error)
        runs = [request for request in self.stub.requests if request["path"] == RUN_PATH]
        cancels = [request for request in self.stub.requests if request["path"] == CANCEL_PATH]
        self.assertEqual(len(malformed), len(runs))
        self.assertEqual(len(malformed), len(cancels))
        self.assertTrue(all(request["body"]["data"]["target_request_id"] == SCRIPT_ARGS["request_id"] for request in cancels))

    async def test_pure_calls_old_environment_and_invalid_raw_native_arguments_fail_before_auth(self) -> None:
        private = PASSWORD + TOKEN + "private-script-input"
        cases = [
            {**SCRIPT_ARGS, "unexpected": private},
            {**SCRIPT_ARGS, "environment": None},
            {**SCRIPT_ARGS, "environment": {"kind": "npc", "operation": "start", "private": private}},
            {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7, "request_id": "old-pure", "source": "1+2"},
            {**SCRIPT_ARGS, "source": {"private": private}},
            {**SCRIPT_ARGS, "source": None},
            {**SCRIPT_ARGS, "source": "x" * 65537},
            {**SCRIPT_ARGS, "timeout_ms": True},
            {**SCRIPT_ARGS, "timeout_ms": 0},
            {**SCRIPT_ARGS, "timeout_ms": 5001},
            {**SCRIPT_ARGS, "timeout_ms": "1000"},
            {**SCRIPT_ARGS, "parameters": [private]},
            {**SCRIPT_ARGS, "options": None},
            {**SCRIPT_ARGS, "args": None},
            {**SCRIPT_ARGS, "expected_generation": False},
        ]
        cases.extend({key: value for key, value in SCRIPT_ARGS.items() if key != missing}
                     for missing in ["session_id", "expected_generation", "request_id", "kind", "operation"])
        async with self.stdio_client() as client:
            for index, arguments in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("script_run", deepcopy(arguments))
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_help_rejects_retired_names_and_extra_fields_before_auth(self) -> None:
        private = PASSWORD + TOKEN
        base = {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7}
        cases = [{**base, "unexpected": private}, {**base, "names": []}, {**base, "names": private},
                 {**base, "names": ["move"]}, {**base, "expected_generation": True},
                 {"session_id": SCRIPT_ARGS["session_id"]}]
        async with self.stdio_client() as client:
            for index, arguments in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("script_help", arguments)
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_stdio_keeps_depth_guard_and_forwards_server_budget_rejection(self) -> None:
        depth_boundary = None
        for _ in range(16):
            depth_boundary = {"nested": depth_boundary}
        too_deep = {"nested": depth_boundary}

        def respond(request: dict):
            if request["path"] == RUN_PATH and request["body"]["requestId"] == "script:server-budget":
                return 400, {"message": "invalid_script_parameters"}
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client() as client:
            response = await client.call_tool("script_run", {**SCRIPT_ARGS, "parameters": depth_boundary})
            self.assertFalse(response.is_error)
            response = await client.call_tool("script_run", {**SCRIPT_ARGS, "parameters": too_deep})
            self.assertTrue(response.is_error)
            self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
            response = await client.call_tool("script_run", {
                **SCRIPT_ARGS, "request_id": "script:server-budget", "parameters": {"text": "a" * 20000}})
            self.assertTrue(response.is_error)
            self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
        self.assertEqual(2, len([request for request in self.stub.requests if request["path"] == RUN_PATH]))

    async def test_official_sdk_cancel_reaches_independent_http_cancel(self) -> None:
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
        async with self.stdio_client() as client:
            task = asyncio.create_task(client.call_tool("script_run", deepcopy(SCRIPT_ARGS)))
            try:
                self.assertTrue(await asyncio.to_thread(run_started.wait, 2))
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                self.assertTrue(await asyncio.to_thread(cancel_started.wait, 2), "SDK取消必须贯通后端，不能仅取消本地等待。")
                cancel_requests = [request for request in self.stub.requests if request["path"] == CANCEL_PATH]
                self.assertEqual(1, len(cancel_requests))
                self.assertEqual(SCRIPT_ARGS["request_id"], cancel_requests[0]["body"]["data"]["target_request_id"])
            finally:
                cleanup_complete.set()
            response = await client.call_tool("script_help", {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7})
            self.assertFalse(response.is_error)

    async def test_lost_run_and_cleanup_responses_keep_original_request_unknown_without_replay(self) -> None:
        self.stub.disconnect_requests = True
        async with Client(build_server(self.stub.config())) as client:
            response = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
            help_response = await client.call_tool("script_help", {
                "session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7,
            })
        self.assertTrue(response.is_error)
        data = response.structured_content
        self.assertEqual("HTTP_UNAVAILABLE", data["error"]["code"])
        self.assertEqual(SCRIPT_ARGS["request_id"], data["request_id"])
        self.assertTrue(data["error"]["outcome_unknown"])
        self.assertFalse(data["error"]["retryable"])
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertFalse(help_response.is_error)
        runs = [request for request in self.stub.requests if request["path"] == RUN_PATH]
        cancels = [request for request in self.stub.requests if request["path"] == CANCEL_PATH]
        self.assertEqual(1, len(runs))
        self.assertEqual(1, len(cancels))
        self.assertEqual({**runs[0]["body"]["data"], "target_request_id": SCRIPT_ARGS["request_id"]},
                         cancels[0]["body"]["data"])
        self.assertNotEqual(SCRIPT_ARGS["request_id"], cancels[0]["body"]["requestId"])

    async def test_unknown_script_call_waits_for_http_cleanup_before_returning(self) -> None:
        cancel_started, cleanup_complete = Event(), Event()

        def respond(request):
            if request["path"] == RUN_PATH:
                return 200, b"invalid script response"
            if request["path"] == CANCEL_PATH:
                cancel_started.set()
                cleanup_complete.wait(3)
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with Client(build_server(self.stub.config())) as client:
            task = asyncio.create_task(client.call_tool("script_run", deepcopy(SCRIPT_ARGS)))
            try:
                self.assertTrue(await asyncio.to_thread(cancel_started.wait, 2))
                with self.assertRaises(asyncio.TimeoutError):
                    await asyncio.wait_for(asyncio.shield(task), 0.05)
                self.assertFalse(task.done(), "清理响应未到时，原工具调用不能结束。")
            finally:
                cleanup_complete.set()
            response = await asyncio.wait_for(task, 3)
        self.assertTrue(response.is_error)
        self.assertEqual("INVALID_RESPONSE", response.structured_content["error"]["code"])
        self.assertTrue(response.structured_content["error"]["outcome_unknown"])
        self.assertEqual(SCRIPT_ARGS["request_id"], response.structured_content["request_id"])
        self.assertEqual(1, sum(request["path"] == RUN_PATH for request in self.stub.requests))
        self.assertEqual(1, sum(request["path"] == CANCEL_PATH for request in self.stub.requests))
