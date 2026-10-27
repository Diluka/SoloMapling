package soloMapling.game.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldEpochTest {
    @AfterEach
    void stopWorld() { WorldEpoch.onWorldStopping(); }

    @Test
    void everyLifecycleTransitionPublishesANewIdentityAndPreservesEarlierSnapshots() {
        WorldEpoch.onWorldStarted();
        WorldEpoch.State first = WorldEpoch.snapshot();
        assertTrue(first.active());
        assertSame(first, WorldEpoch.snapshot());
        String text = first.token().value();
        assertDoesNotThrow(() -> UUID.fromString(text));
        WorldEpoch.onWorldStopping();
        WorldEpoch.State stopped = WorldEpoch.snapshot();
        assertFalse(stopped.active());
        assertNotSame(first.token(), stopped.token());
        WorldEpoch.onWorldStarted();
        WorldEpoch.State restarted = WorldEpoch.snapshot();
        assertTrue(restarted.active());
        assertNotSame(first.token(), restarted.token());
        assertNotSame(stopped.token(), restarted.token());
        assertTrue(first.active());
        assertEquals(text, first.token().value());
        WorldEpoch.onWorldStarted();
        assertNotSame(restarted.token(), WorldEpoch.snapshot().token());
    }

    @Test
    void tokensCannotBeConstructedByAControllerOrComparedAsTextualAuthority() {
        assertEquals(0, WorldEpoch.Token.class.getConstructors().length);
        for (var constructor : WorldEpoch.Token.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        assertEquals(Object.class, assertDoesNotThrow(() ->
                WorldEpoch.Token.class.getMethod("equals", Object.class)).getDeclaringClass());
    }
}
