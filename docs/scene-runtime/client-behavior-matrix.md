# SceneRuntime 客户端职责与首批行为矩阵

本报告对应 [#306](https://github.com/Diluka/SoloMapling/issues/306)，服务于 [#305](https://github.com/Diluka/SoloMapling/issues/305) 的执行重写。调查日期为 2026-11-04；源码基线为 `642c4c34481970eefd57515e7cd7b490aa93434e`，只读北斗 1.12 为 `75a86268738ca8845e06ea9d59ec36d15b22c91b`。

这里记录基线行为、可复用来源及新组件应承担的职责。它不把同分支随后新增的实现计作已部署能力。源码表明存在处理器或广播，只能证明服务端路径存在；原生生效、观察端收包和真人画面分别取证。

## 事实归属与执行契约

- 原生 `Character`、`Monster`、`MapleMap`、背包、技能工厂和原生效果是事实来源。场景持有精确对象引用、当前入场资格及必要的预测/动作上下文，不维护第二份 HP、位置、库存或冷却档案。
- 每个实际地图实例在同一世界代次内共享一个场景宿主；同地图 ID 的其他频道或 EIM 实例分别定位。死亡且仍在原图的角色保留成员身份。
- `CharacterRuntime` 持有角色来源、唯一角色 lane、跨场景操作、模块工作和最终保存退出。后台角色动作也必须持有效 scope/Grant，并在该 lane 中执行短命令。持有地图或角色引用不授予写资格。
- 业务模块决定目标、路线、职业选招、活动顺序和复活选择。场景组件补客户端计算、动作输入与本地状态推进；二级职责共用所属一级模块生命周期，不建立20套独立注册与启停机制。
- 结果至少区分未派发、已派发、原生事实已确认和结果未知。停止请求、Future 终态、处理器返回、实际 work 退出以及效果发生分别记录；取消不撤回已发生位移、伤害或收益。
- 离场使旧入场资格失效；最后成员离场或世界停止后，要等自己启动的工作、回调及订阅实际退出。原生地图的刷怪、掉落、Buff 到期任务保持原所有权。

## 五模块与20项二级职责

下表中的输入/结果描述内部类型化契约及现有可用类型，不要求为每项新增同名包装类。缺失的能力返回明确不可用结果，不能以空实现宣布支持。

| 一级模块 / 二级职责 | 事实与临时状态负责人 | 输入 → 结果及可复用来源 | 真实收尾条件 / 当前边界 |
|---|---|---|---|
| Environment / `SceneGeometry` | 地形归 `MapleMap`；图缓存沿现有世界级所有权 | 实际场景、坐标、角色运动配置 → foothold/梯绳/障碍/投影；[A03]、[A04] | 本地查询结束；共享图构建不能被一个调用方误关闭。导航目标/路线留业务层；#308 |
| Environment / `SceneObjectView` | 原生对象集合；场景不缓存第二份实体档案 | 实际地图、查询范围/类型 → 当前对象引用或有界观察投影；[A02]、[A03] | 观察订阅退出，原生地物生命周期继续；#308 |
| Environment / `EnvironmentTriggers` | 客户端本地触碰检测归本职责；机关状态归原生 Reactor | 接触/命中意图、精确机关 OID → 原生触发及实际状态；[A19] | 已出队触发退出；不重复原生环境定时伤害或掉落；#315 后续 |
| Character / `CharacterStateBridge` | HP/MP、死亡、当前姿态归 Character；场景只关联本次入场与变化 | 原生状态/事件 → 当前动作资格、死亡/复活变化；[A01]、[A10] | 旧入场回调失效并退出；死亡保留成员；#309/#313 |
| Character / `CharacterMotion` | 位置/stance/foothold 归 Character；预测运动及停止阶段归组件 | 方向/目标约束、`RuntimeNavigationEngine.Frame` → 实际帧、原生末位置、稳定/停止结果；[A04]、[A05] | 输入释放后仍等待滑行、落地、制动和原 lane work 退出；普通/冰面 WALK/JUMP/DROP/CLIMB；#309 |
| Character / `CharacterAttack` | 当前装备/状态/目标事实归原生；本次攻击动作资格归组件 | 目标引用、明确技能、朝向/允许目标集 → `StrikeResult`；[A06]、[A07] | 本次派发及真实工作退出；每次重读装备，#301 空手路径需修正；#312 |
| Character / `CharacterDamage` | 真实受击和死亡归原生；接触检测与输入节拍归本职责 | 身体框、实际相对位置、受击来源 → `ContactResult`、原生 HP/MP/存活事实；[A08]、[A09] | 业务交战结束不停止常驻处理；离场后对应原 lane 工作退出；有限 `contact_physical_v1`，击退待样本；#313 |
| Monster / `MonsterStateAccess` | Monster/MonsterStats 唯一持有属性、位置、HP、异常 | 精确 Monster → 实际状态/资料；[A11] | 不另建怪物档案；资料存在与客户端行为支持分开；#307 |
| Monster / `MonsterControlCoordinator` | 原生 controller 与仇恨保持原生事实；有效驱动端映射归协调者 | 候选、入场、离场、攻击仇恨切换 → 唯一有效驱动/保留原驱动/不可用；[A11]、[A12] | 旧驱动的活动真正停止后交接；仅登录不等于有驱动；#310、#299/#300 |
| Monster / `MonsterBrain` | 原生怪物/仇恨为输入；待机/追击/动作选择上下文归组件 | 当前场景、支持行为资料、目标约束 → 明确活动/运动/攻击意图；[A11]、[A13] | 无成员、失权、死亡或场景停止后自身活动退出；首批训练场样本待补；#311 |
| Monster / `MonsterMotion` | 原生 Monster 持位置；预测速度、运动段和序号归组件 | 有效驱动、运动意图 → 合法 `MOVE_LIFE` 序列及实际位置；[A13] | 原生输入/广播退出，无双重驱动；不能把角色物理直接视为怪物完整算法；#311 |
| Monster / `MonsterAttack` | 原生成本/冷却/效果归 Monster/MobSkill；客户端调度归组件 | 已确认攻击/技能活动及目标 → 一次合法原生输入、成本/效果事实；[A11]、[A13] | 已提交效果与模拟工作分别收尾；`canUseAttack`、`canUseSkill(...,true)`有副作用；#311 |
| Ability / `SkillDefinitions` | SkillFactory/MobSkillFactory/StatEffect 持定义 | 实际技能 ID/等级 → 范围、成本、动画及效果；[A06]、[A14] | 直接复用原生工厂；不新建另一 WZ 库；#312 |
| Ability / `SkillExecution` | 原生技能/角色持成本、Buff、冷却；组件组织一次共同执行 | 明确技能计划、合法角色/目标 → `StrikeResult` / `BuffResult`；[A06]、[A14] | 原生施放一次，真实短 work 退出；职业树选招保留 `CombatJobTree`；#312 |
| Ability / `ProjectileDispatch` | 普通投射视觉由原生攻击字段及客户端资源解释 | 合法远程攻击及原生 projectile 字段 → 原生远程广播；[A07]、[A15] | 不额外生成并重复结算一枚弹体；特殊持续/追踪弹体待样本；#312 |
| Ability / `SummonDriver` | Summon 为事实；跟随/寻敌/输入上下文归组件 | 精确召唤物、运动/攻击意图 → 原生移动/攻击/移除；[A20] | 召唤物消失、离场或失效后驱动实际退出；创建/到期保持原生；#316 后续 |
| Ability / `PetDriver` | Pet 为事实；本地跟随/运动上下文归组件 | 精确宠物、移动意图 → 原生宠物移动及位置；[A21] | 原生收回/离场后跟随退出；自动拾取、喂食等单独核对；#317 后续 |
| Presentation / `ActionPresentationCoordinator` | 实体资格归对应模块；动作顺序/朝向/保持衔接归本职责 | 已准入动作及动作参数 → 对应输入顺序和结束衔接；[A05]、[A07]、[A16] | 动作保持/结束回调真实退出；旧 `GCMovement.markAlerted`只作用于旧GC状态，不能直接当V2能力；#314 |
| Presentation / `EffectOutputAdapter` | 原生处理器/MapleMap 持广播；组件只接确认的缺口 | 已确认动作/效果 → 复用原生输出、保留发送范围；[A09]、[A15]、[A16] | 不再发送第二份攻击、怪物死亡、掉落或Buff消息；#314 |
| Presentation / `VisibleStateSynchronizer` | 存活/姿态归 Character/Monster；组件维护必要衔接进度 | 原生死亡/复活等变化 → 缺失的可见状态输入；[A10]、[A16] | 旧场景迟到同步不能写入新场景；源码发包、观察端收到和真人播放分别核；#314、#304 |

## 首批行为的完整链条

“正常样本待采”表示本报告未取得匹配版本的正常真人客户端输入、观察端收包及画面组合。历史 Issue 中的缺陷事实用作回归基线，不替代正常样本。

| 行为 / 发起方与本地计算 | 提交及原生处理 | 原生反馈与实际事实 | 样本、支持边界与验收方法 |
|---|---|---|---|
| 入场/换图：角色客户端完成场景载入并确认 | `CHANGE_MAP` → `ChangeMapHandler`；`PLAYER_MAP_TRANSFER` → `PlayerMapTransitionHandler`；[A12]、[A17] | 原生 add/remove/spawn、`setMapTransitionComplete()`；重新取当前实际 SceneRef | 已有门户/复活接线；正常样本待采。核精确地图对象/EIM、旧资格拒绝、其他玩家看到一次入/离场；#308/#318 |
| 角色普通/冰面运动：方向输入、地面物理、碰撞、梯绳与预测帧 | `MOVE_PLAYER` → `MovePlayerHandler.updatePosition`、`MapleMap.movePlayer`；[A04]、[A05] | Character 实际位置、stance、foothold；向其他成员广播 `MOVE_PLAYER` | 当前导航覆盖 WALK/JUMP/DROP/CLIMB；有界方向动作仅同foothold。正常样本待采。核逐帧原生末位置、停止后的稳定状态与真人连续运动；#309 |
| 普通攻击：读取当前武器/状态，计算有限合法命中及伤害输入 | `CLOSE_RANGE_ATTACK` → `CloseRangeDamageHandler` → 原生 `applyAttack` / `MapleMap.damageMonster`；[A06]、[A07] | 原生伤害探针、目标 HP/死亡，原生攻击广播；动作字段不等于 Character stance | #301 已有空手4次×1历史缺陷；合法武器正常样本待采。空手、持武器、交战中卸武器分别核；并用合法武器独立回归 #299/#302；#312 |
| 技能攻击/Buff：职业选招归业务，场景执行明确技能 | `MAGIC_ATTACK` / `CLOSE_RANGE_ATTACK` / `SPECIAL_MOVE` → 原生技能及 StatEffect；[A06]、[A14] | HP/MP成本、原生Buff/冷却、真实怪物扣血及单次广播 | 当前原生首片为普攻、1001004/1001005/2001004/2001005，Buff为1001003/2001002/2001003；其他legacy不能标原生全职业。正常样本待采；#312 |
| 接触受击：身体框、接触、物防/伤害输入、受击动作与可能击退 | `TAKE_DAMAGE` → `TakeDamageHandler`；[A08]、[A09] | 原生HP/MP、MagicGuard、反伤、死亡及 `DAMAGE_PLAYER` | 基线受击循环依附 `combat.engage`；有限policy不含完整回避/击退模型。正常受击及击退运动样本待采。待命、停狩猎、交战三种状态分别核；#313 |
| 角色死亡：本地动作转死亡；业务决定何时复活 | `hpChangeAction` → 原生 `playerDead`；姿态变化需客户端运动/姿态衔接；[A10] | 清Buff、死亡裁决/经验处理；HP0本身不证明其他客户端收到死亡姿态 | #304 有“HP0仍站立”的历史观察。基线原生死亡不自动广播死亡stance，旧GC有18/19规则。正常死亡包、原位姿态及同图画面待采；#313/#314 |
| 普通复活：死亡角色发起请求，事件先裁决 | `CHANGE_MAP` 的普通复活输入 → `ChangeMapHandler` / `Character.respawn`；[A17] | 原生HP与实际新地图/位置，事件接管与已复活分别报告 | 已有 `NativeReviveActions`；正常样本待采。核原CID、新入场资格、实际可行动及日程接续；#303策略仍归业务；#313/#318 |
| 怪物控制：服务端选控制者，客户端实际承担活动驱动 | Monster候选/仇恨/直接切换、入场确认与 `MOVE_LIFE`；[A11]、[A12]、[A13] | `SPAWN_MONSTER_CONTROL`、控制停止、移动回复；精确controller与后续有效输入 | #299 CID2→null、#300 34只中17只给无驱动CID4为历史缺陷。正常混合场景样本待采；进入/停留/退出和合法武器命中分别核；#310 |
| 怪物活动/攻击/技能：驱动端待机、追击、运动与时序选择 | `MOVE_LIFE` 提供OID/活动/技能/运动序列 → `MoveLifeHandler`；[A13] | 原生位置、MP/技能效果、`MOVE_MONSTER_RESPONSE`及`MOVE_MONSTER` | 基线无完整headless怪物AI。100101/120100/130101/1210100的正常活动样本待采；资料索引见#307，不从jump节点推出算法；#311 |
| 怪物死亡/掉落：合法伤害使原生结算发生 | `MapleMap.damageMonster` → `killMonster` / `dropFromMonster`；[A03] | 原生HP、死亡移除、经验/任务、掉落，`KILL_MONSTER`及地物出现/移除 | 原生路径可复用；不由场景重复生成收益/掉落。正常死亡/掉落画面样本待采；核原生事实和单次可见输出；#312/#314 |
| 拾取：客户端选择附近实际地物；业务决定行走与收集次序 | `ITEM_PICKUP` → `ItemPickupHandler`；[A18] | 原生地物归属/年龄/容量检查、真实入包或金币变化、移除广播 | 现有 `loot.collect` 与基本pickup复用。正常样本待采；核地物OID、背包增量/地物消失和画面，不以handler返回代替；#318 |
| 普通箭矢/飞镖：攻击输入携带原生投射字段 | `RANGED_ATTACK` → `RangedAttackHandler.visProjectile` / `PacketCreator.rangedAttack`；[A15] | 原生远程攻击包包含视觉projectile参数 | 仅确认服务端现有通路。当前原生首片不覆盖全部远程职业；legacy数据与特殊弹体分别标。正常客户端投射样本待采；#312 |
| 机关/环境触发、召唤物与宠物：本地检测或跟随驱动 | 原生触碰/机关、召唤、宠物处理器；[A19]、[A20]、[A21] | 原生状态/运动广播；不等于已有headless后台输入 | 当前列为#315/#316/#317后续；运动、攻击、环境伤害正常样本未取得，不阻塞核心首环声明 |

NPC对话、商店、仓库先沿已有原生API/会话路径复用。它们的内部UI、社交、组队与复杂交互不计作本轮场景首环必需模拟，也不据已有API推导真人UI已验。

## 协议与副作用核对

协议号来自本次固定北斗源码 [A22]，不是跨版本手写API目录。实现应直接查源码，不能将它们开放成任意封包接口。

| 输入 | 原生输出 | 调查结论 |
|---|---|---|
| `MOVE_PLAYER` 0x29 | `MOVE_PLAYER` 0xB9 | 更新原生移动对象后广播；停止、死亡姿态与复活动作要核实际运动序列 |
| `CLOSE_RANGE_ATTACK` 0x2C / `RANGED_ATTACK` 0x2D / `MAGIC_ATTACK` 0x2E | 0xBA / 0xBB / 0xBC | 原生已有攻击广播；不要再从表现层发送第二份 |
| `TAKE_DAMAGE` 0x30 | `DAMAGE_PLAYER` 0xC0 | 原生伤害和受击输出存在；客户端击退运动及死亡姿态仍需独立衔接 |
| `MOVE_LIFE` 0xBC | 控制0xEE / 移动0xEF / 回复0xF0 | 输入只应来自实际有效控制端；回复不是完整AI |
| 原生怪物击杀/地物变更 | `KILL_MONSTER` 0xED / 地物0x10C、0x10D | 场景不重复推进原生死亡、掉落和移除 |
| `MOVE_SUMMON` 0xAF / `MOVE_PET` 0xA7 | 0xB1 / 0xAA | 原生收包/广播存在，headless跟随驱动另做 |

`Monster.canUseAttack` 调用 `usedAttack`，扣MP并登记原生攻击清除任务；`canUseSkill(..., true)` 调用 `usedSkill`，扣MP并登记冷却。它们不能作为每tick重复调用的纯查询。`MoveLifeHandler` 的攻击/技能处理位于 `aggroMoveLifeUpdate` 控制者确认之前；可信模拟边界需要先核精确当前controller，再只派发一次合法输入。原生任务继续由原生服务拥有。

### #302 攻击动画

基线 `NativeCombatActions.attackPacket` 的 display/action/facing/speed 字段，与 `AbstractDealDamageHandler.parseDamage` 和 `PacketCreator.addAttackBody` 的对应字段顺序一致；当前没有包头错位证据。`BotAttackData.actionFor` 的攻击动作编码与 Character 的站立/行走stance不是同一字段。

原生 `CloseRangeDamageHandler` 使用范围广播；旧 `BotAttackEffects.broadcastAndApply` 使用全图广播，并另调 `GCMovement.markAlerted`。这些是定位线索，尚不足以认定缺失动画的原因。不能通过新增重复广播掩盖原始问题。

需要固定合法武器、角色CID、目标OID、观察者距离，记录一次攻击的提交字段、原生HP变化、观察者实际收到的攻击包及随后运动包，再与真人画面对齐。MCP包摘要或原生伤害探针不证明客户端实际播放。

### #304 死亡姿态

基线 `Character.hpChangeAction → playerDead` 执行原生死亡裁决，未设置或广播死亡stance；`TakeDamageHandler` 广播受击。旧 `BotPhysicsEngine.resolveStance/resolveDeadStance` 在HP≤0时使用 `CharacterStance` 的18/19死亡姿态；基线V2缺少常驻生命变化到该姿态的衔接。

原生 `Character.broadcastStance(int)` 已提供设置stance并通过 `getIdleMovement()` 广播的通路，可作为在原角色lane内补齐可见状态的复用点。是否适合所有死亡/复活路径及真人最终画面，仍要按正常样本确认。原生 `respawn`、新场景入场和实际可行动状态共同决定恢复，不能仅把stance改回站立就宣布复活完成。

## 原始缺陷的独立回归

| Issue | 原始证据与要求 | 本轮必须补的证据 | 对应职责 |
|---|---|---|---|
| [#299](https://github.com/Diluka/SoloMapling/issues/299) | 合法真人CID2控制者被环境来源攻击切成null；原历史样本与空手扣血共用 | 改用合法武器真实命中，核controller、后续真人MOVE_LIFE仍受理和怪物持续活动；不能用拒绝空手替代 | #310/#311/#318 |
| [#300](https://github.com/Diluka/SoloMapling/issues/300) | MCP无驱动观察角色CID4获17/34只怪物控制权 | 进入、停留、退出三阶段核驱动资格、控制分配和真人持续移动；有后台能力的来源另核映射 | #310/#318 |
| [#301](https://github.com/Diluka/SoloMapling/issues/301) | 无武器、totalWatk0仍4次×1真实扣血 | 空手无有效伤害、合法武器有效、交战中卸武器立即影响下一次派发；逐击HP与画面分别取证 | #312 |
| [#302](https://github.com/Diluka/SoloMapling/issues/302) | 用户观察攻击无对应动画；根因尚待核 | 合法攻击字段、原生效果、观察端实际收包及真人画面按同一击对齐 | #312/#314 |
| [#303](https://github.com/Diluka/SoloMapling/issues/303) | 日程死亡后停在BLOCKED，不能自动接续 | 可靠死亡事实→按业务配置复活→新场景可行动→重新进狩猎/售物日程；连续两轮并核停止排空 | #313提供事实，#303修业务，#318联合验收 |
| [#304](https://github.com/Diluka/SoloMapling/issues/304) | HP0但观察画面站立卡住 | 原生死亡、死亡姿态输入/输出、至少两个同图观察、复活后实际状态分别核 | #313/#314 |

历史缺陷记录位于部署目录 `.backups/20261103-160603-pr297-acceptance-issue-audit/`，其中 `user-hunter/unarmed-sample.json`、`scene-debug.json`、`monster-freeze/live-controller-summary.json` 及 `SOURCE_FINDINGS.md` 的位置见对应Issue。本报告通过Issue读取历史结论，没有重跑那些历史场景；它们不构成本候选通过证据。

## 正常客户端样本的采集清单

采样必须使用授权测试场景和专用角色；数据、原生输入、观察者收包和录像/截图按同一来源身份、世界代次、实际地图对象、CID/OID及时间关联。不要记录账号密码或管理JWT。每组保留正常原始样本，不能只保留“成功”摘要。

| 样本 | 最小场景与记录 | 当前状态 |
|---|---|---|
| 合法武器普攻及已适配技能 | 同图近距离观察；完整动作字段、目标逐击HP、一次原生广播、攻击后运动时序、画面；另采观察距离边界 | 待采 |
| 普通/冰面运动 | WALK、JUMP、DROP、CLIMB及停止；原生前后位置/stance/foothold、输出运动序列与连续画面；鞋子防滑来源单独记 | 待采 |
| 受击/击退 | 原生怪物接触、HP/MP/护盾、DAMAGE_PLAYER以及后续位移/动作；待命与交战分别记录 | 待采 |
| 死亡/复活 | HP归零、原生死亡裁决、死亡stance运动、同图观察；普通复活原请求、新地图及恢复可行动 | 待采 |
| 首批怪物活动 | 四个候选各自待机、遇敌、追击、停止、攻击及实际存在的跳跃；控制者变更与MOVE_LIFE/回复、原生位置、画面对齐 | 待采；数据分类由#307补充 |
| 拾取及入场 | 新进图spawn/地图完成确认；地物OID、归属/年龄/容量、原生入包和移除、观察画面 | 待采 |

本次完成静态职责、协议来源和验收契约调查。以上正常客户端样本均未在本次取得；#306 的样本采集交付仍需补齐或明确调整范围。#318 在隔离、主分支部署、真人与两轮实际日程证据齐备前保持未完成。

## 源码索引

下列路径均相对仓库根；符号以冻结基线为准。后续实现迁移时保留本报告的基线含义，并在实际支持文档登记新入口，不能把历史调查改成上线声明。

- **[A01]** [GameContext / NativeContextChecks](../../beidou-integration/src/main/java/soloMapling/game/context/NativeContextChecks.java)：`captureCharacter`、`captureScene`、`requireCharacterCurrent`、`requireSceneCurrent`；[CharacterRuntime](../../beidou-integration/src/main/java/soloMapling/game/runtime/CharacterRuntime.java)：模块上下文、scope、原角色lane及根关闭。
- **[A02]** [SceneSnapshotMapper](../../beidou-integration/src/main/java/org/gms/soloacceptance/SceneSnapshotMapper.java)：`snapshot` 读取已加载地图对象并作有界投影。
- **[A03]** [MapleMap](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/server/maps/MapleMap.java)：`getMapObjectsInRect`、`getMapObjectsInRange`、`getAllMonsters`、`getAllReactors`、`damageMonster`、`killMonster`、`dropFromMonster`、`moveMonster`、`movePlayer`、`addPlayer`、`removePlayer`、`broadcastMessage`。
- **[A04]** [RuntimeNavigationEngine](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/RuntimeNavigationEngine.java)：`plan`、`Frame`、`Session`、`GroundSession`；[BotPhysicsEngine](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotPhysicsEngine.java)、[GroundRules](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GroundRules.java)、[BotMovementProfile](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotMovementProfile.java) 为已有运动/地形算法来源。
- **[A05]** [NativeMovementActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeMovementActions.java)：`dispatchNavigationFrame`；[NavigationActionModule](../../beidou-integration/src/main/java/soloMapling/game/navigation/NavigationActionModule.java)：`execute`、实际帧与收尾；[MovePlayerHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/MovePlayerHandler.java)：`handlePacket`。
- **[A06]** [NativeCombatActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeCombatActions.java)：`plan`、`canHit`、`strike`、`buff`、`requireAdmission`、`damage`、`attackPacket`；[NativeAttackDamagePolicy](../../beidou-integration/src/main/java/soloMapling/game/action/NativeAttackDamagePolicy.java)：`capture`、`roll`；[CombatActionModule](../../beidou-integration/src/main/java/soloMapling/game/combat/CombatActionModule.java)：`contactLoop` 与业务交战现有归属。
- **[A07]** [BotAttackData](../../src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackData.java)：`actionFor`、`projectileFor`；[BotAttackEffects](../../src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackEffects.java)：`meleeStrike`、`broadcastAndApply`；[AbstractDealDamageHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/AbstractDealDamageHandler.java)：`parseDamage`、`applyAttack`。
- **[A08]** [NativeContactDamageActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeContactDamageActions.java)：`contact`、`touching`、`ContactResult`；[BotMobHitboxProvider](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotMobHitboxProvider.java)：`getMobBounds`。
- **[A09]** [TakeDamageHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/TakeDamageHandler.java)：`handlePacket` 的原生HP/MP、防御效果、反伤及受击广播。
- **[A10]** [Character](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/Character.java)：`hpChangeAction`、`playerDead`、`broadcastStance`、`getIdleMovement`、`respawn`；[CharacterStance](../../beidou-integration/src/main/java/org/gms/constants/game/CharacterStance.java) 的死亡姿态及 [BotPhysicsEngine](../../src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotPhysicsEngine.java) 的 `resolveStance`、`resolveDeadStance`。
- **[A11]** [Monster](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/server/life/Monster.java)：`getNextControllerCandidate`、`aggroSwitchController`、`aggroMonsterDamage`、`aggroMoveLifeUpdate`、`canUseAttack`、`usedAttack`、`canUseSkill`、`usedSkill`。
- **[A12]** [PlayerMapTransitionHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/PlayerMapTransitionHandler.java)：`handlePacket` 的入场完成与控制者分配。
- **[A13]** [MoveLifeHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/MoveLifeHandler.java)：`handlePacket`；[AbstractMovementPacketHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/AbstractMovementPacketHandler.java)：运动解析/原生位置更新；`MonsterInformationProvider.getMobAttackInfo/getMobSkillAnimationTime` 为原生动作资料入口。
- **[A14]** [SkillFactory](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/SkillFactory.java)、[MobSkillFactory](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/server/life/MobSkillFactory.java)、[StatEffect](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/server/StatEffect.java)、[MobSkill](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/server/life/MobSkill.java)：定义读取、成本与原生效果。
- **[A15]** [CloseRangeDamageHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/CloseRangeDamageHandler.java)、[MagicDamageHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/MagicDamageHandler.java)、[RangedAttackHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/RangedAttackHandler.java)：原生伤害接收、`visProjectile`及广播。
- **[A16]** [PacketCreator](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/util/PacketCreator.java)：`spawnPlayerMapObject`、`movePlayer`、`closeRangeAttack`、`magicAttack`、`rangedAttack`、`addAttackBody`、`damagePlayer`、`killMonster`、`moveMonster`、`moveMonsterResponse`。
- **[A17]** [NativePortalActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativePortalActions.java)：`enterPortal`；[NativeReviveActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeReviveActions.java)：`revive`；[ChangeMapHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/ChangeMapHandler.java)：普通过图/复活原生裁决。
- **[A18]** [NativeItemActions](../../beidou-integration/src/main/java/soloMapling/game/action/NativeItemActions.java) 与 [ItemPickupHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/ItemPickupHandler.java)：原生拾取准入、背包及地物结果。
- **[A19]** [TouchReactorHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/TouchReactorHandler.java)、[ReactorHitHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/ReactorHitHandler.java) 与原生 `ReactorScriptManager`。
- **[A20]** [MoveSummonHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/MoveSummonHandler.java)、[SummonDamageHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/SummonDamageHandler.java)。
- **[A21]** [MovePetHandler](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/MovePetHandler.java)：原生运动解析、Pet位置及广播。
- **[A22]** [PacketProcessor](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/PacketProcessor.java)、[RecvOpcode](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/opcodes/RecvOpcode.java)、[SendOpcode](../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/opcodes/SendOpcode.java)：当前处理器注册及协议号。
