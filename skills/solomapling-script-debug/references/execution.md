# 执行、连续调用与收尾

参数形状读取当前工具 schema 和 [script_run 声明](../../../tools/acceptance-mcp/src/solo_acceptance_mcp/server.py)；具体约束与结果契约见[脚本执行实现说明](../../../docs/robot-runtime/script-execution.md)。以下示例是调用参数片段，需合并实际 `session_id`、会话 `expected_generation` 和调用方保存的唯一 `request_id`。

## 选择并启动原生环境

先从[源码导航](source-navigation.md)选择环境、实际对象和原脚本。NPC/任务会沿原管理器启动对话；门户/机关从角色当前实际地图取得对象；事件创建角色自己持有的原生 EM。当前使用 `kind/operation`，`source` 是选定北斗引擎里的代码片段。

NPC 原脚本 [1032110.js](../../../upstream/BeiDou-Server/gms-server/scripts-zh-CN/npc/1032110.js) 可用于任务需要的无收益对话调试。先读它的 `start/action`，确认本次环境允许该路径，再启动：

```json
{
  "kind": "npc",
  "operation": "start",
  "options": {"npc_id": 1032110},
  "source": "({name: cm.getPlayer().getName(), status: status})"
}
```

`start` 加载原文件、执行原入口，再执行非空 `source`，会产生真实对话。只为查询方法签名时读源码即可。原管理器仍执行点击冷却；再次启动按实际原生时钟留间隔。

保存响应的原 `context_id`、操作 ID、状态和本次请求的完整参数。每个环境分别绑定实际对象；后续调用不能用同模板 ID 或重新启动替代原上下文。

## 继续回调或读取变量

对上面的实际脚本继续其 `action`，适配器通过真实路由处理 `reply`：

```json
{
  "kind": "npc",
  "operation": "invoke",
  "context_id": "上次返回的实际context_id",
  "entry": "reply",
  "args": [1, 0, 0]
}
```

这组参数来自该脚本；其他脚本先查原回调和当前路由。NPC 的 legacy/NextLevel 与任务 start/end 由现有适配器选择，不手工拼原始回复包。此示例的下一次回调会 dispose，响应若已关闭就结束该上下文。

在仍为 OPEN 的环境里读取状态，使用 `invoke` 的 `source`，不带 `entry`：

```json
{
  "kind": "npc",
  "operation": "invoke",
  "context_id": "仍然OPEN的实际context_id",
  "parameters": {"label": "当前角色"},
  "source": "console.log(parameters.label); ({name: cm.getPlayer().getName(), level: cm.getPlayer().getLevel()})"
}
```

原脚本全局变量沿同一引擎保留，本次 `parameters` 随调用更新。将所需字段投影为 JSON，原生对象和函数不能直接作为最终 JSON 结果传出。

## 模块共享函数

在已有 OPEN 北斗上下文中，沿上文的 `invoke/source` 执行 `modules.help()`，查看运行体实际装配的共享函数及当前可用性；按其声明使用 `modules.call(name, args)`，其中 `args` 是 JSON 对象。没有业务导出预装时，目录可以为空。

函数缺失或提供者暂停、停用时正常返回 `UNAVAILABLE`；按实际结果选择后续流程。函数声明、装配与结果契约见[机器人模块参考](../../../docs/robot-runtime/modules.md#共享函数声明与调用)。北斗原有 `cm/qm/pi/rm/em` 方法继续按[源码导航](source-navigation.md)查阅。

## 实时读取执行输出

在 `script_run` 参数中提供 `output_file`，即可在执行期间读取该文件中的 `started/output/gap` 事件。首条消息包含本次 `request_id` 和 `operation_id`；输出消息包含 `seq/channel/text/output_truncated`。只提供 `output_file` 时合并 stdout/stderr；另提供 `error_file` 可单独记录 stderr 事件。只提供 `error_file` 时仅记录 stderr。

```python
result = await client.call_tool("script_run", {
    **invocation,
    "output_file": "/absolute/debug/script-output.ndjson",
    "error_file": "/absolute/debug/script-errors.ndjson",
})
```

文件属于 MCP 进程所在机器，按 UTF-8 NDJSON 追加写入并逐条刷新。父目录须已存在，建议使用绝对路径；相对路径按 MCP 工作目录解析。调用前打开文件，打开失败时脚本尚未提交。执行期间可在另一终端运行 `tail -f /absolute/debug/script-output.ndjson`。未提供文件时只返回最终结果，调用不发送 MCP progress 通知。

保存最后收到的输出 `seq`。连接中断后，以原请求和完整执行参数、原 `request_id` 加 `since_output` 恢复读取；文件路径可改，执行身份保持原参数。文件采用追加模式，使用较早游标会重复记录重读片段。已知操作ID时，通过 `operation_get(operation_id, since_output=...)` 的 `script_output` 读取保留窗口。`gap/cursor_expired` 表明窗口缺失，`output_truncated` 表明输出达到预算。最终响应继续提供 JSON 结果、错误和真实收尾状态。

## 判断结果与关闭

分别核对：

- 本次脚本结果、`stdout/stderr`、截断和错误；无异常只证明本次调用路径，原生效果另读实际状态或反馈。
- `invocation_settled/work_settled` 及上下文状态；`OPEN`、`context_closed=false` 可以是正常保留。
- 原调度回调是否实际完成；`schedule` 返回值只表示调度请求，不是回调完成。

本次任务需要收尾时，关闭自己创建或受托收尾、仍为 OPEN 的原上下文；需要继续保留的上下文记录原 ID 和状态：

```json
{
  "kind": "npc",
  "operation": "close",
  "context_id": "仍然OPEN的实际context_id"
}
```

关闭使用空默认 `source/options/args` 且不带 `entry`。核对最终引擎、原生会话及调度资源释放。上下文关闭后，再按角色任务完成正常保存退出。

## 超时、中断和未知结果

保存原 ID 及完整参数，通过同一请求恢复原 operation，已知操作 ID 可用 `operation_get` 查询。同 ID 变更参数会冲突，换新 ID 会形成另一调用，均不能用来恢复未知副作用。

提前取消尚未启动的 `invoke/close` 只结算本次调用，已有 Context 仍可能 OPEN。恢复结果中已确认 OPEN 时按需要显式关闭原上下文；UNKNOWN 保持未知，不重新 start 或假报关闭。停止 guest 循环也不证明正在执行的 Java 方法已返回，实际收尾标记与业务后态分别记录。

调试服务端传送、任务入口、门户或机关回调时，记录这条原生入口实际发生的变化；普通玩家的距离、资格和客户端准入需要各自验证。
