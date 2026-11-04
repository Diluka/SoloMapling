# 有限怪物驱动与控制权

本分支的 `MonsterSceneModule` 使用 `journey_ground_reference_v1`：首批仅100101、120100、130101，在普通地面的当前单一 foothold 内漫游、停驻与转向。成员装载后自动运行，和狩猎、交战调用的期限分别独立；首批没有 `attackN/skillN`，身体接触由角色常驻受击入口处理。

`1210100` 的 jump 触发、追击和复杂AI，以及冰面、飞行、跨 foothold、掉落/跳跃、速度异常与Doom均未支持。没有可执行驱动的 headless 来源不会获得这些怪物的controller，真人原生路径继续处理。三个已接入种类的算法也尚缺匹配官方客户端样本，不能称为官方v83 AI等价或真人画面已通过。

## 可追溯算法

参考 [ryantpayton/MapleStory-Client](https://github.com/ryantpayton/MapleStory-Client)，提交 `cbb0fe27cf9683a12eca0a569121d8033cdc2a4d`：

- `Gameplay/MapleMap/Mob.cpp`：WZ `speed` 转为 `(speed + 100) * 0.001` 的水平力，待机/运动切换、受击力和计时。
- `Gameplay/MapleMap/Mob.h`：MOVE为2/3、STAND为4/5、HIT为8/9，最低位编码朝向。
- `Gameplay/Physics/Physics.cpp`：惯性除以3、摩擦0.5、坡度项0.1的地面积分。
- `Constants.h`：物理步长8ms。

这是自制兼容客户端，README声明兼容v83服务端，当前开发资源版本与北斗并不相同。其源代码采用AGPL-3.0-or-later，本仓库按AGPL-3.0组合，版权与来源见 `NOTICE`。这里没有将角色的125px/s速度直接套给怪物。

每96ms提交一个原角色lane短命令，内部进行12步8ms预测。预测的亚像素余量和速度只属于临时运动上下文；每次读取原生坐标，发现外部变化或控制者交接后复位预测。原生怪物仍拥有实际位置、HP、Buff、死亡及掉落。单foothold边界采用保守转向，未声称复刻参考客户端的完整地形穿行。

## 输入与控制权

`MonsterControlCoordinator` 统一候选、直接切换、仇恨切换和输入资格。正常真人优先；headless必须有当前成员、活动scope、运行中的场景驱动和对应行为支持。原锁内先验下一驱动者，再移除原controller。合法机器人攻击保留真人控制权及原生仇恨事实，撤销驱动或离场后精确解除旧句柄。

`NativeMonsterMovementActions` 在该controller的原lane内构造有限 `MoveLifeHandler` 输入。原生处理器验证controller，更新位置、返回控制响应并广播。坐标线上有2px偏移，实际结果从原生对象回读。没有任意封包/任意怪物操作的外部接口。原生已结算伤害的回调只准备后续受击动作输入，不重算伤害或广播伤害。

## 证据边界与待验

源码和原生处理器测试分别覆盖控制权保持、无驱动观察角色拒绝、句柄释放及运动更新/广播。真实隔离场景仍需核三种怪物的位置、姿态、待命持续驱动、合法武器攻击后的控制权、接触受击以及离场排空；真人/多成员混合还需独立观察。#307和#311的正常客户端样本要求继续开放，#299/#300必须按原场景独立回归。
