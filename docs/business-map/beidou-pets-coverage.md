# 上游原生宠物：责任与源码覆盖

本图依据北斗 1.12 固定子模块 `75a86268738ca8845e06ea9d59ec36d15b22c91b` 的源码，未执行任何游戏动作或数据库写入。下列路径均相对于 `upstream/BeiDou-Server/gms-server/src/main/java/org/gms/`。

| 责任 | 业务边界 |
| --- | --- |
| 结果与受益人 | 玩家能够孵化、召唤、收回与养育原生宠物，并使用源码支持的指令、移动、拾取过滤和补药入口。 |
| 所有状态与决策 | 宠物唯一记录、召唤、等级/亲密/饱食、地图位置与拾取排除列表归本域；宠物物品、食物和药品的持有归经济域。 |
| 生命周期 | `Pet.createPet/loadFromDb/saveToDb/deleteFromDb` 支持记录创建、读取、保存与清理；孵化/召唤进入角色宠物槽并登记饥饿，手动或饥饿收回退出在场状态；喂食及成功指令改变成长。 |
| 协作者与限制 | 原生角色承载宠物槽，地图承载位置及掉落对象；经济域决定物品/金币实际拾取与药品消耗，角色/战斗效果决定HP/MP恢复。本域不包含坐骑疲劳，不把地图机器人当原生宠物。 |

| 场景或状态责任 | 决定性源码 | 地图概念 |
| --- | --- | --- |
| 宠物记录创建、加载、保存、清理 | `client/inventory/Pet.java#createPet/loadFromDb/saveToDb/deleteFromDb` | `beidou.pets.records` |
| 蛋孵化及重复幼体拒绝、单/多宠切换、召唤、过滤加载、手动收回 | `client/processor/action/SpawnPetProcessor.java#processSpawnPet`、`client/Character.java#unEquipPet` | `beidou.pets.summon` |
| 原生500ms喂食间隔、在场宠物/真实食物核验、普通食物补饱与亲密变化 | `PetFoodHandler.handlePacket`、`Pet.gainTamenessFullness` | `beidou.pets.feed`、`feed.flow` |
| 按配置登记与调度饥饿、饱食过低收回 | `World.registerPetHunger/runPetSchedule`、`Character.runFullnessSchedule` | `beidou.pets.hunger` |
| 物种命令、命令成功概率与亲密成长 | `PetCommandHandler.handlePacket` | `beidou.pets.command` |
| 角色宠物索引、位置更新与地图广播 | `MovePetHandler.handlePacket` | `beidou.pets.movement` |
| 磁铁/物品袋、过滤与距离检查后委托原生拾取 | `PetLootHandler.handlePacket/checkPetPickupDistance` | `beidou.pets.loot` |
| 按宠物ID持久化排除列表、拒绝负数ID、差异更新和缓存提交 | `PetExcludeItemsHandler.handlePacket`、`Character.loadPetExcludedItems/updatePetExcludedItems`、`dao/entity/PetignoresDO.java` | `beidou.pets.configure-exclusions`、`exclusions` |
| 补药入口、存活和满HP/MP拒绝、真实药品槽、警戒线/配置与多槽消耗 | `PetAutoPotHandler.handlePacket`、`PetAutopotProcessor.runAutopotAction` 与内部 `AutopotAction.run` | `beidou.pets.autopot` |

规则要点：普通食物在饱食度不超过75时才加1亲密；76～99只补饱；100时再次喂食降低亲密并可能降级，食物仍被消耗。饥饿更新低至不超过5时会设置饱食度15并收回。拾取距离规则包括玩家近距、传送前位置及宠物位置尚未初始化的特殊分支，不可概括为只检查宠物与物品的固定距离。

`PetAutoPotHandler` 读取的药品槽/ID直接委托现有处理器；此次源码证据不能据此宣称所有补药请求均已验证活跃宠物或自动补药装备。各宠物入口的资格检查分别建模，不从拾取或移动的宠物归属核验推导其他入口拥有相同保证。商城购买、现金道具交付和背包中的宠物物品仍通过经济域导航；宠物聊天包属于相关展示入口，不单立功能域。源码支持与当前部署启用、真实客户端完整玩法均分别判断。
