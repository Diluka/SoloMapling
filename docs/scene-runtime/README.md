# 场景客户端行为

`CharacterRuntime` 保持每个角色唯一的来源、模块作用域、执行 lane 与最终保存退出。`SceneRuntimeManager` 按世界代次及实际 `MapleMap` 对象维护共享 `SceneRuntime`，同 ID 的频道地图或副本分别管理。首位需要模拟的角色进入时建立宿主，最后成员和离场工作真实结束后关闭。

原生 MCP 与 V2 环境角色的装配在来源绑定后调用 `CharacterRuntime.initializeSceneMembership(Server)`，接入相同的私有 `scene-client` 生命周期模块；场景后台短命令使用该模块作用域及角色原有 lane。正常换图只替换本次入场绑定，接触受击冷却和有界结果保留在角色级。场景组件读取原生 HP、位置、装备、怪物和地物，不维护第二份游戏档案。

| 模块 | 当前源码职责 |
| --- | --- |
| `EnvironmentSceneModule` | 复用原生地形和对象视图，供导航、观察和场景驱动读取。 |
| `CharacterSceneModule` | 每次入场的运动、攻击入口与常驻接触受击；导航保留目标和路线编排。 |
| `MonsterSceneModule` | 有效控制者协调、四种怪物的连续地面活动、原生仇恨追击及1210100跳跃。 |
| `AbilitySceneModule` | 已适配技能资格、有限命中参数与共同原生执行；职业选招仍归业务模块。 |
| `PresentationSceneModule` | 合法攻击朝向及真实死亡、恢复姿态，复用原生输出。 |

导航、基本方向动作和攻击从当前绑定进入。离场停止新输入，订阅原提供者工作项的真实完成，并等待后台任务退出；场景不重新登记角色工作、不建立动作队列。真实死亡且仍在原图时继续保留成员；复活目的地及日程恢复选择由原生规则和业务模块处理。

怪物驱动停止清理时，先等待该绑定已登记的原入场、导航、方向与攻击动作work及carrier退出，再使用原lane的清理名额释放原生controller；该等待不包含驱动任务自身或常驻业务控制。世界停止取消或授权撤销可能先于绑定离场标记，因此清理次序不依赖该标记。运动收尾和controller释放按原工作完成信号衔接，避免争用同一清理名额，未释放仍保留失败事实。

角色场景组件为每次实际入场先向原 `scene-client` scope 和角色 lane 提交一次短命令，复用 `PlayerMapTransitionHandler` 完成客户端入场确认。普通MCP登录及原生换图消费相同路径；命令执行时重验精确绑定、世界代次、取消及原生 `isChangingMaps()`，已经由门户或复活同步确认的入场直接跳过。入场确认的真实work退出也属于离场等待范围，场景不另存ready状态。

正常入场ACK完成后，角色仍在本次原生入场点且下方存在真实地面时，场景自动启动一次零水平DROP；完全待命时也会落地。任务、运动会话与短帧分别沿原 `scene-client` scope、共享 `CharacterMotion` 和角色唯一lane执行。紧接登录的普通导航等待这次入场任务真实退出，等待计入原调用期限；取消导航等待不取消场景入场任务。已有运动占用时不另行重试入场资格。MCP有界 `move` 保留原24px契约，也未开放任意待命坠落。具体条件见[导航支持范围](../robot-runtime/navigation.md)，实际地图按[入场落地冒烟](entry-landing-smoke.md)采样。

`SceneRuntimeManager.snapshots()` 和现有角色/场景观察提供安全摘要，用于核对宿主、成员及收尾。包消息从原 `BotClient`、`AcceptanceClient` 路由，原包观察保持原归属。

`scene_snapshot`、`scene_record` 提供无需角色会话的只读场景数据帧和事件记录，可按固定实际地图保存JSON或NDJSON证据。采集只读取已经存在的模拟宿主；字段来源、记录限制与真实停止语义见[场景采集格式](../mcp-acceptance/scene-capture.md)。

详见[客户端行为矩阵](client-behavior-matrix.md)、[怪物资料调查](monster-data-report.md)、[有限怪物驱动](monster-driver.md)和[呈现边界](presentation.md)。环境触发、召唤物和宠物驱动分别由 #315、#316、#317 继续覆盖。

本目录描述源码支持范围，实际交付以 #305 及各子项的冻结提交和验收证据为准。真实图形客户端使用部署工作区的 `client/maple` 操作，短暂动作结合原生场景记录与带时间戳的真实画面核对。源码、自动化、隔离交互、实际部署和画面播放分别记录，不能相互代替。
