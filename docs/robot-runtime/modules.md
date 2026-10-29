# 机器人模块定义与共享函数

本文面向接入模块的开发者，描述 #205 的模块契约、#270 的动作/策略入口及 #263/#122 的类型装配和导航接入。候选实现、源码测试、隔离验收和实际部署分别按 Issue 记录确认。当前源码提供 MCP 控制、导航、战斗和世界人口能力模块；具体业务类型按配置选择模块。#271 的持续交战请求、结果及首批候选范围见[战斗参考](combat.md)。

## 定义与实例配置

所有可装载模块实现 `RuntimeModule` 生命周期协议，按其对外职责选择调用入口：

| 协议 | 单次调用与职责 |
|---|---|
| [`RuntimeModule`](../../beidou-integration/src/main/java/soloMapling/game/runtime/RuntimeModule.java) | `onActivated/onStopping/onUnloaded` 由运行体管理装载、停止与卸载 |
| [`ActionModule<I, R>`](../../beidou-integration/src/main/java/soloMapling/game/runtime/ActionModule.java) | `execute(I input, ModuleContext context, WorkHandle work)` 在既定目标及约束内规划、推进、确认结果并完成本次收尾 |
| [`StrategyModule<I, R>`](../../beidou-integration/src/main/java/soloMapling/game/runtime/StrategyModule.java) | `run(I input, ModuleContext context, WorkHandle work)` 选择或切换任务目标、约束及顺序，决定发起、继续或中断任务 |

动作与策略接口都继承 `RuntimeModule`。输入 `I`、结果 `R` 保留具体业务类型；导航为 `MoveToRequest → NavigationResult`。通用 MCP 控制模块接受外部 Agent 或脚本的决策，世界人口模块投影世界服务入口，两者继续直接实现 `RuntimeModule`。

模块使用三个对象接入运行体：

| 对象 | 职责 |
|---|---|
| `ModuleDefinition<C, M extends RuntimeModule>` | 复用的类型标识、控制资源、scope 额度、工厂和导出声明 |
| `ModuleSelection<C>` | 本运行体内的 `instanceId`、模块自有配置 `config` 和启用标志 `enabled` |
| `CharacterRuntime.MountedModule<M>` | 挂载结果；`reference()` 是当前模块引用，`instance()` 是本次工厂创建的实际模块 |

`CharacterRuntime.mount(definition, selection)` 检查当前宿主状态并登记实例与导出名称，随后将模块自有配置交给工厂创建实例并绑定导出；启用实例随后激活。实例标识与共享函数名称在同一运行体中唯一。

工厂构造实例的私有结构与状态，角色绑定后的初始化沿生命周期 hook 进行。单次 `execute/run` 接受本次任务并复用这些状态，原生调用和受管资源使用提供者的 `ModuleContext`。模块只保存自己的配置及进度；角色、背包、任务等事实继续读取北斗真实来源。

## 装配方案与生命周期

机器人装配方案及配置随源码维护，通过对应测试和验收确认模块之间的组合。每个机器人按方案创建模块；正常工作周期内模块组合固定，模块的任务、状态和能力可用性随运行变化。

[`RobotPopulation.yaml`](population.md) 的 `types` 声明每个类型的模块组合及参数，加载时转换成装配计划。现有 MCP 普通专用角色固定使用 `mcp_test` 类型；后续新增非自主动作模块时更新该类型的显式配置，自主策略模块保留在其业务类型中。

策略模块负责选择角色活动目标、约束与顺序，动作模块完成已经确定的任务。动作可以持续执行、安排多个步骤，并为完成本次目标派生子目标、组合调用其他动作；这些选择属于执行规划。具体职责和调用关系由装配方案确定，`controls` 声明模块执行原生动作所需的控制资源。队伍人数、店铺访客数、牌桌席位及模块内部任务数量由各自业务规则管理。

- 工厂或首次激活失败时，运行体进入本实例的卸载收尾。生命周期 hook、scope 工作、资源和 Grant 真实结束后撤下导出登记。
- 暂停或停用保留实例、配置和导出声明；相关导出显示不可用。
- 恢复生成新的 `ModuleRef` 和 `ModuleContext` 代次，原引用失去调用资格。
- 卸载完成后撤下实例的导出登记。停止或卸载清理 hook 异常时保留 `FAILED`、失败原因和未完成的清理责任，根关闭继续等待真实收尾。

模块只清理自己拥有的工作与资源。角色最终退出、来源释放和唯一 lane 的关闭仍由 `CharacterRuntime` 管理。

可装载的父模块可以在内部组合子模块或组件，由父模块构造、初始化和管理私有结构、启用关系、局部状态与内部调用协议，并提供统一对外契约。内部工作的资源归属及最终收尾由父模块原 scope 承担；通过共享目录调用的独立动作模块仍使用各自的 scope 与资源，取消、期限及收尾沿调用关系关联。内部查询结果由父模块解释，单次任务结果由具体 `R` 表达，整体提供者可用性由共享目录表达。

## 共享函数声明与调用

