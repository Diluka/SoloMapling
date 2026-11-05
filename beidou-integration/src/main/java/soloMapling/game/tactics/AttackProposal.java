package soloMapling.game.tactics;

import soloMapling.game.combat.CombatJobTree;

/** The combat provider's selected skill and trusted timing; tactics never chooses another skill tree. */
public record AttackProposal(CombatJobTree.AttackPlan plan, long availableAtNanos,
                             int actionDurationMs, long observedNanos) { }
