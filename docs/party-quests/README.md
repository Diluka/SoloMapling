# 北斗 1.12 组队任务 bot 目录

**交付状态：全量静态探索、逐家族规则设计和后续实现任务；本轮没有新增可运行的组队任务 bot，也没有运行副本通关。**

用户目标是机器人彼此协调分工，与真人玩家合作完成任务。采用一个 `PartyQuestBot` 执行内核、每家族一份 `QuestRules`、每次真实运行一份团队协调器；换任务规则不换公会成员身份和成长。

- [协作架构与验收矩阵](DESIGN.md)：分工、任务预约、持续站位屏障、失败补位、真人职责、实例隔离。
- [逐家族规则册](RULES.md)：入口、门槛、职责、专用机制、原生收益和来源问题。
- [OPQ 现有实现审查](OPQ-AUDIT.md)：旧实现只有两段原型，不能视为已支持完整女神之塔。
- [机器可读目录](catalog.json)：全部事件映射、变体、完整约束及源码依据；它是设计资料，不是运行配置。
- [总跟踪任务](https://github.com/Diluka/SoloMapling/issues/13)、[协作内核](https://github.com/Diluka/SoloMapling/issues/14)、[原生动作适配](https://github.com/Diluka/SoloMapling/issues/15)、[Milestone](https://github.com/Diluka/SoloMapling/milestone/2)。

## 盘点范围与证据

基线为官方北斗发布 `1.12`、提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b`，Solo `e45097bbe54a0d1a2188b2fe68bca67f4449fb90`，核对日期2026-10-27。英文事件108份、中文事件110份，合并后110个唯一事件key已经全部分类、没有遗漏或重复；另查Java/Channel驱动的挑战和比赛。中文文件存在时优先使用，缺文件才回退英文；中文脚本执行失败不自动换英文。

**38个设计家族 = 35类合作任务/Boss/挑战 + 2类竞技相邻玩法 + 1类入口断链候选。** 同家族阵营/难度变体合并设计，不能把38解释成38种传统公众PQ。交通、单人剧情、野外Boss刷新、典礼和系统事件均明确排除。

正常管理端鉴权只读核对的本地配置快照：`use_enable_solo_expeditions=false`、`use_enable_party_level_limit_lift=false`、`use_enable_stage_skip=false`、`use_party_for_starters=false`。因此下表采用当前中文脚本的原始门槛；这些是2026-10-27配置快照，不保证后来仍相同。角色等级范围、队伍人数、职业、婚姻、任务状态和物理解谜人数应分别检查。

脚本与WZ入口、阶段、奖励/出口的静态追踪，不等于已在当前镜像执行过。怪物/机关实际掉落表、真实JS执行、人物技能和路径仍需隔离实例及客户端验证。缺事件key还可能返回 `0_EXAMPLE` 管理器，不能只检查非null。

## 每家族一份规则和验收任务

### 经典合作PQ

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| 月妙组队任务／迎月花山丘 | 3～6人；10～255级；10分钟 | [`HenesysPqRules`](RULES.md#henesys-pq) | [#16](https://github.com/Diluka/SoloMapling/issues/16) |
| 废弃都市组队任务／第一伴奏 | 3～6人；21～30级；30分钟（中文最多6人，英文最多4人） | [`KerningPqRules`](RULES.md#kerning-pq) | [#17](https://github.com/Diluka/SoloMapling/issues/17) |
| 玩具城组队任务／时空裂缝（LPQ） | 5～6人；35～50级；45分钟 | [`LudiPqRules`](RULES.md#ludi) | [#18](https://github.com/Diluka/SoloMapling/issues/18) |
| 玩具城迷宫组队任务（LMPQ） | 3～6人；51～70级；15分钟 | [`LudiMazePqRules`](RULES.md#ludi-maze) | [#19](https://github.com/Diluka/SoloMapling/issues/19) |
| 毒雾森林（艾琳森林组队任务） | 4～6人；44～55级；冒险家；30分钟 | [`EllinPqRules`](RULES.md#ellin-pq) | [#20](https://github.com/Diluka/SoloMapling/issues/20) |
| 海盗船（百草堂海盗组队任务） | 3～6人；55～100级；首阶段4分钟，后续按阶段计时 | [`PiratePqRules`](RULES.md#pirate-pq) | [#21](https://github.com/Diluka/SoloMapling/issues/21) |
| 女神之塔（天空之城组队任务） | 5～6人；51～70级；45分钟 | [`OrbisRules`](RULES.md#orbis) | [#22](https://github.com/Diluka/SoloMapling/issues/22) |
| 阿莫利亚挑战（结婚组队任务/APQ） | 6人；40～255级；全员已婚、至少一男一女、门票；75分钟 | [`AmoriaRules`](RULES.md#amoria) | [#23](https://github.com/Diluka/SoloMapling/issues/23) |
| 玛加提亚：罗密欧与朱丽叶 | 4人；71～85级；45分钟；A/Z阵营变体 | [`MagatiaPqRules`](RULES.md#magatia) | [#24](https://github.com/Diluka/SoloMapling/issues/24) |

### 公会／职业协作PQ

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| 公会任务：沙连尼安遗迹（GPQ） | 同公会6～30人；1～255级；公会队列；90分钟 | [`GuildQuestRules`](RULES.md#guild-quest) | [#25](https://github.com/Diluka/SoloMapling/issues/25) |
| 绯红要塞组队任务（CWKPQ） | 远征6～30人；90～255级；五职业及所需技能 | [`CrimsonwoodKeepRules`](RULES.md#cwkpq) | [#26](https://github.com/Diluka/SoloMapling/issues/26) |

### 特色／季节合作PQ

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| MV的巢穴／寻宝组队任务 | 4～6人；140～255级；45分钟＋奖励房 | [`TreasurePqRules`](RULES.md#treasure) | [#27](https://github.com/Diluka/SoloMapling/issues/27) |
| 高级之路／网吧组队任务 | 3～6人；21～120级；45分钟；六张主题图变体 | [`CafePartyQuestRules`](RULES.md#cafe) | [#31](https://github.com/Diluka/SoloMapling/issues/31) |
| 假日组队任务／枫之圣诞节雪人保卫战 | 3～6人；21～30／31～40／41～50三级段；15／20／25分钟 | [`HolidayPartyQuestRules`](RULES.md#holiday) | [#32](https://github.com/Diluka/SoloMapling/issues/32) |

### 组队Boss／生存挑战

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| 首领突袭／Boss Rush | 1～6人；1～255级；大厅按队伍等级分档 | [`BossRushPqRules`](RULES.md#boss-rush) | [#28](https://github.com/Diluka/SoloMapling/issues/28) |
| 少林密室／武林妖僧 | 1～6人；120～255级；发起者任务8534完成；30分钟 | [`ShaolinBossRules`](RULES.md#shaolin-yaoseng) | [#29](https://github.com/Diluka/SoloMapling/issues/29) |
| 上海秘密任务／大王蜈蚣 | 1～6人；25～90级；10分钟；不是武陵道场 | [`GiantCentipedeBossRules`](RULES.md#shanghai-wugong) | [#30](https://github.com/Diluka/SoloMapling/issues/30) |
| 奈特金字塔 | 可单人；组队同图至少2人；发起者40级以上，非HELL最高60级 | [`NettsPyramidRules`](RULES.md#netts-pyramid) | [#33](https://github.com/Diluka/SoloMapling/issues/33) |
| 武陵道场 | 普通party上限6人；操作者25级以上；初入全队等级差≤30 | [`MulungDojoRules`](RULES.md#mulung-dojo) | [#36](https://github.com/Diluka/SoloMapling/issues/36) |
| 未来之城Boss组队战 | 2～6人；70～255级；五场Boss变体，各有时限与任务/入口条件 | [`NeoCityBossRules`](RULES.md#neo-city-bosses) | [#41](https://github.com/Diluka/SoloMapling/issues/41) |
| 帕普拉图斯（闹钟）组队Boss | 事件声明1～6人、1～255级；门户前置另校验；45分钟 | [`PapulatusBattleRules`](RULES.md#papulatus-battle) | [#48](https://github.com/Diluka/SoloMapling/issues/48) |
| 幽灵船长（Latanica）组队Boss | 事件声明1～6人、1～255级；门户前置另校验；10分钟 | [`LatanicaBattleRules`](RULES.md#latanica-battle) | [#49](https://github.com/Diluka/SoloMapling/issues/49) |

### 准入断链待验证

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| 废弃都市地铁清怪挑战（尘土飞扬的平台／999号列车） | 入口宣称25～30级；原生旧API缺失，准入和可解性未证实 | [`KerningSubwayHuntRules`](RULES.md#kerning-subway-hunt) | [#34](https://github.com/Diluka/SoloMapling/issues/34) |

### 竞技相邻玩法

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| 怪物嘉年华 | 两支等人数队；通常2～6人/队；CPQ1为30～50级、CPQ2为51～70级 | [`MonsterCarnivalRules`](RULES.md#monster-carnival) | [#35](https://github.com/Diluka/SoloMapling/issues/35) |
| 阿里安特竞技场 | 个人竞争；20～30级；不得在普通party；人数沿报名管理器 | [`AriantColiseumRules`](RULES.md#ariant-coliseum) | [#37](https://github.com/Diluka/SoloMapling/issues/37) |

### 前置／技能合作任务

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| 扎昆前置：未知死亡矿井 | 6人；50～255级；发起者任务100200；30分钟 | [`ZakumPreQuestRules`](RULES.md#zakum-prequest) | [#38](https://github.com/Diluka/SoloMapling/issues/38) |
| 黑龙前置：生命洞穴迷宫 | 6人；120～255级；25分钟 | [`HorntailPreQuestRules`](RULES.md#horntail-prequest) | [#39](https://github.com/Diluka/SoloMapling/issues/39) |
| 雪域组队：护卫泰勒斯 | 1～4人；80～255级；发起者任务6192进行中；10分钟 | [`ProtectTylusRules`](RULES.md#elnath-protect-tylus) | [#40](https://github.com/Diluka/SoloMapling/issues/40) |
| 元素塔纳托斯（法师技能组队任务） | 2名法师；100～255级；发起者技能任务状态要求；20分钟 | [`ElementalThanatosRules`](RULES.md#elemental-thanatos) | [#50](https://github.com/Diluka/SoloMapling/issues/50) |
| 拯救 Delli（技能任务护送/防守） | 1～2人；120～255级；任务6410相关；6分钟 | [`DelliDefenseRules`](RULES.md#delli-rescue) | [#51](https://github.com/Diluka/SoloMapling/issues/51) |
| 蘑菇城总理（剧情Boss，可单人/组队） | 1～3人；30～255级；蘑菇城剧情任务状态／道具门槛；10分钟 | [`MushroomPrimeMinisterRules`](RULES.md#mushroom-prime-minister) | [#52](https://github.com/Diluka/SoloMapling/issues/52) |
| 弓箭手四转技能异世界任务（狙击／集中） | 至少1名合格队员；120级以上；职业312/322/GM；发起者任务6107或6108 | [`ArcherSkillQuestRules`](RULES.md#s4a-world) | [#53](https://github.com/Diluka/SoloMapling/issues/53) |

### 远征Boss

| 任务家族 | 当前脚本门槛摘要 | 规则设计 | 实现与验收 |
|---|---|---|---|
| 扎昆远征 | 远征6～30人；50～255级；120分钟；召唤物与前置沿原生规则 | [`ZakumRaidRules`](RULES.md#zakum-raid) | [#42](https://github.com/Diluka/SoloMapling/issues/42) |
| 黑龙远征 | 远征6～30人；100～255级；120分钟；前置沿原生规则 | [`HorntailRaidRules`](RULES.md#horntail-raid) | [#43](https://github.com/Diluka/SoloMapling/issues/43) |
| 品克缤远征 | 远征6～30人；120～255级；140分钟 | [`PinkBeanRaidRules`](RULES.md#pink-bean-raid) | [#44](https://github.com/Diluka/SoloMapling/issues/44) |
| 暴力熊与心疤狮王远征 | 远征6～30人；100～255级；60分钟 | [`ScargaRaidRules`](RULES.md#scarga-raid) | [#45](https://github.com/Diluka/SoloMapling/issues/45) |
| 昭和黑帮远征（The Boss） | 远征3～30人；100～255级；60分钟 | [`ShowaRaidRules`](RULES.md#showa-raid) | [#46](https://github.com/Diluka/SoloMapling/issues/46) |
| 巴洛古远征 | 普通远征6～30人；50～255级；Easy声明3～30人但入口未定位 | [`BalrogRaidRules`](RULES.md#balrog-raid) | [#47](https://github.com/Diluka/SoloMapling/issues/47) |

## 不列为合作PQ的内容

以下事件仍保留在全量索引中，并写明排除原因；野外Boss可以由通用狩猎活动处理，个人剧情和交通也可在对应活动系统扩展，不按事件文件数机械新增PQ机器人。

| 分类家族 | 事件key | 原因 |
|---|---|---|
| 失控的摩诃（战神转职剧情） | `MahaBattle` | 单人剧情实例，入口按单个player启动。 |
| 帕比国王与雪人（蘑菇城单人剧情） | `KingPepeAndYetis` | 单人剧情实例，入口按单个player启动。 |
| 人偶师（单人剧情实例） | `Puppeteer` | 单人剧情实例，入口按单个player启动。 |
| 野外区域Boss刷新事件 | `AreaBossBamboo`, `AreaBossCentipede`, `AreaBossDeo`, `AreaBossDoor1`, `AreaBossDoor2`, `AreaBossDoor3`, `AreaBossDoor4`, `AreaBossDoor5`, `AreaBossDoor6`, `AreaBossDyle`, `AreaBossEliza1`, `AreaBossFaust1`, `AreaBossFaust2`, `AreaBossKimera`, `AreaBossKingClang`, `AreaBossKingSageCat`, `AreaBossLeviathan`, `AreaBossMano`, `AreaBossNineTailedFox`, `AreaBossSeruf`, `AreaBossSnackBar`, `AreaBossStumpy`, `AreaBossTaeRoon`, `AreaBossTimer1`, `AreaBossTimer2`, `AreaBossTimer3`, `AreaBossZeno` | 野外Boss定时刷新，不是队伍报名/关卡实例。 |
| 废都广场摇滚之魂任务／VIP区域 | `RockSpirit`, `RockSpiritVIP` | 个人任务实例，按单个player入场。 |
| 九灵之巢龙蛋任务实例 | `NineSpirit` | 个人任务奖励场景，不是合作PQ。 |
| 玩具城零件收集任务3239实例 | `q3239` | 个人任务实例，按单个player入场。 |
| 崔斯坦之墓任务2245 | `BalrogQuest` | 个人剧情Boss任务。 |
| 五职业三转分身试炼 | `3rdJob_bowman`, `3rdJob_magician`, `3rdJob_pirate`, `3rdJob_thief`, `3rdJob_warrior` | 个人职业晋升试炼。 |
| 骑宠保护猪猪试炼 | `3rdJob_mount` | 个人骑宠试炼。 |
| 海盗四转技能试炼（三种） | `4jaerial`, `4jship`, `4jsuper` | 个人技能试炼。 |
| 战神二/三阶骑宠试炼 | `Aran_2ndmount`, `Aran_3rdmount` | 个人骑宠试炼。 |
| 骑士团魔法图书馆剧情 | `Cygnus_Magic_Library` | 单人剧情实例，入口按单个player启动。 |
| 玩偶之家（任务3230） | `DollHouse` | 个人解谜任务。 |
| 教堂/大教堂婚礼典礼 | `WeddingCathedral`, `WeddingChapel` | 婚礼典礼，不是APQ结婚组队任务。 |
| 双倍经验世界活动 | `2xEvent` | 全服倍率定时活动。 |
| 事件示例与缺失事件回退 | `0_EXAMPLE` | 缺失事件回退模板，不是可报名任务。 |
| 魔法密林—天空之城渡轮 | `Boats` | 魔法密林↔天空之城渡轮定时发车/到达及途中蝙蝠魔，非组队关卡/奖励任务。 |
| 天空之城—玩具城列车 | `Trains` | 天空之城↔玩具城定时列车，非组队关卡/奖励任务。 |
| 新加坡飞机 | `AirPlane` | 废弃都市↔新加坡飞机，非组队关卡/奖励任务。 |
| 爱奥斯塔升降机 | `Elevator` | 爱奥斯塔升降机，非组队关卡/奖励任务。 |
| 神木村渡船 | `Cabin` | 天空之城↔神木村渡船，非组队关卡/奖励任务。 |
| 阿里安特精灵交通 | `Genie` | 天空之城↔阿里安特飞船，非组队关卡/奖励任务。 |
| 武陵仙鹤 | `Hak` | 天空之城↔武陵白鹤个人交通实例，非组队关卡/奖励任务。 |
| transport-kerningtrain | `KerningTrain` | 废弃都市↔地铁站个人交通实例，非组队关卡/奖励任务。 |
| 废都—新叶城地铁 | `Subway` | 废弃都市↔新叶城地铁定时交通，非组队关卡/奖励任务。 |
| 未来之城守护者尼克斯个人试炼 | `GuardianNex` | 虽然事件复用了team清理方法且minPlayers=1，实际入口明确传入单个player，没有组队协作准入，排除标准组队任务。 |
| 解救嘎嘎个人飞船跳跃活动 | `RescueGaga` | 个人飞船/跳跃活动，不是多人PQ；NPC的太空矿场选项明确尚未编码，不另立组队规则。 |

另有金庙/Ravana未找到本版本入口、混沌扎昆/黑龙仅发现远征枚举、巴洛古Easy没有定位到入口。清单保留“未证实”状态，不从其他版本攻略补成已存在内容。详见 `catalog.json.unprovenContent`。

## 推进与验证

先完成协作内核及原生动作链，再用月妙验证“分区收集—定点播种—持续守护—交付”，用废都和LPQ验证组合反馈与同时站位；随后按各家族的专用规则独立推进。GPQ的低级专员、CWK的五职业、APQ的婚姻条件不能靠会长±10的核心小队自动满足，需合法专员或清楚报告缺口。

每个Issue都分别检查规则实现、协作与失败测试、真实后端集成、原生收益/退出和真人客户端通关；参数变体分别留证。来源缺口先隔离核实，必要适配只在Solo维护，北斗子模块一个字符不改。不开启人数/等级/跳关开关来代替通关，也不伪造物品或直接改清关属性。

可运行以下只读结构验证：

```bash
python3 docs/party-quests/validate.py
```

验证检查固定版本、110事件覆盖、家族去重、分工设计、Issue链接、源码文件及行号、本地文档链接。它不会启动游戏、连接数据库或证明任务可通关。
