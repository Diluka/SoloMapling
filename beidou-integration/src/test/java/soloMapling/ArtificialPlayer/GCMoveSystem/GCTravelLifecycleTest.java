package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GCTravelLifecycleTest {
    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void detachedHeadlessCharacterCannotStartANewTrip() {
        Character character = headless(781);
        try (MockedStatic<BotRegistry> registry = mockStatic(BotRegistry.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            GCTravel.travel(character, 10, ignored -> fail("Detached trips must not re-arm old callbacks"));
            assertFalse(GCTravel.isTraveling(character));
            movement.verify(() -> GCMovement.enable(character), never());
            verify(character, never()).changeMap(anyInt());
            verify(character, never()).setLoggedIn(anyBoolean());
        }
    }

    @Test
    void detachedPollCancelsItsFutureAndRemovesTravelAndMovementState() throws Exception {
        Character character = headless(782);
        Class<?> tripType = Class.forName(GCTravel.class.getName() + "$Trip");
        Constructor<?> constructor = tripType.getDeclaredConstructor(Character.class, int.class, Consumer.class);
        constructor.setAccessible(true);
        Object trip = constructor.newInstance(character, 10, null);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        Field taskField = tripType.getDeclaredField("task");
        taskField.setAccessible(true);
        taskField.set(trip, task);
        Field tripsField = GCTravel.class.getDeclaredField("TRIPS");
        tripsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> trips = (Map<Integer, Object>) tripsField.get(null);
        trips.put(character.getId(), trip);
        Method poll = GCTravel.class.getDeclaredMethod("poll", tripType);
        poll.setAccessible(true);
        try (MockedStatic<BotRegistry> registry = mockStatic(BotRegistry.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            poll.invoke(null, trip);
            poll.invoke(null, trip);
            assertFalse(GCTravel.isTraveling(character));
            verify(task, times(1)).cancel(false);
            movement.verify(() -> GCMovement.disable(character), times(1));
            verify(character, never()).changeMap(anyInt());
            verify(character, never()).setLoggedIn(anyBoolean());
        } finally {
            trips.remove(character.getId(), trip);
        }
    }

    @Test
    void realGmDiagnosticCharacterKeepsItsExistingTravelContract() {
        Character character = mock(Character.class);
        when(character.getClient()).thenReturn(mock(Client.class));
        when(character.getMap()).thenReturn(mock(MapleMap.class));
        when(character.getMapId()).thenReturn(10);
        AtomicBoolean called = new AtomicBoolean();
        try (MockedStatic<BotRegistry> registry = mockStatic(BotRegistry.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            GCTravel.travel(character, 10, result -> called.set(result));
            assertTrue(called.get());
            registry.verifyNoInteractions();
        }
    }

    private static Character headless(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getClient()).thenReturn(mock(BotClient.class));
        return character;
    }
}
