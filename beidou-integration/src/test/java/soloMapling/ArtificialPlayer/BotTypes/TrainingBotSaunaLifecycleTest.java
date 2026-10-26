package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import org.gms.client.Client;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotAttackSystem.ThrowingStarSelector;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotTownSystem.TownLoiter;
import soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.BotLogger;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.server.BotTickService;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.EventMessageSystem.EventBus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the real sauna chain while controlling callback delivery; no clock sleeps or server. */
class TrainingBotSaunaLifecycleTest {
    @BeforeAll
    static void nativeContext() {
        CharacterTestContext.install();
    }

    @Test
    void stoppedBotCancelsSaunaTimersAndIgnoresAlreadyDequeuedExitAndEntryCallbacks() throws Exception {
        try (Sauna fixture = new Sauna()) {
            fixture.enterSauna();
            assertEquals(2, fixture.delayed.size());
            Runnable doorDeadline = fixture.delayed.get(0);
            Runnable exit = fixture.delayed.get(1);
            Consumer<Boolean> hotelArrival = fixture.travels.getFirst();

            fixture.bot.stopScheduledTask();

            assertFalse(fixture.bot.getRunning());
            fixture.futures.forEach(future -> verify(future).cancel(false));
            fixture.movement.clearInvocations();
            clearInvocations(fixture.character);

            // Cancelling a Future cannot retract a callback already dequeued by its worker.
            exit.run();
            doorDeadline.run();
            fixture.doorArrival.run();
            hotelArrival.accept(true);

            fixture.movement.verifyNoInteractions();
            verify(fixture.character, never()).changeMap(anyInt());
        }
    }

    @Test
    void stoppingDuringTheReturnTripPreventsTheLateHotelCallbackFromStartingAnotherTravel() throws Exception {
        try (Sauna fixture = new Sauna()) {
            fixture.enterSauna();
            fixture.delayed.get(1).run();
            assertEquals(2, fixture.travels.size());
            Consumer<Boolean> returningToHotel = fixture.travels.get(1);

            fixture.bot.stopScheduledTask();
            fixture.movement.clearInvocations();
            returningToHotel.accept(true);

            fixture.movement.verifyNoInteractions();
            assertFalse(fixture.bot.getRunning());
        }
    }

    private static final class Sauna implements AutoCloseable {
        final Character character = mock(Character.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final List<Runnable> delayed = new ArrayList<>();
        final List<ScheduledFuture<?>> futures = new ArrayList<>();
        final List<Consumer<Boolean>> travels = new ArrayList<>();
        final MockedStatic<GCMovement> movement = mockStatic(GCMovement.class);
        final MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class);
        final MockedStatic<TownLoiter> loiter = mockStatic(TownLoiter.class);
        final MockedStatic<BotWanderSystem> wandering = mockStatic(BotWanderSystem.class);
        final MockedStatic<SocialCommands> social = mockStatic(SocialCommands.class);
        final MockedStatic<ThrowingStarSelector> stars = mockStatic(ThrowingStarSelector.class);
        final MockedStatic<BotLogger> logs = mockStatic(BotLogger.class);
        final MockedStatic<EventBus> events = mockStatic(EventBus.class);
        // Initialize movement/tick classes against real pools before replacing this scheduler lookup.
        final MockedStatic<ExecutorServiceManager> executors = mockStatic(ExecutorServiceManager.class);
        final TrainingBot bot;
        Runnable doorArrival;

        Sauna() {
            when(character.getId()).thenReturn(771);
            when(character.getName()).thenReturn("SaunaRegression");
            when(character.getClient()).thenReturn(mock(Client.class));
            events.when(EventBus::getInstance).thenReturn(mock(EventBus.class));
            executors.when(ExecutorServiceManager::getScheduledExecutorService).thenReturn(scheduler);
            executors.when(() -> ExecutorServiceManager.runAsync(any(Runnable.class))).thenAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            });
            when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenAnswer(invocation -> {
                        delayed.add(invocation.getArgument(0));
                        ScheduledFuture<?> future = mock(ScheduledFuture.class);
                        futures.add(future);
                        return future;
                    });
            movement.when(() -> GCMovement.travel(eq(character), anyInt(), any()))
                    .thenAnswer(invocation -> {
                        travels.add(invocation.getArgument(2));
                        return null;
                    });
            movement.when(() -> GCMovement.move(eq(character), anyInt(), anyInt(), any()))
                    .thenAnswer(invocation -> {
                        doorArrival = invocation.getArgument(3);
                        return null;
                    });
            bot = new TrainingBot(character);
            bot.setRunning(true);
        }

        void enterSauna() throws Exception {
            Method start = TrainingBot.class.getDeclaredMethod("startSaunaTrip");
            start.setAccessible(true);
            start.invoke(bot);
            assertEquals(1, travels.size());
            travels.getFirst().accept(true);
            assertNotNull(doorArrival);
            doorArrival.run();
            verify(character).changeMap(anyInt());
        }

        @Override
        public void close() {
            executors.close();
            events.close();
            logs.close();
            stars.close();
            social.close();
            wandering.close();
            loiter.close();
            ticks.close();
            movement.close();
        }
    }
}
