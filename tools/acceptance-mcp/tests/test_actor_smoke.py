from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit

from solo_acceptance_mcp.actor_smoke import ActorSmokeFailure, checkpoint, require_persistence_summaries, run_actor_smoke
from solo_acceptance_mcp.smoke import IMPLEMENTED_TOOLS
from tests.helpers import BridgeStub, CAPABILITIES, PASSWORD, TOKEN, envelope


class ActorSmokeStub(BridgeStub):
    def __init__(self, mode: str = "normal") -> None:
        self.mode = mode
        self.sessions: dict[str, dict] = {}
        self.operations: dict[str, dict] = {}
        self.open_requests: dict[str, str] = {}
        self.close_requests: dict[str, str] = {}
        self.creations = 0
        super().__init__()

    def actor(self, number: int) -> dict:
        return {
            "character_id": 42, "account_id": 12, "profile_id": "primary", "name": "McpPrimary",
            "world_id": 0, "channel_id": 1, "map_id": 10000,
            "position": {"x": 10, "y": 20}, "level": 2 if self.mode == "reset" and number > 1 else 1,
            "exp": 0, "hp": 50, "mp": 5, "meso": 0, "job_id": 0, "guild_id": 0, "party_id": -1,
            "logged_in_world": True, "client_logged_in": True, "is_current": True,
            "persisted": True, "is_bot": self.mode == "bot",
            "inventory_summary": {
                category: {"count": 1 if category == "equipped" else 0, "truncated": False,
                           "items": [{"item_id": 1302000, "quantity": 1, "position": -11}]
                           if category == "equipped" else []}
                for category in ["equipped", "equip", "use", "setup", "etc", "cash"]
            },
            "quest_summary": {"count": 0, "truncated": False, "quests": []},
            "packet_events": [], "packet_events_dropped": 0,
            "scene": {
                "scene_ref": "scene-actor-fixture", "world_id": 0, "channel_id": 1,
                "map_id": 10000, "map_instance_key": "map-actor", "runtime_key": "runtime-actor",
                "runtime_generation": "generation-fixture-1",
            },
        }

    def response(self, operation: dict, *, queued: bool = False) -> dict:
        result = deepcopy(operation)
        result["session"] = deepcopy(self.sessions[result["session_id"]])
        if queued:
            result["status"] = "QUEUED"
            result["result"] = {}
        return result

    def new_operation(self, kind: str, session: dict, result: dict, *, status: str = "SUCCEEDED") -> dict:
        operation = {
            "schema_version": 1, "operation_id": "operation-" + str(len(self.operations) + 1),
            "kind": kind, "status": status, "step": "fixture-native-result",
            "session_id": session["session_id"], "session_generation": session["session_generation"],
            "profile_id": "primary", "runtime_generation": "generation-fixture-1",
            "cancel_requested": False, "result": result, "error": None,
            "events": {"items": [], "next_cursor": 0, "oldest_cursor": 0, "latest_cursor": 0,
                       "cursor_expired": False, "truncated": False},
        }
        self.operations[operation["operation_id"]] = operation
        return operation

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            caps = deepcopy(CAPABILITIES)
            if self.mode != "disabled":
                caps.update(implementation_round="R2a", enabled_tools=sorted(IMPLEMENTED_TOOLS))
            return 200, envelope(caps)
        if path == "/solo/acceptance/v1/actor/open":
            if self.mode == "open_timeout":
                time.sleep(0.3)
            request_id = request["body"]["requestId"]
            if request_id in self.open_requests:
                if self.mode == "reject_duplicate":
                    return 409, {"sensitive": "never echo a rejected request body"}
                return 200, envelope(self.response(self.operations[self.open_requests[request_id]]))
            self.creations += 1
            actor = self.actor(self.creations)
            session = {"session_id": "session-" + str(self.creations), "session_generation": self.creations,
                       "profile_id": "primary", "runtime_generation": "generation-fixture-1",
                       "state": "OPENING" if self.mode == "pending" else "OPEN",
                       "actor_current": self.mode != "pending", "actor": actor}
            self.sessions[session["session_id"]] = session
            result = {"observed_outcome": "native_actor_online", "actor": actor}
            operation = self.new_operation("OPEN", session, result,
                                           status="RUNNING" if self.mode == "pending" else "SUCCEEDED")
            self.open_requests[request_id] = operation["operation_id"]
            receipt = self.response(operation, queued=True)
            if self.mode == "malformed_receipt":
                receipt["operation_id"] = None
            return 200, envelope(receipt)
        if path == "/solo/acceptance/v1/actor/close":
            request_id = request["body"]["requestId"]
            if request_id in self.close_requests:
                return 200, envelope(self.response(self.operations[self.close_requests[request_id]]))
            session = self.sessions[request["body"]["data"]["session_id"]]
            session.update(state="CLOSED", actor_current=False, actor=None)
            result = {"observed_outcome": "native_actor_closed", "character_id": 42, "account_id": 12,
                      "native_cleanup_verified": True, "persisted_state_verified": self.mode != "bad_save",
                      "persistence_verification_scope": "fixture-public-character-fields"}
            operation = self.new_operation("CLOSE", session, result)
            self.close_requests[request_id] = operation["operation_id"]
            return 200, envelope(self.response(operation, queued=True))
        if path.startswith("/solo/acceptance/v1/operation/"):
            parts = path.split("/")
            operation_id = parts[-2] if parts[-1] == "cancel" else parts[-1]
            operation = self.operations[operation_id]
            if parts[-1] == "cancel" and operation["status"] == "RUNNING":
                operation.update(status="CANCELLED", cancel_requested=True)
                self.sessions[operation["session_id"]].update(state="CANCELLED", actor_current=False, actor=None)
            return 200, envelope(self.response(operation))
        if path == "/solo/acceptance/v1/observe":
            actor = next(session["actor"] for session in self.sessions.values() if session["actor_current"])
            context = deepcopy(actor["scene"])
            if self.mode == "scene":
                context["map_instance_key"] = "wrong-instance"
            return 200, envelope({
                "schema_version": 1, "runtime_generation": "generation-fixture-1", "context": context,
                "players": {"total_count": 1, "truncated": False, "items": [{"id": 42, "is_bot": False}]},
            })
        return super().default_response(request)


