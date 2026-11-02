package soloMapling.game.hunting;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.McpControlModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapGrindProfile;
import soloMapling.ArtificialPlayer.BotGrindSystem.Spot;
import soloMapling.ArtificialPlayer.BotGrindSystem.SpotFinder;
import soloMapling.ArtificialPlayer.BotSpotClaims;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.combat.CombatActionModule;
import soloMapling.game.combat.CombatRequest;
import soloMapling.game.combat.CombatResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;

import java.awt.Point;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real control, placement and runtime ownership; world geometry and downstream actions are boundaries. */
class HuntingControlModuleTest {
    private static final AtomicInteger IDS = new AtomicInteger(6000);

    @BeforeEach void startWorld() { CharacterTestContext.install(); Job.values(); WorldEpoch.onWorldStarted(); }
    @AfterEach void stopWorld() { ExecutionScope.tryBeginWorldStop(); }

    @Test void activityOutlivesItsCallAndSupportsBusyStopRestartAndDuration() throws Exception {
        try (var fixture = new Fixture(false, false)) {
            assertEquals(HuntingControlModule.State.IDLE, fixture.hunting.snapshot().state());
            var call = fixture.caller.call(HuntingControlModule.START,
                    new HuntingStartOptions(HuntingStartOptions.Style.ROAM, null));
            assertEquals(ModuleCall.Status.SUCCEEDED, call.status());
            assertEquals(HuntingStartResult.Outcome.STARTED, call.result().outcome());
            var started = fixture.hunting.snapshot();
            assertEquals(HuntingControlModule.State.HUNTING, started.state());
            assertEquals("ROAM", started.style());
            assertTrue(fixture.huntingScope().acceptedWork() > 0);
            assertEquals(0, fixture.caller.scope().snapshot().acceptedWork());

            var busy = fixture.hunting.start(new HuntingStartOptions(HuntingStartOptions.Style.CAMP, 100L));
            assertEquals(HuntingStartResult.Outcome.BUSY, busy.outcome());
            assertEquals(started.activityId(), busy.activity().activityId());
            assertEquals("ROAM", busy.activity().style());
            assertNull(busy.activity().durationMs());

            var stopped = (Map<?, ?>) fixture.caller.call("hunting.stop", Map.of()).get("result");
            assertEquals("STOPPED", stopped.get("outcome"));
            assertEquals("IDLE", stopped.get("state"));
            fixture.assertHuntingDrained();
            assertEquals(HuntingStopResult.Outcome.ALREADY_IDLE, fixture.hunting.stop().outcome());

            var timed = fixture.hunting.start(new HuntingStartOptions(HuntingStartOptions.Style.ROAM, 100L));
            assertEquals(HuntingStartResult.Outcome.STARTED, timed.outcome());
            assertTrue(timed.activity().activityId() > started.activityId());
            eventually(() -> fixture.hunting.snapshot().state() == HuntingControlModule.State.IDLE);
            assertEquals("duration_elapsed", fixture.hunting.snapshot().endReason());
            fixture.assertHuntingDrained();
        }
    }

    @Test void pauseAndRootCloseReleaseTheSharedClaimAndResumeReturnsToIdle() throws Exception {
        try (var fixture = new Fixture(true, false)) {
            assertEquals(HuntingStartResult.Outcome.STARTED, fixture.hunting.start(HuntingStartOptions.DEFAULT).outcome());
            eventually(() -> BotSpotClaims.holders(fixture.mapId, 0) == 1);
            assertEquals("CAMP", fixture.hunting.snapshot().style());

            var paused = fixture.runtime.stop(fixture.huntingRef, CharacterRuntime.StopMode.PAUSE);
            assertEquals(ExecutionScope.DrainResult.DRAINED, paused.await(Duration.ofSeconds(5)));
            assertEquals(0, BotSpotClaims.holders(fixture.mapId, 0));
            fixture.assertHuntingDrained();
            fixture.huntingRef = fixture.runtime.activate(fixture.huntingRef);
            assertEquals(HuntingControlModule.State.IDLE, fixture.hunting.snapshot().state());
            assertEquals(0, BotSpotClaims.holders(fixture.mapId, 0));

            // A one-spot patrol uses the existing CAMP fallback and the same claim registry.
            var patrol = fixture.hunting.start(new HuntingStartOptions(HuntingStartOptions.Style.PATROL, null));
            assertEquals(HuntingStartResult.Outcome.STARTED, patrol.outcome());
            eventually(() -> BotSpotClaims.holders(fixture.mapId, 0) == 1);
            assertEquals("PATROL", fixture.hunting.snapshot().style());
            fixture.closeRuntime();
            assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
            assertEquals(0, BotSpotClaims.holders(fixture.mapId, 0));
        }
    }

