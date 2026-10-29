from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from pathlib import Path
import tempfile
from threading import Event
import unittest

from mcp import Client

from solo_acceptance_mcp.server import build_server

from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.script_fixture import CANCEL_PATH, RUN_PATH, STREAM_PATH, SCRIPT_ARGS, ScriptStub, script_operation


NATIVE_ARGS = {**SCRIPT_ARGS, "request_id": "native-script:fixture-1", "source": "",
               "parameters": {"label": "原生脚本协议桩"}}


class NativeScriptProtocolTests(unittest.IsolatedAsyncioTestCase):
    """Actual SDK schema, request defaults and cancellation for the sole native script interface."""

    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = ScriptStub().start()
        self.directory = tempfile.TemporaryDirectory()
        self.output_file = Path(self.directory.name) / "output.ndjson"

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)
        self.directory.cleanup()

    async def test_stdio_file_receives_output_before_completion_with_unicode_and_channels(self) -> None:
        release = Event()
        notifications = []
        operation = script_operation(NATIVE_ARGS["request_id"])
        operation["result"].update(stdout="开始😀\n", stderr="B中文\n")
        association = {"request_id": NATIVE_ARGS["request_id"], "operation_id": operation["operation_id"]}
        self.output_file.write_text('{"previous":true}\n', encoding="utf-8")

        def stream(request):
            yield {"type": "started", **association}
            first = {"type": "output", **association, "seq": 1, "channel": "stdout",
                     "text": "开始😀\n", "output_truncated": False}
            encoded = (json.dumps(first, ensure_ascii=False) + "\n").encode()
            boundary = encoded.index("😀".encode()) + 1
            yield encoded[:boundary]
            yield encoded[boundary:]
            release.wait(4)
            yield {"type": "output", **association, "seq": 2, "channel": "stderr",
                   "text": "B中文\n", "output_truncated": False}
            yield {"type": "result", "operation": operation}

        async def progress(value, total, message):
            notifications.append(message)

        self.stub.stream_responder = stream
        async with self.stdio_client() as client:
            running = asyncio.create_task(client.call_tool("script_run", {
                **NATIVE_ARGS, "output_file": str(self.output_file)}, progress_callback=progress))
            try:
                async with asyncio.timeout(4):
                    while "开始😀" not in self.output_file.read_text(encoding="utf-8"):
                        await asyncio.sleep(0.01)
                self.assertFalse(running.done(), "首段输出已写入文件时，脚本仍应等待后续执行。")
                written = [json.loads(line) for line in self.output_file.read_text(encoding="utf-8").splitlines()]
                self.assertEqual([{"previous": True}, {"type": "started", **association}, {
                    "type": "output", **association, "seq": 1, "channel": "stdout",
                    "text": "开始😀\n", "output_truncated": False}], written)
            finally:
                release.set()
            result = await asyncio.wait_for(running, 4)
        written = [json.loads(line) for line in self.output_file.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["stdout", "stderr"], [event["channel"] for event in written if event.get("type") == "output"])
        self.assertEqual([1, 2], [event["seq"] for event in written if event.get("type") == "output"])
        self.assertFalse(any(event.get("type") == "result" for event in written))
        self.assertEqual([], notifications)
        self.assertEqual(operation, result.structured_content)
        request = next(request for request in self.stub.requests if request["path"].startswith(STREAM_PATH))
        self.assertNotIn("output_file", request["body"]["data"])
        self.assertNotIn("error_file", request["body"]["data"])

    async def test_stdio_routes_stderr_to_separate_or_only_error_file(self) -> None:
        error_file = Path(self.directory.name) / "error.ndjson"
        operation = script_operation(NATIVE_ARGS["request_id"])
        operation["result"].update(stdout="正常输出\n", stderr="诊断😀\n")
        self.stub.stream_responder = lambda request: self.stub.operation_events(request, operation)
        async with self.stdio_client() as client:
            result = await client.call_tool("script_run", {
                **NATIVE_ARGS, "output_file": str(self.output_file), "error_file": str(error_file)})
            self.assertEqual(operation, result.structured_content)
            output = [json.loads(line) for line in self.output_file.read_text(encoding="utf-8").splitlines()]
            errors = [json.loads(line) for line in error_file.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(["started", "output"], [event["type"] for event in output])
            self.assertEqual(["正常输出\n"], [event["text"] for event in output if event["type"] == "output"])
            self.assertEqual(["诊断😀\n"], [event["text"] for event in errors])
            self.assertEqual(["stderr"], [event["channel"] for event in errors])
            output_before = self.output_file.read_bytes()
            result = await client.call_tool("script_run", {**NATIVE_ARGS, "error_file": str(error_file)})
        self.assertEqual(operation, result.structured_content)
        self.assertEqual(output_before, self.output_file.read_bytes())
        errors = [json.loads(line) for line in error_file.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["诊断😀\n", "诊断😀\n"], [event["text"] for event in errors])

    async def test_output_file_open_failure_rejects_before_submitting_script(self) -> None:
        async with self.stdio_client() as client:
            result = await client.call_tool("script_run", {
                **NATIVE_ARGS, "output_file": str(Path(self.directory.name) / "missing" / "output.ndjson")})
        self.assertTrue(result.is_error)
        self.assertEqual("OUTPUT_FILE_UNAVAILABLE", result.structured_content["error"]["code"])
        self.assertFalse(result.structured_content["error"]["outcome_unknown"])
        self.assertEqual([], self.stub.requests)

    async def test_file_write_failure_keeps_written_output_and_requests_backend_cancel(self) -> None:
        operation = script_operation(NATIVE_ARGS["request_id"])
        operation["result"].update(stdout="失败前的输出\n", stderr="触发写入失败\n")
        self.stub.stream_responder = lambda request: self.stub.operation_events(request, operation)
        async with self.stdio_client() as client:
            result = await client.call_tool("script_run", {
                **NATIVE_ARGS, "output_file": str(self.output_file), "error_file": "/dev/full"})
        self.assertTrue(result.is_error)
        self.assertEqual("OUTPUT_WRITE_FAILED", result.structured_content["error"]["code"])
        self.assertTrue(result.structured_content["error"]["outcome_unknown"])
        written = [json.loads(line) for line in self.output_file.read_text(encoding="utf-8").splitlines()]
        self.assertEqual("失败前的输出\n", written[-1]["text"])
        cancels = [request for request in self.stub.requests if request["path"] == CANCEL_PATH]
        self.assertEqual(1, len(cancels))
        self.assertEqual(NATIVE_ARGS["request_id"], cancels[0]["body"]["data"]["target_request_id"])
        self.assertNotIn("output_file", cancels[0]["body"]["data"])
        self.assertNotIn("error_file", cancels[0]["body"]["data"])

    async def test_same_request_recovers_output_from_cursor_and_queries_its_window(self) -> None:
        arguments = {**NATIVE_ARGS, "since_output": 4, "output_file": str(self.output_file)}
        operation = script_operation(NATIVE_ARGS["request_id"])
        association = {"request_id": NATIVE_ARGS["request_id"], "operation_id": operation["operation_id"]}
        output = {"type": "output", **association, "seq": 7, "channel": "stdout",
                  "text": "恢复后的输出\n", "output_truncated": True}
        operation["script_output"] = {"items": [output], "next_cursor": 7, "oldest_cursor": 7,
                                      "latest_cursor": 7, "cursor_expired": True, "output_truncated": True}

        def stream(request):
            yield {"type": "started", **association}
            yield {"type": "gap", **association, "oldest_cursor": 7, "latest_cursor": 7}
            yield output
            yield {"type": "result", "operation": operation}

        def respond(request):
            if request["path"].startswith("/solo/acceptance/v1/operation/"):
                return 200, envelope(operation)
            return self.stub.default_response(request)

        self.stub.stream_responder = stream
        self.stub.responder = respond
        async with self.stdio_client() as client:
            result = await client.call_tool("script_run", arguments)
            queried = await client.call_tool("operation_get", {"operation_id": operation["operation_id"],
                                                               "since_output": 4})
        written = [json.loads(line) for line in self.output_file.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["started", "gap", "output"], [event["type"] for event in written])
        self.assertEqual(output, written[-1])
        self.assertEqual(operation, result.structured_content)
        self.assertEqual(operation["script_output"], queried.structured_content["script_output"])
        request = next(request for request in self.stub.requests if request["path"].startswith(STREAM_PATH))
        self.assertTrue(request["path"].endswith("?since_output=4"))
        self.assertNotIn("since_output", request["body"]["data"])
        self.assertNotIn("output_file", request["body"]["data"])
        self.assertEqual(NATIVE_ARGS["request_id"], request["body"]["requestId"])

    async def test_flat_schema_requires_kind_operation_and_has_no_environment_parameter(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(chat_protocol.REGISTERED_TOOLS, set(tools))
        schema = tools["script_run"].input_schema
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual({"session_id", "expected_generation", "request_id", "kind", "operation"}, set(schema["required"]))
        self.assertNotIn("environment", schema["properties"])
        self.assertEqual({"npc", "quest", "portal", "reactor", "event"}, set(schema["properties"]["kind"]["enum"]))
        self.assertEqual({"start", "invoke", "close"}, set(schema["properties"]["operation"]["enum"]))
        self.assertNotIn("null", str(schema["properties"]["options"]))
        self.assertNotIn("null", str(schema["properties"]["args"]))
        self.assertEqual([], self.stub.requests)

    async def test_omitted_source_uses_native_entry_and_sends_explicit_defaults(self) -> None:
        minimal = {"session_id": NATIVE_ARGS["session_id"], "expected_generation": 7,
                   "request_id": "native-script:defaults", "kind": "npc", "operation": "start"}
        async with self.stdio_client() as client:
            response = await client.call_tool("script_run", minimal)
        self.assertFalse(response.is_error)
        self.assertEqual(script_operation(minimal["request_id"]), response.structured_content)
        data = next(request for request in self.stub.requests if request["path"] == RUN_PATH)["body"]["data"]
        self.assertEqual({"session_id": NATIVE_ARGS["session_id"], "expected_generation": 7,
                          "kind": "npc", "operation": "start", "context_id": None, "options": {}, "entry": None,
                          "args": [], "source": "", "parameters": {}, "timeout_ms": 1000}, data)
        self.assertFalse(response.structured_content["result"]["context_closed"])
        self.assertTrue(response.structured_content["result"]["invocation_settled"])
        self.assertEqual(response.structured_content, json.loads(response.content[0].text))
        self.assertNotIn(PASSWORD, response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json())

    async def test_invalid_flat_native_combinations_are_rejected_before_auth_without_echo(self) -> None:
        private = PASSWORD + TOKEN + "private-native-input"
        cases = [
            {**NATIVE_ARGS, "environment": {"kind": "npc", "operation": "start", "private": private}},
            {**NATIVE_ARGS, "kind": private},
            {**NATIVE_ARGS, "operation": "invoke", "context_id": None},
            {**NATIVE_ARGS, "options": private},
            {**NATIVE_ARGS, "args": private},
            {**NATIVE_ARGS, "entry": "cm.action"},
            {**NATIVE_ARGS, "source": "cm.dispose()", "operation": "close", "context_id": "ctx", "options": {}},
            {**NATIVE_ARGS, "source": "1", "operation": "invoke", "context_id": "ctx", "entry": "reply"},
        ]
        async with self.stdio_client() as client:
            for index, arguments in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("script_run", deepcopy(arguments))
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_sdk_cancel_keeps_complete_flat_fingerprint(self) -> None:
        run_started, cancel_started, cleanup_complete = Event(), Event(), Event()

        def respond(request: dict):
            if request["path"] == RUN_PATH:
                run_started.set()
                cleanup_complete.wait(4)
            elif request["path"] == CANCEL_PATH:
                cancel_started.set()
                cleanup_complete.wait(4)
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client() as client:
            task = asyncio.create_task(client.call_tool("script_run", deepcopy(NATIVE_ARGS)))
            try:
                self.assertTrue(await asyncio.to_thread(run_started.wait, 2))
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                self.assertTrue(await asyncio.to_thread(cancel_started.wait, 2))
                cancels = [request for request in self.stub.requests if request["path"] == CANCEL_PATH]
                self.assertEqual(1, len(cancels))
                self.assertEqual({**{key: value for key, value in NATIVE_ARGS.items() if key != "request_id"},
                                  "target_request_id": NATIVE_ARGS["request_id"]}, cancels[0]["body"]["data"])
                self.assertNotIn("environment", cancels[0]["body"]["data"])
                self.assertEqual(1, sum(request["path"] == RUN_PATH for request in self.stub.requests))
            finally:
                cleanup_complete.set()

    async def test_native_kinds_callbacks_and_close_reach_http_with_original_id(self) -> None:
        calls = [
            {"kind": "npc", "options": {"npc_id": 1032110}},
            {"kind": "quest", "options": {"quest_id": 20600, "npc_id": 1101002, "phase": "start"}},
            {"kind": "portal", "options": {"portal_id": 1}},
            {"kind": "reactor", "options": {"reactor_oid": 218}},
            {"kind": "event", "options": {"script": "KerningTrain"}},
        ]
        calls.extend([
            {"operation": "invoke", "context_id": "native-context-fixture-1", "options": {},
             "entry": "reply", "args": [1, 0, 0]},
            {"operation": "close", "context_id": "native-context-fixture-1", "options": {}},
        ])
        async with Client(build_server(self.stub.config())) as client:
            for changes in calls:
                arguments = {**deepcopy(NATIVE_ARGS), **changes}
                response = await client.call_tool("script_run", arguments)
                self.assertFalse(response.is_error)
                self.assertEqual(arguments["operation"] == "close",
                                 response.structured_content["result"]["context_closed"])
                run = next(request for request in reversed(self.stub.requests) if request["path"] == RUN_PATH)
                self.assertEqual({"requestId": arguments["request_id"],
                                  "data": {key: value for key, value in arguments.items() if key != "request_id"}},
                                 run["body"])
        self.assertEqual(len(calls), sum(request["path"] == RUN_PATH for request in self.stub.requests))
