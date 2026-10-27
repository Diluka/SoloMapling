# 每个任务家族的协作规则设计

本册与[协作内核](DESIGN.md)一起构成设计交付。规则名是待实现设计，不是已经存在的Java类；每个家族的实现与验收Issue保持开放。完整阶段数据、门槛、来源及变体见[catalog.json](catalog.json)。

阶段职责可以顺序兼任；同时发生的收集/守护、多个站位、职业机关必须安排不同的合格执行者。所有任务都需要共享目标、稳定分工、互斥预约、真实完成反馈和失败恢复，不能各bot独立随机跑图。

<a id="henesys-pq"></a>
## 月妙组队任务／迎月花山丘

规则：`HenesysPqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/16)。类型：经典合作PQ。

**入口与编制：** 3～6人；10～255级；10分钟。事件：`HenesysPQ`。

**协作分工：** 种子收集者、按颜色定点播种者、月妙守护者、年糕收集／队长提交者。

**机制人数/能力：** 未发现硬性同时站位；播种后守护与收年糕必须并行安排。

**专用规则：**

- 六色种子和moonflower1..6的物品/坐标绑定
- stage=6才进入保护月妙模式
- 友军生存优先于普通清怪
- 队长10年糕提交与可选5分钟奖励路线

**正常完成与收尾：** rewards：stageExpBase：1600；randomClearItemReward：当前HenesysPQ.setEventRewards中的物品奖励设置被注释，不能宣称必得帽子或随机物品。；hatExchange：入口NPC另有4001101×20兑换1002798，需正常背包容量和扣物品。；failureAndExit：超时未过关或月妙死亡结束实例并送910010300；队伍人数不足、队长离开活动地图、离队/掉线按event回调清理；退出NPC1012113从910010300送回100000200

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 当前没有专用bot动作实现；种子投放、保护友军和拾取入包是必需能力，不得直接写事件stage或发奖励来伪装通关。
- reactor掉落与怪物掉落还依赖原生已加载掉落数据；本轮核对了来源迁移记录，但未读取实际数据库。

**关键依据：** [HenesysPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HenesysPQ.js)；[HenesysPQ.js:155](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HenesysPQ.js#L155)；[HenesysPQ.js:250](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HenesysPQ.js#L250)；[HenesysPQ.js:259](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HenesysPQ.js#L259)；[HenesysPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HenesysPQ.js#L26)；[HenesysPQ.js:265](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HenesysPQ.js#L265)。完整引用保存在机器目录。

<a id="kerning-pq"></a>
## 废弃都市组队任务／第一伴奏

规则：`KerningPqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/17)。类型：经典合作PQ。

**入口与编制：** 3～6人；21～30级；30分钟（中文最多6人，英文最多4人）。事件：`KerningPQ`。

**协作分工：** 独立答题与收券者、通行证交接者、三个互斥站位者、NPC检查者／真人队长。

**机制人数/能力：** 机关要求三个独立角色站位，同时需队长检查；最低3人是否可兼任队长操作需实测。

**专用规则：**

- 题目文本/答案识别及准确持有量
- 三组可观察反馈驱动的站位枚举器
- 明确队长/站位者/清怪者分工和阶段屏障
- 通过实际NPC交互、站位和门户推进阶段，不以直接写过关属性替代任务动作。

**正常完成与收尾：** rewards：stageExpBase：100；200；400；800；1500；randomClearItemReward：由setEventRewards提供卷轴/装备/药水/矿石等候选；giveEventReward实际授予并检查背包空间。；failureAndExit：30分钟超时结束并送103000890；队伍人数不足、队长离开活动范围、离队/解散/掉线沿原生回调处理；奖励背包空间不足应暂停领取并告知，而不是直接改奖励或跳退出

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 存在代码级门槛与机关人数不一致的可能：允许3人入场，但3个站位者和队长检查需在真实NPC交互距离及移动约束下验证；本轮不据此认定必然失败。
- 需明确机器人解谜观察范围与策略：正常NPC反馈可驱动组合搜索，服务端组合属性可供调试与验收；不把直接改过关标志当作解谜成功。
- 掉落数据与NPC事件接入尚需实际副本验证；仅列脚本与WZ链路接通。

**关键依据：** [KerningPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/KerningPQ.js)；[KerningPQ.js:146](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/KerningPQ.js#L146)；[KerningPQ.js:163](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/KerningPQ.js#L163)；[KerningPQ.js:231](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/KerningPQ.js#L231)；[KerningPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/KerningPQ.js#L26)；[KerningPQ.js:41](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/KerningPQ.js#L41)。完整引用保存在机器目录。

<a id="ludi"></a>
## 玩具城组队任务／时空裂缝（LPQ）

规则：`LudiPqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/18)。类型：经典合作PQ。

**入口与编制：** 5～6人；35～50级；45分钟。事件：`LudiPQ`。

**协作分工：** 分房搜票者、机关／远程目标执行者、通行证交接者、五个互斥箱位者、队长检查者。

**机制人数/能力：** 第8关要求五名不同角色保持九箱中的五处站位；队长兼任可操作性需实测。

**专用规则：**

- 各关不同票据数及分房任务
- 第6关数字隐藏门户路线
- 第7关远程击杀及投物召唤
- 第8关九选五的多人站位与反馈搜索
- 第9关投票召Boss与证物交付

**正常完成与收尾：** nativeCompletionStages：stage：9；map：922010900；objective：投放4001022触发2201003召唤9300012阿利莎乐，击杀得4001023并由队长交2040044，触发clearPQ；actions：drop_item_at_reactor；boss_combat；loot_to_inventory；leader_npc_check；stage：bonus_reward；maps：922011000；922011100；objective：清关进入1分钟奖励箱图；计时结束移至奖励图，逐人找2040035领取事件奖励，背包足够后回221024500；actions：attack_reactor；reward_inventory_check；npc_reward；normal_exit

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 启用单人入口也绕不过第8关五人机关，bot规则必须尊重机械所需人数。
- 职业/跳跃能力、实际反应器投物与移动录制可达性尚未验证，不能宣称已有自动通关bot。

**关键依据：** [LudiPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiPQ.js)；[LudiPQ.js:194](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiPQ.js#L194)；[LudiPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiPQ.js#L26)；[LudiPQ.js:268](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiPQ.js#L268)；[LudiPQ.js:96](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiPQ.js#L96)；[2040034.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2040034.js)。完整引用保存在机器目录。

<a id="ludi-maze"></a>
## 玩具城迷宫组队任务（LMPQ）

规则：`LudiMazePqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/19)。类型：经典合作PQ。

**入口与编制：** 3～6人；51～70级；15分钟。事件：`LudiMazePQ`。

**协作分工：** 互斥迷宫房间清理者、票券收集／交接者、终点集合者、队长提交者。

**机制人数/能力：** 所有已登记成员要在终点会合，队长实际持至少30券；仅全队总券数够不满足交付条件。

**专用规则：**

- 迷宫房号/门户目的地有向图与最短路径
- 刷箱收券路线分工
- 券集中队长而非各自保留
- 15分钟内全员会合在809050015并按队长券数结算

