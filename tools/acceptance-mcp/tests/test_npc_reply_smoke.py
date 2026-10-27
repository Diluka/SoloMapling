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

from solo_acceptance_mcp.actor_smoke import ActorSmokeFailure, checkpoint
from solo_acceptance_mcp.npc_reply_smoke import run_npc_reply_smoke
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_npc_center_smoke import NpcCenterStub


REPLY_ACTIONS = ["chat", "npc_center_open", "npc_cancel", "npc_reply"]
CENTER_OFFERS = [3, 69, 70, 71, 4, 2, 0, 1, 72]


class NpcReplyStub(NpcCenterStub):
    """Local HTTP NPC transitions; no native game, database, script or menu executes."""

    def __init__(self, mode: str = "normal") -> None:
        self.reply_dispatches: list[dict] = []
        self.reply_rejections: list[dict] = []
        self.consumed_versions: set[int] = set()
        self.named_dialogue_closes: list[str] = []
        self.reply_packets: list[dict] = []
        super().__init__(mode)

    @staticmethod
    def options(path: str) -> dict:
        if path == "center":
            return {"kinds": ["select"], "offered_selections": list(CENTER_OFFERS),
                    "allowed_selections": [0, 71], "complete": True, "consumed": False}
        if path == "transport_category":
            return {"kinds": ["select"], "offered_selections": [0, 1, 2, 3],
                    "allowed_selections": [2], "complete": True, "consumed": False}
        if path == "welfare":
            return {"kinds": ["decline"], "offered_selections": [],
                    "allowed_selections": [], "complete": True, "consumed": False}
        return {"kinds": [], "offered_selections": [], "allowed_selections": [],
                "complete": False, "consumed": False}

    @staticmethod
    def inactive_dialogue() -> dict:
        value = NpcCenterStub.inactive_dialogue()
        value.update(path="unsupported", reply_options=NpcReplyStub.options("unsupported"))
        return value

    def emit_dialogue(self, actor: dict, path: str) -> int:
        if path == "center":
            text = "北斗中心 " + " ".join("#L" + str(value) + "#选项#l" for value in CENTER_OFFERS)
        elif path == "transport_category":
            text = "选择传送分类 " + " ".join("#L" + str(value) + "#分类#l" for value in range(4))
        elif path == "transport_town":
            text = ("#L1#射手村#l " + "城镇菜单正文" * 250)[:1024]
        else:
            text = "新人福利：是否领取礼包？本轮只验证拒绝。"
        message_type = 12 if path == "welfare" else 4
        packet = {"opcode": 304, "length": 11 + len(text.encode("gbk")), "event_type": "npc_dialogue",
                  "decode_status": "decoded", "information_level": "player_visible", "speaker_type": 4,
                  "npc_template_id": 9900001, "message_type": message_type, "speaker": 0,
                  "text": text, "text_truncated": path == "transport_town"}
        if self.mode == "wrong_welfare_type" and path == "welfare":
            packet["message_type"] = 4
        elif self.mode == "welfare_text_truncated" and path == "welfare":
            packet["text_truncated"] = True
        version = self.append_packet(packet)
        self.reply_packets.append(deepcopy(self.packet_events[-1]))
        actor["dialogue"] = {"dialogue_version": version, "active": True, "supported": True,
                             "entry_type": "native_ui_center", "npc_template_id": 9900001,
                             "message_type": message_type, "packet_sequence": version, "path": path,
                             "reply_options": self.options(path)}
        if self.mode == "bad_offered_selections" and path == "center":
            actor["dialogue"]["reply_options"]["offered_selections"] = [0, 71, 999]
        elif self.mode == "town_claims_complete" and path == "transport_town":
            actor["dialogue"]["reply_options"] = {"kinds": ["select"], "offered_selections": [1],
                                                   "allowed_selections": [1], "complete": True, "consumed": False}
        return version

    def action_receipt(self, request_id: str, data: dict, result: dict, *, status: str = "SUCCEEDED") -> tuple[int, object]:
        session = self.sessions[data["session_id"]]
        operation = self.new_operation("ACTION", session, result, status=status)
        operation["action"] = data["action"]
        self.npc_requests[request_id] = operation["operation_id"]
        receipt = self.response(operation, queued=True)
        if self.mode == "reply_timeout" and data["action"] == "npc_reply":
            time.sleep(0.3)
        return 200, envelope(receipt)

    def rejected_reply(self, request_id: str, data: dict, dialogue: dict, status: int, reason: str) -> tuple[int, object]:
        self.reply_rejections.append({"request_id": request_id, "data": deepcopy(data), "dialogue": deepcopy(dialogue),
                                       "http_status": status, "reason": reason})
        if self.mode == "rejected_selection_accepted":
            version = dialogue.get("dialogue_version") or 1
            result = {"observed_outcome": "dialogue_replied", "native_dispatch_started": True,
                      "dialogue_version": version, "packet_sequence": version,
                      "packet_cursor_before": self.packet_events[-1]["sequence"],
                      "packet_cursor_after": self.packet_events[-1]["sequence"],
                      "active": dialogue["active"], "native_dialogue_cleanup_verified": False,
                      "kind": data["parameters"]["kind"], "selection": data["parameters"].get("selection"),
                      "reply_version_consumed": True, "dialogue_path": dialogue["path"]}
            return self.action_receipt(request_id, data, result)
        return status, {"sensitive": "fixture denied reply without exposing the script or request body"}

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            status, payload = super().default_response(request)
            caps = payload["data"]
            caps["actions"] = list(REPLY_ACTIONS)
            control = caps["actor_control"]
            control["actions"] = list(REPLY_ACTIONS)
            control["npc_dialogue"].update(message_types=[4, 12], reply_kinds=["select", "decline"],
                                           requires_complete_offers=True, requires_current_offered_selection=True,
                                           maximum_offered_selections=128)
            for decoder in control["packet_observation"]["decoded_packets"]:
                if decoder["event_type"] == "npc_dialogue":
                    decoder["message_types"] = [4, 12]
            if self.mode == "missing_reply_action":
                caps["actions"].remove("npc_reply")
                control["actions"].remove("npc_reply")
            elif self.mode == "missing_reply_offer_guard":
                control["npc_dialogue"].pop("requires_complete_offers")
            elif self.mode == "missing_type12_decoder":
                next(row for row in control["packet_observation"]["decoded_packets"]
                     if row["event_type"] == "npc_dialogue")["message_types"] = [4]
            return status, payload
        if path == "/solo/acceptance/v1/actor/close":
            actor = self.sessions[request["body"]["data"]["session_id"]]["actor"]
            if actor is not None and actor["dialogue"]["active"]:
                self.named_dialogue_closes.append(actor["dialogue"]["path"])
            return super().default_response(request)
        if path != "/solo/acceptance/v1/actor/act":
            return super().default_response(request)

        request_id, data = request["body"]["requestId"], request["body"]["data"]
        action, params = data["action"], data["parameters"]
        if request_id in self.npc_requests:
            if self.mode == "duplicate_reply_packet" and action == "npc_reply":
                self.append_packet(self.reply_packets[-1])
            return 200, envelope(self.response(self.operations[self.npc_requests[request_id]]))
        session = self.sessions[data["session_id"]]
        actor, before = session["actor"], self.packet_events[-1]["sequence"]
        dialogue = actor["dialogue"]
        if action == "npc_center_open":
            if dialogue["active"]:
                return 409, {"sensitive": "an existing native conversation must stay intact"}
            now = time.monotonic()
            if self.last_center_open_at is not None and now - self.last_center_open_at < 0.502:
                return 409, {"sensitive": "normal NPC click interval was not respected"}
            self.last_center_open_at = now
            version = self.emit_dialogue(actor, "center")
            self.open_dialogue_versions.append(version)
            result = {"observed_outcome": "center_dialogue_opened", "native_dispatch_started": True,
                      "dialogue_version": version, "packet_sequence": version, "npc_template_id": 9900001,
                      "message_type": 4, "packet_cursor_before": before, "packet_cursor_after": version,
                      "active": True, "native_dialogue_cleanup_verified": False}
        elif action == "npc_reply":
            version = params["dialogue_version"]
            if version in self.consumed_versions or version != dialogue["dialogue_version"]:
                return self.rejected_reply(request_id, data, dialogue, 410, "stale_dialogue")
            if not dialogue["active"]:
                return self.rejected_reply(request_id, data, dialogue, 409, "dialogue_not_active")
            options = dialogue["reply_options"]
            kind, selection = params["kind"], params.get("selection")
            if kind == "select":
                if not options["complete"]:
                    return self.rejected_reply(request_id, data, dialogue, 409, "menu_incomplete")
                if selection not in options["offered_selections"]:
                    return self.rejected_reply(request_id, data, dialogue, 409, "npc_selection_not_offered")
                if selection not in options["allowed_selections"]:
                    return self.rejected_reply(request_id, data, dialogue, 409, "path_not_supported")
            elif kind not in options["kinds"]:
                return self.rejected_reply(request_id, data, dialogue, 409, "path_not_supported")
            self.consumed_versions.add(version)
            dialogue["reply_options"]["consumed"] = True
            old_path = dialogue["path"]
            if kind == "decline":
                successor = self.append_packet({"opcode": 304, "length": 14, "event_type": "packet_summary",
                                                "decode_status": "unsupported", "reason": "unsupported_npc_message_type"})
                actor["dialogue"] = self.inactive_dialogue()
                next_path, outcome, active, cleanup = "closed", "dialogue_declined", False, True
            else:
                next_path = {("center", 0): "welfare", ("center", 71): "transport_category",
                             ("transport_category", 2): "transport_town"}[(old_path, selection)]
                successor = self.emit_dialogue(actor, next_path)
                outcome, active, cleanup = "dialogue_replied", True, False
            self.reply_dispatches.append({"request_id": request_id, "source_version": version, "source_path": old_path,
                                           "kind": kind, "selection": selection, "successor": successor, "path": next_path})
            result = {"observed_outcome": outcome, "native_dispatch_started": True, "dialogue_version": successor,
                      "packet_sequence": successor, "npc_template_id": 9900001,
                      "message_type": actor["dialogue"].get("message_type"), "active": active,
                      "native_dialogue_cleanup_verified": cleanup, "packet_cursor_before": before,
                      "packet_cursor_after": self.packet_events[-1]["sequence"], "kind": kind, "selection": selection,
                      "reply_version_consumed": True, "dialogue_path": next_path}
            if self.mode == "reply_version_not_consumed":
                result["reply_version_consumed"] = False
            elif self.mode == "wrong_successor_path":
                result["dialogue_path"] = "unsupported"
            elif self.mode == "decline_keeps_cm" and kind == "decline":
                self.emit_dialogue(actor, "welfare")
            elif self.mode == "reward_on_decline" and kind == "decline":
                actor["meso"] += 100
            elif self.mode == "ticket_on_select":
                actor["tickets_summary"]["nx_credit"] += 100
        elif action == "npc_cancel":
            version = params["dialogue_version"]
            if version != dialogue["dialogue_version"] or not dialogue["active"]:
                return 410, {"sensitive": "stale NPC cancel cannot affect another conversation"}
            self.cancelled_dialogue_versions.append(version)
            actor["dialogue"] = self.inactive_dialogue()
            self.append_packet({"opcode": 0x9999, "length": 2, "event_type": "packet_summary",
                                "decode_status": "unsupported", "reason": "unsupported_opcode"})
            result = {"observed_outcome": "dialogue_cancelled", "native_dispatch_started": True,
                      "dialogue_version": version, "packet_sequence": version, "npc_template_id": 9900001,
                      "message_type": dialogue["message_type"], "active": False,
                      "native_dialogue_cleanup_verified": True, "packet_cursor_before": before,
                      "packet_cursor_after": self.packet_events[-1]["sequence"]}
        else:
            return 400, {"sensitive": "unimplemented fixture action"}
        self.npc_actions.append({"request_id": request_id, "action": action, "data": deepcopy(data), "at": time.monotonic()})
        return self.action_receipt(request_id, data, result)


