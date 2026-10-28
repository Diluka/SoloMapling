package soloMapling.game.action;

/** One ordinary melee attack against an existing monster in the captured scene. */
public record NativeAttackAction(int targetOid) {
    public NativeAttackAction {
        if (targetOid < 1) throw new NativeActionService.Invalid("invalid_attack_target");
    }
}