**正常完成与收尾：** nativeCompletionStages：stage：maze_exit；map：809050015；npcId：9103000；objective：沿WZ固定门户图到16号房；全员到齐且队长至少30券后确认退出，移除队长券并给全队50×队长券数量经验，然后clearPQ；actions：maze_graph_navigation；rendezvous；leader_item_check；leader_npc_confirmation；stage：reward_exit；map：809050016；npcId：9103002；objective：每人正常领取事件随机物品奖励；背包不够可重试，成功去809050017，再找9103003回玩具城220000000；actions：npc_reward；inventory_capacity_check；normal_exit

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 中文NPC文案误称“鲁塔比/鲁塔比斯”，按地图和event key应归为玩具城迷宫，不另当新副本。
- 迁移源码有怪物券掉落记录，但未核实当前真实库掉率或实际拾取执行；地图图可达不等于bot能完成移动与机关交互。

**关键依据：** [LudiMazePQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiMazePQ.js)；[LudiMazePQ.js:120](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiMazePQ.js#L120)；[LudiMazePQ.js:140](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiMazePQ.js#L140)；[LudiMazePQ.js:147](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiMazePQ.js#L147)；[LudiMazePQ.js:232](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiMazePQ.js#L232)；[LudiMazePQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LudiMazePQ.js#L26)。完整引用保存在机器目录。

<a id="ellin-pq"></a>
## 毒雾森林（艾琳森林组队任务）

规则：`EllinPqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/20)。类型：经典合作PQ。

**入口与编制：** 4～6人；44～55级；冒险家；30分钟。事件：`EllinPQ`。

**协作分工：** 毒液收集／净化者、投物开门者、受控压血攻击者、捕捉者、迷宫探路／紫石交接者。

**机制人数/能力：** 受控压血与捕捉者协调停手，真实捕捉道具和弹珠库存为证据。

**专用规则：**

- 冒险家职业限制
- 毒液稀释与4次荆棘投放链
- 随机传送迷宫反馈探索
- 捕捉而不是全歼的HP控制
- 紫石箱子搜索/祭坛召唤
- 最终Boss形态与可选出口奖励

**正常完成与收尾：** completion：nativeClear：最终9300182击杀触发eim.clearPQ，仅停止计时并设eventCleared；optionalReward：Boss有机会掉4001164；出口投泉后，3008000.hit给在场各玩家52000经验；离开时泉state>0且背包有格可领4001198碎片，10碎片可换/升级耳环。掉落非必出，不能承诺每次都领碎片。；failureAndExit：timeout：30分钟后end；成员离图/掉线/退队按minPlayers条件结束或移除该成员；未通关解散会结束事件

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [EllinPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/EllinPQ.js)；[EllinPQ.js:123](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/EllinPQ.js#L123)；[EllinPQ.js:146](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/EllinPQ.js#L146)；[EllinPQ.js:232](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/EllinPQ.js#L232)；[EllinPQ.js:237](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/EllinPQ.js#L237)；[EllinPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/EllinPQ.js#L26)。完整引用保存在机器目录。

<a id="pirate-pq"></a>
## 海盗船（百草堂海盗组队任务）

规则：`PiratePqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/21)。类型：经典合作PQ。

**入口与编制：** 3～6人；55～100级；首阶段4分钟，后续按阶段计时。事件：`PiratePQ`。

**协作分工：** 分区勋章收集者、队长交物者、四门钥匙投放者、可选箱子执行者、Boss战斗者。

**机制人数/能力：** 三轮勋章与四门钥匙按真实库存交接，封门者和清怪者分工；未见固定同时站位机关。

**专用规则：**

- 三轮各20勋章的物品与状态顺序
- 可选宝箱分支会改变Boss和经验
- 四门封刷怪点与清理顺序
- 分段倒计时
- Boss击杀后必须NPC确认才发全队奖励

**正常完成与收尾：** completion：nativeClear：clearPQ停止计时并置完成；按宝箱数给予28000/35000/42000事件经验，warpEventTeam到925100600；exitReward：各成员与2094001选择离开，在ETC背包有格时获得4001158并返回251010404；累积20枚可按原有顺序兑换/升级海盗帽。；failureAndExit：timeout：首关4分钟，推进后各关重设6分钟、Boss关8分钟；超时end到925100700；掉线/退队按人数判断结束或退出

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 招募地图251010404的WZ带davy_next00脚本门户，但scripts-zh-CN与scripts两边均没有该脚本；不能说英文回退可补齐。NPC2094000正常startInstance入口仍接通，bot应走已核实的NPC入口，不宣称整个PQ因此不可用。

**关键依据：** [PiratePQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PiratePQ.js)；[PiratePQ.js:215](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PiratePQ.js#L215)；[PiratePQ.js:232](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PiratePQ.js#L232)；[PiratePQ.js:249](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PiratePQ.js#L249)；[PiratePQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PiratePQ.js#L26)；[PiratePQ.js:346](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PiratePQ.js#L346)。完整引用保存在机器目录。

<a id="orbis"></a>
## 女神之塔（天空之城组队任务）

规则：`OrbisRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/22)。类型：经典合作PQ。

**入口与编制：** 5～6人；51～70级；45分钟。事件：`OrbisPQ`。

**协作分工：** 云朵分区收集者、唱片调查／搬运者、三人区域站位者、门户／杠杆执行者、种子收集与守护者、队长阶段检查者。

**机制人数/能力：** 密室需三名角色在三区域分布；人数放开并不自动消除机制。

**专用规则：**

- 按星期选择唱片
- 三区域人数分配及反馈约束求解
- 16层门户选择与记忆
- 精确2杠杆状态协调
- 碎片与scar反应堆的物品对应
- 种子/花盆/生命草终局顺序

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 终局2006001.js的act内未声明eim，却在32～33行调用eim.giveEventPlayersExp/showClearEffect；ReactorScriptManager只注入rm。前面的spawnNpc/clearPQ/statusStg8已执行，但后续奖励经验及bonus timer可能因ReferenceError中断。

**关键依据：** [OrbisPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/OrbisPQ.js)；[OrbisPQ.js:229](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/OrbisPQ.js#L229)；[OrbisPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/OrbisPQ.js#L26)；[2013000.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2013000.js)；[2013000.js:50](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2013000.js#L50)；[2013001.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2013001.js)。完整引用保存在机器目录。

<a id="amoria"></a>
## 阿莫利亚挑战（结婚组队任务/APQ）

规则：`AmoriaRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/23)。类型：经典合作PQ。

**入口与编制：** 6人；40～255级；全员已婚、至少一男一女、门票；75分钟。事件：`AmoriaPQ`。

**协作分工：** 按性别分区执行者、镜片交接者、五个互斥绳索／箱位者、队长检查者、跑图与集合者。

**机制人数/能力：** 五人绳索/平台分布，另有男女和婚姻限制；不能用1人准入推断1人可解。

**专用规则：**

- 婚姻/真实性别及票券资格
- 男女分工清怪与镜片投物顺序
- 五人三绳索人数分布求解
- 五人在九平台唯一占位求解
- 组合失败的重置和怪物反馈
- 七门逃生路径与全员集合
- 夫妻奖励与首领领奖协调

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 出生无婚姻关系的普通机器人不能直接参与；需要真实满足北斗婚姻记录，不能只伪造isMarried或跳过原生准入。主任务并不要求配偶也在同队，只有额外夫妻奖励有该条件。
- 即使放开人数准入仍有五人站位和至少两种性别条件，必须将入场资格与机制可解性分别校验。
- stage2 generateCombo1按随机整数的三进制有效位数累计人数，取值较小时生成的人数总和可能少于强制5人；脚本7次失败后会重置。需在专用规则验证可解性和有界重试，不能无限尝试同一组合。

