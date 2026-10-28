"""Explicit-target packet observation acceptance through the official MCP stdio SDK."""
from __future__ import annotations

import argparse
import asyncio
from copy import deepcopy
import json
from pathlib import Path
import time
from typing import Any

from mcp import Client

from .actor_smoke import ActorAcceptance, actor_target_config, check, run_actor_acceptance
from .bridge import MAX_LONG, validate_integer
from .smoke import require_capabilities, require_generation, require_scene_context


PACKET_CAPABILITY = {
    "capacity": 256,
    "maximum_text_chars": 1024,
    "cursor_scope": "session_packet_sequence",
    "decoded_packets": [
        {"event_type": "chat_text", "opcode": 162},
        {"event_type": "player_hint", "opcode": 214},
        {"event_type": "server_message", "opcode": 68, "message_types": list(range(8))},
    ],
}
NPC_PACKET_CAPABILITY = {"event_type": "npc_dialogue", "opcode": 304, "message_types": [4]}
YELLOW_TIP_PACKET_CAPABILITY = {"event_type": "yellow_tip", "opcode": 77}


def require_packet_capability(caps: dict[str, Any]) -> dict[str, Any]:
    scopes = caps.get("scopes")
    control = caps.get("actor_control")
    check(isinstance(scopes, list) and "actor" in scopes, "ACTOR_OBSERVATION_NOT_ENABLED")
    check(isinstance(control, dict), "PACKET_CAPABILITY_MISSING")
    packet = control.get("packet_observation")
    check(isinstance(packet, dict), "PACKET_CAPABILITY_MISSING")
    for key in ["capacity", "maximum_text_chars"]:
        check(type(packet.get(key)) is int and packet[key] == PACKET_CAPABILITY[key], "PACKET_CAPABILITY_INVALID")
    check(packet.get("cursor_scope") == PACKET_CAPABILITY["cursor_scope"], "PACKET_CURSOR_SCOPE_INVALID")
    decoded = packet.get("decoded_packets")
    check(isinstance(decoded, list) and len(decoded) in {3, 4, 5}
          and all(isinstance(row, dict) and isinstance(row.get("event_type"), str) for row in decoded),
          "PACKET_DECODERS_INVALID")
    by_type = {row.get("event_type"): row for row in decoded}
    optional_packets = [NPC_PACKET_CAPABILITY, YELLOW_TIP_PACKET_CAPABILITY]
    expected_packets = PACKET_CAPABILITY["decoded_packets"] + [
        row for row in optional_packets if row["event_type"] in by_type
    ]
    check(len(by_type) == len(decoded) == len(expected_packets), "PACKET_DECODERS_INVALID")
    for expected in expected_packets:
        actual = by_type.get(expected["event_type"])
        check(isinstance(actual, dict) and type(actual.get("opcode")) is int
              and actual["opcode"] == expected["opcode"], "PACKET_DECODERS_INVALID")
        if "message_types" in expected:
            types = actual.get("message_types")
            supported_types = ([4], [4, 12]) if expected["event_type"] == "npc_dialogue" else (expected["message_types"],)
            check(isinstance(types, list) and all(type(value) is int for value in types)
                  and any(len(types) == len(candidate) and set(types) == set(candidate) for candidate in supported_types),
                  "PACKET_MESSAGE_TYPES_INVALID")
    return packet


