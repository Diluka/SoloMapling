# 统一角色运行体与机器人模块

本文描述当前源码 API。#205 在既有运行体上补充模块定义、实例装配和受管导出；
候选实现、源码测试、隔离验收及实际部署分别以对应 Issue 的记录为准。
类型及初始人口配置见 [#263 的人口参考](population.md)，当前场景目标导航见
[#122 的导航参考](navigation.md)。
阶段入口为 [M1 #79](https://github.com/Diluka/SoloMapling/issues/79)，MCP 首环与次环分别为
[#139](https://github.com/Diluka/SoloMapling/issues/139)、[#140](https://github.com/Diluka/SoloMapling/issues/140)。

当前包含通用角色壳、MCP 控制接入、配置装配、环境实例创建/移除和导航模块。生产自主练级、巡逻、刷怪、
跟随及其策略按各自任务迁移。模块定义、装配方案和共享函数见[机器人模块参考](modules.md)；
其中的最小示例仅说明协议接线。

## 分工与迭代

- 运行体会话负责 `soloMapling.game.runtime` 的宿主、模块协议和机制测试。
- MCP 会话负责后端 MCP 控制模块、`AcceptanceActorExecution`/`ActorControlService`
  接入和外部协议/真实原生验收。
- 既有单壳聊天/观察、暂停、卸载和根关闭继续使用原运行体。
  装配方案随源码测试和验收，业务模块在统一契约上逐项迁移和验收。

## 一个实例持有什么

`CharacterRuntime` 在原生 OPEN 前创建并拥有：

- 固定 `WorldEpoch` 与运行身份；
- 一个根 `ExecutionScope`、原生角色资源握手及来源适配；
- 唯一 `CharacterCommandLane` 和它借用的承载 executor 的最终关闭责任；
- 模块定义、实例配置、共享导出及各模块独立作用域/控制 Grant；
- 安全的运行体快照和最近64条诊断事实。

它不拥有第二份等级、背包、队伍等角色档案，不自行选怪或安排活动。
`RoleBinding.observe()` 每次读取原生来源事实；宿主只投影这些事实。
原生包序号、MCP operation 游标、运行体事件游标与对话版本相互独立。

## 来源打开与最终关闭

```java
CharacterRuntime runtime = new CharacterRuntime(epoch, options, ownedSerialExecutor);
ExecutionScope.WorkHandle open = runtime.acceptOpeningWork();
runtime.submitOpening(open, () -> {
    CharacterRuntime.OriginResource resource = runtime.reserveOrigin(open);
    // 来源适配器在真正原生创建之前拿到这次握手。
    // 失败时按原生证据 confirmAbsent/creationFailed；有实际角色时 adopt(binding)。
    runtime.bindAdoptedOrigin(); // 普通账号/租约/登录等检查由 binding.requireCurrent 保留。
}, settled);
```

`OriginResource.adopt(RoleBinding)` 只接受精确来源身份与固定角色引用。
adopt 不等于在线成功；`bindAdoptedOrigin` 才在来源核验后进入 BOUND。
同一个实际角色对象不能绑定到第二个活宿主。

已受理的来源创建即使在世界停止或重启后才返回，精确角色结果仍须登记到原宿主的根资源。
保留来源原样捕获的角色引用，不刷新世界代次；登记债务不授予在线绑定或动作资格。
原停止窗口仍有效时可按原根许可清理；新世界已开始时拒绝旧清理入口，保留未释放债务，
不能借新世界的许可释放、谎报不存在或宣布 CLOSED。

`RoleBinding` 保留普通验收角色与环境机器人各自的创建、身份核验、原生事实及退出政策。
MCP 外部 JWT、profile、request_id、幂等历史和操作记录不移入宿主。

最终退出由 `beginClose(stopTicket)` 停止全部模块，然后对根登记资源调用
`acquireOriginCleanup` / `submitOriginCleanup` / `releaseOrigin`。
`RoleBinding.release(DrainPermit)` 只有核实自己的原生退出/存档约定后才返回 released=true。
原生 OPEN 失败的内联回滚也可在根 scope 内跟踪同一个精确 cleanup attempt，不能再登记第二份角色资源。
`awaitClosed` / `tryFinalizeClose` 仅在模块、根工作、资源和物理 lane 都已排空后确认 CLOSED。
SELF_WAIT、未证实创建/释放和失败债务不能当作关闭成功。

## MCP 模块与唯一动作链

`ModuleDefinition<C, M>` 声明类型、有限控制资源、scope 额度、工厂
及共享函数。`ModuleSelection<C>` 选择实例 ID、配置和启用状态。
实例 ID 和导出名称在宿主内唯一。模块组合及配置由源码中的装配方案确定，并通过测试和验收；
机器人正常工作周期内保持该组合，已有模块的任务、状态和能力可用性随运行变化。

MCP 来源绑定现有专用角色后，按固定类型名 `mcp_test` 从 `RobotPopulation.yaml` 装配。
默认组合为 MCP 控制、导航和人口能力模块；新增非自主能力模块时更新该类型配置。
`initial_population` 只用于本轮启动时创建环境实例，脚本通过 `robots.create/remove` 增减完整实例。
已有 MCP 角色的账号、profile、当前位置和保存退出继续由其原来源管理。

```java
var assembled = population.assemble("mcp_test", runtime);
var mounted = assembled.find(McpControlModule.class).orElseThrow();
McpControlModule mcpModule = mounted.instance();
CharacterRuntime.ModuleRef module = mounted.reference();
CharacterRuntime.ModuleContext context = runtime.context(module);
ExecutionScope.WorkHandle work = context.acceptWork();
```

这里的 `population` 是本轮世界已加载配置的人口服务；模块名称及组合取自对应类型。

短原生调用使用 `context.submit(control, work, preflight, body, settled)`。
已有 `NativeActionService` 会自己向 lane 提交一次，因此通过
`context.prepareNative(control, work, preflight, preparation)` 只准备原始 ActionHandle，
再调用原有 `submitChat`。不得先在宿主排队，再在 body 内 submit/join/get 自己。
这个准备入口是受信任 Java 适配器边界，不向外开放 handler、原始包或任意脚本。

MCP 原样映射已有 receipt、原生结果和 `work.completion()` 的真实结算，
不在壳外再造一份可独立改写的动作执行状态。
`context.recordResult` 记录同角色/同世界的结果事实；模块状态更新另走
`publishOnce` 和有效代次检查，不能用迟到原生事实重新激活旧模块。

## 暂停、卸载和角色关闭

- `stop(ref, PAUSE)`：停止该次模块执行、撤 Grant、取消/排空自己的 scope，保留配置、模块对象和导出声明。
- `activate(ref)`：仅在旧 scope 真正退出且许可有效后恢复；返回新代次的 ModuleRef/Context。
  旧 Context 不会重新获得资格。
- `stop(ref, UNLOAD)`：排空并完成卸载 hook 后撤下导出；角色及宿主 lane 由根继续管理。
- `setEnabled` 处理配置许可；暂停/条件不满足不会反改这个开关。
- `beginClose`：所有模块退出后按来源关闭原生角色，最后才关闭唯一 lane。

模块回调必须短且非阻塞，自己的异步任务/资源归自己的 scope。
激活、停止和卸载回调自身也属于未退出的生命周期工作；线程被抢占或回调未返回时不能确认卸载完成。
卸载清理 hook 异常时保留 `FAILED`、失败原因和未完成的清理责任，根关闭继续等待实际收尾。
停用/卸载不回滚已发生的游戏结果。MCP 模块卸载后，外层合法 profile 所有者仍须能关闭宿主。
首轮 MCP 策略保留属于原生 Client 的既有对话：暂停/卸载只撤控制并排空命令，
显式 `npc_cancel` 或根 `actor_close` 才按正常规则取消/清理。模块恢复后重新观察来源与最新版本，
不能复活旧模块已准备的动作。该策略的实际兼容/卸载证据由 #139/#140 分别验证，
不把聊天首环通过扩大成所有交互都已验。

## 观察与证据

`snapshot()` 提供宿主、模块、作用域及控制资源，`events()` 返回最近64条非消费诊断事实快照。
窗口不保存聊天正文、原生对象或异常正文。角色包/领域结果仍可作为来源证据，由 MCP 授权投影。
MCP 观察要从同一个宿主和原生来源读取，不建立另一份角色事实账本。
当前快照不接收游标或分页参数；保留事件序号、截断前历史丢失标记及稳定重读，不提供独立事件订阅。

本轮测试分为宿主机制合同、MCP 同壳接入、隔离原生验证和实际部署证据；
前一层通过不能代替后一层。已交付 #117/#118/#119 直接组合复用。

## 背包基本动作

[#200](https://github.com/Diluka/SoloMapling/issues/200) 的首片 #164 通过同一 `ModuleContext.prepareNative("inventory", ...)` 消费共享 `NativeInventoryActions`，只提交原 lane 一次。具体物品规则由北斗执行，宿主只负责控制权、工作和 `NativeActionFact` 安全摘要，不持有第二份库存。参数、结果与存档验收见[背包移位](../shared-game-foundation/inventory-move.md)，各层验收及实际部署以 Issue 记录为准。

## 持续基本动作与关闭屏障

#200 的方向移动属于模块内有界编排，定时资源和父工作由原scope拥有，短tick沿原lane。`whenModulesDrained()`仅在宿主关闭且模块hook、scope、grant和资源真实退出后完成，回调锁外触发；它是排空事实，不授予根cleanup许可。根来源退出仍走精确原资源的cleanup attempt。其他短基本动作共用原作用域与lane，见[基本操作](../shared-game-foundation/basic-control.md)。

## 北斗脚本执行

[统一脚本入口](script-execution.md)让MCP在当前角色运行体内执行北斗脚本，直接指定 `kind/operation` 和真实上下文选择。独立纯JS模式及外层 `environment` 已移除；运行体继续消费原模块scope/work，在短命令lane之外执行，持久Context、本次调用和最终资源释放分别核验。

[#216的北斗脚本上下文](beidou-script-environment.md)复用真实 `cm/qm/pi/rm/em`、原文件和原生Java API。NPC/任务保留原管理器状态；门户、机关及角色自有事件使用独占引擎，避免单角色取消关闭共享环境。[脚本调试技能](../../skills/solomapling-script-debug/SKILL.md)导航实际适配器和原生源码，执行结果保留上下文标识与状态。原生引擎内的 `modules.help()` / `modules.call(name, args)` 与 Java `ModuleContext.help()` / `call()` 消费同一模块函数目录，详见[机器人模块参考](modules.md)。现有动作和管理入口保留，完整EIM与具体业务模块按各自任务验收；源码、CI、隔离和实际交付结果分别记录。
