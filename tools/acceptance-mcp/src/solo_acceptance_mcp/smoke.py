"""Exercise the real stdio protocol with the official SDK, without a model."""
from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
import re
import sys
from typing import Any

from mcp import Client, StdioServerParameters

from .bridge import ACTION_NAMES, load_config


CONTEXT_FIELDS = (
    "scene_ref", "world_id", "channel_id", "map_id", "map_instance_key", "runtime_key"
)
OBSERVATION_TOOLS = frozenset({"game_capabilities", "game_observe"})
ACTOR_TOOLS = frozenset({"actor_open", "actor_close", "operation_get", "operation_cancel"})
ACTOR_IMPLEMENTED_TOOLS = OBSERVATION_TOOLS | ACTOR_TOOLS
BASIC_IMPLEMENTED_TOOLS = ACTOR_IMPLEMENTED_TOOLS | {"game_act"}
COMMAND_TOOLS = frozenset({"game_command_help", "game_command"})
IMPLEMENTED_TOOLS = BASIC_IMPLEMENTED_TOOLS | COMMAND_TOOLS


def require_capabilities(capabilities: dict[str, Any], names: list[str], *, actors: bool = False, chat: bool = False) -> list[str]:
    """Accept implemented actor subsets and the optional paired command tools."""
    if len(names) != len(set(names)) or set(names) != IMPLEMENTED_TOOLS:
        raise RuntimeError("MCP 工具清单与当前已实现接口不一致。")
    enabled = capabilities.get("enabled_tools")
    if (
        capabilities.get("service") != "solomapling-acceptance"
        or capabilities.get("server_online") is not True
        or not isinstance(enabled, list)
        or any(not isinstance(name, str) for name in enabled)
        or len(enabled) != len(set(enabled))
        or set(enabled) - COMMAND_TOOLS not in (OBSERVATION_TOOLS, ACTOR_IMPLEMENTED_TOOLS, BASIC_IMPLEMENTED_TOOLS)
        or set(enabled) & COMMAND_TOOLS not in (set(), COMMAND_TOOLS)
    ):
        raise RuntimeError("实际验收桥没有返回合法的已开放能力。")
    expected_round = "R2a" if ACTOR_TOOLS <= set(enabled) else "R1"
    if capabilities.get("implementation_round") != expected_round:
        raise RuntimeError("验收桥迭代版本与实际开放工具不一致。")
    if actors and not ACTOR_TOOLS <= set(enabled):
        raise RuntimeError("当前验收桥没有开放原生角色控制，未执行写操作。")
    actions = capabilities.get("actions", [])
    if "game_act" in enabled:
        control = capabilities.get("actor_control")
        if (not isinstance(actions, list) or any(not isinstance(value, str) for value in actions)
                or len(actions) != len(set(actions))
                or not (set(actions) in ({"chat"}, {"chat", "npc_center_open", "npc_cancel"},
                                         {"chat", "npc_center_open", "npc_cancel", "npc_reply"})
                        or set(ACTION_NAMES[:5]) <= set(actions) <= set(ACTION_NAMES))
                or not isinstance(control, dict) or control.get("actions") != actions):
            raise RuntimeError("角色动作与实际工具能力不一致。")
    elif actions != []:
        raise RuntimeError("未开放动作工具的验收桥不能公告聊天能力。")
    if chat and "game_act" not in enabled:
        raise RuntimeError("当前验收桥没有开放普通聊天动作，未执行写操作。")
    require_generation(capabilities)
    source = capabilities.get("source")
    if (
        not isinstance(source, dict)
        or source.get("available") is not True
        or any(not isinstance(source.get(key), str)
               or re.fullmatch(r"[0-9a-fA-F]{40}", source[key]) is None
               for key in ["soloGitRevision", "beidouRevision"])
    ):
        raise RuntimeError("实际验收桥没有有效的源码身份。")
    return sorted(enabled)