class NpcReplySmokeTests(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, mode: str = "normal"):
        stub = NpcReplyStub(mode).start()
        try:
            root = Path(__file__).resolve().parents[1] / ".test-tmp"
            root.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json", timeout=0.1 if mode in {"reply_timeout", "close_timeout"} else 2)
                output = folder / "npc-reply-report.json"
                report = await run_npc_reply_smoke(config, profile_id="primary", expected_base_url=stub.base_url,
                                                  output=output, operation_timeout=3, poll_interval=0.01)
                self.assertEqual(report, json.loads(output.read_text()))
                self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return stub, report
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_official_stdio_completes_decline_cancel_truncated_town_and_named_cm_close_without_rewards(self):
        stub, report = await self.run_fixture()
        self.assertTrue(report["passed"], report)
        self.assertTrue(report["cleanup"]["verified"])
        self.assertFalse(report["game_client_verified"])
        self.assertFalse(report["model_called"])
        self.assertFalse(report["claim_marker_verified_via_cli"])
        self.assertTrue(any(row["kind"] == "decline" and row["source_path"] == "welfare" for row in stub.reply_dispatches))
        self.assertTrue(any(row["source_path"] == "transport_category" and row["selection"] == 2 for row in stub.reply_dispatches))
        self.assertTrue(any(path in {"welfare", "transport_category", "transport_town"} for path in stub.named_dialogue_closes))
        rejected_selections = {row["data"]["parameters"].get("selection") for row in stub.reply_rejections}
        self.assertTrue(set(range(61, 69)) | {999, 3, 1} <= rejected_selections)
        self.assertTrue(any(row["reason"] == "stale_dialogue" for row in stub.reply_rejections))
        self.assertTrue(any(row["reason"] == "menu_incomplete" for row in stub.reply_rejections))
        self.assertEqual(2, stub.creations)
        self.assertEqual(2, len(stub.close_requests))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        self.assertNotIn(PASSWORD, json.dumps(report))
        self.assertNotIn(TOKEN, json.dumps(report))

    async def test_missing_reply_capability_or_type12_decoder_stops_before_login(self):
        for mode in ["missing_reply_action", "missing_reply_offer_guard", "missing_type12_decoder"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertEqual(0, stub.creations)
                self.assertEqual([], stub.reply_dispatches)
                self.assertTrue(report["cleanup"]["verified"])

    async def test_bad_welfare_type_truncated_prompt_or_untrustworthy_offers_do_not_pass(self):
        for mode in ["wrong_welfare_type", "welfare_text_truncated", "bad_offered_selections", "town_claims_complete"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))

    async def test_reply_must_consume_source_version_and_report_the_actual_successor(self):
        for mode in ["reply_version_not_consumed", "wrong_successor_path"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.reply_dispatches))

    async def test_old_hidden_or_truncated_reply_acceptance_cannot_be_reported_as_rejection(self):
        stub, report = await self.run_fixture("rejected_selection_accepted")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertGreaterEqual(len(stub.reply_rejections), 1)
        self.assertEqual(1, len(stub.close_requests))

    async def test_same_request_replay_that_emits_another_npc_packet_is_detected(self):
        stub, report = await self.run_fixture("duplicate_reply_packet")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(1, len(stub.close_requests))

    async def test_decline_must_end_current_cm_and_no_reply_may_change_currency(self):
        for mode in ["decline_keeps_cm", "reward_on_decline", "ticket_on_select"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))

    async def test_unknown_reply_result_is_not_replayed_and_known_actor_is_normally_closed(self):
        stub, report = await self.run_fixture("reply_timeout")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        replies = [request for request in stub.requests if request["path"].endswith("/actor/act")
                   and request["body"]["data"]["action"] == "npc_reply"]
        accepted = [request for request in replies if request["body"]["requestId"] in stub.npc_requests]
        self.assertEqual(1, len(accepted), "the deliberate rejection probes are separate from the unknown accepted write")
        self.assertEqual(1, len(stub.reply_dispatches))
        self.assertEqual(accepted[0]["body"]["requestId"], report["recovery"]["unknown_action_write"]["request_id"])
        request_ids = [request["body"]["requestId"] for request in replies]
        self.assertEqual(1, request_ids.count(accepted[0]["body"]["requestId"]))
        self.assertEqual(1, len(stub.close_requests))

    async def test_unknown_close_preserves_the_original_close_id_and_cannot_claim_cleanup(self):
        stub, report = await self.run_fixture("close_timeout")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        closes = [request for request in stub.requests if request["path"].endswith("/actor/close")]
        self.assertEqual(1, len(closes))
        self.assertEqual(closes[0]["body"]["requestId"], report["recovery"]["unknown_write"]["request_id"])

    async def test_evidence_disk_failure_stops_new_business_writes_but_still_closes_the_known_actor(self):
        stub = NpcReplyStub().start()
        try:
            root = Path(__file__).resolve().parents[1] / ".test-tmp"
            root.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json")
                output = folder / "disk-failure.json"

                def unavailable_after_first_center(path, report):
                    if report.get("npc_dialogues"):
                        raise OSError("simulated evidence disk failure after opening the first center")
                    checkpoint(path, report)

                with patch("solo_acceptance_mcp.actor_smoke.checkpoint", unavailable_after_first_center):
                    report = await run_npc_reply_smoke(config, profile_id="primary", expected_base_url=stub.base_url,
                                                      output=output, operation_timeout=3, poll_interval=0.01)
                self.assertFalse(report["passed"])
                self.assertEqual("EVIDENCE_UNAVAILABLE_BEFORE_WRITE", report["failure"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.open_dialogue_versions))
                self.assertEqual([], stub.reply_dispatches)
                self.assertEqual([], stub.reply_rejections)
                actions = [request for request in stub.requests if request["path"].endswith("/actor/act")]
                self.assertEqual(1, len(actions))
                self.assertEqual("npc_center_open", actions[0]["body"]["data"]["action"])
                self.assertEqual(1, len(stub.close_requests))
                self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
                journal = json.loads(output.read_text())
                self.assertEqual(0o600, output.stat().st_mode & 0o777)
                self.assertEqual(next(iter(stub.sessions)), journal["recovery"]["active_session"]["session_id"])
                self.assertIn(actions[0]["body"]["requestId"], [entry["request_id"] for entry in journal["requests"]])
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_target_and_existing_recovery_output_are_checked_before_any_http(self):
        stub = NpcReplyStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json")
                with self.assertRaisesRegex(ActorSmokeFailure, "EXPLICIT_TARGET_MISMATCH"):
                    await run_npc_reply_smoke(config, profile_id="primary", expected_base_url="http://127.0.0.1:1")
                output = folder / "retain.json"
                previous = b'{"passed":false,"request_id":"retain-npc-reply"}\n'
                output.write_bytes(previous)
                with self.assertRaisesRegex(ActorSmokeFailure, "EVIDENCE_OUTPUT_ALREADY_EXISTS"):
                    await run_npc_reply_smoke(config, profile_id="primary", expected_base_url=stub.base_url, output=output)
                self.assertEqual(previous, output.read_bytes())
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)
