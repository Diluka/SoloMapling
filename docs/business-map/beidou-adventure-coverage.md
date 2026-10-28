# 北斗战斗、任务与挑战的覆盖依据

本记录对应 `combat.yaml` 与 `quests.yaml`。证据为只读子模块北斗 `1.12`、提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b` 的当前源码；未运行游戏、修改数据库或改变上游文件。以下路径省略共同前缀 `upstream/BeiDou-Server/gms-server/`。

## 功能责任与边界

| 责任 | 受益人与承诺 | 规则和状态 | 支持的生命周期 | 协作者和边界 |
| --- | --- | --- | --- | --- |
| `beidou.combat` 战斗与角色成长 | 玩家与调用原生引擎的上层功能可定位攻击、技能、击杀收益、成长和死亡复活规则 | 地图怪物生命/状态/伤害贡献；角色经验、等级、职业、AP/SP、技能、当前生命状态与图鉴成长 | 攻击/施法资格检查 → 应用效果/伤害 → 存活或击杀结算；经验与升级/加点/转职；受伤死亡 → 原生复活；持续效果建立与解除；图鉴卡获得与数量上限 | 当前场景由世界域拥有；角色主记录及最终存档由身份域拥有；掉落物品与消耗资格由经济物品路径维护；击杀可以推进任务域进度。此域不等于 Solo 或 MCP 已开放攻击能力。 |
| `beidou.quests` 任务、NPC与挑战 | 玩家可按具体任务和脚本规则接取、推进、交付或放弃任务，与当前NPC会话交互并参加挑战及桌游对局 | 任务状态/需求/动作/进度；CM/QM与脚本上下文；具体挑战资格、阶段、结束与奖励条件；独立桌游房间、棋盘/牌组与胜负战绩 | 接取或拒绝 → 进行中 → 完成/放弃/重复接取；NPC会话建立 → 回复/返回/取消 → 清理；挑战准入 → 实例参与 → 通关/超时/成员不足等结束 → 独立奖励领取；桌游建房/加入 → 对局 → 胜负/认输/和棋 → 留房或退出/关闭 | 世界域事件接口负责实例和地图运行基础；战斗域提供击杀、经验与死亡事件；身份域负责存档。MCP的已审路径和对话版本限制属于Solo验收域，不能套作北斗全局规则。 |

战斗与任务可以分别改变伤害/成长规则和任务资格/剧情规则，故保留两个领域；不会把任务进度归给触发击杀的战斗调用方。NPC/QM、任务开始/完成及挑战规则放在同一任务域，并以能力和场景区分，避免按每个脚本文件拆域。事件实例底座保持在世界域，规则脚本引用该接口，不重新定义第二套实例机制。OMOK/MATCH_CARD由独立MiniGame房间承载，不把它们称为EIM事件，也不因共用PlayerInteractionHandler就归入交易经济域。

## 从源码入口和状态拥有者到地图的覆盖

| 独立发现的场景或生命周期 | 决定性源码 | 地图概念或流程 |
| --- | --- | --- |
| 近战、远程、魔法与召唤物攻击 | `src/main/java/org/gms/net/server/channel/handlers/CloseRangeDamageHandler.java#handlePacket`、`RangedAttackHandler.java#handlePacket`、`MagicDamageHandler.java#handlePacket`、`SummonDamageHandler.java#handlePacket`；`AbstractDealDamageHandler.java#applyAttack` | `beidou.combat.attack`、`native-rules`、`combat-entry`；`attack.flow` 保留拒绝、未击杀与击杀分支 |
| 技能等级不符、冷却、存活和施法消耗 | `src/main/java/org/gms/net/server/channel/handlers/SpecialMoveHandler.java#handlePacket`；`server/StatEffect.java#applyTo` | `cast-skill`、`native-rules`；不宣称所有技能消耗与效果具有整体回滚 |
| 怪物生命、仇恨、技能占用与异常 | `src/main/java/org/gms/server/life/Monster.java#damage`、`#canUseSkill`、`#aggroMonsterDamage`、`#applyStatus` | `monsters`、`monster-skills`、`manage-status` |
| 怪物移除、个人/同图队伍经验和掉落分支 | `src/main/java/org/gms/server/maps/MapleMap.java#damageMonster`、`#killMonster`；`server/life/Monster.java#killBy`、`#distributePartyExperience`、`#giveExpToCharacter` | `settle-kill`；调用 `gain-level` 并写 `beidou.quests.progress` |
| 经验开关、等级上限、升级保护与奖励属性 | `src/main/java/org/gms/client/Character.java#gainExpInternal`、`#levelUp` | `gain-level`、`growth` |
| 手动/自动AP与职业技能SP分配 | `src/main/java/org/gms/client/processor/stat/AssignAPProcessor.java#APAssignAction`、`#APAutoAssignAction`；`AssignSPProcessor.java#canSPAssign`、`#SPAssignAction` | `allocate-growth`、`native-rules` |
| 转职职业状态、AP/SP、背包和生命变化 | `src/main/java/org/gms/client/Character.java#changeJob` | `change-job`；准入由调用的任务/NPC等规则决定，方法本身不代表无条件转职入口 |
| 受伤来源、防护与生命转零 | `src/main/java/org/gms/net/server/channel/handlers/TakeDamageHandler.java#handlePacket`；`client/Character.java#hpChangeAction`、`#playerDead` | `take-damage`；包含嘉年华CP、安全护符、地图经验保护与事件死亡回调 |
| 命运之轮、事件接管或返回地图复活 | `src/main/java/org/gms/net/server/channel/handlers/ChangeMapHandler.java#handlePacket` 的死亡分支 | `recover-life`、`recover-life.flow`；世界地图归属不转移给复活场景 |
| 角色增益建立、覆盖/取消与异常解除 | `src/main/java/org/gms/client/Character.java#registerEffect`、`#cancelEffect`、`#giveDebuff`、`#dispelDebuffs` | `manage-status` |
| 怪物卡拾取即用、单卡最多5张及独特卡推动图鉴等级 | `src/main/java/org/gms/client/Character.java#applyConsumeOnPickup`；`client/MonsterBook.java#addCard`、`#calculateLevel` | `monster-book`；图鉴随角色存档，拾取资格仍属于物品路径 |
| 任务接取、脚本开始和地图/NPC距离拒绝 | `src/main/java/org/gms/net/server/channel/handlers/QuestActionHandler.java#handlePacket`、`#isNpcNearby`；`server/quest/Quest.java#canStart`、`#start` | `beidou.quests.start`、`eligibility`、`scripted-quest`；`start.flow` 描述普通接取的资格和动作拒绝 |
| 任务交付、选择奖励与完成动作 | `src/main/java/org/gms/server/quest/Quest.java#canComplete`、`#complete`；`server/quest/actions/ExpAction.java#run` | `complete`、`progress`；完成检查与奖励动作顺序在摘要中明确，未虚构跨动作事务 |
| 放弃、计时清除、次数及重复接取 | `src/main/java/org/gms/server/quest/Quest.java#forfeit`、`#canStartQuestByStatus` | `forfeit`、`quest-lifecycle` |
| 击杀任务与专门勋章计数 | `src/main/java/org/gms/client/Character.java#raiseQuestMobCount`；`server/quest/medal/VeteranHunterMedal.java#onMonsterKilled`；`SpecialChallengeMedal.java#onMonsterKilled` | `progress`、`quest-lifecycle`；勋章仍沿已接任务及对应等级/首领等条件推进 |
| 地图NPC及指定脚本入口、点击间隔、CM建立和清理 | `src/main/java/org/gms/net/server/channel/handlers/NPCTalkHandler.java#handlePacket`；`scripting/npc/NPCScriptManager.java#start`、`#dispose` | `talk`、`dialogue-context`；脚本中心不是远距离点击地图NPC的同义入口 |
| QM任务脚本开始/结束与会话路由 | `src/main/java/org/gms/scripting/quest/QuestScriptManager.java#start`、`#end`；`net/server/channel/handlers/NPCMoreTalkHandler.java#handlePacket` | `scripted-quest`、`npc-dialogue`、`dialogue-context` |
| legacy和NextLevel回复、选择、返回、取消 | `src/main/java/org/gms/net/server/channel/handlers/NPCMoreTalkHandler.java#cmRouting`；`scripting/npc/NPCScriptManager.java#action`、`#nextLevel` | `reply`、`npc-dialogue`；`talk.flow` 限定为非文本回复路由，文本输入保留为节点证据中的单独原生分支 |
| 组队任务招募、同图、等级、人数、队长和配置例外 | `scripts-zh-CN/npc/9020000.js#action`；`scripts-zh-CN/event/KerningPQ.js#getEligibleParty` | `party-challenge`、`challenge-rules`；`party-challenge.flow` 记录本例准入与大厅拒绝 |
| 挑战阶段、通关、超时、离队/解散及收尾 | `scripts-zh-CN/event/KerningPQ.js#clearPQ`、`#scheduledTimeout`、`#changedMap`、`#leftParty`、`#disbandParty`、`#end` | `party-challenge`；世界事件基础通过 `consumes beidou.world.events` 表达 |
| 事件纯经验、随机物品与奖励格位不足 | `src/main/java/org/gms/scripting/event/EventInstanceManager.java#giveEventReward`、`#hasRewardSlot` | `challenge-reward`；通关事实和实际物品领取分开 |
| 非脚本目录中的比赛与挑战 | `src/main/java/org/gms/server/partyquest/MonsterCarnival.java#complete`、`AriantColiseum.java#distributeAriantPoints`、`Pyramid.java#startGaugeSchedule` | `competitive-challenges`；按CP、积分/公平性、能量条分别定位，没有把它们漏作普通事件框架细节 |

