"""Explicit-target no-reward NPC replies through current native offers and paths."""
from __future__ import annotations

import argparse
import asyncio
from copy import deepcopy
import json
from pathlib import Path
import time
from typing import Any

from mcp import Client

from .actor_smoke import actor_target_config, check, run_actor_acceptance
from .bridge import MAX_INT, MAX_LONG
from .npc_center_smoke import NpcCenterAcceptance, UNCHANGED_FIELDS
from .smoke import require_generation


REPLY_ACTIONS = {"chat", "npc_center_open", "npc_cancel", "npc_reply"}
PATHS = {"center", "welfare", "transport_category", "transport_town", "unsupported"}


class NpcReplyAcceptance(NpcCenterAcceptance):
    def __init__(self, profile: str, timeout: float, interval: float, output: Path | None) -> None:
        super().__init__(profile, timeout, interval, output)
        self.report.update(acceptance="native_npc_reply_no_reward", npc_reply_verified=False,
                           claim_marker_verified_via_cli=False, return_location_storage_verified_via_cli=False,
                           reward_acceptance_performed=False, reply_results=[], reply_rejections=[])
        self.expected_npc_sequences: list[int] = []
        self.save()

    def capabilities(self, caps: dict[str, Any], names: list[str]) -> None:
        super().capabilities(caps, names)
        check(set(caps["actions"]) == REPLY_ACTIONS, "NPC_REPLY_NOT_ENABLED")
        npc = caps["actor_control"]["npc_dialogue"]
        check(npc.get("message_types") == [4, 12] and npc.get("reply_kinds") == ["select", "decline"]
              and npc.get("requires_complete_offers") is True
              and npc.get("requires_current_offered_selection") is True
              and type(npc.get("maximum_offered_selections")) is int
              and npc["maximum_offered_selections"] == 128, "NPC_REPLY_CAPABILITY_INVALID")
        decoder = next(row for row in self.report["packet_observation"]["decoded_packets"]
                       if row["event_type"] == "npc_dialogue")
        check(decoder["message_types"] == [4, 12], "NPC_REPLY_DECODER_NOT_ENABLED")

    @staticmethod
    def dialogue(actor: dict[str, Any], active: bool) -> dict[str, Any]:
        value = actor.get("dialogue")
        check(isinstance(value, dict) and value.get("active") is active and "text" not in value,
              "NPC_REPLY_DIALOGUE_STATE_INVALID")
        if not active:
            return value
        check(value.get("supported") is True and value.get("entry_type") == "native_ui_center"
              and value.get("npc_template_id") == 9900001 and value.get("message_type") in {4, 12}
              and type(value.get("dialogue_version")) is int and 1 <= value["dialogue_version"] <= MAX_LONG
              and type(value.get("packet_sequence")) is int and value["packet_sequence"] == value["dialogue_version"]
              and value.get("path") in PATHS - {"unsupported"}, "NPC_REPLY_CURRENT_SOURCE_INVALID")
        options = value.get("reply_options")
        check(isinstance(options, dict) and set(options) == {
            "kinds", "offered_selections", "allowed_selections", "complete", "consumed",
        }, "NPC_REPLY_OPTIONS_MISSING")
        kinds, offered, allowed = (options.get(key) for key in ["kinds", "offered_selections", "allowed_selections"])
        check(isinstance(kinds, list) and all(isinstance(kind, str) and kind in {"select", "decline"} for kind in kinds)
              and len(kinds) == len(set(kinds)), "NPC_REPLY_KINDS_INVALID")
        for values, limit in [(offered, 128), (allowed, 2)]:
            check(isinstance(values, list) and len(values) <= limit
                  and all(type(item) is int and 0 <= item <= MAX_INT for item in values)
                  and len(values) == len(set(values)), "NPC_REPLY_SELECTIONS_INVALID")
        check(set(allowed) <= set(offered) and type(options.get("complete")) is bool
              and type(options.get("consumed")) is bool, "NPC_REPLY_OPTIONS_INVALID")
        if not options["complete"]:
            check(not offered and not allowed and not kinds, "NPC_INCOMPLETE_OFFERS_EXPOSED")
        return value

    def menu(self, actor: dict[str, Any], path: str, *, complete: bool = True) -> dict[str, Any]:
        value = self.dialogue(actor, True)
        check(value["path"] == path and value["reply_options"]["complete"] is complete
              and value["reply_options"]["consumed"] is False, "NPC_REPLY_PATH_OR_COMPLETENESS_MISMATCH")
        options = value["reply_options"]
        if path == "center":
            check(value["message_type"] == 4 and options["kinds"] == ["select"]
                  and set(options["allowed_selections"]) == {0, 71}
                  and {0, 71, 3} <= set(options["offered_selections"])
                  and not (set(range(61, 69)) | {999}) & set(options["offered_selections"]), "NPC_CENTER_OFFERS_INVALID")
        elif path == "welfare":
            check(value["message_type"] == 12 and options["kinds"] == ["decline"]
                  and not options["offered_selections"] and not options["allowed_selections"], "NPC_WELFARE_BUTTONS_INVALID")
        elif path == "transport_category":
            check(value["message_type"] == 4 and options["kinds"] == ["select"]
                  and set(options["offered_selections"]) == {0, 1, 2, 3}
                  and options["allowed_selections"] == [2], "NPC_TRANSPORT_OFFERS_INVALID")
        elif path == "transport_town":
            check(value["message_type"] == 4 and not complete, "NPC_TOWN_MUST_BE_TRUNCATED")
        return value

    async def open_center(self, session, observation, cursor, suffix):
        value, latest, operation, args = await super().open_center(session, observation, cursor, suffix)
        self.menu(value["actor"], "center")
        self.expected_npc_sequences.append(operation["result"]["packet_sequence"])
        return value, latest, operation, args

    async def reply(self, session: dict[str, Any], observation: dict[str, Any], cursor: int,
                    kind: str, suffix: str, *, selection: int | None = None, path: str) -> tuple[dict[str, Any], int, dict[str, Any], dict[str, Any]]:
        source = self.dialogue(observation["actor"], True)
        options = source["reply_options"]
        check(options["complete"] and not options["consumed"] and kind in options["kinds"], "NPC_REPLY_NOT_CURRENTLY_OFFERED")
        parameters: dict[str, Any] = {"dialogue_version": source["dialogue_version"], "kind": kind}
        if kind == "select":
            check(selection in options["offered_selections"] and selection in options["allowed_selections"],
                  "NPC_REPLY_SELECTION_NOT_ALLOWED")
            parameters["selection"] = selection
        args = {"session_id": session["session_id"], "expected_generation": session["session_generation"],
                "request_id": self.prefix + suffix, "action": "npc_reply", "parameters": parameters,
                "expected_context": {"scene_ref": observation["actor"]["scene"]["scene_ref"], "runtime_generation": self.generation}}
        self.actioning = await self.call("game_act", args, write=True)
        self.action_identity(self.actioning, session)
        self.save()
        operation = await self.wait(self.actioning, "ACTION")
        self.action_identity(operation, session)
        result = operation.get("result", {})
        check(operation["status"] == "SUCCEEDED" and result.get("observed_outcome") == (
            "dialogue_replied" if kind == "select" else "dialogue_declined")
              and result.get("native_dispatch_started") is True and result.get("reply_version_consumed") is True
              and result.get("kind") == kind and result.get("selection") == selection
              and (result.get("selection") is None if kind == "decline" else type(result.get("selection")) is int)
              and result.get("dialogue_path") == path, "NPC_REPLY_NATIVE_OUTCOME_UNVERIFIED")
        for key in ["dialogue_version", "packet_sequence", "packet_cursor_before", "packet_cursor_after"]:
            check(type(result.get(key)) is int and 0 <= result[key] <= MAX_LONG, "NPC_REPLY_SEQUENCE_INVALID")
        check(result["dialogue_version"] == result["packet_sequence"]
              and cursor <= result["packet_cursor_before"] < result["packet_sequence"] <= result["packet_cursor_after"]
              and result["packet_sequence"] > source["dialogue_version"], "NPC_REPLY_NOT_A_NEW_PACKET")
        value, events, latest = await self.current(session, cursor)
        successors = [event for event in events if event.get("opcode") == 304
                      and event.get("sequence") == result["packet_sequence"]]
        check(len(successors) == 1, "NPC_REPLY_SUCCESSOR_PACKET_MISSING")
        event = successors[0]
        if path == "closed":
            check(result.get("active") is False and result.get("native_dialogue_cleanup_verified") is True,
                  "NPC_DECLINE_CLEANUP_UNVERIFIED")
            self.dialogue(value["actor"], False)
            check(event.get("event_type") == "packet_summary" and event.get("decode_status") == "unsupported",
                  "NPC_TYPE_ZERO_WAS_NOT_A_SUMMARY")
        else:
            successor = self.menu(value["actor"], path, complete=path != "transport_town")
            check(result.get("active") is True and successor["dialogue_version"] == result["dialogue_version"]
                  and event.get("event_type") == "npc_dialogue" and event.get("decode_status") == "decoded"
                  and event.get("message_type") == successor["message_type"] and event.get("npc_template_id") == 9900001
                  and event.get("speaker_type") == 4 and event.get("speaker") == 0
                  and isinstance(event.get("text"), str) and bool(event["text"].strip())
                  and event.get("text_truncated") is (path == "transport_town"), "NPC_REPLY_SUCCESSOR_DIALOGUE_MISMATCH")
        self.actioning = None
        self.last_npc_completion = time.monotonic()
        self.expected_npc_sequences.append(result["packet_sequence"])
        self.report["reply_results"].append({"operation_id": operation["operation_id"], "parameters": parameters,
                                               "result": deepcopy(result), "successor_event": deepcopy(event),
                                               "dialogue": deepcopy(value["actor"]["dialogue"])})
        self.save()
        return value, latest, operation, args

    async def reject_reply(self, session: dict[str, Any], observation: dict[str, Any], cursor: int,
                           parameters: dict[str, Any], suffix: str, status: int) -> tuple[dict[str, Any], int]:
        check(self.client is not None, "CLIENT_UNAVAILABLE")
        args = {"session_id": session["session_id"], "expected_generation": session["session_generation"],
                "request_id": self.prefix + suffix, "action": "npc_reply", "parameters": parameters,
                "expected_context": {"scene_ref": observation["actor"]["scene"]["scene_ref"], "runtime_generation": self.generation}}
        self.prepare_write("game_act", args)
        response = await self.client.call_tool("game_act", args)
        data = response.structured_content
        check(response.is_error and isinstance(data, dict) and data.get("request_id") == args["request_id"]
              and isinstance(data.get("error"), dict) and data["error"].get("http_status") == status
              and data["error"].get("outcome_unknown") is False, "UNSAFE_NPC_REPLY_NOT_REJECTED")
        self.unknown_write = None
        value, events, latest = await self.current(session, cursor)
        check(value["actor"].get("dialogue") == observation["actor"].get("dialogue")
              and not any(event.get("opcode") == 304 for event in events), "REJECTED_NPC_REPLY_CHANGED_DIALOGUE")
        self.report["reply_rejections"].append({"parameters": parameters, "request_id": args["request_id"], "http_status": status})
        self.save()
        return value, latest

    async def run(self, client: Client) -> None:
        self.client = client
        caps = await self.call("game_capabilities", {})
        self.capabilities(caps, sorted(tool.name for tool in (await client.list_tools()).tools))
        self.generation = require_generation(caps)
        self.report.update(source=caps["source"], runtime_generation=self.generation)
        session = await self.open(self.prefix + ":reply-open1")
        actor = deepcopy(self.opened_actor(session))
        check(type(actor.get("level")) is int and actor["level"] == 1 and actor.get("map_id") == 10000,
              "NPC_REPLY_FIXTURE_START_INVALID")
        self.baseline = actor
        self.unchanged(actor)
        self.dialogue(actor, False)
        self.report["first_actor"] = actor
        observation, _, cursor = await self.current(session, 0)
        await self.verify_scene(observation["actor"])
        initial_cursor = cursor

        observation, cursor, _, _ = await self.open_center(session, observation, cursor, ":root-decline")
        root_version = observation["actor"]["dialogue"]["dialogue_version"]
        for selection in list(range(61, 69)) + [999, 3]:
            observation, cursor = await self.reject_reply(session, observation, cursor,
                {"dialogue_version": root_version, "kind": "select", "selection": selection}, ":reject-root-" + str(selection), 409)
        observation, cursor, selected, args = await self.reply(session, observation, cursor, "select", ":welfare1", selection=0, path="welfare")
        observation, cursor = await self.replay(session, selected, args, cursor, observation["actor"]["dialogue"])
        observation, cursor = await self.reject_reply(session, observation, cursor,
            {"dialogue_version": root_version, "kind": "select", "selection": 0}, ":consumed-root", 410)
        welfare_version = observation["actor"]["dialogue"]["dialogue_version"]
        observation, cursor, declined, args = await self.reply(session, observation, cursor, "decline", ":decline", path="closed")
        observation, cursor = await self.replay(session, declined, args, cursor, observation["actor"]["dialogue"])
        observation, cursor = await self.reject_reply(session, observation, cursor,
            {"dialogue_version": welfare_version, "kind": "decline"}, ":consumed-decline", 410)

        await self.wait_click()
        observation, cursor, _, _ = await self.open_center(session, observation, cursor, ":root-cancel")
        observation, cursor, _, _ = await self.reply(session, observation, cursor, "select", ":welfare2", selection=0, path="welfare")
        version = observation["actor"]["dialogue"]["dialogue_version"]
        observation, cursor, _, _ = await self.cancelled(session, observation, cursor, version, ":welfare-cancel")

        await self.wait_click()
        observation, cursor, _, _ = await self.open_center(session, observation, cursor, ":root-transport")
        observation, cursor, _, _ = await self.reply(session, observation, cursor, "select", ":transport", selection=71, path="transport_category")
        observation, cursor, _, _ = await self.reply(session, observation, cursor, "select", ":town", selection=2, path="transport_town")
        version = observation["actor"]["dialogue"]["dialogue_version"]
        observation, cursor = await self.reject_reply(session, observation, cursor,
            {"dialogue_version": version, "kind": "select", "selection": 1}, ":reject-truncated-town", 409)
        observation, cursor, _, _ = await self.cancelled(session, observation, cursor, version, ":town-cancel")

        await self.wait_click()
        observation, cursor, _, _ = await self.open_center(session, observation, cursor, ":root-close")
        observation, cursor, _, _ = await self.reply(session, observation, cursor, "select", ":welfare-close", selection=0, path="welfare")
        _, final_events, _ = await self.current(session, initial_cursor)
        check([event["sequence"] for event in final_events if event.get("opcode") == 304] == self.expected_npc_sequences,
              "NPC_REPLY_EXTRA_OR_DELAYED_PACKET")
        self.report["first_close"] = (await self.close(self.prefix + ":reply-close1"))["result"]
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.save()
        await self.rejected(self.observe_args(session, 0, 1), 410, "CLOSED_NPC_REPLY_SESSION_NOT_REJECTED")
        self.report["cleanup"] = {"verified": False, "state": "REOPEN_REQUESTED"}
        self.save()
        reopened = await self.open(self.prefix + ":reply-open2")
        check(reopened["session_id"] != session["session_id"]
              and reopened["session_generation"] != session["session_generation"], "NPC_REPLY_REUSED_SESSION")
        reopened_actor = deepcopy(self.opened_actor(reopened))
        self.unchanged(reopened_actor, position=False)
        self.dialogue(reopened_actor, False)
        self.report["reopened_actor"] = reopened_actor
        await self.rejected(self.observe_args(session, 0, 1), 410, "OLD_NPC_REPLY_SESSION_LEAKED")
        self.report["second_close"] = (await self.close(self.prefix + ":reply-close2"))["result"]
        self.report.update(actor_control_verified=True, actor_packet_observation_verified=True, npc_action_verified=True,
                           npc_reply_verified=True, duplicate_request_verified=True, closed_session_rejected=True,
                           public_snapshot_state_unchanged_verified=True,
                           state_verification_scope=list(UNCHANGED_FIELDS) + ["position before close"],
                           independent_claim_marker_audit_required=True, reward_acceptance_performed=False)
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.report["passed"] = "evidence_error" not in self.report
        self.save()


