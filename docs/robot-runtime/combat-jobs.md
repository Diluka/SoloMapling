# 简化战斗的职业能力

战斗父模块在角色绑定后的初始化阶段构造自己的职业节点树，并固定当前职业入口。选招只访问当前职业及其祖先；单次交战复用节点实例及模块状态。

技能和参数来自唯一的 `BotAttackConfig`、`BotAttackProfile`、`BotBuffConfig` 声明。节点保存本职业新增攻击和 Buff，父模块分别查询单体、群攻、大招，再按旧 AUTO 规则选择本次类别。伤害沿 `BotDamageModel`；这些能力表示 #271 一期迁移的旧简化模拟规则。

## 完整候选覆盖

首批冰雷四转链和英雄链已完成实际交战、继承与收尾验证，之后按同一模型补齐其余分支。当前源码覆盖下表34个冒险家职业节点及 BEGINNER 兜底节点；具体隔离和部署结果按 #271 交付记录分别确认。

“—”表示本节点没有新增该类别，查询继续到父节点。技能名沿北斗常量及旧表声明；本表不维护另一份技能参数。

| 职业节点 | 父节点 | 本地单体 | 本地群攻 | 本地大招 | 本地 Buff |
|---|---|---|---|---|---|
| WARRIOR 100 | — | POWER_STRIKE | SLASH_BLAST | — | IRON_BODY |
| FIGHTER 110 | WARRIOR | — | — | — | RAGE、POWER_GUARD、SWORD_BOOSTER |
| CRUSADER 111 | FIGHTER | SWORD_PANIC / AXE_PANIC | SWORD_COMA / AXE_COMA | — | COMBO |
| HERO 112 | CRUSADER | BRANDISH | — | — | MAPLE_WARRIOR、ENRAGE |
| PAGE 120 | WARRIOR | — | — | — | POWER_GUARD、SWORD_BOOSTER |
| WHITEKNIGHT 121 | PAGE | CHARGE_BLOW | — | — | SWORD_FIRE_CHARGE |
| PALADIN 122 | WHITEKNIGHT | BLAST | HEAVENS_HAMMER | — | MAPLE_WARRIOR、SWORD_HOLY_CHARGE |
| SPEARMAN 130 | WARRIOR | — | — | — | IRON_WILL、HYPER_BODY、SPEAR_BOOSTER |
| DRAGONKNIGHT 131 | SPEARMAN | SPEAR_CRUSHER / POLE_ARM_CRUSHER | SPEAR_CRUSHER / POLE_ARM_CRUSHER | DRAGON_ROAR | DRAGON_BLOOD |
| DARKKNIGHT 132 | DRAGONKNIGHT | — | — | — | MAPLE_WARRIOR、BERSERK |
| MAGICIAN 200 | — | MAGIC_CLAW | — | — | MAGIC_GUARD、MAGIC_ARMOR |
| FP_WIZARD 210 | MAGICIAN | FIRE_ARROW | — | — | MEDITATION |
| FP_MAGE 211 | FP_WIZARD | — | EXPLOSION | — | SPELL_BOOSTER |
| FP_ARCHMAGE 212 | FP_MAGE | BIG_BANG | — | METEOR_SHOWER | MAPLE_WARRIOR、INFINITY、MANA_REFLECTION |
| IL_WIZARD 220 | MAGICIAN | COLD_BEAM | THUNDERBOLT | — | MEDITATION |
| IL_MAGE 221 | IL_WIZARD | THUNDER_SPEAR | ICE_STRIKE | — | SPELL_BOOSTER |
| IL_ARCHMAGE 222 | IL_MAGE | BIG_BANG | — | BLIZZARD | MAPLE_WARRIOR、INFINITY、MANA_REFLECTION |
| CLERIC 230 | MAGICIAN | HOLY_ARROW | — | — | BLESS、INVINCIBLE |
| PRIEST 231 | CLERIC | — | SHINING_RAY | — | HOLY_SYMBOL |
| BISHOP 232 | PRIEST | ANGEL_RAY | — | GENESIS | MAPLE_WARRIOR、HOLY_SHIELD、INFINITY、MANA_REFLECTION |
| BOWMAN 300 | — | DOUBLE_SHOT | — | — | FOCUS |
| HUNTER 310 | BOWMAN | — | ARROW_BOMB | — | SOUL_ARROW、BOW_BOOSTER |
| RANGER 311 | HUNTER | STRAFE | ARROW_RAIN | — | — |
| BOWMASTER 312 | RANGER | HURRICANE | — | — | MAPLE_WARRIOR、SHARP_EYES、CONCENTRATE |
| CROSSBOWMAN 320 | BOWMAN | — | IRON_ARROW | — | SOUL_ARROW、CROSSBOW_BOOSTER |
| SNIPER 321 | CROSSBOWMAN | STRAFE | ARROW_ERUPTION | — | — |
| MARKSMAN 322 | SNIPER | SNIPE | — | — | MAPLE_WARRIOR、SHARP_EYES |
| THIEF 400 | — | LUCKY_SEVEN（爪）/ DOUBLE_STAB（其他武器） | — | — | — |
| ASSASSIN 410 | THIEF | — | — | — | HASTE、CLAW_BOOSTER |
| HERMIT 411 | ASSASSIN | — | AVENGER | — | SHADOW_PARTNER、MESO_UP |
| NIGHTLORD 412 | HERMIT | TRIPLE_THROW | — | — | MAPLE_WARRIOR、SHADOW_STARS |
| BANDIT 420 | THIEF | SAVAGE_BLOW | — | — | HASTE、DAGGER_BOOSTER |
| CHIEFBANDIT 421 | BANDIT | — | BAND_OF_THIEVES | — | MESO_GUARD |
| SHADOWER 422 | CHIEFBANDIT | ASSASSINATE | BOOMERANG_STEP | — | MAPLE_WARRIOR |

