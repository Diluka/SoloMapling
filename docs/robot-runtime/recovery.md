# 常驻恢复控制

本文描述 #289 的源码接口，面向装配模块和编写控制逻辑的开发者。源码测试、隔离实际调用、部署与真人客户端验证分别按 Issue 记录。`RecoveryControlModule` 实现 `ControlModule`，类型为 `recovery-control`，持续观察绑定角色并决定用药时机；药效与分组冷却由独立的[药剂动作模块](potions.md)维护。

## 配置与自动运行

```yaml
modules:
  recovery:
    type: recovery-control
    config:
      hp_threshold_pct: 50
      mp_threshold_pct: 50
      cleanse_enabled: true
      check_interval_ms: 500
```

| 配置 | 默认值 | 规则 |
|---|---|---|
| `hp_threshold_pct` | `50` | 0～100；非零时，HP 未满且当前比例小于等于阈值触发恢复；0关闭HP触发 |
| `mp_threshold_pct` | `50` | 与HP规则相同，独立判断MP |
| `cleanse_enabled` | `true` | `true/false`，是否根据可解异常请求万能疗伤药 |
| `check_interval_ms` | `500` | 正整数毫秒，两轮检查之间的等待时间 |

配置在装配边界解析。HP、MP任一触发时只请求一次 `2000004`，两者同时触发也保持一次；药水仍按原生效果同时恢复HP/MP。每轮先评估恢复，再评估异常解除，两组请求分别处理。

首次激活和恢复激活都会自动开始一个监控循环。角色满血蓝、暂无异常、药剂冷却、暂时死亡及正常换图期间保持监控；角色重新存活或进入新场景后继续读取当前事实。死亡期间等待外部复活，普通复活入口见 [`native.revive`](native-actions.md)。

## 开始与停止

在已有北斗脚本上下文中调用：

```javascript
modules.call("recovery.stop", {});
modules.call("recovery.start", {});
```

Java直接调用同一实例的强类型方法：

```java
RecoveryStartResult start();
RecoveryStopResult stop();
RecoveryStatus status();
```

`START`、`STOP` 通过 `ModuleFunction` 导出，脚本与Java复用同一业务方法。目录外壳的 `SUCCEEDED` 表示方法返回，具体运行状态由业务结果表达。

| 调用 | 业务结果 |
|---|---|
| 待命时 `start()` | `STARTED`，建立本模块自有监控工作后返回 |
| 已运行时 `start()` | `ALREADY_RUNNING`，保持原监控 |
| 正在收尾时 `start()` | `BUSY`，等待本次监控真实退出 |
| `stop()` | `STOPPED`，本次监控和下游药剂实际结束后返回 |
| 已待命时 `stop()` | `ALREADY_IDLE` |

显式停止后，本轮激活期间保持 `IDLE`；显式开始或下一次重新激活会再次监控。开始函数返回及调用脚本关闭后，已启动监控继续。暂停、停用、卸载和根关闭结束监控；恢复激活重新自动开始。

脚本提供者不可用时沿共享目录返回 `UNAVAILABLE`。未激活实例的直接Java `start()` 也可返回业务 `UNAVAILABLE`。

## 药效与冷却

恢复控制只决定时机，使用 `PotionActionModule.USE` 调用原有药剂提供者：

| 条件 | 请求 |
|---|---|
| 存活且HP或MP触发阈值 | `2000004`，特殊药水 |
| 存活、已启用解除且存在可解异常 | `2050004`，万能疗伤药 |

可解集合由 `PotionItem.ALL_CURE.curableDiseases()` 与药剂动作共用，包含 `POISON/SEAL/DARKNESS/WEAKEN/SLOW/CURSE`。只有 `STUN/SEDUCE/ZOMBIFY/CONFUSE` 等不可解状态时继续监控，具体解除与保留事实沿原生药效返回。

恢复组和异常解除组各有独立下一可用时间，其他调用者与本模块消费同一份药剂冷却。`APPLIED/NO_EFFECT/COOLDOWN/DEAD` 按实际结果进入后续检查；一组冷却不阻塞另一组。监控重新开始会重置本次诊断计数，药剂冷却继续按原提供者时间推进。

药剂暂缺或停用时，监控保留 `potions_unavailable` 原因并按检查节拍继续。角色与场景资格变化先结算当前调用，下一轮重新读取当前来源。

其他执行异常结束本次监控并保留 `monitor_failed`，原异常沿受管工作报告。停止过程中已经确认的药效、计数和结束原因保留。

## 结果与诊断

`start`、`stop` 的JSON结果平铺 `outcome` 与下列状态字段；Java `status()` 返回当前或最近一次监控的 `RecoveryStatus`。

| 字段 | 含义 |
|---|---|
| `state`、`reason` | `IDLE/RUNNING/STOPPING`，以及监控、死亡、场景切换、提供者不可用或结束原因 |
| `checks` | 本次完成的原生观察轮数 |
| `restore_requests`、`cleanse_requests` | 分别递交的恢复、解除请求次数 |
| `restorations`、`cleanses` | 分别确认 `APPLIED` 的次数 |
| `observation` | 最近原生 `alive/hp/mp/max_hp/max_mp/diseases` 快照 |
| `last_restore`、`last_cleanse` | 两项最近的 `PotionResult`，具体字段见[药剂参考](potions.md) |

尚未观察或请求时，对应快照、最近结果为 `null`。最近结果中的CD为那次调用的采样值，当前用药资格仍由药剂提供者核定。

开始、停止、实际用药及提供者可用性转换记录为有界 `CONTROL_ACTIVITY` 事实。它们与原生包、外部operation及诊断计数分别核对。

## 执行归属与组合

监控通过 [`ModuleTask`](../../beidou-integration/src/main/java/soloMapling/game/runtime/ModuleTask.java) 承载本模块原scope中的独立工作。原生观察使用该scope的新短work和角色原有唯一lane；药剂操作使用药剂提供者的scope/work，取消和真实收尾沿调用关系传递。

`stop()` 等待监控及下游实际退出，已发生的药效、冷却和统计事实保留。模块只收尾自己的工作，角色与MCP会话继续由根管理。

恢复可以独立装配，也可与[狩猎控制](hunting.md)并行。狩猎决定目标与活动，恢复决定用药时机；结束狩猎后，恢复仍按自己的状态工作。长期 `mcp_test` 的装配见[人口配置](population.md)。
