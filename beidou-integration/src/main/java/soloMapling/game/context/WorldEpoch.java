package soloMapling.game.context;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Shared world lifecycle identity, independent of any controller or acceptance configuration. */
public final class WorldEpoch {
    private static final AtomicReference<State> CURRENT = new AtomicReference<>(new State(false, new Token()));

    private WorldEpoch() { }

    public static void onWorldStarted() {
        CURRENT.set(new State(true, new Token()));
    }

    public static State snapshot() {
        return CURRENT.get();
    }

    /** Atomically reports the exact active source of a stop, without inventing an inactive source. */
    public static StopTransition stopTransition() {
        while (true) {
            State source = CURRENT.get();
            if (!source.active()) return new StopTransition(source, source, false);
            State stopped = new State(false, new Token());
            if (CURRENT.compareAndSet(source, stopped)) return new StopTransition(source, stopped, true);
        }
    }

    public record StopTransition(State source, State stopped, boolean transitioned) { }

    public record State(boolean active, Token token) { }

    /** Tokens compare by object identity; text is only a stable boundary projection. */
    public static final class Token {
        private final String value = UUID.randomUUID().toString();

        private Token() { }

        public String value() { return value; }
    }
}
