# SoloMapling 与北斗业务地图

本目录按功能责任划分业务地图，使用 `semantic-atlas` 的 `schemaVersion: 1`。`solomapling-*.yaml` 描述本项目的扩展，`beidou-*.yaml` 描述固定北斗上游；所有文件共同构成一张可查询的关系图。每个功能域只维护一份 YAML，无聚合副本或生成索引。

地图依据 2026-10-28 的源码工作区编写。北斗基线为 `1.12`，提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b`；上游锚点均指向本仓库的只读 `upstream/BeiDou-Server/`。Solo 的有效构建输入为 `src/main/java/soloMapling/` 与 `beidou-integration/`，继承的 Cosmic 引擎及旧数据库脚本不属于本地图的北斗运行范围。

地图说明源码中的业务含义与限制，部署和玩法状态需要查验各自证据。[同壳 MCP 首环 #139](https://github.com/Diluka/SoloMapling/issues/139) 已由 [PR #142](https://github.com/Diluka/SoloMapling/pull/142) 合并并完成隔离与实际部署验收；完整暂停/恢复、卸载重挂及复杂关闭仍由 #140 独立推进，移动/战斗、真实客户端玩法和真实模型调用也分别验收。不能由首环交付推导这些后续能力已完成。

## 功能域

| 范围 | 功能域 | 地图与稳定领域 ID |
| --- | --- | --- |
| 本项目 | 人口计划、模板、生成、登记与撤出 | [solomapling-population.yaml](solomapling-population.yaml) · `solo.population` |
| 本项目 | 招募、组队、跟随、导航与旧版训练 | [solomapling-companions.yaml](solomapling-companions.yaml) · `solo.companions` |
| 本项目 | 静态社交、语言、可选模型与会话结束 | [solomapling-social.yaml](solomapling-social.yaml) · `solo.social` |
| 本项目 | 人工货源、估价、店铺与面对面交易 | [solomapling-market.yaml](solomapling-market.yaml) · `solo.market` |
| 本项目 | 牌局、骰子、掉落游戏与环境表演 | [solomapling-recreation.yaml](solomapling-recreation.yaml) · `solo.recreation` |
| 本项目 | 教程对话、游戏 GM 授权、赠礼与武器处理 | [solomapling-onboarding.yaml](solomapling-onboarding.yaml) · `solo.onboarding` |
| 本项目 | 统一角色宿主、模块、执行与精确资源关闭 | [solomapling-runtime.yaml](solomapling-runtime.yaml) · `solo.runtime` |
| 本项目 | MCP 观察、专用角色、请求历史与有限动作 | [solomapling-acceptance.yaml](solomapling-acceptance.yaml) · `solo.acceptance` |
| 本项目 | 固定基线、构建织入、来源报告与世界接线 | [solomapling-integration.yaml](solomapling-integration.yaml) · `solo.integration` |
| 北斗上游 | 账号、角色建立、登录、保存、改名与转区 | [beidou-identity.yaml](beidou-identity.yaml) · `beidou.identity` |
| 北斗上游 | 世界、频道、地图、实例与事件生命周期 | [beidou-world.yaml](beidou-world.yaml) · `beidou.world` |
| 北斗上游 | 战斗、技能、击杀、成长与死亡复活 | [beidou-combat.yaml](beidou-combat.yaml) · `beidou.combat` |
| 北斗上游 | 任务、NPC 脚本、挑战与桌游规则 | [beidou-quests.yaml](beidou-quests.yaml) · `beidou.quests` |
| 北斗上游 | 物品、余额、交易、店铺、寄存、制造及兑换 | [beidou-economy.yaml](beidou-economy.yaml) · `beidou.economy` |
| 北斗上游 | 聊天、好友、队伍、公会、家族与婚姻 | [beidou-social.yaml](beidou-social.yaml) · `beidou.social` |
| 北斗上游 | 宠物召唤、喂养、成长、拾取与补药 | [beidou-pets.yaml](beidou-pets.yaml) · `beidou.pets` |
| 北斗上游 | 管理认证、启动、配置、在线管理与内容维护 | [beidou-operations.yaml](beidou-operations.yaml) · `beidou.operations` |

## 所有权与关系

领域由结果承诺、规则和状态所有权划分，不按 Java 包或调用链的每一层拆分。每个非领域概念用 `part_of` 指向一个业务所有者；跨域调用、数据读写、接口消费与约束分别用 `invokes`、`reads/writes`、`consumes/publishes` 和 `constrained_by` 表达。关系放在 `from` 概念所属文件，流程放在所属场景的文件。

共享概念只定义一次。例如北斗拥有持久角色、背包、钱包、队伍和原生 NPC 规则，Solo 地图通过稳定 ID 引用；MCP 拥有 profile、owner/session、外部请求历史和允许的回答路径，统一运行体拥有工作结算、授权与根资源关闭。原生接口存在不代表 MCP 已开放该动作。

典型关联可从以下概念进入：

- `solo.acceptance.chat` → `solo.runtime.dispatch-chat` → `beidou.social.chat`：同壳聊天、共享执行与原生广播规则。
- `solo.acceptance.native-source` → `beidou.identity.native-character`：专用普通角色沿原生创建、登录和存档生命周期取得。
- `solo.market.direct-trade` → `beidou.economy.trade-entry`：人工货源与原生资产结算分别归属。
- `solo.integration.start-world/stop-world` → `beidou.world.lifecycle`：本地切面消费官方世界生命周期边界。

旧 V1 SocialBot 的直接广播、无人观察训练的抽象经验和现有小游戏限制在各域中保留真实含义，不能从共享动作 API 反推旧机器人已全部迁入统一运行体。教程源码会修改游戏 GM 等级与资产，应从其实际副作用理解；游戏 GM 等级与管理端 `webadmin` 身份分别核对。

## 查询、校验与查看

命令在 SoloMapling 仓库根执行，需要 Node.js 24 和 `semantic-atlas`；本轮使用版本 `2.8.0`。未加入 PATH 时可用 `npx --yes --package=semantic-atlas@2.8.0 semantic-atlas` 替代命令名。

```bash
semantic-atlas validate --repo .
semantic-atlas search "机器人交易" --repo .
semantic-atlas context solo.acceptance.chat --repo .
semantic-atlas render --repo . --output target/business-map/index.html
git diff --check
```

渲染结果为可离线打开的 HTML，可按领域选择关系图或流程图，点击概念查看当前源码入口。`target/` 中的 HTML 和检查截图为可再生结果，不是额外的业务事实来源。

新增功能域时创建同一目录下带对应前缀的 YAML，并给它独立的 `domain` 根。稳定 ID 不随文件改名变化。修改后验证全图和相关流程，检查锚点指向的实际声明，再更新覆盖说明；仅通过语法校验不能证明业务边界准确。

## 源码覆盖与限制

各覆盖说明从源码入口、状态所有者和退出路径独立核对，记录责任、受益人、生命周期、协作及未验证边界：

- [机器人、市场、休闲与教程](solomapling-bots-coverage.md)。
- [统一运行体与 MCP 验收](solomapling-control-coverage.md)。
- [北斗身份与运营](beidou-platform-coverage.md)、[世界与事件](beidou-world-coverage.md)。
- [战斗与任务](beidou-adventure-coverage.md)、[经济与资产](beidou-commerce-coverage.md)。
- [社交与组织](beidou-social-coverage.md)、[宠物](beidou-pets-coverage.md)。

集成交付以 `build.py` 的 `assert_engine_unchanged`、`compile_and_weave`、`assemble_jar`、`verify_native_resources` 和 `verify_jar` 为决定性来源，分别覆盖固定输入、织入、来源与许可、原配置/迁移保留和包结构。`BeiDouRuntime.onServerReady/onServerStopping` 与 `GameplayHooks.aj` 确定本地扩展的启动和停止接线。构建报告不替代数据兼容性或玩法验收；`--skip-tests` 只形成标明未执行测试的调试产物。

本轮只创建地图并进行静态取证、图校验、查询及查看器检查，没有操作游戏数据、重启服务、调用真实模型或完成新一轮玩法验收。
