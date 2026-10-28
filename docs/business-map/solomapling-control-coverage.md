# 运行体与验收域的边界及覆盖依据

地图描述 2026-10-28 可见的 SoloMapling 工作区源码，记录统一角色运行与验收控制的责任和限制。源码可能继续演进，使用地图时应重新核对锚点；不能将它当作当前部署的逐字对应证明。本轮只编写地图，未运行游戏或访问数据库。

## 责任划分

| 业务责任 | 受益人与结果 | 自有决策和状态 | 完整生命周期 | 协作边界 |
| --- | --- | --- | --- | --- |
| `solo.runtime` 统一角色运行与执行 | 可信控制模块可在同一原生角色上执行受控命令，运营者可判断真实排空与关闭 | 宿主、模块、有限控制资源、执行代次、根资源、工作/载体结算与安全事件；不另持角色等级/背包档案 | 原生 OPEN 前建根宿主，接纳/绑定来源，挂载模块，执行与观察，撤授权并暂停/卸载，新代次恢复，来源最终释放，全部排空后 CLOSED | 角色身份、原生登录和保存政策由 RoleBinding 来源保留；共享动作沿北斗规则；MCP 的 JWT、profile、请求策略留在外层 |
| `solo.acceptance` MCP 游戏验收 | 授权管理员观察真实世界，并操控白名单普通验收角色，得到可恢复操作事实 | owner/profile/session、请求指纹及历史额度、可开放动作、包观察窗口、对话版本与已审路径、quarantine | 能力读取，已加载场景观察，创建/重登专用角色，原生聊天或有限 NPC，操作查询/取消，正常清理与存档核验，原角色保留重开；失败保留恢复关联 | 不接管真人或环境机器人；账号/角色持久化、场景、背包、币值及脚本语义归北斗；只消费同一运行体根资源与 lane |

世界/场景上下文、精确资源和执行机制合并在运行体域，是因为它们共同建立“一个可信角色实例可被执行、被排空并被最终关闭”的承诺。未把每个 Java 包或 DTO 单独拆成业务域。MCP 身份、限额与对话开放策略可以独立改变，因此由验收域拥有，未并入通用运行体。

## 从独立入口与状态所有者发现的覆盖

下列源码路径均相对于仓库根目录，方法名在写锚点前已读取声明和相关实现。

| 场景或生命周期责任 | 责任域及地图节点/流程 | 决定性源码/文档 |
| --- | --- | --- |
| OPEN 前根宿主、来源创建握手、精确角色接纳和绑定 | `solo.runtime.bind-role`、`role-binding`、`unique-lane` | `beidou-integration/src/main/java/soloMapling/game/runtime/CharacterRuntime.java` 的构造、`reserveOrigin`、`OriginResource.adopt`、`bindAdoptedOrigin` |
| 模块挂载、有限资源冲突、暂停、卸载和新代次恢复 | `solo.runtime.modules`、`resume-module`、`module-state`、`modules.flow` | 同文件 `mount`、`activate`、`stop`、`tryFinishStop`；`docs/robot-runtime/README.md` |
| 命令仅进入唯一 lane 一次 | `solo.runtime.command-api`、`dispatch-chat`、`unique-lane` | `CharacterRuntime.ModuleContext.prepareNative`；`NativeActionService.submitChat`；`AcceptanceActorExecution`；`McpControlModule` |
| 原生聊天派发、真实匹配包、失败和未知事实 | `solo.runtime.dispatch-chat`、`action-result`；`solo.acceptance.chat.flow` | `beidou-integration/src/main/java/soloMapling/game/action/NativeActionService.java` 的 `execute`、`reduce`、`settled`；`ActorControlService.submitChat` |
| 取消、body 退出、carrier 结算、发布及资源释放分别计账 | `solo.runtime.work`、`resources`、`true-settlement` | `ExecutionScope.beginDrain`、`awaitDrained`、`publishOnce`；`ScopeResources`；`CharacterCommandLane` |
| 原世界停止票据、旧引用拒绝和迟到资源债务 | `solo.runtime.world-stop`、`epoch`、`current-context`、`exact-release` | `ExecutionScope.tryBeginWorldStop`、`joinWorldStop`；`NativeContextChecks.requireSceneCurrent`；`CharacterRuntime.OriginResource.adopt` |
| 根关闭等待模块/钩子、来源释放、真正 CLOSED | `solo.runtime.close-role.flow`、`release-origin` | `CharacterRuntime.beginClose`、`releaseOrigin`、`tryFinalizeClose`；`RoleBinding` |
| 从同一来源读取角色和安全事件 | `solo.runtime.observe-role`、`events` | `CharacterRuntime.observeOrigin`；`RuntimeEvents.read` |
| 七工具通过正常管理端和管理员权限访问 | `solo.acceptance.protocol`、`admin` | `tools/acceptance-mcp/src/solo_acceptance_mcp/server.py:build_server`；`AcceptanceAccess.requireAdmin`；`ActorControlController` |
| 仅读已加载场景，旧场景/世界代次拒绝 | `solo.acceptance.discover`、`context` | `AcceptanceObservationService.observe`、`discover`、`registerActorScene` |
| 专用 profile、账号租约、普通权限和原生创建/登录 | `solo.acceptance.native-source`、`profiles`、`open.flow` | `ActorProfiles.resolve`；`NativeActorBackend.openOwned`；`ActorAccountOwnership`；`AcceptanceRoleBinding` |
| 相同请求恢复、参数冲突、历史容量及首次关闭预留 | `solo.acceptance.idempotency`、`operations`、`sessions` | `ActorControlService.open`、`duplicate`、`requireHistoryCapacity`、`requireCloseHistoryCapacity` |
| actor 观察前后 owner/session/在线对象复核、独立包游标 | `solo.acceptance.observe`、`packets` | `ActorControlService.observe`；`PacketEventBuffer.accept/window`；`PacketEventDecoder` |
| 固定脚本中心、当前 CM、版本消费、有限选择/拒绝/取消 | `solo.acceptance.npc.flow`、`dialogue`、`reply-rule` | `NativeActorBackend.npcCenterOpen/npcReply/npcCancel`；`AcceptanceClient.validateNativeDialogueReply/Cancel`；`NativeDialogueTracker.consume/capture` |
| 原操作查询与取消、未知副作用不重放 | `solo.acceptance.poll`、`cancel` | `ActorControlService.get/cancel`；`tools/acceptance-mcp/src/solo_acceptance_mcp/bridge.py` 的 mutation 恢复边界 |
| 正常合作退出、CM/QM清理、声明范围存档与保留fixture | `solo.acceptance.close.flow`、`quarantine` | `NativeActorBackend.close/nativeCleanupComplete/matchesPersistedState`；`ActorControlService.runClose/closeCompleted` |