    @Test void stopCancelsTheCurrentActionButWaitsForItsActualBodyToExit() throws Exception {
        try (var fixture = new Fixture(false, true)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var actionWork = new AtomicReference<ExecutionScope.WorkHandle>();
            var request = new AtomicReference<CombatRequest>();
            var cancelled = mock(CombatResult.class);
            when(cancelled.outcome()).thenReturn(CombatResult.Outcome.CANCELLED);
            when(cancelled.hits()).thenReturn(List.of());
            when(fixture.combat.execute(any(), any(), any())).thenAnswer(call -> {
                request.set(call.getArgument(0));
                actionWork.set(call.getArgument(2));
                entered.countDown();
                awaitEvenWhenCancelled(release);
                return cancelled;
            });
            CompletableFuture<HuntingStopResult> stopped = null;
            try {
                var result = fixture.hunting.start(new HuntingStartOptions(HuntingStartOptions.Style.ROAM, null));
                assertEquals(HuntingStartResult.Outcome.STARTED, result.outcome());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(fixture.monster.getObjectId(), request.get().targetOid());
                assertEquals(Set.of(fixture.monster.getObjectId()), request.get().allowedTargetOids());
                stopped = CompletableFuture.supplyAsync(fixture.hunting::stop);
                eventually(() -> actionWork.get().cancelRequested());
                assertFalse(stopped.isDone());
                assertEquals(HuntingControlModule.State.STOPPING, fixture.hunting.snapshot().state());
                assertTrue(fixture.huntingScope().acceptedWork() > 0);
            } finally { release.countDown(); }
            assertNotNull(stopped);
            assertEquals(HuntingStopResult.Outcome.STOPPED, stopped.get(5, TimeUnit.SECONDS).outcome());
            fixture.assertHuntingDrained();
            assertTrue(actionWork.get().isSettled());
            assertEquals(0, fixture.hunting.snapshot().kills());
        }
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    private static void awaitEvenWhenCancelled(CountDownLatch release) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("hunting_action_gate_timeout");
                    return;
                } catch (InterruptedException cancelled) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, MapGrindProfile> profiles() throws ReflectiveOperationException {
        var field = SpotFinder.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        return (Map<Integer, MapGrindProfile>) field.get(null);
    }

    private static final class Fixture implements AutoCloseable {
        final int id = IDS.incrementAndGet();
        final int mapId = 910000000 + id;
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final HuntingControlModule hunting;
        final CombatActionModule combat = mock(CombatActionModule.class);
        final Monster monster = mock(Monster.class);
        final MapGrindProfile profile;
        final MapGrindProfile previousProfile;
        CharacterRuntime.ModuleRef huntingRef;

