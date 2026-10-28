# 运行体中的北斗脚本环境

本页记录 [#216](https://github.com/Diluka/SoloMapling/issues/216) 下 [#219 首个原生环境闭环](https://github.com/Diluka/SoloMapling/issues/219) 的源码契约，供通过 MCP 调试机器人的开发者使用。源码测试、CI、隔离 MCP、实际部署及真实机器人交互各自留证，实际开放范围以 `game_capabilities`、`script_help` 和对应 Issue 为准。

`script_run` 未传 `environment` 时继续执行[独立的纯 JavaScript](script-execution.md)。传入 `environment` 时，运行体加载北斗已有脚本文件、绑定对应真实原生对象并调用原入口。这里复用北斗对象和 Java API，不为每个已有方法另造同义函数，也不把五类对象同时拼进一个没有来源的上下文。

## 原生对象与入口

| `kind` | 真实对象及来源 | `start` 选择方式 | 后续入口与状态 |
|---|---|---|---|
| `npc` | 当前角色 Client 上由 `NPCScriptManager` 创建的 `NPCConversationManager`，全局 `cm` | `options={"npc_id":1032110}`；可选 `script_name` | 原 `start`；`invoke` 的 `entry="reply"` 使用原 legacy/NextLevel 路由，保留同一 CM、引擎及脚本全局变量 |
| `quest` | 由 `QuestScriptManager` 创建的 `QuestActionManager`，全局 `qm` | `options={"quest_id":20600,"npc_id":1101002,"phase":"start"}`；`phase` 可为 `start/end`，默认 `start` | 使用原任务开始/结束入口；`reply` 根据实际 QM 的 phase 继续原回调 |
| `portal` | 当前实际地图的精确 Portal 和 `PortalPlayerInteraction` | `options={"portal_id":实际门户ID}`；脚本名从该 Portal 取得 | 原 `enter(pi)`，参数与全局 `pi` 是同一对象；该运行体独占引擎，不关闭全局门户缓存 |
| `reactor` | 当前实际地图中按 OID 取得的精确 Reactor 和 `ReactorActionManager`，全局 `rm` | `options={"reactor_oid":实际机关OID}`；脚本路径来自实际模板 ID | `act` 为默认入口，也可使用原 `hit/touch/untouch`；同一会话保留其引擎 |
| `event` | 使用角色实际 Channel 和同一受管 Invocable 创建的原生 `EventManager`，全局 `em` | `options={"script":"KerningTrain"}`，为已有事件脚本文件名，不带目录和扩展名 | 默认原 `init`；脚本定义的回调与 `em.schedule` 继续使用同一 Invocable 和全局状态 |

NPC/任务启动要求当前 Client 没有外来的 CM/QM。后续调用核对原对象身份；新对话替换旧对象后，旧 `context_id` 不能操作或清理新对话。门户和机关调用还核对原地图及实际对象，换图不会给旧引用续权。

事件环境由本角色运行体所有，不注册为频道全局 EventManager，也不取消频道现有事件。共享频道对象是来源，角色自己创建的 EM、引擎与受管回调才是本会话的资源。北斗原加载器继续处理脚本语言回退、UTF-8 读取、顶层定义及 Java host access；原脚本在对应管理器注入对象后取得其 `cm/qm/pi/rm/em`，不能假定这些对象在文件首次顶层求值前已经存在。

这些是服务端脚本执行入口。NPC 模板启动不等于玩家在地图中近距离点选 NPC，`pi` 执行不等于完整客户端门户准入，`rm.act()` 不等于客户端机关击打封包。任务脚本管理入口也不代替原 handler 的等级、职业和资格校验；例如 20600 的脚本首屏与拒绝可用于无收益调试，不能据此推导角色满足该任务的普通接取条件。原脚本的直接传送、改值、开 UI 或奖励仍保留各自语义，不能据此宣称相应普通玩家流程已经验收。

原生模式沿用北斗的 Java host access，面向已鉴权管理员提交的受信服务端脚本。入口的角色、场景和模块守卫不构成对任意 Java 对象路径的完整沙盒；不能把纯模式的隔离承诺扩展到这个环境。脚本中到达其他原生对象的行为和副作用仍须按调试任务逐项核对。

## 请求与连续调用

角色目标、鉴权和请求 ID 沿用既有 `script_run`。`source` 仍是外层必填字符串；纯模式要求非空，原生模式允许空字符串，让适配器执行选定原脚本的入口。原生非空 `source` 片段通过同名 `parameters` 取得 JSON 参数；原脚本入口的参数仍按它自己的签名传入 `args`，不假定文件首次顶层求值前存在额外对象。

```json
{
  "kind": "npc",
  "operation": "start",
  "options": {"npc_id": 1032110}
}
```

上面是 `environment` 的值。完整调用还需实际 `session_id`、`expected_generation`、唯一 `request_id`、`source=""`，以及可选 `parameters/timeout_ms`。运行体返回不透明 `context_id`，后续使用原值；它不代替角色会话、世界代次或模块代次。

| `operation` | 约定 |
|---|---|
| `start` | 不带 `context_id`，按 `kind/options` 创建并登记本模块所有的原生脚本环境，加载实际文件并执行原入口；非空 `source` 在原入口完成后继续求值，不替换原文件 |
| `invoke` | 必须带原 `context_id`；使用 `entry/args` 调原入口，或在同一引擎执行非空 `source`；非空 `source` 与 `entry` 互斥 |
| `close` | 必须带原 `context_id`，`source` 必须为空字符串，`options/args` 必须为空且不得指定 `entry`；关闭这个精确环境 |

`environment` 不接受未声明字段。`kind` 为表中五类，`entry` 匹配 `[A-Za-z_$][A-Za-z0-9_$]{0,127}`；参数、`options` 和 `args` 的 JSON 总量受 16,384 个 UTF-8 字节限制，各根最大深度 16。`source` 最多 65,536 个 UTF-16 单元；`timeout_ms` 仍为 1～5,000 的整数，默认 1,000。原生对象、函数、循环引用等不能作为 JSON 参数或结果传出。

NPC/任务的协议回复为 `entry="reply",args=[mode,type,selection]`，由适配器选择当前真实 legacy、NextLevel 或任务开始/结束路由，不让调用方以任意函数名替代原管理器。下列示例使用已有无收益 NPC 的第二次回复：

```json
{
  "kind": "npc",
  "operation": "invoke",
  "context_id": "上次返回的context_id",
  "entry": "reply",
  "args": [1, 0, 0]
}
```

同一原生环境也可在 `invoke` 中用 `source` 读取原对象或执行其现有方法。例如已启动 `KerningTrain` 后，`source="em.schedule('cancelSchedule', 100); ({event: em.getName()})"` 会沿原生调度器提交该脚本已有的回调。回调实际执行、调度任务取消与本次请求结束分别核对，不能把 schedule 返回值当作回调已完成。

## 结果、帮助与收尾

原生调用仍使用 `kind=SCRIPT` 的 operation 外壳，输出与错误类别沿用脚本基础。`result` 另有 `context_id`、`context_state`、`environment` 和 `invocation_settled`；`work_settled` 对应本次原 work 的真实结算。

成功调用可以保留 `context_state="OPEN"` 且 `context_closed=false`，以便下一次原生回复或事件回调继续使用全局状态。这不表示本次调用未结束：需分别核对本次执行退出、`invocation_settled` 和 `work_settled`。最终关闭或取消则还需核对 Context、原生会话/调度资源及 scope 的真实释放；不能把持久 Context 的正常保留套用成纯模式的清理失败，也不能因请求已终态就抹掉仍在运行的工作。

`script_help` 继续返回同源纯函数目录 `functions/unavailable`，并增加 `native_environments` 和本模块 `contexts`。原生环境说明列出实际绑定类型、选项、入口和所有权；它不是每个 Java 方法的另一份函数库。`functions=[]` 仅表示未注册该类函数，不能据此推断没有北斗原生环境。当前会话的路径、绑定身份和可用性应读取实际帮助及调用结果。

每次原生调用消费当前模块已经接纳的原 scope/work，不另建角色宿主、动作队列或物理工作账本；持久原生环境登记在同一 scope 的资源机制中。长脚本不占角色短命令 lane。模块停止、根关闭和世界换代先停止新受理，再处理本环境创建的回调、原生对象和引擎；只允许释放原登记对象，不能清理外来或后继会话。

MCP 中断仍转发独立取消 HTTP，请求包含原 `environment`、source、parameters 和期限的完整指纹。原 ID 同参数恢复原结果，变更环境或其他参数会冲突；取消先到的检查点阻止迟到执行。已发生的原生副作用不因取消撤回，未知结果不换新 ID 自动重放。

`Context.close(true)` 能停止 guest JavaScript 循环，但不能替代正在执行的 Java host 方法实际返回。阻塞的原生 Java 调用或未核实的事件资源仍是原 scope 债务，必须据实保留未完成状态。原生事件调度器的异步取消、已出队回调不能只凭 Future 取消或 `dispose()` 返回就宣称结束。本首环拒绝尚未接入生命周期管理的 EIM 实例创建，不让原脚本创建实例地图后漏记其一分钟延迟清理。

## 本片验证边界

本片选择无收益 NPC、多次任务拒绝、门户提示、机关状态检查，以及独占 EM 的原 `init/em.schedule` 回调作为代表验证场景；实际通过范围与结果由 #219 分层记录。EIM 实例创建在首环明确拒绝，完整地图实例及其延迟清理留在 #216 后续；所有原生脚本组合、机器人实际交互和真实客户端玩法也仍按各自实际证据验收。首环完成不等于整个 #216 完成。

官方北斗 submodule 保持只读，适配和织入在 Solo 工程维护。已有十三个 `game_act` 动作和独立管理指令保留，本接入不执行 #213 的直接入口迁移，也不提前完成 #205/#211 的完整行为模块和共享环境设计。
