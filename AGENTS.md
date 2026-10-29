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
- 真实玩家在验收期间可能正常创建角色、登录或保存。记录实际用户活动和动态数据基线，把具体变化与本次专用fixture操作分开归因；不回写旧指纹、不把新玩家当fixture，也不以宽泛“玩家活动”掩盖未解释的数据损失。隔离库与正式库使用各自前态。
- 外部 `application.yml` 和机器人人口文件继续作为运行配置。保留数据库、JWT、IP、端口、语言和人口定制；密钥不入库、不进入镜像、不写入日志。
- 测试容器只使用本次创建的独立项目、网络和数据卷；不占用实际服务端口，也不挂载真实 MySQL 数据目录。

## 验证

- 必须验证 submodule 的提交与工作区均未被修改；构建失败不得修改 submodule 来绕过接口差异。
- 修改行为时验证对应公开结果，尤其机器人身份不会误判真人、模板不会复用真人角色、聊天/组队事件通过原有检查、交易失败返还与结算、机器人卸载和存档。
- 世界停服应先停止接收、取消并排空会引用地图的后台导航任务，再销毁原生地图；只使 future 或迟到结果失效不能证明运行线程已结束。排空超时必须保留失败证据，重新启服后再明确开放新任务。
- LLM 测试使用 stub；覆盖配置、超时、回退、会话结束后的迟到回复与资源释放，单测不调用真实模型。
- 北斗测试目录包含数据库代码生成等开发工具，不直接执行无筛选的整套北斗测试。
- 分开报告：源码与子模块检查、编译与单测、隔离数据验证、实际启动/网页/端口检查、游戏客户端和玩法验证。HTTP 200、端口连通或容器启动不等于玩法验收。

## 测试设计与提交

- 先从业务目标定义测试，再选择测试层级和夹具：确认真实消费方、设计支持的入口、输入来源，以及要验证的结果、拒绝语义或副作用。业务入口可以是内部 Java 协议、服务或最终构建产物，不限于 UI/HTTP；不能从已有方法、分支或覆盖率反推测试需求。
- 所有提交进仓库的测试都必须有业务意义。评审时应能说明它保护哪项业务事实；只验证标准库、测试自建模型、同一实现算出的预期值、私有布局或未承诺兼容性的断言不提交。临时用于实现探索的脚手架测试，用后删除；已入库的无意义测试也应清理，不以必须同时发现生产代码问题为前提。
- 专用子模块若可完全收为父模块的私有内部元素，且没有独立业务消费契约，不建立独立测试面；有效业务断言由父模块入口承接。不以算法复杂、运行便宜或方便定位为保留独立套件的例外，也不要求机械内联正常的内部类和方法。
- 子模块只有承担真实内部共享作用时，才保留对应的独立测试：列清独立业务消费方，测试其公共作用及一条消费线修改可能破坏另一条线的风险。仅保留足以保护这些消费线的最小约束，不追求模块自身百分百覆盖。`public`、多个内部调用点、同一业务的不同包装或未来可能复用，均不单独证明共享。
- 输入边界以所有受支持生产入口及其前置条件为准。不得把测试能够构造的参数、状态或组合直接扩大为业务支持域，也不得为这些用例增加业务错误语义和防御门禁。清理前核对外部协议、原生数据、回调、并发与生命周期，不能把真实可达的失败状态当成不可能输入。
- 夹具服务于真实业务验证，在存储、网络、原生世界等实际协作者边界做最小受控替代。不得为方便直接测试专用子组件，在生产中长期保留无业务消费者的构造器、getter、替换策略、兼容重载或测试开关；也不把桩的固定返回值当成被测业务已经完成。
- 清理时区分直接删除与迁移：无业务断言和不可达输入契约直接删除；有效事实先归真实父入口；共享测试按实际消费线合并。同步回收仅被这些测试维持的生产接缝、死协议、重复校验和参数泛化，不通过换文件名、复制 helper 套件或新增同义测试接口维持原负担。
- 验收看无效测试/代码是否退出以及真实业务承诺是否保持，不以测试数量、执行速度或覆盖率增减作为完成指标。权限、身份、资产、来源、原生取证、真实取消排空及必要失败恢复仍按其业务责任验证和保留。

## MCP 迭代与问题闭环

