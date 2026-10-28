package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import soloMapling.game.action.NativeAttackEvidence;

/** Observe actual native HP accounting without registering a long-lived monster listener. */
public aspect NativeAttackEvidenceAspect {
    before(Monster target, Character actor):
            execution(boolean Monster.damage(Character, int, boolean)) && this(target) && args(actor, *, *) {
        NativeAttackEvidence.onDamageEntered(target, actor);
    }

    before(Monster target, Character actor, int trueDamage):
            execution(void Monster.dispatchMonsterDamaged(Character, int)) && this(target) && args(actor, trueDamage) {
        NativeAttackEvidence.onDamageApplied(target, actor, trueDamage);
    }

    after(Monster target, Character actor) returning(boolean lastHit):
            execution(boolean Monster.damage(Character, int, boolean)) && this(target) && args(actor, *, *) {
        NativeAttackEvidence.onDamageReturned(target, actor, lastHit);
    }
}
