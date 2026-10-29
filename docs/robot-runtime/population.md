# 机器人类型与人口配置

本文描述 #263 的当前源码契约，面向配置机器人类型及接入模块的开发者。源码测试、隔离验收和实际部署状态以对应 Issue 的证据为准。

## 配置来源与类型

`solomapling.robot-population-config` 指定 `RobotPopulation.yaml` 的路径。未指定路径时读取随程序分发的[默认配置](../../beidou-integration/src/main/resources/soloMapling/game/population/RobotPopulation.yaml)。部署继续通过 `application.yml` 指定本地只读配置文件；每轮世界启动时读取一次，并将模块配置转换成可供创建入口直接使用的装配计划。

文件先定义 `types`，再定义 `initial_population`：

| 字段 | 含义 |
|---|---|
| `types.<type_id>` | 机器人类型；供初始化、脚本创建或已有角色装配引用 |
| `character.template` | 新建环境机器人的来源模板，当前为 `fmbot` |
| `character.spawn.world/channel/map` | 新环境角色的初始世界、频道和地图 |
| `modules.<instance_id>.type` | 已注册模块的 `ModuleDefinition.typeId`；键是本机器人内的模块实例名 |
| `modules.<instance_id>.config` | 该模块的自有配置，省略时为 `{}` |
| `modules.<instance_id>.enabled` | 是否允许该模块工作，省略时为 `true` |
| `initial_population.<type_id>` | 本轮世界启动时创建的非负整数数量；`0` 或未列入表示不在启动时新建 |

类型组合与配置随源码测试和验收。每个机器人创建自己的模块实例与私有状态；实例存续期间保持既定组合，任务和行为在已有模块中变化。

## 固定的 MCP 测试类型

现有 MCP 专用普通角色在原生来源绑定后，固定读取 `mcp_test` 类型的模块组合。它继续沿既有 `primary` profile 创建、登录和保存，不使用环境机器人模板或配置中的出生位置。

默认配置包含：

| 类型 | 装配与用途 |
|---|---|
| `mcp_test` | `mcp-control`、`navigation`、`robot-population`；为已有 MCP 专用角色提供测试能力，不声明新建角色来源 |
| `navigation_probe` | `fmbot` 来源及导航模块；用于通过人口服务创建、移除独立环境机器人 |

两种类型的默认初始数量均为 `0`。MCP 角色仍由 `actor_open` 打开；`navigation_probe` 可由脚本创建，或在专用配置中设置启动数量。

后续新增非自主能力模块时，将其加入 `mcp_test.modules` 并随模块共同验收。自主控制模块保留在其业务类型中。MCP 接入代码只引用固定类型名，模块组合在这份配置中维护。

## 初始化与实例生命周期

人口服务在每轮世界来源就绪后，按 `initial_population` 创建一次初始环境人口。启动数量是初始值；之后脚本的创建、移除会改变本轮实际数量，配置文件保持原样。世界重新启动后重新按文件初始化。

人口服务拥有自己创建的完整机器人运行体。创建沿环境来源、模板加载及 `BotRegistry` 接入真实角色，再装配该类型的模块；成功结果表示角色和模块初始化完成。关闭先停止模块并等待其工作、资源真实结束，再释放根角色来源及唯一 lane。世界停止沿同一关闭流程处理本服务的实例。

已有 MCP 角色通过 `assemble("mcp_test", runtime)` 消费相同类型目录，其角色来源和最终关闭仍归原 MCP 根宿主。现有一代 `EnvironmentPopulation.yaml` 继续管理其批次及人口意图。

## 脚本创建与移除

`robot-population` 模块通过共享目录导出 `robots.create` 和 `robots.remove`。受管北斗引擎提供对应的简写：

```javascript
const robot = robots.create("navigation_probe");
const removed = robots.remove(robot.instance_id);
({robot, removed});
```

| 入口 | 结果 |
|---|---|
| `robots.create(typeId)` | 返回 `instance_id`、`type_id`、`runtime_id` 和 `character_id` |
| `robots.remove(instanceId)` | 返回 `instance_id`、`type_id` 和 `removed=true`；成功表示实际关闭完成 |

简写复用 `modules.call("robots.create", {type_id: ...})` 和 `modules.call("robots.remove", {instance_id: ...})`，成功时解开目录结果的 `result`，提供者缺失或不可用时保留原 `UNAVAILABLE` 结果。类型或实例错误、创建和清理异常沿当前脚本错误契约返回。

脚本新建需要该类型提供 `character` 来源；`mcp_test` 只用于已有角色装配。每次创建返回具体实例引用，同类型的其他实例有独立身份和状态。请求中断或结果未知时按原脚本请求恢复事实，已发生的创建、移除不自动重放。

实现入口为 [RobotPopulationConfig](../../beidou-integration/src/main/java/soloMapling/game/population/RobotPopulationConfig.java)、[RobotPopulationService](../../beidou-integration/src/main/java/soloMapling/game/population/RobotPopulationService.java) 和 [RobotPopulationModule](../../beidou-integration/src/main/java/soloMapling/game/population/RobotPopulationModule.java)。