- M1按#79/#139/#140采用统一角色运行体与MCP首个控制模块，按小闭环扩展协议。`soloMapling.game.runtime` 的宿主/模块协议与机制测试属于运行体工作；MCP模块及来源/外部适配由MCP工作接入。首轮不交付生产自主练级、巡逻或刷怪模块，机制测试替身不等于自治能力。
- 运行体在OPEN前拥有根scope、唯一角色lane和原生来源资源。`RoleBinding`保留AcceptanceClient账号/lease/保存与BotClient/BotRegistry的身份差异；挂载MCP不能改变角色来源。角色资源在adopt时唯一登记，来源释放前核精确根scope/cleanup attempt，不能从另一scope借同identity的permit调用原生退出。
- 模块scope/Grant与宿主根资源分开；暂停/卸载只排空自己的工作，不登出角色或关闭lane。生命周期hook本身也要退出，不能因动作work已空就提前宣布卸载；根关闭在所有模块真正结束后才退出角色。恢复创建新模块执行代次，旧Context不能复活。
- 现有NativeActionService自己提交一次lane；宿主`prepareNative`只准备，不先外排再内排。MCP投影同一个结果与真实work结算，不另建动作状态。运行体事件、原生包、operation和对话游标分别使用；窗口只保存安全事实，不存正文或原生对象。协议说明见`docs/robot-runtime/README.md`，源码/机制测试与MCP接线/实际部署分别留证。

