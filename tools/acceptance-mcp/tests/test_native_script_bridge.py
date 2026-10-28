from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from threading import Event
import unittest

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_script_bridge import CANCEL_PATH, HELP_PATH, RUN_PATH, SCRIPT_ARGS, ScriptStub, script_operation


NATIVE_ENVIRONMENT = {
    "kind": "npc", "operation": "start", "options": {"npc_id": 1032110},
    "entry": "start", "args": [],
}
NATIVE_ARGS = {**SCRIPT_ARGS, "request_id": "native-script:fixture-1", "source": "",
               "parameters": {"label": "原生环境协议桩"}, "environment": NATIVE_ENVIRONMENT}


def native_operation(request_id: str, *, closed: bool = False, status: str = "SUCCEEDED") -> dict:
    """Protocol receipt only; native execution and cleanup require game acceptance."""
    operation = script_operation(request_id, status=status)
    operation["result"] = {
        "context_id": "native-context-fixture-1", "context_state": "CLOSED" if closed else "OPEN",
        "invocation_settled": True, "work_settled": True, "engine_exited": True,
        "context_closed": closed, "result": {"native_symbol": "cm"},
        "stdout": "原生环境协议桩\n", "stderr": "", "output_truncated": False,
    }
    return operation


class NativeScriptStub(ScriptStub):
    def default_response(self, request: dict) -> tuple[int, object]:
        if request["path"] == RUN_PATH:
            return 200, envelope(native_operation(request["body"]["requestId"],
                                                  closed=request["body"]["data"]["environment"]["operation"] == "close"))
        if request["path"] == HELP_PATH:
            return 200, envelope({
                "schema_version": 1, "language": "javascript", "functions": [], "unavailable": [],
                "native_environments": [{"kind": "npc", "globals": ["cm"], "host_object": "NPCConversationManager"}],
                "contexts": [{"context_id": "native-context-fixture-1", "kind": "npc", "state": "OPEN"}],
            })
        if request["path"] == CANCEL_PATH:
            return 200, envelope(native_operation(request["body"]["data"]["target_request_id"],
                                                  closed=True, status="CANCELLED"))
        return super().default_response(request)


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

    async def test_five_environment_kinds_are_forwarded_without_replacing_original_context(self) -> None:
        for kind in ("npc", "quest", "portal", "reactor", "event"):
            environment = {"kind": kind, "operation": "start", "options": {"native_selector": kind}}
            with self.subTest(kind=kind):
                response = await self.run_native(environment=environment)
                self.assertEqual(native_operation(NATIVE_ARGS["request_id"]), response)
                self.assertFalse(response["result"]["context_closed"], "持久原生上下文可合法留存。")
                self.assertEqual(environment, self.requests(RUN_PATH)[-1]["body"]["data"]["environment"])
        request = self.requests(RUN_PATH)[0]
        self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
        self.assertEqual("", request["body"]["data"]["source"])
        self.assertEqual(NATIVE_ARGS["parameters"], request["body"]["data"]["parameters"])
        self.assertEqual(1, self.stub.login_count)

    async def test_invoke_expression_entry_and_close_preserve_their_exact_descriptors(self) -> None:
        invocations = [
            ("cm.getPlayer().getName()", {"kind": "npc", "operation": "invoke", "context_id": "native-context-fixture-1"}),
            ("", {"kind": "npc", "operation": "invoke", "context_id": "native-context-fixture-1",
                   "entry": "action", "args": [1, 0, 0]}),
            ("", {"kind": "npc", "operation": "close", "context_id": "native-context-fixture-1"}),
        ]
        for source, environment in invocations:
            with self.subTest(operation=environment["operation"], source=source):
                response = await self.run_native(source=source, environment=environment)
                self.assertEqual(environment, self.requests(RUN_PATH)[-1]["body"]["data"]["environment"])
                self.assertEqual(environment["operation"] == "close", response["result"]["context_closed"])

    async def test_help_native_environments_and_contexts_are_backend_facts(self) -> None:
        help_result = await self.client.script_help(NATIVE_ARGS["session_id"], 7)
        self.assertEqual([], help_result["functions"])
        self.assertEqual([{"kind": "npc", "globals": ["cm"], "host_object": "NPCConversationManager"}],
                         help_result["native_environments"])
        self.assertEqual("native-context-fixture-1", help_result["contexts"][0]["context_id"])
        self.assertNotIn(PASSWORD, json.dumps(help_result))
        self.assertNotIn(TOKEN, json.dumps(help_result))

    async def test_invalid_environment_shapes_and_ambiguous_operations_fail_before_auth(self) -> None:
        private = PASSWORD + TOKEN + "native-private-input"
        cases = [
            {"environment": value} for value in [False, 1, [], private, {}, {"kind": "npc"},
                {"kind": "unknown", "operation": "start"}, {"kind": "npc", "operation": "unknown"},
                {**NATIVE_ENVIRONMENT, "unexpected": private},
                {**NATIVE_ENVIRONMENT, "context_id": "native-context-fixture-1"},
                {"kind": "npc", "operation": "invoke"},
                {"kind": "npc", "operation": "close"},
                {"kind": "npc", "operation": "invoke", "context_id": "bad/context"},
                {**NATIVE_ENVIRONMENT, "options": []}, {**NATIVE_ENVIRONMENT, "args": {}},
                {**NATIVE_ENVIRONMENT, "entry": "cm.start"}, {**NATIVE_ENVIRONMENT, "entry": False},
                {**NATIVE_ENVIRONMENT, "options": {"bad": float("nan")}},
                {**NATIVE_ENVIRONMENT, "args": ["\ud800"]}]
        ]
        cases.extend([
            {"source": "1", "environment": {"kind": "npc", "operation": "invoke", "context_id": "ctx", "entry": "action"}},
            {"source": "1", "environment": {"kind": "npc", "operation": "close", "context_id": "ctx"}},
            {"environment": {"kind": "npc", "operation": "close", "context_id": "ctx", "entry": "action"}},
            {"environment": {"kind": "npc", "operation": "close", "context_id": "ctx", "args": [1]}},
            {"environment": {"kind": "npc", "operation": "close", "context_id": "ctx", "options": {"npc_id": 1}}},
        ])
        for index, changes in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(BridgeError) as caught:
                await self.run_native(**changes)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
            self.assertNotIn(private, json.dumps(caught.exception.public_data()))
        self.assertEqual([], self.stub.requests)

    async def test_native_json_budget_is_shared_by_parameters_options_and_args(self) -> None:
        too_deep = None
        for _ in range(17):
            too_deep = {"nested": too_deep}
        cases = [
            {"parameters": {"p": "x" * 9000}, "environment": {**NATIVE_ENVIRONMENT, "options": {"o": "x" * 9000}}},
            {"parameters": {"a": "x" * 10000, "b": "y" * 10000}},
            {"parameters": {"p": "x" * 6000}, "environment": {**NATIVE_ENVIRONMENT,
                "options": {"o": "x" * 6000}, "args": ["a" * 6000]}},
            {"environment": {**NATIVE_ENVIRONMENT, "options": too_deep}},
            {"environment": {**NATIVE_ENVIRONMENT, "args": [too_deep]}},
        ]
        for index, changes in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(BridgeError) as caught:
                await self.run_native(**changes)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_pure_json_budget_counts_all_sibling_leaves(self) -> None:
        with self.assertRaises(BridgeError) as caught:
            await self.client.script_run(NATIVE_ARGS["session_id"], 7, request_id="pure:multi-leaf",
                                         source="parameters.a.length + parameters.b.length",
                                         parameters={"a": "x" * 10000, "b": "y" * 10000})
        self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual([], self.stub.requests)

    async def test_native_entry_and_shared_json_budget_accept_the_exact_boundaries(self) -> None:
        # Each root keeps its own depth budget; only their compact JSON byte
        # lengths are added, without inventing an extra wrapper depth.
        parameters = {"text": "a" * (16384 - len('{"text":""}') - len('{}') - len('[]'))}
        environment = {"kind": "npc", "operation": "start", "entry": "$" + "a" * 127}
        await self.run_native(parameters=parameters, environment=environment)
        self.assertEqual(environment, self.requests(RUN_PATH)[0]["body"]["data"]["environment"])
        self.assertEqual(parameters, self.requests(RUN_PATH)[0]["body"]["data"]["parameters"])
        for changes in [
            {"parameters": {"text": parameters["text"] + "a"}, "environment": environment},
            {"environment": {**environment, "entry": "$" + "a" * 128}},
        ]:
            with self.subTest(changes=list(changes)), self.assertRaises(BridgeError) as caught:
                await self.run_native(**changes)
            self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertEqual(1, len(self.requests(RUN_PATH)))

    async def test_environment_change_under_same_request_id_preserves_backend_conflict_without_retry(self) -> None:
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
            await self.run_native(environment={"kind": "event", "operation": "start", "options": {"script_name": "KerningTrain"}})
        self.assertEqual(409, caught.exception.http_status)
        self.assertFalse(caught.exception.retryable)
        self.assertEqual(3, len(self.requests(RUN_PATH)))
        self.assertEqual([], self.requests(CANCEL_PATH))
        self.assertNotIn(PASSWORD, json.dumps(caught.exception.public_data()))

    async def test_cancellation_carries_a_detached_original_environment_and_waits_for_cleanup(self) -> None:
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
            arguments["environment"]["options"]["npc_id"] = 9999999
            arguments["environment"]["args"].append(PASSWORD)
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
