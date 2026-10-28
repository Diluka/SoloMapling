from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from threading import Event
import unittest

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_script_bridge import CANCEL_PATH, HELP_PATH, RUN_PATH, SCRIPT_ARGS, SCRIPT_HELP, ScriptStub, script_operation


NATIVE_ARGS = {**SCRIPT_ARGS, "request_id": "native-script:fixture-1", "source": "",
               "parameters": {"label": "原生脚本协议桩"}}


def native_operation(request_id: str, *, closed: bool = False, status: str = "SUCCEEDED") -> dict:
    return script_operation(request_id, closed=closed, status=status)


class NativeScriptStub(ScriptStub):
    pass


class NativeScriptBridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.stub = NativeScriptStub().start()
        self.client = BridgeClient(self.stub.config())

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await asyncio.to_thread(self.stub.stop)

    async def run_native(self, **changes) -> dict:
        return await self.client.script_run(**{**deepcopy(NATIVE_ARGS), **changes})

    def requests(self, path: str) -> list[dict]:
        return [request for request in self.stub.requests if request["path"] == path]

    async def test_five_native_kinds_are_forwarded_as_top_level_fields(self) -> None:
        for kind in ("npc", "quest", "portal", "reactor", "event"):
            with self.subTest(kind=kind):
                response = await self.run_native(kind=kind, options={"native_selector": kind})
                self.assertEqual(native_operation(NATIVE_ARGS["request_id"]), response)
                self.assertFalse(response["result"]["context_closed"], "持久原生上下文可合法留存。")
                data = self.requests(RUN_PATH)[-1]["body"]["data"]
                self.assertEqual(kind, data["kind"])
                self.assertEqual({"native_selector": kind}, data["options"])
                self.assertNotIn("environment", data)
        self.assertEqual(1, self.stub.login_count)

    async def test_invoke_source_entry_and_close_preserve_native_call_data(self) -> None:
        invocations = [
            {"operation": "invoke", "context_id": "native-context-fixture-1", "options": {},
             "source": "cm.getPlayer().getName()"},
            {"operation": "invoke", "context_id": "native-context-fixture-1", "options": {},
             "entry": "reply", "args": [1, 0, 0]},
            {"operation": "close", "context_id": "native-context-fixture-1", "options": {}},
        ]
        for changes in invocations:
            with self.subTest(operation=changes["operation"], source=changes.get("source", "")):
                response = await self.run_native(**changes)
                expected = {key: value for key, value in {**deepcopy(NATIVE_ARGS), **changes}.items() if key != "request_id"}
                self.assertEqual(expected, self.requests(RUN_PATH)[-1]["body"]["data"])
                self.assertEqual(changes["operation"] == "close", response["result"]["context_closed"])

    async def test_help_contains_only_backend_native_descriptions_and_live_contexts(self) -> None:
        help_result = await self.client.script_help(NATIVE_ARGS["session_id"], 7)
        self.assertEqual(SCRIPT_HELP, help_result)
        self.assertNotIn("functions", help_result)
        self.assertNotIn("unavailable", help_result)
        self.assertEqual(1, help_result["maximum_contexts_per_module"])
        self.assertNotIn(PASSWORD, json.dumps(help_result))
        self.assertNotIn(TOKEN, json.dumps(help_result))

    async def test_invalid_flat_native_arguments_and_ambiguous_operations_fail_before_auth(self) -> None:
        private = PASSWORD + TOKEN + "native-private-input"
        cases = [
            {"kind": value} for value in [None, False, 1, private]
        ] + [
            {"operation": None}, {"operation": "unknown"}, {"operation": False},
            {"context_id": "native-context-fixture-1"},
            {"operation": "invoke"}, {"operation": "close"},
            {"operation": "invoke", "context_id": "bad/context"},
            {"options": []}, {"args": {}}, {"entry": "cm.start"}, {"entry": False},
            {"options": {"bad": float("nan")}}, {"args": ["\ud800"]},
            {"source": "1", "operation": "invoke", "context_id": "ctx", "entry": "reply"},
            {"source": "1", "operation": "close", "context_id": "ctx", "options": {}},
            {"operation": "close", "context_id": "ctx", "options": {}, "entry": "reply"},
            {"operation": "close", "context_id": "ctx", "options": {}, "args": [1]},
            {"operation": "close", "context_id": "ctx"},
        ]
        for index, changes in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(BridgeError) as caught:
                await self.run_native(**changes)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn(private, json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_native_json_roots_keep_the_depth_guard(self) -> None:
        too_deep = None
        for _ in range(17):
            too_deep = {"nested": too_deep}
        cases = [
            {"options": too_deep}, {"args": [too_deep]},
        ]
        for index, changes in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(BridgeError) as caught:
                await self.run_native(**changes)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_entry_name_boundary_is_validated_before_http(self) -> None:
        entry = "$" + "a" * 127
        await self.run_native(entry=entry)
        self.assertEqual(entry, self.requests(RUN_PATH)[0]["body"]["data"]["entry"])
        with self.assertRaises(BridgeError) as caught:
            await self.run_native(entry="$" + "a" * 128)
        self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual(1, len(self.requests(RUN_PATH)))

    async def test_native_call_change_under_original_id_preserves_conflict_without_retry(self) -> None:
        first = None

        def respond(request: dict):
            nonlocal first
            if request["path"] == RUN_PATH:
                data = request["body"]["data"]
                if first is None:
                    first = deepcopy(data)
                elif data != first:
                    return 409, {"message": PASSWORD + TOKEN + "request_id_conflict"}
            return self.stub.default_response(request)

        self.stub.responder = respond
        await self.run_native()
        await self.run_native()
        with self.assertRaises(BridgeError) as caught:
            await self.run_native(kind="event", options={"script": "KerningTrain"})
        self.assertEqual(409, caught.exception.http_status)
        self.assertFalse(caught.exception.retryable)
        self.assertEqual(3, len(self.requests(RUN_PATH)))
        self.assertEqual([], self.requests(CANCEL_PATH))
        self.assertNotIn(PASSWORD, json.dumps(caught.exception.public_data()))

    async def test_cancellation_carries_a_detached_flat_fingerprint_and_waits_for_cleanup(self) -> None:
        run_started, cancel_started, cleanup_complete = Event(), Event(), Event()
        original = deepcopy(NATIVE_ARGS)
        arguments = deepcopy(original)

        def respond(request: dict):
            if request["path"] == RUN_PATH:
                run_started.set()
                cleanup_complete.wait(4)
                return 200, envelope(native_operation(original["request_id"], closed=True, status="CANCELLED"))
            if request["path"] == CANCEL_PATH:
                cancel_started.set()
                cleanup_complete.wait(4)
            return self.stub.default_response(request)

        self.stub.responder = respond
        task = asyncio.create_task(self.client.script_run(**arguments))
        try:
            self.assertTrue(await asyncio.to_thread(run_started.wait, 2))
            arguments["options"]["npc_id"] = 9999999
            arguments["args"].append(PASSWORD)
            arguments["parameters"]["label"] = TOKEN
            task.cancel()
            self.assertTrue(await asyncio.to_thread(cancel_started.wait, 2))
            await asyncio.sleep(0.03)
            self.assertFalse(task.done())
            run_body = self.requests(RUN_PATH)[0]["body"]
            self.assertEqual({key: value for key, value in original.items() if key != "request_id"}, run_body["data"])
            cancel_body = self.requests(CANCEL_PATH)[0]["body"]
            self.assertEqual({**run_body["data"], "target_request_id": original["request_id"]}, cancel_body["data"])
            self.assertNotEqual(original["request_id"], cancel_body["requestId"])
            self.assertEqual(1, len(self.requests(RUN_PATH)))
        finally:
            cleanup_complete.set()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 4)
