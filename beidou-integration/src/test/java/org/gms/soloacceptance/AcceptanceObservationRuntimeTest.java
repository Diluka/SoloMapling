package org.gms.soloacceptance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.game.context.WorldEpoch;

import static org.junit.jupiter.api.Assertions.*;

class AcceptanceObservationRuntimeTest {
    @AfterEach
    void stopWorld() { WorldEpoch.onWorldStopping(); }

    @Test
    void legacyFacadeAndSharedConsumersObserveOneWorldEpoch() {
        WorldEpoch.onWorldStarted();
        WorldEpoch.State first = WorldEpoch.snapshot();
        assertTrue(AcceptanceObservationRuntime.snapshot().active());
        assertEquals(first.token().value(), AcceptanceObservationRuntime.snapshot().generation());

        AcceptanceObservationRuntime.onWorldStopped();
        assertFalse(WorldEpoch.snapshot().active());
        assertNotSame(first.token(), WorldEpoch.snapshot().token());
        assertEquals(WorldEpoch.snapshot().token().value(), AcceptanceObservationRuntime.snapshot().generation());

        WorldEpoch.Token stopped = WorldEpoch.snapshot().token();
        AcceptanceObservationRuntime.onWorldStarted();
        assertTrue(WorldEpoch.snapshot().active());
        assertNotSame(stopped, WorldEpoch.snapshot().token());
        assertEquals(WorldEpoch.snapshot().token().value(), AcceptanceObservationRuntime.snapshot().generation());
    }
}
