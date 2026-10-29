# 指定目标的持续交战

本文描述 #271 第一阶段的当前源码契约。HERO 与 IL_ARCHMAGE 两条职业链先完成实际交战验证，随后沿同一契约补齐旧表：当前完整候选覆盖34个冒险家职业节点及 BEGINNER，旧表28项攻击声明和31项Buff声明已完成来源对照；详细范围与职业特例见[职业能力清单](combat-jobs.md)。其余职业场景的实际验证、完整候选最终验收、正式部署与真人客户端结果仍分别按 Issue 记录确认。

主控决定接战目标、允许范围、继续或中断。战斗动作在这些约束内选招、调整朝向、接敌并连续攻击，指定目标死亡、失效、不可达或调用结束后返回实际结果，后续活动交由主控决定。

## 装配与调用

模块类型为 `combat`，实现 `ActionModule` 职责标记与运行体生命周期协议。强类型输入输出归于操作 `CombatActionModule.ENGAGE`，其声明为 `ModuleFunction<CombatActionModule, CombatRequest, CombatResult>`；同一声明维护用途、参数、结果说明、完成条件和实际 handler。当前可用性从 `modules.help()` 查看。

Java 通过 `context.call(CombatActionModule.ENGAGE, request, callerWork)` 获得 `ModuleCall<CombatResult>`。先检查 `status/reason` 表达的目录可用性，再读取具体业务 `result.outcome()`。脚本以 `modules.call("combat.engage", args)` 消费同一提供者和 handler，JSON 参数在目录边界转换为 `CombatRequest`。该操作在已受理原生短命令结算、导航及交战停止后返回。

当前源码的长期 `mcp_test` 类型显式装配战斗、导航和人口能力。战斗模块只有一个配置项：

```yaml
modules:
  combat:
    type: combat
    config:
      timeout_ms: 30000
```

