package soloMapling.game.tactics;

import org.gms.server.life.Monster;
import soloMapling.game.context.GameContext;

import java.util.Set;

public record EngagementCandidate(GameContext.SceneRef scene, Monster target,
                                  Set<Integer> allowedTargetOids, EngagementArea activityBounds) {
    public EngagementCandidate { allowedTargetOids = Set.copyOf(allowedTargetOids); }
}
