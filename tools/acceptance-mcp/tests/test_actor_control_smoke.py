from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import time
import unittest
from urllib.parse import urlsplit

from solo_acceptance_mcp.actor_control_smoke import MODULE_CAPABILITY, run_actor_control_smoke
from solo_acceptance_mcp.actor_smoke import ActorSmokeFailure
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_chat_action_smoke import ChatActionStub
from tests.test_npc_center_smoke import NpcCenterStub


class ActorControlStub(NpcCenterStub):
    """Expose native-shaped lifecycle facts at the HTTP boundary; no game or database is contacted."""

    def __init__(self, mode: str = "normal") -> None:
        self.runtimes: dict[str, dict] = {}
        self.control_requests: dict[str, str] = {}
        self.control_inputs: dict[str, dict] = {}
        self.control_transitions: list[str] = []
        self.paused_actions: list[str] = []
        super().__init__(mode)

    def actor(self, number: int) -> dict:
        actor = super().actor(number)
        runtime = {"id": "runtime-module-" + str(number), "epoch": "generation-fixture-1",
                   "state": "BOUND", "character_id": actor["character_id"], "origin_kind": "mcp_acceptance",
                   "module": {"type_id": "mcp-control", "instance_id": "session-" + str(number),
                              "generation": 2, "state": "ACTIVE"},
                   "lane": {"closed": False, "pending": 0, "ordinary": 0, "cleanup": 0}}
        self.runtimes["session-" + str(number)] = runtime
        actor["runtime"] = runtime
        return actor

    def response(self, operation: dict, *, queued: bool = False) -> dict:
        result = super().response(operation, queued=queued)
        runtime = self.runtimes.get(result["session_id"])
        if runtime is not None:
            result["session"]["runtime"] = deepcopy(runtime)
        return result

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            status, payload = super().default_response(request)
            caps = payload["data"]
            caps["enabled_tools"].append("actor_control")
            caps["source"]["soloWorkingTreeDirty"] = self.mode == "dirty_source"
            caps["actor_control"]["module_control"] = deepcopy(MODULE_CAPABILITY)
            if self.mode == "missing_module_capability":
                caps["actor_control"].pop("module_control")
            elif self.mode == "bad_capability":
                caps["actor_control"]["module_control"]["pause_preserves_native_dialogue"] = False
            return status, payload
        if path == "/solo/acceptance/v1/actor/act":
            session_id = request["body"]["data"]["session_id"]
            if self.runtimes[session_id]["module"]["state"] != "ACTIVE":
                self.paused_actions.append(request["body"]["data"]["action"])
                return 409, {"sensitive": PASSWORD + TOKEN}
            if request["body"]["data"]["action"] == "chat":
                return ChatActionStub.default_response(self, request)
        if path == "/solo/acceptance/v1/actor/close":
            response = super().default_response(request)
            runtime = self.runtimes[request["body"]["data"]["session_id"]]
            runtime["state"], runtime["lane"]["closed"] = "CLOSED", True
            if self.mode == "close_wrong_runtime":
                runtime["id"] = "runtime-foreign-closed-host"
            if self.mode == "control_and_close_timeout":
                time.sleep(0.3)
            return response
        if path != "/solo/acceptance/v1/actor/module/control":
            return super().default_response(request)

        request_id, data = request["body"]["requestId"], request["body"]["data"]
        if request_id in self.control_requests:
            if data != self.control_inputs[request_id]:
                return 409, {"sensitive": PASSWORD + TOKEN}
            return 200, envelope(self.response(self.operations[self.control_requests[request_id]]))
        session = self.sessions[data["session_id"]]
        runtime = self.runtimes[data["session_id"]]
        module, action = runtime["module"], data["operation"]
        previous = module["generation"]
        if data["expected_module_generation"] != previous:
            return 409, {"sensitive": PASSWORD + TOKEN}
        if action == "pause":
            if module["state"] != "ACTIVE":
                return 409, {"sensitive": PASSWORD + TOKEN}
            if self.mode != "pause_keeps_active":
                module["state"] = "SUSPENDED"
            if self.mode == "pause_loses_dialogue":
                session["actor"]["dialogue"] = self.inactive_dialogue()
        elif action == "resume":
            if module["state"] != "SUSPENDED":
                return 409, {"sensitive": PASSWORD + TOKEN}
            module["state"] = "ACTIVE"
            if self.mode != "resume_reuses_generation":
                module["generation"] += 1
            if self.mode == "resume_replaces_host":
                runtime["id"] = "runtime-replaced-unexpectedly"
        else:
            return 400, {"sensitive": PASSWORD + TOKEN}
        result = {"observed_outcome": "actor_module_paused" if action == "pause" else "actor_module_resumed",
                  "runtime_id": runtime["id"], "previous_module_generation": previous,
                  "module_generation": module["generation"], "module_state": "SUSPENDED" if action == "pause" else "ACTIVE",
                  "module_control_complete": self.mode != "incomplete_control"}
        if action == "pause":
            result["module_drained"] = self.mode != "pause_not_drained"
        operation = self.new_operation("MODULE_CONTROL", session, result)
        operation["module_operation"] = action
        self.control_requests[request_id] = operation["operation_id"]
        self.control_inputs[request_id] = deepcopy(data)
        self.control_transitions.append(action)
        receipt = self.response(operation, queued=True)
        if self.mode == "foreign_control_receipt":
            receipt.update(session_id="session-foreign", session_generation=999)
            receipt["session"].update(session_id="session-foreign", session_generation=999)
        if self.mode in {"control_timeout", "control_and_close_timeout"} or self.mode == "resume_timeout" and action == "resume":
            time.sleep(0.3)
        return 200, envelope(receipt)