**关键依据：** [AmoriaPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/AmoriaPQ.js)；[AmoriaPQ.js:101](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/AmoriaPQ.js#L101)；[AmoriaPQ.js:187](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/AmoriaPQ.js#L187)；[AmoriaPQ.js:236](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/AmoriaPQ.js#L236)；[AmoriaPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/AmoriaPQ.js#L26)；[9201043.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9201043.js)。完整引用保存在机器目录。

<a id="magatia"></a>
## 玛加提亚：罗密欧与朱丽叶

规则：`MagatiaPqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/24)。类型：经典合作PQ。

**入口与编制：** 4人；71～85级；45分钟；A/Z阵营变体。事件：`MagatiaPQ_A`, `MagatiaPQ_Z`。

**协作分工：** 互斥调查点搜索者、烧杯供药／投药者、侧房留守开门者、文件交接者、四个独立试路者、友军守护者、队长限时对话者。

**机制人数/能力：** 四房探路、双侧文件与留守开门需协调；未测放宽后的物理最小人数。

**专用规则：**

- 26处调查分配与对象ID结果
- 三烧杯计数与投药时序
- 双房文件/钥匙/开门分工
- 10秒尤利特对话和站位分支
- 四房十层互斥路径的试错共享
- 友方NPC保护与两种Boss结局

**正常完成与收尾：** 45分钟到时end并逐人warp退出；队长离开副本范围、解散或人数不足时结束；死亡确认/掉线/退队按isEventTeamLackingNow处理；最终2112018在giveEventReward成功后返回招募地图；背包无空位不直接离开

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- WZ地图926100203引用portal/rnj6_act，926110203引用portal/jnr6_act；英、中脚本目录均未找到。对尤利特特殊分支的实际影响需验证，不据此断言普通清关完全不可用。
- StageMsg_romio / StageMsg_juliet 被地图WZ引用，英、中目录均未找到；可能影响关卡提示，运行影响未确认。
- 2619001反应器WZ action=rnjScript0但reactor/2619001.js及rnjScript0.js均未找到；引擎按反应器ID取脚本，不使用WZ action。友方NPC由WZ已有life生成，故此不是护送NPC必然不存在的依据。
- A线Boss为9300151/9300152，但奖励/normalClear分支仍比较Z线9300139，导致普通结局标志与经验分支看起来不一致；是源代码静态风险，未实测，不在本轮修复。

**变体：** eventKey：MagatiaPQ_A；entryMap：926110000；bossIds：9300151；9300152；eventKey：MagatiaPQ_Z；entryMap：926100000；bossIds：9300139；9300140。全部地图/物品参数见机器目录。

**关键依据：** [MagatiaPQ_A.js:194](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MagatiaPQ_A.js#L194)；[MagatiaPQ_A.js:221](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MagatiaPQ_A.js#L221)；[MagatiaPQ_A.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MagatiaPQ_A.js#L26)；[MagatiaPQ_A.js:302](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MagatiaPQ_A.js#L302)；[MagatiaPQ_A.js:317](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MagatiaPQ_A.js#L317)；[MagatiaPQ_A.js:424](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MagatiaPQ_A.js#L424)。完整引用保存在机器目录。

<a id="guild-quest"></a>
## 公会任务：沙连尼安遗迹（GPQ）

规则：`GuildQuestRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/25)。类型：公会／职业协作PQ。

**入口与编制：** 同公会6～30人；1～255级；公会队列；90分钟。事件：`GuildQuest`。

**协作分工：** 雕像序列观察／复现者、职业与位移能力专员、长矛／献礼物资搬运者、30级及以下专员、耳环门执行者、公会队长检查者。

**机制人数/能力：** 需要30级及以下同公会专员，并校验各支线所需位移/职业能力；人数够不代表阵容够。

**专用规则：**

- 持久同公会名册与最多30人实例协调，不限普通6人party
- 雕像序列记忆
- 四区域献礼约束求解
- 低等级及职业能力分工
- 装备保护耳环与死亡/复活策略
- 国王服饰有序投放

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [GuildQuest.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/GuildQuest.js)；[GuildQuest.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/GuildQuest.js#L26)；[GuildQuest.js:315](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/GuildQuest.js#L315)；[9040000.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9040000.js)；[9040000.js:68](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9040000.js#L68)；[9040001.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9040001.js)。完整引用保存在机器目录。

<a id="cwkpq"></a>
## 绯红要塞组队任务（CWKPQ）

规则：`CrimsonwoodKeepRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/26)。类型：公会／职业协作PQ。

**入口与编制：** 远征6～30人；90～255级；五职业及所需技能。事件：`CWKPQ`。

**协作分工：** 五职业符印技能专员、互斥压台者、职业房间执行者、遗物搬运者、远征队长检查者、分阶段战斗者。

**机制人数/能力：** 五职业真实技能、职业门与符印必须满足；职业外观或六人总数不能替代技能门槛。

**专用规则：**

- Expedition 6～30人生命周期
- 五职业及activeSkillID能力匹配
- 随机reactor身份识别与任务分工
- 五人通过/同时压台协调
- 职业房间独立解题与遗物收集
- 两波首领状态机

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 中文字符串内未转义"Sigils"；Node24 --check确认SyntaxError。该NPC为阶段说明，不确定是否阻断主流程，不能将其直接定为全部CWKPQ不可玩。
- action()使用eim但仅touch/untouch局部声明eim；五人平台动作存在ReferenceError可达风险，未执行Graal运行探针。

**关键依据：** [CWKPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CWKPQ.js)；[CWKPQ.js:104](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CWKPQ.js#L104)；[CWKPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CWKPQ.js#L26)；[9201107.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9201107.js)；[9201107.js:11](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9201107.js#L11)；[9201110.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9201110.js)。完整引用保存在机器目录。

<a id="treasure"></a>
## MV的巢穴／寻宝组队任务

规则：`TreasurePqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/27)。类型：特色／季节合作PQ。

**入口与编制：** 4～6人；140～255级；45分钟＋奖励房。事件：`TreasurePQ`。

**协作分工：** 分区信件收集者、队长交信者、Boss机关执行者、奖励箱分配者。

**机制人数/能力：** simultaneousMinimum：未见强制多人同时站位或职业开关；源码未证明人数放开后可实际单人通关；cooperation：收集/战斗/机关可分工；原生资格人数仍须满足；runtimeVerified：否

**专用规则：**

- 秘密信15封必须由队长实际持有并向NPC提交；不能直接改EIM进度
- 地图起伏/落坑回主图；机关打击与收集路线需协调
- Boss房需主动攻击召唤机关，不能只等待怪物自然刷新
- 奖励房倒计时及有序离开

