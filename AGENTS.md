# SoloMapling 北斗集成维护规则

## 项目与修改边界

- 本仓库基于 MadaraGameDev/SoloMapling，维护北斗适配及经审查引入的插件功能。北斗是运行引擎；继承的 Cosmic 引擎只保留上游历史，不混入北斗产物。
- `upstream/BeiDou-Server` 是只读官方 submodule，基线以仓库 gitlink 和构建校验为准。不得修改其受版本控制的源码、POM、配置、脚本或许可证，也不在其中应用补丁或生成兼容源码。
- 适配源码、AspectJ 切面、依赖与打包规则均维护在 Solo。构建使用 `beidou-integration/target/` 内的北斗原样副本；派生 JAR 的部分字节码会改变，不宣称官方二进制完全不变或数据自动完全兼容。

## 目录与构建

- `src/main/java/soloMapling/` 是机器人框架、功能与配置资源；`beidou-integration/src/` 是适配、切面及集成测试；`beidou-integration/scripts/build.py` 负责原样编译、织入、测试和重打包。
- 构建环境要求、参数和产物检查见 [集成构建](beidou-integration/README.md) 与 [维护说明](beidou-integration/MAINTENANCE.md)。使用 Python 3、JDK 21 和 Maven；无本机 Maven 时由脚本使用 Docker。不要用原版 `mvn package` 替代北斗集成构建。
- 初始化或同步前检查本地修改；构建前后核对 submodule 身份及清洁状态。接口差异在 Solo 侧修复，不能修改 submodule 或跳过校验来让构建通过。

在仓库根检查基线与选项，按集成说明完成构建：

```bash
git submodule status upstream/BeiDou-Server
git -C upstream/BeiDou-Server status --porcelain
python3 beidou-integration/scripts/build.py --help
python3 beidou-integration/scripts/build.py
```

国内 Maven 镜像通过 `--settings` 传入；管理页面保留、增量选项和镜像交付按集成说明及所属部署工作区要求执行。构建脚本变化同步相应文档，不在 Docker 构建中重新拉取源码或绕过验证。

## 数据与配置安全

- 使用北斗原生账号、角色、背包、交易与存档规则，不照搬 Cosmic 的 SQL、迁移、固定角色 ID 或数据库配置。数据兼容结论必须有相应验收依据。
- 独立单测、实验、迁移验证及新增角色写路径先使用临时数据库或一致副本；部署后的正常冒烟按所属工作区既有授权执行。变更数据库或切换版本前保留配置和一致性备份，禁止为测试清空、覆盖或删除真实游戏数据，也不对实际部署执行 `docker compose down --volumes`。
- 测试容器仅使用本次创建的独立项目、网络和数据卷，不占实际服务端口或挂载真实 MySQL 数据目录。清理只影响本次隔离资源，恢复先核对迁移后的数据兼容性。
- 专用测试角色与玩家资料分开；不接管玩家、提权或复制模板改 ID 冒充原生持久角色。玩家活动期间按实际动态前态归因，不能恢复旧指纹或把未解释的数据损失归为正常活动；隔离库和实际库各用自己的基线。
- 运行配置继续外置；保留数据库、JWT、IP、端口、语言和人口定制。密钥不进入源码、镜像或日志，不用内置默认覆盖本地定制。

## 验证与证据

- 按变更影响验证真实公开结果，重点覆盖身份与来源隔离、资产守恒、原生准入、失败恢复、卸载和存档。北斗测试目录含数据库代码生成等开发工具，不执行无筛选的整套北斗测试。
- LLM 单测使用 stub，覆盖配置、超时、回退、迟到回复和资源释放；实际模型调用与游戏内回复另行留证。
- 分别报告源码与子模块、编译与单测、隔离数据、实际启动/网页/端口、游戏客户端与玩法验证。已实现、候选已验、实际已部署分别判断；处理器返回、广播发送和真人收到/播放分别取证。

## 测试设计

- 测试保护真实业务入口和可观察结果，夹具在存储、网络与原生世界等真实边界做最小替代，不把测试替身通过当成成品行为已完成。
- 只保留有业务意义的最小契约；探索脚手架用后删除，不为测试增加无业务消费者的生产接口或扩大实际支持范围。

## 运行体、模块与资源所有权

