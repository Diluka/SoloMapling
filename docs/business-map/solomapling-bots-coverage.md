# SoloMapling 机器人功能覆盖依据

本轮按当前工作区源码建图；以北斗集成 `build.py` 的 `compile_and_weave` 为构建范围：`src/main/java/soloMapling`、集成 Java 和 AspectJ。根目录继承的 Cosmic 引擎、旧 SQL 与旧说明不因存在于仓库就成为北斗能力。本文件是源码到地图的覆盖账，不是部署验收报告。

## 环境人口

受益人为玩家与本地运营者，结果是按计划把运行期机器人放入实际世界。所有者负责人口来源、批次、城镇分布、精确对象登记和移除，不拥有普通玩家账号、MCP 原生角色、交易规则或各行为模块规则。`PopulationPlan` 属于本域；原生模板角色和地图仍归北斗。

| 独立发现的责任/入口 | 源码依据 | 映射 |
| --- | --- | --- |
| 显式配置、工作目录、资源包与内置回退；显式配置错误不得改变人口 | `EnvironmentPopulationConfig.load/openReader`、`PopulationConfigurationTest` | `solo.population.load-plan/plan` |
| 开服读取启动开关，九类批次及缩放、市场/训练/城镇配置 | `BeiDouRuntime.onServerReady`、`EnvironmentManager.environmentLoadStartup` | `solo.population.initialize` 及对应 flow |
| 加载唯一既有模板，注册后进入地图与设置外观 | `BotGeneration.templateCharacterId/loadTemplate/createBot` | `solo.population.create-bot/template-rule/appearance` |
| 分配 ID 避让持久角色/在线角色；isBot 识别 BotClient 或精确登记对象，isCurrent 另核登记和频道存储同一对象 | `BotRegistry.allocateId/register/isCurrent` | `solo.population.bots/identity-rule/bot-source` |
| 城镇社交、闲逛、训练人口按位置生成 | `EnvironmentManager.spawnTown/spawnTownCohort` | `solo.population.town-presence` |
| 停止延迟、社交、移动等子系统，等宏回调后移除运行角色 | `BeiDouRuntime.onServerStopping`、`EnvironmentManager.stop`、`BotGeneration.removeBotFromServer` | `solo.population.stop-world/remove-bot` |

生命周期为读取计划、生成/注册、放入地图、运行与移除。角色类型和小游戏由各自业务域决定；批次仅负责调用。原生模板只读加载，空库自动准备不在当前实现范围。创建累计数包括已安排后的生成数，不能当在线人数。停服等待宏回调失败时源码记录 ERROR 并继续原生保存关闭，地图未把该路径宣称为完整排空。当前没有读取正式数据库或操作游戏角色，历史本地人口值不作为通用规则。

## 机器人社交

受益人为与机器人同图聊天的玩家和观察环境闲聊的玩家。所有者负责点名/续聊分流、独占交互者、打扰降级、会话轮次、静态话术、可选模型、超时和结束；北斗拥有普通聊天接入规则、地图成员，外部模型拥有回答生成。

| 独立发现的责任/入口 | 源码依据 | 映射 |
| --- | --- | --- |
| 只观察原生成功广播后的真人普通聊天，按同图名字/会话路由 | `GameplayHooks`、`Dispatcher.processMessages/checkIfCharacterOnMap` | `solo.social.route-chat`，消费 `beidou.social.chat` |
| 新玩家点名、忙碌拒绝、重复打扰降级 | `Dispatcher.handleSocialBotSession`、`SocialBot.onFirstInteraction` | `solo.social.start-conversation/conversation` |
| 已知选择、自由文本、告别和组队转交 | `SocialBot.handleDialogueChoice` | `solo.social.choose-reply/end-conversation`；组队行为由伙伴域拥有 |
| YAML 类别、上下文解析、语言和节点回退 | `SocialBot.respondWithYamlCategory`、`BotDialogueHandler.readDialogueYaml/getRandomResolvedLine`、`SoloMaplingLanguageConfig.configure`、`LocalizedResources.resolve` | `solo.social.yaml-reply/dialogue-packs/language-rule` |
| 模型配置有效才调用，token/历史/超时，异常/空回复回退 | `SocialLlmConfig`、`SocialLlmService.completeAsync`、`DeepSeekLlmClient.complete` | `solo.social.llm-reply/model-provider`、回复 flow |
| 对话结束/换人使旧结果失效；模型历史按 bot/player 清理 | `SocialBot.isCurrentLlmTurn/resetConversation/checkConversationTimeout`、`SocialChatSessionStore` | `solo.social.current-turn-rule/end-conversation/conversation` |
| 玩家在场、地图冷却、空闲 SocialBot 群组播放 | `ConversationManager.tick/findClusterOnMap` | `solo.social.ambient-chat` |
| V1 文案与表情广播 | `SocialCommands.BotSpeak/BotFullChat` | `solo.social.broadcast` |

