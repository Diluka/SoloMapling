# 同场景导航模块

本文描述 #122 的当前源码契约。模块复用旧导航图、搜索和物理预测，实际位置帧通过北斗原生移动入口及角色唯一 lane 执行。源码测试、支持路线的实际验证和部署状态以 Issue 证据分别确认。

## 配置与调用

类型配置通过模块 `type: navigation` 选择 [NavigationActionModule](../../beidou-integration/src/main/java/soloMapling/game/navigation/NavigationActionModule.java)。模块实现 `ActionModule`，持有 `movement` 执行资源，实例同时处理一个导航任务。单项操作 `MOVE_TO` 使用 `ModuleFunction<NavigationActionModule, MoveToRequest, NavigationResult>` 声明；绑定后的强类型调用接收本次目标与当前提供者的受管工作。目录适配把 `x/y` 及可选 `timeout_ms` 一次转换为 `MoveToRequest`，调用同一handler并投影既有 JSON 结果。配置类型、函数名与实例标识保持原契约。

| 模块配置 | 默认值 | 含义 |
|---|---:|---|
| `arrival_tolerance_px` | 8 | 实际末位置与目标坐标的容差，范围4～32767px |
| `timeout_ms` | 30,000 | 本次规划和移动的执行期限 |
| `stalled_timeout_ms` | 3,000 | 位置持续无进展时结束本次任务的期限 |

地面步行保留4px目标接近距离，以容纳坡面微小起伏；抵达容差不能小于该距离。配置加载时明确拒绝0～3px，不自动调整配置值。默认8px继续沿用现有移动规则。

在当前受管北斗上下文中同步调用：

```javascript
const navigation = modules.call("navigation.moveTo", {
    x: parameters.x,
    y: parameters.y
});
if (navigation.status === "SUCCEEDED" && navigation.result.outcome === "ARRIVED") {
    console.log("已抵达", navigation.result.position);
}
navigation;
```

`x/y` 是当前实际场景中的整数坐标，范围为有符号 16 位坐标。可选调用参数 `timeout_ms` 为1～120,000毫秒，默认120,000；实际期限取调用预算与模块配置的较小值。Java 使用 `MoveToRequest(x, y, timeoutMs)`，双参数构造沿用默认预算。持续动作组合接敌时可传入自己的剩余预算，返回前仍等待导航实际停止与本次收尾。调用保留当前地图对象与场景身份；目标地形规划使用 `WALK/JUMP/DROP/CLIMB`，分别对应步行、跳跃、可落地下降和梯绳。具体地图能否完成路线，以实际规划与执行结果为准。

## 地面规则与已穿鞋子（#276 候选）

导航父模块管理目标、路线、执行阶段、期限和运动方式切换；普通地面与冰面的轻量规则组件属于其内部实现。地面规则用于加速、滑行、制动、停止距离和接近目标计算，`WALK/JUMP/DROP/CLIMB` 继续沿同一运动状态推进，保持起跳、落地及梯绳与地面之间的速度衔接。运行体仍装载一个导航模块，内部工作与资源归原导航 scope 收尾。

有效地面规则读取当前地图、实际角色移动属性及 `EQUIPPED[-7]` 的鞋子：

| 地图地面 | 已穿鞋子效果 | 有效规则 |
|---|---|---|
| 普通地面 | 任意 | 普通地面 |
| 冰面 | 无有效防滑效果 | 冰面加速、滑行和制动 |
| 冰面 | WZ `fs` 或实例 `ItemConstants.SPIKES` 提供有效防滑 | 滑度按 `1.0` 使用普通地面规则 |

天然防滑鞋和原生卷轴赋予的防滑标记沿实际装备效果解析。真实速度、跳跃及其他装备或 Buff 属性分别按原生来源读取；冰面改变加速与制动过程，最高速度仍由角色移动属性决定。调用方继续提供业务目标。

规划、图缓存、逐帧执行和停止预测消费同一份有效运动配置。普通地图沿既有规范化逻辑复用缓存；冰面普通鞋与防滑鞋按实际规则区分。每次新调用读取当前装备。执行期间有效速度、跳跃或防滑效果变化使旧计划失效，沿 `BLOCKED / attributes_changed` 结束本次任务，并按当前规则完成剩余滑行、落地或制动；结果保留实际位移。

本节描述 #276 的候选契约。普通鞋、天然防滑鞋、卷轴鞋及支持路线的源码、隔离、实际部署和真人客户端结果分别以该 Issue 的验收记录为准。

## 结果与完成条件

共享目录外壳的 `status=SUCCEEDED` 表示 handler 返回，导航的实际结果由 `result.outcome` 表达：

| `outcome` | 含义 |
|---|---|
| `ARRIVED` | 实际位置进入容差，达到稳定站立或可保持位置，本次主动导航已收尾 |
| `BUSY` | 该实例已有当前导航任务 |
| `NO_PATH` | 当前场景没有可用路径 |
| `READ_FAILED` | 地形图读取或构建失败 |
| `UNSUPPORTED` | 当前地形或起始状态尚未支持 |
| `BLOCKED` | 执行持续无位置进展 |
| `TIMED_OUT` | 本次调用预算或导航模块期限到达 |
| `CANCELLED` | 调用或模块生命周期请求停止 |
| `CONTEXT_CHANGED` | 实际角色或场景来源已失效 |

结果还提供 `target`、已取得的实际 `position`、`stable`、`steps`、`elapsed_ms` 和经过的 `edge_types`；失败可附固定 `reason`。参数错误及原生帧执行、位置核对异常沿脚本错误契约返回。函数缺失或提供者不可用使用目录原有的 `UNAVAILABLE` 结果。

## 执行与收尾

`navigation.moveTo` 操作在本次给定目标内完成路径规划、移动、抵达判断和收尾，复用实例的配置与状态。规划与同步等待在模块受管载体执行。每个实际移动帧从导航提供者接受新的短 work，再进入原角色 lane；导出 handler 的调用 work 仅跟踪这次导航调用。到达取决于真实位置与稳定状态，不由路径回调或广播请求单独确认。

调用者脚本 work 的取消和 scope 停止持续关联到提供者 work。脚本期限沿现有取消入口停止本次导航，因此实际执行同时受脚本期限与模块自身期限约束。停止时保留已发生位移，并等待本次执行及资源真实收尾。外层脚本的最终状态和 `invocation_settled/work_settled` 仍按[脚本执行契约](script-execution.md)核对。

场景变化、模块停止或世界结束会终止旧任务。后续任务使用当前实际环境，不沿旧场景或旧模块代次继续移动。跨地图路线及门户进入由 #123、#94 等后续消费范围衔接。

MCP 保留的 `game_act/move`、`game_act/stop` 通过同一导航提供者执行原有有界基本移动；`navigation.moveTo` 提供坐标目标及路径完成语义。两类入口复用角色来源、唯一 lane 和真实结算事实。
