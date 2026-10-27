from __future__ import annotations

import asyncio
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlsplit

from solo_acceptance_mcp.actor_events_smoke import run_actor_events_smoke
from solo_acceptance_mcp.actor_smoke import ActorSmokeFailure
from solo_acceptance_mcp.bridge import BridgeError, MAX_LONG
from solo_acceptance_mcp.smoke import ACTOR_IMPLEMENTED_TOOLS
from tests.helpers import CAPABILITIES, PASSWORD, TOKEN, envelope
from tests.test_actor_smoke import ActorSmokeStub


PACKET_CAPABILITIES = {
    "capacity": 256,
    "maximum_text_chars": 1024,
    "cursor_scope": "session_packet_sequence",
    "decoded_packets": [
        {"event_type": "chat_text", "opcode": 162},
        {"event_type": "player_hint", "opcode": 214},
        {"event_type": "server_message", "opcode": 68, "message_types": list(range(8))},
    ],
}


class ActorEventsStub(ActorSmokeStub):
    """Deterministic packet window over the same real HTTP/stdio fixture boundary."""

    def __init__(self, mode: str = "normal") -> None:
        self.event_reads: list[dict] = []
        self.read_counts: dict[tuple[str, int], int] = {}
        self.event_actors: dict[str, dict] = {}
        received = datetime.now(timezone.utc).isoformat()
        text = "" if mode == "empty_ticker" else "本地桩公告：组队请互相照顾"
        self.packet_events = [
            {"sequence": 1, "received_at": received, "opcode": 14, "length": 6,
             "event_type": "packet_summary", "decode_status": "unsupported",
             "reason": "unsupported_opcode"},
            {"sequence": 2, "received_at": received, "opcode": 68,
             "length": 6 + len(text.encode("gbk")), "event_type": "server_message",
             "decode_status": "decoded", "information_level": "player_visible",
             "message_type": 4, "ticker_enabled": True, "text": text, "text_truncated": False},
            {"sequence": 3, "received_at": received, "opcode": 232, "length": 3,
             "event_type": "packet_summary", "decode_status": "unsupported",
             "reason": "unsupported_opcode"},
        ]
        if mode == "no_ticker":
            self.packet_events[1].update(message_type=0)
            self.packet_events[1].pop("ticker_enabled")
        elif mode == "false_ticker":
            self.packet_events[1]["ticker_enabled"] = False
        elif mode == "missing_ticker_flag":
            self.packet_events[1].pop("ticker_enabled")
        elif mode == "latest_regression":
            self.packet_events[0], self.packet_events[1] = self.packet_events[1], self.packet_events[0]
            for sequence, event in enumerate(self.packet_events, 1):
                event["sequence"] = sequence
        super().__init__(mode)

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            caps = deepcopy(CAPABILITIES)
            caps.update(implementation_round="R2a", enabled_tools=sorted(ACTOR_IMPLEMENTED_TOOLS),
                        scopes=["world", "scene", "actor"], event_stream=False,
                        actor_control={"packet_observation": deepcopy(PACKET_CAPABILITIES)})
            if self.mode == "missing_capability":
                caps["actor_control"].pop("packet_observation")
            elif self.mode == "missing_scope":
                caps["scopes"].remove("actor")
            elif self.mode == "wrong_cursor_scope":
                caps["actor_control"]["packet_observation"]["cursor_scope"] = "operation_lifecycle"
            return 200, envelope(caps)
        if path != "/solo/acceptance/v1/actor/observe":
            return super().default_response(request)

        data = request["body"]["data"]
        self.event_reads.append(deepcopy(data))
        session_id, cursor, limit = data["session_id"], data["since_cursor"], data["limit"]
        session = self.sessions.get(session_id)
        if session is None:
            return 404, {"sensitive": "unknown actor account must not be exposed"}
        if data["expected_generation"] != session["session_generation"]:
            return 410, {"sensitive": "stale actor generation"}
        if session["state"] != "OPEN":
            accepts_closed = (self.mode == "closed_accepted"
                              or self.mode == "old_after_reopen_accepted" and self.creations > 1)
            if not accepts_closed:
                return 410, {"sensitive": "closed actor packet history must not be exposed"}
        else:
            self.event_actors[session_id] = deepcopy(session["actor"])

        latest = self.packet_events[-1]["sequence"]
        if self.mode == "latest_regression" and cursor > 0:
            latest = 1
        if (self.mode == "append_on_repeat" and cursor == 0 and self.read_counts.get((session_id, cursor)) == 1
                or self.mode == "packet_before_future" and cursor > latest):
            event = deepcopy(self.packet_events[-1])
            event["sequence"] = self.packet_events[-1]["sequence"] + 1
            self.packet_events.append(event)
            latest = event["sequence"]
        if cursor > latest and self.mode != "future_accepted":
            return 400, {"sensitive": "packet cursor ahead must stay a fixed public error"}
        read_key = session_id, cursor
        self.read_counts[read_key] = self.read_counts.get(read_key, 0) + 1
        effective_cursor = cursor
        if self.mode == "consuming_reads" and self.read_counts[read_key] > 1 and cursor < latest:
            effective_cursor += 1
        items = deepcopy([event for event in self.packet_events if effective_cursor < event["sequence"] <= latest][:limit])
        next_cursor = items[-1]["sequence"] if items else cursor
        if self.mode == "jumping_cursor" and cursor == 0 and len(items) < latest:
            next_cursor = latest
        actor = deepcopy(self.event_actors[session_id])
        snapshot = {
            "schema_version": 1, "captured_at": datetime.now(timezone.utc).isoformat(), "scope": "actor",
            "runtime_generation": "generation-fixture-1", "information_level": "server_diagnostic",
            "snapshot_consistency": "best_effort", "session_id": session_id,
            "session_generation": session["session_generation"], "profile_id": "primary", "actor": actor,
            "events": {"items": items, "next_cursor": next_cursor, "oldest_cursor": 1,
                       "latest_cursor": latest, "cursor_expired": False,
                       "truncated": next_cursor < latest, "capacity": 256,
                       "dropped_events_total": 0, "malformed_events_total": 0},
        }
        if self.mode == "wrong_session":
            snapshot["session_id"] = "session-someone-else"
        elif self.mode == "wrong_actor":
            snapshot["actor"]["character_id"] += 1000
        elif self.mode == "wrong_runtime_generation":
            snapshot["runtime_generation"] = "generation-replaced-world"
        elif self.mode == "wrong_scene":
            snapshot["actor"]["scene"]["map_instance_key"] = "map-someone-else"
        elif self.mode == "expired_window":
            snapshot["events"]["cursor_expired"] = True
        return 200, envelope(snapshot)