**正常完成与收尾：** stage_exp：60000；100000；event_reward_item_list：；bonus_room_reactors：是；native_clear_trigger：monsterKilled(9400589)

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [TreasurePQ.js:122](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TreasurePQ.js#L122)；[TreasurePQ.js:234](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TreasurePQ.js#L234)；[TreasurePQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TreasurePQ.js#L26)；[9220018.js:49](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9220018.js#L49)；[9220020.js:42](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9220020.js#L42)；[guyfawkes0_esc.js:20](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/portal/guyfawkes0_esc.js#L20)。完整引用保存在机器目录。

<a id="boss-rush"></a>
## 首领突袭／Boss Rush

规则：`BossRushPqRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/28)。类型：组队Boss／生存挑战。

**入口与编制：** 1～6人；1～255级；大厅按队伍等级分档。事件：`BossRushPQ`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** simultaneousMinimum：未见强制多人同时站位或职业开关；源码未证明人数放开后可实际单人通关；cooperation：收集/战斗/机关可分工；原生资格人数仍须满足；runtimeVerified：否

**专用规则：**

- 按平均等级选择大厅，不把lobby当独立bot类型
- 基于怪物是否清空推进，禁止直接修改clear状态
- 多轮Boss+休息点节奏与全队集合
- 退出顺序及阶段奖励选择

**正常完成与收尾：** tiers：6；claim_via_npc_or_rest_portal：是；inventory_space_required：是；native_clear_trigger：final room empty plus NPC conversation

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**变体：** 入口按队伍平均等级选择lobby 0..7，尝试更高lobby；事件getMaxLobbies返回7，实际大厅可用数量需运行核对。全部地图/物品参数见机器目录。

**关键依据：** [BossRushPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/BossRushPQ.js#L26)；[BossRushPQ.js:80](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/BossRushPQ.js#L80)；[9000021.js:49](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9000021.js#L49)；[9000037.js:32](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9000037.js#L32)；[9000037.js:40](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9000037.js#L40)；[9000037.js:91](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9000037.js#L91)。完整引用保存在机器目录。

<a id="shaolin-yaoseng"></a>
## 少林密室／武林妖僧

规则：`ShaolinBossRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/29)。类型：组队Boss／生存挑战。

**入口与编制：** 1～6人；120～255级；发起者任务8534完成；30分钟。事件：`YaoSengPQ`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** simultaneousMinimum：未见强制多人同时站位或职业开关；源码未证明人数放开后可实际单人通关；cooperation：收集/战斗/机关可分工；原生资格人数仍须满足；runtimeVerified：否

**专用规则：**

- 事件isPq=false，但NPC要求正常party；应归Boss房间而非解谜PQ
- 当前只有中文事件/入口；zh-CN事件目录会被枚举注册，en-US无同名文件
- 前置8534只在入口发起者检查，事件成员筛选未逐个检查该Quest
- 配置掉落列表[2000005]每个5次40%抽样；不可把空event_reward物品表描述为无Boss掉落

**正常完成与收尾：** native_clear_trigger：monsterKilled(BossID)；allMonstersDead也调用clearPQ；clear_after_minutes：2；drop_items：2000005；reward_pool_and_stage_exp_empty：是

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [YaoSengPQ.js:1](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/YaoSengPQ.js#L1)；[YaoSengPQ.js:132](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/YaoSengPQ.js#L132)；[YaoSengPQ.js:369](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/YaoSengPQ.js#L369)；[YaoSengPQ.js:380](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/YaoSengPQ.js#L380)；[9310039.js:17](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9310039.js#L17)；[9310039.js:52](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9310039.js#L52)。完整引用保存在机器目录。

<a id="shanghai-wugong"></a>
## 上海秘密任务／大王蜈蚣

规则：`GiantCentipedeBossRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/30)。类型：组队Boss／生存挑战。

**入口与编制：** 1～6人；25～90级；10分钟；不是武陵道场。事件：`WuGongPQ`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** simultaneousMinimum：未见强制多人同时站位或职业开关；源码未证明人数放开后可实际单人通关；cooperation：收集/战斗/机关可分工；原生资格人数仍须满足；runtimeVerified：否

**专用规则：**

- WuGong是蜈蚣，不是武陵道场；事件isPq=true但实际是一间Boss房
- NPC isRepeat=true默认走实例；isRepeat=false才启用4103/8512完成后的替代路径，不应把此分支当默认前置
- 与YaoSeng共享Boss房间能力但参数、前置、地图、时限和完成回调不同
- 仅中文脚本，zh-CN自动注册

**正常完成与收尾：** native_clear_trigger：monsterKilled(BossID)；clear_after_minutes：2；drop_items：2000005；reward_pool_and_stage_exp_empty：是

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [WuGongPQ.js:1](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/WuGongPQ.js#L1)；[WuGongPQ.js:132](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/WuGongPQ.js#L132)；[WuGongPQ.js:370](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/WuGongPQ.js#L370)；[WuGongPQ.js:381](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/WuGongPQ.js#L381)；[9310006.js:34](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9310006.js#L34)；[9310006.js:58](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9310006.js#L58)。完整引用保存在机器目录。

<a id="cafe"></a>
## 高级之路／网吧组队任务

规则：`CafePartyQuestRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/31)。类型：特色／季节合作PQ。

**入口与编制：** 3～6人；21～120级；45分钟；六张主题图变体。事件：`CafePQ_1`, `CafePQ_2`, `CafePQ_3`, `CafePQ_4`, `CafePQ_5`, `CafePQ_6`。

**协作分工：** 分区清怪收券者、票券交接者、队长提交者。

**机制人数/能力：** simultaneousMinimum：未见硬编码同步多人站位；实际拾券/汇集/战斗效率需验收；cooperation：跨图战斗与券汇集至队长；runtimeVerified：否

**专用规则：**

- 区域/地图路线与券数表
- 跨地图分工以及所有券汇集到队长的完成条件
- 选择对应橡皮擦奖励与可选贩卖机兑换策略

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- NPC1052013完成分支在检查有券后调用gainItem(4001007,+couponsNeeded)，是加券而非扣券；中文英文一致。此为来源静态异常，本轮未修复或声称阻断通关。
- 六个Cafe主要是参数与地图差异，应共用一套规则而非六个独立bot实现。
- 现有普通bot拾取若只删除地图物品而未入背包，将无法完成队长持券条件。

**变体：** id：1；eventKey：CafePQ_1；title：呼吸寺院门口；entryMap：190000000；id：2；eventKey：CafePQ_2；title：猴子森林；entryMap：191000000；id：3；eventKey：CafePQ_3；title：火焰山Ⅰ；entryMap：192000000；id：4；eventKey：CafePQ_4；title：危险的蚂蚁洞；entryMap：195000000；id：5；eventKey：CafePQ_5；title：荒凉的山丘；entryMap：196000000；id：6；eventKey：CafePQ_6；title：暴风露台Ⅰ；entryMap：197000000。全部地图/物品参数见机器目录。

