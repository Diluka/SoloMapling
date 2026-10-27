# 协作型组队任务机器人设计

范围：Solo main当前北斗集成；官方北斗1.12固定75a86268738ca8845e06ea9d59ec36d15b22c91b。未修改源码、配置、服务或数据库，未做运行/客户端通关验收。Serena Java初始化退出后，使用限定文件文本读取。

## 结论

可以复用一个PartyQuestBot执行内核，每种任务提供一个独立QuestRules规则实现，玩家看到的“月妙bot/废都bot/玩具城bot”等是该规则对应的任务身份。公会成员身份、职业、等级和存档保持不变，进入副本时挂载一次任务活动，退出后恢复原活动。

每种任务仍需独立阶段规则和验收；通用内核解决实例隔离、动作执行、协作分工与取消恢复，不会让所有任务自动变成“跟随+打怪”。配置档、空类和已命名profile均不能报告为已可通关。当前OPQ是两阶段实验实现，不是完整北斗OPQ能力；详见 [OPQ 现状](OPQ-AUDIT.md)。

## 已确认的引擎契约

| 原生路径 | 实际契约与设计影响 | 源码证据 |
|---|---|---|
| 事件脚本加载 | Channel枚举英文和当前语言event目录并去重；AbstractScriptManager逐文件优先语言脚本，缺文件回退英文。中文独有event能被枚举，实际JS初始化成功与NPC入口接通仍需另外验证。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/Channel.java:463；scripting/AbstractScriptManager.java:51 |
| EventManager查找 | getEventManager找不到key会返回0_EXAMPLE的fallback.em。profile启动前应校验实际em.getName等于期待key，不能仅以非null当脚本存在。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/event/EventScriptManager.java:82 |
| 开启实例 | startInstance选择/锁大厅、执行setup、setLeader、registerParty、startEvent。规则入口沿正常NPC及原生事件流程调用，保留等级人数与任务资格，不能只warp进图。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/event/EventManager.java:817 |
| 参加实例 | registerPlayer要求isLoggedInWorld；保存成员并设置chr.eventInstance，随后playerEntry。registerParty只登记eligible、online且在入口map内的队员。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/event/EventInstanceManager.java:270、394 |
| Headless地图脚本 | 当前切面跳过BotClient在MapleMap.addPlayer内的MapScriptManager.runMapScript；EIM.registerPlayer的playerEntry并没有被此切面跳过。须区分实例入口函数和地图onUserEnter，不可声称全部脚本都禁止或全部可用。 | beidou-integration/src/main/aspect/soloMapling/beidou/GameplayHooks.aj:104 |
| 实例地图 | Character.getWarpMap有EIM时从eim.getMapInstance取图；现有SoloUtilities.getMapleMapById却始终取频道公共MapFactory。因此任务内地图查找必须带EIM身份；相同mapId不能证明同一副本。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/Character.java:1304；src/main/java/soloMapling/server/SoloMaplingUtilities.java:141 |
| 阶段与生命周期 | EIM有changedMap/afterChangedMap、monsterKilled/allMonstersDead、leader变更、playerDisconnected、unregister/dispose。任务观察器用这些实际结果驱动规则，不凭角色到坐标就标阶段完成。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/event/EventInstanceManager.java:489、513、597、646 |
| 正常NPC交互 | NPCScriptManager.start/action维护每个Client的会话与脚本；BotClient.sendPacket为空，不会自行响应NPC对话。需要受控对话适配器，在实际NPC脚本上下文中执行期待的菜单步骤。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/npc/NPCScriptManager.java:116、172；beidou-integration/src/main/java/org/gms/client/BotClient.java:38 |
| 正常门户 | PortalScriptManager.executePortalScript执行portal.enter并串行化共享脚本。现有BotScriptedWarp只预置一个地铁边，直接changeMap绕过脚本，不适用于有任务判断的PQ门户。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/portal/PortalScriptManager.java:68；src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotScriptedWarp.java:14 |
| 正常机关 | Reactor.hitReactor按WZ状态、方向/技能约束运行onHit/act脚本及物品反应器流程；CustomReactor.hitReactor只是state++和动画广播，不能算原生完成。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/server/maps/Reactor.java:383；src/main/java/soloMapling/MapVFX/CustomReactor.java:99 |
| 正常拾取 | Character.pickupItem进行原生拾取；DropCommands.lootItemListOnFloor/botLootSingleDrop直接调用MapleMap.pickItemDrop，该方法仅移除地面对象并标pickedUp，不向背包入账。任务物品不能沿当前表演拾取路径实现。 | upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/Character.java:2003；server/maps/MapleMap.java:1049；src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java:227、261 |
| 正常投放 | 现有botDropItem/botDropItemQty/botThrowItem等新建物品并spawnItemDrop，不扣原背包。PQ交物/祭品/唱片应使用原生背包消耗与落地路径，并按数量、所有权、reactor区域条件验收。 | src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java:53、80、104；upstream/BeiDou-Server/gms-server/src/main/java/org/gms/server/maps/MapleMap.java:2261 |

