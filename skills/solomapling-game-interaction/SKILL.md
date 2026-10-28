---
name: solomapling-game-interaction
description: 在附源码的 SoloMapling 工作区观察专用角色、执行游戏动作或原生命令，并确认实际结果。适用于查看世界和机器人、登录退出、聊天、NPC、移动战斗及背包操作；脚本调试和机器人行为回归使用对应技能。
---

# SoloMapling 游戏交互

使用实际开放的 MCP 能力完成用户要求的观察或操作，以原生反馈和后态确认结果。

## 工作方式

1. 确认当前 SoloMapling 源码工作树和 MCP 目标，读取 `game_capabilities`。参数直接查当前工具 schema；实现从[工具注册](../../tools/acceptance-mcp/src/solo_acceptance_mcp/server.py)定位。说明书中的源码链接以随附仓库布局解析。
2. 只读问题先观察 world，再按真实 `scene_ref` 观察场景。只有任务需要角色输入时才打开已配置专用 profile。已有用户会话按任务继续使用。
3. 操作前保存实际会话、代次、最近场景及原请求 ID；核对目标 OID、槽位或对话版本。按任务只读取相关说明：
   - 会话、场景和包事件：[会话与观察](references/actor.md)。
   - 聊天、NPC、移动战斗和物品：[游戏动作](references/actions.md)。
   - 查命令实现、执行和读反馈：[原生命令](references/commands.md)。
   - 超时、取消、旧引用及退出：[结果与恢复](references/recovery.md)。
4. 查询原 operation 到实际终态，核对对应反馈或状态变化。受理、派发、业务效果和保存分别判断；未知结果先恢复原记录。
5. 完成任务需要的收尾。自己打开且已结束用途的会话正常退出并核清理；需要继续交互的会话保留。向用户说明实际结果、失败和未验边界。

需要操作北斗脚本上下文时，使用[脚本调试技能](../solomapling-script-debug/SKILL.md)；需要判断机器人是否符合设计时，使用[机器人验收技能](../solomapling-bot-testing/SKILL.md)。本技能沿当前会话授权和原生权限操作。
