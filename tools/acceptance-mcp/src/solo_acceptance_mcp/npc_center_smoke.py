"""Explicit-target native script-center first screen and versioned cancellation."""
from __future__ import annotations

import argparse
import asyncio
from copy import deepcopy
import json
from pathlib import Path
import time
from typing import Any

from mcp import Client

from .actor_events_smoke import NPC_PACKET_CAPABILITY, require_packet_capability
from .actor_smoke import PERSISTENT_FIELDS, actor_target_config, check, run_actor_acceptance
from .bridge import MAX_LONG
from .chat_action_smoke import ChatAcceptance
from .smoke import CONTEXT_FIELDS, require_capabilities, require_generation


NPC_ACTIONS = {"chat", "npc_center_open", "npc_cancel"}
TICKET_FIELDS = {"nx_credit", "maple_point", "nx_prepaid"}
UNCHANGED_FIELDS = PERSISTENT_FIELDS + ("tickets_summary",)


class NpcCenterAcceptance(ChatAcceptance):
    def __init__(self, profile: str, timeout: float, interval: float, output: Path | None) -> None:
        super().__init__(profile, timeout, interval, output)
        self.report.update(acceptance="native_npc_center", npc_center_verified=False, npc_action_verified=False,
                           npc_dialogues=[], npc_cancellations=[], stale_version_probes=[], click_waits=[])
        self.last_npc_completion: float | None = None
        self.required_wait_ms = 0
        self.baseline: dict[str, Any] | None = None
        self.save()

    def capabilities(self, caps: dict[str, Any], names: list[str]) -> None:
        self.report["enabled_tools"] = require_capabilities(caps, names, actors=True)
        check(set(caps.get("actions", [])) == NPC_ACTIONS, "NPC_ACTIONS_NOT_ENABLED")
        packet = require_packet_capability(caps)
        check(NPC_PACKET_CAPABILITY in packet["decoded_packets"], "NPC_PACKET_DECODER_NOT_ENABLED")
        control = caps["actor_control"]
        npc = control.get("npc_dialogue")
        check(isinstance(npc, dict) and npc.get("entrypoints") == ["native_ui_center"]
              and npc.get("message_types") == [4] and npc.get("version_scope") == "session_packet_sequence"
              and npc.get("cancel_requires_latest_version") is True, "NPC_DIALOGUE_CAPABILITY_INVALID")
        for value in [npc.get("npc_minimum_interval_ms"), control.get("native_clock_update_interval_ms")]:
            check(type(value) is int and 1 <= value <= MAX_LONG, "NPC_TIMING_CAPABILITY_INVALID")
        self.required_wait_ms = npc["npc_minimum_interval_ms"] + control["native_clock_update_interval_ms"] + 1
        check(self.required_wait_ms <= self.timeout * 1000, "NPC_TIMING_EXCEEDS_TIMEOUT")
        self.report.update(packet_observation=packet, npc_dialogue_capability=npc, npc_timing={
            "npc_minimum_interval_ms": npc["npc_minimum_interval_ms"],
            "native_clock_update_interval_ms": control["native_clock_update_interval_ms"],
            "required_wait_ms": self.required_wait_ms,
        })

    def unchanged(self, actor: dict[str, Any], *, position: bool = True) -> None:
        check(self.baseline is not None, "NPC_BASELINE_MISSING")
        tickets = actor.get("tickets_summary")
        check(isinstance(tickets, dict) and set(tickets) == TICKET_FIELDS
              and all(type(value) is int and value >= 0 for value in tickets.values()), "NPC_TICKETS_SNAPSHOT_INVALID")
        fields = UNCHANGED_FIELDS + (("position",) if position else ())
        check(all(key in actor and actor[key] == self.baseline[key] for key in fields), "NPC_UNEXPECTED_GAMEPLAY_CHANGE")
        if position:
            original = self.baseline["scene"]
            context = actor.get("scene", {})
            check(all(context.get(key) == original[key] for key in CONTEXT_FIELDS + ("runtime_generation",)),
                  "NPC_SCENE_CHANGED")

    @staticmethod
    def dialogue(actor: dict[str, Any], active: bool) -> dict[str, Any]:
        value = actor.get("dialogue")
        check(isinstance(value, dict) and value.get("active") is active and "text" not in value,
              "NPC_DIALOGUE_STATE_INVALID")
        if active:
            check(value.get("supported") is True and value.get("entry_type") == "native_ui_center"
                  and value.get("npc_template_id") == 9900001 and value.get("message_type") == 4
                  and type(value.get("dialogue_version")) is int and value["dialogue_version"] > 0
                  and value.get("packet_sequence") == value["dialogue_version"], "NPC_ACTIVE_DIALOGUE_INVALID")
        return value

    async def current(self, session: dict[str, Any], cursor: int) -> tuple[dict[str, Any], list[dict[str, Any]], int]:
        value, events, latest = await self.read_packets(session, cursor)
        self.unchanged(value["actor"])
        return value, events, latest

    async def action(self, session: dict[str, Any], scene: dict[str, Any], action: str,
                     parameters: dict[str, Any], suffix: str) -> tuple[dict[str, Any], dict[str, Any]]:
        args = {"session_id": session["session_id"], "expected_generation": session["session_generation"],
                "request_id": self.prefix + suffix, "action": action, "parameters": parameters,
                "expected_context": {"scene_ref": scene["scene_ref"], "runtime_generation": self.generation}}
        self.actioning = await self.call("game_act", args, write=True)
        self.action_identity(self.actioning, session)
        self.save()
        value = await self.wait(self.actioning, "ACTION")
        self.action_identity(value, session)
        check(value["action"] == action and value["status"] == "SUCCEEDED", "NPC_ACTION_NOT_SUCCEEDED")
        result = value.get("result", {})
        expected = "center_dialogue_opened" if action == "npc_center_open" else "dialogue_cancelled"
        check(result.get("observed_outcome") == expected and result.get("native_dispatch_started") is True,
              "NPC_NATIVE_DISPATCH_UNVERIFIED")
        for key in ["dialogue_version", "packet_sequence", "packet_cursor_before", "packet_cursor_after"]:
            check(type(result.get(key)) is int and 0 <= result[key] <= MAX_LONG, "NPC_RESULT_SEQUENCE_INVALID")
        check(result["dialogue_version"] == result["packet_sequence"] > 0, "NPC_RESULT_VERSION_INVALID")
        self.actioning = None
        self.report["action_results"].append({"operation_id": value["operation_id"], "action": action, "result": result})
        self.last_npc_completion = time.monotonic()
        self.save()
        return value, args

    async def wait_click(self) -> None:
        check(self.last_npc_completion is not None, "NPC_CLICK_BASELINE_MISSING")
        start = self.last_npc_completion
        await asyncio.sleep(max(0, self.required_wait_ms / 1000 - (time.monotonic() - start)))
        self.report["click_waits"].append({"required_wait_ms": self.required_wait_ms,
                                          "actual_wait_after_last_completion_ms": (time.monotonic() - start) * 1000})
        self.save()

    async def open_center(self, session: dict[str, Any], observation: dict[str, Any], cursor: int,
                          suffix: str) -> tuple[dict[str, Any], int, dict[str, Any], dict[str, Any]]:
        operation, args = await self.action(session, observation["actor"]["scene"], "npc_center_open", {}, suffix)
        result = operation["result"]
        check(result.get("active") is True and cursor <= result["packet_cursor_before"] < result["packet_sequence"]
              <= result["packet_cursor_after"], "NPC_OPEN_RESULT_UNVERIFIED")
        value, events, latest = await self.current(session, cursor)
        dialogue = self.dialogue(value["actor"], True)
        check(dialogue["dialogue_version"] == result["dialogue_version"], "NPC_OPERATION_DIALOGUE_MISMATCH")
        matches = [event for event in events if event.get("sequence") == dialogue["packet_sequence"]
                   and event.get("event_type") == "npc_dialogue" and event.get("opcode") == 304
                   and event.get("decode_status") == "decoded" and event.get("npc_template_id") == 9900001
                   and event.get("message_type") == 4 and event.get("speaker_type") == 4 and event.get("speaker") == 0
                   and isinstance(event.get("text"), str) and bool(event["text"].strip())
                   and event.get("text_truncated") is False]
        check(len(matches) == 1, "NPC_FIRST_SCREEN_NOT_OBSERVED")
        self.report["npc_dialogues"].append({"dialogue": deepcopy(dialogue), "event": deepcopy(matches[0])})
        self.save()
        return value, latest, operation, args

    async def cancelled(self, session: dict[str, Any], observation: dict[str, Any], cursor: int,
                        version: int, suffix: str) -> tuple[dict[str, Any], int, dict[str, Any], dict[str, Any]]:
        operation, args = await self.action(session, observation["actor"]["scene"], "npc_cancel",
                                            {"dialogue_version": version}, suffix)
        result = operation["result"]
        check(result["dialogue_version"] == version and result.get("active") is False
              and result.get("native_dialogue_cleanup_verified") is True, "NPC_CANCEL_CLEANUP_UNVERIFIED")
        value, _, latest = await self.current(session, cursor)
        self.dialogue(value["actor"], False)
        self.report["npc_cancellations"].append(deepcopy(result))
        self.save()
        return value, latest, operation, args

    async def replay(self, session: dict[str, Any], operation: dict[str, Any], args: dict[str, Any],
                     cursor: int, expected_dialogue: dict[str, Any]) -> tuple[dict[str, Any], int]:
        response = await self.call("game_act", args, write=True)
        self.action_identity(response, session)
        check(response["operation_id"] == operation["operation_id"] and response.get("result") == operation["result"],
              "NPC_DUPLICATE_CHANGED_OPERATION")
        value, events, latest = await self.current(session, cursor)
        check(not any(event.get("opcode") == 304 for event in events)
              and value["actor"].get("dialogue") == expected_dialogue, "NPC_DUPLICATE_CHANGED_DIALOGUE")
        return value, latest

    async def reject_old(self, session: dict[str, Any], observation: dict[str, Any], cursor: int,
                         version: int, suffix: str) -> tuple[dict[str, Any], int]:
        check(self.client is not None, "CLIENT_UNAVAILABLE")
        args = {"session_id": session["session_id"], "expected_generation": session["session_generation"],
                "request_id": self.prefix + suffix, "action": "npc_cancel", "parameters": {"dialogue_version": version},
                "expected_context": {"scene_ref": observation["actor"]["scene"]["scene_ref"], "runtime_generation": self.generation}}
        self.unknown_write = {"tool": "game_act", "request_id": args["request_id"]}
        self.report["requests"].append(dict(self.unknown_write))
        self.save()
        response = await self.client.call_tool("game_act", args)
        value = response.structured_content
        check(response.is_error and isinstance(value, dict) and value.get("request_id") == args["request_id"]
              and isinstance(value.get("error"), dict) and value["error"].get("http_status") == 410
              and value["error"].get("outcome_unknown") is False, "STALE_NPC_VERSION_NOT_REJECTED")
        self.unknown_write = None
        self.report["stale_version_probes"].append({"request_id": args["request_id"], "dialogue_version": version,
                                                     "http_status": 410})
        current, events, latest = await self.current(session, cursor)
        check(current["actor"].get("dialogue") == observation["actor"].get("dialogue")
              and not any(event.get("opcode") == 304 for event in events), "STALE_NPC_VERSION_CHANGED_DIALOGUE")
        self.save()
        return current, latest

    def closed(self, value: dict[str, Any]) -> None:
        super().closed(value)
        check(value.get("result", {}).get("native_dialogue_cleanup_verified") is True,
              "NPC_CLOSE_DIALOGUE_CLEANUP_UNVERIFIED")

    async def run(self, client: Client) -> None:
        self.client = client
        names = sorted(tool.name for tool in (await client.list_tools()).tools)
        caps = await self.call("game_capabilities", {})
        self.capabilities(caps, names)
        self.generation = require_generation(caps)
        self.report.update(source=caps["source"], runtime_generation=self.generation)
        first = await self.open(self.prefix + ":npc-open1")
        actor = deepcopy(self.opened_actor(first))
        check(type(actor.get("level")) is int and actor["level"] == 1 and actor.get("map_id") == 10000,
              "NPC_FIXTURE_START_LOCATION_INVALID")
        self.baseline = actor
        self.unchanged(actor)
        self.dialogue(actor, False)
        self.report["first_actor"] = actor
        observation, _, cursor = await self.current(first, 0)
        await self.verify_scene(observation["actor"])
        initial_cursor = cursor

        observation, cursor, opened1, args = await self.open_center(first, observation, cursor, ":center1")
        version1 = opened1["result"]["dialogue_version"]
        observation, cursor = await self.replay(first, opened1, args, cursor, observation["actor"]["dialogue"])
        observation, cursor, cancel1, args = await self.cancelled(first, observation, cursor, version1, ":cancel1")
        observation, cursor = await self.replay(first, cancel1, args, cursor, observation["actor"]["dialogue"])
        observation, cursor = await self.reject_old(first, observation, cursor, version1, ":stale-ended")

        await self.wait_click()
        observation, cursor, opened2, _ = await self.open_center(first, observation, cursor, ":center2")
        version2 = opened2["result"]["dialogue_version"]
        check(version2 > version1, "NPC_REOPEN_REUSED_VERSION")
        observation, cursor = await self.reject_old(first, observation, cursor, version1, ":stale-new")
        observation, cursor, _, _ = await self.cancelled(first, observation, cursor, version2, ":cancel2")

        await self.wait_click()
        observation, cursor, opened3, _ = await self.open_center(first, observation, cursor, ":center3")
        check(opened3["result"]["dialogue_version"] > version2, "NPC_REOPEN_REUSED_VERSION")
        _, all_events, _ = await self.current(first, initial_cursor)
        npc_sequences = [event["sequence"] for event in all_events if event.get("opcode") == 304]
        check(npc_sequences == [version1, version2, opened3["result"]["dialogue_version"]],
              "NPC_DELAYED_DUPLICATE_PACKET")
        self.report["first_close"] = (await self.close(self.prefix + ":npc-close1"))["result"]
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.save()
        await self.rejected(self.observe_args(first, 0, 1), 410, "CLOSED_NPC_SESSION_NOT_REJECTED")
        self.report["cleanup"] = {"verified": False, "state": "REOPEN_REQUESTED"}
        self.save()
        second = await self.open(self.prefix + ":npc-open2")
        reopened = deepcopy(self.opened_actor(second))
        check(second["session_id"] != first["session_id"] and second["session_generation"] != first["session_generation"],
              "NPC_REOPEN_REUSED_SESSION")
        self.unchanged(reopened, position=False)
        self.dialogue(reopened, False)
        self.report["reopened_actor"] = reopened
        await self.rejected(self.observe_args(first, 0, 1), 410, "OLD_NPC_SESSION_LEAKED_NEW_EVENTS")
        self.report["second_close"] = (await self.close(self.prefix + ":npc-close2"))["result"]
        self.report.update(actor_control_verified=True, actor_packet_observation_verified=True,
                           npc_center_verified=True, npc_action_verified=True, no_gameplay_changes_verified=True,
                           duplicate_request_verified=True, closed_session_rejected=True, menu_selection_performed=False,
                           reopened_identity_verified=True)
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.report["passed"] = "evidence_error" not in self.report
        self.save()


