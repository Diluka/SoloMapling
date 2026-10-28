# 原生命令

## 查命令到实现的映射

从 SoloMapling 仓库根使用随技能提供的只读查询脚本。配置使用当前目标的本地私密文件，凭据留在该文件中：

```bash
tools/acceptance-mcp/.venv/bin/python \
  skills/solomapling-game-interaction/scripts/commands.py \
  --config /absolute/private/solo-acceptance.json --syntax time
```

脚本调用现有 `/command/v1/getCommandListFromDB`，读取 `syntax/clazz/defaultLevel/level/enabled/description`。`--syntax` 用于定位候选项，使用查询结果中的实际命令名。查询结果只提供目录和状态，尚未执行命令。

实现路径按 `defaultLevel` 和 `clazz` 定位：

```text
upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/command/commands/gm<defaultLevel>/<clazz>.java
```

读取对应 `execute(Client, String[])`，沿其 i18n 键定位 [message_zh_CN.properties](../../../upstream/BeiDou-Server/gms-server/src/main/resources/i18n/message_zh_CN.properties)。参数解析、默认值和副作用以实现为准；语言资源提供语法与提示原文。比如 `time` 对应 [TimeCommand](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/command/commands/gm0/TimeCommand.java)，执行后产生服务器时间提示。

映射来源见 [CommandController](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/controller/CommandController.java) 的 `getCommandListFromDB` 和 [CommandService](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/service/CommandService.java) 的 `getCommandInstance`。名称、权限等级和启用状态从已有查询读取，说明书不手工列命令全集。

## 通过 MCP 执行

1. 确认目标是已配置且实际在线的普通专用 profile，读取 capabilities 的 `debug_commands` 和 `command_generation`。
2. 查明目标命令、前缀和参数。把完整文本原样提交给 `game_command`，使用该次唯一请求 ID 和实际管理代次。精确参数从当前工具 schema 或 [server.py](../../../tools/acceptance-mcp/src/solo_acceptance_mcp/server.py) 的 `game_command` 查阅。
3. 读取调用结果，再从 actor 新包和实际状态确认效果。命令目录中的权限等级与原生角色权限共同裁决执行；管理员工具鉴权不提升该角色 GM 等级。

`RETURNED`、`native_handler_returned=true` 和 `command_outcome=UNREPORTED` 表示原生 void 处理器已经返回。权限拒绝、未知命令或忙碌也可能正常返回。时间命令用实际黄字反馈核验；其他命令按其本来效果检查状态。

管理员黄字使用 actor 包窗口中的 `yellow_tip`，忙碌等反馈可能是 `server_message`，与普通聊天回声分别读取。执行前保存包游标，避免把旧反馈归给新请求。

## 脚本中的命令

北斗原生脚本提供 `executeCommand(commandText)`，与独立 MCP 命令共用 [NativeCommandExecutor](../../../beidou-integration/src/main/java/soloMapling/game/command/NativeCommandExecutor.java)。其原生权限、文本和结果含义一致；脚本调用归本次脚本上下文与请求，独立 MCP 命令使用单独的管理代次和请求历史。

需要在已有脚本里执行时，查[脚本调试技能](../../solomapling-script-debug/SKILL.md)及 [NativeScriptSession](../../../beidou-integration/src/main/java/soloMapling/game/script/nativeenv/NativeScriptSession.java) 的 `bindCommandExecutor`；不要为查命令另建脚本环境。

## 命令恢复

超时或 `UNKNOWN` 时，保留原命令全文、profile、请求 ID 和管理代次，原样恢复原记录。管理命令不接入 actor 的 `operation_get/operation_cancel`，没有撤回接口。重启后旧管理代次失效，先核对已知效果；不能刷新代次重发未知旧命令。

具体命令是否保存数据由其实现决定。库存、地图等持久结果需要任务要求的独立核验。