## 查询与职业特例

单体、群攻、大招各自从当前职业向祖先查询，首个提供该类别的节点结束本次查询。例如冰雷四转的单体、大招由222提供，群攻由221提供；英雄单体由112提供，群攻由111提供；黑骑士三类攻击都继承131。

Buff 从最早祖先向当前节点累计，并保持每个节点的原声明顺序。中间职业节点即使没有新增攻击，也参与继承及自己的 Buff。例如 ASSASSIN 的 HASTE、CLAW_BOOSTER 在 HERMIT/NIGHTLORD 路径中保留。

- 飞侠根节点按本次实际武器读取原表：爪使用两段 LUCKY_SEVEN，其他武器使用两段 DOUBLE_STAB；HERMIT 未声明单体，沿祖先继承这项规则。
- 战士武器变体沿 `BotAttackProfile.skillFor` 和旧 `BotAttackData.usesAltWarriorVariant`：剑/斧切换 PANIC、COMA，矛/枪切换 CRUSHER。CRUSHER 的单体与群攻预设均为最多3目标、每目标3段；这保留旧简化表的选择。
- 牧师路径在 AUTO 遇到亡灵指定目标时优先使用 CLERIC 提供的 HEAL，计划明确标记 `undeadOnly`，命中集合继续排除非亡灵。该特例被 PRIEST/BISHOP 继承；普通群攻查询仍按各节点本地声明回溯。强制 SINGLE/AOE/ULTIMATE 沿原强制类别规则。
- HERMIT 路径累计 SHADOW_PARTNER 后，派发将每目标伤害段数翻倍：LUCKY_SEVEN 2→4、AVENGER 1→2、TRIPLE_THROW 3→6。此处沿旧模拟 Buff 声明工作，不读取真实角色已登记 Buff。
- AUTO 在允许命中范围内至少有2个可命中怪物时采用可用群攻，大招沿独立25秒节奏；大招暂不可用时继续使用常规群攻。强制 ULTIMATE 使用该类别，并遵守本模块的攻击和大招节奏。

## 专属技能与通用兜底

表内职业链使用旧表的专属技能及 Buff。BEGINNER(0) 以及旧攻击/Buff 表没有配置专属技能的海盗、骑士团、战神、龙神等职业，使用通用普通攻击，计划提供者为 BEGINNER，技能0、单目标单段、近战路径，Buff列表为空。

通用兜底支持交战执行，不表示已经迁移这些职业的专属技能。实例初始化固定职业入口；成长或转职后根据新事实更新入口属于二期。

## 参数与实际效果

| 攻击预设 | 默认目标上限 | 每目标段数 | 默认范围 X/Y | 攻击间隔 |
|---|---:|---:|---:|---:|
| 单体近战 | 1 | 各技能声明 | 90/60 | 720ms |
| 普通近战群攻 | 6 | 各技能声明 | 130/80 | 780ms |
| 单体远程 | 1 | 各技能声明 | 450/150 | 780ms |
| 远程群攻 | 6 | 各技能声明 | 450/200 | 820ms |
| 单体魔法 | 1 | 各技能声明 | 450/150 | 840ms |
| 魔法群攻 | 6 | 各技能声明 | 450/210 | 900ms |
| 通用普通攻击 | 1 | 1 | 90/60 | 720ms |

表中范围为 `BotAttackProfile` 的默认值，实际命中计算继续使用旧派发规则；近战技能可从 WZ 取得具体范围。常规多段数按原表：BRANDISH/MAGIC_CLAW/DOUBLE_SHOT/LUCKY_SEVEN/DOUBLE_STAB为2段，CRUSHER/TRIPLE_THROW/ASSASSINATE为3段，STRAFE为4段，SAVAGE_BLOW为6段，BOOMERANG_STEP为2段，其余旧声明为1段。Shadow Partner 在上述基础上翻倍。技能等级沿旧模拟值20；普通攻击等级0。

伤害按职业转职阶段、角色等级、攻击段数和旧暴击规则模拟。命中后可能产生真实怪物扣血、死亡、经验和掉落，结果须按实际前后态核对。旧简化攻击沿用其消耗规则，真实职业技能资格、技能等级和资源消耗在二期升级。

机器人自身 Buff 主要展示外观，沿旧 WZ 持续时间安排再次展示；附近真人队友可能获得真实队伍 Buff 效果。实际副作用、调用结算与模块停止后的资源结束分别记录。

源码入口：

- `beidou-integration/src/main/java/soloMapling/game/combat/CombatJobTree.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackConfig.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackProfile.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffConfig.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotDamageModel.java`
