# 机器人类型与人口配置

本文描述 #263 的当前源码契约，面向配置机器人类型及接入模块的开发者。源码测试、隔离验收和实际部署状态以对应 Issue 的证据为准。

## 配置来源与类型

`solomapling.robot-population-config` 指定一个路径或有序路径列表。未指定时读取随程序分发的[默认配置](../../beidou-integration/src/main/resources/soloMapling/game/population/RobotPopulation.yaml)。部署通过 `application.yml` 指定本地只读文件，例如：

```yaml
solomapling:
  robot-population-config:
    - /opt/config/RobotPopulation.yaml
    - /opt/config/RobotPopulation.local.yaml
```

每轮世界启动时按列表顺序读取并合并：`types` 按类型名合并，后文件的同名类型完整替换前面的定义；`initial_population` 按类型名覆盖，`0` 也是有效覆盖值。文件可以只提供其中一部分。全部合并完成后，再一次转换模块装配计划并核对初始化数量引用。

列出的文件须存在。只使用默认配置时列出第一个文件即可；路径未指定时仅加载内置默认文件。`RobotPopulation.local.yaml` 用于本地临时测试，已由 Git 忽略，按需要创建并加入加载列表。

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

类型组合与配置随源码测试和验收。每个机器人创建自己的模块实例与私有状态；实例存续期间保持既定组合，任务、状态和能力可用性在已有模块中变化。

## 固定的 MCP 测试类型

现有 MCP 专用普通角色在原生来源绑定后，固定读取 `mcp_test` 类型的模块组合。它继续沿既有 `primary` profile 创建、登录和保存，不使用环境机器人模板或配置中的出生位置。

默认配置包含：

| 类型 | 装配与用途 |
|---|---|
| `mcp_test` | `mcp-control`、`navigation`、`combat`、`combat-tactics-control`、`native-actions`、`potions`、`hunting-control`、`recovery-control`、`robot-population`、`travel`、`loot-control`、`shop`；为已有MCP专用角色装配能力，继续使用原生普通角色来源，不装训练日程 |
| `training_basic` | 环境来源、旅行、导航、原生指令、战斗、常驻战术、拾取控制、商店、狩猎、恢复与 `training-schedule`；从射手村开始固定训练场与杂货店循环，默认初始数量0 |

`mcp_test` 的默认初始数量为 `0`，MCP 角色仍由 `actor_open` 打开。

当前源码的 `combat` 模块默认 `config.timeout_ms: 30000`，通过 `combat.engage` 接受一次指定目标的持续交战。当前职业范围、参数与结果见[战斗参考](combat.md)，实际目录和部署状态按当前环境核对。

`native-actions` 模块使用空配置 `{}`，提供普通复活、普通门户和单次拾取 `native.pickup`。单次拾取不包含扫描或自动接近。原生事件、标准回城及角色后态见[原生动作参考](native-actions.md)，原生准入、跨图和地图进入完成见[门户参考](portals.md)。

`potions` 模块独立导出 `potions.use`，使用指定的已适配物品药效并维护恢复、异常解除两组冷却；两组默认各5000ms，可分别配置 `restore_cooldown_ms` 与 `cleanse_cooldown_ms`。该模块可独立于战斗装配，参数、原生效果及结果见[药剂参考](potions.md)。

`hunting-control` 默认 `style: AUTO`，装载及恢复激活后保持 `IDLE`，由 `hunting.start` 开始本场景活动；`hunting.stop` 等活动真实收尾后回待命。`recovery-control` 每次激活自动监控HP/MP和可解异常，显式 `recovery.stop` 后待命，`recovery.start` 或重新激活会再次开始。结束狩猎不结束恢复，配置及状态见[狩猎](hunting.md)和[恢复](recovery.md)参考。

`combat-tactics-control` 和 `loot-control` 是独立控制模块，每次激活自动开始各自的监控；显式停止后本轮待命，重新开始或重新激活恢复。默认战术配置为 `check_interval_ms: 100`、`prediction_window_ms: 1000`、`reserve_hp_pct: 30`、`local_escape_distance_px: 128`；默认拾取配置为 `check_interval_ms: 100`、`equipment_timeout_ms: 30000`。战术没有交战上下文时只避险，不自行选怪开战；拾取不依赖狩猎启动。交战依赖当前战术提供者，缺失或停止时返回明确不可用。参数与支持范围见[战术](tactics.md)和[拾取](loot.md)。这些为 #322/#323 候选源码契约，隔离和实际部署分别留证。

后续能力由各机器人类型明确选择，长期MCP调试能力随 `mcp_test` 共同维护和验收。MCP接入代码只引用固定类型名，模块组合在这份配置中维护。

`travel` 只规划地图间线路，`shop` 提供按次商店动作；拾取由独立常驻控制组织。`mcp_test` 没有自动训练日程，战术、拾取和恢复按各自生命周期运行。自己的手工丢弃地物也符合原始自身归属规则，可能随后被自动拾回；手工 `drop/pickup` 专项先调用 `loot.stop` 并等待收尾，结束后正常 `loot.start`，不增加排除手工地物的业务过滤。

日程由专用 `training_basic` 类型装配。其 `training-schedule` 最后激活，自动协调狩猎、独立拾取结果、真实容量条件、到店出售和返回；恢复、战术与拾取继续各自运行。配置及受阻、启停语义见[基础练级日程](training-schedule.md)，容量和出售候选见[背包事实](inventory.md)。该类型初始数量0；配置人口或创建实例并激活后，日程持续推进。临时验收类型和材料放本地覆盖文件。

升级本地配置时，凡是完整覆盖 `mcp_test`、`training_basic` 或其他含 `combat` 的类型，都需要显式装配 `combat-tactics-control`；原 `type: loot` 改为 `loot-control` 并按新模块配置。后文件同名类型完整替换前定义，不会自动获得内置新增模块。保留原数量、来源、出生、导航期限、日程容量/出售与策略定制；迁移前生成临时副本核对，正式文件按备份部署流程应用。

## 本地临时类型

测试类型放在 `RobotPopulation.local.yaml`，例如：

```yaml
types:
  navigation_probe:
    character:
      template: fmbot
      spawn: {world: 0, channel: 1, map: 100000000}
    modules:
      navigation:
        type: navigation
        config: {}

initial_population:
  navigation_probe: 0
```

加载默认文件和这个临时文件后，同时获得 `mcp_test` 与 `navigation_probe`。临时文件按自己的测试需要维护，默认分发文件只保存长期使用的类型。

## 初始化与实例生命周期

人口服务在每轮世界来源就绪后，按 `initial_population` 创建一次初始环境人口。启动数量是初始值；之后脚本的创建、移除会改变本轮实际数量，配置文件保持原样。世界重新启动后重新按文件初始化。

人口服务拥有自己创建的完整机器人运行体。创建沿环境来源、模板加载及 `BotRegistry` 接入真实角色，再装配该类型的模块；成功结果表示角色和模块初始化完成。关闭先停止模块并等待其工作、资源真实结束，再释放根角色来源及唯一 lane。世界停止沿同一关闭流程处理本服务的实例。

环境来源在 OPEN 的原角色 lane 中，将入口0的位置投影到同 x 正下方的实际地面，作为发布前的出生初值；地图加入完成后通过原生接口结束首次换图状态。入口下无地面时创建明确失败；已发布角色的移动继续由动作模块执行。普通 MCP 来源保留原生登录与保存位置。

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
