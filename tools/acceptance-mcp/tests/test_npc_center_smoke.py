from __future__ import annotations

import asyncio
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import time
import unittest
from urllib.parse import urlsplit

from solo_acceptance_mcp.actor_smoke import ActorSmokeFailure
from solo_acceptance_mcp.npc_center_smoke import run_npc_center_smoke
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_chat_action_smoke import ChatActionStub


NPC_ACTIONS = ["chat", "npc_center_open", "npc_cancel"]
NPC_CAPABILITY = {
    "entrypoints": ["native_ui_center"], "message_types": [4],
    "version_scope": "session_packet_sequence", "cancel_requires_latest_version": True,
    "npc_minimum_interval_ms": 500,
}


class NpcCenterStub(ChatActionStub):
    """Native-shaped NPC state exposed only through the local HTTP fixture."""

    def __init__(self, mode: str = "normal") -> None:
        self.npc_requests: dict[str, str] = {}
        self.npc_actions: list[dict] = []
        self.open_dialogue_versions: list[int] = []
        self.cancelled_dialogue_versions: list[int] = []
        self.stale_cancel_rejections: list[dict] = []
        self.dialogue_packets: list[dict] = []
        self.closed_with_active_dialogue: list[bool] = []
        self.last_center_open_at: float | None = None
        self.delayed_npc_packet_pending = False
        self.delayed_npc_packet_injected = False
        super().__init__(mode)

    @staticmethod
    def inactive_dialogue() -> dict:
        return {"dialogue_version": None, "active": False, "supported": False,
                "entry_type": "unsupported", "npc_template_id": None,
                "message_type": None, "packet_sequence": None}

    def actor(self, number: int) -> dict:
        actor = super().actor(number)
        actor["dialogue"] = self.inactive_dialogue()
        actor["tickets_summary"] = {"nx_credit": 0, "maple_point": 0, "nx_prepaid": 0}
        return actor

    def append_packet(self, event: dict) -> int:
        event = deepcopy(event)
        event.update(sequence=self.packet_events[-1]["sequence"] + 1,
                     received_at=datetime.now(timezone.utc).isoformat())
        self.packet_events.append(event)
        return event["sequence"]

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            status, payload = super().default_response(request)
            caps = payload["data"]
            caps["actions"] = list(NPC_ACTIONS)
            caps["actor_control"].update(actions=list(NPC_ACTIONS), npc_dialogue=deepcopy(NPC_CAPABILITY))
            caps["actor_control"]["native_clock_update_interval_ms"] = 777 if self.mode == "cached_clock" else 1
            caps["actor_control"]["packet_observation"]["decoded_packets"].append(
                {"event_type": "npc_dialogue", "opcode": 304, "message_types": [4]})
            if self.mode == "missing_npc_capability":
                caps["actor_control"].pop("npc_dialogue")
            elif self.mode == "missing_npc_action":
                caps["actions"].remove("npc_cancel")
                caps["actor_control"]["actions"].remove("npc_cancel")
            elif self.mode == "missing_npc_decoder":
                caps["actor_control"]["packet_observation"]["decoded_packets"].pop()
            return status, payload
        if path == "/solo/acceptance/v1/actor/close":
            session = self.sessions[request["body"]["data"]["session_id"]]
            self.closed_with_active_dialogue.append(session["actor"]["dialogue"]["active"])
            status, payload = super().default_response(request)
            operation = self.operations[self.close_requests[request["body"]["requestId"]]]
            operation["result"]["native_dialogue_cleanup_verified"] = self.mode != "bad_close_cleanup"
            payload = envelope(self.response(operation, queued=True))
            if self.mode == "close_timeout":
                time.sleep(0.3)
            return status, payload
        if (path == "/solo/acceptance/v1/actor/observe" and self.delayed_npc_packet_pending
                and self.cancelled_dialogue_versions):
            # The immediate replay observation was clean. A delayed duplicate now
            # reaches history while the current native conversation remains closed.
            self.append_packet(self.dialogue_packets[0])
            self.delayed_npc_packet_pending = False
            self.delayed_npc_packet_injected = True
        if (path.startswith("/solo/acceptance/v1/operation/") and path.endswith("/cancel")
                and self.mode == "pending_action_cancel_timeout"):
            operation_id = path.split("/")[-2]
            self.cancelled_operation_ids.append(operation_id)
            operation = self.operations[operation_id]
            operation.update(status="CANCELLED", cancel_requested=True,
                             result={"observed_outcome": "action_cancelled_before_dispatch",
                                     "native_dispatch_started": False})
            receipt = self.response(operation)
            # Cancellation has completed; only its HTTP receipt remains unknown.
            time.sleep(0.75)
            return 200, envelope(receipt)
        if path != "/solo/acceptance/v1/actor/act":
            return super().default_response(request)

        request_id, data = request["body"]["requestId"], request["body"]["data"]
        action = data["action"]
        if action not in {"npc_center_open", "npc_cancel"}:
            return 400, {"sensitive": "this fixture only accepts NPC center actions"}
        if request_id in self.npc_requests:
            if self.mode == "duplicate_open_packet" and action == "npc_center_open":
                self.append_packet(self.dialogue_packets[-1])
            elif self.mode == "delayed_duplicate_open_packet" and action == "npc_center_open":
                self.delayed_npc_packet_pending = True
            return 200, envelope(self.response(self.operations[self.npc_requests[request_id]]))
        session = self.sessions[data["session_id"]]
        actor = session["actor"]
        dialogue = actor["dialogue"]
        before = self.packet_events[-1]["sequence"]
        if self.mode == "pending_action_cancel_timeout":
            self.npc_actions.append({"request_id": request_id, "action": action,
                                     "data": deepcopy(data), "at": time.monotonic()})
            operation = self.new_operation("ACTION", session, {}, status="RUNNING")
            operation["action"] = action
            self.npc_requests[request_id] = operation["operation_id"]
            return 200, envelope(self.response(operation, queued=True))
        if action == "npc_cancel":
            version = data["parameters"]["dialogue_version"]
            if not dialogue["active"] or version != dialogue["dialogue_version"]:
                self.stale_cancel_rejections.append({"version": version, "active": dialogue["active"],
                                                     "current": dialogue.get("dialogue_version")})
                return 410, {"sensitive": "stale NPC answer must not reach the active conversation"}
            self.cancelled_dialogue_versions.append(version)
            if self.mode != "cancel_keeps_cm":
                actor["dialogue"] = self.inactive_dialogue()
            self.append_packet({"opcode": 0x9999, "length": 2, "event_type": "packet_summary",
                                "decode_status": "unsupported", "reason": "unsupported_opcode"})
            result = {"observed_outcome": "dialogue_cancelled", "native_dispatch_started": True,
                      "dialogue_version": version, "packet_sequence": version,
                      "packet_cursor_before": before, "packet_cursor_after": self.packet_events[-1]["sequence"],
                      "npc_template_id": 9900001, "message_type": 4,
                      "active": False, "native_dialogue_cleanup_verified": True}
            if self.mode == "missing_cancel_cleanup":
                result.pop("native_dialogue_cleanup_verified")
            elif self.mode == "meso_changed":
                actor["meso"] += 1
            elif self.mode == "position_changed":
                actor["position"]["x"] += 10
            elif self.mode == "ticket_changed":
                actor["tickets_summary"]["maple_point"] += 1
        else:
            if dialogue["active"]:
                return 409, {"sensitive": "an existing native conversation must not be overwritten"}
            now = time.monotonic()
            wait = 1.278 if self.mode == "cached_clock" else 0.502
            if self.last_center_open_at is not None and now - self.last_center_open_at < wait:
                return 409, {"sensitive": "native click interval was not respected"}
            self.last_center_open_at = now
            packet = {"opcode": 304, "length": 40, "event_type": "npc_dialogue", "decode_status": "decoded",
                      "information_level": "player_visible", "speaker_type": 4, "npc_template_id": 9900001,
                      "message_type": 4, "speaker": 0, "text": "北斗中心 #L3#查看说明#l", "text_truncated": False}
            if self.mode == "wrong_npc":
                packet["npc_template_id"] = 1012000
            elif self.mode == "wrong_message_type":
                packet["message_type"] = 0
            elif self.mode == "truncated_menu":
                packet["text_truncated"] = True
            version = self.append_packet(packet)
            self.dialogue_packets.append(deepcopy(self.packet_events[-1]))
            self.open_dialogue_versions.append(version)
            actor["dialogue"] = {"active": self.mode != "inactive_cm", "dialogue_version": version,
                                 "packet_sequence": version, "entry_type": "native_ui_center",
                                 "npc_template_id": 9900001, "message_type": 4, "supported": True}
            result = {"observed_outcome": "center_dialogue_opened", "native_dispatch_started": True,
                      "dialogue_version": version, "packet_sequence": version,
                      "npc_template_id": 9900001, "message_type": 4, "native_dialogue_cleanup_verified": False,
                      "packet_cursor_before": before, "packet_cursor_after": version, "active": True}
        self.npc_actions.append({"request_id": request_id, "action": action, "data": deepcopy(data), "at": time.monotonic()})
        operation = self.new_operation("ACTION", session, result)
        operation["action"] = action
        self.npc_requests[request_id] = operation["operation_id"]
        receipt = self.response(operation, queued=True)
        if self.mode == "action_timeout":
            time.sleep(0.3)
        return 200, envelope(receipt)


