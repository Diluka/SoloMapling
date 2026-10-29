# 原生动作模块与普通复活

本文描述 #275 首环的当前候选源码契约，尚未代表最终隔离验收或正式部署完成。`NativeActionModule` 以语义明确的操作封装有限原生请求及结果确认，供主控、其他模块和受管北斗脚本消费。首个操作为普通复活，后续操作按实际需求独立声明输入、结果和完成条件。

## 模块与操作

模块类型为 `native-actions`，实现 `ActionModule` 职责标记和既有生命周期协议，配置为 `{}`。当前源码的长期 `mcp_test` 类型显式装配该模块；临时死亡、事件及材料测试类型使用不提交的 `RobotPopulation.local.yaml`，见[人口配置](population.md)。已有 MCP 角色仍沿原 `primary` 来源登录、保存和关闭。

普通复活操作由 `NativeActionModule.REVIVE` 声明，类型为 `ModuleFunction<NativeActionModule, ReviveRequest, ReviveResult>`。用途、参数、输入转换、handler、结果投影和完成条件来自同一声明。脚本调用无业务参数：

```javascript
modules.call("native.revive", {});
```

Java 通过同一目录调用强类型操作：

```java
var call = context.call(NativeActionModule.REVIVE, ReviveRequest.NORMAL, callerWork);
if (call.status() == ModuleCall.Status.SUCCEEDED) {
    ReviveResult result = call.result();
    // result.outcome() 及前后事实表达本次业务结果。
}
```

`ModuleCall<ReviveResult>.status/reason` 表达目录可用性，具体 `ReviveResult` 表达原生复活结果。脚本对应返回 `{status: "SUCCEEDED", result: ...}`，函数缺失或提供者不可用时按目录契约返回 `UNAVAILABLE`。首环请求不接收目的地、坐标或封包字段；普通复活的路径和效果由北斗原规则裁决。

## 完整普通点击流程

适配调用北斗1.12的 `ChangeMapHandler`，沿原处理器状态检查及完整复活路径执行。内部构造10字节普通请求：死亡标记1、非-1目标标记0、空门户、保留位0、复活轮标记0、追随标记0。编码是本版本适配细节，调用方只选择普通复活语义。

派发前核对精确来源、原场景及角色死亡状态。角色已经存活时返回 `ALREADY_ALIVE`，不再派发复活点击。死亡角色由当前事件的 `revivePlayer/playerRevive` 优先裁决；允许标准路径时，处理器沿 `Character.respawn(returnMap)` 完成后续原生规则。事件接管时保留其原决定和实际变化。回城地图、HP/MP与Buff处理继续使用原处理器及重生实现。

处理器返回 `void`，内部还可能捕获异常，因此 `handler_returned` 只说明本次派发返回。结果确认在同一 `CharacterRef` 和原世界代次下重新读取当前场景及角色状态。普通复活造成的合法换图按本操作允许的转换确认，不用旧地图对象拒绝已发生的复活；真正的来源或世界代次失效仍保留未知结果或原契约错误。

合法换图还可能结束当前 NPC 脚本 Context。`native.revive` 的原生执行完成与外层脚本能否继续分别核对：读取实际 `script_run` 结果和最新角色状态，Context 仍 OPEN 时按原 ID 继续；已关闭时按后续任务选择新的原生环境。

## 结果与证据

`ReviveResult.toMap()` 返回安全前后事实及本次实际路径：

| 字段 | 含义 |
|---|---|
| `outcome`、可选 `reason` | 本次业务结果及具体原因 |
| `character_id`、`epoch` | 本次精确角色及原世界代次 |
| `before`、`after` | 各含 `alive/hp/mp/map_id/x/y`；尚未派发或后态不可读时相应值可为 `null` |
| `dispatch_started` | 已通过派发前检查并进入原生处理器 |
| `handler_returned`、`handler_error` | 本次处理器返回及其错误证据，分别于业务后态表达 |
| `event_route` | `NOT_PRESENT/STANDARD_ALLOWED/TAKEN_OVER/UNCONFIRMED` |
| `event_consulted`、`event_decision` | 精确事件已被调用及其是否允许标准路径；决定未返回时为 `null` |
| `standard_respawn_started/returned` | 处理器标准 `respawn(int)` 路径的开始与返回证据 |
| `respawn_started/returned` | 实际 `respawn(EventInstanceManager, int)` 步骤的开始与返回，包括事件内调用 |
| `requested_return_map` | 已观察到的原生重生目标地图，未观察到时为 `null` |
| `scene_changed`、`cancel_requested` | 本次观察到的场景转换及取消请求事实 |
| `event_work_completion` | 事件被调用时为 `OWNER_MANAGED_UNOBSERVED`，否则为 `NOT_APPLICABLE` |

| `outcome` | 含义 |
|---|---|
| `REVIVED` | 本次已派发，随后确认同一角色实际存活且HP大于0 |
| `ALREADY_ALIVE` | 初始检查或真正派发前角色已经存活，复活点击未执行 |
| `NOT_REVIVED` | 当前后态可读，本次尚未确认复活；事件接管但仍死亡也属于此结果 |
| `UNKNOWN` | 当前角色后态无法确认 |
| `CANCELLED` | 派发前取消，本次未进入原生处理器 |

事件返回允许标准路径时为 `STANDARD_ALLOWED`，返回接管时为 `TAKEN_OVER`；事件决定尚未确认时保留 `UNCONFIRMED`。这些路径由精确角色与事件的原生探针记录，实际复活仍由当前后态判断。即使处理器出错或随后收到取消，只要已有可靠复活事实，结果仍保留已发生效果。

事件回调的同步处理已返回，并不证明事件自身的后续任务全部完成。`OWNER_MANAGED_UNOBSERVED` 明确表示其后续工作由原事件所有者管理，本模块未接管或等待整个事件实例结束。事件可能按自己的规则保留死亡状态、另行安排复活或产生其他资料变化，调用方按当前结果继续观察。

## 执行、取消与最终收尾

`native-actions` 声明独立 `revive` 控制入口，与 MCP、导航及战斗各自合法 Grant 并存。目录的调用 work 已在提供者受管载体执行；实际复活短命令使用提供者 scope 的新 work，进入同一角色原有唯一 lane，并等待它真实结算。原生派发沿既有限状态检查、ThreadLocal恢复与请求缓冲释放边界。

调用者取消、期限、模块停止及根关闭沿现有调用关系传递。派发前取消阻止点击；派发后的取消不撤回已经发生的复活、换图或资料变化。函数完成条件是本次原生处理及提供者短命令实际退出，scope/work结算与操作结果分别核对。模块只收尾自己的工作和观察资源，原生事件及其共享任务保留原所有权。角色最终保存与退出由根来源负责。

死亡、事件裁决及消耗材料场景在专用 fixture 或独立副本准备，准备变化与本次操作效果分开归因。验收分别记录真实存活、HP/MP、地图/位置、标准及事件路径、取消前后事实、正常保存重开，以及源码、隔离、部署和真人客户端范围。

源码入口为 [NativeActionModule](../../beidou-integration/src/main/java/soloMapling/game/nativeaction/NativeActionModule.java)、[NativeReviveActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeReviveActions.java)、[ReviveRequest](../../beidou-integration/src/main/java/soloMapling/game/action/ReviveRequest.java)、[ReviveResult](../../beidou-integration/src/main/java/soloMapling/game/action/ReviveResult.java) 和 [原生证据切面](../../beidou-integration/src/main/aspect/soloMapling/beidou/NativeReviveEvidenceAspect.aj)。共享操作的强类型接线见[模块参考](modules.md#共享函数声明与调用)。