| 原生五子棋、翻牌建房和访客准入 | `src/main/java/org/gms/net/server/channel/handlers/PlayerInteractionHandler.java#handlePacket` 的CREATE 1/2与VISIT分支；`server/maps/MiniGame.java#checkPassword`、`#hasFreeSlot`、`#addVisitor` | `table-games`、`play-table-game`、`table-game-state`；建房核存活/房间资格/持有道具，加入核密码与空位 |
| 桌游落子、翻牌配对、认输/和棋、胜负和退出 | `src/main/java/org/gms/server/maps/MiniGame.java#setPiece`、`#setOwnerPoints`、`#minigameMatchOwnerWins`、`#minigameMatchVisitorWins`、`#minigameMatchDraw`、`#removeVisitor`、`#closeRoom`；`client/Character.java#setMiniGamePoints`、`#closeMiniGame` | `play-table-game`、`table-game-state`；游客进行中退出判房主胜，房主退出只按源码关闭房间，不泛称所有离场均判负；胜负/平由角色保存 |
| 任务物品与金币、事件物品和成员金币 | `src/main/java/org/gms/server/quest/actions/ItemAction.java#check`、`#run`；`MesoAction.java#runAction`；`scripting/event/EventInstanceManager.java#giveEventReward`、`#giveEventPlayersMeso` | `start`、`complete`、`challenge-reward` 与经济域 `inventory/wallet` 读写关系；不是所有任务或通关都必有金币/物品 |

