# SoloMapling 验收 MCP：R1 / R2a

本工具使用官方 MCP Python SDK `2.3.0`。客户端实现七个工具，实际后端是否已开启角色控制和普通聊天，以 `game_capabilities.enabled_tools` 及 `actions` 为准：

| 工具 | 用途 | 状态影响 |
|---|---|---|
| `game_capabilities` | 读取实际版本、运行源码身份、后端在线状态和已开放能力 | 只读 |
| `game_observe` | 观察 world、真实 scene_ref 或已打开角色的包事件窗口 | 只读，不加载地图或创建实例 |
| `actor_open` | 为已配置的专用 profile 排队执行原生登录 | 可能创建专用 fixture、更新登录状态；必须有 request_id |
| `actor_close` | 排队正常离线并核对清理和保存 | 可能触发原生队伍/副本离开；必须有 request_id |
| `operation_get` | 查询操作、当前角色会话及有界事件 | 只读，返回成功不代表操作已成功 |
| `operation_cancel` | 请求取消排队或进行中的操作 | 必须继续查询实际终态；必须有 request_id |
| `game_act` | 在真实 actor 场景中排队普通 `chat` | 会产生原生聊天广播；必须有 request_id 和实际 expected_context |

R1 桥默认只有两项观察能力；R2a 的四项角色/操作接口需要后端显式启用。actor 包观察还需实际 capabilities 公告 `scopes` 中的 `actor` 和 `actor_control.packet_observation`。聊天另需实际七工具及顶层和 `actor_control` 中的 `actions=["chat"]`，不因客户端 schema 存在就当作已开启。没有 NPC、移动或任务脚本工具，SocialBot 完整互动仍待后续验收。后续计划见[基础方案](../../docs/mcp-acceptance/README.md)。

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
| `game_observe(scope="actor", session_id="…", expected_generation=1, since_cursor=0, limit=100)` | `POST /solo/acceptance/v1/actor/observe`，data 仅为这四个角色/窗口参数 |
| `actor_open(profile_id="primary", request_id="open-unique-1")` | `POST /solo/acceptance/v1/actor/open`，data 为 profile_id 与可选 world/channel |
| `actor_close(session_id="…", expected_generation=1, request_id="close-unique-1")` | `POST /solo/acceptance/v1/actor/close` |
| `operation_get(operation_id="…", since_cursor=0, limit=100)` | `GET /solo/acceptance/v1/operation/{id}?since_cursor=0&limit=100` |
| `operation_cancel(operation_id="…", request_id="cancel-unique-1")` | `POST /solo/acceptance/v1/operation/{id}/cancel`，data 为空对象 |
| `game_act(session_id="…", expected_generation=1, request_id="chat-unique-1", action="chat", parameters={"text":"实际原文"}, expected_context={"scene_ref":"…","runtime_generation":"…"})` | `POST /solo/acceptance/v1/actor/act`，请求 ID 只放外层信封 |

成功读取北斗 `{code:20000,data:{…}}` 后，返回原生 data 的 `structured_content` 和 JSON 文本。HTTP、业务或参数失败返回 `is_error=true` 与固定错误码，不回显后端错误正文、JWT 或凭据。

四种写调用都要求调用方提供 `request_id`，它是原生信封的 `requestId`。客户端成功和 HTTP/传输错误都会保留该 ID。HTTP `401` 最多刷新一次 JWT 并重发同一信封；超时或连接错误不会自动重放写请求。写请求结果未知时返回 `outcome_unknown=true`、`retryable=false`，应先查询已有操作，或明确使用原请求 ID 恢复，不换新 ID 盲重试。无效参数在发 HTTP 前拒绝。

`actor_open` 只按后端明确配置的 profile 工作，可选 world/channel 必须符合后端 profile。打开返回 `QUEUED` 只表示已受理；查询到 `SUCCEEDED`、`observed_outcome=native_actor_online`、原生在线和实际场景身份后才确认登录。已关闭 session 的旧打开请求必须返回原操作，不能重新创建角色。

`actor_close` 同样可能先排队。需查询到原生关闭结果、`native_cleanup_verified=true` 和 `persisted_state_verified=true`，才能确认保存与离线。`operation_cancel` 仅请求取消；不能把 `CANCEL_REQUESTED` 当作已经停止或已清理。

