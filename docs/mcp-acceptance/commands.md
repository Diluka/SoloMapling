# 通用原生命令执行

本入口复用北斗现有 `CommandsExecutor.handle(Client, String)`，完整命令文本原样传入。
命令注册、参数解析、启停、GM等级和具体行为仍由原生系统处理；桥不维护命令或物品白名单，
不改变普通角色的GM等级，也不绕过原生权限。旧 #207 的受限GiveService/type5包装已移除，
不再接受 `command="give"` 加 `parameters` 的旧结构。

## 脚本函数

每个受管北斗原生引擎在原文件首次eval前只注入一个执行函数：

```javascript
executeCommand("@time");
```

函数只接受一个非空字符串；使用该原生环境的实际Client，直接调用共享
`soloMapling.game.command.NativeCommandExecutor.execute`。同引擎的后续source和原生回调
复用同一个函数。调用仍需该环境的原生绑定就绪；函数在首次eval前安装，不代表可绕过事件等环境的初始化资格。结果是普通JS JSON数据，不返回Client/角色等Java对象。脚本不注入帮助函数。

示例的前缀和命令是否可用依原生注册及权限；普通GM0角色不会因为脚本调用而获得GM权限。
纯语言运算、环境生命周期和原生上下文规则继续沿北斗脚本接口，本片不增加独立纯JS模式
或新的调度队列。脚本停止后不再推进后续调用，已发生的命令效果不撤回。

## MCP 接口

`game_command` 解析已登记在线profile的真实Client，然后调用同一共享执行器，
不通过角色lane，也不为MCP实现第二份命令逻辑。管理员JWT、已登记普通专用目标和
配置开关保持原有边界；角色/游戏数据仍由北斗持有。

```json
{
  "profile_id": "primary",
  "request_id": "native-command-unique-1",
  "expected_command_generation": "command-generation-from-capabilities",
  "command": "@time"
}
```

`command` 是完整文本，保留前缀、大小写和空格，不在桥中拆分或改写；最多16384个
Unicode字符，拒绝非字符串、全空白、不成对surrogate和多余字段。脚本调用及HTTP/MCP
输入仍分别受到各自已有的总输入预算约束。

命令查阅由[游戏交互技能](../../skills/solomapling-game-interaction/SKILL.md)提供。其脚本复用正常管理员鉴权，调用北斗已有的只读 `POST /command/v1/getCommandListFromDB`，取得语法、权限、启停与处理类映射；参数和副作用直接查处理类及语言资源。独立执行使用 `POST /solo/acceptance/v1/command/execute`，requestId 放信封外层，其余字段放 data；管理代次从 capabilities 取得。

源码默认关闭独立MCP命令工具，在本地application.yml保留原配置并开启：

```yaml
solomapling:
  acceptance:
    enabled: true
    actors-enabled: true
    debug-commands-enabled: true
```

这个开关只管理独立MCP入口，不改变原生脚本本来具备的Java API访问能力。
实际开放以 `game_capabilities.debug_commands` 和顶层enabled_tools为准。

## 调用事实与结果

原生handle返回void，可能在未知命令、权限不足或Client忙时发提示后正常return。
因此 `status=RETURNED`、`native_handler_invoked=true`、`native_handler_returned=true`
只表示调用已返回，`command_outcome=UNREPORTED` 不确认命令业务成功。需要通过原生
反馈包或实际状态判断效果，不能只看JS无异常、HTTP200或MCP的is_error=false。管理员黄字反馈沿actor包窗口的yellow_tip读取（原生0x4D），与普通聊天广播分开，保留原编码、有界文本和游标语义。Client忙碌提示沿原有server_message（0x44/type5）读取；原生动作信号量有7个许可，NPC回调持有一份许可并不意味着嵌套命令必然拒绝。本片不改变许可或锁规则。
原生调用异常返回UNKNOWN；目标资格失败等本层拒绝则不调用原生处理器。

MCP按管理员身份/request_id保留本进程最多256条历史，不淘汰；满容量明确拒绝。
同ID同参数恢复原结果，即使目标已离线也不重新执行；变参或旧管理进程代次拒绝。
命令正文只在本次调用中使用，历史保存SHA256签名而非命令文本。超时或UNKNOWN只用
原ID、原参数和原代次恢复，不换新ID重发。管理代次与世界/角色/模块代次独立。

脚本调用沿原script_run请求的幂等、上下文和生命周期；每次合法函数调用是一次原生命令
调用，不增加独立MCP历史或第二套工作账本。具体命令是否保存数据由原生实现决定，
这个通用返回不提供通用物品增量或存档成功声明。
