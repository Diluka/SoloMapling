# 同场景导航模块

本文描述 #122 的当前源码契约。模块复用旧导航图、搜索和物理预测，实际位置帧通过北斗原生移动入口及角色唯一 lane 执行。源码测试、支持路线的实际验证和部署状态以 Issue 证据分别确认。

## 配置与调用

类型配置通过模块 `type: navigation` 选择 [NavigationModule](../../beidou-integration/src/main/java/soloMapling/game/navigation/NavigationModule.java)。模块持有 `movement` 执行资源，实例同时处理一个导航任务。

| 模块配置 | 默认值 | 含义 |
|---|---:|---|
| `arrival_tolerance_px` | 8 | 实际末位置与目标坐标的容差 |
| `timeout_ms` | 30,000 | 本次规划和移动的执行期限 |
| `stalled_timeout_ms` | 3,000 | 位置持续无进展时结束本次任务的期限 |

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

`x/y` 是当前实际场景中的整数坐标，范围为有符号 16 位坐标。调用保留当前地图对象与场景身份；目标地形规划使用 `WALK/JUMP/DROP/CLIMB`，分别对应步行、跳跃、可落地下降和梯绳。具体地图能否完成路线，以实际规划与执行结果为准。

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
| `TIMED_OUT` | 导航模块自身期限到达 |
| `CANCELLED` | 调用或模块生命周期请求停止 |
| `CONTEXT_CHANGED` | 实际角色或场景来源已失效 |

结果还提供 `target`、已取得的实际 `position`、`stable`、`steps`、`elapsed_ms` 和经过的 `edge_types`；失败可附固定 `reason`。参数错误及原生帧执行、位置核对异常沿脚本错误契约返回。函数缺失或提供者不可用使用目录原有的 `UNAVAILABLE` 结果。

## 执行与收尾

规划与同步等待在模块受管载体执行。每个实际移动帧从导航提供者接受新的短 work，再进入原角色 lane；导出 handler 的调用 work 仅跟踪这次导航调用。到达取决于真实位置与稳定状态，不由路径回调或广播请求单独确认。

调用者脚本 work 的取消和 scope 停止持续关联到提供者 work。脚本期限沿现有取消入口停止本次导航，因此实际执行同时受脚本期限与模块自身期限约束。停止时保留已发生位移，并等待本次执行及资源真实收尾。外层脚本的最终状态和 `invocation_settled/work_settled` 仍按[脚本执行契约](script-execution.md)核对。

场景变化、模块停止或世界结束会终止旧任务。后续任务使用当前实际环境，不沿旧场景或旧模块代次继续移动。跨地图路线及门户进入由 #123、#94 等后续消费范围衔接。

MCP 保留的 `game_act/move`、`game_act/stop` 通过同一导航提供者执行原有有界基本移动；`navigation.moveTo` 提供坐标目标及路径完成语义。两类入口复用角色来源、唯一 lane 和真实结算事实。
