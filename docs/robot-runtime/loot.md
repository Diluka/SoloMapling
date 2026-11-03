# 普通地物拾取

`LootActionModule` 是类型 `loot` 的有限动作模块。调用者给出当前实际场景的地物OID，模块按顺序接近并拾取；模块装载本身不会开始扫描或拾取。已交付的 `NativeItemActions` 继续承担真实普通物品拾取，导航消费现有 `navigation.moveTo`。

```javascript
modules.call("loot.collect", {
    object_ids: [1000000042, 1000000043],
    timeout_ms: 30000
});
```

Java消费者通过同一声明调用：

```java
context.call(LootActionModule.COLLECT, new LootRequest(objectIds, 30_000), currentWork);
```

`object_ids` 为1～32个不同的正整数，重复OID按首次顺序合并；`timeout_ms` 为整次接近和拾取的预算，范围1～120000ms，默认30000ms。每个对象取当前真实MapItem、库存和原生归属。需要接近时先执行场景内导航，实际进入背包仍通过原生 `ItemPickupHandler`。

## 结果与执行归属

结果包含总体 `outcome`、`reason`、`elapsed_ms`、逐对象 `items` 及确认的 `collected_objects/collected_quantity`。`COMPLETED` 表示本轮对象处理结束；只有逐项 `COLLECTED` 计入确认入包摘要。各项返回object_id、物品ID、地物数量、观察到的库存数量增量、原生结果与派发状态，不重复输出完整库存快照。

- 物体已消失、尚未落稳、归属拒绝、未支持的物品和拾取限制分别说明；导航失败为该对象的 `UNREACHABLE`。
- `CAPACITY_FULL` 结束本轮收集并保留此前已入包事实，供控制模块读取真实背包并决定回城。
- 原生派发后结果不确定时返回 `INDETERMINATE`，保留观察到的增量，确认入包计数仅包含已证实的对象。本轮不自动重放该动作。
- 同一模块已有收集调用时返回 `BUSY`。取消、期限、暂停、卸载或场景变化结束当前调用；已经发生的入包效果保留。

目录调用运行于拾取提供者的原scope/work。每次原生读取与拾取使用继承本调用取消关系的新短work；`NativeBasicActions.submit`仅向原角色lane提交一次。调用等待原生结果及短work真实结算，导航也需实际退出后才返回。

原生规则包含至少400ms的地物年龄，以及原生归属、任务和容量资格；此版本没有相邻两次拾取的300ms冷却。动作在对象间使用250ms节拍，这是沿旧有机拾取范围的调度策略。

## 狩猎中的拾取阶段

`HuntingControlModule` 装配可用 `loot` 后，在一次交战结束或真实 `WAIT` 空档中安排有限收集。首次开始或返场重新开始时，已有可交战目标先走原选怪与交战；收集不会抢在首次决策前清理旧地物。

狩猎扫描沿旧 `GrindLoot` 的近层策略：900px范围、垂直差不超过120px、至少1500ms落稳时间，按距离和OID稳定选择最多32个普通可归属地物。执行仍交给拾取动作，原版V1的移动和移除逻辑不作为真实入包实现复用。原生延迟生成的掉落可在后续交战间隙或等待节拍再次发现；没有可用拾取模块时保留原独立狩猎行为。

Hunting快照增加 `loot_calls`、`collecting`、`collected_objects`、`collected_quantity`、`loot_outcome`、`loot_reason`；最后两项描述最近收集结果，不替代角色背包。狩猎停止等待当前收集、接近移动及原生短work真实结束，保留已确认入包数量。收集结果不确定时，本次狩猎以 `loot_indeterminate` 结束，交由外层处理。

组合控制持有开始结果的 `activity_id` 和对应Hunting实例，可用Java `stop(expectedActivityId)` 精确停止自己启动的活动。当前活动已变化时返回 `ACTIVITY_CHANGED` 与真实当前快照，不停止后继活动；无参stop与脚本入口继续停止当前活动。
