# 原生背包移位

对应 [#164](https://github.com/Diluka/SoloMapling/issues/164)，由 [#200](https://github.com/Diluka/SoloMapling/issues/200) 组织 MCP 基本操作验收。本轮先交付单一背包的正槽位移位，实际构建、隔离、CI 与部署状态分别以 Issue 证据为准。

## 能力归属

`NativeInventoryActions` 位于 `soloMapling.game.action`，是角色运行体供控制模块直接消费的中立 Java 能力。模块通过 `CharacterRuntime.ModuleContext.prepareNative("inventory", ...)` 取得原宿主的 scope、Grant、角色引用与唯一 lane，再调用 `submitMove` 一次。不增加执行器、第二队列、背包档案或 MCP 私有动作后端；其他可信 Java 模块也可直接使用同一能力，无须经过 Python、HTTP 或 MCP。

`CharacterRuntime` 负责角色资源、控制权和工作结算，`RoleBinding` 保留来源身份、实时事实及最终释放职责。具体背包规则由北斗执行，MCP 保留鉴权、profile/session、请求历史、幂等和结果投影。宿主的 `NativeActionFact` 仅记录角色 ID、世界代次、固定原因与结果，不保存物品、槽位或聊天正文。

## 参数和原生规则

`NativeInventoryMoveAction(inventoryType, sourceSlot, targetSlot)` 只接受 `EQUIP/USE/SETUP/ETC/CASH` 和不同的 `1..127` 正槽位；执行时再核实际背包上限和源槽物品。

MCP 在既有 `game_act` 中使用：

```json
{
  "action": "inventory_move",
  "parameters": {"inventory_type": "ETC", "source_slot": 1, "target_slot": 2}
}
```

同时必须提供既有 `session_id`、`expected_generation`、`request_id` 和最近观察得到的 `expected_context`。同请求 ID 改类型、槽位或上下文会冲突；超时后查询原 operation，不自动重发。

执行沿原生 `ItemMoveHandler`，整槽移动、交换或合堆的行为以原生物品规则为准，不提供拆分数量。该 handler 还支持穿脱和丢弃，但本入口在进入原生代码前拒绝零槽位、负槽位和同槽位，不借此开放 #158/#166。原生 300ms 限频及缓存时钟保持原样，连续验收应至少留出限频加缓存时钟步进的间隔。

## 结果、取消和存档

在同一原生背包锁内复制两槽前态，派发原生处理器，再读取两槽后态，以精确物品身份、位置及数量关系核验原生移动、交换或合堆。`InventoryMoveResult` 区分未派发、已派发、成功、失败、取消和结果未知。handler 返回 void 或无异常不构成成功；无变化据实返回失败，派发后读取或归因不完整则保留未知。

排队取消不释放尚未结算的物理槽；原生变化已经发生后取消不撤回。结果完成与 work 实际结算分开，MCP 仍只在后者归还 ACTION 额度，并给首次关闭保留容量。终态不保留原生对象或输入闭包。

actor 观察从同一根来源实时读取五类普通背包，提供槽位上限、物品槽位/ID/数量及完整性标记。观察不是另一份库存账本，也不意味着五类背包之间有单一原子快照。

现有 `actor_close.persisted_state_verified` 不含背包字段。背包存档必须单独执行正常退出、同 CID 重开并核对具体物品槽位与数量；不能用既有保存标志替代。

## 验证范围

定向测试覆盖参数边界、真实原生内存背包变化、限频、排队取消、重复提交、授权/代次与结果归约；MCP 协议验证严格整数和额外字段拒绝、幂等恢复及能力公告。

隔离验收使用专用普通角色已有的新手指南，先观察确认后执行槽1→空槽2，检查重复请求和参数冲突，正常关闭并重开核存档，再移回槽1并关闭。聊天和已审 NPC 路径分别回归。移动、攻击、物品使用、拾取丢弃、穿脱及自主行为继续由各自任务交付，不从本片测试推导完成。
