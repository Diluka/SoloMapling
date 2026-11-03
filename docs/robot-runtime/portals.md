# 普通门户进入

`NativeActionModule`（`native-actions`）提供普通门户操作 `native.enterPortal`。它消费当前角色、实际场景与门户定义，通过北斗 `ChangeMapHandler` 执行客户端普通门户点击，再用 `PlayerMapTransitionHandler` 完成这次地图进入。角色继续归同一运行体、世界代次与唯一 lane。

## 调用

Java 控制模块调用 `NativeActionModule.ENTER_PORTAL`，参数为 `PortalRequest(portalId)`；具体 `enterPortal` 方法与脚本导出同源：

```javascript
modules.call("native.enterPortal", {portalId: 17});
```

`portalId` 是当前实际场景的非负门户 ID。调用者先读取当前门户坐标并安排场景内导航，确认导航结果后再进入；本操作沿原生距离、开放状态和角色资格裁决。

首期支持同频道的普通 `pt=2`、无门户脚本、目标地图及目标入口可确定的门户。事件场景及缺少明确目标入口的定义返回 `UNSUPPORTED`。旅行规划继续由 `TravelPlanModule` 提供；本动作完成一次实际进入。

## 结果与事实

目录外壳 `SUCCEEDED` 表示本次方法返回。业务结果由 `PortalResult.outcome` 表达：

| outcome | 事实 |
|---|---|
| `ENTERED` | 已观察到原生地图转移，实际目标地图对象匹配，并确认本次地图进入已完成。 |
| `NOT_ENTERED` | 没有本次进入的正证据，例如门户不存在或原生条件拒绝。 |
| `UNSUPPORTED` | 门户类型、脚本、事件场景或目标入口超出首期范围。 |
| `UNKNOWN` | 派发后无法确认完整后态、原生处理失败，或实际目标与声明目标不符；已观察到的事实保留。 |
| `CANCELLED` | 本次派发前请求已取消，没有进入门户。 |

- `exit` 是本次实际来源门户的值快照；`entry` 是从原生目标地图解析的**声明入口**快照。实际末场景与位置以 `after` 为准，原生地图进入逻辑可以继续改变位置。
- `before/after` 包含世界、频道、地图、诊断用 `scene_identity`、位置、存活和 `changing_maps`。诊断标识不授予后续操作资格；下一段从当前运行体重新捕获实际场景。
- `dispatch_started/handler_returned/handler_error` 描述原生点击派发；`acknowledgement_*` 与 `transition_complete` 描述对应的原生地图进入完成处理。
- 取消发生于实际转移之后时，操作在原已入场短 work 内完成该次 ACK，保留 `ENTERED` 与 `cancel_requested=true`。它等待实际短命令退出，不撤回换图，也不建立第二队列或借用新的 Grant。
- 世界或角色资格丢失时，不放宽检查补做动作。`UNKNOWN` 与可确认的前后事实分别保留，调用方观察当前状态后决定下一步。

正常换图会沿原生 `closePlayerInteractions` 关闭既有 NPC/任务对话。MCP 在原生对话 Context 内发起该操作后，本次调用可以返回结果，该 Context 随后正常成为 `CLOSED`；下一段脚本重新启动 Context。角色、运行体及模块继续存在，Java 日程不依赖该 MCP Context。

## 基础售物循环

首个组合由 #292/#296 调度：

```text
104040000（训练场Ⅰ）
↔ 100000000（射手村）
↔ 100000100（集市）
↔ 100000102（杂货店）
```

门户 ID 与入口名称取各次实际地图定义，固定六条往返边仅作业务验收样本。官方1.12静态WZ的六个目标入口相对实际地面差为 `2/-1/0/1/-1/-1px`，处于现有地面容差；场景内路线可达和实际往返仍由组合验收独立核对。

源码入口为 `game/nativeaction/NativeActionModule.java`、`game/action/NativePortalActions.java` 和 `PortalRequest/PortalResult`。资格与效果来自只读北斗 `ChangeMapHandler`、`GenericPortal`、`Character.changeMap` 和 `PlayerMapTransitionHandler`，适配维护在 Solo。