**关键依据：** [CafePQ_1.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CafePQ_1.js)；[CafePQ_1.js:122](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CafePQ_1.js#L122)；[CafePQ_1.js:245](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CafePQ_1.js#L245)；[CafePQ_1.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CafePQ_1.js#L26)；[CafePQ_1.js:97](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CafePQ_1.js#L97)；[CafePQ_2.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/CafePQ_2.js)。完整引用保存在机器目录。

<a id="holiday"></a>
## 假日组队任务／枫之圣诞节雪人保卫战

规则：`HolidayPartyQuestRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/32)。类型：特色／季节合作PQ。

**入口与编制：** 3～6人；21～30／31～40／41～50三级段；15／20／25分钟。事件：`HolidayPQ_1`, `HolidayPQ_2`, `HolidayPQ_3`。

**协作分工：** 雪人守护者、雪球收集／投喂者、漏怪拦截者。

**机制人数/能力：** 保护雪人与收集投喂并行；人数门槛之外仍须验证防守能力和时间预算。

**专用规则：**

- 真假雪之力量分类，丢物坐标和reactor喂养
- 雪人血量保护与成长阶段监控
- 队长在成长完成时再次对话召唤首领
- 三个等级/难度参数、对应monsterId公式与奖励

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- Holiday三变体为相同机制的等级/时限/地图/奖励参数变化，应共用一套规则。
- 入口需前置任务28003；是否在实际服务器当前活动条件下可接取未做运行验证。
- 三张战斗地图都引用portal脚本08_xmas_st，但源码scripts与scripts-zh-CN下未找到该文件；NPC主线可见，缺失portal是否阻断需单独验收，不能宣称完整通关。
- 当前NPC9105005退出脚本在area>0时warp(mapId+1)，即战斗图到出口；大厅返回209000000。

**变体：** id：1；eventKey：HolidayPQ_1；entryMap：889100001；lobbyMap：889100000；baselineMinLevel：21；baselineMaxLevel：30；id：2；eventKey：HolidayPQ_2；entryMap：889100011；lobbyMap：889100010；baselineMinLevel：31；baselineMaxLevel：40；id：3；eventKey：HolidayPQ_3；entryMap：889100021；lobbyMap：889100020；baselineMinLevel：41；baselineMaxLevel：50。全部地图/物品参数见机器目录。

**关键依据：** [HolidayPQ_1.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HolidayPQ_1.js)；[HolidayPQ_1.js:258](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HolidayPQ_1.js#L258)；[HolidayPQ_1.js:28](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HolidayPQ_1.js#L28)；[HolidayPQ_1.js:306](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HolidayPQ_1.js#L306)；[HolidayPQ_2.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HolidayPQ_2.js)；[HolidayPQ_2.js:28](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HolidayPQ_2.js#L28)。完整引用保存在机器目录。

<a id="netts-pyramid"></a>
## 奈特金字塔

规则：`NettsPyramidRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/33)。类型：组队Boss／生存挑战。

**入口与编制：** 可单人；组队同图至少2人；发起者40级以上，非HELL最高60级。事件：Java/Channel驱动，无event key。

**协作分工：** 分区清怪者、禁杀目标避让者、计量衰减应对者、奖励结算者。

**机制人数/能力：** teamStructure：单人合法；组队同图至少2人；cooperation：五阶段持续战斗与行动槽；人数放开不替代正确计分动作；runtimeVerified：否

**专用规则：**

- 按难度/单双人选择地图及房间。
- 行动槽驱动攻击节奏、避免MISS和误伤特殊法老雪人，按原生技能次数使用法老怒火。
- 必须启用真实击杀而非TrainingBot无人观察抽象经验。
- 支持Java PartyQuest对象及onUserEnter Massacre_first/Massacre_result，不依赖EventInstanceManager。

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 现有headless地图入场脚本屏蔽策略需由PQ活动适配，不能仅修改bot类型就认为活动已启动。

**变体：** EASY；NORMAL；HARD；HELL。全部地图/物品参数见机器目录。

**关键依据：** [2103013.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2103013.js)；[2103013.js:126](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2103013.js#L126)；[Massacre_first.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/map/onUserEnter/Massacre_first.js)；[Massacre_first.js:1](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/map/onUserEnter/Massacre_first.js#L1)；[Massacre_result.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/map/onUserEnter/Massacre_result.js)；[Massacre_result.js:1](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/map/onUserEnter/Massacre_result.js#L1)。完整引用保存在机器目录。

<a id="kerning-subway-hunt"></a>
## 废弃都市地铁清怪挑战（尘土飞扬的平台／999号列车）

规则：`KerningSubwayHuntRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/34)。类型：准入断链待验证。

**入口与编制：** 入口宣称25～30级；原生旧API缺失，准入和可解性未证实。事件：Java/Channel驱动，无event key。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 先隔离验证入口断链，当前不能建立“可入场可通关”的人数结论。

**专用规则：**

- 先明确并修复此入口后端，不得把同名交通Subway event绑定成战斗规则。
- 通关、计分、奖励与清理的真实实现需要重新追踪/补齐后才能创建可用bot。

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 缺少start_PyramidSubway与bonus_PyramidSubway实现，不能标记可用或已通关。

**变体：** platform；train-999-bonus。全部地图/物品参数见机器目录。

**关键依据：** [1052115.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/1052115.js)；[1052115.js:29](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/1052115.js#L29)；[1052115.js:38](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/1052115.js#L38)；[1052115.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts/npc/1052115.js)；[1052115.js:41](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts/npc/1052115.js#L41)；[NPCConversationManager.java](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/src/main/java/org/gms/scripting/npc/NPCConversationManager.java)。完整引用保存在机器目录。

<a id="monster-carnival"></a>
## 怪物嘉年华

规则：`MonsterCarnivalRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/35)。类型：竞技相邻玩法。

**入口与编制：** 两支等人数队；通常2～6人/队；CPQ1为30～50级、CPQ2为51～70级。事件：Java/Channel驱动，无event key。

**协作分工：** 本队清怪得分者、CP消费／召唤者、本队守护机关执行者、对手目标应对者。

**机制人数/能力：** 两支不同队均合法报名；仅队内协调，不能共享对手私有计划或凭空省掉对手队。

**专用规则：**

- 需要双队匹配和对手AI，不能只有“一个真人+同队机器人”就假定能开。
- 两版本共享规则内核，以等级/地图/房间/奖励参数区分。
- 队伍级CP预算、召怪/干扰/守护者策略、敌我阵营和比分驱动动作。
- Headless须响应真实挑战确认，并调用原生CP消费链，不直接修改比分或结算。

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 需适配Headless挑战确认、敌对阵营策略与CP指令；use_cpq/人数分支还需实际验证。

双队竞争玩法：只在同一队内协调，对手信息与决策隔离；单人公会也不能省掉对手队。

**变体：** id：cpq1；name：怪物嘉年华1；id：cpq2；name：怪物嘉年华2。全部地图/物品参数见机器目录。

**关键依据：** [2042000.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2042000.js)；[2042000.js:124](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2042000.js#L124)；[2042000.js:177](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2042000.js#L177)；[2042000.js:18](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2042000.js#L18)；[2042005.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2042005.js)；[2042005.js:8](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2042005.js#L8)。完整引用保存在机器目录。

<a id="mulung-dojo"></a>
## 武陵道场

规则：`MulungDojoRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/36)。类型：组队Boss／生存挑战。

**入口与编制：** 普通party上限6人；操作者25级以上；初入全队等级差≤30。事件：Java/Channel驱动，无event key。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** teamStructure：单人模式合法；组队模式普通Party上限6，1人party拒绝分支注释；cooperation：Boss爬塔、清怪开门、休息层集合；无额外多人机关已证实；runtimeVerified：否

**专用规则：**

- Dojo槽位及分段地图编码识别
- 清怪→门开启→逐层上行，不能只看到出口就抢跑
- 休息层集合和队长续关
- 能量与秘密技能策略
- 单人续关与组队slot分别处理

**正常完成与收尾：** 逐层addDojoPointsByMap；最终dojang_next传送925020003并gainExp(2000*dojoPoints)；NPC2091005以道场点数换1132000..1132004腰带，按use_fast_dojo_upgrade选择积分表，并可能消耗前置腰带；代码等级比较为>；中途NPC退出925020002；定时器超时也退出，释放道场区段；单人支持记录进度

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 925020003.img.xml:21引用dojang_QcheckSet，中英scripts目录均未找到该脚本；不能据此直接断言主线无法通关，需测试最终图和称号/结算相关行为
- Headless当前集成会跳过部分入图脚本；该玩法依赖dojang_1st生成怪物，通用PQ适配必须验证原生入图事件

**变体：** solo（925020xxx）；party（925030xxx）。全部地图/物品参数见机器目录。

**关键依据：** [WuGongPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/WuGongPQ.js)；[WuGongPQ.js:5](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/WuGongPQ.js#L5)；[2091005.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2091005.js)；[2091005.js:152](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2091005.js#L152)；[2091005.js:194](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2091005.js#L194)；[2091005.js:293](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2091005.js#L293)。完整引用保存在机器目录。

<a id="ariant-coliseum"></a>
## 阿里安特竞技场

规则：`AriantColiseumRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/37)。类型：竞技相邻玩法。

