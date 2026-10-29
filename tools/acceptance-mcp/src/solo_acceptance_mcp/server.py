"""Official MCP SDK 2.x observation, actor, and administrator debug tools."""
from __future__ import annotations

import argparse
from contextlib import asynccontextmanager
import json
from pathlib import Path
from typing import Annotated, Any, Literal

from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from pydantic import BaseModel, ConfigDict, Field

from . import __version__
from .bridge import (
    BridgeClient, BridgeConfig, BridgeError, ConfigurationError, EQUIPMENT_SLOTS, MAX_LONG, MAX_INT, MAX_COMMAND_CHARACTERS,
    SCRIPT_MAX_SOURCE_UNITS, SCRIPT_DEFAULT_TIMEOUT_MS, SCRIPT_MAX_TIMEOUT_MS, load_config, validate_action_request,
    validate_command_request, validate_script_run,
)


Identifier = Annotated[str, Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.-]+$", strict=True)]
ProfileId = Annotated[str, Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.-]+$", strict=True)]
RequestId = Annotated[str, Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.:-]+$", strict=True)]
Generation = Annotated[int, Field(ge=1, le=MAX_LONG, strict=True)]
Cursor = Annotated[int, Field(ge=0, le=MAX_LONG, strict=True)]
WorldId = Annotated[int, Field(ge=0, le=255, strict=True)]
ChannelId = Annotated[int, Field(ge=1, le=255, strict=True)]
Limit = Annotated[int, Field(ge=1, le=500, strict=True)]
NativeScriptEntry = Annotated[
    str, Field(min_length=1, max_length=128, pattern=r"^[A-Za-z_$][A-Za-z0-9_$]{0,127}$", strict=True),
]
InventorySlot = Annotated[int, Field(ge=1, le=127, strict=True)]
EquippedSlot = Literal[*EQUIPMENT_SLOTS]
ObjectId = Annotated[int, Field(ge=1, le=MAX_INT, strict=True)]
CommandText = Annotated[str, Field(min_length=1, max_length=MAX_COMMAND_CHARACTERS, strict=True)]


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
    source_slot: InventorySlot
    target_slot: InventorySlot


class MoveParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    direction: Literal["LEFT", "RIGHT"]
    duration_ms: Annotated[int, Field(ge=100, le=3000, strict=True)]


class StopParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class AttackParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    target_oid: ObjectId


class PickupParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    object_id: ObjectId


class DropParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    inventory_type: Literal["EQUIP", "USE", "SETUP", "ETC", "CASH"]
    source_slot: InventorySlot
    quantity: Annotated[int, Field(ge=1, le=32767, strict=True)]


class UseItemParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    source_slot: InventorySlot


class EquipParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    source_slot: InventorySlot
    target_slot: EquippedSlot


class UnequipParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    source_slot: EquippedSlot
    target_slot: InventorySlot