class ActorEventsSmokeTests(unittest.IsolatedAsyncioTestCase):
    async def run_fixture(self, mode: str = "normal", *, page_limit: int = 1):
        stub = ActorEventsStub(mode).start()
        try:
            root = Path(__file__).resolve().parents[1] / ".test-tmp"
            root.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json", timeout=0.1 if mode == "open_timeout" else 2)
                output = folder / "actor-events-report.json"
                report = await run_actor_events_smoke(
                    config, profile_id="primary", expected_base_url=stub.base_url, output=output,
                    page_limit=page_limit, operation_timeout=2, poll_interval=0.01,
                )
                self.assertEqual(report, json.loads(output.read_text()))
                self.assertEqual(0o600, output.stat().st_mode & 0o777)
            return stub, report
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_official_stdio_pages_repeats_rejects_and_reopens_the_same_native_actor(self):
        stub, report = await self.run_fixture()
        self.assertTrue(report["passed"], report)
        self.assertTrue(report["cleanup"]["verified"])
        self.assertFalse(report["game_client_verified"])
        self.assertFalse(report["model_called"])
        self.assertEqual(2, stub.creations)
        self.assertEqual(2, len(stub.close_requests))
        self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
        first_id = next(iter(stub.sessions))
        cursors = [read["since_cursor"] for read in stub.event_reads if read["session_id"] == first_id]
        self.assertGreaterEqual(cursors.count(0), 2, "same cursor must be read twice without consumption")
        self.assertTrue({1, 2, 3, MAX_LONG} <= set(cursors), "pagination, latest-empty and future probes must all run")
        self.assertTrue(all(read["limit"] == 1 for read in stub.event_reads))
        self.assertNotIn(PASSWORD, json.dumps(report))
        self.assertNotIn(TOKEN, json.dumps(report))

    async def test_empty_actual_ticker_text_is_valid_and_not_replaced_with_a_welcome_constant(self):
        stub, report = await self.run_fixture("empty_ticker")
        self.assertTrue(report["passed"], report)
        self.assertEqual("", stub.packet_events[1]["text"])
        self.assertEqual(2, len(stub.close_requests))

    async def test_normal_new_packets_during_repeated_read_or_future_probe_do_not_false_fail(self):
        for mode, limit in [("append_on_repeat", 5), ("packet_before_future", 1)]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode, page_limit=limit)
                self.assertTrue(report["passed"], report)
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(2, len(stub.close_requests))

    async def test_missing_packet_capabilities_or_actor_scope_do_not_start_a_write(self):
        for mode in ["missing_capability", "missing_scope", "wrong_cursor_scope"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertEqual(0, stub.creations)
                self.assertEqual([], stub.event_reads)
                self.assertTrue(report["cleanup"]["verified"])

    async def test_false_missing_or_non_ticker_messages_fail_and_close_the_known_session(self):
        for mode in ["no_ticker", "false_ticker", "missing_ticker_flag"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))
                self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))

    async def test_wrong_actor_session_scene_and_world_generation_fail_and_release_the_actor(self):
        for mode in ["wrong_session", "wrong_actor", "wrong_runtime_generation", "wrong_scene"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))

    async def test_skipped_cursor_consuming_read_and_expired_history_cannot_pass_as_complete(self):
        for mode in ["jumping_cursor", "consuming_reads", "expired_window"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertEqual(1, len(stub.close_requests))

    async def test_latest_cursor_regression_cannot_hide_previously_advertised_packets(self):
        stub, report = await self.run_fixture("latest_regression")
        self.assertFalse(report["passed"])
        self.assertEqual("PACKET_LATEST_CURSOR_REGRESSED", report["failure"])
        self.assertTrue(report["cleanup"]["verified"])
        self.assertEqual(1, len(stub.close_requests))

    async def test_unrejected_future_cursor_and_closed_session_do_not_count_as_success(self):
        for mode in ["future_accepted", "closed_accepted", "old_after_reopen_accepted"]:
            with self.subTest(mode=mode):
                stub, report = await self.run_fixture(mode)
                self.assertFalse(report["passed"])
                self.assertTrue(report["cleanup"]["verified"])
                self.assertTrue(all(session["state"] == "CLOSED" for session in stub.sessions.values()))
                self.assertEqual(2 if mode == "old_after_reopen_accepted" else 1, len(stub.close_requests))
                if mode == "closed_accepted":
                    self.assertEqual("NATIVE_CLOSED", report["cleanup"]["state"])

    async def test_unknown_open_result_keeps_the_request_id_without_blind_replay(self):
        stub, report = await self.run_fixture("open_timeout")
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"]["verified"])
        opens = [request for request in stub.requests if request["path"].endswith("/actor/open")]
        self.assertEqual(1, len(opens))
        self.assertEqual(opens[0]["body"]["requestId"], report["recovery"]["unknown_write"]["request_id"])
        self.assertEqual(0, len(stub.close_requests))

    async def test_explicit_target_and_page_limit_fail_before_authentication_or_mutation(self):
        stub = ActorEventsStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                config = stub.write_config(Path(directory) / "private.json")
                with self.assertRaises(ActorSmokeFailure):
                    await run_actor_events_smoke(config, profile_id="primary",
                                                 expected_base_url="http://127.0.0.1:1")
                for value in [0, 501, True, 1.0, "1"]:
                    with self.subTest(page_limit=value), self.assertRaises((ActorSmokeFailure, BridgeError, ValueError)):
                        await run_actor_events_smoke(config, profile_id="primary",
                                                     expected_base_url=stub.base_url, page_limit=value)
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)

    async def test_previous_failure_journal_is_never_overwritten_by_a_new_run(self):
        stub = ActorEventsStub().start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                config = stub.write_config(folder / "private.json")
                output = folder / "previous-failure.json"
                previous = b'{"passed":false,"recovery":{"unknown_write":{"request_id":"retain-this-id"}}}\n'
                output.write_bytes(previous)
                with self.assertRaisesRegex(ActorSmokeFailure, "EVIDENCE_OUTPUT_ALREADY_EXISTS"):
                    await run_actor_events_smoke(config, profile_id="primary", expected_base_url=stub.base_url,
                                                 output=output)
                self.assertEqual(previous, output.read_bytes())
            self.assertEqual([], stub.requests)
        finally:
            await asyncio.to_thread(stub.stop)
