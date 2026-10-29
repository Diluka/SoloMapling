# 运行体中的北斗脚本执行

`script_run` 只执行北斗脚本。调用方直接指定 `kind`、`operation` 和相应上下文参数，由现有角色运行体加载原脚本、绑定真实对象并管理执行。独立纯 JavaScript 模式及外层 `environment` 参数已移除；只有 `source` 的旧请求不会自动选择环境。

本文为当前源码的公共执行契约。五类对象的来源、原入口和连续调用示例见[北斗脚本上下文](beidou-script-environment.md)；MCP 使用流程见[工具说明](../../tools/acceptance-mcp/README.md#脚本基础调用)。源码测试、CI、隔离验收与实际部署结果按对应 Issue 分别记录，不能仅由本文推导部署状态。

## 请求

先正常打开专用角色并保存实际 `session_id/session_generation`，后者作为 `expected_generation`。脚本请求继续核验管理员鉴权、owner、角色来源、世界及模块执行代次，不接受任意角色 ID。

| 参数 | 约定 |
|---|---|
| `session_id`、`expected_generation` | 必填，使用当前实际角色会话和代次 |
| `request_id` | 必填，调用方保存的唯一请求 ID；同 ID 只能恢复完全相同的调用 |
| `kind` | 必填，`npc/quest/portal/reactor/event` 中的一种 |
| `operation` | 必填，`start/invoke/close` 中的一种 |
| `context_id` | `start` 不传；`invoke/close` 必须使用此前返回的原值 |
| `options` | JSON 对象，默认 `{}`；`start` 按 `kind` 选择实际脚本及对象 |
| `entry`、`args` | 原脚本入口及其参数；`args` 默认为 `[]`，按实际原生签名使用 |
| `source` | 默认空字符串，最多 65,536 个 UTF-16 单元；非空时在同一北斗引擎中执行代码片段 |
| `parameters` | JSON 对象，默认 `{}`，以同名对象供本次脚本读取 |
| `timeout_ms` | 整数 1～120,000，默认 30,000；覆盖本次环境准备、执行和结果转换 |
| `since_output` | 输出游标，默认 0；同一原请求恢复时填写已收到的最后输出序号 |

`options`、`args`、`parameters` 的输入预算由服务端统一裁决：按服务端规范化 JSON 的 UTF-8 编码计算，三根总量最多 16,384 字节，每个根的深度最多 16。客户端保留 JSON 类型、有限数值、有效 Unicode、节点数和深度检查，不再用自身重编码的字节数作第二次预算裁决。不同编码器可能将同一数值写为 `1e+20` 或 `1.0E20`，客户端编码长度不能用来推定服务端是否接受；超出服务端预算的请求明确拒绝，不进入原生执行。

`entry` 匹配 `[A-Za-z_$][A-Za-z0-9_$]{0,127}`，不能用属性路径替代原入口。

`start` 创建本模块所有的真实北斗上下文，加载现有文件并执行原入口；非空 `source` 在原入口成功后追加执行，不替换原文件。`invoke` 调用 `entry/args`，或执行非空 `source`，两者互斥。`close` 只收尾原 `context_id`：`source` 必须为空，`options/args` 必须为空，且不得指定 `entry`。

缺少 `kind/operation` 或提交旧 `environment` 字段时拒绝请求。脚本内仍可做参数运算、整理结果和语言输出，这些代码始终运行在选定的北斗上下文中。

## 输出和结果

`script_run` 在执行过程中通过 MCP progress 通知发送增量输出，通知的 `message` 是 JSON 文本。官方 SDK 调用方传入 `progress_callback` 接收通知；客户端是否展示进度按实际宿主验收记录判断。后端使用 `POST /solo/acceptance/v1/script/stream?since_output=0` 的 NDJSON 响应，沿用原脚本请求信封与幂等记录。

| `type` | 内容 |
|---|---|
| `started` | 本次 `request_id` 和原 `operation_id` |
| `output` | `seq`、`channel`（stdout/stderr）、增量 `text` 和 `output_truncated`，以及请求与操作 ID |
| `gap` | 有界窗口已失去部分输出时的 `oldest_cursor/latest_cursor` |
| `result` | 最终 `operation`；MCP 工具响应返回这份原结果 |

输出序号在同一 operation 内递增，两个通道共用顺序。`since_output` 与生命周期的 `since_cursor`、角色包的 `since_cursor` 分别使用。每个 operation 保留最近 256 个输出事件；通过 `operation_get(operation_id, since_output=…)` 的 `script_output` 读取已保留输出及 `next_cursor/oldest_cursor/latest_cursor/cursor_expired/output_truncated`。恢复流时使用完全相同的原请求和原 `request_id`，另带输出游标；断线后的执行状态及取消结果从原 operation 核对。

```python
import json

async def receive_output(progress, total, message):
    event = json.loads(message)
    if event["type"] == "output":
        print(event["text"], end="", flush=True)

result = await client.call_tool(
    "script_run", invocation, progress_callback=receive_output
)
```

`console`、`print` 等语言输出按 UTF-8 收集到本次 `stdout/stderr`，保留分片写入的完整 Unicode 字符；每路最多 16,384 字节，超出设置 `output_truncated=true`。这是脚本语言输出，游戏聊天仍沿角色的原生字符集规则。非空 `source` 返回最后表达式的值；空 `source` 执行原入口并返回其可序列化结果。

JSON 结果最多 16,384 个 UTF-8 字节，最大深度 32，并受节点和字符串累计额度限制。原生对象、函数、循环引用、Promise、非有限数及非法 Unicode 字符串不能作为 JSON 结果传出；脚本应明确投影所需的安全字段。终态结果不保留 Graal `Value`、Context、原生角色或 Throwable 引用。

响应使用既有 operation 外壳，`kind=SCRIPT`；这与请求中选择脚本类型的 `kind` 分属不同位置。其 `result` 示例：

```json
{
  "status": "SUCCEEDED",
  "result": {"name": "McpPrimary"},
  "stdout": "已读取角色\n",
  "stderr": "",
  "output_truncated": false,
  "context_id": "native-...",
  "context_state": "OPEN",
  "engine_exited": true,
  "context_closed": false,
  "invocation_settled": true,
  "work_settled": true
}
```

成功调用可以保留 `OPEN` Context 和原脚本全局变量，以便后续对话或事件回调继续执行。`invocation_settled/work_settled` 证明本次调用真实结算；`context_closed=false` 表示上下文正常保留。最终关闭还需核验 Context、原生会话、调度资源和原 scope 真正释放，不能把单次请求结束当作整个环境关闭。

| `result.status` | 含义 |
|---|---|
| `SUCCEEDED` | 本次调用及 JSON 结果转换完成 |
| `PARSE_ERROR` | 脚本语法错误 |
| `RUNTIME_ERROR` | 求值或原生调用异常 |
| `RESULT_ERROR` | 结果不能投影为声明范围内的 JSON |
| `TIMED_OUT` | 执行期限已到并触发停止 |
| `CANCELLED` | 调用或所属生命周期请求停止 |
| `CLEANUP_ERROR` | 执行退出或清理未证实 |

错误可附有界 `error={message,line,column}`，无法定位时行列为 `null`。外层 operation 使用 `SUCCEEDED/FAILED/CANCELLED/INDETERMINATE`；脚本失败设置 MCP `is_error=true`，同时保留已收集输出、首个已知脚本错误和真实收尾标记。

## 接口查阅

[脚本调试技能](../../skills/solomapling-script-debug/SKILL.md)提供源码导航和连续调用说明。五类环境的 `descriptor()` 同时服务实际 capabilities；对象方法、父类和返回类型直接查北斗源码，生成方法可查匹配构建产物。`script_run` 返回精确 `context_id`、上下文状态和本次结算，原请求及 operation 用于恢复；每模块最多一个持久上下文。

## 模块函数命名空间

当前受管北斗引擎提供 `modules.help()` 和 `modules.call(name, args)`，后者的 `args` 为 JSON 对象。Java `ModuleContext.help()` / `call()` 与它们消费同一目录，帮助来自实际装配的模块声明，调用按当前精确实例和执行代次解析。默认 MCP 测试类型为 `mcp_test`，提供 [`navigation.moveTo`](navigation.md) 和 [`robots.create/remove`](population.md#脚本创建与移除)；具体声明、返回和错误语义见[机器人模块参考](modules.md#共享函数声明与调用)。

`modules` 绑定在原 `kind/operation` 选定的北斗上下文中。`cm/qm/pi/rm/em` 的对象来源和适用范围，以及 `executeCommand` 的现有命令行为保持原契约。模块定义、装配与受管执行见[机器人模块参考](modules.md)。

`robots.create(typeId)`、`robots.remove(instanceId)` 是同一目录对应函数的简写：成功时返回函数的 `result`，提供者缺失或不可用时保留原 `UNAVAILABLE` 结果。创建按已加载的类型生成完整环境机器人；移除按具体实例引用完成实际关闭。初始人口、来源及运行期数量约定见[人口配置](population.md)。

同步模块函数在自己的提供者 scope 中执行，当前调用者 work 的取消及 scope 停止持续关联到提供者 work。脚本期限通过同一停止链生效；`navigation.moveTo` 在抵达或失败及本次收尾后返回，后续脚本据实际导航结果继续执行。

## 归属、取消和真实收尾

`CharacterRuntime.ModuleContext.runNativeScript` 消费调用模块已接纳的原 `ExecutionScope.WorkHandle`。每模块最多保留一个原生脚本上下文；上下文和原生调度资源登记在同一 scope，长执行不占角色短命令 lane，不另建角色宿主、动作队列或物理工作账本。

执行载体监视期限、原 work 的取消请求和模块状态。`Context.close(true)` 能停止 guest JavaScript 无限循环，但不能替代正在执行的 Java host 方法实际返回。执行体、Context、原生会话或调度资源未退出时保留原 scope 债务及未确定结果；`Future.cancel/isDone`、取消通知、`dispose()` 返回或 HTTP 返回均不是替代证据。

模块停止撤销其调用资格并取消、排空原 scope。根 `actor_close` 等模块真实结束后再释放角色；旧世界、旧 Context 及旧模块代次不能复活或清理外来、后继会话。

MCP handler 中断通过独立 `/script/cancel` 请求转发到后端，携带原请求 ID 及完整相同的类型、操作、上下文、脚本、参数和期限。取消先到时留下已结算取消检查点，迟到 run 不执行；变参仍冲突。已发生的原生副作用不会撤回，未知结果不换新 ID 自动重放。

提前取消尚未启动的 `invoke/close` 只结算本次调用，不自动关闭它指向的已有 Context。检查点按原 `context_id` 记录当时读到的上下文事实；已有环境保持 `OPEN` 时，不能据 `CANCELLED` 或 `invocation_settled/work_settled` 推导其已关闭。无法确认上下文时返回 `context_state=UNKNOWN`，不宣称已清理；调用方保留原上下文 ID，按原请求或 operation 恢复本次证据，并在需要时显式 `close`。提前取消 `start` 的 `NOT_STARTED` 只表示该次新上下文没有创建。

已取消的 MCP 请求按协议不再返回工具响应。使用相同 `request_id` 和完整原参数恢复原 operation，或用已知 `operation_id` 查询。脚本专用 HTTP 预算至少为执行期限加 5 秒，宿主工具期限另留通信与收尾余量。

## 验证边界

本接口用于机器人开发调试，代表脚本、原生规则和实际行为按任务分别验收。EIM 创建及其一分钟延迟地图释放尚未接通，运行体所有的 EM 明确拒绝相关入口；完整 EIM、更多脚本组合、真实机器人交互和客户端玩法不由基础执行通过推导完成。官方北斗 submodule 保持只读。

## 通用命令函数

受管北斗引擎首次eval前发布 `executeCommand(commandText)`，同引擎的后续代码和回调
均可复用。它取当前真实Client，调用与MCP一致的NativeCommandExecutor；原生权限不变，
不进入角色短lane，RETURNED不表示命令效果成功。只注入执行函数，不注入帮助函数。
具体协议、状态和恢复边界见[通用命令执行](../mcp-acceptance/commands.md)。