## 最小可实施结构

1. `PartyQuestBot`：保留一个通用行为执行器和生命周期；承担读取快照、执行分配动作、等待原生结果、失败撤退。不要为每种任务复制整份BotSM。
2. `QuestRules`：每种任务family一个规则实现（比如HenesysRules/KerningRules/LudiRules/OrbisRules）。提供资格检查、阶段识别、可用团队角色、阶段目标、成功/失败判据和退出策略。复杂机关解题保留明确的Java策略，地图/坐标/物品ID/门槛等参数可用数据文件，不先引入覆盖所有脚本的庞大DSL。
3. `QuestRunContext`：一次真实实例一份共享上下文，记录world/channel、event key、具体EIM对象及运行代次、队伍/远征身份、真人队长、参与角色、当前阶段、角色分工、对象预约、协作barrier与超时。仅mapId、仅event名字或JVM全局单例均不足以隔离不同队伍。
4. `QuestActionExecutor`：共用MoveTo、EnterPortal、TalkToNpc、AttackMonster、HitReactor、PickUp、DropFromInventory、StandAt、WaitForNativeCondition。每条动作在发出前和回调后校验当前活动代次、角色当前FSM、EIM、地图实例与目标对象身份。
5. `PartyQuestCoordinator`：在同一个run内为机器人分配任务角色（队长交接/收集/战斗/守护/机关站位/职业机关等），唯一预约reactor/箱子/坐标槽位，等待全队实际就位后由负责角色执行NPC检查。玩家保留队长时机器人不抢代答或启动下一阶段。

典型状态为：资格检查→集结→正常报名/入场→实例绑定→分配阶段职责→执行/等待原生结果→下一阶段→正常领奖/离场→恢复公会活动。缺队员、队长掉线、地图不匹配、实例已销毁、不可达或执行超时进入明确失败/撤退。

## 任务profile与运行状态必须分开

- QuestProfile是只读规则与版本化参数，记录family、具体eventKey/variant、入口、支持地图、规则版本和需要的能力。Cafe难度、Magatia阵营、Holiday变体优先作为同family参数，不自动变成多份复制bot代码。
- QuestRunContext只存本次运行态，包含实际EIM与generation，stage进度以北斗脚本/地图/背包实际结果为准。不要直接改clear标志、伪造任务物品或自行发奖励来证明完成。
- 公会长期身份、职业和成长不放进run context，不随选择QuestRules重建。副本结束只清活动上下文；持久成长仍沿原生存档。
- 规则切换只发生在未入场、正常退出或安全终止本次run后。同一次副本中只切该profile内的阶段/职责，不能随时把OPQ规则替换成LPQ规则。
- 游戏服重启后EIM和对象OID都失效，最多恢复“需要重试/回城”的长期目标，不恢复旧活动回调和副本地图对象。

## 分层验收与报告状态

1. **发现/建档**：源码里有事件文件与入口；单独标入口可达、语言分支、未接通及代码待修。
2. **规则已实现**：每个阶段有真实动作与终止条件，不把配置档或空策略算完成。
3. **规则单测**：给原生观察快照，验证分工/动作/忙态/取消；未知阶段不乱执行，错误规则不能直接发奖励。
4. **引擎集成测试**：在隔离资源中跑真实EIM登记、Graal入口、NPC/门户/机关、背包拾取与交物链；同mapId双实例/双频道无任务和角色交叉。
5. **实际任务验收**：一真人与对应数量机器人正常入场、逐关、原生领奖和离场，验证真人任务进度与核心成员存档；每种任务及变体分别列结果。

通过静态盘点或120项既有Java测试不能自动将所有PQ标可通关。当前没有运行上述任务级验证；本文件是有源码依据的框架设计。