生命周期为点名、接纳或拒绝、选择/自由文本、回复/回退、续聊、告别/超时/停服。模型上下文只在内存保留，`SocialLlmService.shutdown` 全局清空模型历史；单独 `SocialBot.stopScheduledTask` 不调用 `resetConversation`，不能推断交互者及会话修订同时重置。`SocialCommands.BotFullChat` 仍直接构造 CHATTEXT 并广播到地图，与 MCP/公共原生动作服务的 lane/取证边界不同；不能写成所有旧 V1 机器人动作已经迁入统一运行体。语言资源也供市场复用，定义只保留一次，通过关系引用。

现有测试源码包括 `SocialLlmServiceTest`、`SocialChatSessionStoreTest`、`SocialLlmConfigTest`、`LocalizedDialogueTest`、`AmbientDialogueLanguageTest`，这里仅把它们作为证据入口，本轮没有运行游戏或调用模型。仓库 README 明确桩链路已验，不代表真实模型、真实客户端聊天或完整 MCP SocialBot 交互已完成。对话回复 flow 覆盖组队转交、告别结束、静态/模型选择、失败回退和迟到丢弃；招募后的实际邀请与入队由伙伴域流程解释。


---

# 伙伴协作与训练：源码覆盖依据

本文件记录地图的业务边界与源码取证，不声明当前服务已部署这些源码，也不把静态调用链当作客户端玩法验收。构建入口 `beidou-integration/scripts/build.py:compile_and_weave` 编译 `src/main/java/soloMapling` 与集成适配；本域没有使用保留历史的 Cosmic 引擎。

| 责任 | 本域承担的结果与边界 |
|---|---|
| 受益人 | 需要环境机器人同队、跟随或在同图训练的真人；需要分布式训练人口的环境维护方。 |
| 状态与规则 | 本进程的招募意愿、拒绝冷却、待领队/驻点交接，及训练阶段、地图预约和行为资源；不拥有北斗账号、队伍成员和持久角色数据的原生规则。 |
| 生命周期 | 社交/训练对话请求 → 同意或拒绝 → 实际邀请 → 原生入队；训练与跟随相互转换；退队、失联结束跟随；停用释放旧训练行为。 |
| 内聚边界 | SocialBot、TrainingBot、FollowerBot 是同一环境角色可转换的协作状态。招募、跟随与驻点共同决定该协作生命周期，故保留为一个域；静态语言/LLM回复、人口创建与市场交易分别由其他域所有。 |
| 重要区别 | V1 的机器人训练采用自己的职业攻击配置和直接伤害适配；无人观察时直接修改本对象等级/经验。不等同于 V2 统一运行体的生产自治模块，也不承诺持久伙伴成长。 |
| 上游协作 | 原生 `InviteCoordinator`、`Party.joinParty` 决定队伍结果；频道角色存储解析当前领队；地图、怪物、等级经验表和角色对象来自北斗。 |

