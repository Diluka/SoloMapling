package org.gms.soloacceptance;

import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;

/** MCP's compatibility view of the shared world lifecycle; this class owns no separate epoch. */
public final class AcceptanceObservationRuntime {
    private AcceptanceObservationRuntime() { }

    public static void onWorldStarted() {
        WorldEpoch.onWorldStarted();
    }

    public static void onWorldStopped() {
        ExecutionScope.tryBeginWorldStop();
    }

    public static State snapshot() {
        WorldEpoch.State state = WorldEpoch.snapshot();
        return new State(state.active(), state.token().value());
    }

    public record State(boolean active, String generation) { }
}