`ModuleFunction<M>` 同时保存名称、用途、参数列表、结果说明和实际 handler。`describe()`、参数校验及 `bind(instance)` 使用同一声明；同一运行体中的导出名称必须唯一。

handler 接收精确提供者实例、当前提供者 `ModuleContext`、本次受管 `WorkHandle` 和脱离脚本引擎的参数对象。动作/策略的导出适配将已校验参数转换为具体 `I`，把同一 context、work 交给 `execute/run`，再把 `R` 投影成该函数约定的 JSON。Java 与脚本目录调用都消费这一执行入口。工作与资源登记在提供者 scope，实际原生执行继续使用原生上下文检查及角色唯一 lane。

受管调用将调用者 work 关联到提供者 work；调用取消及调用者 scope 停止持续传递，提供者工作仍在实际执行退出后结算。同步调用等待本次任务及收尾完成。导出 work 已在受管载体执行，`execute/run` 直接消费它一次；实际原生短命令使用提供者 scope 的新 work 提交到角色唯一 lane。动作组合调用同样沿现有目录传递调用关系。

函数目录每次调用都解析当前登记及执行代次。暂停、停用、卸载或重新挂载后，旧的调用上下文不能借同一函数名称恢复资格。

脚本可在当前北斗引擎中使用：

```javascript
modules.help();
modules.call("example.counter.next", {label: "本次调用"});
```

| 入口 | 返回或错误 |
|---|---|
| `modules.help()` | 当前已登记导出的列表，包含名称、描述、参数、结果说明、`module_id`、`module_generation`、`available` 及不可用时的 `reason` |
| `modules.call(name, args)` | 成功返回 `{status: "SUCCEEDED", result: ...}`；`args` 必须是 JSON 对象 |
| 函数缺失、提供者停用或未激活 | 正常返回 `{status: "UNAVAILABLE", function: name, reason: ...}` |
| 参数缺失、未知参数或类型不符 | 抛出参数异常，沿当前脚本的错误与输出契约返回 |

`UNAVAILABLE` 是该次模块函数调用的结果；脚本可以检查它并决定是否结束流程。调用者仍需具备当前有效的模块和脚本调用资格。
导出调用的 `SUCCEEDED` 表示 handler 正常返回；具体游戏结果由函数自己的结果契约表达。

Java `ModuleContext.help()` / `call()` 与北斗引擎内的 `modules.help()` / `modules.call(name, args)` 消费同一目录。帮助可列出已挂载但暂不可用的导出，内容取决于该类型的实际装配。当前源码的默认 `mcp_test` 导出 [`navigation.moveTo`](navigation.md)、[`combat.engage`](combat.md) 及 [`robots.create/remove`](population.md#脚本创建与移除)。MCP 通过 `script_run` 承接原生上下文中的脚本调用；北斗对象 API 的查阅沿[脚本调试技能](../../skills/solomapling-script-debug/SKILL.md)导航实际源码。

## 最小协议示例

下面只演示模块私有状态、配置和导出接线，不代表已提供游戏业务能力，也不作为生产模块自动注册：

```java
record CounterConfig(int initial) { }

final class CounterModule implements RuntimeModule {
    private final java.util.concurrent.atomic.AtomicInteger value;

    CounterModule(CounterConfig config) {
        value = new java.util.concurrent.atomic.AtomicInteger(config.initial());
    }

    int next() { return value.incrementAndGet(); }
}

ModuleFunction<CounterModule> next = new ModuleFunction<>(
        "example.counter.next", "推进本实例的私有计数",
        List.of(new ModuleFunction.Parameter(
                "label", ModuleFunction.Type.STRING, true, "本次调用的标签")),
        "返回标签和新的私有计数",
        (instance, context, work, args) -> Map.of(
                "label", args.get("label"), "value", instance.next()));

ModuleDefinition<CounterConfig, CounterModule> definition = new ModuleDefinition<>(
        "counter-example", Set.of(),
        new ExecutionScope.Limits(4, 1, 4, 1),
        CounterModule::new, List.of(next));

CharacterRuntime.MountedModule<CounterModule> mounted = runtime.mount(
        definition, new ModuleSelection<>("counter-one", new CounterConfig(0), true));
```

这里的 `runtime` 已经绑定真实角色来源。运行体中其他有效模块可以通过自己的 `ModuleContext.call` 调用导出；当前受管北斗脚本中使用相同名称调用。

## 与北斗脚本环境的关系

脚本继续通过原 `kind/operation` 选择 `npc/quest/portal/reactor/event` 上下文。`modules` 在该原生引擎中绑定，参数和结果通过 JSON 传递；它不替代原脚本文件及原生入口。

`cm/qm/pi/rm/em` 仍按各自真实对象来源和作用范围提供，`executeCommand(text)` 保留已有命令桥行为。已有北斗 Java API 直接复用；模块只显式导出自身提供的共享能力。对象来源见[北斗脚本上下文](beidou-script-environment.md)，执行、错误和取消语义见[脚本执行](script-execution.md)。
