# 北斗 1.12 怪物资料与客户端驱动边界

本报告对应 #307，固定官方提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b`，源码调查基线为 Solo `642c4c34481970eefd57515e7cd7b490aa93434e`。本次完成静态资料读取及原生消费链核对；没有取得正常客户端的运动、追击或跳跃采样，不能据此关闭 #307 的客户端采样验收，也不能宣称 #311 的完整行为已验收。

后续在2026-11-07取得四种首批怪物的匹配V17正常活动、起跳/落地及跨foothold样本，独立记录在[正常客户端样本](monster-client-samples.md)。本报告保留原静态调查的冻结结论；当前有限实现、标定参数和限制见[怪物驱动](monster-driver.md)，运行与部署验收按对应冻结轮次记录。

## 复现与产物

在 Solo 仓库根执行：

```bash
python3 beidou-integration/scripts/index_monster_data.py \
  --upstream upstream/BeiDou-Server \
  --language zh-CN \
  --output docs/scene-runtime
```

脚本只读取官方源码与 XML，使用 Python 标准库，不启动服务或连接数据库。XML 不可解析、必要字段格式不可转换时直接失败，保留具体文件栈；成功生成表示静态解析完成，原生 Java 装载和运行可用性仍需各自验证。

| 产物 | 内容 |
| --- | --- |
| `monster-data-index.jsonl` | 每个有效怪物 ID 一行，包含资源路径与 SHA256、原始 info 字段、info/link 链、视觉来源、动作帧 delay、身体框合并、UOL/技能/复活引用缺口、地图资源引用 |
| `monster-data-skills.jsonl` | 每个 MobSkill ID/level 一行，保留成本、间隔、范围、召唤等原始非位图字段 |
| `monster-data-summary.json` | 官方提交及工作区改动、语言、计数、引用缺口和四个首批样本的完整记录 |

`LocalizedDataProvider.getData` 在语言目录存在整个 XML 时优先使用它，否则回退原始 WZ，未按字段混合。本索引按相同文件选择规则处理 `wz-zh-CN` 的 87 份覆盖。该目录没有新增怪物 ID。

## 资料规模与完整性

- 原始 `gms-server/wz/Mob.wz` 根目录有 2054 份怪物定义；`QuestCountGroup/9101000`～`9101003` 的 4 份 XML 是任务计数组，单独列出，未计入怪物 ID。
- 有效中文资源仍为 2054 份；440 份有 `info/link`，全部目标存在且未发现环。按 WZ 相对路径解析的 9167 个 UOL 均能定位节点。此结果只证明静态目标存在，不证明每个原生调用点采用相同的 UOL 解析方式。
- `Skill.wz/MobSkill.img.xml` 有 41 类、509 份等级化技能定义。22 份怪物引用了该文件缺失的 ID/level，详见 summary 的 `missing_skill_definitions`。其中包括 8240098/8240105 的 226/1、217/9、170/52，8610011/8610024 的 200/224；不把这些数据直接标成运行可用。
- 4 份怪物有缺失的复活目标：9460026→9400303，9800075→9800076，9870036→9410030，9894000→9893000。记录保留原始重复引用，不修改官方资料。
- MobSkill 数字召唤条目及普通地图 life 声明引用的怪物 ID 均能找到定义；这仍不证明该技能或地图会在当前流程实际触发。
- 5364 份 `Map.wz/Map` 地图定义中，827 种怪物存在普通 `life/type=m` 声明，1227 种未找到此类声明。脚本生成、活动条件和运行时存活数量不由这个静态计数推导。
- 部分定义缺少基础字段：speed 134 份、fs 55 份、PDDamage/MDDamage 各14份，另有少量 HP、MP、等级或攻击字段缺失。逐行 `missing_info_fields` 表示原字段未提供；飞行、特殊及链接怪物需要结合其资料解释，缺少 speed 本身不等于一个运行缺陷。

## 运动与行为族

以下为解析视觉来源后的非互斥资料分类。一个 Boss 可以同时具有地面、跳跃和特殊行为，计数不能相加为怪物总量。

| 资料特征 | 数量 | 原生已读 | 场景驱动仍需确认 |
| --- | ---: | --- | --- |
| move 动作 | 1650 | `MonsterStats.isMobile` 检查 move/fly 动作，LifeFactory 汇总动作时长 | WZ speed/fs 到实际速度与加减速的规则、寻敌、转向、地形/边界决策、输入时序 |
| jump 动作 | 333 | 视觉框和动作总时长可被原生读取 | 跳跃触发条件、水平/竖直速度、平台选择与落地；节点存在不能证明算法 |
| fly 动作 | 125 | 视觉 movetype、尺寸、框及动作时长 | flySpeed、二维运动、目标与边界、攻击转向 |
| 无 move/fly 动作 | 279 | 原生可提供当前状态与技能 | 固定、机关、复合实体及事件专属规则需分别核对，不能统一当成普通静止怪 |
| damagedByMob 友好标记 | 25 | 原生 `friendly`、伤害与保护规则 | 被怪物攻击、任务/护送与特殊移动语义 |
| boss 标记 | 745 | 原生属性、技能、血条和效果裁决 | 各阶段、攻击选择、区域判定、召唤与特殊机制 |
| 特殊字段 | 241 | 部分 noFlip、自爆、ban 等已由原生消费 | 各字段真实触发、普通输入与事件脚本分工；索引保留原字段 |

`ground_move` 表示存在 move 资料，不把可能兼有 fly 的实体断言为只能在地面移动。`jump_action_present` 明确只表示动作存在。中文覆盖影响有效 Boss 等计数，原始目录的未覆盖统计不可直接替换本表。

## 读取职责与缺口