class ActorEventsAcceptance(ActorAcceptance):
    def __init__(self, profile: str, timeout: float, interval: float, output: Path | None, page_limit: int) -> None:
        self.page_limit = page_limit
        self.packet_latest: dict[tuple[str, int], int] = {}
        super().__init__(profile, timeout, interval, output)
        self.report.update(
            acceptance="actor_packet_events", page_limit=page_limit, packet_pages=[], rejection_probes=[],
            actor_packet_observation_verified=False, chat_text_live_verified=False,
            player_hint_live_verified=False, chat_action_verified=False, npc_action_verified=False,
        )
        self.save()

    @staticmethod
    def observe_args(session: dict[str, Any], cursor: int, limit: int) -> dict[str, Any]:
        return {"scope": "actor", "session_id": session["session_id"],
                "expected_generation": session["session_generation"], "since_cursor": cursor, "limit": limit}

    def snapshot(self, value: dict[str, Any], session: dict[str, Any], cursor: int) -> dict[str, Any]:
        check(require_generation(value) == self.generation, "PACKET_RUNTIME_CHANGED")
        check(value.get("scope") == "actor" and value.get("information_level") == "server_diagnostic"
              and value.get("snapshot_consistency") == "best_effort", "ACTOR_SNAPSHOT_METADATA_INVALID")
        check(isinstance(value.get("captured_at"), str) and bool(value["captured_at"].strip()),
              "ACTOR_CAPTURE_TIME_MISSING")
        check(value.get("session_id") == session["session_id"]
              and type(value.get("session_generation")) is int
              and value["session_generation"] == session["session_generation"]
              and value.get("profile_id") == self.profile, "PACKET_SESSION_MISMATCH")
        actor = value.get("actor")
        check(isinstance(actor, dict), "PACKET_ACTOR_MISSING")
        original = session["session"]["actor"]
        for key in ["character_id", "account_id", "profile_id"]:
            check(actor.get(key) == original[key], "PACKET_ACTOR_IDENTITY_MISMATCH")
        check(actor.get("is_bot") is False and actor.get("is_current") is True
              and actor.get("logged_in_world") is True and actor.get("client_logged_in") is True,
              "PACKET_ACTOR_NOT_CURRENT")
        context = require_scene_context(actor.get("scene"))
        check(context.get("runtime_generation") == self.generation, "PACKET_SCENE_RUNTIME_CHANGED")
        check(all(context[key] == actor.get(key) for key in ["world_id", "channel_id", "map_id"]),
              "PACKET_SCENE_LOCATION_MISMATCH")
        window = value.get("events")
        check(isinstance(window, dict) and isinstance(window.get("items"), list), "PACKET_WINDOW_MISSING")
        for key in ["next_cursor", "oldest_cursor", "latest_cursor", "dropped_events_total", "malformed_events_total"]:
            check(type(window.get(key)) is int and 0 <= window[key] <= MAX_LONG, "PACKET_CURSOR_INVALID")
        check(type(window.get("capacity")) is int and window["capacity"] == 256, "PACKET_CAPACITY_INVALID")
        check(type(window.get("cursor_expired")) is bool and type(window.get("truncated")) is bool,
              "PACKET_WINDOW_FLAGS_INVALID")
        oldest, latest = window["oldest_cursor"], window["latest_cursor"]
        check(cursor <= latest and ((oldest == latest == 0) or 1 <= oldest <= latest
                                    and latest - oldest + 1 <= window["capacity"]), "PACKET_WINDOW_BOUNDS_INVALID")
        check(window["cursor_expired"] == (cursor < oldest - 1), "PACKET_EXPIRY_FLAG_INVALID")
        # A fresh login's acceptance must cover its complete available history.
        check(window["cursor_expired"] is False, "LOGIN_PACKET_HISTORY_EXPIRED")
        items = window["items"]
        check(len(items) <= self.page_limit, "PACKET_PAGE_LIMIT_EXCEEDED")
        previous = cursor
        for event in items:
            check(isinstance(event, dict) and type(event.get("sequence")) is int
                  and event["sequence"] == previous + 1 and oldest <= event["sequence"] <= latest,
                  "PACKET_SEQUENCE_INVALID")
            previous = event["sequence"]
            check(isinstance(event.get("received_at"), str) and bool(event["received_at"].strip())
                  and type(event.get("opcode")) is int and type(event.get("length")) is int
                  and event["length"] >= 0, "PACKET_EVENT_METADATA_INVALID")
            check(event.get("decode_status") in {"decoded", "unsupported", "malformed"}, "PACKET_DECODE_STATUS_INVALID")
            if event["decode_status"] == "decoded":
                check(event.get("event_type") in {"chat_text", "player_hint", "server_message", "npc_dialogue", "yellow_tip"}
                      and event.get("information_level") == "player_visible"
                      and isinstance(event.get("text"), str) and len(event["text"]) <= 1024
                      and type(event.get("text_truncated")) is bool, "PACKET_TEXT_INVALID")
            else:
                check(event.get("event_type") == "packet_summary" and "text" not in event, "PACKET_SUMMARY_INVALID")
        check(window["next_cursor"] == previous, "PACKET_NEXT_CURSOR_SKIPPED")
        check(window["truncated"] == (previous < latest), "PACKET_TRUNCATION_FLAG_INVALID")
        check(bool(items) or cursor == latest, "PACKET_PAGE_STALLED")
        return window

    async def observe(self, session: dict[str, Any], cursor: int) -> tuple[dict[str, Any], dict[str, Any]]:
        value = await self.call("game_observe", self.observe_args(session, cursor, self.page_limit))
        window = self.snapshot(value, session, cursor)
        identity = (session["session_id"], session["session_generation"])
        check(window["latest_cursor"] >= self.packet_latest.get(identity, 0), "PACKET_LATEST_CURSOR_REGRESSED")
        self.packet_latest[identity] = window["latest_cursor"]
        self.report["packet_pages"].append({
            "session_id": session["session_id"], "session_generation": session["session_generation"],
            "since_cursor": cursor, "scene": deepcopy(value["actor"]["scene"]), "events": deepcopy(window),
        })
        self.save()
        return value, window

    async def rejected(self, args: dict[str, Any], status: int, reason: str) -> None:
        check(self.client is not None, "CLIENT_UNAVAILABLE")
        response = await self.client.call_tool("game_observe", args)
        value = response.structured_content
        check(response.is_error and isinstance(value, dict) and isinstance(value.get("error"), dict)
              and type(value["error"].get("http_status")) is int
              and value["error"]["http_status"] == status, reason)
        self.report["rejection_probes"].append({"arguments": args, "http_status": status})
        self.save()

    async def packet_window(self, session: dict[str, Any]) -> None:
        deadline = time.monotonic() + self.timeout
        value, first = await self.observe(session, 0)
        while not first["items"]:
            check(time.monotonic() < deadline, "LOGIN_PACKETS_NOT_RECEIVED")
            await asyncio.sleep(self.interval)
            value, first = await self.observe(session, 0)
        await self.verify_scene(value["actor"])
        _, repeated = await self.observe(session, 0)
        check(repeated["items"][:len(first["items"])] == first["items"],
              "PACKET_READ_CONSUMED_HISTORY")
        self.report["repeat_read_verified"] = True

        page, cursor, total, notice = first, 0, 0, None
        while True:
            check(time.monotonic() < deadline, "PACKET_PAGINATION_TIMEOUT")
            for event in page["items"]:
                total += 1
                if (event.get("event_type") == "server_message" and event.get("decode_status") == "decoded"
                        and event.get("opcode") == 68 and type(event.get("message_type")) is int
                        and event["message_type"] == 4 and event.get("ticker_enabled") is True
                        and event.get("text_truncated") is False):
                    notice = deepcopy(event)
            cursor = page["next_cursor"]
            if not page["truncated"]:
                _, tail = await self.observe(session, cursor)
                if not tail["items"]:
                    check(tail["next_cursor"] == cursor == tail["latest_cursor"], "PACKET_EMPTY_CURSOR_CHANGED")
                    break
                page = tail
            else:
                _, page = await self.observe(session, cursor)
        check(notice is not None, "NATIVE_TICKER_NOTICE_NOT_OBSERVED")
        check(cursor < MAX_LONG, "PACKET_CURSOR_EXHAUSTED")
        await self.rejected(self.observe_args(session, MAX_LONG, self.page_limit), 400,
                            "FUTURE_PACKET_CURSOR_NOT_REJECTED")
        self.report.update(server_message=notice, paginated_packet_count=total, latest_packet_cursor=cursor,
                           empty_tail_verified=True, future_cursor_rejected=True)
        self.save()

    async def run(self, client: Client) -> None:
        self.client = client
        names = sorted(tool.name for tool in (await client.list_tools()).tools)
        caps = await self.call("game_capabilities", {})
        self.report["enabled_tools"] = require_capabilities(caps, names, actors=True)
        self.report["packet_observation"] = require_packet_capability(caps)
        self.generation = require_generation(caps)
        self.report.update(source=caps["source"], runtime_generation=self.generation)
        first = await self.open(self.prefix + ":events-open1")
        first_actor = deepcopy(self.opened_actor(first))
        self.report["first_actor"] = first_actor
        await self.packet_window(first)
        self.report["first_close"] = (await self.close(self.prefix + ":events-close1"))["result"]
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.save()
        old_args = self.observe_args(first, 0, self.page_limit)
        await self.rejected(old_args, 410, "CLOSED_ACTOR_EVENTS_NOT_REJECTED")
        self.report["cleanup"] = {"verified": False, "state": "REOPEN_REQUESTED"}
        self.save()
        second = await self.open(self.prefix + ":events-open2")
        check(second["session_id"] != first["session_id"]
              and second["session_generation"] != first["session_generation"], "REOPEN_REUSED_SESSION_IDENTITY")
        second_actor = self.opened_actor(second)
        check(all(second_actor[key] == first_actor[key] for key in ["character_id", "account_id", "profile_id"]),
              "REOPEN_NATIVE_IDENTITY_CHANGED")
        self.report["reopened_actor"] = deepcopy(second_actor)
        await self.observe(second, 0)
        await self.rejected(old_args, 410, "OLD_SESSION_LEAKED_NEW_EVENTS")
        self.report["second_close"] = (await self.close(self.prefix + ":events-close2"))["result"]
        self.report.update(actor_control_verified=True, actor_packet_observation_verified=True,
                           closed_session_rejected=True, reopened_identity_verified=True,
                           scene_scope="current actor scene, not each historical packet's occurrence scene")
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.report["passed"] = "evidence_error" not in self.report
        self.save()