## 不同任务后端的边界

EIM是事件脚本型任务的运行身份，不能强制套给所有玩法。奈特金字塔、嘉年华、阿里安特和武陵道场各有Java管理器/比赛或频道槽位。通用内核依赖一个小型`QuestRuntime`适配器，提供真实运行identity、名册、实例地图、阶段快照、时间预算和正常结束接口；EIM只是其中一种实现。无法取得真实后端身份时停止入场，不用mapId拼一个假实例。

竞技属于相邻玩法：嘉年华每支队伍有独立协调器和共享板，双方只通过原生比赛交互，不能跨队共享私有计划；阿里安特是个人竞争，禁止用全参赛者共享板作弊。废弃地铁清怪缺原生入口API，保持阻塞，不当作已可启动任务。

## 公会编队与任务分工

任务成员由公会陪伴系统提供稳定角色，任务规则选择不会改公会身份、职业或等级。会长等级±10仅是核心小队的选择规则，不能覆盖任务原生门槛：GPQ可能另需30级及以下同公会专员，CWK需五职业及实际符印技能，APQ需婚姻和性别资格，远征与对抗也不都使用六人party。入场前应报告具体缺口，不能调级、临时结婚、扩队或跳过机关凑资格。

收集者、守护者、搬运者和站位者是阶段职责，一个成员可在顺序阶段兼任；同时发生且互斥的职责则必须有不同合格成员。规则中的角色列表不是新增人口数量，也不是每个职责都永久绑定一个bot。

## 实施顺序

先做协作内核与原生动作链，用月妙验证真实收集—投放—守护—交付，再用废弃都市/玩具城验证组合与持续站位；随后扩大到迷宫、毒雾森林、海盗船及复杂合作PQ。现有OPQ可参考任务分配思路，但不能依赖旧全局状态或两段rush直接宣布全通。远征、Java比赛与来源断链玩法分别通过对应适配器和独立验证推进。

每个任务的具体规则、来源疑点与实现Issue见 [规则册](RULES.md)，范围与完成状态见 [任务目录](README.md)。团队协作是优先验收项，具体协议和场景如下。

---

## 协作是核心能力

`PartyQuestBot` 是团队执行者，每种 `QuestRules` 提供阶段与任务规则；一次真实副本运行由一个 `PartyQuestCoordinator` 负责协调。公会队员的身份、职业和成长独立于任务活动，不因分配“收集者”“守护者”或“站位者”而重新生成角色。

协调目标包括分工、前后依赖、互斥目标、同时就绪、失败补位和全队退出。协作不依赖机器人各自随机选择目标，也不依赖 LLM 之间对话达成共识。LLM 后续可解释计划、回应真人，但任务分配、物品操作及原生清关判断由确定规则控制。

最小实现只需要同 JVM 的实例协调器、队员执行器和任务规则。不先引入通用工作流 DSL、网络消息总线或分布式锁。

## 一次副本一份协调状态

- `QuestRunContext` 绑定真实后端运行对象（事件型为 `EventInstanceManager`）、世界/频道、规则 key、运行代次，以及当前队伍、远征或公会名册。只有 mapId 或事件名不能标识一次运行。
- 协调器在实例内串行处理观察与回报；队员得到不可变的分工版本与动作指令。慢动作异步执行，结果带回 `runGeneration + planVersion + taskId + attempt` 后重新校验。
- `TeamBoard` 仅保存本次运行的观察事实、计划、分工、目标预约、就绪状态与失败原因。事实来自 EIM、真实地图对象、角色位置、原生背包和脚本结果；保存观察时间及来源，不把队员的本地计数当权威事实。
- 上下文、原生地图实例、OID 与任务租约只属于当前进程运行。重启后重新识别世界与入口，不恢复旧 EIM、对象句柄或迟到回调。
- 普通 Party、Expedition 和 GuildQuest 名册通过小型 `QuestRoster` 适配器读取，保留各自容量和准入语义；GPQ/CWKPQ 不强塞进一个六人队模型。

原生 `EventInstanceManager.registerPlayer/registerParty` 负责成员登记与 `playerEntry`，任务必须以实际登记结果为准；实例内地图从 EIM 获取。参见 `upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/event/EventInstanceManager.java:270、394`、`upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/Character.java:1304`。现有公共地图查找 `src/main/java/soloMapling/server/SoloMaplingUtilities.java:141` 不适合作为 PQ 实例定位依据。

