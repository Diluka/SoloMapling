# 准备与基线

## 确定可验目标

先确定本次要证明的行为，例如“同场景点名后收到目标回复”，再记录触发条件和观察依据。场景目录和对象列表从真实观察取得；场景中的 `id/name/is_bot` 可确认注册机器人身份，具体行为类型还需由当前场景准备记录或实际对象信息确认。名字、外观及 `is_bot=true` 本身不证明它是 SocialBot。

使用 [游戏交互技能](../../solomapling-game-interaction/SKILL.md) 取得实际能力和当前 world、scene、actor。只读问题先观察；需要玩家输入时使用专用角色。记录目标 CID、名称、实际 scene_ref、世界代次及必要位置，避免把同地图 ID 的不同实例混用。

## 先读相应规则

待测行为的现有规则直接查源码，避免把另一种机器人的交互方式套用到当前目标：

- [GameplayHooks](../../../beidou-integration/src/main/aspect/soloMapling/beidou/GameplayHooks.aj)：普通聊天完成原生广播后进入机器人消息链。
- [Dispatcher](../../../src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java)：同地图名称匹配、已有会话及无名称后续消息的分发。
- [SocialBot](../../../src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java)：`getVariant`、`onFirstInteraction`、`handleDialogueChoice`、会话超时和反刷屏状态。
- [SocialLlmConfig](../../../src/main/java/soloMapling/ArtificialPlayer/LlmSystem/SocialLlmConfig.java) 与 [SocialLlmService](../../../src/main/java/soloMapling/ArtificialPlayer/LlmSystem/SocialLlmService.java)：模型启用、期限和回退路径。

SocialBot 有单次回复和交互两种变体，后者才有持续话题选择。等待时机依据当前调度和执行路径，不把刚发送后的空观察直接判为不回应。反复点名可能触发忙碌或交互退化；先核对当前会话及先前测试行为，保留这些前置事实。

## 准备最少条件

准备前记录本次实际需要比较的字段：场景与对象身份、物品槽位和数量、相关角色状态及当前包游标。准备命令或原生脚本按兄弟技能查询真实实现和普通角色权限，确认实际结果。独立记录准备增量，不把它归为待测机器人产生的收益。

只修改任务所需的专用目标。实验、取消和故障探针使用隔离环境；实际服务使用已授权的正常流程。准备过程中保留原请求 ID 和执行结果，未知效果先恢复原请求事实。

### 只准备材料

用户仅要求“给测试角色准备材料”时，查对应原生入口、执行所需准备并核对实际数量或状态，记录仍未满足的条件后结束。权限拒绝或缺少入口时说明具体缺口。

### 复现不回应

先确认同一真实场景中存在目标，取得本次输入前的包游标，记录玩家和目标身份。首次输入使用观察到的准确名称，保持可归因的单次触发。只有本次任务确实需要时才准备物品或改变场景。
