# 首批怪物的正常客户端样本

本报告为 #307、#310、#311 保留北斗 V17 图形客户端的正常活动基线。它记录四种怪物在本次窗口内实际产生的运动，供有限 headless 策略和后续验收对照；不认证官方客户端 AI 等价，也不代表候选版本已构建、部署或通过混合场景验收。

## 冻结身份与原件

- 服务源码：Solo `eff9d5a7fd3264676b07c4b9bb0c655d0f918a77`，北斗 `75a86268738ca8845e06ea9d59ec36d15b22c91b`；产物声明源码干净。
- 场景：独立数据库副本，世界0、频道1、地图 `104040000`；世界代次 `3ffd59ac-ea56-4969-a691-2a27dbab6feb`，场景 `scene-f7bfbd4f-7866-4d85-882c-470fb1d01e42`。
- 原生采集：[normal-client-baseline.ndjson](/mnt/data/game-servers/beidou-ms/.backups/20261107-311-monster-scene/isolated/normal-client-baseline.ndjson)，采集ID `capture-4abc04e0-e8aa-4f60-a7e8-47023ebcb5a0`。上海时间 `2026-11-07 13:43:33.466～13:44:33.466`，60秒、100ms检查点，共600帧；无漏采间隔、事件丢失或对象读取失败，结束时采样停止及订阅释放均确认。事件来源覆盖仍为协议声明的部分覆盖。
- 定向汇总：[活动摘要](/mnt/data/game-servers/beidou-ms/.backups/20261107-311-monster-scene/isolated/normal-client-baseline-summary.json)、[运动参数分析](/mnt/data/game-servers/beidou-ms/.backups/20261107-311-monster-scene/isolated/normal-client-baseline-physics.json)。原件优先于汇总结论。
- GUI运行目录：[/client/runtime/runs/20261107T054033.956479Z](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z)。[地面录制](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-ground-client/frames.jsonl) 为13:42:31.605～13:42:43.719，75帧、实际6.19fps、跳过45个计划采样槽；[猪录制](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-pig-client/frames.jsonl) 为13:45:17.726～13:45:35.830，116帧、实际6.41fps、跳过64槽；两段无取帧失败。它们与上述60秒窗口不重叠，不能逐事件配成同一次跳跃的画面证据。

## 输入、原生状态与画面的边界

运动片段取自向同图专用 fixture CID4 派发的完整 `MOVE_MONSTER` 输出。正常 GUI CID6 是控制端；原生 `MoveLifeHandler` 先受理并更新对象，再退回运动数据起点，由 `PacketCreator.rebroadcastMovementList` 逐字节转发。片段因此能证明处理器受理后转发的客户端运动序列，记录位置位于处理器之后；它不是直接入包抓取，也不证明网络接收或画面播放。外层活动/技能字段可能经过原生裁决。

广播入口只记录摘要，完整片段来自 `server_output.client_send`。原生位置、控制者和仇恨来自检查点。下文 `cmd0` 示例中的位置保留线上坐标：`MoveLifeHandler` 将绝对片段的Y减2写入原生对象，构造 headless 输入时对应加2；`cmd1` 两个短整数保留起跳速度语义，不加坐标偏移。

## 四种怪物的60秒基线

600个检查点中的39只怪物均由 GUI CID6 控制，`controller_has_aggro` 均未出现真值。四类均实际移动、停驻并改变方向；本窗口不是受击追击样本。

| 模板 | 实际数量 | 常见稳定行走 `|vx|`（px/s） | 转发片段与姿态 |
| --- | ---: | ---: | --- |
| 100101 | 8 | 62 | `cmd0`；MOVE 2/3、STAND 4/5 |
| 120100 | 10 | 87 | `cmd0`；MOVE 2/3、STAND 4/5 |
| 130101 | 12 | 62 | `cmd0`；MOVE 2/3、STAND 4/5 |
| 1210100 | 9 | 125 | `cmd0`及55个`cmd1`起跳片段；另有JUMP 6/7 |

