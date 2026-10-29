# 简化战斗的职业能力

战斗父模块在角色绑定后的初始化阶段构造自己的职业节点树，并固定当前职业入口。选招只访问当前职业及其祖先；单次交战不重建树，也不切换到其他职业分支。

技能和参数继续来自 `BotAttackConfig`、`BotAttackProfile`、`BotBuffConfig`。节点保存本职业新增声明，父模块分别查询单体、群攻、大招，并按旧 AUTO 规则选择本次攻击类别。伤害沿 `BotDamageModel`；这份能力表示旧简化模拟规则。

## 首批候选范围

当前首批实现冰雷四转链和英雄链。完成不同职业实例的实际交战、继承与收尾验证后，再按同一模型补齐 #271 中约定的其他分支。首批之外的职业当前只使用通用普通攻击兜底。

| 职业节点 | 父节点 | 本地单体 | 本地群攻 | 本地大招 | 本地 Buff |
|---|---|---|---|---|---|
| WARRIOR 100 | — | POWER_STRIKE | SLASH_BLAST | — | IRON_BODY |
| FIGHTER 110 | WARRIOR | — | — | — | RAGE、POWER_GUARD、SWORD_BOOSTER |
| CRUSADER 111 | FIGHTER | SWORD_PANIC / AXE_PANIC | SWORD_COMA / AXE_COMA | — | COMBO |
| HERO 112 | CRUSADER | BRANDISH | — | — | MAPLE_WARRIOR、ENRAGE |
| MAGICIAN 200 | — | MAGIC_CLAW | — | — | MAGIC_GUARD、MAGIC_ARMOR |
| IL_WIZARD 220 | MAGICIAN | COLD_BEAM | THUNDERBOLT | — | MEDITATION |
| IL_MAGE 221 | IL_WIZARD | THUNDER_SPEAR | ICE_STRIKE | — | SPELL_BOOSTER |
| IL_ARCHMAGE 222 | IL_MAGE | BIG_BANG | — | BLIZZARD | MAPLE_WARRIOR、INFINITY、MANA_REFLECTION |

“—”表示本节点没有新增该类别，查询继续到父节点。冰雷四转的单体由222提供、群攻由221提供、大招由222提供；英雄的单体由112提供、群攻由111提供。战士技能的剑/斧变体仍由实际武器决定。

Buff 从最早祖先向当前节点累计，并保留每个节点的原声明顺序。四转冰雷会累计魔法师、冰雷二转、冰雷三转及四转的 Buff；英雄路径累计战士、剑客、勇士和英雄的 Buff。

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

表中范围为 `BotAttackProfile` 的默认值，实际命中计算继续使用旧派发规则；近战技能可从 WZ 取得具体范围。职业特例的目标数、段数及独立大招间隔随各自声明说明。技能等级沿旧模拟值20；普通攻击为技能0、等级0。

伤害按职业转职阶段、角色等级、攻击段数和旧暴击规则模拟。命中后可能产生真实怪物扣血、死亡、经验和掉落，结果须按实际前后态核对。旧简化攻击沿用其消耗规则，不据此声称已经接入真实职业技能资格和消耗。

机器人自身 Buff 主要展示外观，沿旧 WZ 持续时间安排再次展示；附近真人队友可能获得真实队伍 Buff 效果。实际副作用、调用结算与模块停止后的资源结束分别记录。

源码入口：

- `beidou-integration/src/main/java/soloMapling/game/combat/CombatJobTree.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackConfig.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackProfile.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffConfig.java`
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotDamageModel.java`
