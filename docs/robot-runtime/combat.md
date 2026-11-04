# 指定目标的持续交战

本文描述 `CombatActionModule` 的源码契约。主控选择目标、允许范围及活动安排，战斗动作完成本次接敌、选招、Buff、持续攻击和收尾。#271 的一期来源与 #272/#127 的真实规则首片分别保留覆盖清单；源码、隔离实际调用、部署和真人客户端状态按对应 Issue 记录。

连续选怪由[狩猎控制](hunting.md)调度，自动用药由[恢复控制](recovery.md)调度，单次药效与分组冷却见[药剂动作](potions.md)。

## 装配与调用

模块类型为 `combat`，实现 `ActionModule`。`CombatActionModule.ENGAGE` 使用同一 `ModuleFunction<CombatActionModule, CombatRequest, CombatResult>` 维护参数、强类型handler、结果及完成条件。

```yaml
modules:
  combat:
    type: combat
    config:
      timeout_ms: 30000
```

`timeout_ms` 为1～120000ms，默认30000，作为省略调用期限时的默认值。Java通过 `context.call(CombatActionModule.ENGAGE, request, callerWork)` 获得 `ModuleCall<CombatResult>`；脚本沿共享目录：

```javascript
modules.call("combat.engage", {
    target_oid: parameters.target_oid,
    allowed_target_oids: parameters.allowed_target_oids,
    choice: "AUTO",
    timeout_ms: 20000
});
```

| 参数 | 规则 |
|---|---|
| `target_oid` | 当前实际场景中指定怪物的正整数OID |
| `allowed_target_oids` | 最多32个不同的正整数OID，须包含指定目标；省略时仅该目标 |
| `choice` | `AUTO/SINGLE/AOE/ULTIMATE`，默认 `AUTO`，在当前路径可用类别中选择 |
| `timeout_ms` | 本次交战期限1～120000ms |

目标是本次固定交战对象。目标集合先按当前攻击几何筛选，再受允许集合及技能目标数约束，指定目标优先。目录的 `SUCCEEDED` 表示操作返回，具体游戏结果由 `CombatResult` 表达。

## 当前规则分支

| 分支 | 范围与语义 |
|---|---|
| `native_skill_combat_first_v1` | BEGINNER及冒险家Warrior/Mage族；按真实学会等级和当前属性执行首批已适配技能、普通攻击及Buff |
| `legacy_simulated_combat_v1` | 其余一期职业路径继续沿旧简化选招、伤害和消耗规则 |
| `mixed_native_and_legacy_combat_v1` | 本次交战跨越了两类分支，具体派发同时保留各自规则标记 |

首批攻击为 `1001004/1001005/2001004/2001005`，真实Buff为 `1001003/2001002/2001003`。较高Warrior/Mage职业回溯实际学会的首批祖先技能；未学会时沿当前可用普通攻击或明确的无可用计划结果。详细覆盖及一期来源对照见[职业能力](combat-jobs.md)。

原生分支每次按当前技能等级和WZ取得成本、目标数、段数及效果。派发前检查实际HP/MP、冷却和已声明的攻击/施法阻断状态，资源不足时保留明确事实；成本复用原生计算及其Buff修正，受信施放中若原生效果拒绝，本次伤害也被阻断。角色职业发生变化时重建当前职业入口，后续计划使用新事实。

伤害输入由可信有限policy按真实属性、装备与技能数据生成，结果保留 `damage_policy` 及原生派发事实：普攻沿 `basic_melee_v1`，已适配近战技能使用 `native_physical_skill_v1`，魔法使用 `native_magic_skill_v1`，真实自身Buff使用 `native_self_buff_v1`。原生近战、魔法或施法handler负责对应技能结算，并由 `MapleMap.damageMonster` 处理怪物HP、死亡、经验、任务与掉落。完整职业技能及客户端算法按后续切片扩展；当前覆盖以声明的分支和技能为准。

## 场景常驻接触入伤

`CharacterSceneModule` 的 `CharacterDamage` 在角色有效入场后持续观察接触，当前支持BEGINNER或冒险家Warrior/Mage的 `contact_physical_v1` 范围。待命、接敌、狩猎停止和商店活动均保持该场景职责；单次交战不再拥有受击循环。

它复用WZ怪物身体框，以角色脚点向上50px的竖向探测线与怪物下半身体框检查接触，按角色唯一的2500ms节拍派发有限物理受击输入。节拍跨目标、交战和换图保留。基础输入按怪物PADamage减去已穿装备WDEF及WDEF Buff之和的一半计算，最小为1；原生 `TakeDamageHandler` 结算MagicGuard、反伤、防御被动、真实HP/MP与死亡。完整客户端回避和复杂伤害模型继续按后续切片扩展。

常驻工作归角色的 `scene-client` 原scope，每次观察和受击通过角色原有唯一lane执行。离场、世界停止和根关闭取消并等待对应工作真实退出；死亡留在原图时继续作为场景成员，读取原生存活事实并同步死亡/恢复姿态。恢复控制只观察真实后态并请求药剂。

## 接敌与收尾

不可直接命中时，通过当前运行体的 `navigation.moveTo` 调用接敌，传递本次剩余期限，并等待导航实际结果。方向、攻击和场景受击短命令使用角色原有唯一lane；交战循环和停止等待在受管载体执行。

取消、期限、目标失效、场景变化、暂停、卸载和根关闭均停止新的派发，并等待本交战已受理短命令和接敌导航真实结算；场景被动受击按成员生命周期继续。已发生的HP/MP、命中、死亡或收益事实保留。同一实例已有交战时，新调用返回 `BUSY`。

## 坡面接敌的有界真实冒烟

