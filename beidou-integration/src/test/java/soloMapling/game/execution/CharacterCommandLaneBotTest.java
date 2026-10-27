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

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real V1 Character/BotClient/storage/map references with explicitly substituted host routing.
 * Two thin module scopes share one lane; no V2 module, socket, map script, timer or datasource runs.
 */
class CharacterCommandLaneBotTest {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_691_000_000);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final Object runtimeGeneration = new Object();
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private BotClient client;
    private Character bot;
    private MapleMap map;
    private MockedStatic<Server> singleton;
    private MockedStatic<DatabaseConnection> database;

    @BeforeEach
    void assembleNativeBotWithoutChangingItsV1LoginFlags() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        worldPlayers = new PlayerStorage();
        channelPlayers = new PlayerStorage();
        when(server.isOnline()).thenReturn(true);
        when(server.getWorld(0)).thenReturn(world);
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(world.getId()).thenReturn(0);
        when(world.getChannel(1)).thenReturn(channel);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getId()).thenReturn(1);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        singleton = mockStatic(Server.class);
        singleton.when(Server::getInstance).thenReturn(server);
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenAnswer(invocation -> {
            throw new AssertionError("command lane fixtures must not open JDBC");
        });

        client = new BotClient(0, 1);
        bot = Character.getDefault(client);
        bot.setId(NEXT_ID.incrementAndGet());
        bot.setName("LaneBot" + bot.getId());
        bot.setWorld(0);
        client.setPlayer(bot);
        map = new MapleMap(10000, 0, 1, 10000, 1f);
        bot.setMap(map);
        bot.setMapId(map.getId());
        members(map).add(bot); // Fixture assembly only; do not invoke map entry scripts or timers.
        worldPlayers.addPlayer(bot);
        channelPlayers.addPlayer(bot);
        bot.setCanRecvPartySearchInvite(false);
        bot.setEnteredChannelWorld();
        BotRegistry.register(bot);
        singleton.clearInvocations();
        assertNativeBotStillRegistered();
    }

    @AfterEach
    void releaseOnlyThisTestFixture() {
        BotRegistry.unregister(bot);
        WorldEpoch.onWorldStopping();
        if (database != null) {
            database.verifyNoInteractions();
            database.close();
        }
        if (singleton != null) singleton.close();
    }

    @Test
    void callerWaitsForRevokedConflictingModuleBodyAndHookBeforeAuthorizingReplacement() throws Exception {
        BotGameContexts.Binding bindingA = bind();
        BotGameContexts.Binding bindingB = bind();
        ExecutionScope moduleA = scope();
        ExecutionScope moduleB = scope();
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        AtomicInteger aNativeBodies = new AtomicInteger();
        AtomicInteger aNativeChecks = new AtomicInteger();
        AtomicInteger deniedABodies = new AtomicInteger();
        AtomicInteger bNativeBodies = new AtomicInteger();

        try (CharacterCommandLane lane = lane()) {
            CharacterCommandLane.Grant grantA = lane.declareGrant(moduleA);
            CharacterCommandLane.ControlPermit permitA = lane.bindGrantedPermit(moduleA, bindingA.characterRef(), grantA,
                    () -> { BotGameContexts.requireCurrent(server, bindingA); aNativeChecks.incrementAndGet(); });
            ExecutionScope.WorkHandle runningA = moduleA.acceptWork();
            try {
                assertEquals(CharacterCommandLane.Status.ENQUEUED, lane.submit(runningA, permitA, () -> {
                    aNativeBodies.incrementAndGet();
                    bodyEntered.countDown();
                    await(releaseBody);
                }, () -> {
                    hookEntered.countDown();
                    await(releaseHook);
                }).status());
                assertTrue(bodyEntered.await(5, TimeUnit.SECONDS), "module A must actually enter its native body");

                ExecutionScope.WorkHandle cancelledA = moduleA.acceptWork();
                assertEquals(CharacterCommandLane.Status.ENQUEUED,
                        lane.submit(cancelledA, permitA, deniedABodies::incrementAndGet, () -> { }).status());
                moduleA.requestCancel(cancelledA);
                grantA.revoke();
                // Revocation is independent of module cancellation: this new accepted carrier still cannot enter.
                ExecutionScope.WorkHandle revokedA = moduleA.acceptWork();
                assertEquals(CharacterCommandLane.Status.REJECTED,
                        lane.submit(revokedA, permitA, deniedABodies::incrementAndGet, () -> { }).status());
                assertTrue(grantA.inFlight() > 0);
                CharacterCommandLane.GrantDrain callerHandoff = grantA.awaitReleased(Duration.ZERO);
                assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT, callerHandoff);
                // The caller withholds B's conflicting writes; the lane does not choose a module winner.
                assertEquals(0, bNativeBodies.get());

                releaseBody.countDown();
                assertTrue(hookEntered.await(5, TimeUnit.SECONDS), "the body must exit before settlement starts");
                assertEquals(ExecutionScope.BodyState.EXITED, runningA.snapshot().bodyState());
                assertFalse(runningA.completion().toCompletableFuture().isDone());
                assertTrue(grantA.inFlight() > 0, "the settlement hook still owns A's grant lease");
                callerHandoff = grantA.awaitReleased(Duration.ZERO);
                assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT, callerHandoff);
                assertEquals(0, bNativeBodies.get(), "the caller still has not authorized conflicting B work");

                releaseHook.countDown();
                callerHandoff = grantA.awaitReleased(WAIT);
                assertEquals(CharacterCommandLane.GrantDrain.DRAINED, callerHandoff);
                awaitCompletion(runningA);
                awaitCompletion(cancelledA);
                awaitCompletion(revokedA);
                assertEquals(0, grantA.inFlight());
                assertEquals(1, aNativeBodies.get());
                assertEquals(1, aNativeChecks.get(), "cancelled or revoked entries must not reach native verification");
                assertEquals(0, deniedABodies.get());

                // The external conflict handoff now permits B, after A's factual release was verified.
                CharacterCommandLane.Grant grantB = lane.declareGrant(moduleB);
                CharacterCommandLane.ControlPermit permitB = lane.bindGrantedPermit(moduleB, bindingB.characterRef(), grantB,
                        () -> BotGameContexts.requireCurrent(server, bindingB));
                runAndAwait(lane, moduleB, permitB, bNativeBodies::incrementAndGet);

                moduleA.beginDrain();
                assertEquals(ExecutionScope.DrainResult.DRAINED, moduleA.awaitDrained(WAIT));
                assertEquals(ExecutionScope.State.CLOSED, moduleA.snapshot().state());
                assertNativeBotStillRegistered();
                assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, bindingB));
                // Unloading A must neither unregister the shared character nor close B's execution scope/lane.
                runAndAwait(lane, moduleB, permitB, bNativeBodies::incrementAndGet);
                assertEquals(2, bNativeBodies.get());
                assertTrue(moduleB.isActive());
                assertNativeBotStillRegistered();
            } finally {
                releaseBody.countDown();
                releaseHook.countDown();
                grantA.revoke();
                moduleA.beginDrain();
                moduleB.beginDrain();
                moduleA.awaitDrained(WAIT);
                moduleB.awaitDrained(WAIT);
            }
        }
        singleton.verifyNoInteractions();
    }

    @Test
    void nonConflictingModulesKeepIndependentGrantsWhileOneCharacterLaneSerializesTheirBodies() throws Exception {
        BotGameContexts.Binding bindingA = bind();
        BotGameContexts.Binding bindingB = bind();
        ExecutionScope moduleA = scope();
        ExecutionScope moduleB = scope();
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        AtomicInteger executing = new AtomicInteger();
        AtomicInteger maximumExecuting = new AtomicInteger();
        Collection<String> nativeOrder = new ConcurrentLinkedQueue<>();

        try (CharacterCommandLane lane = lane()) {
            CharacterCommandLane.Grant grantA = lane.declareGrant(moduleA);
            CharacterCommandLane.Grant grantB = lane.declareGrant(moduleB);
            assertNotSame(grantA, grantB);
            assertSame(grantA, lane.declareGrant(moduleA), "an unchanged live scope keeps its own grant");
            assertSame(grantB, lane.declareGrant(moduleB));
            assertFalse(grantA.revoked(), "granting a non-conflicting module must not revoke A");
            CharacterCommandLane.ControlPermit permitA = lane.bindGrantedPermit(moduleA, bindingA.characterRef(), grantA,
                    () -> BotGameContexts.requireCurrent(server, bindingA));
            CharacterCommandLane.ControlPermit permitB = lane.bindGrantedPermit(moduleB, bindingB.characterRef(), grantB,
                    () -> BotGameContexts.requireCurrent(server, bindingB));
            ExecutionScope.WorkHandle aWork = moduleA.acceptWork();
            ExecutionScope.WorkHandle bWork = moduleB.acceptWork();
            try {
                assertEquals(CharacterCommandLane.Status.ENQUEUED, lane.submit(aWork, permitA,
                        nativeBody("A", nativeOrder, executing, maximumExecuting, () -> {
                            aEntered.countDown();
                            await(releaseA);
                        }), () -> { }).status());
                assertTrue(aEntered.await(5, TimeUnit.SECONDS));
                assertEquals(CharacterCommandLane.Status.ENQUEUED, lane.submit(bWork, permitB,
                        nativeBody("B1", nativeOrder, executing, maximumExecuting, () -> {
                            bEntered.countDown();
                            await(releaseB);
                        }), () -> { }).status());
                assertEquals(1L, bEntered.getCount(), "B must remain behind the running A command");
                assertEquals(List.of("A"), List.copyOf(nativeOrder));

                releaseA.countDown();
                assertTrue(bEntered.await(5, TimeUnit.SECONDS));
                awaitCompletion(aWork);
                assertEquals(ExecutionScope.BodyState.RUNNING, bWork.snapshot().bodyState());
                assertEquals(1, executing.get());
                grantA.revoke();
                assertEquals(CharacterCommandLane.GrantDrain.DRAINED, grantA.awaitReleased(Duration.ofSeconds(1)),
                        "A release cannot wait for the non-conflicting B body");
                moduleA.beginDrain();
                assertEquals(ExecutionScope.DrainResult.DRAINED, moduleA.awaitDrained(Duration.ofSeconds(1)));
                assertEquals(ExecutionScope.State.CLOSED, moduleA.snapshot().state());
                assertEquals(1L, releaseB.getCount());
                assertEquals(ExecutionScope.BodyState.RUNNING, bWork.snapshot().bodyState());
                assertFalse(grantB.revoked());
                assertTrue(moduleB.isActive());
                assertNativeBotStillRegistered();

                releaseB.countDown();
                awaitCompletion(bWork);
                runAndAwait(lane, moduleB, permitB,
                        nativeBody("B2", nativeOrder, executing, maximumExecuting, () -> { }));
                assertEquals(List.of("A", "B1", "B2"), List.copyOf(nativeOrder));
                assertEquals(1, maximumExecuting.get(), "both grants share one FIFO, never parallel native writes");
                assertEquals(0, executing.get());
                assertNativeBotStillRegistered();
            } finally {
                releaseA.countDown();
                releaseB.countDown();
                grantA.revoke();
                grantB.revoke();
                moduleA.beginDrain();
                moduleB.beginDrain();
                moduleA.awaitDrained(WAIT);
                moduleB.awaitDrained(WAIT);
            }
        }
        singleton.verifyNoInteractions();
    }

    @Test
    void normalWorldEpochReplacementPreventsAnOldPermitEnteringAndNeedsAFreshBotBindingAndLane() throws Exception {
        BotGameContexts.Binding oldBinding = bind();
        WorldEpoch.Token oldEpoch = oldBinding.characterRef().epoch();
        ExecutionScope oldModule = scope();
        AtomicInteger oldNativeChecks = new AtomicInteger();
        AtomicInteger oldNativeBodies = new AtomicInteger();
        AtomicInteger settled = new AtomicInteger();
        try (CharacterCommandLane oldLane = lane()) {
            CharacterCommandLane.Grant oldGrant = oldLane.declareGrant(oldModule);
            CharacterCommandLane.ControlPermit oldPermit = oldLane.bindGrantedPermit(oldModule, oldBinding.characterRef(),
                    oldGrant, () -> { BotGameContexts.requireCurrent(server, oldBinding); oldNativeChecks.incrementAndGet(); });
            ExecutionScope.WorkHandle oldWork = oldModule.acceptWork();

            WorldEpoch.onWorldStopping();
            WorldEpoch.onWorldStarted();
            assertNotSame(oldEpoch, WorldEpoch.snapshot().token());
            assertSame(oldEpoch, oldPermit.characterRef().epoch());
            assertNativeBotStillRegistered();

            // Queue acceptance is not authority to execute: the carrier must recheck the actual current epoch.
            assertEquals(CharacterCommandLane.Status.ENQUEUED,
                    oldLane.submit(oldWork, oldPermit, oldNativeBodies::incrementAndGet, settled::incrementAndGet).status());
            ExecutionScope.WorkSnapshot oldResult = awaitCompletion(oldWork);
            assertTrue(oldResult.skipped());
            assertEquals(ExecutionScope.BodyState.NOT_STARTED, oldResult.bodyState());
            assertEquals(0, oldNativeChecks.get());
            assertEquals(0, oldNativeBodies.get());
            assertEquals(1, settled.get());
            oldGrant.revoke();
            assertEquals(CharacterCommandLane.GrantDrain.DRAINED, oldGrant.awaitReleased(WAIT));
            oldModule.beginDrain();
            assertEquals(ExecutionScope.DrainResult.DRAINED, oldModule.awaitDrained(WAIT));

            BotGameContexts.Binding freshBinding = bind();
            ExecutionScope freshModule = scope();
            assertNotSame(oldBinding.characterRef().epoch(), freshBinding.characterRef().epoch());
            IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                    () -> oldLane.declareGrant(freshModule));
            assertEquals("lane_epoch_mismatch", mismatch.getMessage());
            AtomicInteger freshBodies = new AtomicInteger();
            try (CharacterCommandLane freshLane = lane()) {
                try {
                    CharacterCommandLane.Grant freshGrant = freshLane.declareGrant(freshModule);
                    CharacterCommandLane.ControlPermit freshPermit = freshLane.bindGrantedPermit(freshModule,
                            freshBinding.characterRef(), freshGrant, () -> BotGameContexts.requireCurrent(server, freshBinding));
                    runAndAwait(freshLane, freshModule, freshPermit, freshBodies::incrementAndGet);
                    assertEquals(1, freshBodies.get());
                    assertNativeBotStillRegistered();
                } finally {
                    freshModule.beginDrain();
                    freshModule.awaitDrained(WAIT);
                }
            }
        } finally {
            oldModule.beginDrain();
            oldModule.awaitDrained(WAIT);
        }
        singleton.verifyNoInteractions();
    }

    private ExecutionScope scope() {
        return new ExecutionScope(WorldEpoch.snapshot().token(),
                new ExecutionScope.OwnerStamp(runtimeGeneration, new Object()), new ExecutionScope.Limits(8, 0, 8, 1));
    }

    private static CharacterCommandLane lane() {
        return new CharacterCommandLane(8, 1, Thread.ofVirtual().name("bot-command-lane-test-", 0).factory());
    }

    private BotGameContexts.Binding bind() {
        return BotGameContexts.capture(server, bot, client, world, channel);
    }

    private void assertNativeBotStillRegistered() {
        assertTrue(BotRegistry.isRegistered(bot));
        assertSame(client, bot.getClient());
        assertSame(bot, client.getPlayer());
        assertSame(bot, worldPlayers.getCharacterById(bot.getId()));
        assertSame(bot, channelPlayers.getCharacterById(bot.getId()));
        assertSame(bot, map.getCharacterById(bot.getId()));
        assertFalse(bot.isLoggedIn());
        assertFalse(bot.isLoggedInWorld());
        assertFalse(bot.isAwayFromWorld());
        assertTrue(client.isLoggedIn());
    }

    private static Runnable nativeBody(String name, Collection<String> order, AtomicInteger executing,
                                        AtomicInteger maximum, Runnable body) {
        return () -> {
            int count = executing.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            order.add(name);
            try { body.run(); }
            finally { executing.decrementAndGet(); }
        };
    }

    private static void runAndAwait(CharacterCommandLane lane, ExecutionScope scope,
                                    CharacterCommandLane.ControlPermit permit, Runnable body) throws Exception {
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertEquals(CharacterCommandLane.Status.ENQUEUED, lane.submit(work, permit, body, () -> { }).status());
        ExecutionScope.WorkSnapshot result = awaitCompletion(work);
        assertEquals(ExecutionScope.BodyState.EXITED, result.bodyState());
        assertFalse(result.skipped());
        assertTrue(result.settled());
    }

    private static ExecutionScope.WorkSnapshot awaitCompletion(ExecutionScope.WorkHandle work) throws Exception {
        return work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("bot_command_lane_latch_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Character> members(MapleMap map) throws Exception {
        Field field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(map);
    }
}