- 共享入口为 #116：开始相关MCP切片前核对 #117上下文、#118作用域/时序、#119原生动作证据、#120按需事实/事件、#121NPC及 #122～#129各动作；现有任务树和里程碑不重挂。优先从已验实现小范围提炼一份中立Java后端，MCP和V2分别薄适配，不能各复制一份或让V2经Python/HTTP/MCP动作。
- MCP 外层保留 JWT/admin、owner/profile、外部 request_id、幂等历史与额度；原生账号创建/登录/存档由可信来源适配执行，角色资源与最终关闭归统一宿主根。V2 保留模块/预设/优先级/资源仲裁。公共层只验证调用方已授控制权和原生规则，不搬整个ActorControlService、不决定V2优先级，也不放宽MCP排除环境bot的身份保护。
- 世界代次、MCPsession、V2runtime/模块代次、执行句柄、包序号、对话版本分别建模；同一内部执行句柄防重复派发，新的合法重复动作允许，不把攻击tick全部登记为外部operation。公共层不依赖acceptance开关、管理JWT或全世界扫描，不强制所有bot保留256包正文历史。
- #139 当前接入目标是统一角色壳与首个真实 MCP 控制模块：OPEN 前已确定同一 root host/原接纳 epoch/唯一 lane，根来源登记与绑定原生角色，再挂模块；聊天与 actor 观察消费同一宿主，外层不得复制独立执行实例。外部 session、角色宿主与模块代次分别建模，`runtime_generation` 继续投影 WorldEpoch，不能改成 host/module ID。接入、回归及部署边界见 `docs/mcp-acceptance/runtime-module.md`，交付以 Issue 证据为准。
- 原生 CM/QM 与包缓冲属于根 Client/角色来源。MCP 模块暂停/卸载默认停止输入、撤控制并排空自己的工作，保留现有 CM，不隐式 dispose/登出；显式 npc_cancel 或根 actor_close 沿正常规则处理。完整暂停/恢复及复杂关闭由 #140 验收，不从 #139 聊天首环推导完成；生产自治、巡逻、刷怪不在本轮。
- 共享上下文位于 `soloMapling.game.context`：`WorldEpoch` 是唯一世界时钟，旧验收runtime仅投影委托；`CharacterRef` 固定角色/Client/World/Channel与捕获epoch，`SceneRef` 另外固定Map及成员/地图EIM。重验不能刷新旧epoch或按同ID替代对象；正常跨图不自动使角色绑定失效，场景资格另核。当前角色观察定点登记MCP不透明scene_ref，不借全目录刷新作为控制资格。
- 公共检查只核对象结构，不核普通登录或授予控制权。MCP薄适配保留账号/lease/GM0/普通登录/排bot，Bot薄适配核精确BotRegistry对象与BotClient；V1模板通过非频道加载，Character.loggedIn可为false，不能为复用而改其存档/登录标记。getter故障是读取不可用，不能把活会话标LOST；角色资格和场景冲突分别处理。
- #118 公共执行位于 `soloMapling.game.execution`：`ExecutionScope` 记录固定 epoch/runtime/module 身份与实际工作，`ScopeResources` 记录精确资源及一次握手，`CharacterCommandLane` 串行执行单个实际角色的短命令。MCP 控制模块经统一 `CharacterRuntime` 消费这些机制；Session/helper 不再自建 scope、Grant、角色资源或第二队列，外部额度只投影宿主原工作的真实结算；公共容量由调用方配置，不硬编码 MCP 7/8/256 策略。Scope 的普通工作与清理工作分别使用显式有界额度；允许持有资源时至少保留一个清理工作名额，不能只预留 lane 物理槽而让工作账本阻塞清理。
- CharacterCommandLane 允许调用方已授予的多个合法 Grant 并存，只做 FIFO 与原生资格复核；V2 自己决定资源冲突和交接。revoke 只撤对应 Grant，不自动排空整个 scope；释放等待只跟踪相关工作，不能让其他模块或后继 Grant 的持续命令拖住旧授权。撤销前已受理 carrier 仍须真实结算；撤销后新递交旧 Grant 立即锁外拒绝并结算，不入 FIFO 占用其他模块容量或续旧债务。
- 取消请求、body 退出、队列承载/结算完成、资源释放与卸载完成分开核验。`Future.cancel/isDone` 或 operation 终态不能提前归还物理槽；MCP ACTION 仅在精确 work 实际结算后归还额度。自回调排空返回 `SELF_WAIT`，未知创建/释放保留债务，不能假报 CLOSED。
- 停服经 `ExecutionScope.tryBeginWorldStop()` 原子取得对应 active→stopped 的 `WorldStopTicket`；同次停止复用票据，未知 inactive 不造权。只给来源 epoch 一致的 scope 加入专用清理窗口，下一世界启动后旧原生入口与轮询拒绝；旧 quarantine 不借新票据续权。清理使用原登记精确对象的 `DrainPermit`，已合法完成的释放事实可晚到入账；不给公共 API 增加 ignoreEpoch，也不借清理执行新动作或创建新资源。
- #119 共享普通聊天位于 `soloMapling.game.action`，MCP/Bot 通过各自身份和取证薄适配调用同一 Java 实现，只向已有角色 lane 提交一次；不在 lane body 内再 submit/join/get 等自己。JWT/profile/request_id/历史仍在 MCP，Grant/模块仲裁仍在 V2；不开放任意 handler、ByteBuf、脚本或反射动作入口。
- 区分受理、实际 handler 派发、证据与终态：派发检查点在 validateState 通过后、handlePacket 调用前，ThreadLocal Client 恢复原值，临时包恰好释放一次。成功需真实新匹配包并标来源/完整性；Bot 默认不收包且无正证据为未知，不能用无异常或换证据标签补成功。派发后取消不撤回，已发生正证据不被后续异常抹掉，未知副作用不重放。终态不长期持有原生对象、probe、聊天正文或异常正文；事实结果完成仍不等于真实排空，模块结果应用另走 scope/代次检查。
- 共享切片用真实原生场景和第二类薄上下文适配器独立验收，不等完整V2成品；MCP工具开放、V2组合和PQ通关仍在原任务分别验收。若公共提炼扩大当前切片风险，先收口已授权小切片，并在共享叶子写清适配债务与下一步，不能把“以后复用”当已完成。#117 已完成共享上下文交付；#118 的交付/验收状态及证据以对应 Issue 为准，不能用合并或定向测试代替实际部署；#119 的实现与各层证据也按对应 Issue 核对。当前优先 #139 同壳 MCP 首环，再推进 #140 及 #120/#121 的实际依赖，礼包/出行后排。
- 总任务用于串联进度，开发 Issue 按可独立实现和验收的功能切片组织。例如原生角色生命周期、普通聊天与机器人回复、NPC 多页对话分别交付；不要把一个空接口或局部测试通过标成整轮完成。
- 使用 GitHub 原生 parent/sub-issue 关系组织树形拆分；正文清单用于说明和验收，不代替实际关联。新增子项时检查父节点，完成后同步父任务状态，避免重复建单或错挂到不相关任务。
- 每个切片在实现前明确范围和验收结果。实现、对应测试、隔离/实际 MCP 验收通过后及时关闭该 Issue，并同步总任务；仍缺证据的项目保持开放，具体写明未验部分。
- 可以用 MCP 验证从上游迁入的机器人行为。先记录实际请求、场景/角色/运行代次、回复及正常规则，再为确认问题建立独立缺陷 Issue；修复后重跑同一场景，保留修复前后证据，验收完成即关闭。
- 新的角色写路径先在临时库或一致副本验证，再按已授权的备份和部署冒烟流程使用真实库。使用专用原生测试账号/角色，不能接管普通玩家、提升 GM/webadmin，或把环境模板复制改 ID 当作真实持久角色。
- 操作已排队、原生动作完成和游戏目标达成分别记录。MCP 超时/取消不等于后台停止；开关角色、NPC、队伍和任务副作用必须以实际后态验证，不能以原生方法的 void 返回作证。
- 每次能力变化同步更新工具说明、接口/配置文档和相关 `AGENTS.md`。能力公告只维护已实现的工具、动作和观察范围，基本调试入口按 #200 逐片交付，脚本基础由 #199/#204 独立验收，不等待 #200 全部完成。实际能力以 `game_capabilities` 为准；工作区配置、官方 SDK 协议调用、宿主原生工具加载及真实客户端玩法验收分别留证。
- R2a 的原生角色控制需同时启用 `solomapling.acceptance.enabled` 与 `actors-enabled`，并在本地配置专用 `actor-profiles`。MCP 仅接收 profile ID，不接收账号密码或任意角色 ID；账号使用 `mcp_` 前缀且无 GM/webadmin。`AcceptanceClient` 直接继承北斗 `Client`，不登记为环境 bot。
- 原生登录前同时准备 Server 的账号缓存和目标 World 的原生角色视图。同 JVM 停启世界会保留前者并重建后者；验收需包含停启后相同 CID 重登、保存退出和旧场景代次失效，不能只验证首次启动。
- `actor_open`、`actor_close`、`operation_cancel` 必须携带调用方生成的请求 ID；写请求超时不盲目重放，按原 ID 与 operation/session 检查点恢复。操作受理不等于成功；关闭以原生清理和已声明范围内的持久字段核验为准，失败保留 fixture 和 quarantine。
- 角色包事件使用同一 session 的独立包序号，不能复用 operation 生命周期游标。角色观察先验证 owner、会话/世界代次与在线对象，再读取有界窗口，返回前复核；关闭后的旧会话不能读取历史正文。观察不分配操作记录或占用关闭名额。
- 游戏动作复用同一 session 执行队列，受理和实际出队前都核对 owner、会话/世界代次及实际场景。聊天沿原生 `GeneralChatHandler`、字符集和频率规则，只有收到自身CID与原文匹配的原生广播才确认成功；不能直接构造回声包冒充执行。
- NPC 动作只能使用实际开放的原生入口和当前对话版本。脚本中心是固定 `ENTER_MTS` UI 入口，须核 `use_mts=false`，不能伪装远距离点击地图NPC或允许任意模板/脚本。最新NPC包序号与同一活跃CM共同确认可回答版本；未知、畸形新NPC包及CM结束也使旧版本失效，不使用不存在的 `isDisposed()`。
- NPC对话取消通过当前真实包类型对应的原生回复，调用方不能任填 lastMsg。操作取消只约束排队动作，不等于关闭已打开对话；`actor_close` 必须核CM/QM原生清理。只打开/取消首屏的验收应核金币、券、经验、背包任务及场景无玩法变化，不把后续菜单选择和正常出行算成已交付。
- NPC选择须同时属于该版真实、完整、无歧义的offer和本轮已审查路径，不能仅因脚本switch有分支就放行；隐藏GM/测试项、截断菜单或未知来源全部拒绝。派发前消费答复版本，原请求靠去重恢复事实，新请求不能再次消费同版；未知结果不恢复资格或盲重试。
- 命名子CM只能沿受控原生同步转移继承入口来源，不能把同模板任意脚本视为可信。NextLevel可能发包后才设置上下文，实际派发前在同session队列读取当前路由；legacy与NextLevel的拒绝/取消字节分别核实，不能统一手动dispose或固化捕包瞬间的路由。
- 保持现有8个执行队列槽，ACTION最多占7个未离队/结束的名额，给首次关闭保留执行槽及历史额度；仅标记取消不能提前归还物理槽。取消排队动作不关闭角色，已广播的消息不报告撤回；动作失败不得恢复或覆盖正在进行的关闭状态。操作终态历史不保存消息正文。
- 只解码已核实的北斗包布局，并沿角色实际字符集严格解码；未知、畸形包仅保留固定有界摘要，不能打断原生 `sendPacket`。正文截断须显式标明且不切断 Unicode 字符；当前场景是观察时状态，不冒充每条历史包的发生场景。不得以系统消息解码通过代替真实聊天或 NPC 验收。
- 幂等历史有界且在进程生命周期内不遗忘，进程重启后不能沿用旧去重保证。每个存活会话保留首次关闭额度，额外重试只使用余量；满容量时按明确错误恢复，不绕过校验或静默丢弃旧记录。聊天、NPC 与内置脚本按后续独立 Issue 实现，不借角色生命周期工具执行未开放动作。

