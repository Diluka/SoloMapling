# 药剂动作模块

本文描述 #279 一期的源码契约；源码测试、隔离实际调用、部署和真人客户端验证按 Issue 分别记录。`PotionActionModule` 执行上层明确指定的一次药剂操作，确认真实角色后态，并维护该角色的分组冷却。恢复与异常解除可独立于战斗模块使用，交战和补给联合验收由 #272 消费。

## 装配与调用

模块类型为 `potions`，实现 `ActionModule`，声明自身的 `potions` 控制资源。默认 `mcp_test` 装配该模块，已有 MCP 角色继续使用原 profile、来源及保存规则；环境类型可通过同一模块定义选择装配。

```yaml
modules:
  potions:
    type: potions
    config:
      restore_cooldown_ms: 5000
      cleanse_cooldown_ms: 5000
```

两个字段分别覆盖对应组的默认值，省略的组保持5000ms。值域为0～2147483647ms，`0` 表示该组无等待；配置边界拒绝未知字段。临时类型和材料放在不提交的 `RobotPopulation.local.yaml`，见[人口配置](population.md)。

共享操作为 `potions.use`，参数为必填整数 `itemId`：

```javascript
const call = modules.call("potions.use", {itemId: 2000004});
// call.status 表达提供者可用性；call.result 表达实际药效。
call;
```

Java使用 `PotionActionModule.USE`，类型为 `ModuleFunction<PotionActionModule, PotionRequest, PotionResult>`：

```java
var call = context.call(PotionActionModule.USE, new PotionRequest(2000004), callerWork);
if (call.status() == ModuleCall.Status.SUCCEEDED) {
    PotionResult result = call.result();
    // result.outcome() 及角色前后态表达本次药效。
}
```

Java与脚本使用同一操作声明、handler及完成条件，参数说明和已适配项来自模块定义。函数未装配或提供者不可用时沿目录返回 `UNAVAILABLE`；参数结构、类型或超出 Java `int` 范围的 `itemId` 沿原脚本错误契约报告。

## 原生药效与支持项

| `itemId` | 支持项 | 冷却组 | 原生效果 |
|---|---|---|---|
| `2000004` | `ELIXIR`，特殊药水 | `restore` | 在当前值上恢复 HP/MP，基础量各为当前上限的50%；取整、上限裁剪及僵尸化修正沿原生药效 |
| `2050004` | `ALL_CURE`，万能疗伤药 | `cleanse` | 解除 `POISON`、`SEAL`、`DARKNESS`、`WEAKEN`、`SLOW`、`CURSE` |

模块的支持项枚举维护物品 ID、冷却组和说明；底层药效继续从北斗 WZ 读取。在角色唯一 lane 内调用绑定角色已有的 `getAbstractPlayerInteraction().useItem(itemId)`，由原生 `StatEffect.applyTo` 更新属性、疾病及到期记录，并发送自身和周围角色通知。

万能药可解集合由 `PotionItem.ALL_CURE.curableDiseases()` 描述，药剂效果确认和[恢复控制](recovery.md)共用这一来源。恢复控制按阈值和异常状态决定何时调用，药剂提供者继续拥有实际药效及两组冷却。

背包无需存在对应药水，这条原生入口不扣药水库存。已有背包物品使用入口继续承担实际道具消耗语义。

特殊药水任一资源实际增加即生效，另一项已满时保持当前值；两项均未增加时返回 `NO_EFFECT`。HP缺损、MP缺损或两者缺损都使用同一个恢复组状态。

万能疗伤药至少实际解除一项可解异常才生效；诱惑 `SEDUCE`、僵尸化 `ZOMBIFY`、方向反转 `CONFUSE`、眩晕 `STUN` 及普通 Buff 沿原规则保留。没有可解除异常时返回 `NO_EFFECT`。

合法整数 `itemId` 未命中支持枚举时返回 `UNSUPPORTED`，保留请求中的 `itemId`，本次操作不调用原生用药入口，也不改变角色、库存和任何冷却。角色死亡时返回 `DEAD`，复活由已有 [`native.revive`](native-actions.md) 完成。

## 两组冷却与完成语义

`restore` 和 `cleanse` 各有下一可用时间，Java和脚本共用该提供者实例的状态，不同角色分别维护。两组相互独立；一种药效生效只推进所属组，保持另一组的下一可用时间。

同一次角色 lane 短命令内完成冷却检查、应用药效、读取后态和记录对应冷却。实际生效才记入冷却；无效果、未适配项、拒绝或派发前取消保持已有冷却。冷却期间返回 `COOLDOWN` 及剩余时间。暂停和恢复保留已记入的时间，冷却按服务端时间自然推进。

| `result.outcome` | 业务结果 |
|---|---|
| `APPLIED` | 已确认实际恢复或解除异常，并记入所属组冷却 |
| `NO_EFFECT` | 原生药效未产生本操作要求的恢复或异常解除，保持已有冷却 |
| `COOLDOWN` | 所属组尚在冷却，未派发用药 |
| `UNSUPPORTED` | 请求的物品 ID 尚未适配，未派发用药 |
| `DEAD` | 绑定角色已死亡，未派发用药 |
| `CANCELLED` | 在派发药效前取消，保持已有角色事实与冷却 |

JSON结果字段如下：

| 字段 | 含义 |
|---|---|
| `itemId`、`supported_item`、`cooldown_group` | 请求的物品 ID、`ELIXIR/ALL_CURE` 和 `restore/cleanse`；未适配项后两者为 `null` |
| `effective`、`hp_restored`、`mp_restored` | 是否确认生效及实际 HP/MP恢复量 |
| `before`、`after` | `alive/hp/mp/max_hp/max_mp/diseases` 原生前后态，疾病使用名称列表 |
| `removed_diseases`、`remaining_diseases` | 实际解除及后态仍存在的负面异常；不包含 `NULL/FISHABLE` |
| `cooldowns.restore_ms`、`cooldowns.cleanse_ms` | 结果采样时两组剩余时间 |
| `dispatch_started`、`cancel_requested` | 是否进入原生用药及本次调用的取消事实 |

目录外壳 `SUCCEEDED` 表示 handler 正常返回，业务效果由这些结果事实表达；原生 `useItem` 的 `void` 返回本身不构成生效证据。

导出调用 work 已在提供者受管载体执行。原生药效短命令使用提供者原 scope 的新 work、有效 ControlPermit 和角色原有唯一 lane，并等待该工作真实结算。来源、原场景和存活前提在原生边界核验；负面状态下仍可请求相应药剂。

取消、期限、暂停、卸载和根关闭沿原有调用关系传递并等待真实工作退出。生效后取消保留药效事实和所属组冷却，模块只收尾自己的工作与资源；原生疾病到期任务保持原所有权。