`game_act` 本轮只允许 `action="chat"`，参数对象仅有 `text`，场景对象仅有 `scene_ref/runtime_generation`；顶层及嵌套多余字段都拒绝。文本按 Java UTF-16 单元计算1～127的长度，拒绝全空白及首字符 `@`、`/`，保留提交原文而不 trim。客户端不猜测角色编码；后端按原生字符集严格编码，中文 GBK 无法表示的字符会失败，不静默替换成问号。`!` 仍按普通角色的原生规则处理。

聊天复用同 session 队列，返回 `kind=ACTION/action=chat`；`QUEUED` 或 handler 返回均不表示已广播。只有实际自身 CID、原文和新包 sequence 匹配的 `CHATTEXT 0xA2` 才能形成 `SUCCEEDED/native_chat_broadcast`。终态历史只保留匹配结果和序号，不保存正文，正文沿当前 session 的有界事件窗口读取。未 dispatch 的取消必须等实际 `CANCELLED/chat_cancelled_before_dispatch`；已广播不能撤回，须保留实际结果及 `cancel_too_late`。取消聊天不关闭角色。

实际能力公告 `actor_control.maximum_pending_actions_per_session=7` 和 `actor_executor_queue_capacity=8`；后端为首次 close 保留历史及物理队列额度。聊天时钟另公告 `chat_minimum_interval_ms` 和实际 `native_clock_update_interval_ms`。客户端不通过自动重试绕过原生限频，聊天没有广播或结果未知时不会自动再说一次。

角色和事件观察不改变已有游戏状态。`limit` 为 `1`～`500` 的严格整数，world 不接受 scene_ref；scene 必须使用实际返回的引用。world/scene 不接受角色会话参数或非零包游标，既有请求正文保持原样。actor 必须提供 `session_id` 和正整数 `expected_generation`，不接受 `scene_ref`。新参数在 Python `BridgeClient.observe` 中为 keyword-only，原有前三个位置参数保持兼容。所有整数拒绝 bool、浮点数和数值字符串。actor 只读 POST 的请求 UUID 不作为写操作去重键，不创建 operation 或占用历史/关闭额度。

actor 的顶层 `events` 使用每会话独立的包 `sequence`，与 `operation_get.events` 的生命周期 `seq` 完全分开。`since_cursor` 表示已读到的位置，默认0；窗口最多保留256包，每页最多 `limit` 项，读取不消费。`next_cursor` 是本页最后一项，没有项时保留输入游标。`truncated` 表示窗口内还有下一页；`cursor_expired` 表示更早历史已淘汰，两者独立。未来游标返回400，关闭或旧代次的已知会话返回410，进程重启后未知会话可能返回404。不能用 operation 历史接口绕过会话状态读取正文。

当前解码 `CHATTEXT`、`PLAYER_HINT` 和 `SERVERMESSAGE` type0～7，按角色原生字符集读取，正文最多1024个 Unicode 字符并注明截断。未知或畸形包只有有界摘要，不返回原始包。type4 公告在正文前有 ticker flag，不能按普通类型误读。已有 `session.actor.packet_events` 继续只返回摘要；完整正文只在 actor 观察的顶层事件窗口中出现。`actor.scene` 是读取时的当前真实场景，不代表每条历史包发生的场景。R1 目录最多前500个场景、无分页；角色自己的 scene_ref 不受该目录限制。

## 本地测试和只读协议冒烟

本地测试只用随机端口 HTTP 桩与 SDK stdio 子进程，不连接游戏数据库、不调用模型：

```bash
.venv/bin/python -m unittest discover -s tests -v
```

测试覆盖既有观察、JWT/信封、401 有界刷新、写请求 ID、超时不重放、错误脱敏、严格参数、七工具读写注解，以及官方 SDK 的真实 stdio 正反例。

实际后端的只读检查不打开角色：

```bash
.venv/bin/python -m solo_acceptance_mcp.smoke \
  --config /absolute/private/solo-acceptance.json \
  --output /absolute/evidence/mcp-observation.json
```

它检查客户端七工具及桥当前合法的两项、六项或七项开放组合、有效源码 SHA、capabilities/world/scene 的非空同代次与完整场景身份。R1 后端仍可通过观察检查，报告不会把它当成角色控制已通过。

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

流程逐步以 `0600` 检查点保存 request、operation 和 session 恢复标识。每轮需新的证据路径；路径已存在时在登录前拒绝启动，不覆盖上轮恢复 ID。已知活动失败会尝试正常清理；同 ID 的明确拒绝不会阻止关闭此前已知的会话。写请求传输结果未知或返回无效操作收据时保留原 ID 并停止自动重放，报告清理未证实。关闭进程或取消 MCP 请求都不保证游戏角色已经离线，应按检查点查询原操作并恢复处理。没有通过的阶段保留失败结果，不覆写为成功。

