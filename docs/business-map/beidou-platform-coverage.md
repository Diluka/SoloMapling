# 北斗账号、角色与运营：责任及源码覆盖

本轮只读固定北斗 `1.12`，提交 `75a86268738ca8845e06ea9d59ec36d15b22c91b`。下表所有短路径都以 `upstream/BeiDou-Server/gms-server/src/main/java/org/gms/` 为基准。本地图依据当前源码，不宣称当前部署、迁移、浏览器、实际模型或游戏客户端已经验收。

## 责任边界

| 所有者 | 结果与受益人 | 拥有的状态、规则和生命周期 | 协作者与界限 |
| --- | --- | --- | --- |
| `beidou.identity` | 玩家获得所属账号与角色，以及可核对的普通世界接入和退出存档路径。 | 账号凭据和准入、角色账号/世界归属、创建名额及初始配方、登录过渡、角色加载与原生保存、删除、改名与转区登记。包括自助资料更新与管理入口的不同规则。 | 世界域拥有地图与频道成员，经济/战斗/社交/任务域拥有各自玩法规则。持久角色保存聚合这些状态，不把相应业务所有权吞入身份域。Solo模板读取和MCP来源复用此接口，权限策略归Solo。 |
| `beidou.operations` | 运营人员通过正常管理页面与API查看和维护游戏服务。 | 当前管理员身份、JWT使用、HTTP入口限流、应用与游戏服启停、运营参数持久及热应用、在线查询、账号/资源/内容管理编排。 | 账号主数据仍归身份域，世界拓扑归世界域，发放资源的具体收益归经济/战斗域。应用配置文件位置及默认值与实际部署配置分开；只记录源码支持的热更新。 |

## 从源码入口独立建立的覆盖

| 场景或生命周期 | 决定性来源 | 地图节点或流程 |
| --- | --- | --- |
| 密码登录、可配置自动注册、密码迁移、封停及重复登录拒绝 | `net/server/handlers/login/LoginPasswordHandler.java#handlePacket`、`client/Client.java#login` | `identity.login`、`accounts`、`login-state`、`account-access`；登录flow分别保留认证、选角、频道拒绝 |
| 角色名额/名称、默认外观与职业开关决定创建 | `net/server/handlers/login/CreateCharHandler.java#handlePacket`、`client/creator/CharacterFactory.java#createNewCharacter` | `identity.create-character`、`creation-policy` |
| 角色属于账号、设备会话、世界容量与地址检查 | `net/server/handlers/login/CharSelectedHandler.java#handlePacket` | `identity.select-character`、`account-access` |
| 频道接入核过渡，加载/复用角色并注册地图 | `net/server/channel/handlers/PlayerLoggedinHandler.java#handlePacket` | `identity.enter-world`、`native-character`；跨域写`world.scene` |
| 普通玩家与非频道模板加载模式 | `service/CharacterService.java#loadCharFromDB`、`client/Character.java#loadCharFromDB` | `identity.load-character`；不把非频道加载视为普通玩家上线 |
| 自动保存调度与真实UPDATE保存 | `client/Character.java#saveCharToDB` | `identity.save-character`、`characters`；未登录条件退出，不用空的`CharacterService.saveCharToDB`证明保存 |
| 关闭交互、地图/世界离线、冷却与角色保存、最终登出或频道过渡 | `client/Client.java#disconnectInternal`、`#forceDisconnect` | `identity.close-character`及退出flow；明确异步受理与实际完成分别确认 |
| 游戏删除PIC/公会/家族/待转区限制；管理删除先处理在线对象 | `net/server/handlers/login/DeleteCharHandler.java#handlePacket`、`service/CharacterService.java#deleteCharacterWithOnlineCheck` | `identity.remove-character`；不把游戏入口限制推定为所有管理入口都相同 |
| 注册、旧密码自助修改、离线管理修改、封停/解封与账号注销级联 | `service/AccountService.java#addAccount`、`#updateAccountByUser`、`#updateAccountByGM`、`#banAccount`、`#unbanAccount`；`service/CharacterService.java#deleteAccount` | `identity.maintain-account`；管理编排由`operations.manage-accounts`调用 |
| 改名登记/冷却/撤销、启动及条件登出应用 | `service/NameChangeService.java#registerNameChange`、`#doNameChange`、`#cancelPendingNameChange`；`client/Character.java#doPendingNameChange` | `identity.rename-character`；应用还同步戒指名称和处理改名卡 |
| 转区登记/冷却/撤销、启动应用前重核及持久变更 | `service/WorldTransferService.java#registerWorldTransfer`、`#checkWorldTransferEligibility`、`#doWorldTransfer`、`#cancelPendingWorldTransfer` | `identity.transfer-world`；目标世界引用`world.topology`，金币、公会、好友副作用留源码证据，后续跨域关系由其所有者对齐 |
| 管理凭据与JWT签发，正常使用时再核当前webadmin | `service/AuthService.java#getToken`、`aop/AuthTokenFilter.java#doFilterInternal`、`service/UserDetailsServiceImpl.java#loadUserByUsername` | `operations.admin-login`、`admin-session`、`admin-authorized`；flow区分取得token与取得管理资格 |
| 管理公开路径与HTTP来源封禁/限流 | `config/SpringSecurityConfig.java#filterChain`、`aop/ServerFilter.java#doFilter` | `operations.admin-api`、`request-gate`；不假定所有HTTP都是管理业务或幂等请求 |
| 应用前置建库、Spring/Flyway及游戏启动 | `ServerApplication.java#main`、`#initDb`、`manager/ServerManager.java#run`；上游`src/main/resources/application.yml`的Flyway声明 | `operations.bootstrap`、`start-game`；只描述入口与配置声明，不运行数据库或启动程序 |
| 立即/倒计时停止、重启和应用销毁 | `controller/ServerController.java#stopServer`、`#stopServerWithMsgAndInternal`、`#restartServer`；`manager/ServerManager.java#destroy` | `operations.stop-game`；调用`world.stop-world`，不推定Solo后台工作已排空 |
| 数据库规则增删改、YAML导入导出与内存刷新 | `service/ConfigService.java#addConfig`、`#updateConfig`、`#deleteConfig`、`#importYml`、`#exportYml`；`config/GameConfig.java#update` | `operations.update-config`、`operating-config`；即时世界倍率变化跨域写`world.topology` |
| 服状态、世界频道列表、实际在线角色分页 | `controller/ServerController.java#online`；`service/ServerService.java#worldList`、`#channelList`；`service/CharacterService.java#getChrOnlineList` | `operations.inspect-online`；不以创建尝试数代替实际在线成员 |
| 资源发放、在线目标拒绝、个人倍率白名单 | `service/GiveService.java#give`、`#giveChr`；`service/CharacterService.java#updateRate` | `operations.manage-resources`；经济/战斗结算的所有权仍在原域 |
| 文件树边界内读写、命令状态与GM重载入口 | `service/FileTreeService.java#writeFile`、`service/CommandService.java#updateCommand`、`#reloadEventsByGMCommand`、`#reloadMapsByGMCommand` | `operations.maintain-content`；具体脚本玩法不在运营域重复定义 |
| 管理页面携带令牌、请求信封、业务错误/401与下载 | `upstream/BeiDou-Server/gms-ui/src/api/interceptor.ts` | `operations.admin-api`；Vue组件和拦截器作为管理体验的实现锚点，不另划技术层域 |