## #205 机器人模块框架

- 当前候选实现使用 `ModuleDefinition<C, M>` 声明类型、控制资源、scope 额度、工厂及导出；`ModuleSelection<C>` 选择实例 ID、配置及启用标志。挂载返回 `MountedModule<M>` 的精确引用和实际实例。工厂接收模块自有配置并构造私有状态；实际工作与资源通过生命周期 hook 和原模块 scope 管理。
- 模块组合和配置属于源码中的装配方案，通过相应测试和验收。机器人正常工作周期内模块组合固定，变化的是已有模块的任务、状态和能力可用性。主控选择角色活动目标，能力模块完成既定任务及已启用服务的事件；具体职责和调用关系由装配方案确定。
- 装配登记实例和导出名称，再创建及初始化模块。暂停保留实例、配置和导出声明，卸载真实完成后撤下导出。停止或卸载清理 hook 失败时保留 `FAILED`、失败原因和未完成的清理责任，根关闭继续等待实际收尾。
- `ModuleFunction` 的说明、参数校验和精确实例绑定同源；调用按当前有效提供者及代次解析，工作归提供者 scope。Java `ModuleContext.help()` / `call()` 与北斗原生引擎内的 `modules.help()` / `modules.call(name, args)` 消费同一目录；缺失、停用或未激活正常返回 `UNAVAILABLE`，参数及执行异常沿现有脚本错误语义报告。
- 脚本仍按原 `kind/operation` 进入真实北斗环境，复用 `cm/qm/pi/rm/em` 各自对象及 `executeCommand`。MCP 负责协议桥接，共享能力由实际装配的模块声明。源码测试、CI、隔离与实际部署证据按本轮交付记录分别确认，不由候选实现推导已上线。

