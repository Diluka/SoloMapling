# 机器人模块定义与共享函数

本文面向接入模块的开发者，描述 #205 的当前源码契约。候选实现、源码测试、隔离验收和实际部署分别按 Issue 记录确认。当前真实接入的是 MCP 控制模块；本轮没有预装导航、战斗策略、商贸或活动业务模块及其共享函数。

## 定义与实例配置

所有模块实现同一 `RuntimeModule` 协议，使用三个对象接入运行体：

| 对象 | 职责 |
|---|---|
| `ModuleDefinition<C, M extends RuntimeModule>` | 复用的类型标识、控制资源、scope 额度、工厂和导出声明 |
| `ModuleSelection<C>` | 本运行体内的 `instanceId`、模块自有配置 `config` 和启用标志 `enabled` |
| `CharacterRuntime.MountedModule<M>` | 挂载结果；`reference()` 是当前模块引用，`instance()` 是本次工厂创建的实际模块 |

`CharacterRuntime.mount(definition, selection)` 检查当前宿主状态并登记实例与导出名称，随后将模块自有配置交给工厂创建实例并绑定导出；启用实例随后激活。实例标识与共享函数名称在同一运行体中唯一。

工厂只构造模块私有状态。实际任务、原生调用和受管资源在生命周期 hook 中通过提供的 `ModuleContext` 接入。框架保存模块自己的配置及进度；角色、背包、任务等事实继续读取北斗真实来源。

## 装配方案与生命周期

机器人装配方案及配置随源码维护，通过对应测试和验收确认模块之间的组合。每个机器人按方案创建模块；正常工作周期内模块组合固定，模块的任务、状态和能力可用性随运行变化。

主控负责选择角色活动目标与顺序；能力模块完成已确定的任务或处理已启用服务的事件。具体职责和调用关系由装配方案确定，`controls` 声明模块执行原生动作所需的控制资源。队伍人数、店铺访客数、牌桌席位及模块内部任务数量由各自业务规则管理。

- 工厂或首次激活失败时，运行体进入本实例的卸载收尾。生命周期 hook、scope 工作、资源和 Grant 真实结束后撤下导出登记。
- 暂停或停用保留实例、配置和导出声明；相关导出显示不可用。
- 恢复生成新的 `ModuleRef` 和 `ModuleContext` 代次，原引用失去调用资格。
- 卸载完成后撤下实例的导出登记。停止或卸载清理 hook 异常时保留 `FAILED`、失败原因和未完成的清理责任，根关闭继续等待真实收尾。

模块只清理自己拥有的工作与资源。角色最终退出、来源释放和唯一 lane 的关闭仍由 `CharacterRuntime` 管理。

## 共享函数声明与调用

`ModuleFunction<M>` 同时保存名称、用途、参数列表、结果说明和实际 handler。`describe()`、参数校验及 `bind(instance)` 使用同一声明；同一运行体中的导出名称必须唯一。

handler 接收精确提供者实例、当前提供者 `ModuleContext`、本次受管 `WorkHandle` 和脱离脚本引擎的参数对象。工作与资源登记在提供者 scope，实际原生执行继续使用原生上下文检查及角色唯一 lane。

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

Java `ModuleContext.help()` / `call()` 与北斗引擎内的 `modules.help()` / `modules.call(name, args)` 消费同一目录。帮助可列出已挂载但暂不可用的导出；当前没有业务导出预装时返回空列表。MCP 通过 `script_run` 承接原生上下文中的脚本调用；北斗对象 API 的查阅沿[脚本调试技能](../../skills/solomapling-script-debug/SKILL.md)导航实际源码。

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