- 原生对象拥有 HP、位置、背包与存档等游戏事实。`CharacterRuntime` 在 OPEN 前拥有角色来源、根 scope、原生资源和唯一角色 lane；`RoleBinding` 保留普通角色与环境 Bot 的账号、lease、登录及存档差异，挂载模块不改变来源。
- MCP 只适配鉴权、owner/profile、请求幂等与结果；模块侧裁决装配、优先级及资源冲突。共享 Java 后端沿原 scope/Grant 和唯一 lane 执行，不复制宿主、动作队列、库存或物理工作账本，也不让 Bot 经 Python/HTTP/MCP 执行自身动作。
- `WorldEpoch` 是唯一世界时钟；世界、session、宿主、模块、执行句柄和各类游标分别建模。旧引用不刷新续权，不按相同 ID 替换原对象；跨图重新核实际场景，读取失败报告不可用，不把活会话误判丢失。
- 模块只拥有自己的 scope、Grant 和资源；内部组件的工作归父模块，独立提供者保留自身所有权，只清理自有的精确资源和会话，不接管外来或后继上下文。暂停/卸载停止输入并等待工作及生命周期 hook 真正退出，默认保留根 Client 的 CM/QM，不隐式登出或关闭 lane；根关闭排空所有模块后释放精确原生资源，恢复使用新执行代次。
- 原生短命令只提交一次角色 lane，不在 lane body 内再递交并等待自己。撤销仅影响对应 Grant，释放等待仅跟踪相关工作，不让其他模块持续工作拖住旧授权；普通工作与清理工作须有真正可用的独立额度。
- 取消请求、body 退出、队列结算、资源释放、卸载与存档分别核验；Future 或 operation 终态不能证明真实排空或提前归还容量。未知创建/释放保留债务与责任，自回调排空不能假报完成；停服先停止接收、取消并排空地图相关工作，再销毁原生地图。
- 世界停止只凭准确来源票据清理原登记对象，旧 scope 不借新世界票据续权，也不以清理权限执行普通动作或创建资源。原生资格、ThreadLocal 恢复和临时包释放沿原边界；已发生事实不因后续取消抹掉，未知副作用不盲目重放，终态不长期持有原生对象或正文。

## 接口与功能文档入口

| 修改范围 | 先阅读 |
| --- | --- |
| 共享上下文、scope/lane 与原生动作 | [共享游戏基建](docs/shared-game-foundation/README.md)、[执行与排空](docs/shared-game-foundation/execution-scope.md) |
| 角色宿主与模块 | [运行体文档](docs/robot-runtime/README.md) |
| MCP 角色控制与命令 | [验收协议](docs/mcp-acceptance/README.md)、[运行体接线](docs/mcp-acceptance/runtime-module.md)、[命令桥](docs/mcp-acceptance/commands.md) |
| 背包与基本动作 | [背包移位](docs/shared-game-foundation/inventory-move.md)、[基本控制](docs/shared-game-foundation/basic-control.md) |
| 脚本设施与迁移边界 | [脚本执行](docs/robot-runtime/script-execution.md)、[迁移准备](docs/robot-runtime/script-basic-readiness.md) |

实际开放能力以 `game_capabilities`、本工作树协议和匹配部署为准，工作区配置、官方 SDK、宿主工具加载与真人玩法分别留证。不在本文件维护工具数量、参数全集或阶段支持清单，也不从其他工作树推断本分支能力。

## Git、实现与许可

- `origin` 是自己的 `Diluka/SoloMapling`，允许提交和推送，PR 仅指向自己的仓库；用户已授权代理在验收通过后自行合并。Madara 原版和插件仓库仅作拉取来源，不向上游推送或提交 PR；禁用上游 remote 的 push URL。
- GitHub CLI 写操作显式指定 `--repo Diluka/SoloMapling`。创建工作分支前检查并保存本地修改，再用 `git pull --ff-only` 对齐自己的远端基线；不以 rebase、强制推送或 hard reset 重写维护历史。
- 原版更新正常合并，插件改进按功能审查并记录来源，不整树合并平台或宿主改动。旧方案清理先保留恢复点，不波及游戏数据和备份，不擅自删除远程仓库。
- 任务按可独立实现、独立验收的切片组织，使用 GitHub 原生 parent/sub-issue 关系。相应实现与验收证据齐全再关闭并同步父任务；确认缺陷保存实际请求、场景、运行代次和前后态，修复后重跑原场景。
- 遵守 YAGNI、DRY、KISS，沿现有约定实现实际需要的适配。使用自然完整的中文沟通，代码、路径和命令保留原文；定位代码优先 Serena/LSP，确认项目及服务，不可用时说明并用限定范围的 `rg`。
- 不修改安装软件或 `node_modules`，不提交工具缓存、`.serena/`、构建副本及产物。整体按 AGPL-3.0 维护，保留原版权、LICENSE、NOTICE 和来源；复用插件代码记录归属，不改标为 MIT。

## 本文件维护与项目日志

- `AGENTS.md` 只在长期工作流、职责边界或约束变化时更新。接口、配置、支持范围和能力参数更新到对应功能文档及实际能力公告；逐轮提交、候选、测试次数、部署结果和当前待办不写进本文件。
- 阶段进展、决定、失败与未覆盖结论写入所属部署工作区的 Serena 项目记忆。读写前按部署工作区绝对路径激活项目，先读 `mem:project-log/index`，按 `mem:project-log/template` 建立或续写任务记忆；不写入源码项目或 `global/`，源码符号操作切回当前源码项目。原始证据和恢复点按部署工作区约定保留；任务、依赖及验收闭环使用本仓库 Issue/PR。保留记录日期和来源，已有历史不能当作当前部署证据。