class ActorSmokeTests(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, mode: str = "normal", *, timeout: float = 2, patch_checkpoint: bool = False):
        stub = ActorSmokeStub(mode).start()
        try:
            root = Path(__file__).resolve().parents[1] / ".test-tmp"
            root.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json", timeout=0.1 if mode == "open_timeout" else 2)
                output = folder / "actor-report.json"
                arguments = dict(profile_id="primary", expected_base_url=stub.base_url, output=output,
                                 operation_timeout=timeout, poll_interval=0.01)
                if patch_checkpoint:
                    calls = 0
                    def failing_checkpoint(path, report):
                        nonlocal calls
                        calls += 1
                        if calls > 2:
                            raise OSError("simulated local evidence disk failure")
                        checkpoint(path, report)
                    with patch("solo_acceptance_mcp.actor_smoke.checkpoint", failing_checkpoint):
                        report = await run_actor_smoke(config, **arguments)
                else:
                    report = await run_actor_smoke(config, **arguments)
                    self.assertEqual(report, json.loads(output.read_text()))
                    self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return stub, report
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_actual_stdio_open_close_reopen_preserves_native_identity_and_bounded_data(self):
        stub, report = await self.run_fixture()
        self.assertTrue(report["passed"])
        self.assertTrue(report["actor_control_verified"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertFalse(report["game_client_verified"])
        self.assertEqual(2, stub.creations)
        self.assertEqual(2, len(stub.close_requests))
        self.assertEqual(report["first_actor"]["character_id"], report["reopened_actor"]["character_id"])
        self.assertTrue(report["closed_request_replay_verified"])
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        self.assertNotIn(PASSWORD, json.dumps(report))
        self.assertNotIn(TOKEN, json.dumps(report))

    async def test_explicit_target_mismatch_fails_before_authentication_or_mutation(self):
        stub = ActorSmokeStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                config = stub.write_config(Path(directory) / "private.json")
                with self.assertRaises(ActorSmokeFailure):
                    await run_actor_smoke(config, profile_id="primary", expected_base_url="http://127.0.0.1:1")
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_r1_only_capability_does_not_start_an_actor(self):
        stub, report = await self.run_fixture("disabled")
        self.assertFalse(report["passed"])
        self.assertEqual(0, stub.creations)
        self.assertEqual("NO_ACTOR_OPERATION_STARTED", report["cleanup"]["state"])

    async def test_wrong_scene_identity_and_environment_bot_are_rejected_and_cleaned(self):
        for mode in ["scene", "bot"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))

    async def test_unverified_save_is_not_reported_as_a_success_or_replayed(self):
        stub, report = await self.run_fixture("bad_save")
        self.assertFalse(report["passed"])
        self.assertEqual("NATIVE_SAVE_UNVERIFIED", report["failure"])
        self.assertEqual(1, len(stub.close_requests))

    async def test_growth_reset_on_reopen_fails_and_releases_the_second_session(self):
        stub, report = await self.run_fixture("reset")
        self.assertFalse(report["passed"])
        self.assertEqual("PERSISTED_ACTOR_CHANGED", report["failure"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(2, len(stub.close_requests))

    async def test_pending_open_timeout_is_cancelled_without_promoting_it_to_logged_in(self):
        stub, report = await self.run_fixture("pending", timeout=0.1)
        self.assertFalse(report["passed"])
        self.assertEqual("OPERATION_TIMEOUT", report["failure"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(0, len(stub.close_requests))
        self.assertTrue(any(request["path"].endswith("/cancel") for request in stub.requests))

    async def test_unknown_write_outcome_keeps_recovery_key_without_automatic_replay(self):
        stub, report = await self.run_fixture("open_timeout")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        opens = [request for request in stub.requests if request["path"].endswith("/actor/open")]
        self.assertEqual(1, len(opens))
        self.assertEqual(opens[0]["body"]["requestId"], report["recovery"]["unknown_write"]["request_id"])
        self.assertEqual(0, len(stub.close_requests))

    async def test_malformed_write_receipt_preserves_recovery_and_cannot_claim_no_actor_started(self):
        stub, report = await self.run_fixture("malformed_receipt")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        self.assertEqual("UNKNOWN_WRITE_REQUIRES_SAME_ID_RECOVERY", report["cleanup"]["state"])
        opens = [request for request in stub.requests if request["path"].endswith("/actor/open")]
        self.assertEqual(1, len(opens))
        self.assertEqual(opens[0]["body"]["requestId"], report["recovery"]["unknown_write"]["request_id"])
        self.assertEqual(0, len(stub.close_requests))
        self.assertTrue(any(session["actor_current"] for session in stub.sessions.values()))

    async def test_definite_write_refusal_still_closes_the_previously_known_actor(self):
        stub, report = await self.run_fixture("reject_duplicate")
        self.assertFalse(report["passed"])
        self.assertEqual("MCP_CALL_FAILED", report["failure"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertIsNone(report["recovery"]["unknown_write"])
        opens = [request for request in stub.requests if request["path"].endswith("/actor/open")]
        self.assertEqual(2, len(opens))
        self.assertEqual(1, stub.creations)
        self.assertEqual(1, len(stub.close_requests))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))

    async def test_evidence_disk_failure_does_not_prevent_native_cleanup(self):
        stub, report = await self.run_fixture(patch_checkpoint=True)
        self.assertFalse(report["passed"])
        self.assertEqual("CHECKPOINT_WRITE_FAILED", report["evidence_error"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(2, len(stub.close_requests))

    def test_missing_or_truncated_persistence_data_cannot_pass_by_equal_null_values(self):
        stub = ActorSmokeStub()
        try:
            for field in ["inventory_summary", "quest_summary"]:
                actor = stub.actor(1)
                actor[field] = None
                with self.subTest(field=field), self.assertRaises(ActorSmokeFailure):
                    require_persistence_summaries(actor)
            actor = stub.actor(1)
            actor["inventory_summary"]["cash"].update(count=101, truncated=True)
            with self.assertRaises(ActorSmokeFailure):
                require_persistence_summaries(actor)
            actor = stub.actor(1)
            actor["quest_summary"].update(count=True)
            with self.assertRaises(ActorSmokeFailure):
                require_persistence_summaries(actor)
        finally:
            # This fixture is never started; close its bound random socket explicitly.
            stub.http.server_close()