## 限制与停止点

- 上游说明中的版本文字可能滞后，地图身份以固定submodule提交为准。
- 管理登录签发JWT本身不校验`webadmin`，正常受保护请求再加载当前管理员。现有源码另含需同时启用Swagger的测试token分支；地图显式限定“正常管理路径”，不把测试捷径当作MCP或集成鉴权方案。
- 当前`AuthController.logout`只返回成功，不建立服务端撤销记录；地图没有编造即时令牌撤销保证。
- 原生保存可能调度、拒绝未登录角色或记录异常。源码里的方法调用、HTTP成功和终态名称不能证明本次实际落库；持久化验收需另有运行证据。
- 世界转移确有金币限额、公会与好友变化；改名确有戒指名称同步及改名卡处理。地图保留这些后果，不宣称跨域多表操作具备本轮未验证的完整原子性。
- 部署流程、镜像构建、备份和Solo控制代次归本项目，未写成上游能力。未访问或修改任何游戏数据库，未启动服务、迁移或发送游戏动作。

## 跨域关系的最后核对

| 源业务 | 已确认的跨域读写或调用 | 决定性源码 |
| --- | --- | --- |
| `identity.rename-character` | 写 `economy.inventory` 与 `economy.assets-in-custody`：清理角色背包和账号商城保管的改名卡；写 `social.marriage-state`：同步戒指 `partnername`。不声称重建其他婚姻资料。 | `NameChangeService.doNameChange` 对 `characters`、`rings`、`namechanges` 的更新，以及按角色/账号删除改名卡记录。 |
| `identity.transfer-world` | 读写 `economy.wallet`：随带金币为原值与1000000的较小值；写 `social.friend-list`：删除双向持久好友关联；写 `social.guild-state`：只清角色侧公会ID/职级；读 `social.marriage-state`：持久 `partnerId` 非null会拒绝应用。 | `WorldTransferService.doWorldTransfer`、`checkWorldTransferEligibility`；没有据角色字段变更推定全部社交内存对象已同步。 |
| `operations.manage-resources` | 写 `economy.inventory`、`economy.wallet`、`combat.growth`：原生道具装备发放、金币点券和经验入口。 | `GiveService.giveItemChr/giveItemAllOnlineChr`、`doGainCash/doGainMeso/doGainExp`，后者分别调用 `CashShop.gainCash`、`Character.gainMeso/gainExp`。 |
| `operations.maintain-content` | 消费 `world.events`：逐频道取消旧事件脚本管理器并按事件集合重建。 | `CommandService.reloadEventsByGMCommand` → `Channel.reloadEventScriptManager`。 |

原生 `Server.init` / `shutdownInternal` 已单列为 `world.lifecycle` 接口，供集成生命周期织入引用；事件脚本的 `world.events` 保持独立。全部关系放在源概念拥有的YAML中，数据仅在其业务域定义一次。
