"""Explicit-target native chat acceptance with independent CHATTEXT evidence."""
from __future__ import annotations

import argparse
import asyncio
from copy import deepcopy
import json
from pathlib import Path
import time
from typing import Any

from mcp import Client

from .actor_events_smoke import ActorEventsAcceptance, require_packet_capability
from .actor_smoke import actor_target_config, check, run_actor_acceptance
from .bridge import MAX_LONG
from .smoke import require_capabilities, require_generation


def history_has_text(value: Any) -> bool:
    if isinstance(value, dict):
        return "text" in value or "parameters" in value or any(history_has_text(item) for item in value.values())
    return isinstance(value, list) and any(history_has_text(item) for item in value)


class ChatAcceptance(ActorEventsAcceptance):
    def __init__(self, profile: str, timeout: float, interval: float, output: Path | None) -> None:
        super().__init__(profile, timeout, interval, output, 500)
        self.report.update(acceptance="native_chat_action", chat_action_verified=False,
                           social_bot_reply_verified=False, action_results=[], chat_echoes=[])
        self.save()

    def action_identity(self, value: dict[str, Any], session: dict[str, Any]) -> None:
        self.operation(value, "ACTION")
        check(value["session_id"] == session["session_id"]
              and value["session_generation"] == session["session_generation"], "CHAT_SESSION_MISMATCH")
        check(not history_has_text(value), "ACTION_HISTORY_CONTAINS_TEXT")

    def native_result(self, value: dict[str, Any], session: dict[str, Any], cursor: int) -> dict[str, Any]:
        self.action_identity(value, session)
        result = value.get("result")
        check(value["status"] == "SUCCEEDED" and isinstance(result, dict), "CHAT_NOT_SUCCEEDED")
        check(result.get("observed_outcome") == "native_chat_broadcast"
              and result.get("native_dispatch_started") is True and result.get("echo_matched") is True,
              "NATIVE_CHAT_BROADCAST_UNVERIFIED")
        actor_id = session["session"]["actor"]["character_id"]
        check(type(result.get("sender_character_id")) is int and result["sender_character_id"] == actor_id,
              "NATIVE_CHAT_SENDER_MISMATCH")
        for key in ["echo_sequence", "packet_cursor_before", "packet_cursor_after"]:
            check(type(result.get(key)) is int and 0 <= result[key] <= MAX_LONG, "CHAT_PACKET_CURSOR_INVALID")
        check(cursor <= result["packet_cursor_before"] < result["echo_sequence"] <= result["packet_cursor_after"],
              "CHAT_ECHO_NOT_NEW")
        check(type(result.get("cancel_too_late")) is bool, "CHAT_CANCEL_FACT_MISSING")
        return result

    async def read_packets(self, session: dict[str, Any], cursor: int) -> tuple[dict[str, Any], list[dict[str, Any]], int]:
        deadline, events = time.monotonic() + self.timeout, []
        while True:
            value, window = await self.observe(session, cursor)
            events.extend(window["items"])
            cursor = window["next_cursor"]
            if not window["truncated"]:
                return value, events, cursor
            check(time.monotonic() < deadline, "CHAT_PACKET_PAGINATION_TIMEOUT")

    @staticmethod
    def echoes(events: list[dict[str, Any]], actor_id: int, text: str) -> list[dict[str, Any]]:
        return [event for event in events if event.get("event_type") == "chat_text"
                and event.get("decode_status") == "decoded" and event.get("opcode") == 162
                and type(event.get("sender_character_id")) is int and event["sender_character_id"] == actor_id
                and event.get("text") == text and type(event.get("show")) is int and event["show"] == 0
                and event.get("text_truncated") is False]

    async def submit_chat(self, session: dict[str, Any], context: dict[str, Any], text: str,
                          suffix: str, cursor: int) -> tuple[dict[str, Any], dict[str, Any]]:
        args = {"session_id": session["session_id"], "expected_generation": session["session_generation"],
                "request_id": self.prefix + suffix, "action": "chat", "parameters": {"text": text},
                "expected_context": {"scene_ref": context["scene_ref"], "runtime_generation": self.generation}}
        self.actioning = await self.call("game_act", args, write=True)
        self.action_identity(self.actioning, session)
        self.save()
        operation = await self.wait(self.actioning, "ACTION")
        result = self.native_result(operation, session, cursor)
        self.actioning = None
        self.report["action_results"].append({"operation_id": operation["operation_id"], "result": deepcopy(result)})
        self.save()
        return operation, args

    async def verified_echo(self, session: dict[str, Any], cursor: int, text: str,
                            operation: dict[str, Any]) -> tuple[dict[str, Any], int]:
        observation, events, latest = await self.read_packets(session, cursor)
        actor_id = session["session"]["actor"]["character_id"]
        matches = self.echoes(events, actor_id, text)
        check(len(matches) == 1 and matches[0]["sequence"] == operation["result"]["echo_sequence"],
              "INDEPENDENT_CHAT_ECHO_MISMATCH")
        self.report["chat_echoes"].append(deepcopy(matches[0]))
        self.save()
        return observation, latest

    async def run(self, client: Client) -> None:
        self.client = client
        names = sorted(tool.name for tool in (await client.list_tools()).tools)
        caps = await self.call("game_capabilities", {})
        self.report["enabled_tools"] = require_capabilities(caps, names, actors=True, chat=True)
        self.report["packet_observation"] = require_packet_capability(caps)
        control = caps["actor_control"]
        for key, expected in [("maximum_pending_actions_per_session", 7), ("actor_executor_queue_capacity", 8)]:
            check(type(control.get(key)) is int and control[key] == expected, "CHAT_QUEUE_CAPABILITY_INVALID")
        for key in ["chat_minimum_interval_ms", "native_clock_update_interval_ms"]:
            check(type(control.get(key)) is int and 1 <= control[key] <= MAX_LONG,
                  "CHAT_TIMING_CAPABILITY_INVALID")
        # Native spam timestamps use Server's periodically cached clock. A wall
        # clock delay of 250ms alone need not advance that clock by its 200ms rule.
        required_wait_ms = max(250, control["chat_minimum_interval_ms"] + control["native_clock_update_interval_ms"])
        check(required_wait_ms <= self.timeout * 1000, "CHAT_TIMING_EXCEEDS_TIMEOUT")
        self.report["chat_timing"] = {
            "chat_minimum_interval_ms": control["chat_minimum_interval_ms"],
            "native_clock_update_interval_ms": control["native_clock_update_interval_ms"],
            "required_wait_ms": required_wait_ms,
            "clock": "monotonic wall time since first ACTION completion",
        }
        self.generation = require_generation(caps)
        self.report.update(source=caps["source"], runtime_generation=self.generation)

        first = await self.open(self.prefix + ":chat-open1")
        actor = deepcopy(self.opened_actor(first))
        self.report["first_actor"] = actor
        observation, _, baseline = await self.read_packets(first, 0)
        await self.verify_scene(observation["actor"])
        text1, text2 = "聊天验收 " + self.prefix[-16:] + " 一", "聊天验收 " + self.prefix[-16:] + " 二"
        self.report["chat_messages"] = [text1, text2]
        action1, args1 = await self.submit_chat(first, observation["actor"]["scene"], text1, ":chat1", baseline)
        first_completed = time.monotonic()
        _, latest = await self.verified_echo(first, baseline, text1, action1)

        duplicate = await self.call("game_act", args1, write=True)
        self.action_identity(duplicate, first)
        check(duplicate["operation_id"] == action1["operation_id"], "CHAT_DUPLICATE_CREATED_OPERATION")
        check(self.native_result(duplicate, first, baseline) == action1["result"], "CHAT_DUPLICATE_RESULT_CHANGED")
        _, replay_events, latest = await self.read_packets(first, baseline)
        replay_matches = self.echoes(replay_events, actor["character_id"], text1)
        check(len(replay_matches) == 1 and replay_matches[0]["sequence"] == action1["result"]["echo_sequence"],
              "CHAT_DUPLICATE_BROADCAST")
        self.report["duplicate_request_verified"] = True

        await asyncio.sleep(max(0, required_wait_ms / 1000 - (time.monotonic() - first_completed)))
        observation, _, baseline2 = await self.read_packets(first, latest)
        await self.verify_scene(observation["actor"])
        self.report["second_chat_wait_after_first_completion_ms"] = (time.monotonic() - first_completed) * 1000
        self.report["chat_timing"]["actual_wait_after_first_completion_ms"] = self.report["second_chat_wait_after_first_completion_ms"]
        self.save()
        action2, _ = await self.submit_chat(first, observation["actor"]["scene"], text2, ":chat2", baseline2)
        await self.verified_echo(first, baseline2, text2, action2)
        check(action2["result"]["echo_sequence"] > action1["result"]["echo_sequence"], "SECOND_CHAT_REUSED_OLD_ECHO")
        # The duplicate may have been queued and broadcast during the normal
        # inter-message delay. Recheck the complete original interval before close.
        _, final_events, _ = await self.read_packets(first, baseline)
        for text, operation in [(text1, action1), (text2, action2)]:
            matches = self.echoes(final_events, actor["character_id"], text)
            check(len(matches) == 1 and matches[0]["sequence"] == operation["result"]["echo_sequence"],
                  "CHAT_DELAYED_DUPLICATE_BROADCAST")
        self.report["first_close"] = (await self.close(self.prefix + ":chat-close1"))["result"]
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.save()
        await self.rejected(self.observe_args(first, 0, 1), 410, "CLOSED_CHAT_SESSION_NOT_REJECTED")
        for prior in [action1, action2]:
            history = await self.call("operation_get", {"operation_id": prior["operation_id"]})
            self.action_identity(history, first)
            check(history["status"] == "SUCCEEDED" and history.get("result") == prior["result"], "CHAT_HISTORY_FACT_CHANGED")
        self.report["cleanup"] = {"verified": False, "state": "REOPEN_REQUESTED"}
        self.save()
        second = await self.open(self.prefix + ":chat-open2")
        reopened = self.opened_actor(second)
        check(second["session_id"] != first["session_id"]
              and second["session_generation"] != first["session_generation"]
              and all(reopened[key] == actor[key] for key in ["character_id", "account_id", "profile_id"]),
              "CHAT_REOPEN_IDENTITY_CHANGED")
        self.report["reopened_actor"] = deepcopy(reopened)
        await self.rejected(self.observe_args(first, 0, 1), 410, "OLD_CHAT_SESSION_LEAKED_NEW_EVENTS")
        self.report["second_close"] = (await self.close(self.prefix + ":chat-close2"))["result"]
        self.report.update(actor_control_verified=True, actor_packet_observation_verified=True,
                           chat_action_verified=True, chat_text_live_verified=True,
                           history_without_text_verified=True, closed_session_rejected=True,
                           reopened_identity_verified=True)
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.report["passed"] = "evidence_error" not in self.report
        self.save()

    async def cleanup(self) -> None:
        if self.actioning is not None and self.unknown_write is None:
            try:
                await self.call("operation_cancel", {"operation_id": self.actioning["operation_id"],
                                                      "request_id": self.prefix + ":chat-cleanup-cancel"}, write=True)
                terminal = await self.wait(self.actioning, "ACTION")
                if terminal["status"] == "CANCELLED":
                    expected = ("chat_cancelled_before_dispatch" if self.actioning["action"] == "chat"
                                else "action_cancelled_before_dispatch")
                    check(terminal.get("result", {}).get("observed_outcome") == expected
                          and terminal["result"].get("native_dispatch_started") is False,
                          "CHAT_CANCEL_OUTCOME_UNVERIFIED")
                self.report["action_cleanup"] = {"operation_id": terminal["operation_id"], "status": terminal["status"]}
                self.actioning = None
            except Exception:
                self.report["action_cleanup"] = {"verified": False, "state": "ACTION_OUTCOME_UNCONFIRMED"}
            self.save()
        if (self.unknown_write is not None and self.unknown_write.get("tool") in {"game_act", "operation_cancel"}
                and self.active is not None):
            # An action or its cancellation may be unknown, but actor identity is
            # known. Preserve that request key separately while closing normally.
            unresolved = deepcopy(self.unknown_write)
            self.unknown_action_write, self.unknown_write = unresolved, None
            self.report["action_cleanup"] = {"verified": False, "state": "ACTION_OUTCOME_UNKNOWN"}
            self.save()
            await super().cleanup()
            if self.unknown_write is None:
                self.unknown_write = unresolved
            self.save()
            return
        await super().cleanup()


