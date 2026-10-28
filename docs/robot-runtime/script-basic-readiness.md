# 基本动作脚本化前的复用核对

核对基线：Solo `7f29824ff8465e44787543d89b4455cdcd840b13`、北斗 `75a86268738ca8845e06ea9d59ec36d15b22c91b`。对应 [#213](https://github.com/Diluka/SoloMapling/issues/213) 的实施前研究，以及 [#215](https://github.com/Diluka/SoloMapling/issues/215) 对旧机器人移动实现的复用核对。本页记录已核事实和建议次序，不表示游戏函数已经绑定或缺陷已经修复。

## 结论

已有引擎、动作和编排实现，应继续复用。新有界移动与旧移动重复了一部分姿态、地面判断和停止规则，#215 直接暴露了共通结束约定未共享的问题。不能用普通角色与 Bot 的身份差异，推导所有旧代码都不可复用。

当前运行体已有纯 JavaScript 沙箱、最小函数注册和真实收尾基础；它还不是完整游戏脚本环境。#213 应在这些现成设施上补受管宿主调用，不另造脚本引擎、角色执行队列或通用任务平台，也不用提前完成 #211 的跨模块共享环境。

## 旧机器人移动已有的实现

| 现成实现 | 当前行为及可复用内容 | 不能直接照搬的部分 |
| --- | --- | --- |
| `MovementCommands.executeMovement`、`BotMoveStreamHelper` | 录制 `.bin` 的同步顺序回放；正常结束、录制中断标志和到达 stopPoint 会注入停止包 | `BotMove` 直接走 Bot 解码、位置更新和广播，没有消费当前运行体 lane/Grant |
| `MovementPacketConstructor.createIdleStandlingPacket(x,y,fh,stance)` | 已有零速度、保留朝向并转站立姿态的纯字段构造；`CharacterStance` 已有步行2/3、站立4/5常量 | 现返回 `InPacket`，新派发器拥有 `ByteBuf` 的释放；应适配或小范围提炼构造数据，不复制另一份字节布局 |
| `BotMovementManager.planGroundAction`、GC driver | 地面不可走或墙边返回 IDLE；停滞放弃、无目标时 idle 并广播 | 包含导航、LOD、动态状态与直接位置更新，不是 #200 的有限方向输入本身 |
| `BotPhysicsEngine` 地面/墙判断、`idleOnGround` | 已有地形模型、清速度/移动状态、解析姿态的实现，可按实际消费者逐步提炼纯片段 | `BotMovementState` 与装饰策略不能整体作为普通角色的另一个运行体 |
| `BotTiming.chain().run/pause/stopUnless` | 已有一条虚拟线程上的顺序编排与阶段门禁，不需要为动作组合重新设计通用调度器 | `start()` 是 fire-and-forget、无结果/work/真实收尾句柄，异常只打印 |
| `TutorialBot` 同步流程 | 已明确同步寻路后再执行下一步，不能靠猜等待时长提前放行 | Bot FSM 的调度归属不能直接替代当前 script work |
| `GCMovement.move(..., onArrival)` | 已有非阻塞到达回调，能作为动作完成信号的实现参考 | 回调在 driver 线程直接运行；放弃移动只删除回调而不通知失败，不可直接当完整 Future，也不能让其持有并调用 Graal 闭包 |
| `BotTickService.awaitQuiescence`、录制取消检查 | 已区分真实 body 退出与 Future 取消，`BotHelpers` 等待会传播中断 | 这些账本属于旧调度器；新脚本继续使用现有 ExecutionScope，不复制一份 |

源码入口：

- [录制回放与停止](../../src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/MovementCommands.java)：`BotMoveStreamHelper`、`injectArtificialStopPacket`、`executeMovement`、`BotMove`。
- [纯字段 idle 构造](../../src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/MovementPacketConstructor.java)：`createIdleStandlingPacket(short,short,short,byte)`；[已有姿态常量](../../beidou-integration/src/main/java/org/gms/constants/game/CharacterStance.java)。
- [GC 地面计划](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotMovementManager.java)：`planGroundAction`；[GC 驱动](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java)；[物理模型](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotPhysicsEngine.java)。
- [顺序编排](../../src/main/java/soloMapling/server/BotTiming.java)；[TutorialBot](../../src/main/java/soloMapling/ArtificialPlayer/BotTypes/TutorialBot.java) 的同步说明与 `send_off` 流程；[GC facade](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java) 的 `enable/move/fireArrival/abandonMove`。

`GCMovement` 明确接受 raw Character，身份本身不是拒绝复用的理由。实际边界在于 `enable` 会创建独立 STATES、图/LOD 和 driver，并直接贴地传送；旧 `BotMove` 直接更新位置和广播。直接挂到普通角色脚本会绕过已确认的原生输入及运行体授权链。复用应保留规则与纯数据实现，原生写入仍经已有角色 lane。

## #215：重复实现遗漏了已有收尾约定

`NativeMovementActions.runStep` 的失败分支无条件设置 `idleRequested=false`。已有移动在合法地面边缘结束时，后续帧停止了，但原生 stance 仍可为 WALK，结果却固定包含 `movement_stopped=true`。#215 已有实际后态和独立内存复现；本研究只核对代码，没有重放正式场景。

最小修正方向是使用**当前执行链已经存在的 idle 路径**：已派发移动、同一世界/场景及控制权仍合法的边缘结束，向原 lane 提交 idle，等待实际结算，再返回原 `FAILED/movement_foothold_edge` 和已发生位移。结合已有姿态/帧构造消除重复规则，不为它再建停止执行器。

不能把所有异常改成 `finally` 强发 stop。旧录制执行也没有保证每个异常都发 idle；[已有取消测试](../../beidou-integration/src/test/java/soloMapling/server/RecordedMovementCancellationTest.java) 明确要求停服取消后不再发包。世界、场景或授权失效时，不得借收尾恢复动作资格；驱动已退出、原生 idle 已观察和角色最终退出要分别表达。

应补的回归包括：边缘前已有位移，失败后 idle 为真、stance为4/5且 lane/工作结算；世界或场景失效时不补发；旧录制及 GC 正常/中断路径保持各自约定。移动族的脚本等价验收和直接入口退役应以修复后的基线为准，但不必阻塞独立的背包移位脚本首环。

## 北斗脚本环境能复用什么

北斗 `AbstractScriptManager` 已经经 JSR-223 使用 `graal.js`；当前运行体也使用同一套已打包 GraalJS 依赖的 Polyglot Context，没有重新引入引擎的必要。

原生 NPC/任务/事件脚本是服务器作者编写的业务脚本：`cm/im/qm/rm/em` 等对象按入口注入，能访问原始 Client、Character、Map、Inventory及发物、换图、强制任务、刷怪；原生加载器还开启 Java host access 和类查找。它们不是与 #200 等价的角色输入 API。

例如 `AbstractPlayerInteraction.useItem(int id)` 只对角色施加物品效果，不按背包槽取物或扣库存，不能作为 #159 `use_item(source_slot)` 的替代实现。应继续调用已验的 `NativeInventoryActions/NativeItemActions/NativeAttackActions/NativeMovementActions`，而不是按同名函数照抄 NPC 接口。

原生 NPC/quest 的 dispose 主要移除管理器和 Client 引用；事件 schedule/dispose 属于事件管理器生命周期。这些不提供当前 script work、guest Context关闭和宿主动作真实排空的等价证明。其语言目录回退、可信文件脚本及适用调度规则可保留，但不能把宽权限 manager 当作 MCP 任意文本 runner。

原生依据为固定北斗的 `org.gms.scripting.AbstractScriptManager`、`AbstractPlayerInteraction`、`npc.NPCScriptManager`、`quest.QuestScriptManager`、`event.EventManager/EventInstanceManager` 和 `Client.removeScriptEngine`；历史调查另见 [#201](https://github.com/Diluka/SoloMapling/issues/201)。本轮重新核对了这些路径，未修改 submodule。

## 当前运行体入口具备的部分与缺口

[ScriptExecution](../../beidou-integration/src/main/java/soloMapling/game/script/ScriptExecution.java) 已有独立受限 Context、JSON 参数/输出/结果、纯 JS 期限与取消、guest 实际退出、Context关闭和原work结算。guest 不占角色短 lane，Java类查找、IO、脚本线程与原生访问关闭。[ScriptRegistry](../../beidou-integration/src/main/java/soloMapling/game/script/ScriptRegistry.java) 按精确 ModuleContext 管理目录；帮助与实际绑定来自同一条注册。当前生产游戏函数目录为空。

接入游戏动作前必须补的局部契约：

1. **本次宿主调用归属**：`ScriptFunction.Body.invoke(List)` 没有当前脚本 work、期限、停止状态或调用关联。脚本父 work 已占 carrier/body，不能再提交给原生 lane；原生调用需使用同模块 scope 的子 work，并关联本次调用。
2. **协作取消与真实排空**：取消需传到已启动动作，在受理及派发前检查本次调用已停止与否；等待仍在已有 guest/受管载体上，不占 lane，也不能只 `join()` 后指望 `Context.close(true)` 停掉任意 Java body。
3. **保留已发生事实**：现有调用后 `requireCurrent` 和最终非成功时 `result=null` 适合纯函数，但可能遮掉已完成动作。先记录调用结果，再禁止下一步；已完成、未执行、取消与未知副作用分别保留，不盲目重放。
4. **定义、校验、帮助同源**：现有 `parameters: List<String>` 只描述参数名，`Entry.invoke`只核 arity，不是类型和范围 schema。绑定需复用现有 typed 参数验证，并从同一函数定义输出帮助，不能维护第二份校验规则。
5. **有界事实投影**：宿主返回也受16KB限额。满包装备前后态可能超额，不能在动作之后只返回 `script_json_limit`。使用有界结果摘要或可追索调用事实，明确截断/完整性；不直接提高限额来掩盖事实丢失。

## 独立 JVM 探针

临时探针使用本地真实 CharacterRuntime、ScriptExecution和固定依赖，原生角色由明确的无I/O测试来源替代。Body中的counter只是纯内存标记，**不构成真实游戏动作证据**。

| 场景 | 实际结果 |
| --- | --- |
| Java host Body保持阻塞，取消并根关闭 | 300ms观察窗口内虽收到一次Java中断，completion仍未完成、父work/body仍在途、宿主CLOSING且原角色未释放；释放latch后才CANCELLED、引擎退出、Context关闭、work结算并最终CLOSED |
| counter先变1，随后PAUSE模块并返回JSON | counter仍1，最终为CANCELLED、result=null、module_not_active；当前结果没有宿主调用事实窗口 |
| 合法满包装备结果形状 | NativeBasicResult接受35,819字节结构；counter先变1再返回该结构后，脚本为RUNTIME_ERROR/script_json_limit、result=null；引擎和work全部正常收尾 |

这些说明现有基座诚实保留在途债务，但尚不能代替原生子工作取消及事实关联；不是在当前空游戏函数目录中确认了上线游戏漏洞。三个场景最终都释放上下文与工作，没有访问游戏或数据库。

源码与原始输出保留在本地 `.backups/20261028-135943-script-readiness-213-215/`，含基线、JDK和文件SHA256清单；原始编译及运行命令见其中 `run_probe.py`。同样结果保留在主工作树忽略的 `beidou-integration/target/script-213-research/`。

## #213 的推进顺序

1. 按 #215 修正合法结束的 idle 收尾；优先共享现成姿态与帧构造，并以真实消费者逐步提炼地面规则，不为修缺陷先整树迁入 GC 导航/LOD。
2. 在现有 ScriptExecution/ModuleContext 上补最小受管宿主调用契约，首先绑定 `inventory_move` 和必要有界状态读取。同前态核原生结果、正常保存重开、取消竞态和超限后的事实；这一环不依赖完整 #211。
3. 基本动作组合优先沿旧同步顺序脚本的模式：原生命令真实结果和工作结算后才放行下一步。需要非阻塞移动时，再用已有动作完成信号明确 start/等待/stop 语义；不要让driver线程执行Graal闭包，不把Promise/Future作为同步JSON完成结果，也不先设计通用任务平台。
4. 按动作族逐项等价验收、切换 SDK/调试脚本/帮助与 #201 资料，再退役对应直接入口。#213 涉及 #200 的九个基本动作；聊天/NPC四动作、角色生命周期、必要观察和独立管理工具仍按各自任务维护，不能直接删除整个 game_act。

本页不固定未验证的公开签名。首环可沿既有名称登记 `functions.inventory_move`，但状态视图、expected_context以及类型/范围说明必须与同一绑定定义和已有场景资格衔接；不得为脚本悄悄刷新旧世界、旧场景或旧模块代次。
