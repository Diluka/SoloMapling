# 上游社交与组织：责任与源码覆盖

本记录支持 `social.yaml` 的初始业务划分。证据来自只读北斗 1.12 子模块 `75a86268738ca8845e06ea9d59ec36d15b22c91b`；这里描述源码支持的规则，不表示本次执行过游戏客户端、数据库或部署验收。下列源码均相对于 `upstream/BeiDou-Server/gms-server/src/main/java/org/gms/`。

| 责任 | 源码支持的边界 |
| --- | --- |
| 结果与受益人 | 玩家能够向正确接收范围沟通，建立及结束好友、队伍、公会联盟、家族和伴侣关系；任务系统消费队伍关系，Solo 通过原生聊天接口接入。 |
| 所有状态与决策 | 社交域拥有名单、组织成员/职级、谱系、声望权益和伴侣关系；各入口决定邀请有效性、人数、资格、权限及接收范围。 |
| 生命周期 | 聊天发送/拒绝；邀请创建、接受、拒绝或失效；好友添加、分组与删除；队伍创建、加入、离开、踢人、换队长及解散；公会/联盟创建加入、管理及退出解散；家族创建合并、分离及权益消耗；求婚答复、取消、关系解除与婚礼赠领。 |
| 协作者与限制 | 角色身份和在线注册属于 identity；实际地图和事件实例属于 world；物品、背包与金币属于 economy；任务/活动决定组队关系变化引发的实例结果。 |

| 独立发现的入口或状态责任 | 决定性源码 | 地图概念 / 流程 |
| --- | --- | --- |
| 地图发言、200ms原生时钟限频、长度、命令、斜杠、禁言及隐身范围 | `net/server/channel/handlers/GeneralChatHandler.java#handlePacket` | `beidou.social.general-chat`、`chat`、`chat-rules`；`general-chat.flow` |
| 私聊、玩家位置查找、目标离线及GM可见性 | `WhisperHandler.handlePacket`、`handleFind`、`handleWhisper` | `beidou.social.direct-chat` |
| 好友/队伍/公会/联盟聊天与配偶聊天 | `MultiChatHandler.handlePacket`、`SpouseChatHandler.handlePacket` | `beidou.social.group-chat`；读取相应社交关系 |
| 三人会话的建立、有效邀请、加入、发言和退出 | `MessengerHandler.handlePacket`、`net/server/world/Messenger.java` | `beidou.social.messenger` |
| 好友申请、容量、离线待确认、接受后的双向持久化、分组与删除 | `BuddylistModifyHandler.handlePacket`、`client/BuddyList.java`、`client/BuddylistEntry.java` | `beidou.social.friends`、`friend-list` |
| 建队、邀请、拒绝、接受时的二次资格复核、离队和解散 | `PartyOperationHandler.handlePacket`、`DenyPartyRequestHandler.handlePacket`、`net/server/world/Party.java#createParty/joinParty/leaveParty/expelFromParty` | `beidou.social.party-membership`、`party`；`party-membership.flow` |
| 队长招募、等级区间、职业/邻图候选、满员收束、超限转长期等待及重入 | `PartySearchStartHandler.handlePacket`、`PartySearchCoordinator.runPartySearch` | `beidou.social.party-search`；不把每次匹配失败写成永久结束 |
| 公会创建资格与伙伴确认、邀请加入、退出踢人、职级、公告徽章及解散 | `GuildOperationHandler.handlePacket`、`net/server/guild/Guild.java#disbandGuild` | `beidou.social.guild`、`guild-state` |
| 公会联盟邀请、容量、权限、退出及解散 | `AllianceOperationHandler.handlePacket`、`net/server/guild/Alliance.java#disbandAlliance` | `beidou.social.alliance`；组织归属统一由 `guild-state` 导航 |
| 家族邀请、代数与等级条件、创建/吸收、分离费用及声望扣减 | `FamilyAddHandler.handlePacket`、`AcceptFamilyHandler.handlePacket`、`FamilySeparateHandler.handlePacket` | `beidou.social.family`、`family-tree` |
| 家族声望权益、重复使用限制、重聚/召集地图限制及个人/组队增益 | `FamilyUseHandler.handlePacket`、`useEntitlement` | `beidou.social.family-benefits` |
| 求婚资格、答复、取消、关系解除、婚礼邀请和礼物赠领 | `RingActionHandler.sendEngageProposal/handlePacket/breakMarriageRing`、`WeddingHandler.handlePacket`、`server/Marriage.java` | `beidou.social.marriage`、`marriage-state` |

划分采用一个社交与组织域，是因为沟通与社交成员关系围绕相同玩家协作结果，并通过明确的能力和状态概念保持边界。金币、背包、地图和任务实例保留原所有者，不因社交操作调用它们而复制定义。联盟当前与公会共用组织状态导航；未来若联盟自身规则成为独立维护重点，可在此域内细分数据概念。

聊天限制按入口分别记录：不能从普通聊天具有禁言/长度检查推导 Messenger 或配偶聊天也具有相同检查。婚礼实例使用事件协议，但婚姻关系与礼物规则仍由社交域决定，物品本体属于经济域。这里没有逐一列出表情、宠物发言、管理员专用聊天、公告及所有客户端展示包；它们属于相关能力的展示入口或管理域，不另立社交业务域。源码中的配置开关和脚本可用性不等于当前部署已启用或实际客户端可完成全部玩法。