class ActorControlSmokeTests(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, mode: str = "normal", *, expected_revision: str | None = "a" * 40):
        stub = ActorControlStub(mode).start()
        try:
            root = Path(__file__).resolve().parents[1] / ".test-tmp"
            root.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json", timeout=0.1 if mode in {
                    "control_timeout", "control_and_close_timeout", "resume_timeout"} else 2)
                output = folder / "module-report.json"
                report = await run_actor_control_smoke(config, profile_id="primary", expected_base_url=stub.base_url,
                    output=output, operation_timeout=2, poll_interval=0.01, expected_revision=expected_revision)
                self.assertEqual(report, json.loads(output.read_text()))
                self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return stub, report
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_official_stdio_preserves_native_dialogue_while_paused_and_resumes_a_new_module_generation(self):
        stub, report = await self.run_fixture()
        self.assertTrue(report["passed"], report)
        self.assertTrue(report["module_control_verified"])
        self.assertTrue(report["same_host_verified"])
        self.assertTrue(report["root_closed_verified"])
        self.assertEqual(["pause", "resume"], stub.control_transitions)
        self.assertEqual(["chat", "npc_cancel"], stub.paused_actions)
        self.assertEqual(1, len(stub.broadcasts))
        self.assertEqual(1, len(stub.cancelled_dialogue_versions))
        self.assertEqual(2, len(stub.close_requests))
        self.assertEqual(2, stub.creations)
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        self.assertTrue(report["cleanup"]["verified"])
        self.assertIsNone(report["module_recovery"]["pending_operation"])
        self.assertFalse(report["game_client_verified"])
        self.assertFalse(report["model_called"])
        self.assertNotIn(PASSWORD, json.dumps(report))
        self.assertNotIn(TOKEN, json.dumps(report))

    async def test_missing_or_unsafe_module_capabilities_and_unfixed_source_stop_before_actor_open(self):
        for mode in ["missing_module_capability", "bad_capability", "dirty_source"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertEqual(0, stub.creations)
                self.assertEqual([], stub.control_transitions)

    async def test_source_mismatch_stops_before_mutation(self):
        stub, report = await self.run_fixture(expected_revision="b" * 40)
        self.assertFalse(report["passed"])
        self.assertEqual("MODULE_SOURCE_MISMATCH", report["failure"])
        self.assertEqual(0, stub.creations)

    async def test_root_close_does_not_accept_a_foreign_closed_host(self):
        stub, report = await self.run_fixture("close_wrong_runtime")
        self.assertFalse(report["passed"])
        self.assertEqual("MODULE_ROOT_CLOSE_UNVERIFIED", report["failure"])
        self.assertFalse(report["cleanup"]["verified"])
        self.assertEqual(1, len(stub.close_requests))

    async def test_incomplete_pause_wrong_state_or_changed_dialogue_cannot_be_reported_as_passed(self):
        for mode, reason in [("pause_not_drained", "MODULE_PAUSE_NOT_DRAINED"),
                             ("pause_keeps_active", "MODULE_STATE_UNVERIFIED"),
                             ("pause_loses_dialogue", "MODULE_PAUSE_CHANGED_DIALOGUE"),
                             ("incomplete_control", "MODULE_CONTROL_NOT_COMPLETE")]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertEqual(reason, report["failure"])
                self.assertEqual(["pause"], stub.control_transitions)
                self.assertTrue(report["cleanup"]["verified"])

    async def test_resume_must_change_generation_and_preserve_the_host(self):
        for mode, reason in [("resume_reuses_generation", "MODULE_RESUME_REUSED_GENERATION"),
                             ("resume_replaces_host", "MODULE_CONTROL_NOT_COMPLETE")]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertEqual(reason, report["failure"])
                self.assertEqual(mode != "resume_replaces_host", report["cleanup"]["verified"])
                self.assertEqual(["pause", "resume"], stub.control_transitions)

    async def test_unknown_control_result_retains_its_request_without_replay_and_closes_the_known_actor(self):
        for mode, count in [("control_timeout", 1), ("resume_timeout", 2), ("foreign_control_receipt", 1)]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertEqual(count, len(stub.control_transitions))
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))
                unknown = report["module_recovery"]["unknown_write"]
                self.assertEqual("actor_control", unknown["tool"])
                self.assertTrue(unknown["request_id"].startswith(report["request_prefix"]))
                self.assertEqual({"tool", "request_id", "session_id", "expected_generation",
                                  "expected_module_generation", "operation"}, set(unknown))
                self.assertEqual(1, unknown["expected_generation"])
                self.assertEqual(2, unknown["expected_module_generation"])
                self.assertIn(unknown["operation"], {"pause", "resume"})
                self.assertEqual(unknown, report["recovery"]["unknown_write"])

    async def test_unknown_control_and_unknown_close_keep_both_recovery_ids(self):
        stub, report = await self.run_fixture("control_and_close_timeout")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        self.assertEqual(["pause"], stub.control_transitions)
        self.assertEqual("actor_control", report["module_recovery"]["unknown_write"]["tool"])
        self.assertEqual("actor_close", report["recovery"]["unknown_write"]["tool"])
        self.assertEqual(1, len(stub.close_requests))

    async def test_explicit_target_mismatch_or_invalid_revision_fails_before_http(self):
        stub = ActorControlStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                config = stub.write_config(Path(directory) / "private.json")
                for options in [{"expected_base_url": "http://127.0.0.1:1"},
                                {"expected_base_url": stub.base_url, "expected_revision": "not-a-revision"}]:
                    with self.assertRaises(ActorSmokeFailure):
                        await run_actor_control_smoke(config, profile_id="primary", **options)
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)


if __name__ == "__main__":
    unittest.main()
