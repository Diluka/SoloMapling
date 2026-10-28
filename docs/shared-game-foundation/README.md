# 共享游戏基建

[#117 角色上下文与世界代次](https://github.com/Diluka/SoloMapling/issues/117) 已完成完整构建、CI、隔离及实际部署验收并关闭，由 MCP 和 Bot 两种薄适配消费。该轮已验收交付源码为 `8f8ebf485c719b868d5ba7ab4a6950632bfff730`（[PR #130](https://github.com/Diluka/SoloMapling/pull/130)）。

[#118 执行作用域与取消排空](execution-scope.md) 提供公共执行与资源机制；[#119 原生动作与结果证据](native-actions.md) 将普通聊天接入一份 Java 执行链，MCP/Bot 分别薄适配。交付状态和各层验收证据以对应 Issue 为准，代码合并或定向测试不代表实际部署通过。其他动作、V2 组合及 PQ 通关分别验收。

本目录说明内部 Java 接口与使用边界。MCP 的工具、配置和实际玩法记录见 [MCP 验收说明](../mcp-acceptance/README.md)，构建入口见 [集成构建](../../beidou-integration/README.md)。所有新增实现都在 Solo，官方北斗 submodule 仍固定 `75a86268738ca8845e06ea9d59ec36d15b22c91b`，受控文件保持原样。

## 谁负责什么

共享上下文只核对调用方指定的原生对象当前是否一致，供 MCP、V2 和 PQ 各自的 Java 适配消费。它不决定谁能控制角色，不执行 handler，不读账号登录状态，也不调度动作。构造 `CharacterRef/SceneRef` 或取得世界 token 文本不会授予角色控制权。

| 层次 | 当前职责 |
|---|---|
| `soloMapling.game.context` | 世界生命周期 token；角色、客户端、世界、频道、地图与事件的精确对象关系；固定结构失败原因 |
| `soloMapling.game.execution` | 工作/资源归属、取消与真实排空、角色短命令时序；不含模块优先级或业务授权 |
| `soloMapling.game.action` | 受控普通聊天、真实原生派发检查点和类型化证据；不开放任意 handler、原始包或脚本 |
| MCP 薄适配 | 继续保留普通账号、GM0、排除 bot、原生登录标志、账号 lease/在线归属；JWT/admin、owner/profile/session/request_id 历史仍在 MCP 服务 |
| Bot 薄适配 | `BotClient` 与 `BotRegistry.isRegistered` 的精确角色实例登记，不借用 MCP 账号或 session |
| V2/PQ 消费侧 | 模块/预设、优先级、资源仲裁、动作资格及公共 scope 的接入；本轮未迁入或宣称已运行 |

V2/PQ 不经 Python、HTTP 或 MCP 发动作，也不复制整个 `ActorControlService`。#110 已验收的 NPC 实现仍有自己的来源、路径、offer 和版本策略，普通聊天公共执行见 #119；NPC 来源、版本、offer 和路由尚不属于该公共动作，继续由 #121 单独提炼与验收。

## 公共 Java ABI

源码位于 `beidou-integration/src/main/java/soloMapling/game/context/`。以下签名省略实现；它们是进程内对象引用，不是对外 DTO 或可反序列化的控制句柄。

```java
public final class WorldEpoch {
    public static void onWorldStarted();
    public static void onWorldStopping();
    public static State snapshot();
    public static StopTransition stopTransition();
    public record StopTransition(State source, State stopped, boolean transitioned) {}
    public record State(boolean active, Token token) {}
    public static final class Token {
        // 构造不公开，按对象身份比较。
        public String value();
    }
}

public final class GameContext {
    public record CharacterRef(WorldEpoch.Token epoch, Character character,
                               Client client, World world, Channel channel) {}
    public record SceneRef(CharacterRef actor, MapleMap map,
                           EventInstanceManager memberEim,
                           EventInstanceManager mapEim) {}
    public enum Failure {
        WORLD_OFFLINE, WORLD_CHANGED, CHARACTER_CHANGED, NOT_IN_WORLD,
        SCENE_CHANGED, EVENT_CHANGED, RUNTIME_CHANGED, READ_UNAVAILABLE
    }
    public static final class Invalid extends RuntimeException {
        public Invalid(Failure reason);
        public Failure reason();
    }
}

public final class NativeContextChecks {
    public static GameContext.CharacterRef captureCharacter(
        Server server, Character character, Client client, World world, Channel channel);
    public static void requireCharacterCurrent(Server server, GameContext.CharacterRef ref);
    public static GameContext.SceneRef captureScene(Server server, GameContext.CharacterRef ref);
    public static void requireSceneCurrent(Server server, GameContext.SceneRef ref);
}
```

这里的 `Character` 是北斗 `org.gms.client.Character`。实际定义见 [WorldEpoch](../../beidou-integration/src/main/java/soloMapling/game/context/WorldEpoch.java)、[GameContext](../../beidou-integration/src/main/java/soloMapling/game/context/GameContext.java) 和 [NativeContextChecks](../../beidou-integration/src/main/java/soloMapling/game/context/NativeContextChecks.java)。

### 角色绑定与场景引用分开

`CharacterRef` 固定捕获时的世界 token、Character、Client、World 和 Channel。重验包括角色与客户端双向指向、实际世界/频道对象及路由ID、两级 PlayerStorage 和当前地图中同 CID 的精确角色对象。数字ID只用于已指定容器内的定点查找，同ID的新对象不能替换旧绑定。

它不固定地图或 EIM。角色正常从地图X进入同mapId的实例Y后，只要上述登记一致，原 `CharacterRef` 仍可用；换客户端、频道、世界对象或世界 token 后旧绑定失效，调用方不能重捕当前 token 让旧句柄自动恢复资格。

`SceneRef` 另固定实际Map、角色memberEim和地图mapEim。跨图、任一EIM引用变化、成员不再精确登记或EIM已dispose会使旧场景引用失效。memberEim与mapEim分别核对，不强制相等，也不按事件名称目录判断存活。EIM故障不是角色结构失效或正常退出的前提。

### 结构读取的边界

公共检查接收调用方已持有的 Server，不调用 `Server.getInstance()`、MCP开关或全世界目录扫描。它不读 `Client.getLoginState`，不加载角色、地图或副本，不写注册关系、不执行脚本、不修复状态。`Client.isLoggedIn`、`Character.isLoggedIn/World` 等标志也不属于公共结构检查。

读取前后重验世界状态和对象关系；读取期间换代返回 `RUNTIME_CHANGED`，原生getter抛出的读取故障转为 `READ_UNAVAILABLE`，不附原生异常正文。对非空EIM仅短暂同步读取原生dispose标志，其余多对象读取不是原子事务。动作真正执行前仍需消费侧再验，不能把一次检查当持续有效的执行许可。

## 两种薄适配

[AcceptanceActorContexts](../../beidou-integration/src/main/java/org/gms/soloacceptance/actor/AcceptanceActorContexts.java) 的 `requireCharacter/captureScene` 先核验收 Handle 的账号、profile、lease、当前在线Client、普通登录标志、GM0和排除BotRegistry等资格，再调用公共结构检查。`NativeActorHandle` 在原生登录完成时保存一次 `CharacterRef`；`requireSceneCurrent` 是已授权场景的结构复核，不重新授予owner权限。MCP服务继续负责管理员与owner/session等外部边界。

[BotGameContexts](../../beidou-integration/src/main/java/soloMapling/beidou/BotGameContexts.java) 提供 `capture/requireCurrent/captureScene/requireSceneCurrent`。`capture` 只接受指定的 `BotClient` 和精确注册角色，返回构造不公开的 `Binding`；后续重验继续检查registry，`Binding.characterRef()` 用于内部结构消费。注销、同CID换对象或换客户端后旧绑定拒绝。

当前V1 bot经非频道模板加载，真实 `Character.loggedIn=false`，即使 BotClient 自身报告登录且地图/频道/世界均已登记。Bot适配不能为共用接口强改该标志或引入普通账号存档；MCP仍严格核自己的登录、lease与权限。Bot上下文通过不等于已满足每个原生handler的玩法资格，动作规则留给对应共享动作切片。

## 世界、会话和事件标识的作用域

| 标识 | 谁维护、何时失效 |
|---|---|
| `WorldEpoch.Token` | 共同世界生命周期；每次启动及实际 active→stopped 过渡换新 token；同次受控停止复用当前停止 token，旧绑定始终失效 |
| MCP `runtime_generation` | 同一个世界token的文本投影；不是第二套独立世界时钟 |
| MCP session ID/代次、profile、lease | MCP验收会话与账号控制权；同CID重开不能继承旧会话资格 |
| V2 runtime/module代次、execution/scope ID | V2消费侧维护，公共上下文不合并其策略或仲裁 |
| packet sequence、dialogue_version、NPC来源token | 对应Client/对话层，不与世界token互换或比较大小 |
| MCP `scene_ref/runtime_key` | 观察服务的不透明外部引用，与内部 `GameContext.SceneRef` 分开 |

[BeiDouRuntime](../../beidou-integration/src/main/java/soloMapling/beidou/BeiDouRuntime.java) 在共同 ready 入口启动 WorldEpoch； #118 在 stopping 入口经 `ExecutionScope.tryBeginWorldStop()` 原子停止并取得专用票据，再进行 actor drain 和原生清理，不以验收开关为条件。[AcceptanceObservationRuntime](../../beidou-integration/src/main/java/org/gms/soloacceptance/AcceptanceObservationRuntime.java) 仅作为旧接口的无独立状态委托；停止委托复用同一受控工厂，生命周期接线不另造世界时钟或清理权限。

世界停用后，新动作必须拒绝旧 token。 #118 将原已拥有角色的登出接入 `ScopeResources.DrainPermit`，保留 MCP 的精确注册、lease 和身份检查；只有对应停止来源及当前清理窗口内的原生入口才可继续。下一世界启动后旧许可不能进入新清理，已经合法完成的旧资源释放可如实入账。具体票据、资源握手及取消/排空区别见[执行与清理参考](execution-scope.md)；公共 API 没有忽略 epoch 的入口。

## MCP定点观察与失败语义

[AcceptanceObservationService.contextForActor(CharacterRef)](../../beidou-integration/src/main/java/org/gms/soloacceptance/AcceptanceObservationService.java) 从已授权绑定捕获当前SceneRef，仅登记/复用该实际场景的MCP引用，不为一个角色刷新全世界目录。弱引用、容量、随机key和JSON形状仍属MCP；world/scene目录浏览保留原发现路径。

角色离开地图会使旧Actor SceneRef不再适合该角色动作；若旧地图仍在原目录可发现范围且世界/频道/地图/runtime身份不变，其全局MCP scene_ref仍可能只读可用。私有实例不因本轮获得更广的全局可见性。

公共 `Failure` 不直接充作新HTTP协议。MCP保留各入口错误顺序：已知旧session/epoch为410，当前动作场景不符为409，读取期间换代为409；世界停止为503。旧外部scene_ref消失仍按原410处理。

读取故障是503 `actor_observation_unavailable` 或当前操作的固定未知证据，不能只返回false而把仍在线角色改为LOST；结构丢失、场景变化与读取不可用分别处理。[#114](https://github.com/Diluka/SoloMapling/issues/114) 的Python错误映射已按同世界关闭会话、有效scene与真实过期scene独立完成隔离和实际验收关闭，未以共享上下文测试代替。

## #117 交付证据与后续切片

#117 已有公共上下文、MCP 适配和 Bot 适配的定向测试。Bot测试使用真实Character、BotClient、Map和PlayerStorage登记对象，宿主路由使用受控替身；覆盖实际V1登录标志、跨图后旧场景失效、同ID换对象、注销、世界换代和读取故障。它证明第二种薄适配可以独立消费公共结构，未执行完整V2模块或PQ玩法。

测试源码见 [公共角色检查](../../beidou-integration/src/test/java/soloMapling/game/context/NativeContextChecksTest.java)、[公共场景检查](../../beidou-integration/src/test/java/soloMapling/game/context/NativeContextSceneTest.java)、[MCP身份适配](../../beidou-integration/src/test/java/org/gms/soloacceptance/actor/AcceptanceActorContextsTest.java) 与 [Bot适配](../../beidou-integration/src/test/java/soloMapling/beidou/BotGameContextsTest.java)。#117 当轮完整469项Java、5项打包和216项MCP测试及CI已通过；隔离SDK实际完成普通角色登录/中文发言/活跃NPC、同JVM世界停启、停代次后的原归属角色正常保存退出、同CID重开及旧NPC回复回归。#114另以同一世界内的已关闭会话、仍加载场景与真正过期场景分别验证，未用共享通过代替错误恢复验收。实际部署的普通角色登录/聊天/NPC/保存退出与重开、错误恢复、数据审计及网页端口也已通过；Bot14项合同证据不冒称V2/PQ实物。首次main CI因依赖镜像下载超时未执行测试，同提交重跑通过，分发可靠性另由#131跟踪。

| [#116共享入口](https://github.com/Diluka/SoloMapling/issues/116) 下的切片 | 本文对应状态 |
|---|---|
| [#117上下文与世界代次](https://github.com/Diluka/SoloMapling/issues/117) | 已交付并关闭；完整/隔离/实际原生验收通过，Bot薄适配合同另列 |
| [#118作用域与排空](https://github.com/Diluka/SoloMapling/issues/118) | 公共实现与 MCP 接线见[执行参考](execution-scope.md)；交付状态及各层验收证据见对应 Issue |
| [#119原生动作与证据](https://github.com/Diluka/SoloMapling/issues/119) | 普通聊天公共执行与 MCP/Bot 薄接入见[动作参考](native-actions.md)；交付与各层验收证据见对应 Issue |
| [#120按需事实与事件](https://github.com/Diluka/SoloMapling/issues/120) | 待实现；不把MCP全局目录变成所有消费侧依赖 |
| [#121原生NPC会话](https://github.com/Diluka/SoloMapling/issues/121) | 待实现；承接当前MCP来源、版本、offer和CM/路由策略 |
| [#122](https://github.com/Diluka/SoloMapling/issues/122)、[#123](https://github.com/Diluka/SoloMapling/issues/123)、[#124](https://github.com/Diluka/SoloMapling/issues/124)、[#125](https://github.com/Diluka/SoloMapling/issues/125)、[#126](https://github.com/Diluka/SoloMapling/issues/126)、[#127](https://github.com/Diluka/SoloMapling/issues/127)、[#128](https://github.com/Diluka/SoloMapling/issues/128)、[#129](https://github.com/Diluka/SoloMapling/issues/129) 各动作 | 待实现，按实际依赖逐叶验收 |

已完成的 MCP #110 不等于共享 #121 已完成；#119 普通聊天提炼不包含 NPC 会话或其他原生动作。后续先推进共享基础的实际依赖，#112礼包、#113完整菜单/付费出行暂后排；既有任务树和里程碑保留，各共享叶子独立记录测试、第二适配、隔离和实际结果后关闭。

## 基本动作增量

[#164 背包移位](inventory-move.md) 提供运行体模块可直接消费的中立原生能力，MCP 仅作薄接线；其实际交付与部署证据单独核对。

[#200 角色基本操作](basic-control.md) 扩展移动与停止、单次近战攻击、拾取丢弃、药剂使用及装备穿脱；运行体共用执行、证据与取消排空，MCP只做薄接入。各层实际交付见对应Issue。