async def run_chat_action_smoke(
    config_path: Path, *, profile_id: str, expected_base_url: str, output: Path | None = None,
    operation_timeout: float = 60, poll_interval: float = 0.1,
) -> dict[str, Any]:
    config = actor_target_config(config_path, profile_id=profile_id, expected_base_url=expected_base_url,
                                 operation_timeout=operation_timeout, poll_interval=poll_interval)
    return await run_actor_acceptance(config_path, config, ChatAcceptance(profile_id, operation_timeout, poll_interval, output))


def main() -> None:
    parser = argparse.ArgumentParser(description="显式目标的 MCP 原生普通聊天验收（会写专用 fixture 并广播中文 nonce）")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-base-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--operation-timeout", type=float, default=60)
    args = parser.parse_args()
    try:
        report = asyncio.run(run_chat_action_smoke(
            args.config, profile_id=args.profile, expected_base_url=args.expected_base_url,
            output=args.output, operation_timeout=args.operation_timeout,
        ))
    except Exception:
        parser.exit(1, "聊天验收未启动；请检查目标、profile、证据新路径和本地配置。\n")
    print(json.dumps({"passed": report["passed"], "report": str(args.output), "cleanup": report["cleanup"]}, ensure_ascii=False))
    if not report["passed"]:
        parser.exit(1)


if __name__ == "__main__":
    main()