## #263 类型配置与 #122 导航接入

- 当前源码通过 `solomapling.robot-population-config` 读取单个路径或有序文件列表；未指定时仅读取内置默认文件。后文件按类型名完整替换同名 `types`，按类型名覆盖 `initial_population`，合并后一次编译装配计划及核对引用。每轮世界启动只创建一次初始人口；运行期通过 `robots.create/remove` 增减完整实例，文件与实例模块组合保持固定。
- 已有 MCP 普通专用角色固定引用 `mcp_test` 类型，保留原 profile、原生来源和当前位置。默认文件只包含长期使用的 `mcp_test`，显式装配 `mcp-control/navigation/robot-population`，初始数量0；以后新增非自主模块同步默认和本地配置及验收，自主控制放入对应业务类型。`navigation_probe` 等测试类型定义在 Git 忽略的 `RobotPopulation.local.yaml`，按需显式加载；现有一代人口计划继续保留。
- `navigation.moveTo(x,y)` 在同一实际场景同步完成规划、移动、抵达判断及本次收尾。规划和物理预测复用旧导航实现，实际帧沿原生移动入口和导航提供者自己的新work进入原角色lane；调用work已在执行中，不能二次提交。旧move/stop入口使用同一导航提供者。
- 脚本默认期限30秒、最大120秒，调用者取消持续关联到提供者work。导航自身默认30秒、8px容差、3秒无进展；真实抵达、失败、已发生位移和实际停止分别核验。源码和部署结果以#122/#263证据为准，参考 `docs/robot-runtime/population.md`、`navigation.md` 及脚本调试技能。

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

## #200 基本动作的增量交付

