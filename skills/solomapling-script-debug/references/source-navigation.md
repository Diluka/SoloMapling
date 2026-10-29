# 从源码查阅脚本接口

以下链接相对于随技能分发的 SoloMapling 源码工作树。先确认 `git rev-parse HEAD` 与实际服务的源码身份，再按本次问题选择入口。

## 找到实际绑定和原入口

| 环境 | Solo 适配入口 | 原生类型 |
|---|---|---|
| NPC、任务 | [NativeDialogueScripts](../../../beidou-integration/src/main/java/org/gms/soloscripting/NativeDialogueScripts.java) 的 `descriptor/start/invoke` | [NPCConversationManager](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/npc/NPCConversationManager.java)、[QuestActionManager](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/quest/QuestActionManager.java) |
| 门户 | [NativePortalScripts](../../../beidou-integration/src/main/java/org/gms/soloscripting/NativePortalScripts.java) | [PortalPlayerInteraction](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/portal/PortalPlayerInteraction.java) |
| 机关 | [NativeReactorScripts](../../../beidou-integration/src/main/java/org/gms/soloscripting/NativeReactorScripts.java) | [ReactorActionManager](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/reactor/ReactorActionManager.java) |
| 事件 | [NativeEventScripts](../../../beidou-integration/src/main/java/org/gms/soloscripting/NativeEventScripts.java) | [EventManager](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/event/EventManager.java) |

`descriptor()` 说明当前绑定的对象、选择参数和入口；`start/invoke` 说明调用原管理器的实际路径。运行服务已公告的环境可从 `game_capabilities.actor_control.native_environments` 取得。

从选定脚本的真实路径继续阅读。中文原脚本位于 [scripts-zh-CN](../../../upstream/BeiDou-Server/gms-server/scripts-zh-CN/)，其他原脚本位于 [scripts](../../../upstream/BeiDou-Server/gms-server/scripts/)。文件首次求值和管理器注入对象的时机不同，顶层代码可用对象以加载及绑定路径为准。

## 沿继承和返回类型查方法

先用语言服务定位定义；不可用时在相关类中使用限定搜索。不要从其他服的同名函数推断北斗用法。

例如查 `cm.getPlayer().getLevel()`：

1. 在 `NPCConversationManager` 确认其父类，继续读 [AbstractPlayerInteraction](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting/AbstractPlayerInteraction.java)。
2. 从 `getPlayer()` 的声明取得实际返回类型，继续读 [Character](../../../upstream/BeiDou-Server/gms-server/src/main/java/org/gms/client/Character.java)。
3. `Character` 的部分访问器来自 Lombok，结合字段和注解确认；需要精确公开签名时查询对应构建产物。

在仓库根执行：

```bash
rg -n 'getPlayer|class NPCConversationManager|class QuestActionManager' upstream/BeiDou-Server/gms-server/src/main/java/org/gms/scripting
javap -public -classpath beidou-integration/target/woven-classes org.gms.client.Character
```

`javap` 使用本工作树已准备且源码身份匹配的构建产物。产物尚未准备时继续读源码；需要构建时沿[项目构建入口](../../../beidou-integration/README.md)准备，不用其他版本的 class 作为当前 API 证据。

查 `getMap()` 等方法时同样沿声明返回类型查阅。涉及重载，核对每个候选的参数和实现；存在调用不等于有相同资产语义，例如 `AbstractPlayerInteraction.useItem` 的实现不能只按名称理解为背包扣药。

## 查 Solo 对原生行为的适配

- 调用参数与原生入口的组合：从 [MCP 工具声明](../../../tools/acceptance-mcp/src/solo_acceptance_mcp/server.py) 的 `script_run` 和当前客户端 schema 读取。
- 引擎、参数和命令绑定：读 [NativeScriptSession](../../../beidou-integration/src/main/java/soloMapling/game/script/nativeenv/NativeScriptSession.java)。
- 原文件首次求值和注入：读 [NativeScriptLoader](../../../beidou-integration/src/main/java/org/gms/soloscripting/NativeScriptLoader.java) 及 [NativeScriptEnvironmentAspect](../../../beidou-integration/src/main/aspect/soloMapling/beidou/NativeScriptEnvironmentAspect.aj)。
- 调度归属与 owned EIM 限制：读 [NativeScriptSchedulerAspect](../../../beidou-integration/src/main/java/org/gms/soloscripting/NativeScriptSchedulerAspect.java)。当前受管 EM 的实例创建入口明确拒绝；普通频道事件有自己的所有权。
- 类型装配与初始人口：读 [RobotPopulationConfig](../../../beidou-integration/src/main/java/soloMapling/game/population/RobotPopulationConfig.java)、[RobotPopulationService](../../../beidou-integration/src/main/java/soloMapling/game/population/RobotPopulationService.java) 和[默认类型配置](../../../beidou-integration/src/main/resources/soloMapling/game/population/RobotPopulation.yaml)。现有 MCP 角色固定引用 `mcp_test` 类型。
- 模块共享导出：读 [ModuleFunction](../../../beidou-integration/src/main/java/soloMapling/game/runtime/ModuleFunction.java) 及对应模块的声明。[NavigationActionModule](../../../beidou-integration/src/main/java/soloMapling/game/navigation/NavigationActionModule.java) 声明目标导航，[RobotPopulationModule](../../../beidou-integration/src/main/java/soloMapling/game/population/RobotPopulationModule.java) 声明完整实例的创建和移除；JS 的 `robots` 简写仍从 `NativeScriptSession` 绑定处查阅。

查阅完成后保留所依据的类、方法、原脚本和源码身份即可。需要反复查看同一组签名时按需提取，不维护另一份手写方法表。