| 独立发现的入口或生命周期责任 | 当前源码证据 | 映射 |
|---|---|---|
| 普通对话请求协作，检查等级、冷却、适用跟随容量和概率 | `BotRecruitManager.rollPartyAsk`、`TrainingBot.optPartyAsk` | `solo.companions.recruit`、`.assess-recruit`、`.recruitment-rules`；招募 flow 的同意/拒绝分支 |
| 同意后等待正确玩家的真实邀请，原生答复与加入分别可能失败 | `BotRecruitManager.pollInvites`、`BotPartyCommands.botAcceptPartyInvite` | `.join-party`、招募 flow 的非匹配/成功/失败分支 |
| 跟随期间按领队 ID 重新解析对象、交易暂停、正常邀请答复 | `FollowerBot.updateState`、`doFollow`、`pollLeaderInvite` | `.follow`、`.recruitment-state` |
| 同图跟随及跨图追赶，目标再次换图时改道 | `GCFollow.tick`、`GCMovement.travel` | `.follow`、`.navigate` |
| 当前领队才能请求驻点，且地图必须有怪 | `FollowerBot.onMenuSelect`、`TrainingBot.doDecide` | `.station` |
| 领队重连、失联宽限、退队或解散后回到环境活动 | `FollowerBot.doLeaderLost`、`fallbackConvert` | `.end-follow` |
| 驻点或真人队伍目标优先；否则按等级、可达性和占用预约选图 | `TrainingBot.doDecide`、`TrainingMapChooser.choose` | `.train`、`.choose-training-map`、`.training-state` |
| 无目标留城等待，行进失败或长期无进展取消并重新决策 | `TrainingBot.doDecide`、`doTravel` | 训练 flow 的无图/行进失败分支 |
| 可见训练按职业、武器、目标距离与普通冷却执行攻击 | `TrainingBot.combatTick`、`BotAttackDriver.botAttack`、`BotAttackEffects.broadcastAndApply` | `.attack`；训练 flow 的可见分支 |
| 无人观察时按时间与怪物等级直接更新机器人等级经验 | `TrainingBot.doGrind`、`accrueAbstractExp` | `.abstract-growth`；训练 flow 的不可见分支 |
| 脚边/行进范围掉落按归属与开放时间筛选，然后从地图移除 | `GrindLoot.grabLootAtFeet`、`DropCommands.botCanLoot`、`botLootSingleDrop` | `.remove-loot`；明确不等于入背包 |
| 训练休息、城镇活动、桑拿与继续训练 | `TrainingBot.updateState`、`doBreakRest`、`startSaunaTrip` | 作为 `.train` 的阶段，不把每个内部状态独立成域 |
| 类型转换或停止后释放预约、攻击、增益和移动状态，取消桑拿回调 | `TrainingBot.stopScheduledTask`；`TrainingBotSaunaLifecycleTest` 验证迟到回调 | `.stop-training` |
| 旧 OPQ 分配云/唱片目标并完成局部动作 | `OPQBot`、`OPQOrchestrator.assignCloudReactor`；`docs/party-quests/OPQ-AUDIT.md` | `.opq-prototype`，明确标注两段原型，不提供通关 flow |

现有源码的两处边界不能被地图抹平：`BotRecruitManager.isArmed` 的有限窗口用于行为节奏，但 `pollInvites` 接受依据是同意邀请者匹配，过期由原生邀请协调器裁决；`DropCommands.botLootSingleDrop` 仅调用地图移除，不执行背包获得。地图保留这些差异，不将它们包装成已保证的通用邀请或拾取合同。

旧 OPQ 只覆盖云与唱片两段。全局单例协调器、直接机关/物品路径及事件注册差异已在 `docs/party-quests/OPQ-AUDIT.md` 记录。通用 `PartyQuestBot`、各任务规则、运行实例协调和完整通关仍是设计与后续工作；本域仅保留原型导航入口，未将计划创建为可执行操作。

本次仅进行了源码与既有测试代码核对，没有运行游戏、数据库写入、角色存档验证或客户端通关。本域不存在足够证据可标记持久伙伴、自动转职/装备赠送或统一运行体生产自治已完成。


---

# Solo 市场域覆盖依据