- #164 的候选背包移位能力位于中立 `soloMapling.game.action.NativeInventoryActions`，经运行体原模块 scope、Grant 与唯一 lane 执行；MCP 仅适配身份、请求、场景及结果，不实现第二份物品规则。实际部署以对应 Issue 和能力公告为准。
- `inventory_move` 仅接受五类普通背包内不同的正槽位1..127；零/负槽位属于其他原生分流，必须拒绝，不提前开放丢弃或穿脱。原生整槽移动、交换、合堆与300ms限频保持原样。
- 成功必须核同一原生背包锁内的两槽前后事实，派发后取消不撤回；原生事实与work真实结算分开。背包存档另核正常退出及同CID重开，既有 `persisted_state_verified` 不包含背包。详见 `docs/shared-game-foundation/inventory-move.md`。

## #200 其余基本操作候选

- `move/stop/attack/pickup/drop/use_item/equip/unequip` 经运行体共享 Java 能力执行，短命令共用 `NativeBasicActions`，移动控制器在模块scope内登记编排work和定时资源，每个原生帧仍走原角色lane；MCP不另建动作队列或角色账本。实际交付与能力公告分别核对。
- 移动取消可保留已发生位移并报告已真实停止，不以Future.cancel或lane暂空当排空。根退出需等待 `whenModulesDrained` 真实信号再向原lane提交根cleanup。
- 攻击 `basic_melee_v1` 是共享后端基于原生属性和怪物数据的基础近战政策，不宣称与全部客户端算法等价；不接受damage/skill/任意包。物品使用只开放普通HP/MP药剂，拾取只普通库存地物，装备沿原生元数据和有限普通槽位。具体参数、事实与验收见 `docs/shared-game-foundation/basic-control.md`。

## 北斗脚本执行入口

- MCP 用于机器人开发调试：Agent 进入游戏，与待测机器人交互并核对实际行为。`script_run` 只执行北斗脚本，直接接收顶层 `kind/operation/context_id/options/entry/args`，不保留独立纯JS模式或外层 `environment` 兼容分支；缺少kind/operation及旧字段请求明确拒绝。
- `source` 默认空字符串，可只执行选定原脚本的入口；非空片段在同一真实北斗引擎中运行。脚本内的参数运算、输出和JSON结果处理仍可用，不等于另有执行模式。环境与接口通过 `skills/solomapling-script-debug/` 的说明书查阅实际适配器及北斗源码；上下文标识和状态来自执行结果。
- `options/args/parameters` 的16KiB输入预算仅由服务端按其规范化JSON UTF-8编码裁决；客户端保留类型、有限数值、Unicode、节点数和深度校验，不再重复计算本地编码字节数或要求跨语言编码器产生同样长度。语言stdout/stderr使用持续UTF-8编码收集完整字符，游戏聊天的原生字符集限制另行保留。
- 脚本设施由 `CharacterRuntime` 承载，消费当前模块代次的原 `ExecutionScope.WorkHandle`，在受管理载体执行，不占角色短命令lane。MCP保留鉴权、协议、幂等历史和结果投影，不建另一角色宿主、动作队列或物理工作账本。
- 当前客户端为九工具，既有十三动作及独立管理指令保留。实际部署与源码测试、CI、隔离验收分层记录，以对应Issue及能力公告为准；当前说明见 `docs/robot-runtime/script-execution.md`。

## #216 北斗脚本上下文

- #247 的 `script_run` 可用 `output_file/error_file` 将原 operation 的 `started/output/gap` 事件实时写入 MCP 宿主本地文件，UTF-8 NDJSON 追加并逐条刷新。单独提供 `output_file` 时合并双通道，同时提供 `error_file` 时分流 stderr，仅提供后者时只记录 stderr；路径不进入后端请求或执行身份。无文件时走同步 `/script/run`，只返回最终结果，MCP 调用不发送 progress 通知。有文件时复用 `/script/stream`；输出共用序号，每通道16KiB、窗口256条，`since_output` 用于原请求恢复和 `operation_get.script_output`。源码、SDK与文件提前可读、隔离和实际部署分别留证，具体协议见 `docs/robot-runtime/script-execution.md`。

