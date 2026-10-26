# SoloMapling 北斗集成维护规则

## 目标与边界

- 本仓库基于 MadaraGameDev/SoloMapling，维护北斗适配及经审查引入的插件功能。
- `upstream/BeiDou-Server` 是官方北斗 submodule，当前固定发布标签 `1.12`，提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b`。
- **不得修改北斗 submodule 的任何受版本控制文件，包括源码、POM、配置、脚本和许可证。** 不在其中应用补丁或生成兼容源码；编译使用 `beidou-integration/target/` 内的原样副本。
- 所有适配源码、切面、依赖和构建规则都属于本仓库。构建时可以对北斗编译类进行定向织入；派生 JAR 中的字节码会改变，不得宣称其与官方二进制逐字节相同。
- 北斗是运行引擎；本仓库继承的 Cosmic 引擎用于保留上游历史，不应混入北斗产物。

## 目录与构建

- `src/main/java/soloMapling/`：机器人框架、配置资源与功能代码。
- `beidou-integration/src/`：北斗适配、AspectJ 切面和集成测试。
- `beidou-integration/scripts/build.py`：原样编译北斗、织入、测试和 Spring Boot JAR 重打包入口。
- `beidou-integration/target/`：可再生成的构建副本、依赖、日志和产物，不提交。
- `README.md`、`beidou-integration/README.md` 与 `beidou-integration/MAINTENANCE.md`：当前构建和维护说明；原版说明保留来源，不当作北斗部署指令执行。

初始化：

```bash
git submodule update --init --recursive
git -C upstream/BeiDou-Server status --porcelain
python3 beidou-integration/scripts/build.py --help
```

构建使用 Java 21、Python 3 和 Maven；缺少 Maven 时由脚本调用 Docker Maven。国内镜像设置通过 `--settings /absolute/path/to/maven-settings.xml` 传入。真实构建命令、产物名称和增量选项以脚本帮助及集成 README 为准，修改脚本后同步文档。不要在仓库根直接运行原版 `mvn package` 来代替北斗集成构建。

## 数据与配置

- 数据由北斗管理。使用北斗原生账号、角色、背包、交易和存档规则；不得照搬 Cosmic 的 SQL、Liquibase 迁移、固定角色 ID 或数据库连接配置。
- “北斗源码不变”不等于已经验证数据完全兼容。模板角色、运行时 ID、角色加载/存档、交易与迁移均需要验收依据；不得作未经验证的 100% 兼容承诺。
- **独立单测、集成实验和迁移验证使用临时数据库或一致的数据副本。部署后的冒烟测试可以直接使用实际北斗库，正常创建、修改、迁移与存档已获用户授权，不需要再因写库本身请求确认。**
- 数据库变更或切换版本前保留可恢复的配置和一致性备份。禁止为测试清空、覆盖或删除真实游戏数据；不要对实际部署运行 `docker compose down --volumes`。
- 外部 `application.yml` 和机器人人口文件继续作为运行配置。保留数据库、JWT、IP、端口、语言和人口定制；密钥不入库、不进入镜像、不写入日志。
- 测试容器只使用本次创建的独立项目、网络和数据卷；不占用实际服务端口，也不挂载真实 MySQL 数据目录。

## 验证

- 必须验证 submodule 的提交与工作区均未被修改；构建失败不得修改 submodule 来绕过接口差异。
- 修改行为时验证对应公开结果，尤其机器人身份不会误判真人、模板不会复用真人角色、聊天/组队事件通过原有检查、交易失败返还与结算、机器人卸载和存档。
- LLM 测试使用 stub；覆盖配置、超时、回退、会话结束后的迟到回复与资源释放，单测不调用真实模型。
- 北斗测试目录包含数据库代码生成等开发工具，不直接执行无筛选的整套北斗测试。
- 分开报告：源码与子模块检查、编译与单测、隔离数据验证、实际启动/网页/端口检查、游戏客户端和玩法验证。HTTP 200、端口连通或容器启动不等于玩法验收。

## Git 与上游

- `origin` 为自己的 `Diluka/SoloMapling`，允许提交和推送。
- Madara 原版与插件仓库只作为拉取来源，不向任何上游推送或提交 PR；上游 remote 的 push URL 应禁用。
- GitHub CLI 的写操作始终显式指定 `--repo Diluka/SoloMapling`，避免 Fork 网络的默认目标指向上游；本机可另用 `gh repo set-default Diluka/SoloMapling`。
- 原版更新正常合并并保留历史，不使用 rebase、强制推送或 hard reset。插件改进按功能审查，记录来源提交；插件仓库另起历史，不直接整树合并其平台和宿主改动。
- 创建工作分支前先检查本地改动并以 `git pull --ff-only` 对齐自己的远端基线。
- Issues/Milestone 跟踪工作与验收；仅在对应证据齐全时关闭。PR 只开到自己的仓库，用户已授权代理验收后自行合并。
- 不擅自删除远程仓库。旧方案本地清理应先确认修改与恢复点，且不得波及游戏数据和备份。

## 实现与许可

- 遵守 YAGNI、DRY、KISS；以可运行的北斗适配为范围，不另建未需要的多宿主插件平台。
- 使用自然、完整的中文沟通和文档。代码、路径、命令保留准确原文。
- 代码定位优先 Serena/LSP；确认项目及语言服务后使用。服务不可用时说明情况，再用限定范围的 `rg`。
- 不修改安装的软件内容或 `node_modules`，不把工具缓存、`.serena/` 和构建产物提交。
- 整体继续按 AGPL-3.0 维护，保留原作者版权、LICENSE 和来源；复用插件代码时记录归属。不得将派生项目整体改标 MIT。