## 从阶段目标拆出可分配任务

每个任务规则明确四件事：当前阶段怎样识别、要达成什么、哪些前置条件成立后可以做、原生游戏怎样证明完成。复杂解谜写成任务专用的 Java 规则，地图/物品/坐标参数使用数据文件即可。

例如一个阶段可以给出以下小型依赖图：

```text
检查能力与库存
  ├─ 收集物品A ─┐
  ├─ 收集物品B ─┼─ 原生交接并确认队长持有 ─ NPC提交 ─ 观察原生阶段推进
  └─ 守护友军 ──┘（持续任务，阶段结束前不能因其他任务完成而停止）
```

任务可以是有限动作，也可以是保持条件：收一件物品、击打一个机关、守住某一区域、站在指定位置直到检查结束。每项定义能力条件、目标、数量/位置、前置依赖、成功证据、重试预算和阶段截止时间。依赖图只表达该阶段实际需要的关系，不为所有任务构造一个无所不包的语言。

### 分配原则

1. 先筛选已在同一实例登记、存活且能执行动作的成员，再按实际技能、装备与移动能力、背包/道具、当前位置、当前职责匹配。职业名或表演动画不等于已具有机关所需技能。
2. 优先完成不能替代的职责，再分配通用工作。例如先保留低等级通路、指定职业机关和真人队长交付，剩余角色承担清怪与收集。开始任务前检查完整编制，而不是入场后才发现缺关键角色。
3. 稳定分工优先：能力和目标仍有效时，成员继续原任务；不每 tick 为追求更短路程交换职责。只在任务完成、阶段改变、成员失效或计划无法推进时重新分配。
4. 分散收集区域、机关目标与站位槽。某成员已持有任务道具时，优先让其继续运输或交付，避免无意义地反复转手。
5. 玩家显式承担的站位、收集或交付槽位进入 `humanCommitted` 状态。默认保留真人队长的报名、关键 NPC 确认和路线选择权；bot 可以提示“其余队员已就位”，不能默默抢代答或启动下一关。
6. 真人可通过任务指令表达“我负责队长交付”“我站某号位”“这一步交给 bot”。自动补位需要先有这种授权，或规则已经明确允许；AFK 不等于取消玩家原委托。

### 单任务与资源预约

任务状态建议保持为：`WAITING → CLAIMED → RUNNING → VERIFYING → DONE`，旁路为 `RETRYABLE / BLOCKED / CANCELLED`。`VERIFYING` 表示指令已发出但必须等原生结果，不能立即将目标重新分配。

任务租约记录持有者、任务版本和有效期；可抢占动作有取消/确认终止步骤。资源预约键包含当前运行代次、EIM 对象、地图实例及运行 OID/位置槽，不以模板 ID 或 mapId 作为全局锁。

- 两个 bot 不能同时领取同一掉落、机关或唯一站位槽；同一角色不能同时执行互相冲突的移动、战斗与投物动作。
- 预约只协调机器人，不阻止真人合法操作原生游戏。真人先拾取物品、击打机关或改变位置时，重新观察并收敛计划，不强行夺回目标。
- 租约过期不代表动作未发生。先取消旧动作、隔离旧回报，再读原生结果；确认未完成且可安全重试，才给新成员下一次任务。
- 原生接口没有统一幂等键。投物、交付、领取奖励等副作用动作不能盲目重放：根据原生库存差值、地面对象、机关状态、NPC阶段和奖励资格核对。结果不确定时暂停并报告，不伪造补偿物品或发第二次奖励。
- 重试受单动作预算和剩余任务时间双重限制；未知阶段、连续不可达或无法验证结果进入明确的阻塞/撤退路径，不能无限循环。

## 同时站位与阶段屏障

站位机关的 `barrier` 是持续成立的游戏条件，不是所有队员各报告过一次“到达”。每次关键检查前重新核对：成员仍存活、仍属于当前实例和名册、处于其被分配的原生区域、没有冲突动作，且人数/区域分布满足本任务规则。

有人离位、掉线、死亡、被击退或切图时，立即撤销该槽位就绪；其他站位成员保持位置或按规则撤回安全区。条件全部成立后，仅被分配的队长/检查者执行一次 NPC 检查；以实际脚本反馈决定成功、换组合或失败。等待 NPC 结果期间不让闲置算法把成员带走。

