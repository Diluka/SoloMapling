# 常驻战术与统一场景派发

`combat-tactics-control` 是独立 `ControlModule`。每次激活自动观察当前角色及周围威胁，
停止后保持待命；它与狩猎、拾取、恢复和训练日程分别拥有自己的scope。
`CombatActionModule` 继续选择目标授权集合内的技能和Buff，向战术登记本次交战及攻击提案。
追击、局部换位和避险移动由战术组织，交战不再另跑同步接敌导航。

类型配置：

```yaml
type: combat-tactics-control
config:
  check_interval_ms: 100
  prediction_window_ms: 1000
  reserve_hp_pct: 30
  local_escape_distance_px: 128
```

间隔、窗口、距离为正，生命余量为0～100。Java及脚本入口、参数和结果以
[CombatTacticsControlModule](../../beidou-integration/src/main/java/soloMapling/game/tactics/CombatTacticsControlModule.java)
的 `START / STOP / ASSESS` 为准。脚本名为 `combatTactics.start`、`combatTactics.stop`、
`combatTactics.assessEngagement`；Java `status()` 给出状态、当前决定、风险和最近避险结果。

## 交战与安排

交战通过进程内 `EngagementBinding` 登记、更新提案、取得指令并注销。
战术缺失或停止时，交战返回 `UNAVAILABLE / tactics_unavailable`。停止接受后即撤销本轮
登记，重新开始要求新的登记，旧交战不能跨停止/恢复继续使用旧许可。
许可年龄从原lane真实观测的单调时间计算，超过两个检查间隔不追加攻击；规划结束不为旧数据续期。

`ATTACK / CHASE / REPOSITION / EVADE / DISENGAGE / WAIT` 表示当前具体决定。
正常追击或换位暂停攻击并保留交战；装备接管或真正脱离可令交战返回 `PREEMPTED`，
保留已造成的伤害和资源事实。独立避险不随这次交战结束而取消。

每个实际入场绑定的 `SceneActionDispatch` 管理当前意图：

```text
LETHAL_ESCAPE > EQUIPMENT_PICKUP > TACTICAL_AVOID > NORMAL
```

同级保持当前有效安排。接管先使旧token失效、取消其原有限work；继任者等待原work及其
前任真实退出，并检查角色级实时动作锁。连续接管保留这条原完成信号链。
安排只引用原提供者、有限work、精确目标及原因，不创建第二条物理队列或执行账本。
角色位置、HP和库存继续属于原生对象。

普通导航、MCP基本方向与攻击都作为 `NORMAL` 接入。排队旧命令出队及真正原生派发前重验，
失效即跳过副作用并结算原载体。运动释放继续使用精确原资源的清理许可，等待滑行、落地、
制动及定时回调退出；清理不因旧token失效而被取消。

普通机会拾取不占移动安排。`submitIfIdle` 在原lane锁内同时判断空闲和占槽，忙碌立即
结算本次机会，不进入FIFO。高优先意图和动作锁仍在场景准入处检查。

## 规划、移动与约束

`navigation.plan` 使用原导航图和投影物理产生 `NavigationPlan`。脚本只看到值摘要；
Java保留可信实际场景、起点、属性与路线，不开放JSON执行句柄。
拾取及战术用 `CharacterMotion.executePlan`，在自己的原scope、声明控制和角色唯一lane
执行。可信plan携带实际导航配置及预算，执行检查场景、起点、属性和剩余期限。
普通 `navigation.moveTo` 保留原导航提供者scope并消费同一运动组件。

狩猎通过 `EngagementArea` 传入原点位、楼层组和目标保持规则。
战术候选不从诊断Map猜区域，也不扩大CAMP、STACK或ROAM原约束。
候选比较包括当前位置、接敌点、左右有限地面点；已有有效安排在相同条件下保持。
没有安全路线时报告 `NO_SAFE_ROUTE`，不将停止输入或取消成功当作已经安全。

## 接触入伤与生命预算

常驻接触属于受管场景角色，不依赖交战的native/legacy选招路径。
已有 `contact_physical_v1` 防御计算和 `TakeDamageHandler` 也服务旧职业受管来源，
真人客户端保持原输入归属。原生护盾、反伤、HP/MP和死亡沿原handler结算一次。

`CharacterDamage.estimateIncoming` 只读当前原生角色及怪物，输出有时窗的损失范围和覆盖说明。
预测使用同一防御来源及角色级2500ms保护，不按每怪重复计保护。
正向临时防御、护盾资源、未发生的药剂或可能击杀不会被当作未来已有生命值。
路径按实际时间片与保护到期时间比较；在保护期内离开的点不会被反复算作后续接触。

首片已驱动三怪100101、120100、130101在普通地面上的可达区，沿实际8ms递推、96ms批次、
坡度夹限和受击输入界计算。未驱动的1210100仅在普通非事件地图、没有有效controller和
其他自主位置输入来源时使用 `undriven_stationary_body_v1`：仍计算静止原生身体接触，
跳跃AI保持未实现。模型不把AI未实现泛化为全图无限威胁。

真人/外来驱动、冰面怪物运动、复杂AI和缺失身体数据保持 `PARTIAL / UNKNOWN`。
未知不会被记成零风险；已知威胁仍可用于减损，但不能据此宣称全场安全。
生命预算比较真实HP减可信损失上界与配置余量，药效实际发生后才在下一次观测使用。

## 动作时窗与证据

动作锁读取实际选择的攻击输入，按匹配V17的16位攻击字段组合还原body action，
再使用现有 `BotAttackData` 适配动作及匹配WZ帧。负delay沿客户端加载语义先取绝对值，
每帧按 `(delay × (clamp(speed, 2, 10) + 10)) / 16` 做整数计算后累加。
skill0、原生首批技能和legacy使用各自实际body与speed，保持原协议编码。
Buff沿实际 `skill.action` 和已核cast speed6读取身体时窗；无身体动作的数据保持相应原语义。

例如1302000、speed4的五个普通动作均为699ms；三种原生首批Buff的alert2在speed6下为600ms。
这些数值不作为全武器、全技能常数。变身、骑乘、特殊keydown或其他未核路径的限制分别记录。
晚到的已派发输入仍能写入真实时窗，继任安排读取实时锁，已发生事实不被撤回。

源码合同、隔离实际输入与后态、部署和真人画面分别留证。
随机追击、伤害、掉落和重复训练使用有界真实冒烟，不以某秒必须命中或固定路线为门禁。
本说明是源码契约，实际交付以冻结版本、标准构建及对应Issue证据为准。
