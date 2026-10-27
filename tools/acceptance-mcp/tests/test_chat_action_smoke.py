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
from solo_acceptance_mcp.chat_action_smoke import run_chat_action_smoke
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_actor_events_smoke import ActorEventsStub


ACTION_QUEUE_CAPABILITIES = {"maximum_pending_actions_per_session": 7, "actor_executor_queue_capacity": 8}
CHAT_TOOLS = {"game_capabilities", "game_observe", "actor_open", "actor_close",
              "operation_get", "operation_cancel", "game_act"}


class ChatActionStub(ActorEventsStub):
    """Queues action receipts separately from the independently observed packet window."""

    def __init__(self, mode: str = "normal") -> None:
        self.action_requests: dict[str, str] = {}
        self.action_parameters: dict[str, dict] = {}
        self.accepted_actions: list[dict] = []
        self.broadcasts: list[dict] = []
        self.action_cancellations: list[dict] = []
        self.session_packets: dict[str, list[dict]] = {}
        self.delayed_duplicate: tuple[str, float] | None = None
        self.delayed_duplicate_injected = False
        self.cancelled_operation_ids: list[str] = []
        super().__init__(mode)
        self.initial_packet_events = deepcopy(self.packet_events)

    def broadcast(self, request_id: str, data: dict) -> tuple[int, int]:
        session = self.sessions[data["session_id"]]
        before = self.packet_events[-1]["sequence"]
        text = data["parameters"]["text"]
        event = {
            "sequence": before + 1, "received_at": datetime.now(timezone.utc).isoformat(),
            "opcode": 162, "length": 10 + len(text.encode("gbk")), "event_type": "chat_text",
            "decode_status": "decoded", "information_level": "player_visible",
            "sender_character_id": session["actor"]["character_id"], "gm": False,
            "text": text, "show": 0, "text_truncated": False,
        }
        if self.mode == "system_notice_instead_of_echo":
            event.update(opcode=68, event_type="server_message", message_type=4, ticker_enabled=True)
            for key in ["sender_character_id", "gm", "show"]:
                event.pop(key)
        elif self.mode == "wrong_sender":
            event["sender_character_id"] += 1000
        elif self.mode == "wrong_text":
            event["text"] += "（另一条消息）"
        elif self.mode == "old_sequence":
            event["sequence"] = before
        self.packet_events.append(event)
        self.broadcasts.append({"request_id": request_id, "session_id": data["session_id"],
                                "text": text, "sequence": event["sequence"], "at": time.monotonic()})
        return before, event["sequence"]

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            status, payload = super().default_response(request)
            caps = payload["data"]
            caps.update(enabled_tools=sorted(CHAT_TOOLS), actions=["chat"])
            caps["actor_control"].update(ACTION_QUEUE_CAPABILITIES)
            caps["actor_control"]["actions"] = ["chat"]
            if self.mode == "chat_not_enabled":
                caps["enabled_tools"].remove("game_act")
            elif self.mode == "missing_chat_action":
                caps["actions"] = []
            elif self.mode == "wrong_action_capacity":
                for key in ACTION_QUEUE_CAPABILITIES:
                    caps["actor_control"][key] += 1
            return status, payload
        if path == "/solo/acceptance/v1/actor/open":
            prior = self.creations
            response = super().default_response(request)
            if self.creations > prior:
                self.packet_events = deepcopy(self.initial_packet_events)
                session_id = next(reversed(self.sessions))
                self.session_packets[session_id] = self.packet_events
            return response
        if path == "/solo/acceptance/v1/actor/observe" and self.delayed_duplicate is not None:
            request_id, due = self.delayed_duplicate
            if time.monotonic() >= due:
                self.broadcast(request_id, self.action_parameters[request_id])
                self.delayed_duplicate = None
                self.delayed_duplicate_injected = True
        if path == "/solo/acceptance/v1/actor/close" and self.mode == "action_and_close_timeout":
            response = super().default_response(request)
            time.sleep(0.3)
            return response
        if path == "/solo/acceptance/v1/actor/act":
            request_id, data = request["body"]["requestId"], request["body"]["data"]
            if request_id in self.action_requests:
                if self.mode == "duplicate_broadcast":
                    self.broadcast(request_id, self.action_parameters[request_id])
                elif self.mode == "delayed_duplicate_broadcast":
                    self.delayed_duplicate = (request_id, time.monotonic() + 0.05)
                return 200, envelope(self.response(self.operations[self.action_requests[request_id]]))
            session = self.sessions[data["session_id"]]
            if self.mode == "context_changed_at_submission":
                return 409, {"sensitive": "never return original chat text or internal scene details"}
            self.accepted_actions.append({"request_id": request_id, "data": deepcopy(data), "at": time.monotonic()})
            self.action_parameters[request_id] = deepcopy(data)
            if self.mode == "pending_action":
                result, operation_status = {}, "RUNNING"
            else:
                before, sequence = self.broadcast(request_id, data)
                result = {"observed_outcome": "native_chat_broadcast", "native_dispatch_started": True,
                          "echo_matched": True, "sender_character_id": session["actor"]["character_id"],
                          "echo_sequence": sequence, "packet_cursor_before": before,
                          "packet_cursor_after": self.packet_events[-1]["sequence"], "cancel_too_late": False}
                if self.mode == "missing_native_dispatch":
                    result.pop("native_dispatch_started")
                elif self.mode == "unmatched_native_result":
                    result["echo_matched"] = False
                elif self.mode == "wrong_native_sender":
                    result["sender_character_id"] += 1000
                elif self.mode == "leaky_operation_result":
                    result["text"] = data["parameters"]["text"]
                operation_status = "SUCCEEDED"
            operation = self.new_operation("ACTION", session, result, status=operation_status)
            operation["action"] = "chat"
            self.action_requests[request_id] = operation["operation_id"]
            receipt = self.response(operation, queued=True)
            if self.mode == "wrong_action_receipt":
                receipt.update(operation_id="operation-foreign-actor", session_id="session-foreign-actor",
                               session_generation=session["session_generation"] + 100)
                receipt["session"].update(session_id=receipt["session_id"],
                                          session_generation=receipt["session_generation"])
            if self.mode in {"action_timeout", "action_and_close_timeout"}:
                # The write has already executed; only its HTTP result is delayed.
                # Cleanup may close the actor while this response is in flight.
                time.sleep(0.3)
            return 200, envelope(receipt)
        if path.startswith("/solo/acceptance/v1/operation/") and path.endswith("/cancel"):
            operation_id = path.split("/")[-2]
            self.cancelled_operation_ids.append(operation_id)
            if operation_id not in self.operations:
                return 404, {"sensitive": "foreign operation must not be touched"}
            operation = self.operations[operation_id]
            if operation["kind"] == "ACTION":
                session = self.sessions[operation["session_id"]]
                if operation["status"] == "RUNNING":
                    operation.update(status="CANCELLED", cancel_requested=True,
                                     result={"observed_outcome": "chat_cancelled_before_dispatch",
                                             "native_dispatch_started": False})
                self.action_cancellations.append({"operation_id": operation_id, "session_state": session["state"],
                                                  "actor_current": session["actor_current"]})
                return 200, envelope(self.response(operation))
        return super().default_response(request)


