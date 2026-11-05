package soloMapling.game.tactics;

import soloMapling.game.scene.IncomingDamageEstimate;
import soloMapling.game.scene.SceneActionDispatch;

public record TacticalDirective(Action action, String reason, SceneActionDispatch.Arrangement arrangement,
                                long observedNanos, IncomingDamageEstimate risk) {
    public enum Action { ATTACK, CHASE, REPOSITION, EVADE, DISENGAGE, WAIT }
}
