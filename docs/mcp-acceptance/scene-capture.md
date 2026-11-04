# 场景数据快照与录制

`scene_snapshot(scene_ref, output_file?)` 保存一次场景数据帧；`scene_record(scene_ref, output_file, duration_ms?, interval_ms?)` 在一次后端请求中持续采样并流回数据。两个工具读取已加载的实际场景，不需要 `actor_open`，不创建地图、角色或场景模拟，不分配怪物控制权。工具沿现有管理员 JWT 和观察权限，只有 `solomapling.acceptance.enabled` 开启时提供。

`scene_ref` 来自 `game_observe(scope="world")` 的实际场景目录。采集绑定该引用对应的世界代次、频道、原生地图对象和事件实例；角色换图表现为原场景成员变化，录制不会跟随角色。已销毁、被同 ID 新对象替换或世界代次变化的目标会拒绝采集或提前结束。

本功能记录服务端诊断数据。原生状态、场景模拟摘要和服务端输出事件各有来源；服务器发送路径不能证明真人客户端已收到或播放。

## 工具与文件

- 单帧：不传文件时返回包含 `schema_version`、`identity`、`source`、`static_scene` 和 `frame` 的结构化值；传文件时使用同一模型写入 UTF-8 JSON。
- 连续录制：文件为 UTF-8 NDJSON，每条完整记录写完即刷新，录制期间可读取已有内容。顺序为 `header`、若干 `frame`、`end`。
- 文件保存在 MCP 宿主，不写进游戏容器。父目录必须存在，目标文件必须尚不存在；已有证据不覆盖。输出失败时保留已完成记录，不将缺少 `end` 的前缀称为完整文件。

默认时长为 10000ms，支持 100～120000ms；默认间隔为 100ms，支持 50～5000ms，间隔不能大于时长。第一帧立即采样；之后按单调时钟调度，错过周期记入 `missed_intervals`，不复制旧帧补帧。

当前边界由 capabilities 的 `scene_capture` 公告：无文件的单帧响应上限为 4MiB；每条逻辑 JSON/NDJSON 记录上限为 32MiB；每次录制总输出上限为 256MiB，后端为结束摘要预留一条记录容量。触及大小限制会明确失败或以部分结果结束，不按对象数量静默裁切。每进程最多同时录制 4 次；最多保留 256 条终态安全摘要，按 FIFO 淘汰，摘要不持有原生对象。

## 数据结构

`identity` 包含 `scene_ref`、世界代次、世界/频道/地图 ID、`map_instance_key` 及 `runtime_key`。源码身份来自服务包公开 manifest；不可取得时明确 `available=false`，不读取应用私密配置。

`static_scene` 保存地图边界、foothold 端点及关联、梯绳、门户位置/目标和可读物理参数。平台以原生 foothold 表示，重力等当前未覆盖字段在 coverage 中列出。静态投影失败时返回 `read_status=unavailable` 和固定原因，动态采集仍可继续。

动态 `frame` 包含：

| 字段 | 含义 |
| --- | --- |
| `seq` | 从 1 开始的本次已写帧序号。 |
| `started_at` / `finished_at` | 本帧真实采样起止时间。 |
| `elapsed_ms` | 从本次采集起点计算的单调相对时间。 |
| `native` | 原生对象的独立值投影。 |
| `simulation` | 已存在场景运行体的只读摘要；不存在时为不可用，不启动模拟。 |
| `events` | 上一帧至本帧的已覆盖事件；单帧快照明确没有观察时间窗。 |
| `coverage` | 当前未支持字段、读取失败、生命周期身份和一致性范围。 |

`native` 按 players、monsters、items、npcs、reactors、portals、pets、summons、mists、dragons 及 other_objects 分类。每类包含 `total_count`、`recorded_count`、`failed_count`、`complete`、`truncated` 和 `items`。读取原生地图完整对象副本，不沿用普通观察的 100 条默认限制。宠物拥有者数组无法读取时，该类总数为未知，不能用 0 或重复角色代替。