**入口与编制：** 个人竞争；20～30级；不得在普通party；人数沿报名管理器。事件：Java/Channel驱动，无event key。

**协作分工：** 每位参赛者独立捕捉与得分；不与敌方共享隐藏状态或分工。

**机制人数/能力：** 不建立全参赛者协作；每个参赛bot独立决策，通过原生对抗交互。

**专用规则：**

- 远征报名而非普通party；主动退出普通队伍必须由活动协调
- 削血捕捉策略，不能直接击杀所有目标
- 元素岩石/炸弹库存和捕捉冷却
- 个人竞赛及宝石拾取/掉落策略
- 公平分数策略及原生奖励规则

**正常完成与收尾：** 每人基础奖励tier=floor(宝石积分/10)，存在对悬殊比分的调整；10分钟结束传送国王房980010010；NPC2101016清活动物品并给经验ceil(92*expRate*tier)和AriantPoints；NPC2101015可花100AriantPoints兑换3010018椰树沙滩椅；不足活跃人数或断线会退出并释放；国王房出口ariantMout前往980010020

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 输入人数上限提示与实际校验不一致，机器人规则应按原生可接受入口并另测边界，不从提示文本推断硬限制
- NPC2101018外部介绍入口低级判断<19，与正式startAriantBattle强制20~30不完全一致；实际资格以Java启动校验为准

个人竞争玩法，正式登记使用远征名册而非普通队伍；作为相邻玩法候选，不算合作PQ。

**变体：** ARIANT（980010100→980010101）；ARIANT1（980010200→980010201）；ARIANT2（980010300→980010301）。全部地图/物品参数见机器目录。

**关键依据：** [2101014.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2101014.js)；[2101014.js:136](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2101014.js#L136)；[2101014.js:37](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2101014.js#L37)；[2101014.js:85](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2101014.js#L85)；[2101015.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2101015.js)；[2101015.js:37](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2101015.js#L37)。完整引用保存在机器目录。

<a id="zakum-prequest"></a>
## 扎昆前置：未知死亡矿井

规则：`ZakumPreQuestRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/38)。类型：前置／技能合作任务。

**入口与编制：** 6人；50～255级；发起者任务100200；30分钟。事件：`ZakumPQ`。

**协作分工：** 互斥矿洞搜索者、钥匙／文件搬运者、七钥匙投放者、队长交矿石者、逐人领奖者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 钥匙/文件产出位置与区域分配
- 7钥匙投放触发器和队长交付
- 文件可选支线及每人一次grid领取
- 跳跳阶段为独立个人动作规则，不混作Boss战

**正常完成与收尾：** NPC2032002:火矿石清关给全队12000经验，若另交30文件则20000；各成员领取4031061，文件支线另领2030007×5。event setEventRewards数组为空，但不表示此PQ无奖励。数值为脚本直接参数，实际倍率链未运行验证。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 1.12默认矿洞队伍为6人；人数解除仅改变准入，不能替代机关分工。
- 前置任务100200与跳跳/火眼100201是相关流程，不应在机器人代码里强制改任务状态。

**关键依据：** [ZakumPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ZakumPQ.js)；[ZakumPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ZakumPQ.js#L26)；[2030008.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2030008.js)；[2030008.js:62](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2030008.js#L62)；[2030008.js:83](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2030008.js#L83)；[2032002.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2032002.js)。完整引用保存在机器目录。

<a id="horntail-prequest"></a>
## 黑龙前置：生命洞穴迷宫

规则：`HorntailPreQuestRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/39)。类型：前置／技能合作任务。

**入口与编制：** 6人；120～255级；25分钟。事件：`HorntailPQ`。

**协作分工：** 门户留守者、钥匙接力者、光暗分路执行者、队长交钥匙者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 迷宫钥匙传递顺序和留守角色
- 光暗随机分支
- 队长钥匙汇总与最终交付

**正常完成与收尾：** 事件通关奖励数组为空，clearPQ主要标记完成并转场到黑龙Boss入口前置区域；不要与HorntailBattle战利品混算。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 主图reactor2402002..2402005的WZ action分别hontaleItem0..3；源码两语言均无同ID脚本也无对应hontaleItem脚本，而ReactorScriptManager仅按reactor ID加载。关键传钥匙是否断链需先隔离验证，不能用机器人直接写clear属性绕过。
- event只设置每个小图passKey1..4状态；现有2408002.js用于回传钥匙与置stageclear。
- 当前Headless跳过玩家入图脚本；各实例入口需求须逐项适配。

**关键依据：** [HorntailPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HorntailPQ.js)；[HorntailPQ.js:121](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HorntailPQ.js#L121)；[HorntailPQ.js:244](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HorntailPQ.js#L244)；[HorntailPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HorntailPQ.js#L26)；[2083001.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2083001.js)；[2083001.js:56](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2083001.js#L56)。完整引用保存在机器目录。

<a id="elnath-protect-tylus"></a>
## 雪域组队：护卫泰勒斯

规则：`ProtectTylusRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/40)。类型：前置／技能合作任务。

**入口与编制：** 1～4人；80～255级；发起者任务6192进行中；10分钟。事件：`ElnathPQ`。

**协作分工：** 泰勒斯守护者、拦截攻击者、护符拾取／任务提交者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 护卫友方而非自动攻击所有地图怪物
- 仅任务请求者交付与奖励，助手不能被当作同样完成技能任务
- 定时掉落与失败状态观察

**正常完成与收尾：** event无随机奖励表；原生6192交4031495奖励110万经验并设置对应战士稳如泰山技能masterLevel10（以原生Quest数据为准）。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 友方受击是MobDamageMobFriendlyHandler客户端上报链，纯机器人无真人观察时的友方受击/怪物控制需要专门验证。
- NPC2022004仅返城，不能把到达成功地图视为任务已交付。

**关键依据：** [ElnathPQ.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ElnathPQ.js)；[ElnathPQ.js:113](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ElnathPQ.js#L113)；[ElnathPQ.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ElnathPQ.js#L26)；[2020008.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2020008.js)；[2020008.js:28](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2020008.js#L28)；[2022004.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2022004.js)。完整引用保存在机器目录。

<a id="neo-city-bosses"></a>
## 未来之城Boss组队战

规则：`NeoCityBossRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/41)。类型：组队Boss／生存挑战。

