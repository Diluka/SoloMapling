package soloMapling.game.combat;

import java.util.Set;

/** One fixed target and the exact set of monsters this task may hit. */
public record CombatRequest(int targetOid, Set<Integer> allowedTargetOids, Choice choice, int timeoutMs) {
    public enum Choice { AUTO, SINGLE, AOE, ULTIMATE }

    public CombatRequest {
        allowedTargetOids = Set.copyOf(allowedTargetOids);
        if (targetOid < 1 || allowedTargetOids.size() > 32 || !allowedTargetOids.contains(targetOid)
                || allowedTargetOids.stream().anyMatch(id -> id < 1) || timeoutMs < 1 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("invalid_combat_request");
        }
        java.util.Objects.requireNonNull(choice);
    }
}