class NpcCenterSmokeTests(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, mode: str = "normal", *, timeout: float = 3):
        stub = NpcCenterStub(mode).start()
        try:
            root = Path(__file__).resolve().parents[1] / ".test-tmp"
            root.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as directory:
                folder = Path(directory)
                # Leave healthy close/observe requests room under concurrent
                # builds; only the deliberate cancellation receipt exceeds it.
                http_timeout = (0.5 if mode == "pending_action_cancel_timeout" else
                                0.1 if mode in {"action_timeout", "close_timeout"} else 2)
                config = stub.write_config(folder / "private.json", timeout=http_timeout)
                output = folder / "npc-center-report.json"
                report = await run_npc_center_smoke(config, profile_id="primary", expected_base_url=stub.base_url,
                                                   output=output, operation_timeout=timeout, poll_interval=0.01)
                self.assertEqual(report, json.loads(output.read_text()))
                self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return stub, report
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_official_stdio_opens_replays_cancels_rejects_stale_versions_and_closes_native_cm(self):
        stub, report = await self.run_fixture()
        self.assertTrue(report["passed"], report)
        self.assertTrue(report["cleanup"]["verified"])
        self.assertFalse(report["game_client_verified"])
        self.assertFalse(report["model_called"])
        self.assertEqual(3, len(stub.open_dialogue_versions))
        self.assertEqual(3, len(stub.dialogue_packets))
        self.assertEqual(sorted(set(stub.open_dialogue_versions)), stub.open_dialogue_versions)
        self.assertEqual(stub.open_dialogue_versions[:2], stub.cancelled_dialogue_versions)
        self.assertTrue(any(row["active"] for row in stub.stale_cancel_rejections))
        self.assertTrue(any(not row["active"] for row in stub.stale_cancel_rejections))
        self.assertEqual([True, False], stub.closed_with_active_dialogue)
        self.assertEqual(2, stub.creations)
        self.assertEqual(2, len(stub.close_requests))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        self.assertNotIn(PASSWORD, json.dumps(report))
        self.assertNotIn(TOKEN, json.dumps(report))

    async def test_cached_clock_requires_the_full_normal_click_interval_without_retrying_failed_opens(self):
        stub, report = await self.run_fixture("cached_clock")
        self.assertTrue(report["passed"], report)
        opens = [row for row in stub.npc_actions if row["action"] == "npc_center_open"]
        self.assertEqual(3, len(opens))
        self.assertTrue(all(later["at"] - earlier["at"] >= 1.278 for earlier, later in zip(opens, opens[1:])))
        self.assertEqual(3, len(stub.dialogue_packets))

    async def test_missing_npc_capabilities_or_decoder_are_rejected_before_login(self):
        for mode in ["missing_npc_capability", "missing_npc_action", "missing_npc_decoder"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertEqual(0, stub.creations)
                self.assertEqual([], stub.npc_actions)
                self.assertTrue(report["cleanup"]["verified"])

    async def test_wrong_or_truncated_npc_event_or_inactive_cm_cannot_count_as_a_center_menu(self):
        for mode in ["wrong_npc", "wrong_message_type", "truncated_menu", "inactive_cm"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.open_dialogue_versions))
                self.assertEqual(1, len(stub.close_requests))

    async def test_duplicate_open_that_emits_another_npc_packet_is_detected(self):
        stub, report = await self.run_fixture("duplicate_open_packet")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(1, len(stub.open_dialogue_versions))
        self.assertEqual(1, len(stub.close_requests))

    async def test_delayed_duplicate_npc_packet_after_cancel_cannot_hide_in_an_advanced_cursor(self):
        stub, report = await self.run_fixture("delayed_duplicate_open_packet")
        self.assertTrue(stub.delayed_npc_packet_injected, "the delayed packet must actually reach history")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(3, len(stub.open_dialogue_versions))
        self.assertEqual(1, len(stub.close_requests))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        self.assertTrue(all(set(row["data"]["parameters"]) <= {"dialogue_version"} for row in stub.npc_actions))

    async def test_cancel_requires_both_native_cleanup_evidence_and_inactive_current_dialogue(self):
        for mode in ["cancel_keeps_cm", "missing_cancel_cleanup"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))

    async def test_gameplay_state_must_not_change_while_only_opening_and_cancelling_the_center(self):
        for mode in ["meso_changed", "position_changed", "ticket_changed"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))

    async def test_actor_close_must_explicitly_verify_native_dialogue_cleanup(self):
        stub, report = await self.run_fixture("bad_close_cleanup")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        self.assertEqual(1, len(stub.close_requests))

    async def test_unknown_npc_write_is_not_replayed_and_our_known_actor_is_normally_closed(self):
        stub, report = await self.run_fixture("action_timeout")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        actions = [request for request in stub.requests if request["path"].endswith("/actor/act")]
        self.assertEqual(1, len(actions))
        self.assertEqual(actions[0]["body"]["requestId"], report["recovery"]["unknown_action_write"]["request_id"])
        self.assertEqual(1, len(stub.close_requests))

    async def test_unknown_actor_close_keeps_its_recovery_id_without_false_cleanup_success(self):
        stub, report = await self.run_fixture("close_timeout")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        closes = [request for request in stub.requests if request["path"].endswith("/actor/close")]
        self.assertEqual(1, len(closes))
        self.assertEqual(closes[0]["body"]["requestId"], report["recovery"]["unknown_write"]["request_id"])

    async def test_unknown_pending_action_cancel_still_closes_known_actor_and_keeps_cancel_recovery_id(self):
        stub, report = await self.run_fixture("pending_action_cancel_timeout", timeout=0.6)
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        cancellations = [request for request in stub.requests if request["path"].endswith("/cancel")]
        self.assertEqual(1, len(cancellations))
        self.assertEqual(1, len(stub.cancelled_operation_ids))
        self.assertEqual(1, len(stub.close_requests))
        recovery = report["recovery"]["unknown_action_write"]
        self.assertEqual("operation_cancel", recovery["tool"])
        self.assertEqual(cancellations[0]["body"]["requestId"], recovery["request_id"])
        self.assertEqual([], stub.dialogue_packets)
        self.assertEqual([False], stub.closed_with_active_dialogue)
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        self.assertEqual(1, len(stub.npc_actions))
        self.assertEqual({}, stub.npc_actions[0]["data"]["parameters"])

    async def test_existing_output_is_not_overwritten_and_explicit_target_is_checked_before_http(self):
        stub = NpcCenterStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json")
                with self.assertRaisesRegex(ActorSmokeFailure, "EXPLICIT_TARGET_MISMATCH"):
                    await run_npc_center_smoke(config, profile_id="primary", expected_base_url="http://127.0.0.1:1")
                output = folder / "retain.json"
                previous = b'{"passed":false,"request_id":"retain-npc-recovery"}\n'
                output.write_bytes(previous)
                with self.assertRaisesRegex(ActorSmokeFailure, "EVIDENCE_OUTPUT_ALREADY_EXISTS"):
                    await run_npc_center_smoke(config, profile_id="primary", expected_base_url=stub.base_url, output=output)
                self.assertEqual(previous, output.read_bytes())
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)