模块 `timeout_ms` 为1～120,000毫秒，默认30,000，是调用省略期限时的默认值。环境角色按自己的类型装配同一个定义；测试类型及材料配置放在不提交的 `RobotPopulation.local.yaml`，见[人口配置](population.md#本地临时类型)。已有 MCP 角色继续使用原 `primary` 来源、账号及保存规则。

在已有 OPEN 北斗上下文中，取得当前实际场景的怪物对象 ID 后调用：

```javascript
modules.call("combat.engage", {
    target_oid: parameters.target_oid,
    allowed_target_oids: parameters.allowed_target_oids,
    choice: "AUTO",
    timeout_ms: 20000
});
```

| 函数参数 | 约定 |
|---|---|
| `target_oid` | 必填，当前实际场景中指定怪物的正整数对象 ID，最大2,147,483,647 |
| `allowed_target_oids` | 可选，允许命中的正整数对象 ID 集合，最多32个不同 ID，须包含指定目标；默认仅指定目标 |
| `choice` | `AUTO/SINGLE/AOE/ULTIMATE`，默认 `AUTO` |
| `timeout_ms` | 本次交战期限1～120,000毫秒，默认模块配置 |

`target_oid` 是本次固定目标，朝向及接敌都围绕它执行。命中集合先按当前射程、地形及职业规则筛选，再受允许集合约束；指定目标优先，实际数量仍受技能预设目标上限限制。只有指定目标时，群攻也只能命中该目标。模块不会在调用内自行换成最近的其他怪物。允许集合约束攻击命中的怪物，Buff 仍沿旧队伍作用范围处理。

目录返回 `{status: "SUCCEEDED", result: ...}` 表示函数正常返回，具体交战结局读 `result.outcome`。函数缺失、停用或未激活时，目录按既有契约返回顶层 `UNAVAILABLE`。参数错误沿当前脚本错误契约表达。外层 `script_run.timeout_ms` 还覆盖环境准备与结果处理，调用时应给本次交战及收尾留出预算。

## 职业路径与实例状态

父模块在角色来源完成绑定后的首次激活中读取当前职业，构造私有职业树并固定入口。当前路径按“本职业 → 上一转职阶段 → 更早祖先”查询；单体、群攻和大招独立解析，首个提供该类别的节点结束本次查询。Buff 声明按“最早祖先 → 当前职业”的顺序累计。单次调用复用该树，其他分支不参与本次选招。运行期转职后的入口刷新由二期衔接角色事实与通知能力。

`AUTO` 沿旧规则结合目标数量、群攻范围和大招间隔选择类别。显式 `SINGLE/AOE/ULTIMATE` 只查询该类别；当前路径无适用类别时返回 `NO_ATTACK`，单体按旧规则保留普通攻击兜底。技能段数、范围、武器变体和伤害预设见[职业能力清单](combat-jobs.md)。

攻击节奏、大招间隔和 Buff 再次派发时间归模块实例持有。大招间隔沿旧规则为25秒；新交战调用继续使用本实例已有冷却。每个实例同时维持一套有效交战循环，重入调用返回 `BUSY`。暂停后恢复保留实例私有状态，恢复的执行资格使用新模块代次。

## 接敌、取消与收尾

交战循环使用战斗提供者的受管调用 work；每次原生观察、朝向、Buff 或攻击以新的短 work 进入该角色原有的唯一 lane。当前目标不在可攻击范围时，通过 `context.call(NavigationActionModule.MOVE_TO, new MoveToRequest(...), work)` 组合调用导航，传入交战剩余期限；`ModuleCall<NavigationResult>` 区分导航可用性及其具体结果，导航自己的配置期限仍适用。地形范围见[导航参考](navigation.md)。导航缺失或停用返回 `UNAVAILABLE`，无法完成接敌时返回相应的不可达、期限或取消结果。

目录将调用者取消关联到战斗 work，再关联到导航提供者 work。战斗停止、脚本取消、场景变化和根关闭都要求本次循环及在途短命令真实退出；函数同步返回前等待本次组合调用收尾。已发生的位移、伤害、死亡、经验及掉落继续作为事实保留。脚本请求被取消后按原 `request_id` 或 operation 恢复证据，具体方法见[脚本执行](script-execution.md#归属取消和真实收尾)。

战斗模块使用 `combat-action` 控制资源，已有 MCP 基本 `attack` 使用 `combat`。两者沿各自合法 Grant 共用角色 lane；本模块只约束自身持续交战循环。其他合法攻击入口可能同时影响怪物状态，结果中的 HP 或经验前后差值须结合具体场景归因。

## 结果与实际效果

`CombatResult.toMap()` 投影本次结果：

| 字段 | 含义 |
|---|---|
| `outcome`、可选 `reason` | 本次结局与具体原因 |
| `initial_job`、`active_path` | 初始化时的职业及从当前职业到祖先的查询路径 |
| `buff_skill_ids` | 沿当前路径累计的 Buff 声明，按祖先到当前职业排列 |
| `buffs_cast` | 本次实际调用旧 Buff 派发的技能记录；短调用可能只派发声明中的一部分 |
| `attacks` | 按 `provider_job/kind/skill_id/route` 汇总的派发次数 `count` |
| `hits` | 按怪物汇总的 `object_id/monster_id/hp_before/hp_after/rolled_damage/killed` |
| `navigation_calls` | 本次接敌的导航调用次数 |
| `level_before/level_after`、`exp_before/exp_after` | 调用开始与结束时的角色实际等级和经验 |
| `elapsed_ms`、`execution_stopped` | 本次耗时及本次执行已退出；`BUSY` 请求未新建交战循环 |
| `rules` | 当前为 `legacy_simulated_combat_v1` |

| `outcome` | 含义 |
|---|---|
| `TARGET_DEAD` | 本次观察到指定目标已死亡 |
| `TARGET_INVALID` | 开始时目标不存在或已死亡，或过程中被移除、替换 |
| `UNREACHABLE` | 当前支持的攻击几何或导航无法完成接敌 |
| `UNAVAILABLE` | 接敌所需的导航提供者不可用 |
| `CANCELLED` | 调用或所属生命周期请求停止 |
| `TIMED_OUT` | 交战或组合导航期限已到 |
| `CONTEXT_CHANGED` | 原角色或场景资格变化 |
| `BUSY` | 同一实例已有交战，本次调用未启动新循环 |
| `NO_ATTACK` | 当前查询类别没有可用攻击计划 |

伤害沿 `BotDamageModel` 的自定义模拟数值，派发复用旧 `BotAttackDriver.strike` 和 `BotAttackEffects`。攻击广播后会实际调用怪物伤害与死亡路径，可能获得真实经验，并按旧适配的掉落表、归属和倍率生成真实地物。`rolled_damage` 是累计模拟伤害，和实际 HP 减少量分别记录；过量伤害或其他参与者都会影响两者关系。经验跨升级时也应同时查看等级变化。当前结果没有掉落清单，掉落及后续库存变化须另外观察；交战动作本身不拾取地物。

一期沿用旧简化消耗：攻击使用模拟技能等级、伤害和节奏，未接入真实技能学习资格、MP、箭矢或飞镖等完整消费链。Buff 派发主要广播施法角色自身外观；附近真人队友在旧队伍 Buff 路径上可能获得实际效果。`buff_skill_ids` 说明可用声明，`buffs_cast` 说明本次已派发记录，两者都不等同于完整服务器属性效果验证。实际扣血、死亡、经验、掉落及持久化分别核对。

二期通过 #90/#127 接入真实职业规则、消耗和成长驱动的入口变化，继续沿用本次交战请求、结果及模块生命周期边界。当前源码说明不表示这些二期规则已交付。

源码入口为 [CombatActionModule](../../beidou-integration/src/main/java/soloMapling/game/combat/CombatActionModule.java)、[CombatRequest](../../beidou-integration/src/main/java/soloMapling/game/combat/CombatRequest.java) 和 [CombatResult](../../beidou-integration/src/main/java/soloMapling/game/combat/CombatResult.java)。职业声明及旧规则来源由[职业能力清单](combat-jobs.md)维护。