class ExpectedContext(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    scene_ref: Identifier
    runtime_generation: Identifier


CHAT_ARGUMENTS = frozenset({"session_id", "expected_generation", "request_id", "action", "parameters", "expected_context"})
COMMAND_ARGUMENTS = frozenset({"profile_id", "request_id", "command", "expected_command_generation"})
SCRIPT_RUN_REQUIRED = frozenset({"session_id", "expected_generation", "request_id", "kind", "operation"})
SCRIPT_RUN_ARGUMENTS = SCRIPT_RUN_REQUIRED | {"source", "parameters", "timeout_ms", "context_id", "options", "entry", "args", "since_output"}


class AcceptanceMCPServer(MCPServer):
    async def list_tools(self):
        tools = await super().list_tools()
        for tool in tools:
            if tool.name in {"game_command", "script_run"}:
                tool.input_schema = {**tool.input_schema, "additionalProperties": False}
            if tool.name == "game_act":
                tool.input_schema = {**tool.input_schema, "additionalProperties": False, "allOf": [{"oneOf": [
                    {"properties": {"action": {"const": action},
                                    "parameters": {"$ref": "#/$defs/" + model}}}
                    for action, model in [("chat", "ChatParameters"), ("npc_center_open", "CenterOpenParameters"),
                                          ("npc_cancel", "NpcCancelParameters"),
                                          ("inventory_move", "InventoryMoveParameters"),
                                          ("move", "MoveParameters"), ("stop", "StopParameters"),
                                          ("attack", "AttackParameters"), ("pickup", "PickupParameters"),
                                          ("drop", "DropParameters"), ("use_item", "UseItemParameters"),
                                          ("equip", "EquipParameters"), ("unequip", "UnequipParameters")]
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
        elif name == "game_command":
            try:
                if set(arguments) != COMMAND_ARGUMENTS:
                    raise BridgeError("INVALID_ARGUMENTS", "game_command 参数缺失或包含额外字段。")
                validate_command_request(**arguments)
            except BridgeError as failure:
                return result(failure.public_data(), is_error=True)
        elif name == "script_run":
            if not SCRIPT_RUN_REQUIRED <= set(arguments) or set(arguments) - SCRIPT_RUN_ARGUMENTS:
                return result({"error": {"code": "INVALID_ARGUMENTS", "message": "script_run 参数缺失或包含额外字段。",
                                         "retryable": False}}, is_error=True)
            try:
                validate_script_run(**arguments)
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
                      "取消操作不等于结束NPC对话。game_act 支持普通聊天、已审NPC回复、背包移位，以及受控移动/停止、单次近战普通攻击、库存物品拾取/丢弃、普通HP/MP药剂使用和装备穿脱；实际能力以桥公告为准。"
                      "基本动作由同一角色运行体执行；不接收任意伤害、技能、坐标或封包，不开放礼包accept、任意NPC、未审查选项或自治通关工具。"
                      "script_run 在同一角色运行体执行有期限的北斗脚本，执行中通过MCP进度通知实时交付stdout/stderr，最终返回结果；根据能力公告及随源码技能选择真实北斗环境与原入口。"
                      "持久 context_id 用于后续回调或关闭，原生副作用按实际结果核验，不由工具返回成功推导玩法完成。"
                      "北斗脚本提供 executeCommand(commandText) 通用执行函数，与 game_command 共用原生handle；权限沿原生，RETURNED不代表具体命令效果。"
                      "game_command 向专用 profile 原样转发完整命令文本，与脚本 executeCommand 共用原生实现；命令及脚本用法通过随源码技能查阅。"
                      "权限沿原生检查；RETURNED仅表示原生处理器返回，不证明命令执行成功或产生玩法效果。"),
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
    destructive_annotations = ToolAnnotations(
        read_only_hint=False, destructive_hint=True, idempotent_hint=True, open_world_hint=True,
    )

    @server.tool(annotations=read_annotations)
    async def game_capabilities() -> CallToolResult:
        """读取验收桥实际版本、源码身份、在线状态及已开放能力。"""
        try:
            return result(await bridge.capabilities())
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=destructive_annotations)
    async def game_command(
        profile_id: ProfileId, request_id: RequestId, command: CommandText,
        expected_command_generation: Identifier,
    ) -> CallToolResult:
        """对已登记在线 profile 原样执行完整原生命令文本，与脚本 executeCommand 共用实现，不按命令名筛选。语法和 GM 权限沿原生检查。RETURNED只证明原生void处理器已返回，权限拒绝也可能正常返回，实际效果须读取反馈或状态确认。先从能力公告取得 command_generation 作为 expected_command_generation，并提供 request_id；同ID同参数恢复原结果。超时或UNKNOWN只用原ID和原代次恢复，不换ID或刷新代次重发。"""
        try:
            return result(await bridge.command(profile_id, request_id=request_id, command=command,
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

    @server.tool(annotations=destructive_annotations)
    async def actor_close(
        session_id: Identifier, expected_generation: Generation, request_id: RequestId,
    ) -> CallToolResult:
        """排队正常退出专用角色，可能触发原生队伍/副本离开；仅在清理与存档核实后确认关闭。"""
        try:
            return result(await bridge.actor_close(session_id, expected_generation, request_id=request_id))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=destructive_annotations)
    async def game_act(
        session_id: Identifier, expected_generation: Generation, request_id: RequestId,
        action: Literal["chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move",
                        "move", "stop", "attack", "pickup", "drop", "use_item", "equip", "unequip"],
        parameters: (ChatParameters | CenterOpenParameters | NpcCancelParameters | NpcSelectParameters
                     | NpcDeclineParameters | InventoryMoveParameters | MoveParameters | StopParameters
                     | AttackParameters | PickupParameters | DropParameters | UseItemParameters
                     | EquipParameters | UnequipParameters),
        expected_context: ExpectedContext,
    ) -> CallToolResult:
        """在实际actor场景排队受控动作，实际开放项及限制以game_capabilities为准。move仅在连续可行走foothold有界步行，stop停止当前移动；attack按basic_melee_v1由运行体计算单目标近战普攻，不接damage/skill。pickup/drop沿原生库存规则，use_item仅普通单角色HP/MP药剂，equip/unequip仅普通装备位；inventory_move只在同一背包正槽位间移位。NPC回复须来自当前完整offer及已审路径。超时查询原operation，不盲目重发。"""
        try:
            return result(await bridge.act(
                session_id, expected_generation, request_id=request_id, action=action,
                parameters=parameters.model_dump(), expected_context=expected_context.model_dump(),
            ))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=open_annotations)
    async def script_run(
        ctx: Context,
        session_id: Identifier, expected_generation: Generation, request_id: RequestId,
        kind: Literal["npc", "quest", "portal", "reactor", "event"],
        operation: Literal["start", "invoke", "close"],
        source: Annotated[str, Field(max_length=SCRIPT_MAX_SOURCE_UNITS, strict=True)] = "",
        parameters: dict[str, Any] | None = None,
        timeout_ms: Annotated[int, Field(ge=1, le=SCRIPT_MAX_TIMEOUT_MS, strict=True)] = SCRIPT_DEFAULT_TIMEOUT_MS,
        context_id: Identifier | None = None,
        options: dict[str, Any] = Field(default_factory=dict),
        entry: NativeScriptEntry | None = None,
        args: list[Any] = Field(default_factory=list),
        since_output: Cursor = 0,
    ) -> CallToolResult:
        """在当前角色运行体执行北斗脚本，通过MCP进度通知实时发送started/output/gap事件（message为JSON），最终返回执行结果。kind选择实际北斗环境，start创建上下文，invoke继续代码或入口，close收尾。输出事件带request_id/operation_id、seq、channel和text；since_output用于同请求恢复输出读取。source默认空，parameters为JSON参数；executeCommand沿原生权限。使用原request_id及相同执行参数恢复，本次执行结算和Context最终关闭分别核验。"""
        notification_sequence = 0

        async def forward(event: dict[str, Any]) -> None:
            nonlocal notification_sequence
            notification_sequence += 1
            await ctx.report_progress(notification_sequence, message=json.dumps(event, ensure_ascii=False))

        try:
            data = await bridge.script_run(
                session_id, expected_generation, request_id=request_id, kind=kind, operation=operation,
                source=source, parameters=parameters, timeout_ms=timeout_ms, context_id=context_id,
                options=options, entry=entry, args=args, since_output=since_output, on_event=forward,
            )
            return result(data, is_error=data.get("status") != "SUCCEEDED")
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=read_annotations)
    async def operation_get(
        operation_id: Identifier, since_cursor: Cursor = 0, limit: Limit = 100, since_output: Cursor = 0,
    ) -> CallToolResult:
        """读取操作状态、角色会话、生命周期事件及脚本输出窗口；since_cursor和since_output分别推进两种游标。"""
        try:
            return result(await bridge.operation_get(operation_id, since_cursor=since_cursor, limit=limit,
                                                     since_output=since_output))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=destructive_annotations)
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

