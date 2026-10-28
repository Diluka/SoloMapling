from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from threading import Event
import unittest

from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN
from tests.test_native_script_bridge import NATIVE_ARGS, NATIVE_ENVIRONMENT, NativeScriptStub, native_operation
from tests.test_script_bridge import CANCEL_PATH, HELP_PATH, RUN_PATH


class NativeScriptProtocolTests(unittest.IsolatedAsyncioTestCase):
    """Official SDK boundary; the fixture does not run native scripts or touch a game DB."""

    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = NativeScriptStub().start()

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_schema_keeps_existing_tools_and_advertises_a_strict_optional_environment(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(chat_protocol.REGISTERED_TOOLS, set(tools))
        schema = tools["script_run"].input_schema
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual({"session_id", "expected_generation", "request_id", "source"}, set(schema["required"]))
        self.assertIn("environment", schema["properties"])
        environment = schema["properties"]["environment"]
        candidates = environment.get("anyOf", [environment])
        model = next(candidate for candidate in candidates if candidate.get("type") != "null")
        if "$ref" in model:
            model = schema["$defs"][model["$ref"].rsplit("/", 1)[-1]]
        self.assertFalse(model["additionalProperties"])
        self.assertEqual({"kind", "operation"}, set(model["required"]))
        self.assertEqual({"npc", "quest", "portal", "reactor", "event"}, set(model["properties"]["kind"]["enum"]))
        self.assertEqual({"start", "invoke", "close"}, set(model["properties"]["operation"]["enum"]))
        self.assertEqual([], self.stub.requests)

    async def test_native_persistent_context_is_a_success_and_help_preserves_real_environment_descriptions(self) -> None:
        async with self.stdio_client() as client:
            response = await client.call_tool("script_run", deepcopy(NATIVE_ARGS))
            help_response = await client.call_tool("script_help", {"session_id": NATIVE_ARGS["session_id"], "expected_generation": 7})
        self.assertFalse(response.is_error)
        self.assertEqual(native_operation(NATIVE_ARGS["request_id"]), response.structured_content)
        self.assertFalse(response.structured_content["result"]["context_closed"])
        self.assertTrue(response.structured_content["result"]["invocation_settled"])
        self.assertEqual(response.structured_content, json.loads(response.content[0].text))
        self.assertEqual([], help_response.structured_content["functions"])
        self.assertEqual("cm", help_response.structured_content["native_environments"][0]["globals"][0])
        self.assertEqual("native-context-fixture-1", help_response.structured_content["contexts"][0]["context_id"])
        self.assertEqual(NATIVE_ENVIRONMENT, next(request for request in self.stub.requests if request["path"] == RUN_PATH)["body"]["data"]["environment"])
        self.assertNotIn(PASSWORD, response.model_dump_json() + help_response.model_dump_json())
        self.assertNotIn(TOKEN, response.model_dump_json() + help_response.model_dump_json())

    async def test_invalid_nested_environment_arguments_are_rejected_before_auth_and_without_echo(self) -> None:
        private = PASSWORD + TOKEN + "private-native-input"
        cases = [
            {**NATIVE_ARGS, "environment": {**NATIVE_ENVIRONMENT, "extra": private}},
            {**NATIVE_ARGS, "environment": {**NATIVE_ENVIRONMENT, "kind": private}},
            {**NATIVE_ARGS, "environment": {"kind": "npc", "operation": "invoke"}},
            {**NATIVE_ARGS, "environment": {**NATIVE_ENVIRONMENT, "options": private}},
            {**NATIVE_ARGS, "environment": {**NATIVE_ENVIRONMENT, "args": private}},
            {**NATIVE_ARGS, "environment": {**NATIVE_ENVIRONMENT, "entry": "cm.action"}},
            {**NATIVE_ARGS, "source": "cm.dispose()", "environment": {"kind": "npc", "operation": "close", "context_id": "ctx"}},
            {**NATIVE_ARGS, "source": "1", "environment": {"kind": "npc", "operation": "invoke", "context_id": "ctx", "entry": "action"}},
        ]
        async with self.stdio_client() as client:
            for index, arguments in enumerate(cases):
                with self.subTest(case=index):
                    response = await client.call_tool("script_run", deepcopy(arguments))
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    self.assertNotIn(private, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_official_sdk_cancel_keeps_native_context_and_complete_original_fingerprint(self) -> None:
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
                self.assertEqual(1, sum(request["path"] == RUN_PATH for request in self.stub.requests))
            finally:
                cleanup_complete.set()
            help_result = await client.call_tool("script_help", {"session_id": NATIVE_ARGS["session_id"], "expected_generation": 7})
            self.assertFalse(help_result.is_error)
