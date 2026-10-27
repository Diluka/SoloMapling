# 执行作用域、角色命令时序与取消排空

**实现范围：[#118](https://github.com/Diluka/SoloMapling/issues/118) 的公共执行机制与 MCP 接线。交付状态和各层验收证据以该 Issue 为准；代码合并或定向测试通过不代替完整构建、CI、隔离及实际部署验收。** 本文说明内部 Java 接口和消费责任。前置上下文及已交付的 #117 见[共享游戏基建](README.md)；本轮不新增 MCP 工具或动作。

## 责任与入口

公共实现位于 `beidou-integration/src/main/java/soloMapling/game/execution/`，直接供 Java 消费方调用。它记录工作和资源归属、限制普通动作进入、串行执行角色短命令并确认实际排空。

| 类型 | 提供的机制 | 消费方仍负责 |
|---|---|---|
| `ExecutionScope` | 固定世界 token、调用方 runtime/module 身份、工作句柄、取消、结果发布和排空账本 | 模块业务、权限来源、原生动作资格及结果含义 |
| `ScopeResources` | 资源登记握手、精确对象归属、取消信号和专用清理许可 | 实际创建、退订、取消、关闭及释放事实核验 |
| `CharacterCommandLane` | 一个实际角色绑定的有界串行命令入口与清理预留 | 选择已获授权的模块、冲突资源仲裁和优先级 |
| `AcceptanceActorExecution` | MCP 的 scope/lane/原生角色资源薄适配 | JWT/admin、owner/profile/session、外部 request_id、历史及原生账号存档 |

`Limits(maxWork, maxResources, maxDiagnostics, maxCleanupWork)` 显式配置容量，调用方必须传入四项，不提供三参隐式默认：`maxWork` 限普通活跃工作，`maxCleanupWork` 是独立的有界清理额度，总工作上限为两者之和。`maxWork >= 1`，其余参数非负；`maxResources > 0` 时 `maxCleanupWork >= 1`，避免普通任务填满账本后无法收尾。没有资源预算时可保留闲置清理额度，但不会因此取得资源权；角色 lane 另有独立的物理清理槽预留。`ScopeSnapshot.acceptedWork` 仍是普通与清理活跃句柄总数，可高于 `maxWork`，诊断记录的 `kind` 区分两类。

`OwnerStamp` 的两项对象身份由调用方提供，分别表达自身 runtime 和 module 代次；不与世界 token、MCP session、operation ID 或包序号混用。构造 scope 或声明 Grant 不会产生角色业务权限。公共层不依赖验收开关、全世界目录或 MCP 操作历史；V2/PQ 通过内部 Java 适配接入。

## 工作的受理、执行与结束

调用方先以 `acceptWork()` 接受一次工作，再将该 `WorkHandle` 交给执行入口。相同内部句柄只派发一次；新的句柄可以表达正常重复命令。外部 request_id 去重仍属于 MCP。

`runTracked(work, body)` 跟踪实际 body 的进入和退出，`publishOnce(work, apply)` 则在作用域、世界和取消状态仍允许时，准入一次结果应用。两者分开：计算已经开始，不会自动取得后续发布资格。已合法进入的 apply 会作为真实执行体继续计入排空；随后 beginDrain 不能撤回它或已发生的副作用，消费方仍须在自身业务写入前核对所需状态。MCP 的 apply 在自己的 monitor 内重验取消与同世界条件。回调、结果应用、取消适配器和完成通知在公共账本锁外执行。

| 观察项 | 能说明的事实 |
|---|---|
| `cancelRequested` | 已请求停止；不证明 body、线程或原生动作已退出 |
| `queueDeparted`、`bodyState` | 任务是否已离队，以及 body 是否真实进入、退出 |
| `carrierSettled`、`settled` | 承载该任务的队列尾部、结算回调及相应工作是否完成 |
| 资源 `Report` | 仍待握手、已附着、释放未核实的资源数量 |
| `ScopeSnapshot.unloadComplete` | 工作、实际执行体、预约回调和本 scope 的资源均已排空 |

`Future.cancel()`、`Future.isDone()` 和 MCP operation 的终态均不能替代上述完成证据。已取消但尚未实际离队/结束的任务继续占用物理容量。长时间导航或攻击应返回可管理的任务/资源句柄，将持续工作登记到 scope；不要在角色短命令里阻塞到最终游戏目标完成。

`beginDrain()` 停止普通工作受理、拒绝尚未获准进入的普通结果发布，并向登记资源发送至多一次取消信号。已合法入场的实际执行体继续计入排空，不把取消当效果撤回。`awaitDrained(timeout)` 分别返回 `DRAINED`、`TIMED_OUT`、`SELF_WAIT`、`INTERRUPTED` 或 `UNVERIFIED`。当前调用栈中仍在执行本 scope 的 body、发布、结算或取消回调时返回 `SELF_WAIT`，不能等自己退出。超时和未核实状态保留债务，不能报告卸载完成。

## 资源登记与精确清理

创建订阅、定时器或其他资源前，使用 `beginRegistration(kind)` 登记；原已受理的工厂 body 通过 `beginOwnedResult(origin, kind)` 登记其返回资源。后者必须来自该 scope 正在执行的普通 body，不能用清理工作繁殖新资源。

登记只允许完成一次握手：

- 创建成功，以 `attach(exactIdentity, nonblockingCancelSignal)` 附着实际对象；已经开始排空时，迟到附着也会触发取消，但仍须核实释放。
- 已证明没有创建资源，以 `confirmAbsent()` 记录不存在。
- 部分创建或异常后无法证明不存在，以 `creationFailed(fixedReason)` 保留 `UNVERIFIED`，不能将失败当释放。

取消适配器应迅速发出信号，不在信号回调中阻塞等待。资源释放由消费方核实：EventBus 需精确退订并关闭迟到回调的业务入口；定时器需取消且跟踪已经进入的真实 body；借用的线程池不能随一个模块卸载而关闭。

`Registration.acquireCleanup()` 返回同一未结束的 `CleanupAttempt`，避免并发重复清理。其 `DrainPermit` 只授权该登记的精确对象，在合法执行体中调用 `requireExecuting(exactIdentity)` 后才能进行对应原生清理；它不能充作普通角色动作许可。`confirmReleased(permit, exactIdentity)` 记录已实际进入的清理尝试完成释放这一事实，不会自行退订、关闭或执行原生写入。

如果旧清理已合法进入并真实释放了旧精确对象，即使随后世界换代，仍可记下该释放事实。新的原生入口及每次原生轮询继续检查清理窗口；记录事实不会续期旧权限。没有取得 Handle 的部分创建回滚也必须核对原 pending 登记及真正进入的工厂 body，不能通过“未知失败”取得任意清理权。

## 角色命令与调用方控制权

同一实际角色绑定共用一条 lane，短命令按受理顺序串行；不同角色使用各自的 lane，不经过全服单队列。`declareGrant(scope)` 记录调用方已经授予的控制权，`bindGrantedPermit(scope, characterRef, grant, nativeVerify)` 将它绑定到精确角色及原生复核。多个合法 Grant 可以并存，lane 不将角色选为某一个模块的独占资源，也不决定哪种模块优先。

V2 协调器自行判断资源是否冲突。允许并行拥有的模块可以各自持有 Grant，其实际角色写命令仍按同一 FIFO 执行；冲突交接则由调用方先撤旧 Grant、确认相关执行退出，再授予后继模块。`revoke()` 只撤该 Grant，不自动 `beginDrain()`，也不停止别的 scope；模块整体卸载仍由消费方安排 scope 取消和资源清理。撤销前已受理的 carrier 仍须完成真实结算；撤销后新递交旧 Grant 的工作在锁外立即拒绝并结算，不进入 FIFO 占用其他模块容量，也不续记旧 Grant 的承载债务。

`Grant.awaitReleased(timeout)` 只等待本 Grant 关联的执行、进入许可及必要的本 scope 初始化/清理承载，不追随后继 Grant 的普通工作。若已释放可直接成功；若尚需等待且调用者正处于本 lane 的承载执行中，返回 `SELF_WAIT` 防止在 FIFO 内等自己。一个 Grant 的撤销/释放不能被另一模块持续提交的普通任务无限拖住。

lane 的容量和清理预留由构造方明确提供。借用执行器必须满足有界、异步、不重入、单串行及受理前明确拒绝的约束，不能把普通并行线程池直接作为载体。`close()` 是终止入口，会撤销本 lane 所有仍存活的 Grant；它不代表已经排空。lane 只关闭自己创建的执行器，借用的执行器仍归调用方所有。

## 世界停止的专用窗口

共同 stopping 入口调用 `ExecutionScope.tryBeginWorldStop()`，经 `WorldEpoch.stopTransition()` 原子地把实际 active 来源变为 stopped，并生成构造不公开的 `WorldStopTicket`。同一次停止复用同一 ticket；来源不明的 inactive 世界返回空，不更换 token，也不制造清理权限。需要严格结果的内部入口可用 `beginWorldStop()`，未知停止会抛固定 `unknown_world_stop`。

scope 只有在 `acceptedEpoch == ticket.sourceEpoch()` 且当前仍为该 `stoppedEpoch()` 时才能 `joinWorldStop(ticket)`。窗口允许收尾原来拥有的精确资源，同时拒绝普通动作。下一次世界启动后，旧 ticket 和旧清理入口失效；旧 quarantine 不能借新世界的停止 ticket 续权。公共 API 没有 `ignoreEpoch` 参数。

MCP 停服先关闭受理并处理所有 session，再使用同一停止票据安排各自清理。一个旧 quarantine 与当前票据来源不符时保留原债务，不能阻断其他当前 session 的正常停止。已有进入的清理可以记录释放事实，尚未核实的 profile 继续保留 quarantine。

## MCP 接入与验收边界

`AcceptanceActorExecution` 为每个 MCP session 维护 scope 和唯一角色 lane；OPEN、ACTION、CLOSE 使用同一执行顺序。OPEN 尚未拿到角色时只用一次性 `AdmissionPermit`，不具有普通角色命令权。实际 Handle 在首次当前性校验前先附着到原 OPEN 的资源登记，校验失败沿原执行体内联回滚，避免把清理排到自己后面并等待。

正常 ACTION 使用已绑定的角色上下文和控制许可，仍由 MCP 在受理及实际执行前检查 owner/session、场景与原生规则。首次 CLOSE 的执行与历史预留、7 个 ACTION/8 个队列槽/256 条历史均是 MCP 策略，公共 scope/lane 的容量由调用方配置。ACTION 额度在精确 work 的实际结算完成后归还，取消或操作终态不能提前退款。

关闭以对应登记的 `DrainPermit` 调用原生后端；原生注销、对话清理和存档回读均核实后才确认资源释放。相同未结束 cleanup work 继续复用，结算后才允许有限重验，不能因回调先清标记而把重复提交悬挂在 `QUEUED`。

测试入口见[执行测试目录](../../beidou-integration/src/test/java/soloMapling/game/execution/)、[MCP 薄适配测试](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/AcceptanceActorExecutionTest.java)和[服务回归](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/ActorControlServiceTest.java)。其中 Bot、EventBus 和定时器合同使用真实原生资源及受控宿主路由替身；它们与实际普通角色的游戏后端证据分开记录，不代表完整 V2/PQ 已运行。

关闭 #118 需要完整构建、CI、隔离普通角色/同 JVM 世界停启及实际部署证据，结果在对应 Issue 留存。[#119 普通聊天原生动作与证据](native-actions.md)、[#120](https://github.com/Diluka/SoloMapling/issues/120) 按需事实/事件、[#121](https://github.com/Diluka/SoloMapling/issues/121) NPC 会话和 #122～#129 各动作仍分别实现、验收；公共作用域加入不扩大当前 MCP 工具能力。