**入口与编制：** 2～6人；70～255级；五场Boss变体，各有时限与任务/入口条件。事件：`TD_Battle1`, `TD_Battle2`, `TD_Battle3`, `TD_Battle4`, `TD_Battle5`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 每Boss目标ID/形态链/危险技能
- 未来之城任务准入与各地图路线

**正常完成与收尾：** 完成、奖励和退出以对应原生管理器的实际结果为准。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- TD_Battle2..5 setup仍resetPQ(240070203)，与自己的entryMap不同，源码静态可疑，应隔离验证；不要因有入口而标为已通关。
- 各Boss图onUserEnter=TD_neo_BossEnter，但两语言没有同名map脚本；地图仍含原生怪物life，不能仅凭缺脚本断言Boss不出现。
- Bergamot/Nibelung的初始与final ID不同，已在WZ revive链找到7220003→7220004→7220005及8220013→8220014→8220015。

**变体：** key：TD_Battle1；boss：Bergamot；finalMob：7220005；key：TD_Battle2；boss：Dunas；finalMob：8220010；key：TD_Battle3；boss：Aufheben；finalMob：8220011；key：TD_Battle4；boss：Oberon；finalMob：8220012；key：TD_Battle5；boss：Nibelung；finalMob：8220015。全部地图/物品参数见机器目录。

**关键依据：** [TD_Battle1.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TD_Battle1.js)；[TD_Battle1.js:122](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TD_Battle1.js#L122)；[TD_Battle1.js:241](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TD_Battle1.js#L241)；[TD_Battle1.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TD_Battle1.js#L26)；[TD_Battle5.js](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TD_Battle5.js)；[TD_Battle5.js:123](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/TD_Battle5.js#L123)。完整引用保存在机器目录。

<a id="zakum-raid"></a>
## 扎昆远征

规则：`ZakumRaidRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/42)。类型：远征Boss。

**入口与编制：** 远征6～30人；50～255级；120分钟；召唤物与前置沿原生规则。事件：`ZakumBattle`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 入场需要火焰之眼4001017
- 召唤由reactor/2111001处理；不能只进场找怪
- 必须区分手臂与本体阶段，最终本体击杀才clear
- 完成后卸载时推进任务100200

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [ZakumBattle.js:191](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ZakumBattle.js#L191)；[ZakumBattle.js:218](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ZakumBattle.js#L218)；[ZakumBattle.js:229](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ZakumBattle.js#L229)；[ZakumBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ZakumBattle.js#L27)；[2030013.js:178](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2030013.js#L178)；[2030013.js:33](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2030013.js#L33)。完整引用保存在机器目录。

<a id="horntail-raid"></a>
## 黑龙远征

规则：`HorntailRaidRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/43)。类型：远征Boss。

**入口与编制：** 远征6～30人；100～255级；120分钟；前置沿原生规则。事件：`HorntailBattle`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 跨多个独立地图，需识别龙头与本体
- 需要原生传送门/召唤机关协作，不能以跟随替代阶段识别
- 击杀最终本体才发胜利广播并推进任务计数

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [HorntailBattle.js:100](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HorntailBattle.js#L100)；[HorntailBattle.js:205](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HorntailBattle.js#L205)；[HorntailBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/HorntailBattle.js#L27)；[2083004.js:170](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2083004.js#L170)；[2083004.js:33](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2083004.js#L33)；[240050400.img.xml](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/wz/Map.wz/Map/Map2/240050400.img.xml)。完整引用保存在机器目录。

<a id="pink-bean-raid"></a>
## 品克缤远征

规则：`PinkBeanRaidRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/44)。类型：远征Boss。

**入口与编制：** 远征6～30人；120～255级；140分钟。事件：`PinkBeanBattle`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 石像8820002..8820006与未激活形态8820019..8820023之间切换
- 5波进度由event stage控制，不能固定攻击不存在目标
- 需要处理最终道具/机关解除阶段及最终本体目标

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [PinkBeanBattle.js:252](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PinkBeanBattle.js#L252)；[PinkBeanBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PinkBeanBattle.js#L27)；[PinkBeanBattle.js:287](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PinkBeanBattle.js#L287)；[PinkBeanBattle.js:98](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PinkBeanBattle.js#L98)；[2141001.js:173](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2141001.js#L173)；[2141001.js:35](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2141001.js#L35)。完整引用保存在机器目录。

<a id="scarga-raid"></a>
## 暴力熊与心疤狮王远征

规则：`ScargaRaidRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/45)。类型：远征Boss。

**入口与编制：** 远征6～30人；100～255级；60分钟。事件：`ScargaBattle`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 入场需要4032246
- 两只Boss的独立多形态计数与召唤点
- 不能第一只最终形态死亡就认定整场完成

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [ScargaBattle.js:170](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ScargaBattle.js#L170)；[ScargaBattle.js:180](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ScargaBattle.js#L180)；[ScargaBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ScargaBattle.js#L27)；[9270047.js:177](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9270047.js#L177)；[9270047.js:33](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9270047.js#L33)；[9270047.js:99](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9270047.js#L99)。完整引用保存在机器目录。

<a id="showa-raid"></a>
## 昭和黑帮远征（The Boss）

规则：`ShowaRaidRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/46)。类型：远征Boss。

**入口与编制：** 远征3～30人；100～255级；60分钟。事件：`ShowaBattle`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 入场需要梳子4000138
- 识别最终目标9400300
- 无死亡条件影响额外Boss，必须记录playerDied而非只看最终胜利

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [ShowaBattle.js:101](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ShowaBattle.js#L101)；[ShowaBattle.js:181](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ShowaBattle.js#L181)；[ShowaBattle.js:198](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ShowaBattle.js#L198)；[ShowaBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ShowaBattle.js#L27)；[9120201.js:177](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9120201.js#L177)；[9120201.js:32](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/9120201.js#L32)。完整引用保存在机器目录。

<a id="balrog-raid"></a>
## 巴洛古远征

规则：`BalrogRaidRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/47)。类型：远征Boss。

**入口与编制：** 普通远征6～30人；50～255级；Easy声明3～30人但入口未定位。事件：`BalrogBattle`, `BalrogBattle_Easy`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- Normal公开NPC只选择BALROG_NORMAL与BalrogBattle
- Easy脚本和BALROG_EASY枚举存在且会自动注册，但未在两语言NPC/portal/Java中找到实际调用Easy事件的入口
- 主身/双爪阶段与延迟生成封印形态必须用专用状态规则
- EventManager远征调用setup(channel)，此脚本setup(level,lobbyid)参数形态不同，需隔离验证，不能仅凭静态入口承诺可通关

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

普通入口已定位；Easy只有事件与枚举，实际入口未定位，变体保持禁用直到验证。