async def run_npc_center_smoke(
    config_path: Path, *, profile_id: str, expected_base_url: str, output: Path | None = None,
    operation_timeout: float = 60, poll_interval: float = 0.1,
) -> dict[str, Any]:
    config = actor_target_config(config_path, profile_id=profile_id, expected_base_url=expected_base_url,
                                 operation_timeout=operation_timeout, poll_interval=poll_interval)
    return await run_actor_acceptance(config_path, config, NpcCenterAcceptance(profile_id, operation_timeout, poll_interval, output))


def main() -> None:
    parser = argparse.ArgumentParser(description="原生脚本中心首屏与版本绑定取消验收（不选择菜单，会写专用 fixture）")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-base-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--operation-timeout", type=float, default=60)
    args = parser.parse_args()
    try:
        report = asyncio.run(run_npc_center_smoke(
            args.config, profile_id=args.profile, expected_base_url=args.expected_base_url,
            output=args.output, operation_timeout=args.operation_timeout,
        ))
    except Exception:
        parser.exit(1, "NPC中心验收未启动；请检查目标、profile、证据新路径和本地配置。\n")
    print(json.dumps({"passed": report["passed"], "report": str(args.output), "cleanup": report["cleanup"]}, ensure_ascii=False))
    if not report["passed"]:
        parser.exit(1)


if __name__ == "__main__":
    main()
