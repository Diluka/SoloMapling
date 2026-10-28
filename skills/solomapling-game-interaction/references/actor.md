# 会话与观察

## 定位接口

以下源码路径以 SoloMapling 仓库根为基准；执行命令时将工作目录设为该根目录。

- [server.py](../../../tools/acceptance-mcp/src/solo_acceptance_mcp/server.py)：`game_capabilities`、`game_observe`、`actor_open`、`actor_close`、`operation_get` 的当前参数及工具描述。
- [ActorControlService](../../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/ActorControlService.java)：查 `open`、`observe`、`get`、`actorSnapshot`、`response` 及结果字段。
- [AcceptanceObservationService](../../../beidou-integration/src/main/java/org/gms/soloacceptance/AcceptanceObservationService.java)：世界、场景目录和当前能力来源。

## 只读查看

先读 capabilities，确认服务在线和所需观察范围。调用 `game_observe(scope="world")`，从结果选择实际场景，再传原 `scene_ref` 查看 scene。world 只列已加载场景，不因查询创建地图。相同地图 ID 的不同实例分别定位。

只需要查看机器人在哪里时，到能回答问题的场景事实即可。需要角色实际看到的聊天、提示或 NPC 包时，才使用已打开会话的 actor 观察。

actor 观察使用实际 `session_id` 和会话代次 `expected_generation`。其 `since_cursor` 是该会话的包序号，与 `operation_get` 的生命周期事件游标分开。读取后保存返回游标，按实际分页／截断标记继续读取；较早正文已淘汰时明确保留证据缺口。actor 当前场景不能代替历史包发生时的场景。

## 打开专用角色

任务需要发言或游戏动作时，使用已有 profile 和唯一 `request_id` 调用 `actor_open`。profile 来自本地配置，工具不接受密码或任意玩家 ID。

打开返回排队状态后，保存 `operation_id` 并用 `operation_get` 查询。根据实际 `native_actor_online` 结果、原生在线角色及场景确认登录，再保存返回的 `session_id/session_generation`。`expected_generation` 使用会话代次；`expected_context.runtime_generation` 使用世界代次，两者分别保存。

随后读取 actor 取得当前真实场景、角色状态和新包游标。会话只在当前服务、当前角色及其代次中有效。

## 一个最小交互

用户要求“进游戏和机器人打招呼”时：

1. 从 world／scene 定位目标机器人和实际场景。
2. 正常打开专用角色，确认在线；核对角色与目标确实在同一场景。
3. 保存 actor 包游标，发送一次带新请求 ID 的正常聊天。
4. 查询聊天 operation，并从新包分别读取自身广播和目标回复。
5. 把自身发言成功与机器人是否回应分别报告；任务结束后正常退出。

目标不在角色当前场景时，先说明实际差异。按用户授权和现有能力处理场景准备，不能从同地图 ID 或机器人名字推断已经同场景。

## 退出与重新进入

使用原会话和新 close 请求 ID 正常 `actor_close`，继续查询到关闭结果。确认原生清理及其声明范围内的保存；背包、装备等具体持久结果按需要在同 CID 重开后独立核对。

服务或世界重启后重新读取 capabilities 和 world。新会话、场景及对话版本从新结果取得；旧引用不延续到新世界。