阶段完成也不等于每项本地工作均返回成功。协调器以原生阶段标志、机关结果及地图转换确认推进，然后废止旧阶段任务和租约；迟到的收集/攻击/投物回报不能在新阶段再触发旧动作。

## 四个任务中的具体协作

### 月妙：先播种，再持续守护与收集

设计分工：六色种子目标按真实 reactor/OID 分成若干收集—投放任务；成员按现有库存和距离认领，不重复攻击别人已认领的植物，也不向错误花台投种。六花原生状态完成后切换计划：至少保留能守住月妙的战斗成员，其他人收年糕并通过正常物品路径集中到队长。

队员“看见第10个年糕掉落”不等于已完成。必须核实原生背包中可交付的10个 `4001101` 到达队长，真人队长再与兴儿交付；只在原生 `clearPQ` 后停止守护并离场。友军月妙死亡是任务失败，不再继续捡物假装可完成。

依据：`upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/1012112.js:88`、`upstream/BeiDou-Server/gms-server/scripts-zh-CN/reactor/9108000.js:9`、`upstream/BeiDou-Server/gms-server/scripts-zh-CN/event/HenesysPQ.js:259、265`、`upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/1012114.js:39、67`。默认3～6人是准入条件；本段没有声称固定多少战斗者一定能守住，需按实际能力与伤害运行验证。

### 玩具城：五人站位与队长检查

第8关由 `LudiPqRules` 保存当前九箱选五的候选组合及失败反馈，协调器为五名不同成员预约五个区域。真人希望站位时显式保留该槽，其余由 bot 补齐；真人仅负责检查时，可让五个 bot 站位。

五名成员都在区域内并保持就绪后，通知真人队长检查 NPC `2040043`。失败后统一结束本轮 barrier，只移动需要变化的成员，再等新组合全员就绪；不让每个 bot 独立试组合。若采用最少5人队，队长同时站位并触发NPC检查是否实际可操作需验证，不能额外假设必需第6人或默认一定可兼任。

依据：`upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/2040043.js:113`。当前默认准入5～6人；第8关确实要求五名独立角色站位，准入人数放开不自动消除机关人数。

### 毒雾森林：压血、停手、捕捉、交接

捕捉阶段将每只怪物 OID 预约给一组明确的压血者/捕捉者，捕捉者先持有原生净化球 `2270004`。压血者使用能控制伤害的动作，将 `9300175` 降至原生捕捉门槛以下后停止攻击；捕捉者核对当前HP并执行正常捕捉。其他成员不把已预约的捕捉目标当普通清怪对象。

必须以捕捉道具消耗与真实 `4001169` 入账作为证据。怪物死亡或被其他人提前捕捉时重新观察并释放预约，不直接补生弹珠。收齐后正常交接到队长，队长持20颗向 `2133001` 提交，观察下一阶段。

依据：`upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/2133001.js:46`、`upstream/BeiDou-Server/gms-server/src/main/java/org/gms/net/server/channel/handlers/UseCatchItemHandler.java:164`。原生要求怪物HP低于40%，不是任意攻击一次就能捕捉。

### 公会任务：先满足独特能力，再分并行支线

进入前把同公会成员、`<=30`通路专员、地形/职业能力、保护耳环等实际条件纳入编队。低等级通路与职业支线是不同的能力槽，可以由同一成员兼任时再合并；不因核心小队人数凑够就宣布阵容有效。

雕像记忆、献礼反馈等步骤由一份团队规则保存共享序列与已尝试结果；分配一名被允许的操作者，其余成员避免误击或误投。骑士长矛/水路服饰可分支并行，取得物品后回报原生背包与位置，再按投放顺序归集。低等级专员失效且没有合格替代者时，该支线阻塞，不能临时给高等级成员降级或跨实例复制道具。

“会长等级±10”的核心小队规则可能无法覆盖`<=30`通路：会长高于40级时，合格核心队员均可能高于30级。设计应从同公会中另选持久的PQ专员，或在开始前清楚报告缺员；不悄悄放宽核心等级规则。

依据：`upstream/BeiDou-Server/gms-server/scripts-zh-CN/portal/under30gate.js:30`、`upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/9040000.js:68`、`upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/9040009.js:61`、`upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/9040006.js:27`。入口默认6～30人，采用公会实例名册；NPC的职业建议与真正机关门控需要分别记录。

