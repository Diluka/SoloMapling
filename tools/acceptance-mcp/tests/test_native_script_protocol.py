from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from threading import Event
import unittest

from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN
from tests.test_native_script_bridge import NATIVE_ARGS, NativeScriptStub, native_operation
from tests.test_script_bridge import CANCEL_PATH, HELP_PATH, RUN_PATH, SCRIPT_HELP


class NativeScriptProtocolTests(unittest.IsolatedAsyncioTestCase):
    """Actual SDK schema, request defaults and cancellation for the sole native script interface."""

    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = NativeScriptStub().start()

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

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
            help_response = await client.call_tool("script_help", {"session_id": NATIVE_ARGS["session_id"], "expected_generation": 7})
        self.assertFalse(response.is_error)
        self.assertEqual(native_operation(minimal["request_id"]), response.structured_content)
        self.assertEqual(SCRIPT_HELP, help_response.structured_content)
        data = next(request for request in self.stub.requests if request["path"] == RUN_PATH)["body"]["data"]
        self.assertEqual({"session_id": NATIVE_ARGS["session_id"], "expected_generation": 7,
                          "kind": "npc", "operation": "start", "context_id": None, "options": {}, "entry": None,
                          "args": [], "source": "", "parameters": {}, "timeout_ms": 1000}, data)
        self.assertFalse(response.structured_content["result"]["context_closed"])
        self.assertTrue(response.structured_content["result"]["invocation_settled"])
        self.assertEqual(response.structured_content, json.loads(response.content[0].text))
        self.assertNotIn(PASSWORD, response.model_dump_json() + help_response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json() + help_response.model_dump_json())

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
            help_result = await client.call_tool("script_help", {"session_id": NATIVE_ARGS["session_id"], "expected_generation": 7})
            self.assertFalse(help_result.is_error)