角色包含实际 HP/MP、上限、姿态/朝向、职业、当前武器、来源与可读 Buff/异常摘要；怪物包含 HP/MP、姿态、当前 controller、客户端来源、有效驱动资格和仇恨标志。地物保留物品/金币、数量、位置和可读归属/拾取状态。未确认的原生对象生命周期代次始终为 `unknown`，OID 不被包装成可靠的生成身份；速度不从位置差异伪装成原生值。

每帧标记 `server_diagnostic / best_effort`，采样期间的对象变化可形成读取失败或部分覆盖，不承诺全场景同一时刻原子冻结。

## 事件来源和缺口

只读探针仅在目标场景有订阅时收集安全值，覆盖已接入的原生对象出现/移除、角色生命与姿态、怪物生命和 controller、攻击/受击派发结果及地图广播摘要。精确来源列表和未覆盖路径见 `scene_capture.event_coverage`。

事件记录 `source`、该来源的 `source_seq`、`kind`、`observed_at`、`observed_monotonic_ns`、`time_type=observed` 和 `values`。这些是探针观察顺序和观察时间，不是跨来源的原子顺序或未经证实的原生发生时间。

每个订阅窗口最多保留 4096 条事件。`events` 带有 `total_since_last`、`dropped`、`gap`、`probe_read_failures` 和 `subscription_closed`；溢出丢失和读取失败会显式保留。最后一次帧之后的冻结事件写入 `end.events`；因大小限制未写出的采样事件如可保留，写入 `end.unwritten_frame_events`。短于采样间隔的事件由这些已声明的原生来源记录，其他未接入路径继续标为未覆盖。

## 结束与停止确认

`end` 和后端终态将以下事实分别表达：

- `file_complete`：后端已将完整结束记录写入本次HTTP流。目标失效或主动取消也可以写出完整end；此字段单独不能证明MCP宿主文件已经落盘。
- `completed_normally`、`recording_complete`：本次按请求时长完成；提前取消、失效或大小限制为 false。
- `sampling_stopped`：本次采样循环已实际退出。
- `subscription_released`：本次原场景事件订阅已同步解除。
- `coverage.complete`：数据覆盖是否完整；当前原生诊断及事件来源仍有明确缺口，保持 false。

摘要另含 `capture_id`、`reason`、真实起止时间、帧数、实际时长、采样设置、错过周期、事件丢失及读取失败计数。常见原因有 `duration_elapsed`、`cancelled`、`interrupted`、`runtime_changed`、`stale_scene_ref`、`scene_destroyed`、`recording_size_limit` 和 `output_failed`。

HTTP 内部入口为 `POST /solo/acceptance/v1/scene/snapshot`、`POST /scene/record`、`GET /scene/record/{capture_id}` 和 `POST /scene/record/{capture_id}/cancel`，均以 `/solo/acceptance/v1` 为前缀并绑定管理员身份。采样直接在本次流的服务载体执行，没有脱离连接继续运行的后台 sampler。取消只唤醒本次载体，终态在真实收尾后登记；查询不允许用其他 owner 的 ID 读取或取消。

MCP 中断、连接或文件写入失败时，桥关闭流并按已收到的 `capture_id` 请求取消、查询真实终态。只有已确认 `sampling_stopped` 与 `subscription_released` 才能报告后端停止。尚未收到 ID、查询失败或超时均保留停止未确认，已有文件保持部分状态。正常录制完成与覆盖完整分别验收，采集本身不得改变角色、controller 或游戏活动。

宿主文件完整性以MCP调用结果、文件实际包含的end及成功关闭文件为准。真实写盘失败时，后端可能已把end写入流并在终态保留 `file_complete=true`，同时本地文件仍缺少end；此时MCP返回写盘错误及 `recording_complete=false`，已经确认的后端停止事实仍可为 `CONFIRMED`。不要用后端流写出事实覆盖本地文件失败。
