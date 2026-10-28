# 游戏动作

## 从实际接口取得参数

先核对 `game_capabilities.actions` 和对应限制。`game_act` 的参数分支由 [server.py](../../../tools/acceptance-mcp/src/solo_acceptance_mcp/server.py) 中的参数模型及工具 schema 提供；需要精确槽位、类型或数值约束时直接查该分支，不维护第二份参数清单。

每次动作使用实际会话、会话代次、唯一请求 ID，以及最近 actor 观察取得的 `expected_context`。把请求与完整参数保存到本次任务记录，结果未知时可以原样恢复。

## 聊天和 NPC

普通聊天使用 `chat`。发送前保存 actor 包游标，发送后查询 operation，再读取新包。自身 CID、原文和新序号匹配的回声可证明自身广播；机器人回复另按目标身份和后续包确认。按能力公告的原生门槛及缓存时钟步进留足间隔，不靠短墙钟等待和重复发送试探冷却。

NPC 中心使用 `npc_center_open` 的原生 UI 入口。回复或取消前读取当前 actor 的对话状态和 `dialogue_version`，核对当前完整、允许的选项后使用 `npc_reply`。正文前缀中出现的编号不等于当前可答选项。回复后重新观察后继对话；同一版只消费一次。

`npc_cancel` 处理已打开的对话；`operation_cancel` 请求取消某次工作，两者分别使用。对话取消以原生 CM/QM 清理结果确认。需要调试其他 NPC 或脚本回调时，沿[脚本调试技能](../../solomapling-script-debug/SKILL.md)查真实入口。

源码入口：[NativeDialogueTracker](../../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/NativeDialogueTracker.java)、[ActorControlService](../../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/ActorControlService.java) 的 `finishChat`、`finishNpc` 及相关动作分支。聊天与 NPC 的原生执行分别沿 [game/action](../../../beidou-integration/src/main/java/soloMapling/game/action) 和原生适配实现查阅。

## 移动和战斗

`move` 执行有界方向步行，`stop` 停止当前移动。先观察当前位置与地形，使用当前能力支持的路段；完成后核对真实位置和停止结果。当前动作不提供自主导航，不能把一段步行解释成已经到达任意目标。

用户要求“向右走一秒然后停下”时，使用当前 schema 中的方向和时长参数提交一次移动；查询原 operation 到实际结果，必要时提交 stop。保存实际位移、停止原因及完成时长事实，提前停止时按实际报告。

`attack` 选择当前场景的真实怪物 OID。当前基础近战政策及距离、武器范围从 capabilities 和 [NativeBasicActions](../../../beidou-integration/src/main/java/soloMapling/game/action/NativeBasicActions.java) 查阅。命中、MISS、伤害、击杀分别以实际事实判断，攻击广播只证明动作派发。

移动停止与取消的结算含义见[结果与恢复](recovery.md)。

## 背包、地物和装备

操作前观察真实槽位、物品及地物 OID，按当前 schema 选择动作：

- `inventory_move`：背包内整槽移动、交换或合堆，核对来源／目标与数量守恒。
- `pickup`、`drop`：核对地物身份、归属、库存前后量，以及丢弃结果是生成地物还是原生消失。
- `use_item`：普通 HP/MP 药剂的消耗量与实际恢复量分别确认。
- `equip`、`unequip`：核对装备和背包位置、冲突部位及属性更新。

实际副作用与支持范围查 [NativeInventoryActions](../../../beidou-integration/src/main/java/soloMapling/game/action/NativeInventoryActions.java)、[NativeBasicActions](../../../beidou-integration/src/main/java/soloMapling/game/action/NativeBasicActions.java) 和[既有基本动作实现说明](../../../docs/shared-game-foundation/basic-control.md)。满血消耗不能报告成正恢复，库存减少不能单独证明已生成可拾回地物。

需要证明存档时，正常关闭并同 CID 重开，再核对具体槽位、数量或属性。已有通用保存标记不扩大为背包全量核验结论。
