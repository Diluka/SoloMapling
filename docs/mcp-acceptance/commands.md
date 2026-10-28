# 服务端管理指令调试入口

本页描述 #207 的独立管理入口。实际启用状态以
`game_capabilities.debug_commands` 与顶层 `enabled_tools` 为准；客户端列出工具
不代表当前服务已经开启。源码默认关闭，配置只维护在本地 `application.yml`：

```yaml
solomapling:
  acceptance:
    enabled: true
    actors-enabled: true
    debug-commands-enabled: true
```

## 原生服务与目标

北斗的 `give` 是 `GiveController` / `GiveService` 的管理资源发放功能，并非已核实的
游戏内 `@give` 命令。本入口直接调用原生管理服务，不经过 `CharacterRuntime`、
模块 scope/Grant 或角色 lane。角色、背包与存档数据由北斗持有，普通 chat 的命令
拒绝规则不变。新增工具独立于 `game_act.actions`。

沿正常管理端 JWT 和管理员授权，只使用已有 `actor-profiles` 登记的在线普通专用
账号/角色。工具不接收密码、任意玩家 ID 或全服目标，不提升 GM/webadmin，不操作
环境机器人。角色模块是否活跃不授予或撤销管理权限。

首轮仅普通 USE/ETC 可堆叠物品，拒绝装备、宠物、现金、任务、可充值及其他未开放
类型，不透传全服发放、GM 提权或地图切换。物品存在性和原生槽位规则在服务端核对，
Python 不复制 WZ 规则。给物品只是调试准备，不替代 #200 动作或礼包流程的验收。

## 查询与调用

先通过 `game_command_help()`、`game_command_help(command="give")` 或能力公告取得
已开放范围和本进程的 `command_generation`。调用示意（代次须使用实际返回值）：

```json
{
  "profile_id": "primary",
  "request_id": "give-debug-unique-1",
  "expected_command_generation": "command-generation-from-help",
  "command": "give",
  "parameters": {"item_id": 2000000, "quantity": 1}
}
```

`item_id` 为正整数，`quantity` 为 1..32767 的整数；布尔值、浮点数、数值字符串
和多余字段拒绝。管理代次独立于世界、actor session 和模块代次。

| 工具 | HTTP 入口 |
| --- | --- |
| `game_command_help(command?)` | `GET /solo/acceptance/v1/command/help`，可选 command 查询参数 |
| `game_command(...)` | `POST /solo/acceptance/v1/command/execute`；requestId 在原生信封外层，其余参数在 data |

## 结果与恢复

同步执行；重复的进行中请求可返回 `RUNNING`，终态为 `SUCCEEDED`、`FAILED` 或
`UNKNOWN`。结果包含 command_id、原 request_id、terminal、native_dispatched、固定
reason 和可取得的 quantity_before/quantity_after。`native_dispatched=null` 表示派发
事实未知。成功必须取得真实库存增量，void 返回或 HTTP 成功不能单独作为成功证据。

历史按管理员身份和请求 ID 隔离，本进程最多256条且不淘汰；满容量明确拒绝。
同 ID 同参数返回原记录，目标随后离线也不重新执行；同 ID 变参拒绝。超时或未知
结果只用原 ID、原参数和原管理代次恢复，不换新 ID 重发。重启后的旧代次拒绝；
不能刷新代次替未知的旧请求再次发放。

管理命令不接入 actor 的 operation_get/operation_cancel，没有撤回接口。
`persisted_state_verified=false` 表示本结果不证明存档，需正常退出后同 CID 重开，
独立核对逻辑物品、数量和槽位。

后续 #199 可把同一适配绑定成脚本函数；#204 管理脚本环境和本次脚本生命周期，
不因此增加管理命令的角色 lane 前置。脚本中断停止后续步骤，不能撤回已发物品。
#199/#204 不是本入口的交付前置。