        Fixture(boolean campable, boolean withMonster) throws Exception {
            var server = mock(Server.class);
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var storage = mock(PlayerStorage.class);
            var actor = mock(Character.class);
            var client = mock(Client.class);
            var map = mock(MapleMap.class);
            var equipped = mock(Inventory.class);
            when(server.isOnline()).thenReturn(true);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannel(1)).thenReturn(channel);
            when(channel.getId()).thenReturn(1);
            when(world.getPlayerStorage()).thenReturn(storage);
            when(channel.getPlayerStorage()).thenReturn(storage);
            when(storage.getCharacterById(id)).thenReturn(actor);
            when(actor.getId()).thenReturn(id);
            when(actor.getClient()).thenReturn(client);
            when(client.getPlayer()).thenReturn(actor);
            when(client.getChannel()).thenReturn(1);
            when(actor.getMap()).thenReturn(map);
            when(actor.getMapId()).thenReturn(mapId);
            when(map.getId()).thenReturn(mapId);
            when(map.getCharacterById(id)).thenReturn(actor);
            when(actor.getPosition()).thenAnswer(call -> new Point(0, 0));
            when(actor.getJob()).thenReturn(Job.BEGINNER);
            when(actor.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
            when(actor.isAlive()).thenReturn(true);
            when(monster.getObjectId()).thenReturn(700001);
            when(monster.isAlive()).thenReturn(true);
            when(monster.getPosition()).thenAnswer(call -> new Point(30, 0));
            when(map.getAllMonsters()).thenReturn(withMonster ? List.of(monster) : List.of());
            when(map.getMapObject(monster.getObjectId())).thenReturn(monster);
            profile = new MapGrindProfile(mapId, -250, 250, 500, campable ? 4 : 0,
                    campable ? .008 : 0, campable ? List.of(new Spot(new Point(0, 0), -1, 250, 4, 4, 500, 2))
                    : List.of(), campable ? 1 : 0, 0, MapGrindProfile.Regime.SPARSE, !campable,
                    List.of(), System.currentTimeMillis());
            previousProfile = profiles().put(mapId, profile);
            var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        try (var singleton = mockStatic(Server.class); var geometry = mockStatic(GCMovement.class)) {
                            singleton.when(Server::getInstance).thenReturn(server);
                            geometry.when(() -> GCMovement.reachableRegions(any(), anyInt(), anyInt())).thenReturn(Set.of());
                            command.run();
                        }
                    }));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), executor);
            var ref = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "hunting-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return ref; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", id); }
                @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                    permit.requireExecuting(actor); return new ReleaseProof(true);
                }
            };
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(binding); runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            caller = runtime.mount(McpControlModule.DEFINITION,
                    new ModuleSelection<>("mcp", Map.of(), true)).instance().context();
            var combatDefinition = new ModuleDefinition<CombatActionModule.Config, CombatActionModule>(
                    CombatActionModule.DEFINITION.typeId(), CombatActionModule.DEFINITION.controls(),
                    CombatActionModule.DEFINITION.limits(), config -> combat, CombatActionModule.DEFINITION.exports());
            runtime.mount(combatDefinition, new ModuleSelection<>("combat", CombatActionModule.DEFAULT_CONFIG, true));
            var navigation = mock(NavigationActionModule.class);
            var navigationDefinition = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), config -> navigation, NavigationActionModule.DEFINITION.exports());
            runtime.mount(navigationDefinition, new ModuleSelection<>("navigation", NavigationActionModule.DEFAULT_CONFIG, true));
            hunting = new HuntingControlModule(HuntingControlModule.DEFAULT_CONFIG, server);
            var definition = new ModuleDefinition<HuntingControlModule.Config, HuntingControlModule>(
                    HuntingControlModule.DEFINITION.typeId(), HuntingControlModule.DEFINITION.controls(),
                    HuntingControlModule.DEFINITION.limits(), config -> hunting, HuntingControlModule.DEFINITION.exports());
            huntingRef = runtime.mount(definition,
                    new ModuleSelection<>("hunting", HuntingControlModule.DEFAULT_CONFIG, true)).reference();
        }

        ExecutionScope.ScopeSnapshot huntingScope() {
            return runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals("hunting"))
                    .findFirst().orElseThrow().scope();
        }

        void assertHuntingDrained() {
            assertEquals(HuntingControlModule.State.IDLE, hunting.snapshot().state());
            assertEquals(0, runtime.snapshot().lane().pending());
            var scope = huntingScope();
            assertEquals(0, scope.acceptedWork());
            assertEquals(0, scope.runningBodies());
            assertEquals(0, scope.pendingCarriers());
            assertEquals(0, scope.outstandingResources());
        }

        void closeRuntime() throws Exception {
            if (runtime.snapshot().state() == CharacterRuntime.State.CLOSED) return;
            runtime.beginClose(Optional.empty());
            runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
        }

        @Override public void close() throws Exception {
            try { closeRuntime(); }
            finally {
                if (previousProfile == null) profiles().remove(mapId, profile);
                else profiles().replace(mapId, profile, previousProfile);
            }
        }
    }
}
