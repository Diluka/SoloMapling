package soloMapling.game.action;

/** Holding one horizontal direction for a bounded period; no destination or raw movement input. */
public record NativeMovementAction(String direction, int durationMs) {
    public NativeMovementAction {
        if (!"LEFT".equals(direction) && !"RIGHT".equals(direction)) {
            throw new NativeActionService.Invalid("invalid_movement_direction");
        }
        if (durationMs < 100 || durationMs > 3000) throw new NativeActionService.Invalid("invalid_movement_duration");
    }
}
