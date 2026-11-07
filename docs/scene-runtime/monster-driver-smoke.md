# 首批怪物驱动的有界冒烟

目标是确认四种首批怪物在角色待命时仍有原生活动，受击后的目标与运动接续正确，真人和多个headless成员之间只有一个有效控制端，离场后原工作与资源真实释放。本文件规定流程和判据；每轮结果单独记为通过、产品失败或未覆盖。

## 冻结版本与前置

- 本轮参考候选：Solo `d839864f3c3709ad901a94ebc9e489953bb2e2dc`，北斗 `75a86268738ca8845e06ea9d59ec36d15b22c91b`；镜像 `sha256:fe572c88a8230a048ad01d327e1535a9c944b091f1ccd6ffdbe9d2e1cba05823`，JAR SHA256 `f92ec364aec6120b9571999368d77b83def2be85d2f1f27f32ea96f2c8b82644`。重验时重新核实际容器、JAR、构建报告与源码身份，不借用旧世界引用。
- 使用独立数据库副本、独立Compose项目及internal网络，无正式端口或真实数据目录挂载。本轮项目为 `beidou311candidate`；环境与精确资源从本轮 `environment.json` 核对，禁止对正式数据源执行实验写入。
- 普通fixture仅 account5/CID4；GUI仅本地私密登记的专用普通账号。图形客户端始终由一名明确操作者控制，SDK操作者不交叉发送键鼠或生命周期命令。
- 以实际world、scene及原生对象核对地图 `104040000` 的100101、120100、130101、1210100，保存实际数量、OID、controller和动态前态。停用本次fixture的自动狩猎、拾取、恢复与战术活动；保留场景常驻驱动，不刷怪制造结果。
- 多成员由现有 `robots.create` 工厂路径分别创建两份独立环境来源，每份保存自己的receipt、来源与原生对象身份；使用已注册scope/Grant和角色唯一lane，不新增任意测试API。
- 单次受击场景先核专用fixture合法 `1302000`、WATK1及本轮属性，确认目标存活且处于合法攻击距离。管理员位置准备及原生 `changeMap` + 入场ACK单独归因，不计作普通导航或门户通过。

## 操作、预算与判据

每段先建立当前场景的 `scene_record`，确认header已经写出后再触发动作；检查点可用100ms，原请求ID、operation/session、世界代次与scene引用写入该段证据。

| 场景 | 有限操作与预算 | 观察判据 |
| --- | --- | --- |
| 纯headless待命 | fixture正常登录并准备到安全位置，30秒不狩猎、不攻击 | 四模板真实位置/方向变化，原生派发及位置确认；猪起跳、腾空和落地分别采样，沿实际OID核连通foothold及独立落地面。 |
| 多headless | 按各自receipt创建两份环境成员，三成员同图采30秒 | 成员数量与来源准确，每只怪只有当前有效controller；各来源均沿自身授权执行，记录实际分配，不要求固定数量或平均分配。 |
| 一次合法命中 | `game_act.attack`正常计算单次伤害，随后用已有导航/有界移动向两侧移动并停下，30秒 | 原生HP与damage回调确认命中，仇恨目标来自原生事实；怪物方向随目标实际位置变化，到达后停止。伤害、controller和动作分别核实。 |
| 同图重入 | 受控原生换图/ACK回到同一实际Map对象，两轮各15秒 | 新入场绑定生效，旧绑定失效，驱动保持或正常接续；保留每轮控制者、工作及清理事实，禁止旧引用续权。 |
| 不同场景往返 | 到另一个实际Map对象再返回，逐段有界观察 | 原场景的目标与成员正确离开，新场景独立建立资格；返回时读取当前scene与OID，不能按地图ID沿用旧对象。 |
| GUI接管与退出 | 独立GUI操作者正常登录，普通 `east00` / `west00` 往返及 `close` 各自验收 | 真人优先接管，原headless停止旧控制输入；真人离开后有效headless接续。普通门户、图形播放、关闭与服务端状态分别判断。 |

随机动作未在预算内出现时记采样缺口；补采另列版本和窗口，不固定路线、起跳次数或时间作为门禁。自然死亡按真实接触、HP归零及原生死亡事实归因；低HP等管理员准备单列。准备失败、路线失败和待测业务失败分别记录，不以直接赋HP0代替自然死亡。

## 分层观察与失败恢复

- 原生层读取HP、位置、姿态、controller/仇恨；驱动层读取实际派发、位置确认、原work与scope状态；输出层保存完整运动片段和接收来源；GUI用实际PNG/录制核播放，并记录帧率、视野和漏采。
- 怪物广播排除当前controller。同一控制端自身的响应不含完整运动序列，协议采样需要第二个同图headless接收者。`MOVE_MONSTER`片段是原生handler受理后逐字节转发的输出，不能称为直接入包或真人收到/播放。
- 脚本facts仅返回必要标量与简短scope摘要，避免大对象树撞上16KiB结果边界；原始录制写文件。截断、异常或超时先按原请求与receipt核实际副作用，禁止整批盲目重放。
- 出现多余成员、无效controller、旧授权输入、未知创建/释放或未退出工作时必须查明；Future/operation终态和取消请求各自不能证明真正排空。

## 收尾与证据

1. 依原receipt逐份移除自有环境实例，核原registry对象、地图对象、runtime、lane与scope真实结束；关闭所有自有脚本Context，成员应按本轮实际创建顺序逐一减少。
2. fixture正常保存退出，同CID4重新登录并比较本轮必要持久字段，将合法伤害、移动和准备增量逐项归因，最后正常关闭；GUI另核STOPPED及所属容器、网络释放。
3. 全部capture核 `sampling_stopped`、`subscription_released`及最终end；最后成员离场后核场景关闭、旧绑定失效、controller精确释放及后台排空，再清理本轮精确资源。
4. 保留冻结版本、请求/receipt、前后snapshot、原始NDJSON、画面、失败与收尾。参考本轮 [候选计划](/mnt/data/game-servers/beidou-ms/.backups/20261107-311-monster-scene/candidate/CANDIDATE-PLAN.md)、候选目录内分段原件和[正常客户端样本](monster-client-samples.md)，不提前将进行中的阶段记为通过。

入口与协议沿[机器人测试技能](../../skills/solomapling-bot-testing/SKILL.md)、[游戏交互技能](../../skills/solomapling-game-interaction/SKILL.md)、[场景采集](../mcp-acceptance/scene-capture.md)、[模块协议](../robot-runtime/modules.md)；算法来源和当前支持见[怪物驱动](monster-driver.md)。
