from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from threading import Event
import unittest

from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_script_bridge import CANCEL_PATH, HELP_PATH, RUN_PATH, SCRIPT_ARGS, ScriptStub, script_operation


class ScriptProtocolTests(unittest.IsolatedAsyncioTestCase):
    """Official SDK stdio calls against an isolated HTTP protocol fixture."""

    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = ScriptStub().start()

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_stdio_registers_implemented_tools_with_strict_script_schema_and_annotations(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(chat_protocol.REGISTERED_TOOLS, set(tools))
        self.assertFalse(tools["script_run"].annotations.read_only_hint)
        self.assertTrue(tools["script_run"].annotations.idempotent_hint)
        self.assertTrue(tools["script_help"].annotations.read_only_hint)
        run_schema = tools["script_run"].input_schema
        self.assertIs(False, run_schema["additionalProperties"])
        self.assertEqual(set(SCRIPT_ARGS), set(run_schema["properties"]))
        self.assertEqual({"session_id", "expected_generation", "request_id", "source"}, set(run_schema["required"]))
        self.assertEqual((1, 5000), (run_schema["properties"]["timeout_ms"]["minimum"],
                                    run_schema["properties"]["timeout_ms"]["maximum"]))
        help_schema = tools["script_help"].input_schema
        self.assertIs(False, help_schema["additionalProperties"])
        self.assertEqual({"session_id", "expected_generation", "names"}, set(help_schema["properties"]))
        self.assertEqual([], self.stub.requests)

    async def test_real_stdio_calls_return_empty_help_and_serializable_output_without_inventing_functions(self) -> None:
        async with self.stdio_client() as client:
            help_result = await client.call_tool("script_help", {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7})
            run_result = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
        self.assertFalse(help_result.is_error)
        self.assertEqual([], help_result.structured_content["functions"])
        self.assertEqual([], help_result.structured_content["unavailable"])
        self.assertFalse(run_result.is_error)
        self.assertEqual(script_operation(SCRIPT_ARGS["request_id"]), run_result.structured_content)
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
        operation["result"]["value"] = {"emoji": "😀🚀", "nested": ["中文🙂"]}
        operation["result"]["output"] = [{"stream": "stdout", "text": "计算完成 😀\n"}]

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
            operation["result"]["value"] = None
            operation["error"] = {"kind": "RESULT_ERROR", "message": "结果含不可序列化字符 �；原输出 😀 已保留"}
            response = await client.call_tool("script_run", deepcopy(SCRIPT_ARGS))
            self.assertTrue(response.is_error)
            self.assertEqual(operation, response.structured_content)
            self.assertEqual(operation, json.loads(response.content[0].text))
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())

    async def test_stdio_rejects_backend_surrogates_safely_and_keeps_cancel_available(self) -> None:
        private = PASSWORD + TOKEN + "private-invalid-backend"
        malformed = []
        for location in ["value", "key", "stdout", "error", "nonfinite"]:
            operation = script_operation(SCRIPT_ARGS["request_id"])
            if location == "value":
                operation["result"]["value"] = {"text": private + "\ud800"}
            elif location == "key":
                operation["result"]["value"] = {private + "\udfff": "value"}
            elif location == "stdout":
                operation["result"]["output"] = [{"stream": "stdout", "text": private + "\ud800"}]
            elif location == "error":
                operation["status"] = "FAILED"
                operation["error"] = {"kind": "RESULT_ERROR", "message": private + "\udfff"}
            else:
                operation["result"]["value"] = {"number": float("nan")}
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

    async def test_unknown_help_names_remain_unavailable_without_fabricating_a_binding(self) -> None:
        async with self.stdio_client() as client:
            response = await client.call_tool("script_help", {"session_id": SCRIPT_ARGS["session_id"],
                                                              "expected_generation": 7, "names": ["move", "attack"]})
        self.assertFalse(response.is_error)
        self.assertEqual([], response.structured_content["functions"])
        self.assertEqual(["move", "attack"], response.structured_content["unavailable"])

    async def test_invalid_raw_script_arguments_fail_before_auth_without_exposing_source(self) -> None:
        private = PASSWORD + TOKEN + "private-script-input"
        cases = [
            {**SCRIPT_ARGS, "unexpected": private},
            {**SCRIPT_ARGS, "character_id": private},
            {**SCRIPT_ARGS, "source": {"private": private}},
            {**SCRIPT_ARGS, "source": ""},
            {**SCRIPT_ARGS, "source": "x" * 65537},
            {**SCRIPT_ARGS, "timeout_ms": True},
            {**SCRIPT_ARGS, "timeout_ms": 0},
            {**SCRIPT_ARGS, "timeout_ms": 5001},
            {**SCRIPT_ARGS, "timeout_ms": "1000"},
            {**SCRIPT_ARGS, "parameters": [private]},
            {**SCRIPT_ARGS, "expected_generation": False},
        ]
        cases.extend({key: value for key, value in SCRIPT_ARGS.items() if key != missing}
                     for missing in ["session_id", "expected_generation", "request_id", "source"])
        async with self.stdio_client() as client:
            for index, arguments in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("script_run", deepcopy(arguments))
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_invalid_help_raw_arguments_do_not_reach_the_backend_or_echo_private_values(self) -> None:
        private = PASSWORD + TOKEN
        base = {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7}
        cases = [{**base, "unexpected": private}, {**base, "names": private},
                 {**base, "names": ["bad/name"]}, {**base, "names": [False]},
                 {**base, "names": ["missing"] * 65}, {**base, "expected_generation": True},
                 {**base, "names": ["module.fn"]}, {**base, "names": ["$function"]},
                 {**base, "names": ["0function"]}, {**base, "names": ["a" * 65]}]
        async with self.stdio_client() as client:
            for index, arguments in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("script_help", arguments)
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_stdio_parameters_respect_16k_utf8_and_depth_16_at_the_protocol_boundary(self) -> None:
        boundary = {"text": "a" * (16384 - len('{"text":""}'))}
        too_large = {"text": boundary["text"] + "a"}
        depth_boundary = None
        for _ in range(16):
            depth_boundary = {"nested": depth_boundary}
        too_deep = {"nested": depth_boundary}
        async with self.stdio_client() as client:
            for parameters in [boundary, depth_boundary]:
                response = await client.call_tool("script_run", {**SCRIPT_ARGS, "parameters": parameters})
                self.assertFalse(response.is_error)
            for parameters in [too_large, too_deep]:
                response = await client.call_tool("script_run", {**SCRIPT_ARGS, "parameters": parameters})
                self.assertTrue(response.is_error)
                self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
        self.assertEqual(2, len([request for request in self.stub.requests if request["path"] == RUN_PATH]))

    async def test_official_sdk_cancellation_reaches_an_independent_http_cancel_request(self) -> None:
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

        self.stub.responder = respond
        async with self.stdio_client() as client:
            task = asyncio.create_task(client.call_tool("script_run", deepcopy(SCRIPT_ARGS)))
            try:
                self.assertTrue(await asyncio.to_thread(run_started.wait, 2))
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                self.assertTrue(await asyncio.to_thread(cancel_started.wait, 2), "SDK取消必须贯通到后端，而非仅取消本地等待。")
                cancel_requests = [request for request in self.stub.requests if request["path"] == CANCEL_PATH]
                self.assertEqual(1, len(cancel_requests))
                self.assertEqual(SCRIPT_ARGS["request_id"], cancel_requests[0]["body"]["data"]["target_request_id"])
            finally:
                cleanup_complete.set()
            response = await client.call_tool("script_help", {"session_id": SCRIPT_ARGS["session_id"], "expected_generation": 7})
            self.assertFalse(response.is_error)