async def run_npc_reply_smoke(
    config_path: Path, *, profile_id: str, expected_base_url: str, output: Path | None = None,
    operation_timeout: float = 60, poll_interval: float = 0.1,
) -> dict[str, Any]:
    config = actor_target_config(config_path, profile_id=profile_id, expected_base_url=expected_base_url,
                                 operation_timeout=operation_timeout, poll_interval=poll_interval)
    return await run_actor_acceptance(config_path, config, NpcReplyAcceptance(profile_id, operation_timeout, poll_interval, output))


def main() -> None:
    parser = argparse.ArgumentParser(description="有限NPC选择/拒绝验收（不接受礼包、不传送，会正常写fixture并存档）")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-base-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--operation-timeout", type=float, default=60)
    args = parser.parse_args()
    try:
        report = asyncio.run(run_npc_reply_smoke(
            args.config, profile_id=args.profile, expected_base_url=args.expected_base_url,
            output=args.output, operation_timeout=args.operation_timeout,
        ))
    except Exception:
        parser.exit(1, "NPC回复验收未启动；请检查目标、profile、证据新路径和本地配置。\n")
    print(json.dumps({"passed": report["passed"], "report": str(args.output), "cleanup": report["cleanup"]}, ensure_ascii=False))
    if not report["passed"]:
        parser.exit(1)


if __name__ == "__main__":
    main()
