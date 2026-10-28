# 北斗物品与经济覆盖依据

本页按固定上游北斗 `1.12`、提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b` 的源码建立责任边界，路径均以 SoloMapling 仓库为根。它描述源码支持的责任，不声明本地配置已经启用该业务，也不替代交易、商城或客户端玩法验收。

## 责任与边界

| 项目 | 证据支持的边界 |
| --- | --- |
| 结果与受益人 | 玩家能够持有、使用、交换、购买或寄存物品，店主能够上架、售出并领取收益。 |
| 状态与规则所有者 | 物品实例和栏位由 `Inventory`、`Item`、`InventoryManipulator` 管理；金币由 `Character` 记录，点券及现金仓库由 `CashShop` 记录；直接交易报价、商店库存和寄存资产由各自原生经济对象管理。 |
| 生命周期 | 取得或拾取 → 持有/整理/穿脱 → 使用、强化、出售或寄存；直接交易还有邀请、报价、确认、拒绝/取消及结算；店铺还有营业、售罄、店主关闭、超时关闭及领取；商城还有购买、赠送和现金仓库领取。 |
| 协作者 | 角色域决定角色身份与正常存档；世界域提供当前地图和掉落对象；战斗与任务域决定其奖励的取得条件，经济域处理奖励落入资产容器及后续流转；社交域拥有组队关系，金币拾取可读取同地图队伍成员进行分配。 |
| 边界挑战 | 将物品、钱包、交易和保管资产放在同一经济域，是因为它们共同维护资产归属及流转结果；角色的登录身份、战斗收益资格、任务完成与组队成员资格不归经济域。没有按 handler、service、mapper 拆分业务域。 |

## 独立源码入口与地图覆盖

| 场景或状态生命周期 | 决定性源码（`upstream/BeiDou-Server/gms-server/src/main/java/org/gms/` 下） | 地图概念或流程 |
| --- | --- | --- |
| 物品实例、背包、已装备栏位、堆叠与容量 | `client/inventory/Inventory.java`、`Item.java` | `beidou.economy.inventory` |
| 金币上下界与各类点券 | `client/Character.java#gainMeso`、`server/CashShop.java#getCash`、`gainCash` | `beidou.economy.wallet` |
| 物品移动、穿戴、卸下和丢弃 | `net/server/channel/handlers/ItemMoveHandler.java#handlePacket`、`InventoryManipulator.java#equip`、`drop` | `beidou.economy.inventory-change` |
| 地图掉落的距离、归属、已拾取状态、空间、金币分配与点券卡 | `net/server/channel/handlers/ItemPickupHandler.java#handlePacket`、`client/Character.java#pickupItem` | `beidou.economy.pickup`；读取 `beidou.world.scene` |
| 使用药水、解除异常、回城和商城道具 | `UseItemHandler.java#handlePacket`、`UseCashItemHandler.java#handlePacket` | `beidou.economy.consume-item`；效果的地图/战斗结果仍由对应领域实现 |
| 卷轴要求、成功、失败及诅咒损毁 | `ScrollHandler.java#handlePacket` | `beidou.economy.enhance-equipment` |
| NPC 买卖、支付条件与飞镖弹药补充 | `server/Shop.java#buy`、`sell`、`recharge` | `beidou.economy.npc-shop` |
| 邀请、报价、双方确认、接收限制、低等级限额与交易税 | `server/Trade.java#inviteTrade`、`completeTrade`、`getFee` | `beidou.economy.direct-trade`、`trade-offer`；`direct-trade.flow` |
| 主动取消及校验失败后的资产处理 | `server/Trade.java#unlockAndCancel`、`cancelTrade`、`cancel` | `beidou.economy.cancel-trade`；`direct-trade.flow` |
| 个人店铺上架、访客购买、售罄及关闭 | `PlayerInteractionHandler.java#handlePacket`、`server/maps/PlayerShop.java#buy`、`closeShop` | `beidou.economy.player-shop` |
| 雇佣商人销售、库存、离线收益、取回物品、关闭持久化 | `server/maps/HiredMerchant.java#buy`、`withdrawMesos`、`takeItemBack`、`forceClose`、`saveItems` | `beidou.economy.hired-merchant`、`assets-in-custody` |
| 仓库等级、GM限制、费用、容量与唯一物品检查，物品金币存取和关闭 | `client/processor/npc/StorageProcessor.java#storageAction`、`server/Storage.java#saveToDB` | `beidou.economy.storage`、`assets-in-custody` |
| 商城资格/余额/容量、单品礼包、赠送、现金仓库取放 | `CashOperationHandler.java#handlePacket`、`ensureCashInventoryCapacity`、`server/CashShop.java#gift`、`save` | `beidou.economy.cash-shop`、`assets-in-custody`；`cash-shop.flow` |
| MTS 入场开关、固定价格挂售、搜索、购物车、撤售和转移领取 | `EnterMTSHandler.java#handlePacket`、`MTSHandler.java#handlePacket` | `beidou.economy.mts` |
| 制造、怪物结晶转换、装备分解、材料金币与配方资格 | `client/processor/action/MakerProcessor.java#makerAction`、`getCreateStatus` | `beidou.economy.craft` |
| 快递寄送费用、跨账号收件、到期领取、归属与分支接收条件及过期清理 | `client/processor/npc/DueyProcessor.java#dueySendItem`、`dueyClaimPackage`、`runDueyExpireSchedule` | `beidou.economy.delivery`、`assets-in-custody` |
| 兑换码有效性、期限和领取记录，物品金币点券发奖 | `CouponCodeHandler.java#getNXCodeResult`、`handlePacket` | `beidou.economy.redeem-code`、`coupon-codes` |
| 经济协作入口与具体限制归属 | `PlayerInteractionHandler.java#handlePacket`、`Trade.java#completeTrade`、`Shop.java#buy` | `beidou.economy.trade-entry`、`exchange-rules` |

