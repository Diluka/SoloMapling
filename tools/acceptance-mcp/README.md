# SoloMapling 验收 MCP：R1 / R2a

本工具使用官方 MCP Python SDK `2.3.0`。客户端实现六个工具，实际后端是否已开启角色控制，以 `game_capabilities.enabled_tools` 为准：

| 工具 | 用途 | 状态影响 |
|---|---|---|
| `game_capabilities` | 读取实际版本、运行源码身份、后端在线状态和已开放能力 | 只读 |
| `game_observe` | 观察 world 或一个真实已加载的 scene_ref | 只读，不加载地图或创建实例 |
| `actor_open` | 为已配置的专用 profile 排队执行原生登录 | 可能创建专用 fixture、更新登录状态；必须有 request_id |
| `actor_close` | 排队正常离线并核对清理和保存 | 可能触发原生队伍/副本离开；必须有 request_id |
| `operation_get` | 查询操作、当前角色会话及有界事件 | 只读，返回成功不代表操作已成功 |
| `operation_cancel` | 请求取消排队或进行中的操作 | 必须继续查询实际终态；必须有 request_id |

R1 桥默认只有两项观察能力；R2a 的四项角色/操作接口需要后端显式启用。没有 `game_act`、聊天、NPC、移动或任务脚本工具。后续计划见[基础方案](../../docs/mcp-acceptance/README.md)。

## 安装与启动

在本目录显式准备独立虚拟环境与锁定依赖：

```bash
uv sync --frozen
```

`uv.lock` 固定依赖，`.venv/` 不提交。MCP 启动直接使用已准备好的 Python，避免启动时解析依赖或联网：

```bash
.venv/bin/python -m solo_acceptance_mcp --config /absolute/private/solo-acceptance.json
```

本地配置文件设为 `0600`，不提交真实账号密码：

```json
{
  "base_url": "http://127.0.0.1:8686",
  "username": "your-admin-account",
  "password": "set-locally",
  "timeout_seconds": 10
}
```

`base_url` 默认 `http://127.0.0.1:8686`，只接受无路径、查询和内嵌凭据的 HTTP/HTTPS 地址。HTTP 超时为 `0.1`～`30` 秒。凭据仅供管理端鉴权，工具参数不接收角色账号密码或任意原生角色 ID。

工作区示例：

```toml
[mcp_servers.solo_acceptance]
command = "/absolute/SoloMapling/tools/acceptance-mcp/.venv/bin/python"
args = ["-m", "solo_acceptance_mcp", "--config", "/absolute/private/solo-acceptance.json"]
cwd = "/absolute/SoloMapling/tools/acceptance-mcp"
startup_timeout_sec = 15
tool_timeout_sec = 45
enabled = true
```

配置可解析、官方 SDK 可调用、当前宿主工具目录已加载是三个独立检查。宿主是否需要重连按实际情况记录。

## 原生鉴权与操作契约

客户端先向 `POST /auth/v1/login` 按 `{requestId,data}` 信封提交管理凭据，JWT 仅保留在内存。后续请求沿正常管理员权限：

| MCP 调用 | 验收桥请求 |
|---|---|
| `game_capabilities()` | `GET /solo/acceptance/v1/capabilities` |
| `game_observe(scope="world", limit=100)` | `POST /solo/acceptance/v1/observe`，data 为 scope/limit |
| `game_observe(scope="scene", scene_ref="…", limit=100)` | 同一 POST，data 加 scene_ref |
| `actor_open(profile_id="primary", request_id="open-unique-1")` | `POST /solo/acceptance/v1/actor/open`，data 为 profile_id 与可选 world/channel |
| `actor_close(session_id="…", expected_generation=1, request_id="close-unique-1")` | `POST /solo/acceptance/v1/actor/close` |
| `operation_get(operation_id="…", since_cursor=0, limit=100)` | `GET /solo/acceptance/v1/operation/{id}?since_cursor=0&limit=100` |
| `operation_cancel(operation_id="…", request_id="cancel-unique-1")` | `POST /solo/acceptance/v1/operation/{id}/cancel`，data 为空对象 |

成功读取北斗 `{code:20000,data:{…}}` 后，返回原生 data 的 `structured_content` 和 JSON 文本。HTTP、业务或参数失败返回 `is_error=true` 与固定错误码，不回显后端错误正文、JWT 或凭据。