本地图记录当前工作区源码责任，未执行数据库操作、服务启动或真实客户端玩法验收。业务域为 `solo.market`，归档文件为 `market.yaml`。

## 责任与边界

| 项目 | 当前源码支持的业务含义 |
| --- | --- |
| 结果与受益人 | 为玩家提供人工货架、地图商人、逛店买家及面对面买卖互动；为运营人口生成提供市场活动。 |
| 自有状态 | Solo 商品池、基础估值、房间与日期调价规则、人工商品可变报价、机器人广告货品和求购条件、议价会话。 |
| 自有决策 | 货源选择、商店组合、价格和数量、逛店购买意愿、买卖报价容忍、是否确认交易。 |
| 生命周期 | 铺摊/生成货架→访问或报价→原生购买或双边交易→成功/取消回调→清理意图；议价实现另含过期、次数拒绝和延迟改价。 |
| 上游协作 | 实际角色、地图、商店、玩家背包/钱包、交易锁定及最终结算归北斗；Solo 生成机器人供给并可补足登记机器人资金，不能表述为玩家资产来源不变。 |
| 非同一责任 | 人口批次和机器人身份归人口域；聊天文案和多语言归社交域；原生经济状态只引用 `beidou.economy`，不在本文件重复定义。 |

## 独立源码入口与映射覆盖

| 场景或生命周期责任 | 决定性源码 | 映射 |
| --- | --- | --- |
| 市场房间摊位选择、异步生成、随机跳位 | `ArtificialFreeMarket.populateFreeMarketRoom` | `solo.market.populate` |
| 档次/职业/商品类别货源、热门房间补货、折扣 | `ArtificialShopGenerator.generateShop`、`generateSecondaryShop`；`ArtificialFreeMarket.generateHiredMerchantShopData` | `solo.market.generate-stock` |
| 商品池按版本/档次选择 | `ItemSelector.getRandomItemFull`、`itemPool/itemConfig/` | `solo.market.catalog` |
| 市场、星期、房间和门口溢价，人工报价 | `FMEconomyManager.priceAdjustmentRules`；`ArtificialShopGenerator.addItemToShop` | `solo.market.price-stock`、`solo.market.quotes` |
| 原生角色商店开放、货架初始化上限 | `ArtificialFreeMarket.BotPlayerStorePermit`；`EngineCompatibility.aj` 中 `PlayerShop.setItems` | `solo.market.open-store` |
| FMBot 根据价格估值比选购，离店后继续逛房间 | `FMBot.purchaseShopItems`、`shouldPurchaseItem`、`exitShop`、`exitRoom`；两种 ShopAdapter | `solo.market.browse-buy` |
| 收购/卖出机器人广告和业务状态 | `BuyingMerchantBot.updateState`、`SellingMerchantBot.updateState`、`BotTradeSM.update` | `solo.market.direct-trade`、`solo.market.sale-intent` |
| 广告库存实体化、原生托管、交易资金准备 | `BotTradeInventory.stockForTrade`；`BotTradeCommands.reserveItem`、`setMeso` | `solo.market.reserve-offer` |
| 锁定前再次核同一托管、报价符合，结算后才反馈成功 | `BotTradeSM.hasAdvertisedEscrow`、`confirmAcceptedTrade`；`TradeHooks.aj` | `solo.market.confirm-trade`、`solo.market.direct-trade.sell-flow` |
| 准备失败、换货失配、报价拒绝、超时和取消 | `BotTradeSM.update`、`abortSale`；原生 `Trade.cancelTrade` | `solo.market.abort-trade`、`solo.market.direct-trade.sell-flow` |
| 议价解析、店主在场/离线策略、还价与次数上限、延迟调价 | `ShopOfferSystem.onPlayerShopChat`、`onHiredMerchantChat`；`ShopOfferResponse`；`HaggleSession` | `solo.market.haggle`、`solo.market.haggle-sessions`、`solo.market.reprice`，但北斗入口未发现接线 |
| NX 商人在成功交易后邀请聊天并交付兑换码 | `NXMerchantBot.deliverNXCode`；`NXCodeManager.createCompleteNXCode` | `solo.market.nx-handoff`，仅源码路径，未验真实交付 |

