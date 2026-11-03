# 普通NPC商店动作

`ShopActionModule` 类型为 `shop`，实现 `ActionModule`。模块只处理当前角色的普通NPC商店交互，控制模块负责前往地图、走近NPC和生成出售清单。

## 入口

| 共享函数 | Java操作 | 完成事实 |
|---|---|---|
| `shop.open({npcId})` | `open(ShopOpenRequest, context, work)` | 在当前实际地图解析NPC，原生商店已绑定，返回本实例单调增加的`interaction_id`。 |
| `shop.sell({items})` | `sell(ShopSellRequest, context, work)` | 逐项原生出售已退出，返回实际物品、数量、金币前后态及已发生效果。 |
| `shop.close({})` | `close(ShopCloseRequest.NORMAL, context, work)` | 自己拥有的商店已关闭，对应资源和受管载体实际退出。 |

```javascript
modules.call("shop.open", {npcId: 1011100});
modules.call("shop.sell", {items: [
    {inventoryType: "ETC", slot: 1, expectedItemId: 4000000, quantity: 1}
]});
modules.call("shop.close", {});
```

脚本清单由共享入口解析一次，Java调用直接使用同一个`ShopSellRequest(List<SaleItem>)`。`SaleItem`沿背包事实模块提供的`inventoryType/slot/expectedItemId/quantity`，每次最多32项。原生背包和金币保持唯一状态来源。

## 原生规则与结果

打开消费实际NPC的`sendShop → Shop.sendShop`，和原生普通商店及`NPCConversationManager.openShopNPC`共用原生绑定和发包实现。该操作保留调用方的CM和QM。当前地图NPC必须对应已登记商店；具有原生NPC脚本文件的目标返回`shop_scripted_npc_unsupported`，检测只读原生语言回退目录的文件元数据，不执行脚本顶层。

本操作的接近范围声明为NPC周围100px，控制先导航到NPC实际位置。该范围是此操作的约束；原生NPC点击处理器本身没有该距离检查。存活、监狱地图、首次地图过渡和原生NPC冷却资格在动作边界检查。`NativeShopActions.cooldownReady`按原生“上次NPC结束时间+配置间隔”供动作和控制观察共用。

出售和关闭使用原生`NPCShopHandler`，售价、物品移除和金币上限沿`Shop.sell`与`Character.gainMeso`。派发前核对当前实际槽位的物品ID和数量。原生商店对可充能弹药会改为整栈出售，因此只接受与实际整栈相等的数量，部分数量返回`NATIVE_WHOLE_STACK_REQUIRED`。原生出售内部使用byte槽位，超出127的槽位明确拒绝。

`OPENED/ALREADY_OPEN`表示有可继续使用的本模块交互；`SOLD`表示整个清单逐项确认，`PARTIAL`保留已确认扣物及金币结果；未确认售出时返回`REJECTED`或`CANCELLED`。每项保留实际物品ID、数量、金币前后态、派发及处理器错误，处理器返回本身不构成出售成功。原生价格为零或金币封顶仍按真实结果表达。

## 交互所有权与停止

一次打开由本模块原scope中的受管`ModuleTask`持有，并登记精确交互资源；原生命令仍只进入角色已有lane。独立脚本返回或关闭不结束商店，后续脚本可继续卖出或关闭。

关闭信号、模块停用和取消唤醒同一个交互载体，使用该资源的cleanup work完成原生关闭，再确认释放。载体等待资源和短命令真正退出后结束。调用方停止后，控制可通过预先持有的提供者实例调用`close(expectedInteractionId)`等待精确旧交互收尾；ID不匹配返回`shop_interaction_changed`，不关闭后继交互。其他来源打开的商店不被本模块接管。

原生打开已发生后取消或出错，模块先收尾自己的交互再返回；部分卖出后取消保留已扣物、金币与安全运行体事件。未确认关闭的资源保留其清理责任，不能提前宣称卸载或根关闭完成。

## 首个场景与验收边界

基础循环使用地图`100000102`中的实际NPC `1011100`，身份、位置与店铺仍从原生当前场景读取。源码业务回归、隔离原生交互、主分支部署及真实客户端证据分别登记于#295/#292；材料准备不计作商店卖出效果。
