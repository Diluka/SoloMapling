package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.server.life.Monster;

/** Short, thread-confined native damage probe; no permanent listeners or retained role objects. */
public final class NativeAttackEvidence {
    private static final ThreadLocal<Probe> CURRENT = new ThreadLocal<>();
    private NativeAttackEvidence() { }

    static Probe begin(Character actor, Monster target) {
        Probe probe = new Probe(actor, target, CURRENT.get());
        CURRENT.set(probe);
        return probe;
    }

    /** Invoked only from the woven native entry; another target, actor or thread contributes nothing. */
    public static void onDamageEntered(Monster target, Character actor) {
        Probe probe = CURRENT.get();
        if (probe != null && probe.matches(actor, target)) probe.entered++;
    }

    /** Native trueDamage has already passed the actual HP compare-and-set at this checkpoint. */
    public static void onDamageApplied(Monster target, Character actor, int trueDamage) {
        Probe probe = CURRENT.get();
        if (probe != null && probe.matches(actor, target) && trueDamage > 0) {
            probe.applied += trueDamage;
            probe.applications++;
        }
    }

    public static void onDamageReturned(Monster target, Character actor, boolean lastHit) {
        Probe probe = CURRENT.get();
        if (probe != null && probe.matches(actor, target)) {
            probe.returned++;
            probe.lastHit |= lastHit;
        }
    }

    record Fact(int entered, int returned, int applications, long appliedDamage, boolean lastHit) {
        boolean observed() { return appliedDamage > 0 || entered == 1 && returned == 1; }
    }

    static final class Probe implements AutoCloseable {
        private Character actor;
        private Monster target;
        private Probe previous;
        private int entered, returned, applications;
        private long applied;
        private boolean lastHit, closed;

        private Probe(Character actor, Monster target, Probe previous) {
            this.actor = actor; this.target = target; this.previous = previous;
        }
        private boolean matches(Character exactActor, Monster exactTarget) {
            return !closed && actor == exactActor && target == exactTarget;
        }
        Fact fact() { return new Fact(entered, returned, applications, applied, lastHit); }
        @Override public void close() {
            if (closed) return;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            actor = null; target = null; previous = null; closed = true;
        }
    }
}
