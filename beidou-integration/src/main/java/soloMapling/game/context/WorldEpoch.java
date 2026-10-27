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

    public static void onWorldStopping() {
        CURRENT.set(new State(false, new Token()));
    }

    public static State snapshot() {
        return CURRENT.get();
    }

    public record State(boolean active, Token token) { }

    /** Tokens compare by object identity; text is only a stable boundary projection. */
    public static final class Token {
        private final String value = UUID.randomUUID().toString();

        private Token() { }

        public String value() { return value; }
    }
}
