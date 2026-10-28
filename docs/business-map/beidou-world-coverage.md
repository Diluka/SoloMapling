# 北斗世界与场景：责任及源码覆盖

本文件依据固定北斗 `1.12` 提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b` 的只读源码建立；所有路径以 SoloMapling 仓库为根。此处是源码业务导航，不是运行、部署或真实客户端玩法验收记录。

## 责任边界

| 责任 | 依据与界限 |
| --- | --- |
| 结果与受益人 | 在线玩家获得可用世界/频道、正确地图成员归属和独立事件场次；事件脚本获得原生场次、成员及计时生命周期。 |
| 拥有的决策与状态 | 世界和频道拓扑、公共/事件地图缓存、MapleMap 成员、EIM 成员及计时状态；控制地图与频道迁移原生资格、实例命名冲突和大厅准入。 |
| 生命周期 | 建世界/频道→取得或加载地图→角色加入/移动/离开→事件建立/登记/计时/退出/释放→频道与世界关闭。 |
| 协作者 | 账号与角色域负责原生登录及持久角色；世界域换频道时调用该保存边界。任务/事件脚本决定具体玩法和奖励；社交域拥有队伍组成，事件入口只消费其资格成员。 |
| 来源边界 | 上游 `gms-server` 世界、频道、地图和事件机制。Solo 的 WorldEpoch、Scope 排空、MCP scene_ref 和控制权策略是本项目机制，不能写为官方已有。 |

## 独立发现的入口与覆盖

下表先从原生入口、权威状态及退出路径发现职责，再对应节点；不是按地图节点反推完整性。

| 场景或生命周期 | 决定性源码入口（均位于 `upstream/BeiDou-Server/gms-server/src/main/java/org/gms/`） | 映射 |
| --- | --- | --- |
| 按配置和上限创建世界及频道 | `net/server/Server.java#addWorld`、`#initWorld` | `beidou.world.open-world`、`topology` |
| 世界/频道停止与原生玩家退出 | `net/server/world/World.java#shutdown`、`net/server/channel/Channel.java#shutdown` | `stop-world`；角色保存引用原生角色域 |
| 地图按需加载、缓存与一次性地图 | `server/maps/MapManager.java#getMap`、`#getDisposableMap` | `load-scene`、`scene`、`scene-identity` |
| 地图成员加入、离开及入口脚本 | `server/maps/MapleMap.java#addPlayer`、`#removePlayer` | `membership`；怪物行为规则由战斗域维护 |
| 交通地图计时到站及地图脚本入口 | `server/maps/MapleMap.java#addPlayer` | 作为入图及跨图行为包含于 `membership`、`change-map`，不另造交通规则域 |
| 门户、死亡回城及引导地图的原生跨图入口 | `net/server/channel/handlers/ChangeMapHandler.java#handlePacket` | `change-map`、`travel-rules` |
| 按事件关联解析目标地图与原生场景切换 | `client/Character.java#changeMap` | `change-map`、`load-scene`、`membership` |
| 换频道拒绝、保存及迁移连接 | `net/server/channel/handlers/ChangeChannelHandler.java#handlePacket`、`client/Client.java#changeChannel` | `change-channel` 及其 flow；只确认频道过渡，不推定目标登录完成 |
| 单人、队伍及远征队场次入口/同名拒绝 | `scripting/event/EventManager.java#startInstance`、`#newInstance` | `start-event`、`instance`；队伍分支有 flow |
| 登记有效在线参与者、退出和断线 | `scripting/event/EventInstanceManager.java#registerPlayer`、`#unregisterPlayer`、`#playerDisconnected` | `participation`、`events` |
| 事件独立地图 | `scripting/event/EventInstanceManager.java#getMapInstance` | `load-scene`、`scene-identity`；不以地图编号替代实例 |
| 事件倒计时、超时和停止 | `scripting/event/EventInstanceManager.java#startEventTimer`、`#stopEventTimer` | `event-clock`、`events` |
| 结束实例并延时释放地图/大厅 | `scripting/event/EventInstanceManager.java#dispose`、`scripting/event/EventManager.java#disposeInstance` | `dispose-event` |
| 迷你地牢队长退出、无人或超时关闭 | `server/maps/MiniDungeon.java#unregisterPlayer`、`#close`、`#dispose` | `mini-dungeon`，跨图复用 `change-map` |

## 证据限制与明确处理

- `EventManager.startInstance` 的部分脚本异常记录后仍可能返回 `true`。流程保留“不确定实际后态”的分支；不把布尔值、受理或回调返回当作完整入场、通关或奖励证据。
- `World.shutdown`、`Channel.shutdown` 描述的是官方原生关闭调用顺序，不保证 Solo 后台工作已真实排空。后者属于本项目共享执行与运行体域。
- 各任务/首领事件的入场资格、阶段目标、奖励和失败退出细则由事件脚本及玩法域拥有，世界域只描述通用实例机制；不在此重复定义同一业务。
- 地图广播、NPC/portal/reactor 脚本的具体剧情和收益属于各玩法责任；门户准入、地图对象和入图触发在本域保留导航。
- 没有访问数据库、调用游戏动作或重启服务。本轮不补造部署现状，也不从源码存在推导真实客户端已验。

## 原生服务生命周期与集成边界

- `beidou.world.lifecycle` 依据 `net/server/Server.java#init` 和 `#shutdownInternal` 提供完整原生启动/停止方法边界，由 `open-world` 和 `stop-world` 发布。`init` 加载资源、世界频道、登录服务和事件脚本，正常结束后设置在线状态；`shutdownInternal` 做重入保护，关闭世界并等待频道，再停止公共线程/计时器及登录服务，按 `restart` 可重新初始化。
- 这条生命周期接口供 Solo 的 `GameplayHooks.aj` 在 `Server.init` 正常返回后、`Server.shutdownInternal` 进入前织入自身启动/停止处理。织入属于 Solo，接口属于上游；不能把原生停止等同于 Solo 工作已经真实排空，也不能用 `beidou.world.events` 代替这个完整服务生命周期。
- `beidou.world.events` 补充 `Channel.reloadEventScriptManager` 锚点：运营事件重载在未结束的频道取消旧脚本管理器并重建。它描述脚本承载与回调边界，不保证正在进行的各玩法场次自动迁移成功。
