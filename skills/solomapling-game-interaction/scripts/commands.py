#!/usr/bin/env python3
"""查询北斗现有命令目录，并给出随附源码中的处理类位置。"""
from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
import sys
from uuid import uuid4


REPOSITORY = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPOSITORY / "tools/acceptance-mcp/src"))

from solo_acceptance_mcp.bridge import BridgeClient, BridgeError, ConfigurationError, load_config


async def query(arguments: argparse.Namespace) -> dict:
    data = {"pageNo": arguments.page, "pageSize": arguments.page_size}
    if arguments.syntax is not None:
        data["syntax"] = arguments.syntax
    async with BridgeClient(load_config(arguments.config)) as bridge:
        # 复用现有鉴权与错误脱敏；该原生 POST 只查询目录，不执行命令。
        page = await bridge._authorized_request(
            "POST", "/command/v1/getCommandListFromDB",
            {"requestId": str(uuid4()), "data": data},
        )
    for command in page["records"]:
        command["source_path"] = (
            "upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/command/commands/"
            f"gm{command['defaultLevel']}/{command['clazz']}.java"
        )
    return page


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="本地私密 MCP 连接配置")
    parser.add_argument("--syntax", help="按命令名称查询；省略时分页列出目录")
    parser.add_argument("--page", type=int, default=1)
    parser.add_argument("--page-size", type=int, default=100)
    arguments = parser.parse_args()
    try:
        result = asyncio.run(query(arguments))
    except BridgeError as failure:
        print(json.dumps(failure.public_data(), ensure_ascii=False), file=sys.stderr)
        return 1
    except ConfigurationError as failure:
        print(str(failure), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