## 支持的停止点与限制

- `MTSHandler` 的求购、竞拍上架和立即竞拍购买等分支为空；地图只保留已经有实现的固定价格市场，不把空分支描述为可用拍卖业务。`use_mts=false` 时入口改开脚本中心，该脚本对话由任务与 NPC 域拥有。
- `Trade.cancel` 在双方均锁定的异常分支不会再次返还资产；地图与流程区分正常未交换报价的返还，不承诺任何取消都必定回滚。正常接收校验失败先经 `unlockAndCancel` 解锁再取消。
- `Character.gainMeso` 会将不足扣减钳至 0。它是余额变更规则，不能替代调用方的足额校验，也不能据此保证所有脚本出行都已正确收费。
- 每种经济路径有各自的锁、校验及持久化调用。本轮是静态业务建图，没有证明多角色数据库事务原子性、故障恢复、全部反作弊规则或真实客户端可用性。
- 制造已覆盖配方资格、材料/费用消耗、结晶转换和分解；具体配方与随机属性公式保留源码细节。宠物召唤、喂养、饥饿和服务规则由独立 `beidou.pets` 域拥有，宠物对应的现金物品本体仍归背包。
- 兑换码的 `getNXCodeResult` 先登记领取者，`handlePacket` 后续才检查现金仓库容量；地图没有把该路径描述为容量失败必不消耗领取资格。快递建包、扣款、装物及领取删除的持久化顺序也不等于已证明跨步骤原子。
- 快递领取的 `canHoldMeso` 位于 `dpItem != null` 分支中；纯金币包裹直接执行 `gainMeso` 的余额钳制，不能把含物品包裹的拒绝条件推广到所有包裹。
- 所有商城特殊道具、每条制造配方和装备属性公式没有逐一建节点。后续特定功能修改需要继续确认当前实现。
- Solo 的 NX 码签发及教程奖励可写入同一 `beidou.economy.coupon-codes`；不因调用方不同复制数据归属。
- 本轮没有查询或写入任何游戏数据库，没有运行会改变游戏状态的经济操作。
