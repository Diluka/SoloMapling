package soloMapling.game.combat;

import java.util.Set;
import soloMapling.game.tactics.EngagementArea;

/** One fixed target and the exact set of monsters this task may hit. */
public record CombatRequest(int targetOid, Set<Integer> allowedTargetOids, Choice choice, int timeoutMs, EngagementArea activityArea) {
    public enum Choice { AUTO, SINGLE, AOE, ULTIMATE }

    public CombatRequest(int targetOid, Set<Integer> allowedTargetOids, Choice choice, int timeoutMs) {
        this(targetOid, allowedTargetOids, choice, timeoutMs, null);
    }

    public CombatRequest {
        allowedTargetOids = Set.copyOf(allowedTargetOids);
        if (targetOid < 1 || allowedTargetOids.size() > 32 || !allowedTargetOids.contains(targetOid)
                || allowedTargetOids.stream().anyMatch(id -> id < 1) || timeoutMs < 1 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("invalid_combat_request");
        }
        java.util.Objects.requireNonNull(choice);
    }
}
