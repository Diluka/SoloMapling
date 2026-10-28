# 北斗脚本上下文与连续调用

`script_run` 只执行北斗脚本。直接用顶层 `kind/operation` 选择原生对象和生命周期操作；不再接收外层 `environment`，也没有省略后执行独立纯 JavaScript 的路径。公共参数、输出、错误、帮助和取消契约见[脚本执行参考](script-execution.md)。实际部署和各层验收按对应 Issue 分别记录。

这里复用北斗原脚本对象和 Java API，不为每个已有方法另造同义函数，也不把五类对象同时拼进没有来源的上下文。

## 原生对象与入口

| `kind` | 真实对象及来源 | `start` 的 `options` | 后续入口与状态 |
|---|---|---|---|
| `npc` | 当前角色 Client 上由 `NPCScriptManager` 创建的 `NPCConversationManager`，全局 `cm` | `{"npc_id":1032110}`；可选 `script_name` | 原 `start`；`entry="reply"` 沿当前 legacy/NextLevel 路由，保留同一 CM、引擎及全局变量 |
| `quest` | 由 `QuestScriptManager` 创建的 `QuestActionManager`，全局 `qm` | `{"quest_id":20600,"npc_id":1101002,"phase":"start"}`；phase为start/end，默认start | 使用原任务开始/结束入口；`reply` 根据真实 QM 的 phase 继续回调 |
| `portal` | 当前实际地图的精确 Portal 与 `PortalPlayerInteraction` | `{"portal_id":实际门户ID}`；脚本名从该 Portal 取得 | 原 `enter(pi)`，参数与全局 `pi` 为同一对象；独占引擎不关闭全局门户缓存 |
| `reactor` | 当前实际地图中按 OID 取得的精确 Reactor 与 `ReactorActionManager`，全局 `rm` | `{"reactor_oid":实际机关OID}`；脚本路径来自实际模板ID | 默认 `act`，也可用原 `hit/touch/untouch`；缺失可选hit按原语义不执行动作 |
| `event` | 角色实际 Channel 与同一受管 Invocable 创建的原生 `EventManager`，全局 `em` | `{"script":"KerningTrain"}`；已有事件脚本文件名，不带目录/扩展名 | 默认原 `init`；原回调及 `em.schedule` 共用同一 Invocable 和全局状态 |

NPC/任务启动要求当前 Client 没有外来的 CM/QM。后续调用核对原对象身份；新对话替换旧对象后，旧 `context_id` 不能操作或清理新对话。门户和机关还核对原地图及实际对象，换图不会给旧引用续权。

NPC/任务入口遵守原生点击冷却及实际缓存时钟，不靠立即重发绕过。之前的500ms门槛、777ms缓存步进环境中，正常两次启动至少等待1278ms；具体时钟按实际公告和配置核对。

事件由本角色运行体所有，不注册为频道全局 EventManager，也不取消频道既有事件。北斗原加载器继续处理语言回退、UTF-8 读取、顶层定义及 Java host access。原脚本在对应管理器注入后取得其 `cm/qm/pi/rm/em`，不能假定文件首次顶层求值前已有这些对象。

这些是服务端脚本执行入口。NPC模板启动不等于地图中近距离点选NPC，`pi`执行不等于完整客户端门户准入，`rm.act()`不等于机关击打封包。任务入口也不替代原handler的等级、职业和资格校验；20600首屏与拒绝可用于无收益调试，不能据此推导角色满足普通接取条件。原脚本的传送、改值、开UI和奖励仍保留原语义，应核对实际副作用。

脚本沿用北斗的Java host access，面向已鉴权管理员提交的受信服务端代码。角色、场景及模块守卫不构成对任意Java对象路径的完整沙盒；所到达对象的行为和副作用仍按调试任务分别验收。

## 先查帮助，再启动

先 `actor_open` 并确认原生角色实际在线，保存 `session_id/session_generation`。然后调用 `script_help`，读取实际 `native_environments`、对象来源、选项和当前 `contexts`。以下均为传给 `script_run` 的参数片段；完整调用另需实际角色会话、`expected_generation` 和每次唯一的 `request_id`。

```json
{
  "kind": "npc",
  "operation": "start",
  "options": {"npc_id": 1032110},
  "source": "({name: cm.getPlayer().getName(), status: status})"
}
```

`start` 会加载原NPC文件并执行原 `start`，然后追加执行非空 `source`。它会实际打开对话，不能把启动当作只注入一个 `cm` 变量。`source` 可以省略，默认空字符串，此时只执行原入口。

响应的 `result.context_id` 标识这份精确上下文。保留原值继续调用，不用角色ID、NPC模板ID或旧模块代次替代。

## 继续原回复或执行代码

对上述无收益NPC执行下一次原生回复：

```json
{
  "kind": "npc",
  "operation": "invoke",
  "context_id": "上次返回的context_id",
  "entry": "reply",
  "args": [1, 0, 0]
}
```

NPC/任务的 `reply` 参数是 `[mode,type,selection]`，由适配器选择当前实际legacy、NextLevel或任务start/end路由。这个示例的原脚本会正常dispose；若结果显示Context已经关闭，不再用旧ID继续调用。

也可以在仍为OPEN的上下文中执行非空 `source`：

```json
{
  "kind": "npc",
  "operation": "invoke",
  "context_id": "仍然OPEN的context_id",
  "parameters": {"label": "当前角色"},
  "source": "console.log(parameters.label); ({name: cm.getPlayer().getName(), level: cm.getPlayer().getLevel()})"
}
```

`source` 与 `entry` 在同一次invoke中互斥。每次MCP调用更新本次JSON `parameters`，同引擎中其他脚本变量继续保留；原生调度回调沿同一环境继续执行。若已启动KerningTrain，`source="em.schedule('cancelSchedule', 100); ({event: em.getName()})"` 会沿原调度器提交该脚本已有回调，不能把schedule返回当作回调已实际完成。

## 用完后关闭

```json
{
  "kind": "npc",
  "operation": "close",
  "context_id": "仍然OPEN的context_id"
}
```

close的 `source/options/args` 使用空默认值且不得指定 `entry`，只收尾这个精确环境。`OPEN` 时 `context_closed=false` 可以是正常保留；本次调用的 `invocation_settled/work_settled` 与最终Context及资源关闭分别核验，详情见[执行结果与真实收尾](script-execution.md#输出和结果)。

## 当前边界

运行体所有的EM在 `newInstance/newMarriage/startInstance` 方法入口、原生预热副作用前明确拒绝EIM创建，构造边界另有兜底。完整EIM实例地图及其一分钟延迟释放仍在[#216](https://github.com/Diluka/SoloMapling/issues/216)后续；普通频道事件不受影响。

[#219](https://github.com/Diluka/SoloMapling/issues/219)验证的代表包括无收益NPC、任务拒绝、门户提示、机关状态检查及独占EM原init/schedule回调；本轮协议精简的源码、CI、隔离和实际结果另按当前交付记录留证。更多原脚本组合、真实机器人交互与客户端玩法仍须各自证据，不能从原生方法可达推导完成。

官方北斗submodule保持只读。现有十三个 `game_act` 动作及独立管理工具保留，本次不执行[#213](https://github.com/Diluka/SoloMapling/issues/213)直接入口迁移，也不提前完成#205/#211的完整行为模块与共享环境设计。
