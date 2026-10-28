# 北斗单 JAR 集成构建

本目录将原版 SoloMapling 适配到官方北斗 `1.12`。北斗由
`upstream/BeiDou-Server` submodule 提供，固定在
`75a86268738ca8845e06ea9d59ec36d15b22c91b`；它的源码、POM 和工作区保持原样。
所有适配代码、AspectJ 切面和打包规则均由 SoloMapling 工程维护。

构建先把北斗的已提交源码按原样导出到 `target/engine-source`，正常编译出
Spring Boot JAR，再对其应用类执行二进制织入。最终产物为仓库根目录的
`target/SoloMapling-BeiDou.jar`。北斗仓库没有修改，但派生产物中的部分北斗
字节码会改变，具体类清单、源码指纹和 SHA256 记录在
`beidou-integration/target/build-report.json`。构建期间源码、测试或运行资源变化时会失败，
需等待编辑完成后重试。

## 构建

要求 Python 3.12+、JDK 21、Git，以及 Maven 3.9+ 或 Docker。没有本机 Maven
时，脚本自动使用 `maven:3.9.11-eclipse-temurin-21` 容器；容器仅挂载本仓库
和 Maven 缓存，不挂载真实数据库目录。

```bash
git submodule update --init
python3 beidou-integration/scripts/build.py --settings /path/to/maven-settings.xml
```

`--settings` 可省略；中国大陆环境建议使用本地已验证的 Maven 镜像配置。
容器 Maven 缓存默认位于 `$HOME/.cache/solomapling-maven`，可通过
`SOLO_MAVEN_CACHE` 指定。已有本机 Maven 时直接使用，也可用 `MAVEN_COMMAND`
显式指定 Maven 启动命令。

若要保留官方发布包附带的管理页面，可追加：

```bash
python3 beidou-integration/scripts/build.py \
  --settings /path/to/maven-settings.xml \
  --ui-jar /path/to/official-BeiDou.jar
```

管理页保留北斗原版权入口，并添加可见的 SoloMapling AGPL 源码链接。
源码仓库、构建时 Git 状态、源码指纹及北斗基线同时写入
`META-INF/solomapling/source.json`；本项目 LICENSE 与 NOTICE 放在同一命名空间下，
不覆盖北斗原有声明。

增量调试可在首轮成功准备后使用 `--skip-engine-build --skip-dependencies`。
这两项仍检查北斗版本、工作区及依赖 POM，不能用于跳过输入变更。
`--prepare-only` 只准备北斗原样编译结果及额外依赖；`--skip-tests` 仅供调试，
构建报告会明确记录没有执行测试。

## 本地镜像交付

先在宿主执行上述构建和验证，再由部署目录的 Dockerfile 把生成的服务 JAR
及构建报告复制进派生镜像；WZ、脚本、JRE 和管理页面继续复用匹配版本的官方资源。
运行时不从宿主挂载 JAR。本地配置继续以独立文件只读挂载，不打入镜像。

构建脚本需要 Git 元数据来核对 submodule 和导出其已提交源码，因此不在排除了
`.git` 的 Docker 构建上下文中重复编译。镜像构建只消费已验证产物，不自动拉取上游。

## 语言与资源

机器人的静态对话和市场文案使用同一语言设置：优先读取本地 `application.yml`
的 `solomapling.language`，未配置时沿用 `gms.service.language`，最后使用 `en-US`。
使用简体中文可配置 `solomapling.language: zh-CN`；切换英文配置为 `en-US`。
保留现有 YAML 节点和其他参数，修改后重启应用，不需要重新构建。

构建将 `BotDialoguePack`、`BotDialoguePack-zh-CN` 及市场词表一同打入服务 JAR，
使用 Solo 的资源读取器加载，不依赖源码目录或插件装载平台。中文资源优先，
缺少文件或对话节点时回退英文；英文资源继续保留以兼容原版更新。
LLM 沿用相同语言选择，静态本地化无须启用模型调用。

