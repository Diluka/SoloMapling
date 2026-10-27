"""Explicit-target native actor lifecycle acceptance through the real MCP protocol.

Unlike the observation smoke, this creates/loads dedicated fixture characters.
The target and profile are mandatory, and checkpoints keep recovery IDs if the
operator or transport interrupts the run. It never calls a database directly.
"""
from __future__ import annotations

import argparse
import asyncio
from copy import deepcopy
import json
import math
from pathlib import Path
import sys
import tempfile
import time
from typing import Any
from uuid import uuid4

from mcp import Client, StdioServerParameters

from .bridge import BridgeConfig, PROFILE_IDENTIFIER, load_config, validate_identifier
from .smoke import CONTEXT_FIELDS, require_capabilities, require_generation, require_scene_context


TERMINAL = frozenset({"SUCCEEDED", "FAILED", "CANCELLED", "INDETERMINATE"})
PENDING = frozenset({"QUEUED", "RUNNING", "WAITING", "CANCEL_REQUESTED"})
PERSISTENT_FIELDS = (
    "character_id", "account_id", "profile_id", "name", "world_id", "channel_id",
    "map_id", "level", "exp", "hp", "mp", "meso", "job_id", "guild_id", "party_id",
    "inventory_summary", "quest_summary",
)


class ActorSmokeFailure(RuntimeError):
    """Only fixed local reason codes cross the smoke's public failure boundary."""


def check(condition: bool, reason: str) -> None:
    if not condition:
        raise ActorSmokeFailure(reason)


def require_persistence_summaries(actor: dict[str, Any]) -> None:
    inventory = actor.get("inventory_summary")
    check(isinstance(inventory, dict), "INVENTORY_SUMMARY_MISSING")
    for category in ["equipped", "equip", "use", "setup", "etc", "cash"]:
        summary = inventory.get(category)
        check(isinstance(summary, dict), "INVENTORY_SUMMARY_INCOMPLETE")
        items = summary.get("items")
        check(isinstance(items, list) and len(items) <= 100, "INVENTORY_ITEMS_INVALID")
        check(type(summary.get("count")) is int and summary["count"] == len(items)
              and summary.get("truncated") is False, "INVENTORY_SUMMARY_NOT_COMPLETE")
        for item in items:
            check(isinstance(item, dict) and type(item.get("item_id")) is int and item["item_id"] > 0
                  and type(item.get("position")) is int and type(item.get("quantity")) is int
                  and item["quantity"] > 0, "INVENTORY_ITEM_INVALID")
    quests = actor.get("quest_summary")
    check(isinstance(quests, dict) and isinstance(quests.get("quests"), list), "QUEST_SUMMARY_MISSING")
    check(type(quests.get("count")) is int and quests["count"] == len(quests["quests"]) <= 100
          and quests.get("truncated") is False, "QUEST_SUMMARY_NOT_COMPLETE")
    for quest in quests["quests"]:
        check(isinstance(quest, dict) and type(quest.get("quest_id")) is int and quest["quest_id"] >= 0
              and type(quest.get("status")) is int and quest["status"] >= 0, "QUEST_STATE_INVALID")


