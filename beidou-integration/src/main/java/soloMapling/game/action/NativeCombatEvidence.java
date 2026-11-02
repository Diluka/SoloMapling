package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.client.SkillFactory;
import org.gms.client.autoban.AutobanManager;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import soloMapling.beidou.NativeCombatEffects;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** One lane-confined native cast; reuses the existing per-monster HP probe for each native call. */
public final class NativeCombatEvidence {
    private static final ThreadLocal<Cast> CURRENT = new ThreadLocal<>();
    private NativeCombatEvidence() { }

    static Cast begin(Character actor, int skillId, List<Monster> targets) {
        var cast = new Cast(actor, skillId, targets, CURRENT.get());
        CURRENT.set(cast);
        return cast;
    }

    public static void effectReturned(StatEffect effect, Character actor, boolean applied) {
        var cast = CURRENT.get();
        if (cast != null && cast.actor == actor && cast.skillId == effect.getSourceId()) {
            cast.effectObserved = true;
            cast.effectApplied = applied;
        }
    }

    /** The native base-MP warning does not account for legal Infinity/Concentrate reductions. */
    public static boolean nativeMpCostSatisfied(AutobanManager manager) {
        var cast = CURRENT.get();
        if (cast == null || cast.skillId == 0 || cast.actor.getAutoBanManager() != manager) return false;
        int level = cast.actor.getSkillLevel(cast.skillId);
        if (level == 0) return false;
        var effect = SkillFactory.getSkill(cast.skillId).getEffect(level);
        return cast.actor.getMp() + NativeCombatEffects.mpChange(effect, cast.actor) >= 0;
    }

    /** A failed native skill effect must not be followed by damage in this trusted cast. */
    public static boolean rejectDamage(Character actor, Monster target) {
        var cast = CURRENT.get();
        return cast != null && cast.actor == actor
                && (!cast.targets.containsKey(target) || cast.effectObserved && !cast.effectApplied);
    }

    public static Observation beginDamage(Character actor, Monster target) {
        var cast = CURRENT.get();
        if (cast == null || cast.actor != actor || !cast.targets.containsKey(target)) return null;
        return new Observation(cast, target, NativeAttackEvidence.begin(actor, target));
    }

    public static void endDamage(Observation observation) {
        if (observation == null) return;
        try { observation.cast.targets.put(observation.target, observation.probe.fact()); }
        finally { observation.probe.close(); }
    }

    public static final class Observation {
        private final Cast cast;
        private final Monster target;
        private final NativeAttackEvidence.Probe probe;
        private Observation(Cast cast, Monster target, NativeAttackEvidence.Probe probe) {
            this.cast = cast; this.target = target; this.probe = probe;
        }
    }

    static final class Cast implements AutoCloseable {
        final Character actor;
        final int skillId;
        final Map<Monster, NativeAttackEvidence.Fact> targets = new IdentityHashMap<>();
        final Cast previous;
        boolean effectObserved, effectApplied;
        Cast(Character actor, int skillId, List<Monster> targets, Cast previous) {
            this.actor = actor; this.skillId = skillId; this.previous = previous;
            targets.forEach(target -> this.targets.put(target, null));
        }
        boolean damageObserved() {
            return targets.values().stream().anyMatch(fact -> fact != null && fact.observed());
        }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            targets.clear();
        }
    }
}
