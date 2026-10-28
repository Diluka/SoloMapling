# 在脚本中使用原生命令

受管北斗引擎提供 `executeCommand(commandText)`。准确绑定见 [NativeScriptSession.bindCommandExecutor](../../../beidou-integration/src/main/java/soloMapling/game/script/nativeenv/NativeScriptSession.java)，实际处理见 [NativeCommandExecutor](../../../beidou-integration/src/main/java/soloMapling/game/command/NativeCommandExecutor.java)。

命令名称、处理类及参数通过[游戏交互技能](../../solomapling-game-interaction/SKILL.md)查阅。对应只读查询脚本为 `skills/solomapling-game-interaction/scripts/commands.py`，在仓库根执行：

```bash
tools/acceptance-mcp/.venv/bin/python skills/solomapling-game-interaction/scripts/commands.py --config /absolute/private/solo-acceptance.json --syntax time
```

使用本地私密配置的实际路径；读取结果中的实现类后，查对应 `Command.execute()` 与语言资源，按当前普通角色权限选择命令。此查询用于读目录，不修改命令配置。

## 调用与结果

在本次任务已打开且仍为 OPEN 的原生上下文中调用：

```json
{
  "kind": "npc",
  "operation": "invoke",
  "context_id": "实际context_id",
  "source": "({receipt: executeCommand('@time')})"
}
```

`@time` 的实现为 [TimeCommand](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/command/commands/gm0/TimeCommand.java)。调用返回后，沿 `game_observe(scope="actor")` 的实际包窗口读取对应反馈，命令提示与普通聊天广播分别取证。

命令函数使用该环境的实际 Client，沿原生注册、语法和权限检查。函数在首次文件求值前安装不表示环境绑定已就绪，应在原生入口或已就绪上下文中使用。原命令可能发出拒绝提示后正常返回，`RETURNED/UNREPORTED` 只证明处理器返回，需读反馈或游戏后态确认效果。

脚本命令沿这次 `script_run` 的请求、上下文及生命周期；停止后不再推进后续脚本，已发生的效果保留。未知结果按[执行与收尾](execution.md)恢复原请求，不能换 ID 重发整段脚本。

独立 `game_command` 使用已登记在线 profile 与管理进程代次，在没有脚本任务时由游戏交互技能使用。两种入口共用原生命令实现，脚本调用不额外建立独立 MCP 命令历史，也不因管理员 JWT 提升角色的原生 GM 权限。