async def run_actor_events_smoke(
    config_path: Path, *, profile_id: str, expected_base_url: str, output: Path | None = None,
    page_limit: int = 1, operation_timeout: float = 60, poll_interval: float = 0.1,
) -> dict[str, Any]:
    validate_integer(page_limit, "page_limit", 1, 500)
    config = actor_target_config(config_path, profile_id=profile_id, expected_base_url=expected_base_url,
                                 operation_timeout=operation_timeout, poll_interval=poll_interval)
    acceptance = ActorEventsAcceptance(profile_id, operation_timeout, poll_interval, output, page_limit)
    return await run_actor_acceptance(config_path, config, acceptance)


def main() -> None:
    parser = argparse.ArgumentParser(description="显式目标的 MCP 原生角色包事件验收（登录和退出会写专用 fixture）")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-base-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--page-limit", type=int, default=1)
    parser.add_argument("--operation-timeout", type=float, default=60)
    args = parser.parse_args()
    try:
        report = asyncio.run(run_actor_events_smoke(
            args.config, profile_id=args.profile, expected_base_url=args.expected_base_url, output=args.output,
            page_limit=args.page_limit, operation_timeout=args.operation_timeout,
        ))
    except Exception:
        parser.exit(1, "包事件验收未启动；请检查目标、profile、page-limit 与本地配置。\n")
    print(json.dumps({"passed": report["passed"], "report": str(args.output), "cleanup": report["cleanup"]}, ensure_ascii=False))
    if not report["passed"]:
        parser.exit(1)


if __name__ == "__main__":
    main()