**关键依据：** [BalrogBattle.js:125](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/BalrogBattle.js#L125)；[BalrogBattle.js:151](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/BalrogBattle.js#L151)；[BalrogBattle.js:257](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/BalrogBattle.js#L257)；[BalrogBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/BalrogBattle.js#L27)；[BalrogBattle_Easy.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/BalrogBattle_Easy.js#L27)；[1061014.js:175](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/1061014.js#L175)。完整引用保存在机器目录。

<a id="papulatus-battle"></a>
## 帕普拉图斯（闹钟）组队Boss

规则：`PapulatusBattleRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/48)。类型：组队Boss／生存挑战。

**入口与编制：** 事件声明1～6人、1～255级；门户前置另校验；45分钟。事件：`PapulatusBattle`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 与任务6361/6363相关路径会分流到922020300，不能误走特殊任务入口
- 要求处于队伍且由队长发起，可为单人队伍
- 副本门与2208001..2208003机关状态联动，清关/失败需恢复门状态

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [PapulatusBattle.js:216](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PapulatusBattle.js#L216)；[PapulatusBattle.js:227](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PapulatusBattle.js#L227)；[PapulatusBattle.js:238](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PapulatusBattle.js#L238)；[PapulatusBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PapulatusBattle.js#L27)；[PapulatusBattle.js:95](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/PapulatusBattle.js#L95)；[Populatus00.js:25](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/portal/Populatus00.js#L25)。完整引用保存在机器目录。

<a id="latanica-battle"></a>
## 幽灵船长（Latanica）组队Boss

规则：`LatanicaBattleRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/49)。类型：组队Boss／生存挑战。

**入口与编制：** 事件声明1～6人、1～255级；门户前置另校验；10分钟。事件：`LatanicaBattle`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 白色精华在portal检查，不能省略为纯战斗房
- 需要正常party和队长，即使一人也需建队
- 召唤机关/战斗/clear三个环节必须接通

**正常完成与收尾：** 事件脚本的额外奖励、阶段经验数组为空；脚本清关/胜利链存在。正常收益依赖原生怪物经验、掉落及相关任务进度，未核对实际数据库掉落表。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [LatanicaBattle.js:229](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LatanicaBattle.js#L229)；[LatanicaBattle.js:239](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LatanicaBattle.js#L239)；[LatanicaBattle.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LatanicaBattle.js#L27)；[LatanicaBattle.js:95](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/LatanicaBattle.js#L95)；[captinsg00.js:3](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/portal/captinsg00.js#L3)；[5411000.js:22](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/reactor/5411000.js#L22)。完整引用保存在机器目录。

<a id="elemental-thanatos"></a>
## 元素塔纳托斯（法师技能组队任务）

规则：`ElementalThanatosRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/50)。类型：前置／技能合作任务。

**入口与编制：** 2名法师；100～255级；发起者技能任务状态要求；20分钟。事件：`ElementalBattle`。

**协作分工：** 符合技能任务条件的法师队员、战斗分工与生存协作者、任务物品交付者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 任务链门槛
- 互补元素技能与目标分工
- 两个Boss都完成的阶段判断

**正常完成与收尾：** event rewards和clear-stage经验列表为空；clearPQ只停计时并标记通关，技能任务链和怪物掉落/任务物品仍需原生任务规则继续验证，不能宣称已有常规随机PQ奖励

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [ElementalBattle.js:122](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ElementalBattle.js#L122)；[ElementalBattle.js:241](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ElementalBattle.js#L241)；[ElementalBattle.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ElementalBattle.js#L26)；[ElementalBattle.js:97](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/ElementalBattle.js#L97)；[2041023.js:54](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2041023.js#L54)；[Channel.java:463](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/src/main/java/org/gms/net/server/channel/Channel.java#L463)。完整引用保存在机器目录。

<a id="delli-rescue"></a>
## 拯救 Delli（技能任务护送/防守）

规则：`DelliDefenseRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/51)。类型：前置／技能合作任务。

**入口与编制：** 1～2人；120～255级；任务6410相关；6分钟。事件：`DelliBattle`。

**协作分工：** Delli守护者、刷怪拦截者、任务进度核对者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 保护友方目标
- 抢先拦截威胁而非只追最近怪
- 坚持到计时结束
- 任务进度与成功出口核对

**正常完成与收尾：** 没有setEventRewards常规奖励；成功与任务6410进度相连，随后NPC可送到120000104

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [DelliBattle.js:102](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/DelliBattle.js#L102)；[DelliBattle.js:115](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/DelliBattle.js#L115)；[DelliBattle.js:125](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/DelliBattle.js#L125)；[DelliBattle.js:200](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/DelliBattle.js#L200)；[DelliBattle.js:26](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/DelliBattle.js#L26)；[2095000.js:46](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/2095000.js#L46)。完整引用保存在机器目录。

<a id="mushroom-prime-minister"></a>
## 蘑菇城总理（剧情Boss，可单人/组队）

规则：`MushroomPrimeMinisterRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/52)。类型：前置／技能合作任务。

**入口与编制：** 1～3人；30～255级；蘑菇城剧情任务状态／道具门槛；10分钟。事件：`MK_PrimeMinister`, `MK_PrimeMinister2`。

**协作分工：** 资格／补给核对者、按实际技能分配战斗与生存职责、阶段检查／原生结算执行者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- NPC或门户可startInstance(party,map,1)，无队时可startInstance(player)
- MK_PrimeMinister首次版仅有2333击杀进度尚未完成的成员时刷3300008；重打版有在场玩家即刷
- 击杀3300008打开portal1并标记清关

**正常完成与收尾：** 无独立随机PQ奖励，服务于蘑菇城任务链；门户可能先完成2332并给4400经验，再进入Boss房

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**关键依据：** [MK_PrimeMinister.js:190](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MK_PrimeMinister.js#L190)；[MK_PrimeMinister.js:6](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MK_PrimeMinister.js#L6)；[MK_PrimeMinister.js:78](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MK_PrimeMinister.js#L78)；[MK_PrimeMinister2.js:78](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/MK_PrimeMinister2.js#L78)；[1300013.js:27](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/1300013.js#L27)；[TD_MC_enterboss2.js:20](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/portal/TD_MC_enterboss2.js#L20)。完整引用保存在机器目录。

<a id="s4a-world"></a>
## 弓箭手四转技能异世界任务（狙击／集中）

规则：`ArcherSkillQuestRules`；[实现与验收](https://github.com/Diluka/SoloMapling/issues/53)。类型：前置／技能合作任务。

**入口与编制：** 至少1名合格队员；120级以上；职业312/322/GM；发起者任务6107或6108。事件：`s4aWorld`。

**协作分工：** 符合职业与任务条件的弓箭手、分区战斗者、任务完成核对者。

**机制人数/能力：** 按原生资格与阶段能力检查；尚未运行验证最小可解人数。

**专用规则：**

- 技能任务与职业资格筛选，NPC文案两人不是现有硬人数校验。
- 定时刷怪与限时清理，协作分区及生存。
- 正常任务进度/交付路径验证，不以本地杀怪数或全图清怪自行宣布任务完成。

**正常完成与收尾：** event有clearPQ和退出清理，但monsterKilled无自动胜利条件；6107/6108的技能任务交付与实际收益需要沿原生任务链另行验证。

**源代码现状：** 已静态追踪入口与任务脚本；规则尚未实现，尚无引擎或真人客户端通关证据。断链与疑点见下列核实项，机器目录保留原始判定。

**实施前核实：**

- 源脚本的任务完成／技能交付证据尚未完整验证；不得据事件存在就宣称任务收益已接通。

**关键依据：** [s4aWorld.js:112](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/s4aWorld.js#L112)；[s4aWorld.js:15](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/s4aWorld.js#L15)；[s4aWorld.js:40](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/event/s4aWorld.js#L40)；[1061012.js:45](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/1061012.js#L45)；[1061012.js:7](https://github.com/BeiDouMS/BeiDou-Server/blob/75a86268738ca8845e06ea9d59ec36d15b22c91b/gms-server/scripts-zh-CN/npc/1061012.js#L7)。完整引用保存在机器目录。

