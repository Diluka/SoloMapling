"""Official MCP SDK 2.x tools for observations and dedicated actor operations."""
from __future__ import annotations

import argparse
from contextlib import asynccontextmanager
import json
from pathlib import Path
from typing import Annotated, Any, Literal

from mcp.server import MCPServer
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from pydantic import BaseModel, ConfigDict, Field

from . import __version__
from .bridge import (BridgeClient, BridgeConfig, BridgeError, ConfigurationError, MAX_LONG, MAX_INT,
                     load_config, validate_action_request, validate_command_help, validate_command_request)


Identifier = Annotated[str, Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.-]+$", strict=True)]
ProfileId = Annotated[str, Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.-]+$", strict=True)]
RequestId = Annotated[str, Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.:-]+$", strict=True)]
Generation = Annotated[int, Field(ge=1, le=MAX_LONG, strict=True)]
Cursor = Annotated[int, Field(ge=0, le=MAX_LONG, strict=True)]
WorldId = Annotated[int, Field(ge=0, le=255, strict=True)]
ChannelId = Annotated[int, Field(ge=1, le=255, strict=True)]
Limit = Annotated[int, Field(ge=1, le=500, strict=True)]


class ChatParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    text: Annotated[str, Field(strict=True)]


class CenterOpenParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class NpcCancelParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    dialogue_version: Generation


class NpcSelectParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    dialogue_version: Generation
    kind: Literal["select"]
    selection: Annotated[int, Field(ge=0, le=MAX_INT, strict=True)]


class NpcDeclineParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    dialogue_version: Generation
    kind: Literal["decline"]


class InventoryMoveParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    inventory_type: Literal["EQUIP", "USE", "SETUP", "ETC", "CASH"]
    source_slot: Annotated[int, Field(ge=1, le=127, strict=True)]
    target_slot: Annotated[int, Field(ge=1, le=127, strict=True)]