速度来自实际转发字段，包含停止及加减速样本，表中数值不表示每帧速度。四种均观察到同一OID在多个正 foothold ID 间活动，不能将单条 foothold 边界当成整个活动平台的边界。资料中的 `bodyAttack=1`、无 `attackN/skillN` 与本次普通运动分别取证，不增加不存在的主动攻击。

## 1210100起跳、下降与落地

OID `1000000038` 在UTC `05:43:33.528295055` 的同一个转发序列包含下列片段；X均为906，表中Y为线上值。

| 阶段 | 命令 | Y / 起跳速度Y | 末速度Y（px/s） | foothold | stance | 时长（ms） |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 地面停驻 | 0 | -566 | 0 | 70 | 5 | 180 |
| 起跳 | 1 | -555（速度） | — | — | 7 | 0 |
| 腾空 | 0 | -642 | -15 | 0 | 7 | 270 |
| 下降 | 0 | -574 | 525 | 0 | 7 | 270 |
| 落地位置 | 0 | -565 | 0 | 0 | 7 | 15 |
| 恢复地面 | 0 | -565 | 0 | 70 | 5 | 345 |

两段270ms速度增量均对应 `2000px/s²`；运动分析中55个可计算样本得到相同值。起跳初速为 `-555px/s`。`cmd1` 对原生怪物只更新姿态，实际位置由后续绝对片段推进，不能只发起跳片段并宣布原生坐标已移动。

无仇恨窗口内已经存在随机跳跃，受击或仇恨不是它发生的必要前置。此窗口不确定官方跳跃概率、精确决策周期、寻敌范围或受击后追击规则；根据包接收UTC和片段时长估计的起跳间隔含网络及批处理时延，不用作固定节拍门禁。

## 画面与追加样本

两段PNG录制已完成只读帧审查，详情及路线原件入口见 [GUI-BASELINE-REPORT.md](/mnt/data/game-servers/beidou-ms/.backups/20261107-311-monster-scene/gui-samples/GUI-BASELINE-REPORT.md)。

- 地面录制的镜头和屋顶角色保持固定，[帧0](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-ground-client/frame-000000.png) 至 [帧46](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-ground-client/frame-000046.png) 可见蓝蜗牛、蘑菇仔、红蜗牛的水平位置变化。
- 猪在 [帧34](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-ground-client/frame-000034.png) 贴草面，[帧38](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-ground-client/frame-000038.png) 跃出画面顶边，[帧40](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-ground-client/frame-000040.png) 清楚腾空并呈跳跃姿态，[帧42](/mnt/data/game-servers/beidou-ms/client/runtime/runs/20261107T054033.956479Z/record-normal-ground-client/frame-000042.png) 回到草面。关键窗口为上海时间13:42:36.930～13:42:38.235；画外部分保留为视野缺口。
- 原图能识别种类，不能独立辨认OID。该跳跃早于60秒原生窗口，不能与上文OID `1000000038` 拼成同一次轨迹。猪专项录制的帧0/30也已审查，但角色爬楼使镜头上下移动，不能用屏幕Y差计算跳高。

随后尝试接近猪层以采存活单击后的追击。持续身体接触及击退使GUI角色跌落，有限用药和撤离尝试后实际死亡，隔离EXP由4556降至3209；本阶段未发出攻击，追击、到达停止和受击转向仍未覆盖。结论限定为隔离GUI采样路线失败，新增headless驱动另验。角色已正常复活回地图 `100000000`，HP50/883、EXP3209，并正常关闭至 `STOPPED`、容器Pid0；装备及用药变化保留在专项报告中。

合法攻击后的控制权保持、真人离开后的headless接续、多成员及最后成员离场排空尚未由本报告初稿认证。后续结果须注明对应冻结版本和窗口，不能把不同轮次合并成一次全量通过。

有限实现的实际支持、来源与限制见[怪物驱动](monster-driver.md)；静态资料覆盖见[怪物资料调查](monster-data-report.md)。