## 拒绝、失败与证据边界

- `BotTradeSMFailureTest` 的源码覆盖禁售广告物品、原生卖方背包满、临确认托管被替换，以及原生取消退回买方金币且不重复退款；此处引用测试设计，没有在本次重新执行这些测试。
- `BotTradeCommands` 在登记机器人身份、交易未锁定、有效槽位、物品可交易/不可丢弃条件和背包容量下操作，接着才扣库存进入托管。登记机器人可以买入前补足资金；这些供给是游戏经济行为，不能混同真实玩家资产自然产生。
- `EngineCompatibility.aj` 的两种 `botBuy` 最终调用北斗 `buy`。FMBot 的叫买台词、选购决定和适配方法返回不构成真实购买成功证据。
- 已限定检索 `src/main/java/soloMapling`、`beidou-integration/src/main` 和北斗源码，没有发现外部调用 `ShopOfferSystem.onPlayerShopChat/onHiredMerchantChat`，北斗只读源码亦没有引用 Solo。议价保留为已有未确认接线的实现，不画可从真实玩家商店聊天触发的流程，不声称已上线。
- `ArtificialShopItem.reprice` 只支持人工商品。接受议价只修改标价，不预留玩家金币、不直接成交，也不证明独占购买或延迟任务已完成。
- `NXCodeManager` 对 `nxcode`、`nxcode_items` 分两次建记录，北斗 `CouponCodeHandler` 负责兑换。没有源码保证这两次写入具有统一事务，也未在本次验证邀请码交付、真实模型、客户端或兑换收益。
- `ArtificialFreeMarket.destroyAllShops` 有遗留“doesnt work”注释。未将人工市场完整卸载/所有店主清理画为已保证生命周期；实际世界停止与根资源关闭由人口/运行体覆盖，市场遗留关闭行为需独立验证。

## 跨域关系依据

- `solo.market.direct-trade -> consumes -> beidou.economy.trade-entry`：`BotTradeCommands` 调用 `Trade.startTrade/inviteTrade/visitTrade/completeTrade/declineTrade`；`BotTradeSM.abortSale` 调用 `Trade.cancelTrade`。
- `solo.market.reserve-offer -> writes -> beidou.economy.inventory/wallet`：`reserveItem` 沿原生 `InventoryManipulator` 扣减、托管拒绝返还；`setMeso` 沿原生钱包和 `Trade.setMesoBot` 托管。
- `solo.market.browse-buy -> writes -> beidou.economy.inventory/wallet`：两种 ShopAdapter 经 `EngineCompatibility.aj` 转到原生 `PlayerShop.buy/HiredMerchant.buy`，保留上游结算责任。
- 人口域可从调度市场的操作 `invokes -> solo.market.populate`；真实调用点见 `EnvironmentManager` 的市场批次配置，关系由来源拥有文件声明。
- 本域市场中文文案通过 `BotDialogueHandler`、`FMShopDescGen` 和本地化资源消费；社交域若提供统一文案接口，可由本域对应能力消费该接口，不另建语言配置所有者。


---

# 休闲游戏业务边界与源码覆盖

本次根据当前工作区源码建图，不连接游戏服务或数据库，不将源码可达路径当作真人玩法验收。北斗基线固定为 `upstream/BeiDou-Server` 的官方 1.12；继承的 Cosmic 引擎及其 Liquibase 迁移不属于北斗产物。

## 责任与边界

