# SoloMapling 北斗集成版

本项目从 [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) 原版历史建立，目标是在官方北斗服务端上运行 SoloMapling 机器人，并持续吸收原版及插件来源的改进。

**当前处于集成与验证阶段。** 构建、机器人行为、数据兼容和实际服务验收分别记录在 [Milestone：北斗源码零修改集成试验](https://github.com/Diluka/SoloMapling/milestone/1) 与 [总跟踪任务 #1](https://github.com/Diluka/SoloMapling/issues/1)。未关闭的任务不代表已经验证完成。

## 集成方式

- `src/main/java/soloMapling/` 保存机器人框架和功能改进。
- `upstream/BeiDou-Server/` 是官方北斗的 Git submodule，当前兼容基线为发布标签 `1.12`。
- `beidou-integration/` 保存北斗适配、AspectJ 构建时织入和打包规则。
- 北斗 submodule 的源码、POM 和资源保持原样；构建在暂存目录进行。
- 最终目标产物是可执行的 `SoloMapling-BeiDou.jar`。其中少量北斗字节码会经过织入，因此“源码不修改”不表示运行时行为完全未扩展。

原版仓库还包含 Cosmic 引擎。北斗集成构建使用官方北斗引擎，不将这套 Cosmic 引擎及其数据库迁移混入北斗服务。

## 构建与运行

初始化源码及固定的北斗子模块：

```bash
git clone --recurse-submodules https://github.com/Diluka/SoloMapling.git
cd SoloMapling
```

构建入口、依赖和参数见 [北斗构建说明](beidou-integration/README.md)。版本更新与验收流程见 [维护说明](beidou-integration/MAINTENANCE.md)。不要在仓库根运行原版的 `mvn package` 来代替北斗集成构建。

运行保留北斗的 `org.gms.ServerApplication` 入口及原有配置读取顺序。应用业务参数维护在本地 `application.yml`，人口配置维护在本地 YAML；这些配置不打入 JAR。WZ、脚本和运行目录继续按北斗要求提供。

## 功能与验证范围

当前集成包含原版机器人框架、SocialBot LLM 对话，以及从插件来源吸收的简体中文对话与自由市场文案。持久伙伴、职业成长、装备赠送等功能需要分别确认宿主能力和数据行为，不因复用部分代码就视为全部完成。

数据使用北斗的数据结构，但这不能自动证明机器人身份、模板角色、存档和迁移完全兼容。变更前备份配置及一致的数据库副本，迁移先在隔离环境验证；通过后再执行已授权的正常迁移、模板角色创建和存档读写。不删除、清空或覆盖真实游戏数据。

构建成功、测试通过、隔离服务启动、实际本地服务启动和游戏客户端玩法验收是不同结果。对应证据记录在 [适配与数据兼容任务 #3](https://github.com/Diluka/SoloMapling/issues/3)。

## 与 SocialBot 聊天

在普通地图聊天的正文中包含机器人的准确名字，例如“机器人名，你好”。开头的 `@` 属于北斗原生玩家命令入口，不作为机器人点名语法；不要用 `@机器人名` 开头来测试普通聊天。

LLM 由本地 `application.yml` 的 `solomapling.llm.*` 配置启用，密钥不提交到仓库、不打入 JAR，也不写入日志。已用桩客户端跑通 `GeneralChatHandler → Dispatcher → SocialBot → LLM 桩客户端 → CHATTEXT 广播` 的完整探针链路；尚未调用实际模型，也不代表已完成真实游戏客户端的聊天验收。

## 对话与市场语言

在本地 `application.yml` 的现有 `solomapling` 节点下设置：

```yaml
solomapling:
  language: zh-CN
```

语言按 `solomapling.language`、北斗的 `gms.service.language`、`en-US` 的顺序选择。北斗已配置 `zh-CN` 时，可省略 Solo 的独立设置；需要让机器人使用英文时，将其设为 `en-US`。修改后重启应用生效，不需要重建 JAR 或镜像。

中文资源覆盖聊天、城镇闲聊、练级、跟随、商人、教程与小游戏等 19 份对话包，以及市场叫卖、店铺名称和描述、报价及价格更新提示。缺少对应语言文件或对话节点时回退英文。静态中文对话不依赖 LLM；启用 LLM 后，模型沿用相同语言设置并参考玩家的语言。

本功能不更改人口配置，也不新增数据库迁移。资源和加载逻辑的来源、回退约定与验收见 [本地化任务 #9](https://github.com/Diluka/SoloMapling/issues/9) 和 [维护说明](beidou-integration/MAINTENANCE.md)。中文资源加载测试不能替代真实游戏客户端的渲染和玩法验收。

## 当前模板数据前提

当前集成按 `name = fmbot` 且 `world = 0` 查找既有模板角色，并要求匹配唯一。它不使用 Cosmic 固定的角色 ID，也不以某个预设 ID 覆盖真实角色。

这是当前试验的数据前提，不表示所有全新空库都能自动初始化机器人模板。全新库模板初始化仍属于后续工作。

## 人口与市场验收

市场沿用原版随机选择商店类型的行为，生成过程还包含二级异步任务和延时。九波加载日志或累计创建计数不能直接当作最终在线人口，某一种商店的数量也不能仅按批次总数推算。

验收时通过正常管理端鉴权查询实际在线角色，以实际机器人 ID 集合和连续稳定采样为依据；分开记录已调度批次、累计生成数与当前进入世界的机器人集合。

## 协作型组队任务设计

[组队任务目录与规则设计](docs/party-quests/README.md) 按固定北斗 1.12 源码盘点全部事件，并补查 Java 驱动的挑战和比赛。设计采用通用 `PartyQuestBot`、每任务独立规则，以及每次运行的团队协调器，重点覆盖分工、多人同时站位、目标预约、失败补位与真人协作。每个任务家族都有独立实现和验收 Issue；当前交付是设计与计划，尚未新增可运行的通关机器人。

## 来源与许可

本项目整体继续采用 **AGPL-3.0**，见 [LICENSE](LICENSE)，不能把复用的 AGPL 代码改标为 MIT。原始文件头、版权和来源声明保留；依赖和第三方素材仍遵守各自的许可证。

原版 SoloMapling、Cosmic/OdinMS、北斗和实际复用的插件改进，见 [NOTICE](NOTICE)。分发或提供网络服务时，应向使用者提供实际运行版本的对应源码，包含适配、织入、构建规则及固定的北斗子模块内容。仅指向与运行版本无关的上游源码，不能替代这一要求。

上游仓库只用于拉取更新；本项目提交仅推送到自己的仓库，不向原版 Solo、插件或北斗上游推送。

## 原版资料

- [原版 README 快照](README.upstream.md)
- [原版 SoloMapling 说明](SoloMapling_README.md)
- [Cosmic 原始说明](Cosmic_README.md)

这些资料描述原版 Cosmic 集成环境。北斗集成的构建、配置与验收以本项目说明为准。