本场景按 #325 保留为待采样，目标是确认实际坡面上的接敌、运动与攻击衔接。一般训练循环、其他职业分支或单独的坡面物理测试均不能替代本场景。历史 CI [41844054388](https://github.com/Diluka/SoloMapling/actions/runs/41844054388) 在源码 `64fe0356` 的模拟坡面中出现 `UNREACHABLE/navigation_navigation_no_progress`，尚未进入攻击，产品原因未确认；原始失败继续保留。

1. 在隔离环境记录源码、JAR与实际运行身份，使用专用角色和实际已加载场景。通过当前观察能力确认普通可行走坡面、有效落地、合法武器、已装载导航/战斗及实际怪物。选择当前攻击范围外、同一受支持坡面上的目标；登记角色职业、规则分支、目标OID和场景/世界代次。材料准备单独归因。
2. 单次观察预算最多30秒，沿上文 `combat.engage` 的既有 `timeout_ms: 20000` 调用一次，允许集合限定本次目标。保持模块默认期限及无进展设置。业务调用自行接敌和攻击，观察者不逐帧驱动、重发或延长预算以取得成功。
3. 用现有 `scene_snapshot/scene_record` 留存本次实际角色/怪物位置、foothold、HP和场景身份，结合交战的 `navigation_calls`、`outcome/reason`、攻击/命中事实和原工作结算分析。核对接敌轨迹与真实坡面，区别未接敌、已抵达、已派发和实际伤害；服务端广播不证明真人动画已经播放。
4. 预算结束或调用返回后，只结束本次活动并确认原工作和运动资源真实结算，保留实际位移和已发生效果。需要关闭专用角色时沿正常来源关闭；其他成员和原生地图任务保持各自生命周期。记录证据文件和收尾结果，不覆盖原轮次。

结果分为通过、产品失败和未覆盖/不确定：前置真实成立且接敌、合法攻击与收尾均取得实际证据时通过；在支持前置成立的连续采样中确认无进展、控制权异常、越权或资源未释放时记录产品失败并调查。目标离场、场景变化、有效坡面样本或采样缺失时记录具体未覆盖原因。预算内没有击杀不自动判失败；已有明确导航无进展也不能用随机伤害或未击杀豁免。原工作停止未确认时仍是阻塞项。

## 结果与事实范围

| 字段 | 含义 |
|---|---|
| `outcome`、`reason` | 交战结果及具体原因 |
| `initial_job`、`current_job`、`active_path`、`job_changes` | 初始化职业、当前职业与路径，以及本次入口变化摘要 |
| `rules` | 本次采用的规则分支 |
| `buff_skill_ids`、`buffs_cast` | 当前路径的Buff声明及实际派发记录，结合规则分支解释效果 |
| `attacks` | 按提供节点、类别、技能及路线汇总的派发次数；原生项同时给出实际 `skill_level` 和 `rules` |
| `hits` | 按怪物汇总的OID、模板、HP前后、输入伤害及死亡事实 |
| `resources_before`、`resources_after` | 本次资源前后快照 |
| `native_attacks`、`native_buffs` | 每项已适配技能最近的原生结果，包括实际等级、policy、派发、资源及命中事实 |
| `native_attack_attempts`、`native_buff_attempts` | 本次原生攻击及Buff的累计尝试次数 |
| `native_attack_fact_scope`、`native_buff_fact_scope` | `LATEST_PER_SKILL`，上述列表分别保留每技能最近事实 |
| `contact_hits`、`contact_hit_count` | 本交战期间场景后台最近一次接触结果及派发次数增量，HP/MP变化读取具体结果 |
| `contact_hit_fact_scope` | `LAST_SCENE_BACKGROUND_DURING_ENGAGEMENT`，接触列表保留本次观察窗口内最后一次场景事实 |
| `navigation_calls` | 接敌导航调用次数 |
| `level_before/after`、`exp_before/after` | 实际等级及经验前后值 |
| `elapsed_ms`、`execution_stopped` | 本次耗时与执行是否真实退出 |

事实为有界摘要，具体保留范围读取对应的 `*_fact_scope`；累计次数与最近事实分别解释。原生技能事实的 `outcome` 区分 `APPLIED/RESOURCE_UNAVAILABLE/COOLDOWN/REJECTED/CANCELLED/UNKNOWN`。`rolled_damage` 是输入伤害，实际HP减少还受原生规则、过量伤害和其他参与者影响。掉落由原生地图延时任务持有，掉落及后续背包变化须另观察。

| `outcome` | 含义 |
|---|---|
| `TARGET_DEAD` | 本次观察到指定目标死亡 |
| `TARGET_INVALID` | 目标缺失、移除、替换或开始时已死亡 |
| `UNREACHABLE` | 当前攻击几何或导航不能完成接敌 |
| `UNAVAILABLE` | 所需动作提供者不可用 |
| `CANCELLED`、`TIMED_OUT`、`CONTEXT_CHANGED` | 调用取消、期限已到或原上下文资格改变 |
| `BUSY`、`NO_ATTACK` | 已有交战或当前没有可用攻击计划 |
| `DEAD` | 角色已死亡 |
| `INDETERMINATE` | 执行或副作用未能确认，保留已取得事实 |

普通MCP来源与环境Bot消费同一后端，原生登录、倍率及保存继续由各自来源管理。环境角色保留自身登录标记，普通掉落、任务候选及收益资格沿原生规则核对。

源码入口为 [CombatActionModule](../../beidou-integration/src/main/java/soloMapling/game/combat/CombatActionModule.java)、[CombatJobTree](../../beidou-integration/src/main/java/soloMapling/game/combat/CombatJobTree.java)、[NativeCombatActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeCombatActions.java) 和 [NativeContactDamageActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeContactDamageActions.java)。#272/#127继续按职业与技能族推进后续范围。
