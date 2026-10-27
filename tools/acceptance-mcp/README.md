# SoloMapling 验收 MCP：R1

本工具使用官方 MCP Python SDK `2.3.0`，向本地 MCP 客户端提供两个只读工具：

- `game_capabilities`：读取实际验收桥版本、运行源码身份、后端在线状态和已开放能力。
- `game_observe`：观察 `world`，或用其返回的 `scene_ref` 观察一个真实已加载场景。

当前没有创建角色、移动、聊天、NPC 操作或任务脚本工具。后续接口与迭代计划见[基础方案](../../docs/mcp-acceptance/README.md)。

## 安装与启动

在本目录显式准备独立虚拟环境和锁定依赖：

```bash
uv sync --frozen
```

`uv.lock` 固定依赖，`.venv/` 不提交。工作区 MCP 启动应直接使用已准备好的 Python，避免每次启动解析依赖或访问网络：

```bash
.venv/bin/python -m solo_acceptance_mcp --config /absolute/private/solo-acceptance.json
```

配置文件只在本地保存，权限设为 `0600`，不要提交真实账号密码：

```json
{
  "base_url": "http://127.0.0.1:8686",
  "username": "your-admin-account",
  "password": "set-locally",
  "timeout_seconds": 10
}
```

`base_url` 默认 `http://127.0.0.1:8686`，只接受无路径、查询和内嵌凭据的 HTTP/HTTPS 服务地址。请求超时可设为 `0.1`～`30` 秒。凭据文件路径通过 `--config` 传入，工具不从环境变量读取或覆盖业务配置。

工作区可按实际绝对路径配置 stdio 入口：

```toml
[mcp_servers.solo_acceptance]
command = "/absolute/SoloMapling/tools/acceptance-mcp/.venv/bin/python"
args = ["-m", "solo_acceptance_mcp", "--config", "/absolute/private/solo-acceptance.json"]
cwd = "/absolute/SoloMapling/tools/acceptance-mcp"
startup_timeout_sec = 15
tool_timeout_sec = 45
enabled = true
```

配置文件有效、官方 SDK 客户端可调用、当前宿主工具目录已加载是三个独立检查；是否需要重新连接由实际宿主行为决定。

## 桥接契约

客户端先向正常管理端 `POST /auth/v1/login`，按原生 `{requestId,data}` 信封提交凭据，取得的 JWT 仅保留在进程内存。随后调用：

| MCP 工具 | 验收桥请求 |
|---|---|
| `game_capabilities()` | `GET /solo/acceptance/v1/capabilities` |
| `game_observe(scope="world", limit=100)` | `POST /solo/acceptance/v1/observe`，`data={scope,limit}` |
| `game_observe(scope="scene", scene_ref="…", limit=100)` | 同一 POST，`data={scope,scene_ref,limit}` |

桥响应采用北斗原生 `{code:20000,data:{…}}`。`limit` 为 `1`～`500` 的整数；`scene` 必须提供引用，`world` 不接受引用。工具不会按地图编号加载地图、创建实例或重新构造一个场景。

成功返回原生 `data` 的 `structured_content` 和对应 JSON 文本。错误返回 `is_error=true` 与固定的结构化错误码，不回显后台错误正文、JWT、凭据或配置内容。HTTP `400/403/404/409/410/503` 分别表示参数、权限、未启用、读取代次变化、场景失效和后端离线；其他 HTTP/业务错误仍保持失败。

HTTP `401` 最多重新登录一次；并发请求共享令牌刷新。观察 POST 的重发保持同一个 `requestId`。超时和连接错误不会自动重放，可由调用方重新发起只读观察。重定向不会跟随，请求不使用进程环境中的 HTTP 代理。

## 测试与实际协议冒烟

本地测试只连接随机端口的 HTTP 桩，不连接游戏数据库、不调用模型：

```bash
.venv/bin/python -m unittest discover -s tests -v
```

测试覆盖正常鉴权与信封、令牌复用/单次刷新/并发刷新、错误脱敏、超时与连接失败、参数和响应边界，以及官方 SDK 启动真实 stdio 子进程进行初始化、列工具和调用。

实际验收桥部署后，用同一官方 SDK 执行无模型冒烟：

```bash
.venv/bin/python -m solo_acceptance_mcp.smoke \
  --config /absolute/private/solo-acceptance.json \
  --output /absolute/evidence/mcp-smoke.json
```

它核对仅有两个 R1 工具、实际 bridge capabilities、有效源码 SHA、三次响应的非空相同运行代次，以及从 `world.scenes.items` 选择的真实场景和 scene 返回的全部六个身份字段。输出报告权限为 `0600`。缺失源码或代次、错误实例、没有已加载场景、桥被关闭或后端错误都会失败，不生成伪造成功结果。

stdio 冒烟通过只证明协议与只读观察链可用，不代表宿主已热加载工具、验收角色可控制或游戏客户端玩法已通过。源码与打包测试、实际服务观察、角色动作和真实客户端验证分别留证。

本目录代码随工程采用 AGPL-3.0，见[根许可证](../../LICENSE)。