本项不修改北斗资源、配置或迁移。具体来源与持续更新约定见
[维护说明](MAINTENANCE.md#本地化来源与更新)。

## MCP 验收桥

验收桥默认关闭，使用正常管理端 JWT 与管理员权限。R1 提供只读世界/场景观察；
R2a 新增专用原生角色的登录、正常退出、存档核验和异步操作查询，须另外启用
`solomapling.acceptance.actors-enabled` 并在本地配置专用 profile。
新增代码和账号所有权切面均在 Solo 侧，北斗 submodule 保持原样。

配置、工具能力与当前验收边界见 [MCP 方案与迭代状态](../docs/mcp-acceptance/README.md)；
安装和显式目标的协议/角色冒烟见 [工具运行说明](../tools/acceptance-mcp/README.md)。
MCP 客户端列出工具不代表后端已启用；以实际 `game_capabilities` 为准。
#70 的 actor 包事件窗口已完成隔离和实际部署验收，六工具版本由 PR #71 交付。
#72 的 `game_act/chat` 已由 PR #73 完成隔离及实际部署验收；原生缓存时钟的限频验收
修正 #74 同时关闭，实际七工具版本保留正常原生规则。
#75 保持七工具，只增加固定脚本中心首屏与取消两动作、type4 NPC解码和当前对话版本，
不开放菜单选择、礼包或传送；隔离与实际部署 SDK 验收已通过，PR #77 交付。
`implementation_round` 保留 `R2a`，以顶层 `enabled_tools/actions` 和观察能力字段判别。
#110 保持七工具，仅在 game_act 增加无收益 npc_reply/select、decline，并扩展已审子菜单的正常取消；
以实际完整offer、已审路径与最新version限制派发，截断城镇菜单禁止选择目的地。
本项已完成隔离及实际部署 SDK 验收，PR #115 交付；accept礼包、完整菜单出行与SocialBot继续独立验收，#200逐片补充基本调试动作，后续同步脚本由#199单独交付。

共享基础的内部ABI及验收范围见 [共享游戏基建](../docs/shared-game-foundation/README.md)。
统一角色壳与模块协议的首轮入口见 [运行体 v0.1](../docs/robot-runtime/README.md)。
该宿主组合已有上下文、scope/lane和原生动作，MCP控制模块接线由#139/#140单独验证；
宿主机制测试不表示MCP已迁入或新版本已经部署，也不包含生产自主行为模块。
#117 加入中立WorldEpoch、角色/场景结构检查和MCP/Bot薄适配，完整构建、CI、隔离及实际部署原生验证已过，PR #130交付并关闭117；
实际运行已包含 #117，公共层不增加 MCP 工具或动作。Bot 真实对象/宿主路由替身测试与完整 V2 运行分别留证。
#118 提供 ExecutionScope、ScopeResources、CharacterCommandLane 及 MCP 薄接线；
[内部执行接口与取消排空](../docs/shared-game-foundation/execution-scope.md)区分取消、真实退出、精确资源释放和世界停止窗口。交付状态与完整构建、CI、隔离及实际部署证据见对应 Issue，代码合并或定向测试不代替部署验收。
#119 的[原生动作与证据参考](../docs/shared-game-foundation/native-actions.md)说明普通聊天的唯一 lane 提交、真实派发检查点及 MCP/Bot 两种取证适配；不增加工具/动作，不把 handler 无异常视为成功。交付与各层验收证据以对应 Issue 为准。
后续按 [共享基础 #116](https://github.com/Diluka/SoloMapling/issues/116) 的 #120～#129 实际依赖推进，
不扩展平行实现；已有MCP交付不冒充公共动作/NPC层完成，礼包及出行暂后排。

## MCP 控制模块接入

[#139](https://github.com/Diluka/SoloMapling/issues/139) 的 MCP 接入在统一宿主内复用 #117～#119：根角色资源、原生来源和唯一 lane 归 `CharacterRuntime`，MCP 模块消费聊天/观察上下文，外层保留鉴权、profile、请求历史与现有额度。接线与回归/部署约定见 [MCP 模块说明](../docs/mcp-acceptance/runtime-module.md)，交付及各层验收证据以对应 Issue 为准。`runtime_generation` 仍表示世界代次；完整暂停/卸载重挂与复杂关闭留 #140，生产自治、巡逻、刷怪不属于本轮。

[#199/#204 的纯脚本基础](../docs/robot-runtime/script-execution.md)为本轮源码实现的基础切片：新增 `script_run/script_help`，通过原模块 work 执行参数运算、语言输出和 JSON 结果，帮助与实际绑定同源，生产函数目录为空。脚本在运行体管理载体执行，短命令 lane 保持可用；超时或取消须核引擎退出、Context 关闭与原 work 真实结算。具体游戏函数、完整行为模块及现有动作迁移后续推进，不等待 #200 全部完成，也不把基础切片等同两个父 Issue 已完成。

## 验证边界

构建会验证 submodule 始终干净；默认执行独立的 LLM 与集成 JUnit 测试，
不执行北斗仓库中会连接数据库的开发工具。打包验证保留北斗原始主入口、
配置与 Flyway 迁移；迁移文件集合和内容、原始配置均逐字节比较。嵌套依赖按
Spring Boot 要求使用 STORED；额外的 Jackson
依赖重定位到 Solo 自有包，避免覆盖北斗的版本。

构建与打包测试可单独运行：

```bash
python3 -m unittest discover -s beidou-integration/scripts -p 'test_*.py'
```

本地化验收需覆盖中文与英文选择、语言优先级、缺资源回退、市场文案消费点及
最终 JAR 内资源。实际服务还应核对启动采用的语言与机器人人数；当前本地部署
沿用单独调整后的 74 个机器人，语言更新不应恢复上游大规模人口配置。
真实客户端的中文渲染、聊天和市场玩法，以及真实 LLM 调用，另记实际验证结果。

服务仍需按北斗原有运行目录提供 WZ、脚本和本地 `application.yml`，并从同一
配置文件完成预建库与 Spring 启动。例如：

```bash
java -jar target/SoloMapling-BeiDou.jar --spring.config.location=./application.yml
```

本构建不打包原版 Cosmic 引擎或其数据库迁移，不生成或替换正式部署配置。
构建过程不会连接游戏数据库；单元测试和故障注入使用内存、临时数据库或副本。
部署冒烟按用户授权沿用实际配置及数据库执行正常应用读写，开始前应保留可恢复备份。
测试建库、故障注入和清理逻辑不得指向实际游戏数据。
构建成功不等于数据与玩法全部兼容，报告需区分构建、隔离验证及实际部署检查。

#200 的移动/停止、一次基础近战、普通库存拾取丢弃、HP/MP药剂与装备穿脱候选说明见[角色基本操作](../docs/shared-game-foundation/basic-control.md)。共享Java能力属于运行体，MCP仅投影协议；以Issue的构建、CI、隔离和实际部署证据分别确认，不从工具schema枚举推导后端已上线。