- `kind` 选择npc/quest/portal/reactor/event，`operation` 为start/invoke/close。帮助与实际适配器说明同源，不为北斗每个Java方法另造同义函数。NPC/QM复用原管理器、精确CM/QM、Client缓存引擎和回调状态；门户和机关来自当前实际地图；事件使用实际Channel与同一受管Invocable创建角色自有原生EventManager，不替换或取消频道共享事件。
- 每模块最多一个持久Context，后续调用使用原context_id及原模块代次。`invocation_settled/work_settled` 证明本次调用结算；`context_closed=false,context_state=OPEN` 可以是正常保留，最终关闭另核引擎、精确原生会话和调度资源真实释放。不得接管或清理外来、后继CM/QM，旧世界或旧模块不能续权。
- 提前取消尚未启动的invoke/close只取消本次调用，不自动关闭原Context；检查点从原运行体按精确context_id投影现有事实，无法确认时标UNKNOWN，不能凭CANCELLED或work已结算编造context_closed=true。start未创建上下文的NOT_STARTED只描述本次新建尝试。
- 期限、取消、模块停止及根关闭均须核实际执行退出。`Future.cancel/isDone`、HTTP返回或operation终态不能单独证明收尾；guest循环已停也不代表host Java方法已返回。原生异步调度、已出队回调和未知资源继续保留原scope债务。
- 首环#219在原生预热副作用前明确拒绝尚未接入的owned EIM创建；完整EIM及一分钟延迟地图释放、更多脚本组合、机器人实际交互和真实客户端玩法仍按各自证据验收。北斗submodule只读，适配和织入仅在Solo维护。对象来源和连续调用见 `docs/robot-runtime/beidou-script-environment.md`，不提前将整个#216标完成。

## #207 独立管理指令调试入口

- 管理指令直接复用北斗原生管理服务，与 CharacterRuntime、模块 scope/Grant 和角色 lane 解耦。角色、背包和存档数据仍在北斗服务端；不得以未经验证的并发假设强加运行体前置。后续 #199 只需将同一适配绑定到脚本环境，不复制实现。
- `game_command` 独立于 `game_act`；需本地 `enabled`、`actors-enabled`、`debug-commands-enabled` 三开关同时启用，新增开关默认关闭。沿正常管理员 JWT，目标限已有 profile 登记的在线普通专用角色；首轮仅普通 USE/ETC 可堆叠物品，不透传全服、提权或其他资源分支。
- 请求携带独立 `expected_command_generation`，从 capabilities 获取。按 owner/request_id 保留本进程最多256条历史且不淘汰，原ID同参数恢复事实、变参拒绝、旧进程代次拒绝；未知结果不新ID重发。实际数量前后态和同CID重开存档分开验证。各层交付以 #207 为准，详见 `docs/mcp-acceptance/commands.md`。

## 通用命令桥（取代 #207 受限give包装）

- 脚本注入 `executeCommand(text)`，MCP `game_command` 也调用同一 `NativeCommandExecutor.execute(Client,String)`，完整文本原样交给CommandsExecutor.handle；不逐命令/物品适配，不保留旧give/type5/USE-ETC白名单。脚本不注入帮助；技能脚本通过北斗已有只读命令查询定位处理类。
- 权限按用户本轮要求暂不改动：管理员JWT/已登记普通目标沿现有边界，原生GM/配置检查照常；不提权、不绕过，也不把权限拒绝改称桥不支持。角色数据归北斗，不增加运行体lane或第二队列。
- RETURNED仅表示void handle返回，command_outcome=UNREPORTED；未知或权限/忙/未知命令反馈不能推导业务成功。MCP旧parameters格式拒绝，历史只存命令摘要；原ID/管理进程代次恢复及容量边界保持。详见docs/mcp-acceptance/commands.md，各层验收以本轮交付记录为准。

## #201 源码开发技能

- `skills/` 提供游戏交互、脚本调试与机器人验收三个技能，`references/` 是所属技能的使用说明书，随源码整套分发。
- 源码中的方法、继承、返回类型和原脚本直接查阅；生成方法按需查询匹配构建产物，不手写 API 全集。
- 当前提供九个执行／观察工具、十三动作和实际 capabilities。环境、对象方法与命令查询通过相应技能导航源码或北斗既有只读目录；命令查询脚本不执行命令或改变配置。

## 导航抵达容差

- `arrival_tolerance_px` 支持4～32767px，默认8px；配置边界拒绝小于现有地面4px停靠距离的值，不自动钳制，也不修改原路径规划、物理积分及停靠算法。实际交付以#269的分层验收证据为准。
