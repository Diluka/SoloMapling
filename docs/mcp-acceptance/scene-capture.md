# 场景状态基线与输出录制

`scene_snapshot(scene_ref, output_file?)` 读取当前场景基线；`scene_record(scene_ref, output_file, duration_ms?, interval_ms?)` 保存开始基线、真实输出事件与周期状态检查点。采集只读已加载的实际场景，不需要 `actor_open`，不创建地图、角色或模拟宿主，不分配怪物控制权，也不为采集构造初始化包或补发消息。沿现有管理员 JWT 和观察权限，只有 `solomapling.acceptance.enabled` 开启时提供。

`scene_ref` 来自 `game_observe(scope="world")`。采集固定其世界代次、频道、原生地图对象及事件实例；同地图 ID 的其他实例不混用，角色换图不会使录制跟随角色。场景销毁、被同 ID 新对象替换或世界代次变化会拒绝或提前结束采集。

## 文件和时间

格式版本为 `schema_version=2`。快照是 UTF-8 JSON；录制是逐条刷新、可在录制期间读取的 UTF-8 NDJSON。文件保存在 MCP 宿主，父目录须已存在，目标文件须尚不存在；不覆盖旧证据。写出失败保留完整前缀，缺少 `end` 的文件属于部分记录。

录制顺序为 `header`、一次 `baseline`、若干独立 `events` 批次与 `checkpoint`、`end`。`baseline/checkpoint` 共用从 1 开始的 `seq`，`events` 使用独立序号；结束摘要分别记录 `frame_count` 和 `event_batch_count`。取消可能发生在基线写出前。

默认 10000ms/100ms，时长支持 100～120000ms，间隔支持 50～5000ms 且不超过时长。间隔仅控制事件批次刷新和状态检查点。移动、攻击等动作来自真实消息，短于间隔的动作仍逐条保存，不由状态差分推导。检查点错过的周期记入 `missed_intervals`，不复制旧帧补齐。

订阅先于基线读取建立，读取期间发生的事件保存在后续事件批次中。基线包含真实读取起止时间，属于非原子的读取区间；区间内事件可能与基线重叠，不能把它们机械重放成一个精确客户端。中途开始采集没有入场前历史，也不会补造先前初始化输出。

无文件快照上限 4MiB；每条逻辑记录上限 32MiB，每段录制上限 256MiB。最多同时 4 段录制，终态安全摘要最多 256 条且不持有原生引用。事件窗口每订阅 4096 条，大小限制、丢失和读取失败明确表达。限制以 capabilities 的 `scene_capture` 为准。

## 全场基线与客户端可见内容

快照结构保持 `identity`、`source`、`static_scene` 和 `frame`。`identity` 固定场景、世界代次、世界/频道/地图及实例键，`source` 使用服务包公开源码身份，不读取私密应用配置。

`static_scene` 保存原生地图边界、foothold 端点与关联、梯绳、门户及可读物理参数。`frame.native` 按 players、monsters、items、npcs、reactors、portals、pets、summons、mists、dragons、other_objects 分类，保留完整原生对象副本和数量/读取失败信息，不沿用普通观察的 100 条限制。

位置、姿态、方向、职业、武器、Buff/异常及对象属性读取原生对象，语义参照原生初始化与更新来源。`stance` 是最近原生移动姿态，攻击动作由独立消息表示。HP/MP、控制者资格、物品归属和 `simulation` 等额外服务端诊断信息在 coverage 中标注，不能视为所有客户端收到的属性。

每个角色的 `native_visible_objects` 是原生远程对象可见性集合的当前副本。它不包含完整客户端状态，也不替代原生非范围对象、隐藏对象和逐消息排除规则。快照明确为全场服务端视图，`snapshot_view=server_scene_state_not_recipient_initialization`，不声称等同某个接收者的初始化包。

OID 不被包装成可靠的生成代次；对象生命周期代次未知时明确为 `unknown`。速度不从位置差分伪装成原生值。角色 `changing_maps` 直接读取原生状态，观察不会触发入场确认。`simulation` 只读取已有场景宿主，不启动模拟。

## 真实输出与诊断事件

每个事件带 `category`、`source`、来源序号、观察时间与 `values`：

- `category=output` 记录已声明场景消息在最终发送路径中的实际目标。普通 Client 在原 `announcerLock` 内 `writeAndFlush` 返回后记录，地图范围过滤和排除规则已由原生完成。`recipient` 标明 CID、客户端来源和本次订阅日志内的传输引用；`recipient_seq` 表示该接收者的受关注消息顺序。它不声称跨接收者的全局游戏因果顺序。
- AcceptanceClient 和 BotClient 没有网络 socket，记录其原有场景消息消费路径，明确为 `headless_scene_dispatch_returned`，不伪称网络提交或内存包缓冲的严格顺序。
- `category=diagnostic` 保存原生对象成员、HP、controller、姿态、攻击解析/结算等回调，以及广播入口摘要。广播入口不等于最终发送，处理器返回不等于命中成功。

输出消息保留 opcode、消息类别、关键协议字段、关联对象、解码完整性和已声明消息的原始协议字节。攻击保留动作、方向、速度、技能、目标与伤害行；属性更新保留消息实际携带的字段及新值。复杂初始化或尚未解开的字段如实标记 partial，原始字节可用于匹配版本协议复核。未知或损坏消息不补造字段；无关聊天、认证等消息不采集正文。具体已支持 opcode 与缺口由 `scene_capture.event_coverage.message_projection` 公告。

普通网络记录只证明原生传输提交，未观察 socket 完成、客户端接收或播放。匹配版本客户端的消息格式、动作语义、接收者和顺序可以据此核对；真人画面属于辅助冒烟，单独记录。

事件批次包含 `total_since_last`、`dropped`、`gap`、`probe_read_failures` 和 `subscription_closed`。读取失败不打断原生发送。最后一个检查点之后的冻结事件进入 `end.events`；因大小限制未写出的批次可保留在 `end.unwritten_frame_events`。采样正常结束不代表全部协议覆盖，`coverage.complete` 继续为 false。

## 结束和收尾

时长结束、取消、断流、文件失败、场景销毁和旧代次分别保留结束原因。只有原采集载体真实停止且订阅释放后，才确认 `sampling_stopped` 和 `subscription_released`。桥接未取得证明时报告 `UNCONFIRMED`，不能补造完整结束记录。

验收按只读、身份、协议字段/接收者/顺序、短暂事件保留、文件不覆盖与真实释放的最小合同，以及有界真实入场/移动/合法攻击冒烟执行。随机路线、掉落和某秒击杀不作为固定门禁。源码、隔离运行、实际部署和真人画面证据分别保留。