class ChatActionSmokeTests(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, mode: str = "normal", *, timeout: float = 2):
        stub = ChatActionStub(mode).start()
        try:
            root = Path(__file__).resolve().parents[1] / ".test-tmp"
            root.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json",
                                           timeout=0.1 if mode in {"action_timeout", "action_and_close_timeout"} else 2)
                output = folder / "chat-report.json"
                report = await run_chat_action_smoke(
                    config, profile_id="primary", expected_base_url=stub.base_url, output=output,
                    operation_timeout=timeout, poll_interval=0.01,
                )
                self.assertEqual(report, json.loads(output.read_text()))
                self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return stub, report
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_official_stdio_matches_two_chinese_echoes_and_replay_does_not_broadcast(self):
        stub, report = await self.run_fixture()
        self.assertTrue(report["passed"], report)
        self.assertTrue(report["cleanup"]["verified"])
        self.assertFalse(report["game_client_verified"])
        self.assertFalse(report["model_called"])
        self.assertEqual(2, len(stub.accepted_actions))
        self.assertEqual(2, len(stub.broadcasts))
        self.assertEqual(2, stub.creations)
        self.assertEqual(2, len(stub.close_requests))
        action_posts = [request for request in stub.requests if request["path"].endswith("/actor/act")]
        self.assertEqual(3, len(action_posts))
        self.assertEqual(action_posts[0]["body"], action_posts[1]["body"])
        self.assertGreaterEqual(stub.accepted_actions[1]["at"] - stub.accepted_actions[0]["at"], 0.25)
        texts = [action["data"]["parameters"]["text"] for action in stub.accepted_actions]
        self.assertNotEqual(*texts)
        self.assertTrue(all(any("\u4e00" <= char <= "\u9fff" for char in text) for text in texts))
        first_id = stub.accepted_actions[0]["data"]["session_id"]
        self.assertTrue(any(read["session_id"] == first_id and read["since_cursor"] >= 3 for read in stub.event_reads))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        for operation in stub.operations.values():
            if operation["kind"] == "ACTION":
                self.assertNotIn("text", operation["result"])
        self.assertNotIn(PASSWORD, json.dumps(report))
        self.assertNotIn(TOKEN, json.dumps(report))

    async def test_system_notice_wrong_sender_wrong_text_or_old_sequence_are_not_native_chat_echoes(self):
        for mode in ["system_notice_instead_of_echo", "wrong_sender", "wrong_text", "old_sequence"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.accepted_actions))
                self.assertEqual(1, len(stub.close_requests))

    async def test_incomplete_or_leaky_operation_results_cannot_count_as_a_verified_native_broadcast(self):
        for mode in ["missing_native_dispatch", "unmatched_native_result", "wrong_native_sender", "leaky_operation_result"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.accepted_actions))
                self.assertEqual(1, len(stub.close_requests))

    async def test_duplicate_request_that_broadcasts_again_is_detected_from_the_packet_window(self):
        stub, report = await self.run_fixture("duplicate_broadcast")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(1, len(stub.accepted_actions))
        self.assertEqual(2, len(stub.broadcasts))
        self.assertEqual(1, len(stub.close_requests))

    async def test_delayed_duplicate_broadcast_cannot_hide_behind_the_second_action_baseline(self):
        stub, report = await self.run_fixture("delayed_duplicate_broadcast")
        self.assertTrue(stub.delayed_duplicate_injected, "the delayed echo must actually reach the packet window")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertIn(len(stub.broadcasts), {2, 3})
        first_request = stub.accepted_actions[0]["request_id"]
        self.assertEqual(2, sum(row["request_id"] == first_request for row in stub.broadcasts))
        self.assertEqual(1, len(stub.close_requests))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))

    async def test_missing_action_capabilities_do_not_log_in_or_send_a_message(self):
        for mode in ["chat_not_enabled", "missing_chat_action", "wrong_action_capacity"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(0, stub.creations)
                self.assertEqual([], stub.accepted_actions)

    async def test_invalid_observed_scene_prevents_chat_and_definite_context_refusal_still_closes(self):
        for mode in ["wrong_scene", "context_changed_at_submission"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual([], stub.accepted_actions)
                self.assertEqual([], stub.broadcasts)
                self.assertEqual(1, len(stub.close_requests))

    async def test_unknown_action_write_preserves_recovery_id_and_normally_closes_without_blind_retry(self):
        stub, report = await self.run_fixture("action_timeout")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        action_posts = [request for request in stub.requests if request["path"].endswith("/actor/act")]
        self.assertEqual(1, len(action_posts))
        self.assertEqual(action_posts[0]["body"]["requestId"], report["recovery"]["unknown_write"]["request_id"])
        self.assertEqual(action_posts[0]["body"]["requestId"], report["recovery"]["unknown_action_write"]["request_id"])
        self.assertEqual(1, len(stub.close_requests))
        self.assertEqual([], stub.cancelled_operation_ids)
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))

    async def test_unknown_action_and_close_results_keep_both_recovery_ids_without_replaying_either(self):
        stub, report = await self.run_fixture("action_and_close_timeout")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        actions = [request for request in stub.requests if request["path"].endswith("/actor/act")]
        closes = [request for request in stub.requests if request["path"].endswith("/actor/close")]
        self.assertEqual(1, len(actions))
        self.assertEqual(1, len(closes))
        self.assertEqual(actions[0]["body"]["requestId"], report["recovery"]["unknown_action_write"]["request_id"])
        self.assertEqual(closes[0]["body"]["requestId"], report["recovery"]["unknown_write"]["request_id"])
        self.assertNotEqual(report["recovery"]["unknown_action_write"]["request_id"],
                            report["recovery"]["unknown_write"]["request_id"])
        self.assertEqual([], stub.cancelled_operation_ids)

    async def test_foreign_action_receipt_is_not_cancelled_and_our_known_actor_is_normally_closed(self):
        stub, report = await self.run_fixture("wrong_action_receipt")
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual([], stub.cancelled_operation_ids)
        actions = [request for request in stub.requests if request["path"].endswith("/actor/act")]
        self.assertEqual(1, len(actions))
        self.assertEqual(actions[0]["body"]["requestId"], report["recovery"]["unknown_action_write"]["request_id"])
        self.assertEqual(1, len(stub.close_requests))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))

    async def test_pending_action_is_cancelled_without_cancelling_the_actor_before_normal_close(self):
        stub, report = await self.run_fixture("pending_action", timeout=0.1)
        self.assertFalse(report["passed"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual([], stub.broadcasts)
        self.assertEqual(1, len(stub.action_cancellations))
        self.assertEqual("OPEN", stub.action_cancellations[0]["session_state"])
        self.assertTrue(stub.action_cancellations[0]["actor_current"])
        self.assertEqual(1, len(stub.close_requests))
        requests = [request["path"] for request in stub.requests]
        cancel_index = next(index for index, path in enumerate(requests) if path.endswith("/cancel"))
        self.assertLess(cancel_index, requests.index("/solo/acceptance/v1/actor/close"))

    async def test_existing_recovery_output_is_refused_before_any_http(self):
        stub = ChatActionStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json")
                output = folder / "retain-recovery.json"
                previous = b'{"passed":false,"recovery":{"unknown_write":{"request_id":"retain-chat-id"}}}\n'
                output.write_bytes(previous)
                with self.assertRaisesRegex(ActorSmokeFailure, "EVIDENCE_OUTPUT_ALREADY_EXISTS"):
                    await run_chat_action_smoke(config, profile_id="primary", expected_base_url=stub.base_url,
                                                output=output)
                self.assertEqual(previous, output.read_bytes())
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_explicit_target_mismatch_fails_before_any_http(self):
        stub = ChatActionStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                config = stub.write_config(Path(directory) / "private.json")
                with self.assertRaisesRegex(ActorSmokeFailure, "EXPLICIT_TARGET_MISMATCH"):
                    await run_chat_action_smoke(config, profile_id="primary", expected_base_url="http://127.0.0.1:1")
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)