def require_generation(snapshot: dict[str, Any]) -> str:
    generation = snapshot.get("runtime_generation")
    if type(snapshot.get("schema_version")) is not int or snapshot["schema_version"] != 1:
        raise RuntimeError("快照没有有效的 R1 schema 版本。")
    if not isinstance(generation, str) or not generation.strip():
        raise RuntimeError("快照没有实际运行代次。")
    return generation


def require_scene_context(context: Any) -> dict[str, Any]:
    if not isinstance(context, dict):
        raise RuntimeError("缺少真实场景身份。")
    for key in ["scene_ref", "map_instance_key", "runtime_key"]:
        if not isinstance(context.get(key), str) or not context[key].strip():
            raise RuntimeError("真实场景身份字段不完整。")
    for key in ["world_id", "channel_id", "map_id"]:
        if type(context.get(key)) is not int or context[key] < (1 if key == "channel_id" else 0):
            raise RuntimeError("真实场景编号无效。")
    return context


def first_scene(world: dict[str, Any]) -> dict[str, Any]:
    scenes = world.get("scenes")
    if not isinstance(scenes, dict) or not isinstance(scenes.get("items"), list) or not scenes["items"]:
        raise RuntimeError("实际 world 快照没有已加载场景，未完成 scene 观察验证。")
    return require_scene_context(scenes["items"][0])


async def run_smoke(config_path: Path, *, limit: int = 25) -> dict[str, Any]:
    config = load_config(config_path)
    params = StdioServerParameters(
        command=sys.executable,
        args=["-m", "solo_acceptance_mcp", "--config", str(config_path.resolve())],
        cwd=Path(__file__).resolve().parents[2],
        env={"PYTHONPATH": str(Path(__file__).resolve().parents[1])},
    )
    failure: RuntimeError | None = None
    report: dict[str, Any] = {}
    async with Client(params, read_timeout_seconds=config.timeout_seconds * 4 + 5) as client:
        # Keep our semantic failure outside the SDK's AnyIO TaskGroup shutdown;
        # otherwise it is wrapped in transport ExceptionGroups.
        try:
            report = await inspect_running_client(client, limit)
        except RuntimeError as error:
            failure = error
    if failure is not None:
        raise failure
    return report


async def inspect_running_client(client: Client, limit: int) -> dict[str, Any]:
    tools = await client.list_tools()
    names = sorted(tool.name for tool in tools.tools)

    async def call(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        response = await client.call_tool(name, arguments)
        if response.is_error or not isinstance(response.structured_content, dict):
            raise RuntimeError("MCP 观察调用未成功；检查验收桥状态与鉴权。")
        return response.structured_content

    capabilities = await call("game_capabilities", {})
    enabled = require_capabilities(capabilities, names)
    generation = require_generation(capabilities)
    world = await call("game_observe", {"scope": "world", "limit": limit})
    if require_generation(world) != generation:
        raise RuntimeError("capabilities 与 world 不属于同一运行代次，请重新观察。")
    selected = first_scene(world)
    scene = await call("game_observe", {"scope": "scene", "scene_ref": selected["scene_ref"], "limit": limit})
    context = require_scene_context(scene.get("context"))
    if (
        require_generation(scene) != generation
        or any(context[key] != selected[key] for key in CONTEXT_FIELDS)
    ):
        raise RuntimeError("scene 返回的实际场景身份或代次不一致。")
    return {
        "passed": True,
        "transport": "stdio",
        "tools": names,
        "enabled_tools": enabled,
        "capabilities": capabilities,
        "world": world,
        "scene": scene,
        "model_called": False,
        "actor_control_verified": False,
        "game_client_verified": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="无模型的 SoloMapling MCP stdio 冒烟")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=25)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not 1 <= args.limit <= 500:
        parser.error("limit 必须在 1～500 之间。")
    try:
        report = asyncio.run(run_smoke(args.config, limit=args.limit))
    except Exception:
        # CLI boundary: SDK/transport failures must not dump private configuration
        # or the child process's request data in an exception traceback.
        parser.exit(1, "MCP 冒烟未通过；请检查配置、验收桥和后端状态。\n")
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
        args.output.chmod(0o600)
        print(json.dumps({"passed": True, "report": str(args.output)}, ensure_ascii=False))
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()

