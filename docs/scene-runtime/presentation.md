# 场景动作与生命状态呈现

`PresentationSceneModule` 只适配场景成员需要补充的客户端动作输入。调用发生在角色已有的短命令 lane 中；模块没有独立时钟、执行队列或角色状态副本。

合法攻击由 `CharacterAttack` / `AbilitySceneModule` 发起。需要转身时，呈现模块在原生攻击之前调用 `Character.broadcastStance`，同步实际朝向。近战、魔法、Buff、受击和怪物死亡的输出继续由各自原生处理器执行一次。V2 不再通过旧 GC 运动状态或微转身录制回放改变攻击朝向。

常驻 `CharacterDamage` 在观察与实际受击之后读取原生存活状态。北斗 `Character.hpChangeAction` / `playerDead` 负责原生死亡裁决，`TakeDamageHandler` 广播伤害；这些路径没有生成客户端死亡动作输入。呈现模块因此在原生角色已经死亡、当前动作仍非死亡时，调用原生 `Character.broadcastStance` 同步 18/19。若恢复存活后仍残留死亡动作，则同步 4/5；正常移动、站立或攻击动作不会被每轮刷新覆盖。姿态编号复用现有 `CharacterStance` 和旧 `BotPhysicsEngine.resolveDeadStance`，没有另建协议。

取消、旧世界或离场后不再产生新呈现输入。移动与导航在死亡后停止发送存活动作的收尾帧。原生复活、地图进入、HP/MP、Buff 和死亡处罚继续由北斗维护。

合法武器攻击的可见性通过固定攻击者、目标和观察者的正常客户端样本核对；输入包的动作、朝向字段沿原生解析及 `PacketCreator.addAttackBody` 的顺序。协议字段和服务端发包不能单独证明客户端播放，需将逐击原生扣血、最终接收者输出与真实画面分别对齐。场景不扩大原生广播范围，也不复制旧 GC 的五秒动作保持策略。

`NativeContactDamageActionsTest` 的真实护盾与致死样本同时检查原生 HP/MP、死亡姿态、单次 `MOVE_PLAYER` 输出及恢复姿态。该证据覆盖服务端状态与构包，真人画面、客户端实际收包及部署业务回归分别保留在 #302/#304/#314/#318 的验收记录中。
