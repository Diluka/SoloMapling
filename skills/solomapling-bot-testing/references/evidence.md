# 交互与证据

## 关联请求、目标与回复

通过 [游戏交互技能](../../solomapling-game-interaction/SKILL.md) 执行输入，保存原 request_id、operation_id、会话代次、期望场景及操作结果。从输入前的包游标继续读取 actor 事件，记录响应序号、发送者、正文和可见提示。

[SceneSnapshotMapper](../../../beidou-integration/src/main/java/org/gms/soloacceptance/SceneSnapshotMapper.java) 的角色对象含 `id/name/is_bot`；[PacketEventDecoder](../../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/PacketEventDecoder.java) 的聊天事件含 `sender_character_id/text/show`。用发送者 CID 关联本次目标。自己的新聊天回声证明自己的广播，目标 CID 发出的新包才构成目标发言证据；结合触发前后与正文判断是否属于本次交互，周围机器人的环境闲聊另记。

`player_hint` 是单独的玩家提示事件，不等同 NPC 对话或聊天正文。观察正文或窗口截断时记录缺失范围；包游标与 operation 生命周期游标分别保存。状态字段、事件含义和分页规则直接查现有 decoder、包缓冲与操作实现，不在这里维护另一份协议表。

## SocialBot 代表流程

1. 观察并确认本次目标与场景，记录待验证的是单次回复还是持续交互。
2. 按 [Dispatcher](../../../src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java) 的名称匹配规则发出一次准确点名，核对自己的广播。
3. 沿当前调度观察目标新发言及提示。根据 [SocialBot](../../../src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java) 的实际分支，区分问候、单次回复、话题菜单、忙碌或退化响应。
4. 本次确需后续话题时，依据实际提示和 `handleDialogueChoice` 选择当前可用输入。后续普通消息的路由与首次点名分别核对。
5. 核对目标回复和预期后态。告别、超时或退出的结果按当前原生会话路径确认。

[SocialCommands](../../../src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/SocialCommands.java) 定义聊天广播、气泡及提示的真实发送方式；[中文对话资源](../../../src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack-zh-CN/SocialBotDialogue.yaml) 是 YAML 回复的来源。回复内容有随机选择时，验证来源、语义类别和实际发生事实，不预设固定句子。

## 区分实现路径

| 观察事实 | 可得结论 |
|---|---|
| 自己的 `chat_text` 匹配本次输入 | 玩家发言已广播 |
| 目标 CID 的新发言与本次触发相符 | 目标产生了相应可见回复 |
| 当前菜单或话题提示 | 相应提示已送达；下一步按实际会话继续 |
| 命令 `RETURNED/UNREPORTED` | 原生处理器已返回，效果继续查反馈和状态 |
| 脚本成功并返回 JSON | 本次求值完成，游戏副作用及最终资源状态另核 |
| 明确的真实提供方调用和目标可见回复 | 分别记录模型调用与游戏内回复；两者不能互相替代 |

模型关闭、失败回退到 YAML，以及本身走 YAML 话题时，都不能将普通回复归为真实模型验收。查看当前配置和调用事实时只记录所需的启用、路径与结果信息，凭据留在私密配置中。

缺少目标回复时，先核对会话、场景、类型、实际派发、等待路径、窗口完整性和正常规则。能稳定重现且已排除这些前置原因，再形成独立缺陷；证据不完整时明确缺哪一段。