class ExpectedContext(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    scene_ref: Identifier
    runtime_generation: Identifier


class GiveItemParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    item_id: Annotated[int, Field(ge=1, le=MAX_INT, strict=True)]
    quantity: Annotated[int, Field(ge=1, le=32767, strict=True)]


CHAT_ARGUMENTS = frozenset({"session_id", "expected_generation", "request_id", "action", "parameters", "expected_context"})
COMMAND_ARGUMENTS = frozenset({"profile_id", "request_id", "command", "parameters", "expected_command_generation"})


class AcceptanceMCPServer(MCPServer):
    async def list_tools(self):
        tools = await super().list_tools()
        for tool in tools:
            if tool.name in {"game_command", "game_command_help"}:
                tool.input_schema = {**tool.input_schema, "additionalProperties": False}
            if tool.name == "game_act":
                tool.input_schema = {**tool.input_schema, "additionalProperties": False, "allOf": [{"oneOf": [
                    {"properties": {"action": {"const": action},
                                    "parameters": {"$ref": "#/$defs/" + model}}}
                    for action, model in [("chat", "ChatParameters"), ("npc_center_open", "CenterOpenParameters"),
                                          ("npc_cancel", "NpcCancelParameters"),
                                          ("inventory_move", "InventoryMoveParameters")]
                ] + [{"properties": {"action": {"const": "npc_reply"}, "parameters": {"oneOf": [
                    {"$ref": "#/$defs/NpcSelectParameters"}, {"$ref": "#/$defs/NpcDeclineParameters"},
                ]}}}]}]}
        return tools

    async def call_tool(self, name: str, arguments: dict[str, Any], context: Any = None):
        # SDK 2.3 otherwise drops unknown top-level arguments before invoking a tool.
        if name == "game_act":
            if set(arguments) != CHAT_ARGUMENTS:
                return result({"error": {"code": "INVALID_ARGUMENTS", "message": "game_act 参数缺失或包含额外字段。", "retryable": False}},
                              is_error=True)
            try:
                # Reject raw input safely before SDK model errors can echo values.
                validate_action_request(**arguments)
            except BridgeError as failure:
                return result(failure.public_data(), is_error=True)
        elif name in {"game_command", "game_command_help"}:
            try:
                if name == "game_command":
                    if set(arguments) != COMMAND_ARGUMENTS:
                        raise BridgeError("INVALID_ARGUMENTS", "game_command 参数缺失或包含额外字段。")
                    validate_command_request(**arguments)
                else:
                    if set(arguments) - {"command"}:
                        raise BridgeError("INVALID_ARGUMENTS", "game_command_help 只接受可选 command。")
                    validate_command_help(**arguments)
            except BridgeError as failure:
                return result(failure.public_data(), is_error=True)
        return await super().call_tool(name, arguments, context)


def result(data: dict[str, Any], *, is_error: bool = False) -> CallToolResult:
    return CallToolResult(
        content=[TextContent(type="text", text=json.dumps(data, ensure_ascii=False))],
        structured_content=data,
        is_error=is_error,
    )


def build_server(config: BridgeConfig) -> MCPServer:
    bridge = BridgeClient(config)

    @asynccontextmanager
    async def lifespan(server: MCPServer):
        try:
            yield
        finally:
            await bridge.close()

    server = AcceptanceMCPServer(
        "solomapling-acceptance",
        version=__version__,
        instructions=("观察实际游戏状态并控制专用验收角色的登录/退出。"
                      "实际开放能力以 game_capabilities 为准；QUEUED 不等于已登录，"
                      "取消操作不等于结束NPC对话。game_act 支持 chat、中心打开、版本绑定取消、有限 npc_reply select/decline 及 inventory_move 正槽位背包移位；实际能力以桥公告为准，不开放礼包accept、任意NPC、未审查选项、移动或通关工具。"
                      "game_command_help 与 game_command 是独立管理员调试入口，按服务端实际配置向专用 profile 提供原生 give 物品能力，不经过角色运行体或 lane。"),
        log_level="WARNING",
        lifespan=lifespan,
    )
    read_annotations = ToolAnnotations(
        read_only_hint=True,
        destructive_hint=False,
        idempotent_hint=True,
        open_world_hint=True,
    )

    open_annotations = ToolAnnotations(
        read_only_hint=False, destructive_hint=False, idempotent_hint=True, open_world_hint=True,
    )
    cleanup_annotations = ToolAnnotations(
        read_only_hint=False, destructive_hint=True, idempotent_hint=True, open_world_hint=True,
    )

    @server.tool(annotations=read_annotations)
    async def game_capabilities() -> CallToolResult:
        """读取验收桥实际版本、源码身份、在线状态及已开放能力。"""
        try:
            return result(await bridge.capabilities())
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=read_annotations)
    async def game_command_help(command: Literal["give"] | None = None) -> CallToolResult:
        """查询实际开放的管理员调试指令及参数；省略 command 时列出目录。指令独立于角色运行体，实际开关和物品范围以服务端说明为准。"""
        try:
            return result(await bridge.command_help(command))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=open_annotations)
    async def game_command(
        profile_id: ProfileId, request_id: RequestId, command: Literal["give"],
        parameters: GiveItemParameters, expected_command_generation: Identifier,
    ) -> CallToolResult:
        """对已登记专用 profile 调用原生 give 物品管理服务，不经过 CharacterRuntime 或角色 lane。先从帮助取得 command_generation，作为 expected_command_generation；它独立于角色与世界代次。必须提供 request_id，同 ID 同参数恢复原结果、不重复发放。超时或未知结果只用原 ID 和原指令代次恢复，不能换新 ID 或刷新代次重发；重启后的旧指令代次会被拒绝。"""
        try:
            return result(await bridge.command(profile_id, request_id=request_id, command=command,
                                               parameters=parameters.model_dump(),
                                               expected_command_generation=expected_command_generation))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=read_annotations)
    async def game_observe(
        scope: Literal["world", "scene", "actor"] = "world",
        scene_ref: str | None = None,
        limit: Limit = 100,
        *, session_id: Identifier | None = None,
        expected_generation: Generation | None = None,
        since_cursor: Cursor = 0,
    ) -> CallToolResult:
        """只读观察 world、真实 scene_ref 或已打开 actor 会话的包事件；actor 的 since_cursor 是独立包序号，不是 operation 生命周期游标。"""
        try:
            return result(await bridge.observe(scope, scene_ref, limit, session_id=session_id,
                                              expected_generation=expected_generation, since_cursor=since_cursor))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=open_annotations)
    async def actor_open(
        profile_id: ProfileId,
        request_id: RequestId,
        world: WorldId | None = None,
        channel: ChannelId | None = None,
    ) -> CallToolResult:
        """为已配置专用 profile 排队登录；用同一 request_id 显式恢复重试，通过 operation_get 确认实际登录。"""
        try:
            return result(await bridge.actor_open(profile_id, request_id=request_id, world=world, channel=channel))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=cleanup_annotations)
    async def actor_close(
        session_id: Identifier, expected_generation: Generation, request_id: RequestId,
    ) -> CallToolResult:
        """排队正常退出专用角色，可能触发原生队伍/副本离开；仅在清理与存档核实后确认关闭。"""
        try:
            return result(await bridge.actor_close(session_id, expected_generation, request_id=request_id))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=open_annotations)
    async def game_act(
        session_id: Identifier, expected_generation: Generation, request_id: RequestId,
        action: Literal["chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move"],
        parameters: ChatParameters | CenterOpenParameters | NpcCancelParameters | NpcSelectParameters | NpcDeclineParameters | InventoryMoveParameters,
        expected_context: ExpectedContext,
    ) -> CallToolResult:
        """在实际 actor 场景排队 chat、中心首屏、最新版本取消、npc_reply 的有限 select/decline 或 inventory_move。移位仅接受同一背包中不同的正槽位，沿原生合并/交换规则，不穿脱、丢弃或自动整理；NPC 回复须来自当前真实完整 offer 及已审路径。实际开放能力以 game_capabilities 为准。"""
        try:
            return result(await bridge.act(
                session_id, expected_generation, request_id=request_id, action=action,
                parameters=parameters.model_dump(), expected_context=expected_context.model_dump(),
            ))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=read_annotations)
    async def operation_get(
        operation_id: Identifier, since_cursor: Cursor = 0, limit: Limit = 100,
    ) -> CallToolResult:
        """读取操作真实状态、角色会话和有界事件；读取成功不表示原生操作已成功。"""
        try:
            return result(await bridge.operation_get(operation_id, since_cursor=since_cursor, limit=limit))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=cleanup_annotations)
    async def operation_cancel(operation_id: Identifier, request_id: RequestId) -> CallToolResult:
        """请求取消排队或进行中的操作；之后查询状态，原生清理完成前不把请求当作已停止。"""
        try:
            return result(await bridge.operation_cancel(operation_id, request_id=request_id))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="SoloMapling 验收 MCP 服务")
    parser.add_argument("--config", type=Path, required=True, help="本地私密 JSON 配置文件")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
    except ConfigurationError as failure:
        parser.exit(2, f"SoloMapling MCP 配置错误：{failure}\n")
    build_server(config).run(transport="stdio")

