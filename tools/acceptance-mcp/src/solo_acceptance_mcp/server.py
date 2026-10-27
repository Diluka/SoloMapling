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
from .bridge import BridgeClient, BridgeConfig, BridgeError, ConfigurationError, MAX_LONG, MAX_INT, load_config, validate_action_request


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


class ExpectedContext(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    scene_ref: Identifier
    runtime_generation: Identifier


CHAT_ARGUMENTS = frozenset({"session_id", "expected_generation", "request_id", "action", "parameters", "expected_context"})


class AcceptanceMCPServer(MCPServer):
    async def list_tools(self):
        tools = await super().list_tools()
        for tool in tools:
            if tool.name == "game_act":
                tool.input_schema = {**tool.input_schema, "additionalProperties": False, "allOf": [{"oneOf": [
                    {"properties": {"action": {"const": action},
                                    "parameters": {"$ref": "#/$defs/" + model}}}
                    for action, model in [("chat", "ChatParameters"), ("npc_center_open", "CenterOpenParameters"),
                                          ("npc_cancel", "NpcCancelParameters")]
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
                      "取消操作不等于结束NPC对话。game_act 支持 chat、中心打开、版本绑定取消及有限 npc_reply select/decline；实际能力以桥公告为准，不开放礼包accept、任意NPC、未审查选项、移动或通关工具。"),
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
        action: Literal["chat", "npc_center_open", "npc_cancel", "npc_reply"],
        parameters: ChatParameters | CenterOpenParameters | NpcCancelParameters | NpcSelectParameters | NpcDeclineParameters,
        expected_context: ExpectedContext,
    ) -> CallToolResult:
        """在实际 actor 场景排队 chat、中心首屏、最新版本取消或npc_reply的有限select/decline；回复必须来自当前真实完整offer及已审路径，截断菜单不可select。无accept、模板、脚本、原始封包。"""
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

