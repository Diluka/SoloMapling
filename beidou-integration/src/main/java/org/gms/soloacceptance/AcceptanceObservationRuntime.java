package org.gms.soloacceptance;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** The native world's lifecycle, not Spring's process lifetime, owns observation references. */
public final class AcceptanceObservationRuntime {
    private static final AtomicReference<State> STATE = new AtomicReference<>(new State(false, nextGeneration()));

    private AcceptanceObservationRuntime() { }

    public static void onWorldStarted() {
        STATE.set(new State(true, nextGeneration()));
    }

    public static void onWorldStopped() {
        STATE.set(new State(false, nextGeneration()));
    }

    public static State snapshot() {
        return STATE.get();
    }

    private static String nextGeneration() {
        return UUID.randomUUID().toString();
    }

    public record State(boolean active, String generation) { }
}