| 资料/职责 | 当前可复用来源 | 接线边界 |
| --- | --- | --- |
| 当前 HP/MP、位置、死亡、仇恨、controller、原生效果 | `Monster`、`MonsterStats` | 继续读取原生对象，场景只保存驱动运行上下文 |
| 基础属性、friendly/Boss、revive、技能清单、攻击 MP/attackAfter | `LifeFactory.getMonsterStats`，`MonsterInformationProvider` | 原生每次攻击/施法只裁决一次，不自行重做成本和死亡/掉落 |
| 本地动作总时长 | `LifeFactory.getMonsterStats` 汇总 `delay` | 原生总时长不保留完整逐帧序列；链接视觉的总时长还需核对实际读取位置 |
| 视觉链接、movetype、尺寸、框 | `LifeFactory.resolveMonsterVisualData/applyMonsterVisualStats` | 官方解析可沿 info/link 寻找视觉来源；不把 info/link 当作全属性继承 |
| 身体接触框 | Solo `BotMobHitboxProvider` | 现有读取只处理一层 link、stand/move/fly 第0帧；多层链接、UOL 及动态动作框不由该实现保证 |
| speed、flySpeed、chaseSpeed、fs、逐帧动作时长 | 有效 WZ XML | `MonsterStats` 未提供相应客户端物理语义；需 Solo 侧按当前语言 DataProvider 读取。数值读取与完整客户端算法分别验收 |
| 技能成本、HP条件、概率、冷却、范围、召唤 | `MobSkillFactory`、`MobSkill` | 工厂能读取不表示已具备 AI 选招；`canUseSkill(..., true)` 和 `canUseAttack` 会消耗资源及登记使用状态，禁止当无副作用查询重复调用 |
| 移动输入与真实广播 | `MoveLifeHandler`、`MovementParser`、原生 controller | headless 驱动必须映射有效 controller，并派发合法输入。不能仅调用 setPosition 或独立广播冒充原生执行 |

官方 `Monster.canUseAttack` 中 usedAttacks 和动画节拍的部分检查被注释；它仍会扣 MP、登记攻击与清理任务。`canUseSkill(..., true)` 同样扣 MP 并登记技能冷却。场景动作编排必须先确定一次执行，再消费这些入口。

427 份定义本地没有 move/fly 动作，但链接后的视觉来源含有这些动作，清单见 summary 的 `linked_visual_motion_without_local_motion`。原生 `getMonsterStats` 的 animationTimes 来自本地动作，`isMobile()` 又仅检查这张表；视觉链接可解析不能保证这条原生移动资格读取已覆盖。首批四种均有本地 move，不受此项读取差异影响。后续扩展应明确使用有效视觉动作判断，并分别核对原生资格，不能把本表的解析后数量当成原生当前可移动数量。

## 首批训练场样本

四种怪物均在地图资源 `104040000` 存在普通生成声明，均为 `bodyAttack=1`、`fs=10.0`，无 attackN/skillN 主动攻击动作。它们的接触伤害由角色受击路径接续，不能为补行为擅自生成不存在的主动攻击。

| ID / 原始资源 | speed | move 帧 delay（ms） | move 总时长 | stand | jump |
| --- | ---: | --- | ---: | --- | --- |
| 100101 / `0100101.img.xml` | -50 | 120,120,120,120 | 480 | 1帧，未提供 delay | 无 |
| 120100 / `0120100.img.xml` | -30 | 180,180,180,180 | 720 | 180,100,180 | 无 |
| 130101 / `0130101.img.xml` | -50 | 120,120,120,120 | 480 | 1帧，未提供 delay | 无 |
| 1210100 / `1210100.img.xml` | 0 | 100,100,300 | 500 | 180,150,180 | 1帧，未提供 delay |

speed 保留 WZ 原数值，未转换为 px/s。`explicit_duration_ms=0` 且 delay 为 null 表示资料未给时长，不能解释为应当零时长播放。动作帧时长不能作为移动距离或物理速度。

本分支另有[有限地面驱动候选](monster-driver.md)：100101、120100、130101 使用可追溯兼容客户端的 `journey_ground_reference_v1`，通过原生输入推进单foothold地面行为。它的算法来源、源码实现与隔离/真人证据分别记录；本索引的 `runtime_support=not_certified_by_static_index` 继续表示静态盘点不认证运行支持。1210100 的跳跃和四种怪物的匹配官方客户端样本仍待核。

首批样本需补下列正常客户端证据，当前统一为待核：

1. 空闲时实际位置/朝向与输入帧，普通寻敌或受击后追击/停止的差别。
2. 相同 foothold 的移动速度与边界转向；从实际输入核对 WZ speed/fs 的计算。
3. 1210100 的 jump 触发条件、轨迹与落地；没有样本时不得自动开启跳跃模拟。
4. 接触受击输入、命中间隔及死亡/恢复后的行为。
5. 真人/机器人混合时 controller 交接；同一实际怪物任一时刻只有一个有效驱动。

## 后续覆盖登记

首片之外按本索引保留四组明确待实现范围：带跳跃行为（含首批1210100的跳跃扩展）、飞行、主动攻击/技能，以及固定/友好/Boss/特殊机制。先取得相应正常客户端输入和算法来源，再作为 #305 的具体后续切片登记；本报告没有把 2054 种静态资料转换成运行支持承诺。召唤物/宠物的客户端驱动继续归 #316/#317，MobSkill 生成怪物与它们分别处理。

本次验证为索引脚本全量成功执行、2054/4/41/509 与固定官方资源相符、首批四个记录及引用缺口核对。原生 Java 工厂全量装载、隔离场景输入、主分支部署、真人画面和完整 AI 仍需后续证据。
