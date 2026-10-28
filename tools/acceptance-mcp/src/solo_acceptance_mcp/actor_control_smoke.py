"""Explicit-target MCP module pause/resume with native role and dialogue evidence."""
from __future__ import annotations

import argparse
import asyncio
from copy import deepcopy
import json
from pathlib import Path
import re
from typing import Any

from mcp import Client

from .actor_smoke import actor_target_config, check, run_actor_acceptance
from .bridge import MAX_LONG
from .npc_center_smoke import NpcCenterAcceptance
from .smoke import MODULE_CONTROL_CAPABILITY, require_capabilities, require_generation


# Keep fixtures and the acceptance report on the same capability contract.
MODULE_CAPABILITY = MODULE_CONTROL_CAPABILITY


class ActorControlAcceptance(NpcCenterAcceptance):
    def __init__(self, profile: str, timeout: float, interval: float, output: Path | None,
                 expected_revision: str | None = None) -> None:
        self.controlling: dict[str, Any] | None = None
        self.unknown_control_write: dict[str, Any] | None = None
        self.expected_revision = expected_revision
        self.host_id: str | None = None
        super().__init__(profile, timeout, interval, output)
        self.report.update(acceptance="native_actor_module_pause_resume", module_control_verified=False,
                           module_controls=[], module_snapshots=[], module_rejection_probes=[])
        self.save()

    def save(self) -> None:
        self.report["module_recovery"] = {
            "pending_operation": None if self.controlling is None else self.controlling.get("operation_id"),
            "unknown_write": self.unknown_control_write,
        }
        super().save()

    def module(self, actor: dict[str, Any], state: str, generation: int | None = None) -> dict[str, Any]:
        runtime = actor.get("runtime")
        check(isinstance(runtime, dict) and isinstance(runtime.get("id"), str) and bool(runtime["id"]),
              "MODULE_HOST_MISSING")
        check(runtime.get("epoch") == self.generation and runtime.get("state") == "BOUND"
              and runtime.get("character_id") == actor.get("character_id"), "MODULE_HOST_NOT_BOUND")
        if self.host_id is None:
            self.host_id = runtime["id"]
        check(runtime["id"] == self.host_id, "MODULE_REPLACED_HOST")
        module = runtime.get("module")
        check(isinstance(module, dict) and module.get("type_id") == "mcp-control"
              and isinstance(module.get("instance_id"), str) and bool(module["instance_id"])
              and type(module.get("generation")) is int and 1 <= module["generation"] <= MAX_LONG
              and module.get("state") == state, "MODULE_STATE_UNVERIFIED")
        if generation is not None:
            check(module["generation"] == generation, "MODULE_GENERATION_MISMATCH")
        lane = runtime.get("lane")
        check(isinstance(lane, dict) and lane.get("closed") is False, "MODULE_CLOSED_ROOT_LANE")
        self.report["module_snapshots"].append(deepcopy(runtime))
        check(len(self.report["module_snapshots"]) <= 64, "MODULE_EVIDENCE_BOUND_EXCEEDED")
        self.save()
        return module

    @staticmethod
    def control_args(session: dict[str, Any], generation: int, operation: str, request_id: str) -> dict[str, Any]:
        return {"session_id": session["session_id"], "expected_generation": session["session_generation"],
                "expected_module_generation": generation, "operation": operation, "request_id": request_id}

    def control_result(self, value: dict[str, Any], session: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
        self.operation(value, "MODULE_CONTROL")
        check(value.get("session_id") == session["session_id"]
              and value.get("session_generation") == session["session_generation"]
              and value.get("module_operation") == args["operation"], "MODULE_CONTROL_IDENTITY_MISMATCH")
        result = value.get("result")
        state = "SUSPENDED" if args["operation"] == "pause" else "ACTIVE"
        outcome = "actor_module_paused" if args["operation"] == "pause" else "actor_module_resumed"
        check(value.get("status") == "SUCCEEDED" and isinstance(result, dict)
              and result.get("observed_outcome") == outcome and result.get("module_control_complete") is True
              and result.get("runtime_id") == self.host_id and result.get("module_state") == state,
              "MODULE_CONTROL_NOT_COMPLETE")
        check(type(result.get("previous_module_generation")) is int
              and result["previous_module_generation"] == args["expected_module_generation"]
              and type(result.get("module_generation")) is int
              and 1 <= result["module_generation"] <= MAX_LONG, "MODULE_CONTROL_GENERATION_INVALID")
        if args["operation"] == "pause":
            check(result.get("module_drained") is True
                  and result["module_generation"] == args["expected_module_generation"], "MODULE_PAUSE_NOT_DRAINED")
        else:
            check(result["module_generation"] > args["expected_module_generation"], "MODULE_RESUME_REUSED_GENERATION")
        return result

    async def control(self, session: dict[str, Any], generation: int, operation: str,
                      suffix: str) -> tuple[dict[str, Any], dict[str, Any]]:
        args = self.control_args(session, generation, operation, self.prefix + suffix)
        self.controlling = await self.call("actor_control", args, write=True)
        self.save()
        completed = await self.wait(self.controlling, "MODULE_CONTROL")
        result = self.control_result(completed, session, args)
        self.controlling = None
        self.report["module_controls"].append({"operation_id": completed["operation_id"],
                                                "module_operation": operation, "result": deepcopy(result)})
        self.save()
        return completed, args

    async def rejection(self, name: str, args: dict[str, Any], reason: str) -> None:
        check(self.client is not None, "CLIENT_UNAVAILABLE")
        self.prepare_write(name, args, business=True)
        response = await self.client.call_tool(name, args)
        value = response.structured_content
        check(response.is_error and isinstance(value, dict) and value.get("request_id") == args["request_id"]
              and isinstance(value.get("error"), dict) and value["error"].get("http_status") == 409
              and value["error"].get("outcome_unknown") is False, reason)
        self.unknown_write = None
        self.report["module_rejection_probes"].append({"tool": name, "request_id": args["request_id"],
                                                       "http_status": 409})
        self.save()

    async def replay_control(self, session: dict[str, Any], original: dict[str, Any],
                             args: dict[str, Any]) -> None:
        replay = await self.call("actor_control", args, write=True)
        check(replay.get("operation_id") == original["operation_id"], "MODULE_DUPLICATE_CREATED_OPERATION")
        check(self.control_result(replay, session, args) == original["result"], "MODULE_DUPLICATE_CHANGED_RESULT")

    def closed(self, value: dict[str, Any]) -> None:
        super().closed(value)
        check(value.get("result", {}).get("native_dialogue_cleanup_verified") is True,
              "MODULE_CLOSE_DIALOGUE_UNVERIFIED")
        runtime = value["session"].get("runtime")
        expected_runtime = None if self.active is None else self.active["session"]["actor"].get("runtime")
        check(isinstance(expected_runtime, dict) and isinstance(runtime, dict)
              and runtime.get("id") == expected_runtime.get("id") and runtime.get("epoch") == self.generation
              and runtime.get("state") == "CLOSED"
              and runtime.get("lane", {}).get("closed") is True, "MODULE_ROOT_CLOSE_UNVERIFIED")

    async def run(self, client: Client) -> None:
        self.client = client
        names = sorted(tool.name for tool in (await client.list_tools()).tools)
        caps = await self.call("game_capabilities", {})
        require_capabilities(caps, names, actors=True, chat=True, module_control=True)
        self.capabilities(caps, names)
        capability = caps["actor_control"].get("module_control")
        source = caps.get("source", {})
        check(source.get("soloWorkingTreeDirty") is False, "MODULE_SOURCE_NOT_FIXED")
        if self.expected_revision is not None:
            check(source.get("soloGitRevision") == self.expected_revision, "MODULE_SOURCE_MISMATCH")
        self.generation = require_generation(caps)
        self.report.update(source=deepcopy(source), runtime_generation=self.generation,
                           module_control_capability=deepcopy(capability))
        first = await self.open(self.prefix + ":module-open1")
        actor = deepcopy(self.opened_actor(first))
        self.baseline = actor
        self.report["first_actor"] = actor
        observation, _, cursor = await self.current(first, 0)
        await self.verify_scene(observation["actor"])
        initial = deepcopy(self.module(observation["actor"], "ACTIVE"))
        observation, cursor, _, _ = await self.open_center(first, observation, cursor, ":module-center")
        dialogue = deepcopy(self.dialogue(observation["actor"], True))

        paused, pause_args = await self.control(first, initial["generation"], "pause", ":module-pause")
        observation, _, cursor = await self.current(first, cursor)
        self.module(observation["actor"], "SUSPENDED", initial["generation"])
        check(observation["actor"].get("dialogue") == dialogue, "MODULE_PAUSE_CHANGED_DIALOGUE")
        await self.replay_control(first, paused, pause_args)

        rejected_text = "暂停拒绝 " + self.prefix[-16:]
        expected_context = {"scene_ref": observation["actor"]["scene"]["scene_ref"],
                            "runtime_generation": self.generation}
        for action, parameters, suffix in [
            ("chat", {"text": rejected_text}, ":module-paused-chat"),
            ("npc_cancel", {"dialogue_version": dialogue["dialogue_version"]}, ":module-paused-npc"),
        ]:
            await self.rejection("game_act", {"session_id": first["session_id"],
                "expected_generation": first["session_generation"], "request_id": self.prefix + suffix,
                "action": action, "parameters": parameters, "expected_context": expected_context},
                "PAUSED_MODULE_ACTION_NOT_REJECTED")
        observation, events, cursor = await self.current(first, cursor)
        self.module(observation["actor"], "SUSPENDED", initial["generation"])
        check(observation["actor"].get("dialogue") == dialogue
              and not any(event.get("opcode") == 304 for event in events)
              and not self.echoes(events, actor["character_id"], rejected_text), "PAUSED_MODULE_DISPATCHED_ACTION")

        resumed, resume_args = await self.control(first, initial["generation"], "resume", ":module-resume")
        observation, _, cursor = await self.current(first, cursor)
        renewed = deepcopy(self.module(observation["actor"], "ACTIVE", resumed["result"]["module_generation"]))
        check(renewed["instance_id"] == initial["instance_id"]
              and observation["actor"].get("dialogue") == dialogue, "MODULE_RESUME_REPLACED_INSTANCE_OR_DIALOGUE")
        await self.rejection("actor_control", self.control_args(first, initial["generation"], "pause",
                              self.prefix + ":module-stale-generation"), "STALE_MODULE_CONTROL_NOT_REJECTED")
        # An old accepted ID remains a historical pause fact; replay must not pause the resumed generation.
        await self.replay_control(first, paused, pause_args)
        await self.replay_control(first, resumed, resume_args)
        observation, _, cursor = await self.current(first, cursor)
        self.module(observation["actor"], "ACTIVE", renewed["generation"])
        check(observation["actor"].get("dialogue") == dialogue, "MODULE_REPLAY_CHANGED_DIALOGUE")

        text = "模块恢复验收 " + self.prefix[-16:]
        chat, _ = await self.submit_chat(first, observation["actor"]["scene"], text, ":module-chat", cursor)
        observation, cursor = await self.verified_echo(first, cursor, text, chat)
        self.unchanged(observation["actor"])
        self.module(observation["actor"], "ACTIVE", renewed["generation"])
        current_dialogue = self.dialogue(observation["actor"], True)
        check(current_dialogue == dialogue, "MODULE_CHAT_CHANGED_DIALOGUE")
        observation, cursor, _, _ = await self.cancelled(first, observation, cursor,
                                                        current_dialogue["dialogue_version"], ":module-npc-cancel")
        self.report["first_close"] = (await self.close(self.prefix + ":module-close1"))["result"]
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.save()
        await self.rejected(self.observe_args(first, 0, 1), 410, "CLOSED_MODULE_SESSION_NOT_REJECTED")
        self.report["cleanup"] = {"verified": False, "state": "REOPEN_REQUESTED"}
        second = await self.open(self.prefix + ":module-open2")
        reopened = deepcopy(self.opened_actor(second))
        self.unchanged(reopened, position=False)
        self.dialogue(reopened, False)
        check(second["session_id"] != first["session_id"]
              and reopened.get("runtime", {}).get("id") != self.host_id, "MODULE_REOPEN_REUSED_CLOSED_HOST")
        self.report["reopened_actor"] = reopened
        self.report["second_close"] = (await self.close(self.prefix + ":module-close2"))["result"]
        self.report.update(module_control_verified=True, actor_control_verified=True,
                           actor_packet_observation_verified=True, chat_action_verified=True,
                           npc_action_verified=True, same_host_verified=True, pause_dialogue_preserved=True,
                           paused_actions_rejected=True, new_module_generation_verified=True,
                           duplicate_request_verified=True, reopened_identity_verified=True,
                           no_gameplay_changes_verified=True, root_closed_verified=True)
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.report["passed"] = "evidence_error" not in self.report
        self.save()

    async def cleanup(self) -> None:
        unknown = self.unknown_write
        if unknown is not None and unknown.get("tool") == "actor_control" and self.active is not None:
            # Preserve the uncertain module command, while the independent root close can still clean a known role.
            self.unknown_control_write, self.unknown_write = deepcopy(unknown), None
            self.save()
            await super().cleanup()
            if self.unknown_write is None:
                self.unknown_write = unknown
            self.save()
            return
        await super().cleanup()


async def run_actor_control_smoke(
    config_path: Path, *, profile_id: str, expected_base_url: str, output: Path | None = None,
    operation_timeout: float = 60, poll_interval: float = 0.1, expected_revision: str | None = None,
) -> dict[str, Any]:
    config = actor_target_config(config_path, profile_id=profile_id, expected_base_url=expected_base_url,
                                 operation_timeout=operation_timeout, poll_interval=poll_interval)
    check(expected_revision is None or re.fullmatch(r"[0-9a-f]{40}", expected_revision) is not None,
          "INVALID_EXPECTED_REVISION")
    return await run_actor_acceptance(config_path, config,
        ActorControlAcceptance(profile_id, operation_timeout, poll_interval, output, expected_revision))


def main() -> None:
    parser = argparse.ArgumentParser(description="固定MCP控制模块暂停/恢复验收（会写专用fixture）")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-base-url", required=True)
    parser.add_argument("--expected-revision")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--operation-timeout", type=float, default=60)
    args = parser.parse_args()
    try:
        report = asyncio.run(run_actor_control_smoke(args.config, profile_id=args.profile,
            expected_base_url=args.expected_base_url, output=args.output, operation_timeout=args.operation_timeout,
            expected_revision=args.expected_revision))
    except Exception:
        parser.exit(1, "模块控制验收未启动；请检查目标、profile、源码身份及新的证据路径。\n")
    print(json.dumps({"passed": report["passed"], "report": str(args.output), "cleanup": report["cleanup"]}, ensure_ascii=False))
    if not report["passed"]:
        parser.exit(1)


if __name__ == "__main__":
    main()
