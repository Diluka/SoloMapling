# MCP 控制模块接入统一角色运行体

**实现范围：[#139](https://github.com/Diluka/SoloMapling/issues/139) 的首环 MCP 控制模块、同壳聊天/观察及现有 actor 接入。交付状态和各层验收证据以该 Issue 为准，代码合并或合同测试不代替隔离与实际部署验收。** 本轮保留现有七工具、四种动作与外部访问边界，不公布生产自治、巡逻、刷怪或任意角色接管能力。

本页只说明 MCP 的接入、映射和验收约定。宿主与模块协议见[运行体 v0.1](../robot-runtime/README.md)，由[统一运行体 #84](https://github.com/Diluka/SoloMapling/issues/84)负责；外部工具、配置及既有玩法边界见 [MCP 说明](README.md)。已经交付的 [#117～#119 共享基础](../shared-game-foundation/README.md)继续作为底层机制复用。

## 三层归属

MCP 的外部 session、角色宿主和控制模块承担不同责任。它们指向同一个原生角色来源，但不能互相充当权限或生命周期代次。

| 层次 | 持有与维护的内容 |
|---|---|
| MCP 外层 | JWT/admin、owner/profile 白名单、外部 session、request_id 幂等、operation/history/events、现有额度、参数/expected_context 和 JSON/错误映射 |
| `CharacterRuntime` 根宿主 | OPEN 前就存在的宿主身份、root scope、原生角色登记与来源适配、唯一 lane，以及最终原生退出/存档与资源释放 |
| MCP 控制模块 | 宿主授予的模块身份和动作上下文、普通聊天/NPC 请求及结果消费、MCP 出队守卫和自己的局部资源 |

角色根资源与 lane 不再由 Session/helper 在壳外另建一份。模块消费宿主接纳的原工作及其真实结算事实，不复制 work、scope、Grant 或物理完成账本。MCP 的 pending ACTION 数仍是外部额度投影，额度归还依据宿主原工作实际结算，不能因 operation 或业务结果先终态就提前归还。

普通原生账号、GM0、lease、在线 Client 和排除环境 bot 的身份规则仍由可信 MCP 来源适配保护；统一宿主不把它们泛化成所有 Bot 的统一登录前提，也不将 BotClient 换成 AcceptanceClient。

## 同一宿主内的入口

| 外部入口 | MCP 接入的责任与事实 |
|---|---|
| `actor_open` | 先在原接纳世界建立 pending host，再由根来源准备、登记并绑定原生角色；角色可读且 MCP 模块可用才报告 OPEN 成功 |
| `game_act/chat` | 模块取得宿主受控动作上下文，准备现有 `NativeActionService` 后只向原 lane 提交一次；不在队列 body 内再次 submit/join/get 等自己 |
| `game_observe(scope="actor")` | 从同一宿主的角色来源读取事实，继续核 owner/session 和包窗口权限；不维护第二份角色状态，也不从只读快照取得普通动作控制权 |
| 现有 NPC 动作 | 保留已审来源、offer、dialogue_version 和 legacy/NextLevel 路由，使用同一宿主 lane，不借迁移扩大菜单或收益路径 |
| `actor_close` | 请求根宿主的正常角色关闭，经来源核原生清理、对话及声明范围存档；模块停止或卸载不能替代此操作 |

MCP 的根来源由两个包内适配承接：

- [AcceptanceRuntimeOrigin](../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/AcceptanceRuntimeOrigin.java) 将原 OPEN work 交给宿主 `reserveOrigin`，沿同一根握手提供 bind/adopt、absence、部分失败与内联回滚。回滚在根 lifecycle scope 内跟踪并调用根释放入口，不排到同一 lane 后再等自己，也不维护壳外第二份资源账本。
- [AcceptanceRoleBinding](../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/AcceptanceRoleBinding.java) 实现 `RoleBinding`，精确身份为原 NativeActorHandle，角色引用沿用 handle.characterRef。`requireCurrent()` 保留 Backend 的普通角色完整守卫，`observe()` 每次读取原生快照，`release(DrainPermit)` 只在精确根许可执行体中、原生 cleanup 与声明范围存档核验都为真时返回释放证明。结果 Map 是独立证据投影，不是另一份权威角色状态；单个释放证明也不等于宿主所有工作已排空。

控制模块对应 [McpControlModule](../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/McpControlModule.java)，类型为 `mcp-control`，声明 `chat/interaction` 两类有限控制资源。`onActivated` 接受宿主提供的 ModuleContext；保留的 Context 引用可用于对应事实记录，普通操作仍由宿主检查当前模块代次及授权，不能把 getter 返回非空当成控制权。

| 模块入口 | MCP 消费方式 |
|---|---|
| `acceptWork()` | 接受宿主管理的本模块原工作，不再另包一个 Session work |
| `prepareChat(work, preflight, preparation)` | 经 `prepareNative("chat", ...)` 只准备 #119 原生聊天句柄；准备返回后再唯一 submit |
| `submitInteraction(work, preflight, body, settled)` | 经同一宿主上下文向原 lane 提交现有有限 NPC 操作 |
| `recordResult(work, result)` | 记录与原工作对应的原生事实，不等同重新激活模块或发布新的业务动作 |
| `context()/reference()` | 关联宿主的精确上下文/模块代次，不作为外部任意接管参数 |

[AcceptanceActorExecution](../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/AcceptanceActorExecution.java) 是 MCP 接线层：在 OPEN 前建立并关联一个 CharacterRuntime，绑定根来源后挂载 McpControlModule，确认活跃 Context 才完成打开。chat 的 prepare 经模块上下文进入 Backend/#119，submit 只转发一次；NPC 使用模块 interaction 入口。`observeOrigin()` 经宿主调用根 RoleBinding.observe，ActorControlService 再附加既有场景引用；角色事实不改成模块自己的副本。关闭、清理和 finalized/排空查询均委托根宿主，保留的方法名不表示 helper 又获得 lane 的关闭权。

外部原 request_id 应先恢复原 operation，不为重试申请新模块上下文或 work。新的合法请求才接受一次新命令。准备、实际原生派发、业务结果和物理结算仍分别记录；聊天成功继续依赖 #119 的真实新匹配包、来源、完整性和接收 coverage，不能由模块 RUNNING 或宿主方法返回代替。

角色来源打开后若绑定、首次模块挂载或当前性校验失败，归根宿主保留原 accepted-work、资源登记和精确清理来源。已经产生的角色不因模块不可用而丢弃；未证实释放的资源保留 quarantine 与恢复引用，不从新模块 Grant 或新世界票据续旧清理权。

## 外部代次和观察语义

`runtime_generation` 继续投影 `WorldEpoch.Token.value()`，表示世界运行代次；它不改成角色宿主 ID、模块实例 ID 或模块代次。已有 session/operation/evidence 保留各自接纳或捕获的世界来源，不在新世界启动后刷新旧引用。

外部 session_generation、模块执行代次、角色/场景引用、原生包 sequence、operation 游标和宿主事件游标分别使用。普通跨图不会自动换角色宿主；根关闭后重新 actor_open 则属于新的宿主/会话和模块执行，即使 CID 相同也不能续旧 Context。模块卸载后重挂时保留原宿主的条件另由 #140 验证。实际 SceneRef 与 expected_context 仍按地图/事件实例检查。读取失败不能把仍在线角色误标 LOST，旧 session 和关闭后的正文拒绝规则保持。

world/scene 目录浏览保留原观察服务；同壳 actor 来源导出的 scene_ref 用于继续读取对应实际场景，不为每个宿主另建全世界目录。宿主快照和事件用于核对宿主、模块及资源归属，不自动构成聊天广播或原生存档的证明；它们不替代角色包窗口或 operation 历史。记录同壳证据时核对实际原生对象与唯一 lane，不能只核一个包装 ID。

### 只读 runtime 诊断

actor 观察结果的 `actor.runtime` 和操作响应的 `session.runtime` 投影同一个宿主诊断，用于核对首环归属。解包后的取值示例分别为 `observation["actor"]["runtime"]` 和 `operation["session"]["runtime"]`：

| 字段 | 含义 |
|---|---|
| `id/epoch/state/origin_kind/character_id` | 宿主实例、捕获世界来源、生命周期及根角色来源身份 |
| `module.type_id/instance_id/generation/state` | MCP 模块类型、实例与执行代次/状态 |
| `lane.pending/ordinary/cleanup/closed` | 同一宿主 lane 的当前账本投影，不是第二队列 |
| `events` | 有界安全事实片段，`cursor_scope=runtime_event_sequence`；包含对应 runtime/module/work 关联与固定结果摘要 |

这些字段只读，不接受凭宿主 ID、模块 ID 或 CID 发任意控制请求。`events.next_cursor` 与原生包 since_cursor、operation seq 分开；当前投影不新增独立 runtime 事件分页/订阅工具，也不表示完整 #120 已交付。诊断可能在读取期间变化，不能据单次计数推导动作已成功、资源已释放或所有执行体已退出。

## 模块停止与 NPC 对话

原生 CM/QM 和包缓冲属于根 Client/角色来源，不归 MCP 模块局部生命周期。MCP 模块暂停或卸载的默认策略是停止输入并排空自己的命令，保留已有 CM，不隐式执行 dispose、取消或原生登出。需要结束对话时，由合法控制入口执行显式 `npc_cancel`，或由根 `actor_close` 沿正常退出规则清理；停止模块本身不声称对话已取消。

重新挂载后的模块须重新观察当前来源和最新对话版本，不能复活旧模块已准备的动作。完整暂停/恢复、卸载重挂、模块不可用时的外部关闭及复杂失败路径由[次环 #140](https://github.com/Diluka/SoloMapling/issues/140)分别验证。#139 同壳聊天/观察通过不代表 #140 已验，也不代表生产自治或自动接管恢复已实现。

## 回归与部署约定

模块合同见 [McpControlModuleTest](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/McpControlModuleTest.java)，MCP 接线回归见 [AcceptanceActorExecutionTest](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/AcceptanceActorExecutionTest.java)及 [ActorControlServiceTest](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/ActorControlServiceTest.java)。来源合同见 [AcceptanceRuntimeOriginTest](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/AcceptanceRuntimeOriginTest.java)：真实宿主机制配原生身份/存档替身，只证明声明的来源适配边界；晚到 OPEN、完整 helper/模块接线及隔离/部署仍按本轮实际证据分别核验。

#139 的证据包括同一个宿主中 OPEN→中文聊天→真实原生回声→actor/scene 观察，并核原角色资源、模块实例及 lane 归属；错误 owner、旧 session/模块来源、原 request_id、拒绝/限频和取消沿现有边界回归。既有 NPC 首屏/有限回复/取消与正常 root close、同 CID 存档重开继续分别验收。

源码/机制合同、隔离原生接入、实际部署与真实客户端结果分层记录。测试模块只能证明协议机制，不公告为自主机器人。首环不等待完整 #120 观察父项或新的移动/战斗工具；#140 和其他玩法仍按各自 Issue 收口。

部署继续使用根目录 `build.sh` 验证后的镜像、本地只读配置与原北斗数据；不得为接入模块另挂 JAR、改用环境变量覆盖业务配置或另建实际角色库。独立实验使用临时库/一致副本，候选通过后沿正常保存、一致备份、切换和实际短流程回归推进；真实玩家活动单独归因。运行源码身份核对构建报告和 capabilities，不能把工作树代码或测试替身当成已部署能力。

本轮 MCP 迁移以原 `a09cec866649d97b2d0ac071c6a06faec0e24d90` 的外部契约与来源行为为基线，组合运行体 `24a36509929e4d2179bdefc22b95293f8c34b1a9` 的协议；后续修正及最终运行源码身份以构建报告、capabilities 和 Issue 证据核对，不把本页基线当当前部署版本。