角色生命周期通过不等于聊天、NPC 或 PQ 验收通过，也不替代真实客户端协议、渲染和操控验收。本目录代码随工程采用 AGPL-3.0，见[根许可证](../../LICENSE)。

## 显式目标的角色包事件验收

独立事件 CLI 复用上述原生角色登录、保存退出及恢复日志逻辑；观察接口只读，但整个流程会正常写专用 fixture：

```bash
.venv/bin/python -m solo_acceptance_mcp.actor_events_smoke \
  --config /absolute/private/solo-acceptance.validation.json \
  --profile primary \
  --expected-base-url http://explicitly-verified-isolated-address:8686 \
  --page-limit 1 \
  --output /absolute/evidence/actor-events.json
```

目标、profile 和证据路径均必填，地址必须与配置完全一致；`page-limit` 默认为1，范围1～500。先检查客户端七工具、actor scope 及实际解码/游标能力，再正常打开角色，核对角色、会话、运行代次和当前真实场景。它按包序号分页，拒绝同会话最新序号回退，确认同游标重读保持已见前缀、尾页及空页游标正确。未来游标探针使用 `Long.MAX_VALUE`，避免正常新包到达使“上一尾页+1”变成合法游标。脚本查找实际 type4、ticker=true 的系统公告；正文可以为空，不匹配固定欢迎语。

随后正常保存退出，验证旧会话不能再读取正文；同一原生 CID 以新会话重新打开后，旧会话仍被拒绝，最后再次正常退出。每页及负向探针的证据逐步保存，失败不会覆写为通过；写结果未知、畸形收据和中断恢复沿用生命周期 CLI 的处理。

本地测试只连接桩服务，不等于实际系统公告已验收。隔离服和实际部署分别保存 SDK 结果；真实公告验证不代表真实触发过 CHATTEXT、PLAYER_HINT、机器人回复、NPC 或客户端画面。登录包少于256时，实物验收不声称覆盖了淘汰；该分支由后端确定性测试验证。

## 显式目标的普通聊天验收

```bash
.venv/bin/python -m solo_acceptance_mcp.chat_action_smoke \
  --config /absolute/private/solo-acceptance.validation.json \
  --profile primary \
  --expected-base-url http://explicitly-verified-isolated-address:8686 \
  --output /absolute/evidence/new-chat-run.json
```

四个目标/证据参数均必填，输出路径必须是本轮新路径。流程先验证实际聊天、事件和时钟能力，正常登录并从 actor 观察获取真实场景；发送带随机 nonce 的中文普通聊天，轮询 ACTION，并独立读取新包窗口核对同一 CID、完整原文、show=0 和返回的 echo_sequence。同 ID 重放后重新遍历窗口，确认没有第二次广播。

第二条以新 ID 发出前，从第一条 ACTION 完成起按单调时钟等待 `max(250, chat_minimum_interval_ms + native_clock_update_interval_ms)` 毫秒。北斗原生限频使用周期更新的缓存时钟，250ms 墙钟并不保证原生200ms已经过去；默认时钟更新777ms时需等待977ms。两项能力必须为正的严格整数，缺失或总等待超出 `operation-timeout` 时登录前拒绝，不退回固定250ms或截短等待。报告保留实际时钟参数、要求间隔和实测间隔；原生拒绝仍按失败处理，不自动再发，也不修改服务时钟或角色限频状态。

关闭前从最初包游标重新核对两个 nonce 都只有一条自身回声，避免延迟的重复广播被中间观察漏掉。最后正常保存退出，核对旧会话正文被拒和 ACTION 历史不含正文；重新打开同一 CID 的新会话，再正常退出。待执行 ACTION 超时先请求取消并核实终态，再正常关闭角色。ACTION 收据在清除未知标记前必须绑定本次输入会话，陌生操作不会被取消。

写结果未知时保留原请求 ID，不盲重发聊天；会话已知时仍尝试正常 close，广播是否发生与角色是否清理分别报告。若 close 也未知，`recovery.unknown_action_write` 保留原 ACTION ID，`unknown_write` 另存关闭 ID；清理成功也不会把聊天未知或先前失败改为通过。通过只证明原生普通发言及自身回声，系统公告、机器人回复、PLAYER_HINT、NPC 和真实客户端画面不能代替本项证据。