三种写调用都要求调用方提供 `request_id`，它是原生信封的 `requestId`。客户端成功和 HTTP/传输错误都会保留该 ID。HTTP `401` 最多刷新一次 JWT 并重发同一信封；超时或连接错误不会自动重放写请求。写请求结果未知时返回 `outcome_unknown=true`、`retryable=false`，应先查询已有操作，或明确使用原请求 ID 恢复，不换新 ID 盲重试。无效参数在发 HTTP 前拒绝。

`actor_open` 只按后端明确配置的 profile 工作，可选 world/channel 必须符合后端 profile。打开返回 `QUEUED` 只表示已受理；查询到 `SUCCEEDED`、`observed_outcome=native_actor_online`、原生在线和实际场景身份后才确认登录。已关闭 session 的旧打开请求必须返回原操作，不能重新创建角色。

`actor_close` 同样可能先排队。需查询到原生关闭结果、`native_cleanup_verified=true` 和 `persisted_state_verified=true`，才能确认保存与离线。`operation_cancel` 仅请求取消；不能把 `CANCEL_REQUESTED` 当作已经停止或已清理。

角色和事件观察不改变已有游戏状态。`limit` 为 `1`～`500` 的严格整数，world 不接受 scene_ref；scene 必须使用实际返回的引用。游标为非负整数，超出缓冲范围由后端明确标记。R1 目录最多前500个场景、无分页；R2a 原生角色快照可直接带自己的 scene_ref，不受目录前500限制。

## 本地测试和只读协议冒烟

本地测试只用随机端口 HTTP 桩与 SDK stdio 子进程，不连接游戏数据库、不调用模型：

```bash
.venv/bin/python -m unittest discover -s tests -v
```

测试覆盖既有观察、JWT/信封、401 有界刷新、写请求 ID、超时不重放、错误脱敏、严格参数、六工具读写注解，以及官方 SDK 的真实 stdio 正反例。

实际后端的只读检查不打开角色：

```bash
.venv/bin/python -m solo_acceptance_mcp.smoke \
  --config /absolute/private/solo-acceptance.json \
  --output /absolute/evidence/mcp-observation.json
```

它检查客户端六工具及桥当前合法开放子集、有效源码 SHA、capabilities/world/scene 的非空同代次与完整场景身份。R1 后端仍可通过观察检查，报告不会把它当成角色控制已通过。

## 显式目标的原生角色验收

以下流程会创建或加载专用 fixture，并正常保存退出。独立实验必须传隔离服务的配置和地址，禁止用默认地址猜测目标：

```bash
.venv/bin/python -m solo_acceptance_mcp.actor_smoke \
  --config /absolute/private/solo-acceptance.validation.json \
  --profile primary \
  --expected-base-url http://127.0.0.1:18687 \
  --output /absolute/evidence/native-actor.json
```

三个目标参数与证据路径均为必填；配置中的地址必须与 `--expected-base-url` 一致。流程先检查实际角色能力，再执行：

1. 打开、查询实际原生在线与角色场景成员。
2. 用同一请求 ID 重试打开，确认操作和 session 不重复。
3. 关闭并核对原生清理和保存；旧打开请求不会让已关闭 session 复活。
4. 新请求重新打开，确认原生 CID/账号、成长字段与完整的有界背包、任务 ID/状态摘要连续。
5. 再次正常关闭，留下清理结果。

保存验证范围会写入报告：后端原生关键字段核实，加关闭后重新加载的公开摘要比较。它不等于验证所有任务计数或超过摘要上限的数据；缺失或截断的摘要不能以 `null == null` 蒙混通过。位置按原生 spawnpoint 恢复，不要求 x/y 完全相同。

流程逐步以 `0600` 检查点保存 request、operation 和 session 恢复标识。已知活动失败会尝试正常清理；同 ID 的明确拒绝不会阻止关闭此前已知的会话。写请求传输结果未知或返回无效操作收据时保留原 ID 并停止自动重放，报告清理未证实。关闭进程或取消 MCP 请求都不保证游戏角色已经离线，应按检查点查询原操作并恢复处理。没有通过的阶段保留失败结果，不覆写为成功。

角色控制通过仍不代表聊天、NPC 或 PQ 已实现，也不替代真实客户端协议、渲染和操控验收。本目录代码随工程采用 AGPL-3.0，见[根许可证](../../LICENSE)。