## 跨目录关系与证据

- `solo.runtime.dispatch-chat → beidou.social.chat`：共享动作实际调用北斗 `GeneralChatHandler`；原生禁言、限频和广播语义由北斗拥有。
- `solo.acceptance.open → beidou.operations.admin-api`：验收接口从北斗正常 Spring Security 上下文要求已认证管理员；协议桥沿原管理端获取 JWT。
- `solo.acceptance.native-source → beidou.identity.native-character/characters`：`NativeActorBackend.openOwned` 调用原生账号登录、`BeginnerCreator`、`CharSelectedHandler` 和 `PlayerLoggedinHandler`，由北斗创建和持久化普通角色。
- `solo.acceptance.observe → beidou.world.scene / beidou.economy.inventory / beidou.economy.wallet`：`NativeActorBackend.snapshot` 只读投影当前地图、背包与余额，不另造权威状态。
- `solo.acceptance.npc → beidou.quests.npc-dialogue`：`EnterMTSHandler`、`NPCMoreTalkHandler` 仍驱动原生脚本与 CM；MCP 自己控制开放路径和回答资格。
- `solo.acceptance.close → beidou.identity.native-character/characters`：沿原生 `Client.disconnect` 保存退出，再读持久角色核验所声明的字段。

关系放在 from 节点所在 YAML。北斗原生角色、场景、背包、钱包和接口只引用对应上游节点，不在 Solo YAML 再定义副本。运行体没有对 V1 全体机器人已使用共享 lane 的断言。

## 未映射为已完成的边界

- [#139 同壳 MCP 首环](https://github.com/Diluka/SoloMapling/issues/139) 已由 [PR #142](https://github.com/Diluka/SoloMapling/pull/142) 合并并完成隔离与实际部署验收，覆盖同一根宿主下的角色、聊天、NPC 与观察接线。该记录不扩大为 #140、移动/战斗或真实客户端玩法完成；既有 #117～#119 与后续切片仍按各自证据核对。
- 暂停/恢复、卸载重挂、多实例与复杂关闭的机制源码存在；完整 MCP 次环实际兼容仍由 #140 独立验收，未扩张为已完成玩法能力。
- 礼包 accept、完整 NPC 菜单/付费目的地、SocialBot 完整交互、移动、命名 recipe、生产自主练级/巡逻/刷怪及 PQ 通关不作为 MCP 当前已交付节点。
- 当前只开放中心菜单 0（福利确认）、71（出行分类）和分类菜单 2（城镇菜单）的已审无收益选择，以及福利 decline；城镇目的地未开放。旧版、截断或歧义菜单不猜测选择。
- 关闭存档核验仅覆盖 identity、level、exp、hp、mp、meso、job、native_saved_map、party；不能由这项核验推导全库或全部玩法状态一致。
- 观察的 runtime 安全事件、原生包序号、operation 游标和 dialogue_version 保持独立；现有诊断投影不视为完整 #120 事件平台交付。
- 未访问正式或隔离数据源，未启动/重启游戏；实际部署状态仍以本地验收记录和运行时 capabilities 为准。
