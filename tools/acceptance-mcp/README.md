# SoloMapling 验收 MCP：R1 / R2a

本工具使用官方 MCP Python SDK `2.3.0`。客户端实现九工具，包含角色观察与操作、脚本基础和独立管理指令；实际开放范围以 `game_capabilities.enabled_tools`、`actions` 与 `debug_commands` 为准：

| 工具 | 用途 | 状态影响 |
|---|---|---|
| `game_capabilities` | 读取实际版本、运行源码身份、后端在线状态和已开放能力 | 只读 |
| `game_observe` | 观察 world、真实 scene_ref 或已打开角色的包事件窗口 | 只读，不加载地图或创建实例 |
| `actor_open` | 为已配置的专用 profile 排队执行原生登录 | 可能创建专用 fixture、更新登录状态；必须有 request_id |
| `actor_close` | 排队正常离线并核对清理和保存 | 可能触发原生队伍/副本离开；必须有 request_id |
| `operation_get` | 查询操作、当前角色会话及有界事件 | 只读，返回成功不代表操作已成功 |
| `operation_cancel` | 请求取消排队或进行中的操作 | 必须继续查询实际终态；必须有 request_id |
| `game_act` | 在真实 actor 场景中排队已开放的聊天、NPC 及运行体基本动作，当前源码包含十三动作 | 写操作，可能消耗或移除物品；必须有 request_id 和实际 expected_context |
| `script_run` | 按kind/operation在现有角色运行体执行北斗原脚本及后续代码，收集输出和JSON结果 | 必须有request_id；可保留上下文并产生实际游戏副作用 |
| `game_command` | 对已登记在线调试角色执行完整原生命令文本 | 写操作；须有 request_id 和 expected_command_generation；不经过角色 lane |

通用命令的配置、目标、调用事实与原 ID 恢复见[管理指令说明](../../docs/mcp-acceptance/commands.md)。新增工具不扩大 `game_act` 范围，后端未启用时不公告为可用能力。

