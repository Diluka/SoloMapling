package soloMapling.game.execution;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.BotGameContexts;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real EventBus, timer/executor and native bot objects with substituted Server/World/Channel routing.
 * These two thin observation modules do not run V2 or issue character commands; command permits have
 * separate lane tests. No socket, script, datasource or global EventBus cleanup is used here.
 */
class ScopedNativeResourcesConsumerTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_692_000_000);
    private final Object runtime = new Object();
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private Character bot;
    private MapleMap map;
    private BotGameContexts.Binding binding;
    private MockedStatic<Server> singleton;
    private MockedStatic<DatabaseConnection> database;

    @BeforeEach
    void registeredNativeBotWithoutChangingItsV1LoginFlag() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getWorld(0)).thenReturn(world);
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(world.getId()).thenReturn(0);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        worldPlayers = new PlayerStorage();
        channelPlayers = new PlayerStorage();
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        singleton = mockStatic(Server.class);
        singleton.when(Server::getInstance).thenReturn(server);
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenThrow(new AssertionError("resource fixture must not open JDBC"));
        BotClient client = new BotClient(0, 1);
        bot = Character.getDefault(client);
        bot.setId(NEXT_ID.incrementAndGet());
        bot.setName("Resources" + bot.getId());
        bot.setWorld(0);
        client.setPlayer(bot);
        map = new MapleMap(10000, 0, 1, 10000, 1f);
        members(map).add(bot); // Only fixture registration: no map-entry scripts.
        bot.setMap(map);
        bot.setMapId(map.getId());
        worldPlayers.addPlayer(bot);
        channelPlayers.addPlayer(bot);
        bot.setCanRecvPartySearchInvite(false);
        bot.setEnteredChannelWorld();
        BotRegistry.register(bot);
        binding = BotGameContexts.capture(server, bot, client, world, channel);
        assertNativeRegistration();
    }

    @AfterEach
    void removeOnlyThisFixture() {
        try {
            BotRegistry.unregister(bot);
            if (database != null) database.verifyNoInteractions();
        } finally {
            WorldEpoch.onWorldStopping();
            if (database != null) database.close();
            if (singleton != null) singleton.close();
        }
    }

    @Test
    void unloadingARealEventSubscriptionGatesBothPartsOfOldCowSnapshotsWhileBContinues() throws Exception {
        EventBus bus = isolatedRealBus();
        ExecutionScope a = scope();
        ExecutionScope b = scope();
        CountDownLatch oldSnapshotPaused = new CountDownLatch(1);
        CountDownLatch releaseOldSnapshot = new CountDownLatch(1);
        CountDownLatch filterEntered = new CountDownLatch(1);
        CountDownLatch releaseFilter = new CountDownLatch(1);
        AtomicInteger aBusinessFilters = new AtomicInteger();
        AtomicInteger aBusinessEvents = new AtomicInteger();
        AtomicInteger bBusinessEvents = new AtomicInteger();
        EventSubscriber barrier = new EventSubscriber() {
            @Override public boolean matchesFilter(GameEvent event) {
                if ("old_snapshot".equals(event.getMessage())) {
                    oldSnapshotPaused.countDown();
                    await(releaseOldSnapshot);
                }
                return false;
            }
            @Override public void onEvent(GameEvent event) { fail("coordination listener never matches"); }
        };
        ScopedSubscriber listenerA = new ScopedSubscriber(a, event -> {
            aBusinessFilters.incrementAndGet();
            if ("running_filter".equals(event.getMessage())) {
                filterEntered.countDown();
                await(releaseFilter);
            }
            return true;
        }, aBusinessEvents::incrementAndGet);
        ScopedSubscriber listenerB = new ScopedSubscriber(b, event -> true, bBusinessEvents::incrementAndGet);
        bus.subscribe(EventType.EMOTE, barrier);
        ScopeResources.Registration registrationA = subscribe(a, bus, listenerA);
        ScopeResources.Registration registrationB = subscribe(b, bus, listenerB);
        GameEvent running = event("running_filter");
        GameEvent staleSnapshot = event("old_snapshot");
        ExecutorService publishers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = publishers.submit(() -> bus.publish(running));
            assertTrue(filterEntered.await(5, TimeUnit.SECONDS));
            Future<?> second = publishers.submit(() -> bus.publish(staleSnapshot));
            assertTrue(oldSnapshotPaused.await(5, TimeUnit.SECONDS));

            a.beginDrain(); // Exact wrapper is really removed; both existing COW iterators still hold it.
            assertFalse(subscribed(bus, listenerA));
            assertTrue(subscribed(bus, listenerB));
            releaseSubscription(a, registrationA, bus, listenerA);
            assertEquals(0, a.snapshot().outstandingResources());
            assertEquals(ExecutionScope.DrainResult.TIMED_OUT, a.awaitDrained(Duration.ZERO),
                    "unsubscribe is not proof that the already entered filter exited");
            assertEquals(1, a.snapshot().runningBodies());

            releaseFilter.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionScope.DrainResult.DRAINED, a.awaitDrained(WAIT));
            assertEquals(1, listenerA.eventAttempts.get(), "the old iterator actually calls the onEvent wrapper");
            assertEquals(0, aBusinessEvents.get(), "onEvent must gate again after the filter finishes");

            releaseOldSnapshot.countDown();
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2, listenerA.filterAttempts.get(), "the second old snapshot really reaches the removed wrapper");
            assertEquals(1, aBusinessFilters.get(), "the old post-unload filter cannot enter business code");
            assertEquals(0, aBusinessEvents.get());
            assertEquals(2, bBusinessEvents.get());
            bus.publish(event("new_publication"));
            assertEquals(3, bBusinessEvents.get());
            assertEquals(2, listenerA.filterAttempts.get(), "new publication no longer contains A at all");
            assertTrue(b.isActive());
            assertFalse(registrationB.released());
            assertNativeRegistration();
        } finally {
            releaseFilter.countDown();
            releaseOldSnapshot.countDown();
            try {
                publishers.shutdown();
                assertTrue(publishers.awaitTermination(5, TimeUnit.SECONDS));
                try {
                    a.beginDrain();
                    if (!registrationA.released()) releaseSubscription(a, registrationA, bus, listenerA);
                } finally {
                    b.beginDrain();
                    releaseSubscription(b, registrationB, bus, listenerB);
                    assertEquals(ExecutionScope.DrainResult.DRAINED, b.awaitDrained(WAIT));
                }
            } finally {
                try {
                    a.beginDrain();
                    b.beginDrain();
                    bus.unsubscribe(EventType.EMOTE, listenerA);
                    bus.unsubscribe(EventType.EMOTE, listenerB);
                    bus.unsubscribe(EventType.EMOTE, barrier);
                } finally { stopExecutor(publishers); }
            }
        }
    }

    @Test
    void cancellingARealScheduledFutureDoesNotDrainItsLiveBodyOrShutdownBsTimer() throws Exception {
        ExecutionScope a = scope();
        ExecutionScope b = scope();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2,
                Thread.ofPlatform().daemon().name("scope-resource-test-", 0).factory());
        CountDownLatch enteredA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bTickedAfterUnload = new CountDownLatch(1);
        AtomicBoolean aStopped = new AtomicBoolean();
        AtomicInteger bTicks = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> installedA = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> installedB = new AtomicReference<>();
        try {
            scheduler.setRemoveOnCancelPolicy(true);
            ScopeResources.Registration timerA = a.resources().beginRegistration("timer");
            ScopeResources.Registration timerB = b.resources().beginRegistration("timer");
            ExecutionScope.WorkHandle aBody = a.acceptWork();
            assertTrue(aBody.claimCarrier());
            ScheduledFuture<?> futureA = scheduler.schedule(() -> {
                try {
                    a.runTracked(aBody, () -> {
                        BotGameContexts.requireCurrent(server, binding);
                        enteredA.countDown();
                        awaitIgnoringInterrupts(releaseA); // Deliberately prove cancellation is not body exit.
                    });
                } catch (Throwable failure) { callbackFailure.compareAndSet(null, failure); }
                finally { aBody.carrierSettled(); }
            }, 0, TimeUnit.MILLISECONDS);
            installedA.set(futureA);
            timerA.attach(futureA, () -> futureA.cancel(true));
            ScheduledFuture<?> futureB = scheduler.scheduleAtFixedRate(() -> {
                runCallback(b, () -> {
                    BotGameContexts.requireCurrent(server, binding);
                    bTicks.incrementAndGet();
                    if (aStopped.get()) bTickedAfterUnload.countDown();
                }, callbackFailure);
            }, 0, 5, TimeUnit.MILLISECONDS);
            installedB.set(futureB);
            timerB.attach(futureB, () -> futureB.cancel(true));
            try {
                assertTrue(enteredA.await(5, TimeUnit.SECONDS));
                a.beginDrain();
                assertTrue(futureA.isCancelled());
                assertTrue(futureA.isDone());
                releaseTimer(a, timerA, scheduler, futureA);
                assertTrue(timerA.released());
                assertEquals(0, a.snapshot().outstandingResources());
                assertEquals(ExecutionScope.BodyState.RUNNING, aBody.snapshot().bodyState());
                assertFalse(aBody.completion().toCompletableFuture().isDone());
                assertEquals(ExecutionScope.DrainResult.TIMED_OUT, a.awaitDrained(Duration.ZERO));

                aStopped.set(true);
                assertTrue(bTickedAfterUnload.await(5, TimeUnit.SECONDS));
                assertTrue(bTicks.get() > 0);
                assertFalse(scheduler.isShutdown(), "unloading A must not close the shared executor");
                assertFalse(futureB.isCancelled());
                assertFalse(timerB.released());
                assertEquals("shared_executor_alive", scheduler.submit(() -> "shared_executor_alive").get(5, TimeUnit.SECONDS));
                assertNativeRegistration();

                releaseA.countDown();
                aBody.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ExecutionScope.DrainResult.DRAINED, a.awaitDrained(WAIT));
                assertTrue(b.isActive());
                assertNull(callbackFailure.get());
                assertNativeRegistration();
            } finally {
                releaseA.countDown();
                try {
                    aBody.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    a.beginDrain();
                    if (!timerA.released()) releaseTimer(a, timerA, scheduler, futureA);
                } finally {
                    b.beginDrain();
                    releaseTimer(b, timerB, scheduler, futureB);
                    assertEquals(ExecutionScope.DrainResult.DRAINED, b.awaitDrained(WAIT));
                }
            }
        } finally {
            releaseA.countDown();
            try {
                if (installedA.get() != null) installedA.get().cancel(true);
                if (installedB.get() != null) installedB.get().cancel(true);
                a.beginDrain();
                b.beginDrain();
            } finally { stopExecutor(scheduler); }
        }
    }

    private final class ScopedSubscriber implements EventSubscriber {
        private final ExecutionScope scope;
        private final java.util.function.Predicate<GameEvent> filter;
        private final Runnable business;
        final AtomicInteger filterAttempts = new AtomicInteger();
        final AtomicInteger eventAttempts = new AtomicInteger();
        ScopedSubscriber(ExecutionScope scope, java.util.function.Predicate<GameEvent> filter, Runnable business) {
            this.scope = scope; this.filter = filter; this.business = business;
        }
        @Override public boolean matchesFilter(GameEvent event) {
            filterAttempts.incrementAndGet();
            if (!scope.isActive()) return false;
            AtomicBoolean matched = new AtomicBoolean();
            ExecutionScope.WorkHandle work = acceptIfActive(scope);
            if (work == null) return false;
            scope.runTracked(work, () -> {
                BotGameContexts.requireCurrent(server, binding);
                matched.set(filter.test(event));
            });
            return matched.get(); // EventBus may call onEvent after stop; that entry has its own gate.
        }
        @Override public void onEvent(GameEvent event) {
            eventAttempts.incrementAndGet();
            ExecutionScope.WorkHandle work = acceptIfActive(scope);
            if (work == null) return;
            scope.runTracked(work, () -> {
                BotGameContexts.requireCurrent(server, binding);
                business.run();
            });
        }
    }

    private ScopeResources.Registration subscribe(ExecutionScope scope, EventBus bus, EventSubscriber wrapper) {
        var registration = scope.resources().beginRegistration("event_subscription");
        bus.subscribe(EventType.EMOTE, wrapper);
        registration.attach(wrapper, () -> bus.unsubscribe(EventType.EMOTE, wrapper));
        return registration;
    }

    private static void releaseSubscription(ExecutionScope scope, ScopeResources.Registration registration,
                                            EventBus bus, EventSubscriber wrapper) throws Exception {
        var cleanup = registration.acquireCleanup();
        assertTrue(scope.runTracked(cleanup.work(), () -> {
            cleanup.permit().requireExecuting(wrapper);
            try { assertFalse(subscribed(bus, wrapper)); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            registration.confirmReleased(cleanup.permit(), wrapper);
        }));
    }

    private static void releaseTimer(ExecutionScope scope, ScopeResources.Registration registration,
                                     ScheduledThreadPoolExecutor scheduler, ScheduledFuture<?> future) {
        var cleanup = registration.acquireCleanup();
        assertTrue(scope.runTracked(cleanup.work(), () -> {
            cleanup.permit().requireExecuting(future);
            assertTrue(future.isCancelled());
            assertFalse(scheduler.getQueue().contains(future));
            registration.confirmReleased(cleanup.permit(), future);
        }));
    }

    private static ExecutionScope.WorkHandle acceptIfActive(ExecutionScope scope) {
        try { return scope.acceptWork(); }
        catch (ExecutionScope.Invalid stopped) {
            if ("scope_not_active".equals(stopped.reason())) return null;
            throw stopped;
        }
    }

    private static void runCallback(ExecutionScope scope, Runnable business, AtomicReference<Throwable> failure) {
        ExecutionScope.WorkHandle work = null;
        try {
            work = acceptIfActive(scope);
            if (work == null) return;
            assertTrue(work.claimCarrier());
            scope.runTracked(work, business);
        } catch (Throwable problem) { failure.compareAndSet(null, problem); }
        finally { if (work != null) work.carrierSettled(); }
    }

    private ExecutionScope scope() {
        return new ExecutionScope(WorldEpoch.snapshot().token(), new ExecutionScope.OwnerStamp(runtime, new Object()),
                new ExecutionScope.Limits(16, 4, 8, 1));
    }

    private GameEvent event(String message) { return new GameEvent(bot, EventType.EMOTE, message, null, true); }

    private void assertNativeRegistration() {
        assertTrue(BotRegistry.isRegistered(bot));
        assertSame(bot, worldPlayers.getCharacterById(bot.getId()));
        assertSame(bot, channelPlayers.getCharacterById(bot.getId()));
        assertSame(bot, map.getCharacterById(bot.getId()));
        assertFalse(bot.isLoggedIn());
        assertFalse(bot.isLoggedInWorld());
        assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, binding == null
                ? BotGameContexts.capture(server, bot, (BotClient) bot.getClient(), world, channel) : binding));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "resource test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "live timer body was not released");
                try { if (latch.await(remaining, TimeUnit.NANOSECONDS)) return; }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void stopExecutor(ExecutorService executor) {
        executor.shutdownNow();
        boolean interrupted = false;
        boolean terminated = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (!terminated) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { terminated = executor.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException retry) { interrupted = true; }
            }
            assertTrue(terminated, "test executor failed to exit after all fixture latches were released");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static EventBus isolatedRealBus() throws ReflectiveOperationException {
        Constructor<EventBus> constructor = EventBus.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance(); // Isolate singleton state; dispatch/unsubscribe remain the real implementation.
    }

    @SuppressWarnings("unchecked")
    private static boolean subscribed(EventBus bus, EventSubscriber wrapper) throws ReflectiveOperationException {
        Field field = EventBus.class.getDeclaredField("subscribers");
        field.setAccessible(true);
        Map<EventType, List<EventSubscriber>> listeners = (Map<EventType, List<EventSubscriber>>) field.get(bus);
        return listeners.getOrDefault(EventType.EMOTE, List.of()).stream().anyMatch(value -> value == wrapper);
    }

    @SuppressWarnings("unchecked")
    private static Collection<Character> members(MapleMap map) throws ReflectiveOperationException {
        Field field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(map);
    }
}
