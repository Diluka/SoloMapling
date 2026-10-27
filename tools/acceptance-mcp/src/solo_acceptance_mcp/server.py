"""Official MCP SDK 2.x stdio adapter for the two R1 read-only tools."""
from __future__ import annotations

import argparse
from contextlib import asynccontextmanager
import json
from pathlib import Path
from typing import Annotated, Any, Literal

from mcp.server import MCPServer
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from pydantic import Field

from . import __version__
from .bridge import BridgeClient, BridgeConfig, BridgeError, ConfigurationError, load_config


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

    server = MCPServer(
        "solomapling-acceptance",
        version=__version__,
        instructions="读取实际 SoloMapling 游戏状态；R1 不提供角色创建、动作控制或任务通关能力。",
        log_level="WARNING",
        lifespan=lifespan,
    )
    annotations = ToolAnnotations(
        read_only_hint=True,
        destructive_hint=False,
        idempotent_hint=True,
        open_world_hint=True,
    )

    @server.tool(annotations=annotations)
    async def game_capabilities() -> CallToolResult:
        """读取验收桥实际版本、源码身份、在线状态及已开放能力。"""
        try:
            return result(await bridge.capabilities())
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    @server.tool(annotations=annotations)
    async def game_observe(
        scope: Literal["world", "scene"] = "world",
        scene_ref: str | None = None,
        limit: Annotated[int, Field(ge=1, le=500, strict=True)] = 100,
    ) -> CallToolResult:
        """只读观察 world 或已返回的 scene_ref；不会创建地图、角色或副本。"""
        try:
            return result(await bridge.observe(scope, scene_ref, limit))
        except BridgeError as failure:
            return result(failure.public_data(), is_error=True)

    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="SoloMapling 只读验收 MCP 服务")
    parser.add_argument("--config", type=Path, required=True, help="本地私密 JSON 配置文件")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
    except ConfigurationError as failure:
        parser.exit(2, f"SoloMapling MCP 配置错误：{failure}\n")
    build_server(config).run(transport="stdio")