MCP用于机器人开发调试，让Agent进入游戏，与待测机器人交互并检查实际行为。`script_run`只执行[#216](https://github.com/Diluka/SoloMapling/issues/216)的北斗脚本，直接指定kind/operation和上下文参数；独立纯JS模式及旧environment字段已移除。现有game_act和管理入口保留，不在本次迁移直接动作，能力公告只列实际实现范围。

R1 桥默认只有两项观察能力；R2a 的四项角色/操作接口需要后端显式启用。actor 包观察还需实际 capabilities 公告 `scopes` 中的 `actor` 和 `actor_control.packet_observation`。客户端兼容旧后端的两项、六项、七项工具组合，以及开放脚本执行、命令执行的组合，全部开启时为九工具。旧动作范围可从 `actions=["chat"]` 扩展至中心首屏/取消、有限 `npc_reply` 和 `inventory_move`，五动作后端还可分别公告已实现的移动、停止、普攻及物品动作，完整源码范围为十三动作。顶层与 `actor_control` 公告必须一致，重复或未知动作拒绝。客户端schema存在不代表后端已开启；脚本调用需后端支持当前北斗协议，不能仅凭旧后端公告两个同名工具就推导新参数兼容。`game_act` 的回复仍只含已审查路径的 select/decline，不扩成任意 NPC、选项或封包；北斗脚本按其原生入口与随源码技能说明使用。礼包、SocialBot 完整互动及具体玩法仍待各自验收。后续计划见[基础方案](../../docs/mcp-acceptance/README.md)。

`inventory_move` 的候选接口仅接受 `parameters={"inventory_type":"USE","source_slot":1,"target_slot":2}`：背包类型为 `EQUIP/USE/SETUP/ETC/CASH`，两个槽位须为不同的 `1..127` 整数。实际槽位容量、物品与原生合并/交换规则由运行体的公共原生动作核验；此动作不穿脱装备、不丢弃、不自动整理。状态及前后槽位以 operation 的实际结果为准；超时后查询原请求，不盲目重发。上线与验收状态见 [#164](https://github.com/Diluka/SoloMapling/issues/164)。

## #200 运行体基本动作

以下协议是候选能力；实际动作及支持范围以 `game_capabilities.actions` 和后端限制公告为准，不能因为工具注册了 schema 就视为已部署。所有动作复用同一角色运行体的 scope、控制 Grant 与唯一 lane，MCP 只做参数、鉴权和结果透传。

| action | parameters | 受控范围 |
|---|---|---|
| `move` | `{"direction":"LEFT","duration_ms":1000}` | 方向为 `LEFT/RIGHT`，时长为 `100..3000` 整数毫秒；连续同一可行走 foothold 的有界步行，无任意坐标或自治导航 |
| `stop` | `{}` | 停止当前移动；必须从原 operation 及实际位置确认已停止，不能把取消 Future 当作停止 |
| `attack` | `{"target_oid":123}` | 当前场景单目标近战普通攻击；运行体按 `basic_melee_v1` 计算，不接受 `damage`、`skill` 或原始包，不表示完整远程/技能战斗支持 |
| `pickup` | `{"object_id":123}` | 拾取当前场景普通库存地物，沿原生归属、距离、容量等规则；不包含金币、NX及拾取即触发脚本/消耗的物品 |
| `drop` | `{"inventory_type":"ETC","source_slot":1,"quantity":1}` | 五类普通背包、槽位 `1..127`、数量 `1..32767`；实际地物生成或原生消失分别报告，不保证丢弃后均可拾回 |
| `use_item` | `{"source_slot":1}` | USE背包中普通单角色HP/MP恢复药剂；不连带开放回城、礼包、全图效果或脚本物品；实际消耗与恢复量分别核实 |
| `equip` | `{"source_slot":1,"target_slot":-11}` | EQUIP正槽位穿到合法普通装备位，资格、冲突卸装和属性沿原生规则 |
| `unequip` | `{"source_slot":-11,"target_slot":1}` | 从合法普通装备位卸至EQUIP正槽位，沿原生容量和冲突规则 |

OID必须为 `1..2147483647` 整数；正背包槽位为 `1..127`。普通装备位白名单为 `-1..-13`、`-15..-19`、`-49/-50`，`-14`、现金位和未知位均拒绝。布尔值、浮点数、额外字段和跨动作参数不会被隐式转换；`game_act` 不接受任意CID、模板ID、物品ID、封包或脚本。

受理、原生派发、命中/位移/库存变化、work真实结算和存档分别由后端事实表达。排队取消及移动停止需继续查询原 operation；已发生的攻击、消耗或丢弃不因取消而撤销。HTTP超时或断连保留原请求ID，不自动再执行。装备和物品持久化通过正常退出后同CID重开核验，不能以既有保存标志替代具体槽位、数量和属性证据。

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

`base_url` 默认 `http://127.0.0.1:8686`，只接受无路径、查询和内嵌凭据的 HTTP/HTTPS 地址。普通 HTTP 超时为 `0.1`～`30` 秒；脚本请求另保证至少执行期限加 5 秒的收尾预算。凭据仅供管理端鉴权，工具参数不接收角色账号密码或任意原生角色 ID。

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
| `game_act(…, action="npc_center_open", parameters={})` | 同一路由，固定原生中心入口，不接受 NPC 模板或脚本名 |
| `game_act(…, action="npc_cancel", parameters={"dialogue_version":123})` | 同一路由，版本必须来自当前实际 actor 对话 |
| `game_act(…, action="npc_reply", parameters={"dialogue_version":123,"kind":"select","selection":0})` | 同一路由，只能选择当前完整 offer 中已审查的项目 |
| `game_act(…, action="npc_reply", parameters={"dialogue_version":124,"kind":"decline"})` | 同一路由，拒绝实际 type12 按钮，不带 selection |
| `script_run(session_id="…", expected_generation=1, request_id="npc-start-unique-1", kind="npc", operation="start", options={"npc_id":1032110})` | `POST /solo/acceptance/v1/script/run`，data为角色目标及平铺kind/operation/context_id/options/entry/args/source/parameters/timeout_ms |

成功读取北斗 `{code:20000,data:{…}}` 后，返回原生 data 的 `structured_content` 和 JSON 文本。HTTP、业务或参数失败返回 `is_error=true` 与固定错误码，不回显后端错误正文、JWT 或凭据。

`actor_open/actor_close/operation_cancel/game_act/script_run` 都要求调用方提供 `request_id`，它是原生信封的 `requestId`。客户端成功和 HTTP/传输错误都会保留该 ID。HTTP `401` 最多刷新一次 JWT 并重发同一信封；超时或连接错误不会自动重放写请求。写请求结果未知时返回 `outcome_unknown=true`、`retryable=false`，应先查询已有操作，或明确使用原请求 ID 恢复，不换新 ID 盲重试。客户端可判定的字段、类型、有限数值、Unicode与深度等错误在发 HTTP 前拒绝；脚本16KiB预算由服务端规范化JSON统一裁决，超额以已知HTTP 400拒绝且不执行。

`actor_open` 只按后端明确配置的 profile 工作，可选 world/channel 必须符合后端 profile。打开返回 `QUEUED` 只表示已受理；查询到 `SUCCEEDED`、`observed_outcome=native_actor_online`、原生在线和实际场景身份后才确认登录。已关闭 session 的旧打开请求必须返回原操作，不能重新创建角色。

`actor_close` 同样可能先排队。需查询到原生关闭结果、`native_cleanup_verified=true` 和 `persisted_state_verified=true`，才能确认保存与离线。`operation_cancel` 仅请求取消；不能把 `CANCEL_REQUESTED` 当作已经停止或已清理。

`game_act` 的 schema 将动作与参数分支关联：chat 只能有 `text`，中心打开必须 `{}`，取消只能有正的严格 long `dialogue_version`。回复分支由 `kind` 进一步区分：select 必须附严格整数 `selection`（0～2147483647），decline 不带 selection，两者都须有正的严格 long 版本；accept 明确拒绝。场景对象仅有 `scene_ref/runtime_generation`；顶层及嵌套多余字段都安全拒绝，不回显非法值。聊天文本按 Java UTF-16 单元计算1～127的长度，拒绝全空白及首字符 `@`、`/`，保留提交原文而不 trim。客户端不猜测角色编码；后端按原生字符集严格编码，中文 GBK 无法表示的字符会失败，不静默替换成问号。`!` 仍按普通角色的原生规则处理。

聊天复用同 session 队列，返回 `kind=ACTION/action=chat`；`QUEUED` 或 handler 返回均不表示已广播。只有实际自身 CID、原文和新包 sequence 匹配的 `CHATTEXT 0xA2` 才能形成 `SUCCEEDED/native_chat_broadcast`。终态历史只保留匹配结果和序号，不保存正文，正文沿当前 session 的有界事件窗口读取。未 dispatch 的取消必须等实际 `CANCELLED/chat_cancelled_before_dispatch`；已广播不能撤回，须保留实际结果及 `cancel_too_late`。取消聊天不关闭角色。

实际能力公告 `actor_control.maximum_pending_actions_per_session=7` 和 `actor_executor_queue_capacity=8`；后端为首次 close 保留历史及物理队列额度。聊天时钟另公告 `chat_minimum_interval_ms` 和实际 `native_clock_update_interval_ms`。客户端不通过自动重试绕过原生限频，聊天没有广播或结果未知时不会自动再说一次。

角色和事件观察不改变已有游戏状态。`limit` 为 `1`～`500` 的严格整数，world 不接受 scene_ref；scene 必须使用实际返回的引用。world/scene 不接受角色会话参数或非零包游标，既有请求正文保持原样。actor 必须提供 `session_id` 和正整数 `expected_generation`，不接受 `scene_ref`。新参数在 Python `BridgeClient.observe` 中为 keyword-only，原有前三个位置参数保持兼容。所有整数拒绝 bool、浮点数和数值字符串。actor 只读 POST 的请求 UUID 不作为写操作去重键，不创建 operation 或占用历史/关闭额度。

actor 的顶层 `events` 使用每会话独立的包 `sequence`，与 `operation_get.events` 的生命周期 `seq` 完全分开。`since_cursor` 表示已读到的位置，默认0；窗口最多保留256包，每页最多 `limit` 项，读取不消费。`next_cursor` 是本页最后一项，没有项时保留输入游标。`truncated` 表示窗口内还有下一页；`cursor_expired` 表示更早历史已淘汰，两者独立。未来游标返回400，关闭或旧代次的已知会话返回410，进程重启后未知会话可能返回404。不能用 operation 历史接口绕过会话状态读取正文。

按实际能力解码 `CHATTEXT`、`PLAYER_HINT`、`SERVERMESSAGE` type0～7，中心后端还解码 `NPC_TALK 0x130` type4，回复后端增加 type12。旧三类 decoder 及仅 NPC type4 的公告仍兼容。按角色原生字符集读取，正文最多1024个 Unicode 字符并注明截断；未知或畸形包只有有界摘要，不返回原始包。NPC type0 仍为摘要，不表示可读全文或可执行 next。type4 系统公告在正文前有 ticker flag，不能按普通类型误读。已有 `session.actor.packet_events` 继续只返回摘要；完整正文只在 actor 观察的顶层事件窗口中出现。`actor.scene` 是读取时的当前真实场景，不代表每条历史包发生的场景。R1 目录最多前500个场景、无分页；角色自己的 scene_ref 不受该目录限制。

中心阶段的 `actor.dialogue` 包含 `dialogue_version/active/supported/entry_type/npc_template_id/message_type/packet_sequence`。version 取当前 NPC 包的独立 sequence，必须与 session/代次合用；当前 CM 对象仍匹配且没有 QM 才能 active。正文、NPC 名称或相同模板不能代替活跃状态。未知新 NPC 包、取消、关闭或世界换代都使旧版本失效。

回复后端在该状态上增加可信 `path` 与 `reply_options={kinds,offered_selections,allowed_selections,complete,consumed}`。path 为 center、welfare、transport_category、transport_town 或 unsupported，绑定受控原生来源及当前 CM。实际展示项最多128，和本轮允许执行项分开：中心真实展示9项但仅允许0/71，传送分类展示0～3但仅允许2，福利 type12 仅开放 decline。截断城镇菜单的 complete=false，offer/allowed/kinds均为空；即使选项1出现在正文前缀也不能选择。隐藏GM61～68/999、未展示和未审查项目在 native dispatch 前拒绝，不靠脚本再次鉴权。

capabilities 的 `npc_dialogue` 继续公告版本和入口规则，回复阶段为 `message_types=[4,12]`、`reply_kinds=["select","decline"]`、`requires_complete_offers=true`、`requires_current_offered_selection=true`、`maximum_offered_selections=128`。回复派发前消费该版本资格；同 ID 恢复原 operation，新 ID 不能再消费旧版。成功结果为 `dialogue_replied` 或 `dialogue_declined`，附 kind、selection、`reply_version_consumed=true`、后继 `dialogue_path` 及新包序号；不保存 NPC 正文。decline 的 type0 告别可仅作为摘要和原生 CM 清理证据，不能声称已读其全文。

中心由原生 `ENTER_MTS` 的 `use_mts=false` 分支打开固定9900001首屏，不要求地图上存在该 NPC，也不把它称为远距离地图点击。取消按当前真实路由：中心 legacy type4 为 `04 ff`，福利 legacy type12 为 `0c ff`；福利 decline 是另一条 `0c 00` 请求。传送 NextLevel SEND_SELECT/SEND_NEXT_SELECT 用 `04 00` 取消，即使城镇正文截断仍可正常取消。调用方不能传 lastMsg、action byte、show、脚本或原始包。成功取消及关闭均要求 `native_dialogue_cleanup_verified=true`，表示原生 CM/QM 映射都已清空。`operation_cancel` 只取消尚未派发的操作，不结束已经打开的 CM。新请求使用已失效或已消费版本返回410；拒绝不消耗当前版本，同 ID 重放仍返回原操作。

## 脚本基础调用

先正常 `actor_open` 并确认实际角色在线，保存 `session_id/session_generation`，后者作为 `expected_generation`。脚本继续沿管理员 JWT、owner、精确角色和当前世界/模块代次核验。环境和 API 查阅见[脚本调试技能](../../skills/solomapling-script-debug/SKILL.md)，实际环境由 capabilities 的 `actor_control.native_environments` 公告，原生对象和方法直接查随附源码。每次调用必填 kind/operation；上下文 ID、状态和本次结算从实际执行结果取得，使用原请求或 operation 恢复。

例如启动已有无收益NPC原脚本，省略source时只执行原入口：

```text
script_run(session_id="实际session_id", expected_generation=实际session_generation,
           request_id="npc-start-唯一ID", kind="npc", operation="start",
           options={"npc_id":1032110})
```

这会加载实际文件并调用原start，能够打开对话，不能当成只注入cm。保存返回的 `result.context_id`；它只属于该角色和模块代次。原NPC/QM点击冷却仍有效，两次启动须按实际原生门槛与缓存时钟留足间隔，不用新ID重发绕过。

在仍OPEN的同一上下文中执行片段，使用新的请求ID：

```text
script_run(session_id="实际session_id", expected_generation=实际session_generation,
           request_id="npc-read-唯一ID", kind="npc", operation="invoke",
           context_id="实际context_id", parameters={"label":"当前角色"},
           source="console.log(parameters.label); ({name: cm.getPlayer().getName(), status: status})")
```

这段代码使用真实cm和原脚本变量；参数运算、语言输出及结果整理都在同一北斗环境内执行。也可以沿原回复路由继续此NPC：

```text
script_run(session_id="实际session_id", expected_generation=实际session_generation,
           request_id="npc-reply-唯一ID", kind="npc", operation="invoke",
           context_id="实际context_id", entry="reply", args=[1,0,0])
```

该代表原脚本随后dispose；以实际 `context_state` 为准，已关闭的ID不能继续调用。如果环境仍OPEN，用新的请求ID显式关闭：

```text
script_run(session_id="实际session_id", expected_generation=实际session_generation,
           request_id="native-close-唯一ID", kind="npc", operation="close",
           context_id="实际context_id")
```

start不带context_id，invoke/close必须带；invoke的非空source与entry互斥。close使用source/options/args的空默认值，不得指定entry。source默认空字符串，parameters/options默认{}，args默认[]，timeout_ms默认1000、范围1～5000。三根JSON输入的16KiB预算按服务端规范化UTF-8编码统一裁决，客户端保留类型、有限数值、Unicode、节点数和深度检查，不重复裁决本地编码字节数。详细JSON额度、入口规则及五类选择见[公共执行契约](../../docs/robot-runtime/script-execution.md#请求)和[北斗上下文](../../docs/robot-runtime/beidou-script-environment.md)。

工具返回既有 `kind=SCRIPT` operation，result包含JSON结果、stdout/stderr、错误和上下文状态。成功调用可以保留 `context_state=OPEN,context_closed=false`；本次 `invocation_settled/work_settled` 与最终环境关闭分开核验。语法、运行、结果、期限、取消及清理状态分别表达，详见[结果参考](../../docs/robot-runtime/script-execution.md#输出和结果)。不为取得context_closed=true而盲目重放原入口。

官方SDK task取消会发送MCP取消通知。桥在受保护的收尾段内向内部 `/script/cancel` 转发原请求ID和完整平铺参数，等待实际收尾；没有新增script_cancel工具。取消先到的检查点阻止迟到run执行。已取消请求按协议不再返回工具响应，使用相同request_id及完整原参数恢复原operation，或用已知operation_id查询；变参冲突，未知结果不换新ID重发。

提前取消未启动的invoke/close不会自动关闭已有Context；检查点中的本次结算与原上下文状态分别核对。仍为OPEN的环境按需显式close，状态 UNKNOWN 时保留未知结论，按原请求和 operation 恢复证据，不把 CANCELLED 当作清理完成。

NPC/任务复用原管理器，门户/机关使用当前真实对象，事件有角色自有原生EM和引擎，不接管频道共享事件。北斗Java API保留实际副作用；服务端warp不是普通玩家门户准入，开UI不等于完成交易，任务脚本入口不替代普通接取资格。guest循环已停止不证明host Java方法已返回，未核实执行和调度资源继续保留债务。

用完脚本上下文后仍须正常actor_close并核存档。故障、无限循环与世界停启探针仅在隔离环境运行，实际部署只做本轮授权的正常闭环。owned EIM创建明确拒绝，完整实例地图与延迟清理、更多脚本组合、真实机器人交互和客户端玩法仍待对应验收；现有九工具和十三动作保留。

## 本地测试和只读协议冒烟

本地测试只用随机端口 HTTP 桩与 SDK stdio 子进程，不连接游戏数据库、不调用模型：

```bash
.venv/bin/python -m unittest discover -s tests -v
```

测试覆盖既有观察、JWT/信封、401 有界刷新、写请求 ID、超时不重放、错误脱敏、严格参数、九工具读写注解与脚本取消转发，以及官方 SDK 的真实 stdio 正反例。引擎真实停止及游戏后态由运行体测试和隔离端到端另行验证。

实际后端的只读检查不打开角色：

```bash
.venv/bin/python -m solo_acceptance_mcp.smoke \
  --config /absolute/private/solo-acceptance.json \
  --output /absolute/evidence/mcp-observation.json
```

它检查客户端九工具及桥当前合法的观察、角色、脚本和管理工具开放组合、有效源码 SHA、capabilities/world/scene 的非空同代次与完整场景身份。R1 后端仍可通过观察检查，报告不会把它当成角色或脚本执行已通过。

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

流程逐步以 `0600` 检查点保存 request、operation 和 session 恢复标识。每轮需新的证据路径；路径已存在时在登录前拒绝启动，不覆盖上轮恢复 ID。证据写盘失败后停止新开角色、新业务动作和负向写探针，避免发送尚未记下恢复 ID 的请求；已知角色的取消和正常关闭仍可清理。已知活动失败会尝试正常清理；同 ID 的明确拒绝不会阻止关闭此前已知的会话。写请求传输结果未知或返回无效操作收据时保留原 ID 并停止自动重放，报告清理未证实。关闭进程或取消 MCP 请求都不保证游戏角色已经离线，应按检查点查询原操作并恢复处理。没有通过的阶段保留失败结果，不覆写为成功。

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

目标、profile 和证据路径均必填，地址必须与配置完全一致；`page-limit` 默认为1，范围1～500。先检查客户端工具目录、actor scope 及实际解码/游标能力，再正常打开角色，核对角色、会话、运行代次和当前真实场景。它按包序号分页，拒绝同会话最新序号回退，确认同游标重读保持已见前缀、尾页及空页游标正确。未来游标探针使用 `Long.MAX_VALUE`，避免正常新包到达使“上一尾页+1”变成合法游标。脚本查找实际 type4、ticker=true 的系统公告；正文可以为空，不匹配固定欢迎语。

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

## 显式目标的原生中心首屏验收

```bash
.venv/bin/python -m solo_acceptance_mcp.npc_center_smoke \
  --config /absolute/private/solo-acceptance.validation.json \
  --profile primary \
  --expected-base-url http://explicitly-verified-isolated-address:8686 \
  --output /absolute/evidence/new-npc-center-run.json
```

目标、profile 和新证据路径均必填。流程要求实际三动作、type4 decoder 和 `actor_control.npc_dialogue` 公告。先正常打开 level1/map10000 的原生角色，记录地图/场景/坐标、等级经验、金币、背包任务和 `tickets_summary` 的三种券 `nx_credit/maple_point/nx_prepaid`。然后只打开中心首屏，独立观察真实304/type4、模板9900001、speaker=0、未截断正文与当前活跃版本，不选择任何菜单。

脚本重放同一打开 ID 检查不增加 NPC 包，正常取消后核对原生清理及全部前后态；重放取消 ID，使用新 ID 提交旧版本必须被拒。重新打开后，用旧版本取消也必须被拒且新 CM 保持原样，再用新版正常取消。第三次打开后直接正常退出，明确核对对话清理；同 CID 新会话重开后须无活跃对话、无收益或地图变化，再正常关闭。关闭前从最初窗口重读，NPC 包必须恰好对应三次已验证打开，避免迟到重复包被中间观察略过。存档重开不要求 x/y 与原位置相同。

每次重新打开前按单调时钟等待 `npc_dialogue.npc_minimum_interval_ms + actor_control.native_clock_update_interval_ms + 1`，当前500+777+1为1278ms，额外1ms对应原生严格 `<` 判断。能力缺失、非正整数或超出 `operation-timeout` 时登录前拒绝。失败不会自动重开、重置点击时间或改服务时钟；NPC ACTION 或其取消请求结果未知时，原 ID 保存在 `recovery.unknown_action_write`，仍尝试正常关闭已知角色，若关闭也未知则另存关闭 ID。清理成功不抹去原失败，也不当作对话已经正常取消。

本地测试使用 HTTP 桩；候选实现、隔离原生脚本验收和实际部署证据分别记录。通过本流程只证明中心首屏和取消，不代表菜单选择、礼包、传送、其他 NPC、SocialBot 或真实客户端画面已验收。

## 显式目标的无收益 NPC 回复验收

```bash
.venv/bin/python -m solo_acceptance_mcp.npc_reply_smoke \
  --config /absolute/private/solo-acceptance.validation.json \
  --profile primary \
  --expected-base-url http://explicitly-verified-isolated-address:8686 \
  --output /absolute/evidence/new-npc-reply-run.json
```

四个目标/证据参数均必填，输出路径必须新建。流程只用未领取礼包的普通 level1/map10000 fixture：实际中心选0得到新的福利 CM 和完整 type12，先 decline；再重开同路径，按正常 npc_cancel 取消。若真实角色已领取而只收到 type0 并结束，就以实际前置不满足失败，不能重置标记或伪造 type12。

第三条路径从实际中心选71，到真实传送分类菜单后只选其完整 offer 中的2；城镇菜单1405字会被1024字符上限截断，CLI提交选择1的负向探针，必须拒绝且当前CM不变，再正常 NextLevel 取消。第四条路径重开福利命名CM并直接 actor_close，核对原生清理和同CID存档重开后无活跃对话。

每步记录实际包、版本、可信路径和当前offer，验证隐藏61～68/999、未审项目3、旧版和已消费版被拒；同原ID重放不得多发包或前进，最终完整窗口核对全部NPC序号，避免迟到重复。重新打开的点击间隔继续按500ms加实际缓存时钟周期再加1ms等待，拒绝或未知结果都不盲重试，并正常关闭已知角色。

公开快照前后比较地图/场景/位置、等级经验、金币、三种券、背包和任务，保存重开不要求完全相同 x/y。`claim_marker_verified_via_cli=false` 与 `return_location_storage_verified_via_cli=false` 明确保留：固定“新人福利礼包”领取标记和持久返回位置由独立数据库审计补证，CLI没有新增查询任意扩展键或数据库能力。通过本项不代表 accept、领取收益、目的地选择、付费出行或真实客户端已验收。

## 通用命令桥

北斗脚本直接使用 `executeCommand("@time")`，只有执行函数，没有额外脚本帮助函数。
MCP `game_command` 接受 profile_id/request_id/expected_command_generation/command，
command是完整原文，旧parameters结构不兼容；脚本与MCP共用NativeCommandExecutor，
不逐命令适配或限定give。命令查阅由[游戏交互技能](../../skills/solomapling-game-interaction/SKILL.md)提供，技能脚本查询北斗已有只读命令库，再定位处理类。
RETURNED仅表示原生void处理器返回，实际效果沿原生反馈和状态核验；GM权限保持原生。
详细说明见[通用命令执行](../../docs/mcp-acceptance/commands.md)。

动作、管理指令及脚本的纯参数校验由 MCP 原始输入入口统一执行；内部 HTTP 客户端转发已验证参数，不提供独立的动作、指令或脚本输入兼容层。鉴权刷新、响应检查、原请求 ID 和未知写结果的清理仍在 HTTP 边界处理。

## 随源码使用的技能

- [游戏交互](../../skills/solomapling-game-interaction/SKILL.md)：角色、动作、命令源码定位与恢复。
- [脚本调试](../../skills/solomapling-script-debug/SKILL.md)：原生对象、连续调用与收尾。
- [机器人验收](../../skills/solomapling-bot-testing/SKILL.md)：场景准备、实际取证与回归。

技能及 `references/` 随仓库整套使用。将这三个技能目录链接到技能安装目录时，保留指向本仓库的链接；源码导航按技能真实路径解析，仓库路径变化后更新安装链接。工具目录保留九个执行／观察工具，两个 help MCP 已由技能取代。
