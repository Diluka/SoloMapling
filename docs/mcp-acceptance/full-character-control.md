# MCP 完整控制单角色

里程碑：[MCP：完整控制单角色](https://github.com/Diluka/SoloMapling/milestone/8)；最终范围与联合验收：[#144](https://github.com/Diluka/SoloMapling/issues/144)。本页记录目标与实施顺序，不把 Issue 已建立或候选源码存在视为能力已部署。实际工具、动作、参数范围与源码身份以 `game_capabilities` 和各叶子实际验收记录为准。

## 边界

Agent 沿北斗普通角色的原生资格和资产规则控制专用角色。统一 `CharacterRuntime` 持有原生来源、root scope、唯一 lane 和最终关闭；MCP 外层保留鉴权、owner/profile/session、请求幂等及历史/额度。生产自主策略、全量 PQ 规则、GM/SQL 捷径和任意脚本执行不作为本里程碑交付。

每叶完成可执行输入→原生动作→实际后态→取消/拒绝→声明范围存档闭环；共享 Java 提供者通过后，MCP 仍须单独接线、公告并完成实际验收。每类支持边界逐项列明，一个药品、攻击或NPC样例不能代表整类。

## 实施叶与原父项

| Issue | 最小闭环 | 原父项 |
|---|---|---|
| [#145](https://github.com/Diluka/SoloMapling/issues/145) | MCP模块首环：专用会话暂停、恢复与代次保护 | #140 |
| [#146](https://github.com/Diluka/SoloMapling/issues/146) | MCP模块次环：独立卸载、同宿主重挂与旧引用失效 | #140 |
| [#147](https://github.com/Diluka/SoloMapling/issues/147) | MCP模块收尾：暂停或卸载后的外部根关闭与在途排空 | #140 |
| [#148](https://github.com/Diluka/SoloMapling/issues/148) | MCP移动首环：平地抵达、重定向与立即停止 | #58 |
| [#149](https://github.com/Diluka/SoloMapling/issues/149) | MCP移动扩展：平台跳跃、上下落点与绳梯抵达 | #58 |
| [#150](https://github.com/Diluka/SoloMapling/issues/150) | MCP过门闭环：普通门户、条件脚本与跨图后态 | #58 |
| [#151](https://github.com/Diluka/SoloMapling/issues/151) | MCP战斗首环：近战目标命中、真实伤害与停手 | #58 |
| [#152](https://github.com/Diluka/SoloMapling/issues/152) | MCP远程战斗：投射物、目标范围与弹药消耗 | #58 |
| [#153](https://github.com/Diluka/SoloMapling/issues/153) | MCP魔法战斗：技能施放、MP消耗与原生命中 | #58 |
| [#154](https://github.com/Diluka/SoloMapling/issues/154) | MCP状态技能：增益、治疗与正常取消效果 | #58 |
| [#155](https://github.com/Diluka/SoloMapling/issues/155) | MCP队伍首环：原生创建、独立退出与解散 | #58 |
| [#156](https://github.com/Diluka/SoloMapling/issues/156) | MCP队伍邀请：版本绑定邀请、接受与拒绝 | #58 |
| [#157](https://github.com/Diluka/SoloMapling/issues/157) | MCP队伍管理：转让队长、移除成员与权限后态 | #58 |
| [#158](https://github.com/Diluka/SoloMapling/issues/158) | MCP地面物品与金币：丢弃、拾取和归属闭环 | #58 |
| [#159](https://github.com/Diluka/SoloMapling/issues/159) | MCP消耗品首环：回复、解状态与实际扣量 | #58 |
| [#160](https://github.com/Diluka/SoloMapling/issues/160) | MCP场景型道具：正常回城、实际扣量与落点存档 | #58 |
| [#161](https://github.com/Diluka/SoloMapling/issues/161) | MCP机关闭环：原生命中/接触与真实阶段变化 | #58 |
| [#162](https://github.com/Diluka/SoloMapling/issues/162) | MCP交易首环：原生邀请、会话与无资产取消 | #58 |
| [#163](https://github.com/Diluka/SoloMapling/issues/163) | MCP交易结算：放入资产、双方确认与原生守恒 | #58 |
| [#164](https://github.com/Diluka/SoloMapling/issues/164) | MCP背包槽位移动、交换与原生整栈合并 | #58 |
| [#165](https://github.com/Diluka/SoloMapling/issues/165) | MCP背包原生整理与堆叠归并 | #58 |
| [#166](https://github.com/Diluka/SoloMapling/issues/166) | MCP装备穿脱、槽位交换与原生属性重算 | #58 |
| [#167](https://github.com/Diluka/SoloMapling/issues/167) | MCP普通NPC商店实际目录、购买与关闭 | #65 |
| [#168](https://github.com/Diluka/SoloMapling/issues/168) | MCP普通NPC商店卖出与金币结算 | #65 |
| [#169](https://github.com/Diluka/SoloMapling/issues/169) | MCP飞镖与子弹原生补充、费用及保存 | #65 |
| [#170](https://github.com/Diluka/SoloMapling/issues/170) | MCP仓库实际打开、物品存取与关闭保存 | #65 |
| [#171](https://github.com/Diluka/SoloMapling/issues/171) | MCP仓库金币存取与原生仓库整理 | #65 |
| [#172](https://github.com/Diluka/SoloMapling/issues/172) | MCP原生单点AP分配与基础属性存档 | #58 |
| [#173](https://github.com/Diluka/SoloMapling/issues/173) | MCP原生技能学习升级、技能书索引与SP扣减 | #58 |
| [#174](https://github.com/Diluka/SoloMapling/issues/174) | MCP技能书使用、随机结果与实际消耗 | #58 |
| [#175](https://github.com/Diluka/SoloMapling/issues/175) | MCP普通任务接取、玩家可见进度与原生放弃 | #65 |
| [#176](https://github.com/Diluka/SoloMapling/issues/176) | MCP普通任务交付、合法奖励选择与部分结果核验 | #65 |
| [#177](https://github.com/Diluka/SoloMapling/issues/177) | MCP脚本任务QM开始、对话与正常完成 | #65 |
| [#178](https://github.com/Diluka/SoloMapling/issues/178) | MCP真实职业NPC转职与全部声明副作用存档 | #65 |
| [#179](https://github.com/Diluka/SoloMapling/issues/179) | 共享NPC：提炼真实CM/QM来源、版本及取消并保持现有MCP交互 | #121 |
| [#180](https://github.com/Diluka/SoloMapling/issues/180) | MCP地图NPC：按真实OID打开脚本或特殊UI并确认原生后态 | #65 |
| [#181](https://github.com/Diluka/SoloMapling/issues/181) | MCP NPC普通文本：按type0按钮事实执行Next、Back与OK | #65 |
| [#182](https://github.com/Diluka/SoloMapling/issues/182) | MCP NPC确认：type1 Yes/No与普通type12接受/拒绝 | #65 |
| [#183](https://github.com/Diluka/SoloMapling/issues/183) | MCP通用NPC菜单：完整offer、有界长菜单与当前选项执行 | #65 |
| [#184](https://github.com/Diluka/SoloMapling/issues/184) | MCP NPC文本输入：type2原生编码、取消与结果验证 | #65 |
| [#185](https://github.com/Diluka/SoloMapling/issues/185) | MCP NPC数字输入：type3原生默认值、边界与正常提交 | #65 |
| [#186](https://github.com/Diluka/SoloMapling/issues/186) | MCP角色物品观察：有界分页读取槽位、装备详情与物品身份 | #120 |
| [#187](https://github.com/Diluka/SoloMapling/issues/187) | MCP角色成长观察：原生属性、AP/SP、技能与冷却事实 | #120 |
| [#188](https://github.com/Diluka/SoloMapling/issues/188) | MCP普通任务观察：任务状态、原生计数及完成后态 | #120 |
| [#189](https://github.com/Diluka/SoloMapling/issues/189) | MCP死亡恢复：真实死亡状态、原生普通复活与存档 | #58 |
| [#190](https://github.com/Diluka/SoloMapling/issues/190) | MCP中断恢复：原请求核对、原生离线清理与同CID重新打开 | #57 |
| [#191](https://github.com/Diluka/SoloMapling/issues/191) | MCP NPC样式选择：type7真实选项、费用及外观存档 | #65 |
| [#192](https://github.com/Diluka/SoloMapling/issues/192) | MCP NPC问答：type6实际题面、合法输入与结果 | #65 |
| [#193](https://github.com/Diluka/SoloMapling/issues/193) | MCP次元之镜：type14实际目录、资格与跨图后态 | #65 |
| [#194](https://github.com/Diluka/SoloMapling/issues/194) | MCP正常换频道：原生来源交接、旧引用失效与同CID连续 | #58 |
| [#195](https://github.com/Diluka/SoloMapling/issues/195) | MCP长菜单观察：完整37项offer、有界分页与版本一致性 | #113 |
| [#196](https://github.com/Diluka/SoloMapling/issues/196) | MCP付费出行：800金币扣费、射手村抵达与存档 | #113 |
| [#197](https://github.com/Diluka/SoloMapling/issues/197) | MCP脚本道具：原生扣量、当前CM回复与结果存档 | #58 |

已有 [#76](https://github.com/Diluka/SoloMapling/issues/76) SocialBot 回复、[#112](https://github.com/Diluka/SoloMapling/issues/112) 礼包准备及 [#113](https://github.com/Diluka/SoloMapling/issues/113) 出行继续复用，均纳入里程碑。共享 #116 及其子项保留原树/里程碑，用原生 `blocked_by` 连接实际提供者。

## 推进与依赖

先推进 #145 暂停/恢复→#146 卸载/重挂→#147 模块不可用时根关闭。共享事实 #135 和纯包解码 #136 可按实际需要并行推进；随后用 #148 平地移动和 #150 门户建立下一个实际闭环，逐项扩展战斗、物品、组队、任务与交易。推荐顺序不自动成为技术阻塞。

- 普通移动不等待 #140 整父，远程/魔法不依赖近战工具。
- #121 消费 #136，#122～#129 消费 #135；不统一等待完整 #120 或 Social/Bot 全集。
- #195 完整长菜单观察可独立于资金和付费出行交付；#183 通用菜单消费 #195，#196 付费出行消费 #195 和 #112。
- AP/SP、普通任务和消费品仅连接其实际读取/原生执行前置；不因排期等待全部 NPC 类型。
- 次元之镜和换频道沿各自原生路由，不把门户工具当成其技术前置。

## 开发、验收与部署

根工作树 `sources/SoloMapling` 固定为 `main`，保持主分支与部署来源，不放功能WIP。每个开发工作树先快进对齐最新 `origin/main`，利用已合入业务地图定位边界，再以当前源码、测试和真实原生证据确认。候选只在独立副本验收；通过并正常合入主分支后，根工作树 `git pull --ff-only`，执行标准 `./build.sh`，备份后从 `main` 部署。

每叶分别记录源码/子模块、测试/CI、隔离、实际部署和真实客户端范围；未验条件保持开放。未知副作用保留原请求/检查点，取消、真实退出和资源释放分别核对，不能盲重放或用 Future/operation 终态假报排空。

## 原生限制与最后覆盖核对

普通背包移动忽略 quantity，不支持任意数量原地拆栈；本里程碑验证整栈移动/交换/合并/整理，不用drop/pickup或直接库存写入伪装拆栈。NPC type7/6/14、普通频道交接与死亡/中断恢复已显式拆叶。

特殊商城、宠物、骑乘、召唤等在最终原生能力矩阵中逐项核定适用范围与后续处置；未盘点或未实现的项不得写成已交付。现有256条进程历史及重启后幂等边界继续明确，不为长流程静默遗忘旧ID或自动重放。命名recipes与多角色协作是后续消费者，不反向阻塞单角色能力本身。