## 已确认关系与未展开的边界

- `attack → settle-kill → gain-level` 是原生伤害到成长的协作；`settle-kill → writes quests.progress` 由 `Monster.giveExpToCharacter` 调 `raiseQuestMobCount` 及勋章计数证实。
- 任务与事件的经验奖励调用 `Character.gainExp`，因此引用战斗成长域的同一操作，不创建第二个角色经验数据概念。
- 任务进度和对话上下文在任务域各定义一次；角色主记录、地图场景、事件实例接口引用对应拥有者，不复制到本域。
- 图鉴的独特卡和单卡数量属于成长结果，拾取、掉落产权、背包和道具交易的完整规则留在经济物品域；可以由经济域的拾取操作调用 `beidou.combat.monster-book`。
- 已补充攻击弹药/金币消耗、施法道具、安全护符和命运之轮消耗对 `beidou.economy.inventory/wallet` 的读写；对应证据为 `RangedAttackHandler.handlePacket`、`StatEffect.applyTo`、`Character.playerDead` 和 `ChangeMapHandler.handlePacket`。技能4111004按源码金币规则消耗，不把金币消耗泛化到所有施法。
- 已补充任务物品/金币及事件奖励对经济域的读写；`MesoAction`的扣款动作不等于已经证实余额预检查或跨任务动作原子回滚。
- 婚礼脚本属于社交婚姻结果的建立过程：`scripts-zh-CN/event/WeddingCathedral.js#setup`与`WeddingChapel.js#setup`调用`em.newMarriage`并维护weddingStage、誓言和礼物等状态，故归现有`beidou.social.marriage`协作。现有婚姻能力已经消费`beidou.world.events`，无需在任务域再建第二个婚礼拥有者。

## 证据限制与停止点

本图覆盖源码可确认的重要生命周期与功能入口，而不是穷举全部职业技能、任务ID、NPC菜单和事件阶段。WZ数据、运行时语言选择及每个脚本的实际执行效果需要对应资源和场景验收；KerningPQ的等级、人数、阶段和计时不能推广为所有挑战的通用规则。Java机制、脚本定义和源码锚点可定位不等于这些玩法已经实测，更不等于Solo的组队通关机器人或MCP攻击/移动能力已经交付。

现有两域的主要责任、退出和拒绝路径已有归属；未独立建模的具体技能/剧情/挑战家族保留在对应能力目录锚点下。跨域接口、掉落与物品规则和原生存档由其拥有域进一步展开，不因此转移所有权。没有为描述地图访问真实数据库或宣称当前部署包含源码工作区的全部行为。
