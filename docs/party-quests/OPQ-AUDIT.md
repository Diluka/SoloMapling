# 现有 OPQ 与北斗 OrbisPQ 的静态契约核对

核对版本：Solo `e45097bb`、官方北斗 1.12 `75a86268`。只读源码、JS、WZ及原生迁移数据；未运行副本、未写库、未修改源码或服务。Serena Java初始化失败后使用限定范围文本检索。

## 结论

现有 OPQ 是针对云与唱片两段的 **rush bot 原型**，不能标为完整天空组队任务实现，更不能当作已在北斗通关。地图和部分机关ID相同，但任务完成语义、物品流及运行实例隔离存在明确差异。

## 已确认匹配的基础

- 募集地图 `200080101`、云图 `920010000`、中心塔 `920010100`、唱片图 `920010400`、普通退出图 `920011200`，与北斗一致。见 `OPQConstants.java:19`、`scripts-zh-CN/event/OrbisPQ.js:26`。
- 云图 WZ确实有20个模板ID `2002001` 的云与 `2006000` 的 `eak`祭坛；唱片图确有7个 `2002004..2002010` 箱、`2008006`播放器和`2002011`雕像箱。机器人按模板ID筛选、以运行OID分配目标的思路可复用。WZ路径：`upstream/BeiDou-Server/gms-server/wz/Map.wz/Map/Map9/920010000.img.xml` 与 `920010400.img.xml`。
- 现有机器人有组队邀请接受、分配未认领云/箱目标、导航、模拟挥击、限时等待和返回大厅等动作。对应 `OPQBot.java:275`、`OPQOrchestrator.java:129`、`:199`、`OPQBot.java:352`。这些是可参考的实现片段，不是完整任务验收。

## 需要处理的契约差异

1. **覆盖范围仅两段。** `OPQSharedContext.OPQPhase`只有STAGE_1、STAGE_2；`OPQBot.detectPhaseFromMap`只认识云、塔、唱片和退出图（`OPQBot.java:915`）。北斗setup初始化`statusStg0..8`和大量实例图（`OrbisPQ.js:120`），还要求六块雕像部件、三人站位分配、杠杆组合、种子/黑食人花/Boss、女神和最终领奖。`npc/2013001.js:11、:90、:135、:207、:285`与`npc/2013002.js:54`。两段rush状态机不可描述成全程通关。
2. **完成条件不同。** 全局orchestrator只凭所有bot的`markTaskComplete`将唱片阶段设为完成（`OPQOrchestrator.java:369`）。北斗播放器反应器先把`statusStg3`设0（`reactor/2008006.js:28`），队长再与易克对话开启雕像箱并更新阶段（`npc/2013001.js:121`）。最终`clearPQ`发生在复活女神流程（`reactor/2006001.js:27`、`OrbisPQ.js:297`），领奖在`2013002.js:54`。bot分配工作完成不等于阶段/副本完成。
3. **唱片箱映射明确错配。** 当前按箱子x降序决定唱片物品（`OPQOrchestrator.java:472、:503`）。北斗按模板ID配置原生掉落，见`src/main/resources/db/migration/V1.0.58__reactordrops_insert_data.sql:709`：2002004→4001056，依次至2002010→4001062。WZ最右箱是2002007（x=-357），其原生物品应为4001059，当前代码却按index0生出4001056。不能复用坐标排序作为原生掉落规则。
4. **机关动作与物品并非原生链。** `CustomReactor.hitReactor`只是setState和广播，不执行原生机关脚本（`MapVFX/CustomReactor.java:99`）；OPQ随后手动生成指定物品（`OPQBot.java:440、:681`）。`DropCommands.lootItemListOnFloor`仅从地图移除物品，未入背包（`:227`、`MapleMap.java:1049`）；OPQ用本地计数或lootedRecordItemId，投掷时`DropCommands.botThrowItem/Qty`重新生成物品（`:104、:132`）。未来通用PQ必须复用原生拾取、归属、背包、丢弃与机关触发，不能把该计数和生物品路径当真实收集交付。
5. **全局单例会串任务。** `OPQOrchestrator.java:48`只有一个INSTANCE、context、leaderId，所有OPQBot注册到同一列表（`OPQBot.java:73`、`OPQOrchestrator.java:108`）；不按world/channel/party/eventInstance分区。两队并行、同地图ID不同实例不可复用此形态。
6. **warp不能代替正式入场。** `OPQOrchestrator.followLeaderWarp`直接将bot放到队长Map对象（`:402`），`BotGeneration.placeBotOnMap:158`只移除/增加地图玩家，不注册事件实例。原生`EventInstanceManager.registerPlayer:270`会setEventInstance再调`playerEntry`，`registerParty:394`会按合格成员正式注册。应以原生正式入场为主，不能仅看到队长在某mapId就将任意bot塞进去。
7. **入口等级有一档偏差。** 北斗OrbisPQ是51～70级、5～6人、45分钟（`OrbisPQ.js:26`）；当前OPQ募集生成使用50～70级（`EnvironmentManager.java:1028`），可能产生不合格成员。必须由任务配置核验每个成员，不能只改外观等级。
8. **缺少通用生命周期隔离。** OPQ构造即注册全局orchestrator（`OPQBot.java:69`），该类未覆盖stopScheduledTask，源码未找到外部unregisterBot调用；全局tick只在注册列表空时停止（`OPQOrchestrator.java:90、:319`）。这是需补齐的静态生命周期缺口，未作停服故障复现。
9. `OPQBotDialogue.yaml`路径在构造器`:71`仍带TODO，当前资源中不存在；现有招募及提示较多硬编码英语。完善任务规则时也需接入已完成的统一本地化资源。

## 对通用PQ内核的建议

- 复用“协调器＋团队分配＋每bot执行器”的组织思路，按`world/channel/eventKey/eventInstanceId/runGeneration`建立独立上下文，party/远征/公会队伍通过Roster接口访问。
- 规则包描述入口资格、阶段识别、团队子目标和结束证据；机制求解器负责站位、机关组合、护送、种子等专用逻辑。禁止只用一个全局mapId→stage表。
- 任务动作读取权威EventInstance属性、地图状态、真实物品与NPC结果；不直接设置clear阶段，不伪造任务品，不以BotTaskComplete替代服务器完成。
- 公会成员身份独立于当前任务活动，只有正常退出/失败清理完成才更换任务规则；所有延时动作验证当前runGeneration与eventInstance对象。
- “元数据存在”“规则实现”“集成测试通过”“真人客户端全程通关”分别列状态；目前本OPQ只能列为已有部分动作原型、未完成北斗全流程验收。