| 项目 | 当前证据支持的结论 |
| --- | --- |
| 受益人与结果 | 真人与环境机器人可进入休闲活动的源码入口；地图只承诺能定位规则、回合与副作用路径，不承诺所有北斗玩法已通过。 |
| 决策与状态 | 本域决定牌点、胜负、入桌容量、地面筹码判定、掉落游戏档位与奖池；持有内存牌桌、参与者和回合状态。 |
| 生命周期 | 二十一点加入、拒绝、下注、行动、判胜、收注/派彩尝试与新回合；掉落游戏付费准备、入队、限时掉落、离图/到时结束。 |
| 协作者 | `solo.population` 负责生成与种类选择；`beidou.economy.trade-entry` 负责实际原生交易；北斗地图拥有掉落/拾取，社交域拥有实际队伍，角色域拥有人气等角色字段。 |
| 领域独立性 | 活动拥有回合、参加资格、支付与奖励规则，能独立改变结果，故不归入普通聊天；物品作为活动副作用不把市场经营责任移入本域。 |

## 独立场景覆盖

| 源码场景或生命周期 | 地图节点/流程 | 决定性源码 |
| --- | --- | --- |
| 环境入口创建庄家、掉落机器人、迎宾及扭蛋表演 | `solo.recreation`，人口域持有生成关系 | `EnvironmentManager.spawnBlackjackTables` / `spawnDropGameBotPotionShop` / `spawnGameZoneHostBots` / `spawnGachaBotsHenesys`，`BotTypeManager.BotType` |
| 玩家 Join、重复/满桌拒绝、无注跳过及离图 | `solo.recreation.blackjack`、`solo.recreation.blackjack.flow` | `BlackjackDealerBot.processJoinInquiries` / `processAllPlayersBets`，`BlackjackTable.addPlayer` |
| 玩家 Hit/Stand、庄家补牌及牌点结果 | `solo.recreation.table`、`solo.recreation.blackjack-rules` | `BlackjackDealerBot.processBlackjackMessages` / `handleDealerTurn`，`BlackjackRules.determineOutcome` |
| 输注收取、中奖筹码投放与收回筹码的人气惩罚 | `solo.recreation.blackjack-settlement`、`solo.recreation.prop-ownership` | `BlackjackDealerBot.payWinner` / `lootLoserBets`，`DropCommands.botThrowItemToOwner`，`DropCompatibility.aj` |
| 邮票筹码的类型与参考价值 | `solo.recreation.chip-catalog` | `CasinoChipConfig`；`casino_chip_shop_setup.sql` 仅为继承参考，北斗构建不执行 Cosmic 迁移。 |
| Cho/Han 下注、无注重新等待、输赢与收回筹码 | `solo.recreation.dice` | `DiceBot.detectBet` / `processBet` / `handlePlayerStealsBets` |
| 精确付费、非服务玩家拒绝、取消与锁定超时 | `solo.recreation.drop-game`、`solo.recreation.drop-game.flow` | `DropGameBot.tradeInitialized` / `tradeValidate` / `tradeFinalize` |
| 奖池加载错误、档位权重、正常限时掉落 | `solo.recreation.loot-pools`、`solo.recreation.drop-game` | `DropGameLootPool.load` / `rollItem`，`DropGameBot.performDrop` |
| 原生入队确认、超时不退费、离图与到时清理 | `solo.recreation.drop-game.flow` | `DropGameBot.partySetup` / `partyWait` / `activeGame` / `endGameCleanup` / `forfeitAndReset` |
| 观众对掉落作出反应 | 作为 `solo.recreation.drop-game` 的表现细节，不另建独立域 | `DropGameSpectatorSystem.onItemDropped` |
| 有真人时扭蛋掉落表演与自身拾取 | `solo.recreation.gacha-display` | `GachaBot.runRoulette` / `pickupItem`；`processReward` 直接返回。 |
| 进入地图欢迎、饮品接受/拒绝/选品与超时 | `solo.recreation.hospitality` | `GameZoneHostBot.handleEvent` / `updateState` / `serveDrink` / `waitForResponse` |

## 明确未支持或未验证的部分

