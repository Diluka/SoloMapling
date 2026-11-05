package soloMapling.game.tactics;

import soloMapling.game.navigation.NavigationPlan;
import soloMapling.game.scene.IncomingDamageEstimate;
import soloMapling.game.scene.SceneActionDispatch;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;

/** Internal pure comparisons; native observation, route planning and execution belong to the control module. */
final class CombatTacticsPlanner {
    record Candidate(Point position, NavigationPlan route, IncomingDamageEstimate risk, boolean canAttack, boolean approach) {
        int travelMs() { return route == null ? 0 : route.prediction().elapsedMs(); }
    }
    record Decision(TacticalDirective.Action action, String reason, Candidate movement,
                    SceneActionDispatch.Priority priority, IncomingDamageEstimate risk) { }

    Decision decide(int hp, int maxHp, int reservePct, boolean engaged, boolean attackReady,
                    List<Candidate> candidates) {
        var standing = candidates.getFirst();
        int reserve = (int) ((long) maxHp * reservePct / 100);
        boolean lethal = standing.risk().hpLossUpper() >= hp && standing.risk().hpLossUpper() > 0;
        boolean low = hp - standing.risk().hpLossUpper() < reserve;
        var ranked = candidates.stream().skip(1).filter(candidate -> candidate.route().prediction().arrived())
                .sorted(Comparator.comparingInt((Candidate candidate) -> candidate.risk().hpLossUpper())
                        .thenComparingInt(Candidate::travelMs)).toList();
        if (lethal || low) {
            var escape = ranked.stream().filter(candidate -> candidate.risk().hpLossUpper() < standing.risk().hpLossUpper())
                    .findFirst();
            if (escape.isEmpty()) return new Decision(TacticalDirective.Action.WAIT, "no_safe_route", null,
                    lethal ? SceneActionDispatch.Priority.LETHAL_ESCAPE : SceneActionDispatch.Priority.TACTICAL_AVOID, standing.risk());
            return new Decision(lethal && engaged ? TacticalDirective.Action.DISENGAGE : TacticalDirective.Action.EVADE,
                    lethal ? "lethal_contact_risk" : "reserve_hp_risk", escape.get(),
                    lethal ? SceneActionDispatch.Priority.LETHAL_ESCAPE : SceneActionDispatch.Priority.TACTICAL_AVOID, escape.get().risk());
        }
        if (!engaged) return new Decision(TacticalDirective.Action.WAIT, "no_engagement", null,
                SceneActionDispatch.Priority.NORMAL, standing.risk());
        if (standing.risk().coverage() != IncomingDamageEstimate.Coverage.SUPPORTED) {
            return new Decision(TacticalDirective.Action.WAIT, "risk_unknown", null, SceneActionDispatch.Priority.NORMAL, standing.risk());
        }
        if (standing.canAttack()) return new Decision(attackReady ? TacticalDirective.Action.ATTACK : TacticalDirective.Action.WAIT,
                attackReady ? "attack_budget_allowed" : "attack_not_ready", null, SceneActionDispatch.Priority.NORMAL, standing.risk());
        var approach = ranked.stream().filter(Candidate::approach)
                .filter(candidate -> candidate.risk().coverage() == IncomingDamageEstimate.Coverage.SUPPORTED
                        && hp - candidate.risk().hpLossUpper() >= reserve).findFirst();
        return approach.map(candidate -> new Decision(TacticalDirective.Action.CHASE, "target_outside_attack_geometry", candidate,
                SceneActionDispatch.Priority.NORMAL, candidate.risk())).orElseGet(() ->
                new Decision(TacticalDirective.Action.WAIT, "no_safe_approach", null, SceneActionDispatch.Priority.NORMAL, standing.risk()));
    }
}
