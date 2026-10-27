# 原生动作执行与结果证据

**实现范围：[#119](https://github.com/Diluka/SoloMapling/issues/119) 的公共普通聊天执行及 MCP/Bot 薄适配。交付状态和各层验收证据以该 Issue 为准；代码合并或定向测试通过不代替完整构建、CI、隔离及实际部署验收。** 本轮保持七工具和 `chat/npc_center_open/npc_cancel/npc_reply` 四动作，不开放任意 handler、原始包、脚本或新聊天种类。

前置接口见[角色与场景上下文](README.md)及[执行作用域与角色时序](execution-scope.md)。公共实现由 Java 消费方直接调用，机器人不经 Python、HTTP 或 MCP 发出自身动作。

## 范围与责任

普通聊天是首个共用原生动作。公共层负责受控参数、实际角色/场景复核、原生派发环境、一次执行和类型化结果；MCP 与 Bot 各自提供身份及证据适配。

| 层次 | 保留的责任 |
|---|---|
| 公共动作层 | 固定普通广播入口、原生资格与字符集、派发检查点、证据归约、异步结果 |
| MCP | JWT/admin、owner/profile/session、lease/GM0/排 bot、外部 request_id/历史、操作 JSON 与错误映射 |
| Bot/V2 | 精确 BotRegistry 身份、模块 Grant、行为与预设、优先级/资源仲裁、结果如何应用到模块状态 |
| 后续共享叶子 | #120 按需事实/真实事件来源，#121 NPC 来源/版本/路由；#122～#129 各自的原生动作规则 |

本轮不迁移整个 `NativeActorBackend` 或 `ActorControlService`，不批量改造 V1 的全部发言点。原 V1 直接广播路径不能因此冒称已经经过公共原生规则；NPC、账号登录/存档及礼包/出行资格仍由原消费侧负责。

## 公共 Java 入口

源码位于 `beidou-integration/src/main/java/soloMapling/game/action/`。入口见 [NativeActionService](../../beidou-integration/src/main/java/soloMapling/game/action/NativeActionService.java)，结果类型见 [NativeActionResult](../../beidou-integration/src/main/java/soloMapling/game/action/NativeActionResult.java) 和 [ChatEvidence](../../beidou-integration/src/main/java/soloMapling/game/action/ChatEvidence.java)，只提供普通聊天的受控入口：

```java
NativeActionService service = new NativeActionService();
NativeActionService.ActionHandle handle = service.prepareChat(
    server, lane, controlPermit, work, sceneRef,
    new NativeChatAction(text), evidenceSource);
NativeActionService.SubmissionReceipt receipt = service.submitChat(handle);
CompletionStage<NativeActionResult> result = handle.result();
```

`prepareChat` 接收 `Server`、已有 `CharacterCommandLane/ControlPermit/WorkHandle`、`GameContext.SceneRef`、`NativeChatAction` 和 `ChatEvidenceSource`。`sceneRef.actor()` 必须是控制许可所持的同一个角色引用对象，epoch 与 work 一致；不能拿同 CID 的新引用替代。`validateChat(characterRef, text)` 只校验普通文本/编码，不授予控制权，实际出队仍重验上下文。

| 类型/访问器 | 语义 |
|---|---|
| `ActionHandle.receipt()` | 同一提交回执，状态为 `PREPARED/SUBMITTING/ENQUEUED/REJECTED`；`workId` 是该 scope 内工作号 |
| `SubmissionReceipt.rejection()` | `NONE/CAPACITY/PERMIT/EXECUTOR/WORK_ALREADY_BOUND`，不把排队受理当动作成功 |
| `ActionHandle.dispatchStarted()` | 实际原生派发检查点，非消费侧的“开始执行”标记 |
| `ActionHandle.result()` | `CompletionStage<NativeActionResult>`，只读事实结果；不阻塞调用线程 |
| `NativeActionResult` | `outcome/dispatch/senderCharacterId/cancelRequested/handlerError/fixedReason/evidence` |
| `ChatEvidence` | `source/sourceId/epoch/cursorBefore/cursorAfter/matchedSequence/completeness/uncertainPacket`，`matched()` 表示存在匹配序号 |

结果的 `Outcome` 为 `SUCCEEDED/FAILED/CANCELLED/INDETERMINATE`，`Dispatch` 为 `NOT_STARTED/STARTED`。证据 `Source` 为 `SELF_NATIVE_CHAT_PACKET/OBSERVER_NATIVE_CHAT_PACKET/NONE`，`Completeness` 为 `COMPLETE/PARTIAL/UNAVAILABLE/NOT_APPLICABLE`；`NONE` 不携带游标或匹配，只表示没有可用证据，不是完整负证据。

`ChatEvidenceSource.none()` 用于没有收包保证的默认路径；`packets(source, sourceId, packetFeed)` 只消费已有解码窗口。`PacketFeed` 提供 `requireCurrent/latestCursor/window`，并通过 `completeReception()` 显式声明连续接收保证；该方法默认返回 false，不能仅因历史窗口可读就取得完整负证据。身份保护仍由薄适配负责。每条执行创建短命 `Probe`，完成有界取证后关闭；它不是 handler 钩子、原始包入口或常驻事件订阅。

## 一条执行链

调用方先在自己的边界确认授权，再将已有 scope、work、角色 lane、控制许可和内部 SceneRef 交给公共聊天入口。公共入口只向该 lane 提交一次；MCP 不先排一次 ACTION、再在队列内部二次提交并等待自己。不同角色继续使用各自 lane，不引入全服动作队列或第二套执行器。

实际出队后重验工作、Grant、精确原生角色及场景，随后核普通文本的长度、命令通路和严格字符集编码。禁言、限频等规则沿原生 `GeneralChatHandler`；不改 V1 的 `Character.loggedIn`，也不为 Bot 绕过原生校验。

证据 checkpoint 在原生派发前取得。实际派发检查点位于原生 `validateState` 通过之后、调用 `handlePacket` 之前；MCP 开始执行操作或公共入口返回排队回执，都不代表 handler 已进入。派发设置并恢复原来的 ThreadLocal Client，临时 ByteBuf 在所有路径恰好释放一次；异常正文不进入共享诊断或终态历史。

派发前的上下文、编码、自身证据初始化或原生状态校验失败可返回 `FAILED/NOT_STARTED`，不冒称进入 handler。handler 返回 void、未抛异常、日志成功或队列受理成功都不是聊天成功。派发后的日志/钩子异常也不能抹掉此前已经发生的真实广播，仍须收集本次后态。

## 证据来源与完整性

聊天正证据必须是本来源派发前游标之后的新原生聊天包，匹配发送者 CID、原文、`show=0`，且正文完整未截断。历史包、其他角色、错文本或错 show 都不能证明本次动作成功。

| 证据情形 | 结果含义 |
|---|---|
| 真实新匹配包 | 已观察到广播成功；之后的读取故障或 handler 异常不能抹掉此事实 |
| MCP 自身完整可信窗口、显式连续接收保证、无不确定聊天包、handler 无异常且没有匹配 | 保留既有“未广播”失败语义 |
| 已派发后 handler 异常、观察不可用、窗口缺页/截断或必要解码失败，且无正证据 | 结果未知，不能从没看到包推导未发生副作用 |
| Bot 默认不收包，或外部观察者没有匹配 | 结果未知；不能按无异常自动报成功或改用“自身回声”标签 |

SELF 的“完整”同时要求原窗口完整与 `completeReception()` 为真。MCP 从 Client 底层单调的 transport closed 状态确认接收保证；接收已经关闭时，历史 `packetEvents` 仍可能可读、未过期且未截断，但无匹配只能得到 `PARTIAL` 并保持结果未知，不能误判未广播。coverage 读取异常且无匹配为 `UNAVAILABLE`。匹配器先扫描实际窗口保留精确新匹配，再核 coverage；已经读到的正证据仍可确认成功，不伪造 `cursor_expired/truncated` 解释接收关闭。

历史缓冲可读但接收已关，与派发前 checkpoint 初始化读取抛错分开处理：前者只是缺少完整接收保证，不改写为“初始读取失败”；后者在 SELF 路径仍沿既有 `FAILED/NOT_STARTED` 拒绝规则。Result、ChatEvidence 和 HTTP 字段不因此增加，coverage 通过证据完整性表达。

来源必须区分自身原生包、观察者原生包和无证据。完整性与来源分别记录，不能通过换标签补成功。证据 epoch 与本次捕获值不符时弃用该证据，不刷新旧句柄。只有同一证据源内的游标可比较；它们不与 operation 事件序号、世界 token 或 V2 模块代次互换。

派发时的 SceneRef 和观察时的当前场景分开处理。聊天包本身不携带地图身份，不能用后来的观察场景冒充历史包发生地点。派发后换图不会抹掉已经取得的广播正证据，也不会证明它发生在原请求场景。

## 异步结果、取消与重试

提交立即返回内部执行句柄，结果在实际队列执行和取证后通过 CompletionStage 完成。首个聊天动作只在短命令内完成广播与有界取证，不增加长时间回声轮询器或通用事件 future；后续移动/战斗不能占住 lane 等最终目标。

同一内部句柄重复提交立即返回原回执，不等待当前提交，也不会再次派发；同一个 work 用另一个句柄再次提交，会得到 `WORK_ALREADY_BOUND` 拒绝，不改动已拥有该 work 的原动作。新的合法重复聊天使用新 work/句柄，不按正文去重。外部 request_id 幂等仍由 MCP 负责，公共结果不是第二套操作历史。

未进入 handler 的排队取消可报告未派发取消；派发后的取消只追加取消事实，不能声称撤回已广播消息。未知副作用不自动重放、回滚或补偿。内部事实结果与 scope 的受控结果应用分开：旧世界已经发生的事实可以如实记录，但 V2 向模块状态应用结果仍须走 `publishOnce` 及当前代次检查，不能复活旧模块或据此发新动作。

结果完成不等于 work、carrier、结算回调及资源均已排空；MCP 仍在精确 work 实际结算后归还 ACTION 额度，CLOSE 预留和取消不提前退款。具体账本见[执行与清理参考](execution-scope.md)。

## MCP 与 Bot 薄适配

MCP 的统一宿主/控制模块接入见 [#139 MCP 模块说明](../mcp-acceptance/runtime-module.md)。本节说明底层原生来源、取证与 JSON 投影，不表示 Session/helper 继续私有持有 lane、scope 或原生角色资源。

MCP 的 `NativeActorBackend.prepareChat(handle, lane, permit, work, action)` 保留普通账号资格并捕获固定 SceneRef，再交公共服务准备；`submitChat(actionHandle)` 只转发同一提交，旧同步 `chat` 入口不再形成另一条派发路径。NPC/login 仍保留原私有 dispatch/withClient，不因本轮扩大为任意原生动作入口。

包内适配 [AcceptanceChatEvidence](../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/AcceptanceChatEvidence.java) 只提供 `SELF_NATIVE_CHAT_PACKET`，核原已受理命令的精确 Handle/Client/Character、账号 lease 及在线 Client 归属。派发后的证据读取不借旧 Map 或 active epoch 检查抹掉此前已经发生的广播；新动作仍在派发前严格校验上下文。

[AcceptanceChatResults](../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/AcceptanceChatResults.java) 仅将类型结果投影到 MCP 既有字段，不再猜 Map 决定操作终态：

| 内部事实 | MCP 既有投影 |
|---|---|
| 实际 `Dispatch` | `native_dispatch_started` |
| 匹配结果和发送者 | `echo_matched/sender_character_id/echo_sequence` |
| 本来源前后游标 | `packet_cursor_before/packet_cursor_after` |
| 广播、未知或未广播 | `observed_outcome` |
| 原生派发后异常 | 可选 `native_handler_error` |
| 中立 `fixedReason` | 映射原有 actor 错误别名，不输出异常正文 |

`source/completeness` 属于内部共享契约，本轮不新增 HTTP 字段或 MCP 工具动作。operation 的终态由服务消费类型化 Outcome，并保留实际派发后外部取消的 `cancel_too_late`；它不撤回广播，也不改写已经取得的事实结果。实际派发、事实结果完成和 work 真实结算分别处理。

Bot 门面 [BotNativeActions](../../beidou-integration/src/main/java/soloMapling/beidou/BotNativeActions.java) 接受同一个 `NativeActionService`。`prepareChat(server, binding, lane, grant, work, scene, action[, evidenceSource])` 消费已有 Grant，绑定精确 Bot 角色许可，并在实际出队时再次执行 BotGameContexts 场景检查；`submitChat(handle)` 转交同一公共服务。生产适配不依赖 AcceptanceClient，不安装常驻订阅、收包历史或新执行器。

默认省略 evidenceSource 时使用 `none()`，Bot 的合法派发没有回声保证，结果为未知。可选观察者源由调用方负责精确观察者 Client/场景/epoch；观察者失效仅使 `OBSERVER_NATIVE_CHAT_PACKET` 证据成为 `UNAVAILABLE`，不成为发送 Bot 的控制授权，合法 Bot 动作仍可派发但保持未知。MCP 自身初始证据失败则在派发前拒绝，不能混用两种策略。

## 终态保留与验收

原生 Client/Character/Map/SceneRef、短命证据 probe 和聊天正文只属于本次执行。公共句柄在完成 result、通知消费回调之前先清掉强持输入的 Pending，probe 也先关闭并清除正文/feed；终态只留标量回执/结果及 work 弱引用。当前 body 栈的局部引用继续到真正退出，不能因 result 已完成就报告引用或工作已排空。终态只保留不可变的来源、完整性、派发与取消事实、固定原因及有界游标/匹配摘要，不保留原始包或异常正文。MCP 当前有效 session 的正文观察窗口仍按原权限和容量规则工作，关闭后不能凭终态历史读取正文。

测试入口见[公共动作](../../beidou-integration/src/test/java/soloMapling/game/action/NativeActionServiceTest.java)、[证据窗口](../../beidou-integration/src/test/java/soloMapling/game/action/ChatEvidenceSourceTest.java)、[MCP 原生聊天](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/NativeActorChatTest.java)及[Bot 薄适配](../../beidou-integration/src/test/java/soloMapling/beidou/BotNativeActionsTest.java)。MCP 回归保留正常原生自身回声、中文编码、禁言/限频、重复 ID、取消、NPC 顺序和同 CID 保存重开。Bot 第二适配分别记录默认 no-op 收包的未知结果及同场景专用观察者的新包正证据；薄适配合同中的 BotClient/Character/Map/Storage 为真实对象，AcceptanceClient 观察者仅作对象夹具登记、未走原生登录；Server/World/Channel、时钟、日志及手动串行执行器使用替身。该合同与隔离真实服务证据分开，不把观察者变成所有 Bot 的常驻运行依赖。

完整 V2 组合、真实战斗 #127、按需事件 #120、NPC 会话 #121 及 PQ 通关仍在各自任务验收；本叶的公共动作或 Bot 薄适配通过不替代它们。保持北斗 submodule 原样，独立实验使用临时库或一致副本，实际部署验证沿既定授权与备份流程。

本轮提炼以 `9c8cbd0e971ef4def11596e8d855cdcd52974893` 为基线，来源为 Solo 自有的 `NativeActorBackend` 普通聊天与派发环境、`ActorControlService` 的动作/结果映射，以及 `BotGameContexts`/`BotClient` 的身份差异；不从北斗子模块改写或移入补丁。公共入口为 `NativeActionService`，终态类型为 `NativeActionResult/ChatEvidence`；MCP/Bot 的具体适配与验收入口见本文对应说明。
