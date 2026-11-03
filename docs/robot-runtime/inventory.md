# 基础循环的背包事实与出售规则

`soloMapling.game.inventory` 为狩猎拾取、商店和练级日程提供同一份背包值观察与规则。原生角色的 `Inventory` 保持唯一库存来源，读取由调用者在该角色已有 lane 中执行；本包不创建执行作用域、队列或长期库存账本。

## 读取背包与候选容量

`InventoryFacts.read(character)` 返回 `InventorySnapshot`，固定按 `EQUIP / USE / SETUP / ETC / CASH` 输出五类普通背包。每包包含原生槽位上限、可用槽位，以及按槽位升序排列的物品ID、位置和数量。装备中的物品不属于普通装备包。观察值不保留原生 `Item` 引用，之后应重新读取来确认拾取或出售后态。

`InventoryFacts.capacity(character, item)` 接受当前实际、正数量的普通候选物品，返回类型化 `ItemCapacity`：

| 字段或结果 | 含义 |
|---|---|
| `freeSlots` | 候选对应背包当前可用槽位 |
| `newSlotsRequired` | 现有兼容堆叠之后还需的新槽位数 |
| `AVAILABLE` | 当前物理容量允许容纳该候选，原生预检也未拒绝禁拾资格 |
| `NO_SPACE` | 需要的新槽位超过当前空位 |
| `PICKUP_RESTRICTED` | 原生预检拒绝重复持有受限物品 |

堆叠上限使用原生 `ItemInformationProvider.getSlotMax`，合堆条件与原生 `addFromDrop` 相同，比较物品ID、owner、flag；装备、可充能弹药与宠物分别使用原生的新槽规则。北斗1.12的通用 `checkSpaceProgressively` 只比较 owner，因此本观察补齐其与实际入包的 flag 差异。容量事实不能替代距离、归属、任务及最终原生入包结果；拾取动作继续按实际地物消失与库存变化确认效果。

## 容量触发与出售候选

`CapacityRule(inventoryTypes, minimumFreeSlots)` 关注指定普通背包。任一关注包的空位数小于等于阈值时，`evaluate(snapshot)` 返回触发的包和 `minimum_free_slots_reached`；未触发时返回 `capacity_available`。本轮出售后再次读取并评估，确认已经腾出配置所需空间。

`SaleRules(allowedItemIds, retainedItemIds, retainedQuantities)` 根据当前观察生成 `List<SaleItem>`：

1. 仅选择允许出售的物品ID。
2. 整项保留ID优先于允许出售和数量规则。
3. 保留数量按同一物品ID的总量计算，按观察中稳定的包、槽位顺序保留，超出的实际数量形成候选。

`SaleItem(inventoryType, slot, expectedItemId, quantity)` 绑定具体槽位、期望物品ID与正数量，交给商店动作再次沿原生资格实际出售。候选计算不扣物、不改金币，也不裁决价格或最终可售资格。

本能力的源码测试及实际拾取、卖出、普通角色保存和环境来源验收，分别见 #293、#294、#295 与基础日程 #292 的分层记录。