## 变化、补位与失败策略

| 情况 | 协调器行为 | 可以补位的条件 |
|---|---|---|
| 成员暂时卡路或动作超时 | 取消旧动作，检查原生结果和剩余时限，有限重试或改派 | 替代者已在当前实例，具有能力且目标尚未完成 |
| 掉线、死亡、离图或被原生注销 | 更新名册、取消该角色任务和就绪状态，读取任务原生失败规则 | 任务尚未被原生终止；不把副本外新人强塞进EIM |
| 关键物品在掉线成员身上 | 检查原生掉落/恢复/回归规则，有限等待或正常失败 | 物品确实可依法转交或重新获得；不能复制或假定已经回库 |
| 缺职业技能、婚姻/性别资格、低等级通路角色 | 入场前阻止无效编队；运行中判定为能力缺口 | 当前合法成员中有真实满足条件的替代者 |
| 真人负责槽位未行动或临时离位 | 保留职责并提示等待；按事先授权接管，否则请求真人让位 | 玩家授权或该活动的明确超时接管规则已成立 |
| 领队变更、退队、公会/远征名册变化 | 重新校验权限与分工，旧队长交付指令失效 | 新领队为原生合法领队，规则与玩家授权允许继续 |
| 实例超时、已销毁、关键友军死亡或无法完成硬机关 | 结束本次运行，沿正常失败/离场流程清理 | 不再派新动作；保留可解释失败原因 |
| 服务器停止或切换另一任务 | 增加运行代次、取消动作与预约，清理上下文；安全离场后恢复原活动 | 新任务必须重新集结/登记，不复用旧EIM/OID |

补给不足按当前任务规则判断：能在原生允许位置安全补给且不破坏阶段时进入恢复任务；无法补给则重新分工或撤退。不能用填满HP/MP、伪造药品或跳过机关作为“恢复”。副本退出后也应先完成原生物品、奖励及成员注销处理，再恢复公会主业。

## 协作验收矩阵

以下均是后续实现必须补齐的验收，当前未执行。

| 场景 | 必须观察的结果 |
|---|---|
| 两bot同时认领同一物品/机关 | 只有一份有效预约；另一方得到新目标或等待，真实背包不重复入账 |
| 投物已发生但回报超时，租约改派 | 先确认原生消耗/地面/机关结果；不得第二次扣物、补生物品或误报丢失 |
| 月妙播种后进入守护阶段 | 每个花台物品正确、月妙为友军；收集者不导致守护者全部离岗，10年糕真实到队长后原生提交 |
| LPQ五人就位，一人被击退 | barrier立刻撤销，检查者不提交；恢复后只发一次检查，失败组合不被并行尝试 |
| LPQ真人选择一号位 | bot不争该位置；真人撤离后按授权/提示流程处理，不能默认AFK就替换 |
| Ellin压血与捕捉竞争 | 捕捉目标不会被全队继续集火；真实净化球消耗和弹珠入账匹配，目标丢失后正常重派 |
| GPQ人数够但缺`<=30`成员 | 入场前报告实际缺口；运行中不改等级、不复制角色，不将默认6人等同能力齐全 |
| 关键成员持物掉线或死亡 | 按原生恢复/失败规则处理；补位者须合法持物和具备能力，不直接补物 |
| 队长变化时旧交付回调到达 | 旧权限指令失效；新队长重新确认后再继续，不重复交付或领奖 |
| 两队同频道同mapId副本并行 | 任务、物品、OID预约、阶段和回报均按EIM隔离；一队失败不取消另一队 |
| 两频道运行同一任务 | 名册和共享板隔离；无全局单例leader/context串用 |
| 超时/停服/换任务后迟到成功回报 | 代次失效，回报不产生动作/状态推进/第二次奖励；重启不恢复旧运行对象 |
| 阶段局部工作完成但原生阶段未清 | 保持等待或报告机制阻塞，不以bot本地DONE替代clearPQ |
| 一真人与足量bot完整运行 | 正常报名、逐阶段分工、原生物品/机关/奖励、离场与角色存档分别留证；每任务及变体独立记录 |

设计完成、规则实现、原生引擎集成通过、真实客户端完整通关是四个独立状态。团队协调的单测不能替代实际原生入口、物品流和机关测试；更不能让一份通用内核的通过结果覆盖所有任务规则。