def checkpoint(path: Path | None, report: dict[str, Any]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    temporary.replace(path)
    path.chmod(0o600)


class ActorAcceptance:
    def __init__(self, profile: str, timeout: float, interval: float, output: Path | None) -> None:
        self.profile, self.timeout, self.interval, self.output = profile, timeout, interval, output
        self.prefix = "actor-smoke-" + uuid4().hex
        self.client: Client | None = None
        self.generation: str | None = None
        self.active: dict[str, Any] | None = None
        self.opening: dict[str, Any] | None = None
        self.closing: dict[str, Any] | None = None
        self.unknown_write: dict[str, str] | None = None
        self.report: dict[str, Any] = {
            "passed": False, "transport": "stdio", "profile_id": profile,
            "request_prefix": self.prefix, "operations": [], "requests": [],
            "actor_control_verified": False, "game_client_verified": False,
            "model_called": False, "cleanup": {"verified": False, "state": "NOT_STARTED"},
        }
        self.evidence_started = False
        self.save()

    def save(self) -> None:
        self.report["recovery"] = {
            "active_session": None if self.active is None else {
                "session_id": self.active["session_id"],
                "session_generation": self.active["session_generation"],
            },
            "opening_operation": None if self.opening is None else self.opening.get("operation_id"),
            "closing_operation": None if self.closing is None else self.closing.get("operation_id"),
            "unknown_write": self.unknown_write,
        }
        try:
            checkpoint(self.output, self.report)
            self.evidence_started = True
        except OSError:
            if not self.evidence_started:
                raise  # No actor operation may start without its initial recovery journal.
            # Losing an evidence disk must not prevent native actor cleanup.
            self.report.update(passed=False, evidence_error="CHECKPOINT_WRITE_FAILED")

    async def call(self, name: str, args: dict[str, Any], *, write: bool = False) -> dict[str, Any]:
        check(self.client is not None, "CLIENT_UNAVAILABLE")
        request_id = args.get("request_id")
        if write:
            self.unknown_write = {"tool": name, "request_id": request_id}
            self.report["requests"].append(dict(self.unknown_write))
            self.save()
        response = await self.client.call_tool(name, args)
        value = response.structured_content
        if response.is_error:
            if (write and isinstance(value, dict) and value.get("request_id") == request_id
                    and isinstance(value.get("error"), dict)
                    and value["error"].get("outcome_unknown") is False):
                # A definite refusal did not start this write. Existing known actors
                # can still be closed, without replaying the refused operation.
                self.unknown_write = None
                self.save()
            raise ActorSmokeFailure("MCP_CALL_FAILED")
        check(isinstance(value, dict), "MCP_CALL_FAILED")
        if write:
            check(value.get("request_id") == request_id, "WRITE_REQUEST_ID_MISMATCH")
            kind = {"actor_open": "OPEN", "actor_close": "CLOSE"}.get(name, value.get("kind"))
            check(kind in {"OPEN", "CLOSE"}, "OPERATION_KIND_INVALID")
            # A reply is only a recoverable receipt after its operation and session
            # identity have been validated. Keep the request key on malformed replies.
            self.operation(value, kind)
        if "operation_id" in value:
            check(isinstance(value.get("operation_id"), str) and bool(value["operation_id"]), "MISSING_OPERATION_ID")
            self.report["operations"].append({
                key: value.get(key) for key in [
                    "operation_id", "kind", "status", "session_id", "session_generation",
                    "profile_id", "runtime_generation", "cancel_requested",
                ]
            })
        if write:
            self.unknown_write = None
        self.save()
        return value

    def operation(self, value: dict[str, Any], kind: str, operation_id: str | None = None) -> None:
        check(type(value.get("schema_version")) is int and value["schema_version"] == 1, "OPERATION_SCHEMA_INVALID")
        check(value.get("kind") == kind and value.get("profile_id") == self.profile, "OPERATION_IDENTITY_MISMATCH")
        check(isinstance(value.get("operation_id"), str) and bool(value["operation_id"]), "MISSING_OPERATION_ID")
        if operation_id is not None:
            check(value["operation_id"] == operation_id, "OPERATION_ID_MISMATCH")
        check(self.generation is not None and value.get("runtime_generation") == self.generation, "RUNTIME_CHANGED")
        check(isinstance(value.get("session_id"), str) and bool(value["session_id"]), "MISSING_SESSION_ID")
        check(type(value.get("session_generation")) is int and value["session_generation"] > 0, "SESSION_GENERATION_INVALID")
        session = value.get("session")
        check(isinstance(session, dict), "MISSING_SESSION")
        for key in ["session_id", "session_generation", "profile_id", "runtime_generation"]:
            check(session.get(key) == value[key], "SESSION_IDENTITY_MISMATCH")
        check(value.get("status") in TERMINAL | PENDING, "UNKNOWN_OPERATION_STATE")

    async def wait(self, receipt: dict[str, Any], kind: str) -> dict[str, Any]:
        self.operation(receipt, kind)
        deadline = time.monotonic() + self.timeout
        operation_id = receipt["operation_id"]
        while True:
            value = await self.call("operation_get", {"operation_id": operation_id, "since_cursor": 0, "limit": 100})
            self.operation(value, kind, operation_id)
            if value["status"] in TERMINAL:
                return value
            check(time.monotonic() < deadline, "OPERATION_TIMEOUT")
            await asyncio.sleep(self.interval)

    def opened_actor(self, value: dict[str, Any]) -> dict[str, Any]:
        self.operation(value, "OPEN")
        check(value["status"] == "SUCCEEDED", "OPEN_NOT_SUCCEEDED")
        check(value.get("result", {}).get("observed_outcome") == "native_actor_online", "NATIVE_LOGIN_UNVERIFIED")
        session = value["session"]
        check(session.get("state") == "OPEN" and session.get("actor_current") is True, "SESSION_NOT_OPEN")
        actor = session.get("actor")
        check(isinstance(actor, dict), "MISSING_NATIVE_ACTOR")
        for key in ["character_id", "account_id"]:
            check(type(actor.get(key)) is int and actor[key] > 0, "NATIVE_ACTOR_ID_INVALID")
        check(actor.get("profile_id") == self.profile, "ACTOR_PROFILE_MISMATCH")
        for key in ["logged_in_world", "client_logged_in", "is_current", "persisted"]:
            check(actor.get(key) is True, "NATIVE_ACTOR_STATE_UNVERIFIED")
        check(actor.get("is_bot") is False, "ACTOR_IS_ENVIRONMENT_BOT")
        check(all(key in actor for key in PERSISTENT_FIELDS), "PERSISTENCE_SNAPSHOT_INCOMPLETE")
        require_persistence_summaries(actor)
        original = value.get("result", {}).get("actor")
        check(isinstance(original, dict) and all(original.get(key) == actor[key]
              for key in ["character_id", "account_id", "profile_id", "world_id", "channel_id"]),
              "OPEN_RESULT_ACTOR_MISMATCH")
        return actor

    def closed(self, value: dict[str, Any]) -> None:
        self.operation(value, "CLOSE")
        result = value.get("result", {})
        check(value["status"] == "SUCCEEDED" and result.get("observed_outcome") == "native_actor_closed", "CLOSE_NOT_SUCCEEDED")
        check(result.get("native_cleanup_verified") is True, "NATIVE_CLEANUP_UNVERIFIED")
        check(result.get("persisted_state_verified") is True, "NATIVE_SAVE_UNVERIFIED")
        check(value["session"].get("state") == "CLOSED" and value["session"].get("actor_current") is False,
              "SESSION_NOT_CLOSED")

    async def open(self, request_id: str) -> dict[str, Any]:
        self.opening = await self.call("actor_open", {"profile_id": self.profile, "request_id": request_id}, write=True)
        self.save()
        value = await self.wait(self.opening, "OPEN")
        self.opened_actor(value)
        self.active, self.opening = value, None
        self.save()
        return value

    async def close(self, request_id: str) -> dict[str, Any]:
        check(self.active is not None, "NO_ACTIVE_SESSION")
        self.closing = await self.call("actor_close", {
            "session_id": self.active["session_id"], "expected_generation": self.active["session_generation"],
            "request_id": request_id,
        }, write=True)
        self.save()
        value = await self.wait(self.closing, "CLOSE")
        self.closed(value)
        self.active = self.closing = None
        self.save()
        return value

    async def verify_scene(self, actor: dict[str, Any]) -> dict[str, Any]:
        context = require_scene_context(actor.get("scene"))
        check(context.get("runtime_generation") == self.generation, "ACTOR_SCENE_GENERATION_MISMATCH")
        check(all(context[key] == actor[key] for key in ["world_id", "channel_id", "map_id"]),
              "ACTOR_SCENE_LOCATION_MISMATCH")
        scene = await self.call("game_observe", {"scope": "scene", "scene_ref": context["scene_ref"], "limit": 500})
        check(require_generation(scene) == self.generation, "ACTOR_SCENE_RUNTIME_CHANGED")
        actual = require_scene_context(scene.get("context"))
        check(all(actual[key] == context[key] for key in CONTEXT_FIELDS), "ACTOR_SCENE_IDENTITY_MISMATCH")
        players = scene.get("players", {}).get("items")
        check(isinstance(players, list), "SCENE_PLAYERS_MISSING")
        check(any(row.get("id") == actor["character_id"] and row.get("is_bot") is False for row in players),
              "NATIVE_ACTOR_NOT_IN_SCENE")
        return {"context": actual, "actor_id": actor["character_id"], "native_membership_verified": True}

    async def run(self, client: Client) -> None:
        self.client = client
        names = sorted(tool.name for tool in (await client.list_tools()).tools)
        caps = await self.call("game_capabilities", {})
        self.report["enabled_tools"] = require_capabilities(caps, names, actors=True)
        self.generation = require_generation(caps)
        self.report.update(source=caps["source"], runtime_generation=self.generation)

        first_request = self.prefix + ":open1"
        first = await self.open(first_request)
        first_actor = deepcopy(self.opened_actor(first))
        self.report["first_actor"] = first_actor
        self.report["scene"] = await self.verify_scene(first_actor)
        duplicate = await self.call("actor_open", {"profile_id": self.profile, "request_id": first_request}, write=True)
        if duplicate.get("operation_id") != first["operation_id"] or duplicate.get("session_id") != first["session_id"]:
            self.unknown_write = {"tool": "actor_open", "request_id": first_request}
        self.operation(duplicate, "OPEN", first["operation_id"])
        check(duplicate["session_id"] == first["session_id"], "DUPLICATE_OPEN_CREATED_SESSION")
        # Use the last current snapshot, not the initial queued response, as the save baseline.
        current = await self.call("operation_get", {"operation_id": first["operation_id"]})
        first_actor = deepcopy(self.opened_actor(current))
        first_close = await self.close(self.prefix + ":close1")
        self.report["first_close"] = first_close["result"]
        closed_replay = await self.call("actor_open", {"profile_id": self.profile, "request_id": first_request}, write=True)
        if (closed_replay.get("operation_id") != first["operation_id"]
                or closed_replay.get("session", {}).get("state") != "CLOSED"):
            self.unknown_write = {"tool": "actor_open", "request_id": first_request}
        self.operation(closed_replay, "OPEN", first["operation_id"])
        check(closed_replay["session"].get("state") == "CLOSED"
              and closed_replay["session"].get("actor_current") is False, "CLOSED_REQUEST_REOPENED_ACTOR")

        second = await self.open(self.prefix + ":open2")
        second_actor = self.opened_actor(second)
        check(second["session_id"] != first["session_id"], "REOPEN_REUSED_CLOSED_SESSION")
        check(all(first_actor[key] == second_actor[key] for key in PERSISTENT_FIELDS), "PERSISTED_ACTOR_CHANGED")
        self.report["reopened_actor"] = deepcopy(second_actor)
        self.report["second_close"] = (await self.close(self.prefix + ":close2"))["result"]
        self.report.update(
            actor_control_verified=True,
            persistence_comparison={"fields": list(PERSISTENT_FIELDS), "summary_scope": "bounded inventory and quest id/status projections"},
            duplicate_request_verified=True,
            closed_request_replay_verified=True,
        )
        self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED"}
        self.report["passed"] = "evidence_error" not in self.report
        self.save()

    async def cleanup(self) -> None:
        if self.active is None and self.opening is None and self.unknown_write is None:
            if self.report["cleanup"]["state"] == "NOT_STARTED":
                self.report["cleanup"] = {"verified": True, "state": "NO_ACTOR_OPERATION_STARTED"}
                self.save()
            return
        if self.unknown_write is not None:
            self.report["cleanup"] = {"verified": False, "state": "UNKNOWN_WRITE_REQUIRES_SAME_ID_RECOVERY"}
            self.save()
            return
        try:
            if self.closing is not None:
                closed = await self.wait(self.closing, "CLOSE")
                self.closed(closed)
                self.active = self.closing = None
            elif self.opening is not None:
                await self.call("operation_cancel", {
                    "operation_id": self.opening["operation_id"], "request_id": self.prefix + ":cleanup-cancel",
                }, write=True)
                result = await self.wait(self.opening, "OPEN")
                if result["status"] == "CANCELLED":
                    check(result["session"].get("state") == "CANCELLED"
                          and result["session"].get("actor_current") is False, "CANCEL_CLEANUP_UNVERIFIED")
                    self.opening = None
                elif result["status"] == "SUCCEEDED":
                    check(result["session"].get("state") == "OPEN"
                          and result["session"].get("actor_current") is True, "OPEN_CLEANUP_UNVERIFIED")
                    self.active, self.opening = result, None
                    await self.close(self.prefix + ":cleanup-close")
                else:
                    raise ActorSmokeFailure("OPEN_CLEANUP_UNVERIFIED")
            else:
                await self.close(self.prefix + ":cleanup-close")
            self.report["cleanup"] = {"verified": True, "state": "NATIVE_CLOSED_OR_CANCELLED"}
        except Exception:
            self.report["cleanup"] = {"verified": False, "state": "NATIVE_CLEANUP_UNCONFIRMED"}
        self.save()


async def run_actor_smoke(
    config_path: Path, *, profile_id: str, expected_base_url: str,
    output: Path | None = None, operation_timeout: float = 60, poll_interval: float = 0.1,
) -> dict[str, Any]:
    config = load_config(config_path)
    validate_identifier(profile_id, "profile_id", PROFILE_IDENTIFIER)
    expected = BridgeConfig(username="target-check", password="target-check", base_url=expected_base_url).base_url
    if config.base_url != expected:
        raise ActorSmokeFailure("EXPLICIT_TARGET_MISMATCH")
    if not math.isfinite(operation_timeout) or not 0.1 <= operation_timeout <= 180:
        raise ActorSmokeFailure("INVALID_OPERATION_TIMEOUT")
    if not math.isfinite(poll_interval) or not 0.01 <= poll_interval <= 5:
        raise ActorSmokeFailure("INVALID_POLL_INTERVAL")
    acceptance = ActorAcceptance(profile_id, operation_timeout, poll_interval, output)
    params = StdioServerParameters(
        command=sys.executable,
        args=["-m", "solo_acceptance_mcp", "--config", str(config_path.resolve())],
        cwd=Path(__file__).resolve().parents[2],
    )
    try:
        async with Client(params, read_timeout_seconds=config.timeout_seconds * 4 + 5) as client:
            try:
                await acceptance.run(client)
            except Exception as failure:
                acceptance.report["failure"] = str(failure) if isinstance(failure, ActorSmokeFailure) else "PROTOCOL_OR_RESPONSE_FAILURE"
                acceptance.report["passed"] = False
            finally:
                await acceptance.cleanup()
    except Exception:
        acceptance.report.update(passed=False, failure="MCP_TRANSPORT_FAILURE")
        if acceptance.active is not None or acceptance.opening is not None or acceptance.unknown_write is not None:
            acceptance.report["cleanup"] = {"verified": False, "state": "TRANSPORT_INTERRUPTED_USE_CHECKPOINT"}
    if "evidence_error" in acceptance.report:
        acceptance.report["passed"] = False
    acceptance.save()
    return acceptance.report


def main() -> None:
    parser = argparse.ArgumentParser(description="显式指定目标的 MCP 原生验收角色生命周期验证（会写专用fixture）")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-base-url", required=True)
    parser.add_argument("--output", type=Path, required=True, help="保存逐步恢复ID与最终证据的本地JSON")
    parser.add_argument("--operation-timeout", type=float, default=60)
    args = parser.parse_args()
    try:
        report = asyncio.run(run_actor_smoke(
            args.config, profile_id=args.profile, expected_base_url=args.expected_base_url,
            output=args.output, operation_timeout=args.operation_timeout,
        ))
    except Exception:
        parser.exit(1, "角色验收未启动；请检查显式目标、profile和本地配置。\n")
    print(json.dumps({"passed": report["passed"], "report": str(args.output), "cleanup": report["cleanup"]}, ensure_ascii=False))
    if not report["passed"]:
        parser.exit(1)


if __name__ == "__main__":
    main()
