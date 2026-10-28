# 结果与恢复

## 为每次写请求保留检查点

保存目标环境、请求 ID、完整参数、会话与代次、已知 operation ID，以及最近观察的场景和包游标。记录来自本次任务即可，不把私密配置或正文复制进源码。

`actor_open`、`actor_close`、`game_act` 和脚本操作可通过已知 `operation_id` 查询；请求传输结果未知时，原 ID 和完整原参数用于恢复原记录。相同 ID 改参数属于另一项请求，不能用它重试。原请求尚不能确认是否派发时，先查询或恢复，保留未知结论。

管理命令的独立恢复方式见[原生命令](commands.md)。

## 对照事实判断结果

| 已取得事实 | 可作出的判断 |
|---|---|
| 请求受理、QUEUED、HTTP 返回 | 已受理或传输完成，继续读取实际结果 |
| 自身 CID 与原文匹配的新聊天回声 | 自身广播已发生；目标回复另行确认 |
| 实际位移、库存或属性前后事实 | 报告对应变化及其完整性 |
| 命令 RETURNED／UNREPORTED | 原生处理器已返回，继续核反馈与后态 |
| 取消请求已受理 | 正在请求停止，继续核实际 work 与清理 |
| 角色关闭且原生清理、声明保存已核实 | 可报告该范围正常离线与保存 |

部分变化已经发生、后续读取失败或取消时保留已知事实。未知效果不因取消而回滚，取消请求也不证明后台已退出。

## 区分四种停止

- `operation_cancel` 请求停止某次操作，继续查询其真实终态。
- `stop` 停止当前移动，核对原移动结果和实际停止事实；保留已发生位移。
- `npc_cancel` 通过当前对话版本结束对话，核对原生 CM/QM 清理。
- `actor_close` 关闭角色根生命周期，核对相关工作退出、原生离线和声明保存。

Future 已取消、队列暂空或 operation 的单个状态字段不能代替实际停止与清理。任务需要退出时继续完成关闭；后端保留未知或隔离状态时，将其作为待处理事实报告。

## 失效引用

会话过期时核对当前会话／操作，再按任务重新打开；场景过期时重新观察当前 world 和 actor，获取真实场景。服务或世界换代后重新取得能力、会话和全部引用。对话版本只来自最新有效对话，不能把旧版替换成猜测值继续回复。

源码入口：[ActorControlService](../../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/ActorControlService.java)、[bridge.py](../../../tools/acceptance-mcp/src/solo_acceptance_mcp/bridge.py) 的 `BridgeError`、写调用及错误映射，以及 [DebugCommandService](../../../beidou-integration/src/main/java/org/gms/soloacceptance/command/DebugCommandService.java)。