- 当前 `DropCompatibility` 的不过期掉落入口要求 `owner` 为已登记环境机器人。`BlackjackDealerBot.payWinner` 经 `botThrowItemToOwner` 使用中奖者作 `owner`，普通玩家因而会被拒绝。地图明确记录此限制，不能把真人中奖派彩画成已完成路径；本次不修复该行为。
- 筹码 NPC 可由环境生成，但北斗构建不自动执行 Cosmic 的筹码商店 SQL，也不修改 WZ。兑换商店与客户端道具呈现需独立确认，未宣称新库自动可用。
- `DropGameBot` 确认交易后按延时推进，没有在此证明真实交易成功；空奖池时“Refunding”文案后只清理重置，不能把它当退款完成证据。入队超时的源码明示金币放弃，地图保留这一分支。
- 奖励通过地图掉落，未证明玩家实际拾取；异步投放、地图容量/掉落限制、断线及世界停止后的清理仍需实物验收。源码中的状态标志和 Future 完成不自动证明收益到账或工作已排空。
- `GachaBot.processReward` 当前直接返回；这里只映射表演与自身拾取，不映射玩家付费抽奖或完整奖励处理。
- 本次未执行普通客户端牌局、骰子局、掉落游戏、饮品服务或筹码兑换，也未调用实际 LLM；不修改真实配置、数据或北斗 submodule。


## 教程与新玩家引导

该域独立拥有新玩家教学选择及本轮进度，受益人是进入范围的玩家；它既有教学文案，也有对真人权限、成长、财产的直接副作用，不能当纯介绍。`EnvironmentManager.spawnTutorialBot` 由 `environmentLoadStartup` 的 `essentials.enabled/tutorial` 控制，地图不推断正式部署已开启。

| 源码场景/副作用 | 决定性证据 | 映射 |
| --- | --- | --- |
| 发现近处尚未帮助玩家，参与/拒绝与超时 | `TutorialBot.waitForNewPlayer/updateState/waitForResponse` | `solo.onboarding.tutorial/state` 与 flow |
| Yes授予GM6、等级职业、AP/SP、HP/MP、两码字符串及两张NX记录写入尝试 | `TutorialBot.grantAdmin` | `solo.onboarding.grant`，写原生角色/成长/兑换码数据 |
| 10亿金币及药水/装备等赠礼交易 | `initiateGiftTrade/waitForTradeAccept/placeTradeItems` | `solo.onboarding.gift`，消费北斗交易入口 |
| 地面三帽选择，清理未选帽子 | `tutorial_2_drops/handleTutPickResponse/handleSelectedHat` | `solo.onboarding.choose-hat` |
| 读取玩家武器、查找附近同ID地面物品、移除、卷轴改造并重投 | `analyzePlayersWeapon/lootPlayersDroppedWeapon/scrollPlayersDroppedWeapon/returnUpgradedWeapon` | `solo.onboarding.upgrade-weapon` |
| 拒绝也送苹果、记录已帮助CID、机器人返回 | `send_off/send_off_parting_gift/returnOrigin` | `solo.onboarding.finish` |

生命周期为发现、参与或拒绝、独立授予选择、礼品交易、教学选择、可选武器处理、送别和重置。`grantAdmin` 方法内没有独立管理员门槛，不能称为安全隔离的开发功能；没有实际启用/授权与副作用后态证据，不声称北斗安全可用。这里只读源码，没有运行教程、生成兑换码或变更真人。交易为null时 `waitForTradeAccept` 在超时检查前返回；武器等待无图中保证的超时；选帽回答不证明原生拾取。武器匹配按位置和ID，不声明精确个人托管、原子升级或自动回滚。

补充休闲与市场覆盖：`HenesysJQBot.attemptJQ/recover/convertToHenesysBot` 对应 `solo.recreation.jump-display`，是环境跳跃表演；`ScrollingBot.setScrollingItems/setSellingItems` 纳入 `solo.market.generate-stock` 的锚点，装备先由人工池生成，成功/失败序列用于表现，随后进入既有面对面待售流程，不作为真人卷轴消耗强化。

教程兑换码补充：`NXCodeManager.createCompleteNXCode` 对 `nxcode` 与 `nxcode_items` 分两次连接写入，异常可返回-1或只留下首表，教程调用者没有检查；显示/记录两码字符串不证明两张有效码，先前真人权限与成长不自动回滚。
