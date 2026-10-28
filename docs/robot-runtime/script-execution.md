# 运行体脚本执行：纯脚本基础

本页描述 [#199](https://github.com/Diluka/SoloMapling/issues/199) 与 [#204](https://github.com/Diluka/SoloMapling/issues/204) 的本轮源码基础切片。源码测试、CI、隔离 MCP 和实际部署结果由 Issue 分别留证，不能由本页推导部署已完成。

本片让已有角色上的 MCP 调用执行纯 JavaScript、传入 JSON 参数、收集语言输出并取得可序列化结果。本文约定适用于未传 `environment` 的纯脚本模式；[#216 北斗脚本环境](beidou-script-environment.md) 另按真实入口绑定原生对象并保留必要上下文。纯函数注册目录为空，现有 `game_act` 保留；动作迁移、行为模块脚本和共享观察/事件目录另行推进。

## 归属与执行

`CharacterRuntime.ModuleContext.runScript(work, request)` 消费调用模块已经接纳的原 `ExecutionScope.WorkHandle`。角色、世界来源与模块执行代次仍由该 Context 核验，不另建角色宿主、scope 或工作账本。

`ScriptExecution` 使用运行体管理的执行载体运行 guest，并等待 guest 真正退出。纯计算、解析和结果转换不进入角色短命令 lane；本模式没有原生游戏调用，也不授予脚本新的原生入口。需要真实北斗对象时显式使用原生环境，角色来源和工作归属仍由同一运行体管理。

纯脚本模式每次调用创建独立 GraalJS Context，不保留此前脚本的变量、函数或闭包。Java host access、类查找、IO、guest 建线程、native access 和 polyglot access 均关闭。此模式不会注入现有 NPC/任务/事件脚本的 `cm/im/qm/rm/em` 或原生角色对象；#201 对旧游戏脚本环境的调查不能当作这个 Context 的能力目录。传入原生 `environment` 时按另一份契约复用北斗加载器、host access 和对应真实绑定，不据纯模式的限制推断其能力。

## 语言输入和结果

| 项目 | 当前约定 |
|---|---|
| 语言 | JavaScript，能力公告值为 `javascript` |
| `source` | 非空脚本文本，最多 65,536 个 UTF-16 单元 |
| `parameters` | JSON 对象，默认 `{}`；作为同名 guest 对象提供，编码后最多 16,384 个 UTF-8 字节 |
| `timeout_ms` | 整数 1～5,000，MCP 默认 1,000；期限包括 Context 创建、绑定、执行和结果转换 |
| 语言输出 | `console`、`print` 等 GraalJS 输出进入本次 `stdout/stderr`；每路最多 16,384 字节，超出设置 `output_truncated=true` |
| 返回值 | `source` 最后表达式的值；返回对象时可使用 `({ ... })`，或调用自执行函数 |
| JSON 结果 | 独立于 Context 的 JSON 值，编码后最多 16,384 个 UTF-8 字节，最大嵌套深度 32，并受节点与字符串累计额度限制 |

函数、循环引用、Promise 等不能作为同步 JSON 结果返回，返回 `RESULT_ERROR`；结果转换仍在可取消的 guest 执行期间进行。终态结果不保留 Graal `Value`、Context、原生角色或 Throwable 引用。

示例脚本：

```javascript
console.log("开始计算", parameters.label);
const total = parameters.values.reduce((sum, value) => sum + value, 0);
({ label: parameters.label, total, count: parameters.values.length });
```

传入 `{"label":"首环","values":[2,3,5]}` 时，结果为 `{"label":"首环","total":10,"count":3}`，输出在 `stdout` 中。该示例没有游戏副作用。

## 最小函数接点

宿主维护一份 `ScriptRegistry`。可信模块通过 `ModuleContext.registerScriptFunction(definition, body)` 注册当前模块代次的函数；`ScriptFunction` 同时描述名称、用途、参数名与返回说明。函数只对当前调用模块的精确代次可见，同模块的 `script_help` 与 `functions.<name>(...)` 使用相同注册项，调用前后复核模块当前性；停止时移除原代次的注册项。不同模块可各自使用相同函数名，不能据此获得彼此的注册项。

这只是本片需要的最小描述与绑定接点。机制测试可注册中立纯函数验证同源；生产没有注册游戏函数，所以全局 `functions` 是空对象，`script_help()` 返回 `functions=[]`。按名称查询缺失项在 `unavailable` 中列出，不合成可执行定义。跨模块能力贡献、提供者调用与在途生命周期属于 #204 后续范围。

本片不实现完整模块能力发现、共享事实与事件、模块内部行为脚本或导出闭包生命周期，也不通过注册接点提前公开游戏操作。

## 结果与错误

MCP 使用既有 operation 外壳，`kind=SCRIPT`。`result` 为本次脚本的独立 JSON 投影：

```json
{
  "status": "SUCCEEDED",
  "result": {"total": 10},
  "stdout": "开始计算 首环\n",
  "stderr": "",
  "output_truncated": false,
  "engine_exited": true,
  "context_closed": true,
  "work_settled": true
}
```

| 脚本 `result.status` | 含义 |
|---|---|
| `SUCCEEDED` | 求值和 JSON 结果转换完成 |
| `PARSE_ERROR` | 脚本语法错误 |
| `RUNTIME_ERROR` | 求值或受管调用异常 |
| `RESULT_ERROR` | 结果不能投影为声明范围内的 JSON |
| `TIMED_OUT` | 执行期限已到并触发停止 |
| `CANCELLED` | 调用或所属生命周期请求停止 |
| `CLEANUP_ERROR` | 引擎退出或 Context 清理未证实 |

错误可附有界 `error={message,line,column}`；无法定位时行列为 `null`。外层 operation 的 `status` 分别使用 `SUCCEEDED/FAILED/CANCELLED/INDETERMINATE`，外层 `error.reason` 标明脚本失败类别。脚本失败在 MCP 中设置 `is_error=true`，同时保留本次已收集输出和收尾标记。

## 期限、取消与真实收尾

执行载体监视期限、原 work 的取消请求及模块 scope 状态。停止使用 `Context.close(true)` 中断 guest；纯 JavaScript 无限循环即使捕获普通异常，也不能因此吞掉引擎取消。最终收尾还必须等待实际 guest 执行退出、Context 关闭以及原 work 的 carrier 结算。

纯模式 `ScriptExecution.completion()` 的结果区分 `engine_exited/context_closed/work_settled`。全部为真才证明本次脚本收尾完成；`Future.cancel()`、`isDone()`、取消通知或请求返回不是替代证据。清理无法核实时保留原 scope 债务和 `INDETERMINATE`，不清空句柄假报完成。原生模式可以在本次 `invocation_settled/work_settled` 后保留 `OPEN` Context，最终环境关闭另行核验，见[原生结果与收尾](beidou-script-environment.md#结果帮助与收尾)。

模块停止撤销其当前调用资格并取消、排空原 scope。根 `actor_close` 继续等待模块真实退出后释放角色；旧 Context 和旧模块代次不能恢复或作用于新模块。Context 是原 work 的词法资源，在退出路径显式关闭，不另立可独立修改的资源账本。

MCP handler 中断会通过桥的独立 `/script/cancel` 请求将取消转发到后端，并保留收尾余量。该请求携带原 `request_id`、source、parameters 与期限的完整指纹；若取消先于 run 受理，后端为原 ID 建立已结算取消检查点，不启动引擎。迟到的同指纹 run 只恢复此结果，变参仍冲突。

已取消的 MCP 请求按协议不再返回工具响应。重新连接后使用相同 `request_id` 和相同参数恢复原 run 结果，或用已知 `operation_id` 查询；不能换新 ID 盲重放。传输或取消失败时保持结果未知，不宣称后台已停止。脚本专用 HTTP 预算至少为执行期限加 5 秒，宿主工具预算还须留出通信与收尾时间。

## 验证边界

本轮源码验证覆盖纯计算、嵌套参数、语言输出、JSON 结果边界、解析/运行错误，以及期限和取消后的真实退出。运行体机制、官方 SDK stdio 端到端、隔离服务和实际部署各自留证。无限循环、世界停启及其他故障探针仅在隔离环境执行；实际部署仅做已授权的正常角色与脚本循环。

脚本基础可用不代表机器人交互、具体游戏函数或真实客户端玩法已验收。后续按实际调试任务选择最小游戏函数，再独立核验权限、原生规则、动作结果与收尾。
