package soloMapling.game.nativeaction;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.CashShop;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapPortal;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.soloacceptance.actor.McpControlModule;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.action.PortalRequest;
import soloMapling.game.action.PortalResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real click/GenericPortal/ACK handlers; native map publication is an explicit fixture boundary. */
class NativePortalModuleTest {
    @BeforeEach void nativeContext() { CharacterTestContext.install(); Job.values(); }

    @Test void typedAndScriptEntriesUseTheActualPortalAndContinueFromTheNewScene() throws Exception {
        try (var fixture = new Fixture()) {
            var oldScene = NativeContextChecks.captureScene(fixture.server, fixture.actorRef);
            var outbound = fixture.enter(17);
            assertEquals(PortalResult.Outcome.ENTERED, outbound.outcome());
            assertEquals(104040000, outbound.before().mapId());
            assertEquals(100000000, outbound.after().mapId());
            assertEquals(20, outbound.entry().portalId());
            assertEquals("west00", outbound.entry().portalName());
            assertEquals(-80, outbound.after().x());
            assertTrue(outbound.dispatch().started());
            assertTrue(outbound.transition().acknowledgementStarted());
            assertTrue(outbound.transition().acknowledgementReturned());
            assertTrue(outbound.transition().complete());
            assertFalse(outbound.after().changingMaps());
            assertEquals(GameContext.Failure.SCENE_CHANGED,
                    assertThrows(GameContext.Invalid.class,
                            () -> NativeContextChecks.requireSceneCurrent(fixture.server, oldScene)).reason());

            var json = (Map<?, ?>) fixture.caller.call("native.enterPortal", Map.of("portalId", 20)).get("result");
            assertEquals("ENTERED", json.get("outcome"));
            assertEquals(104040000, ((Map<?, ?>) json.get("after")).get("map_id"));
            assertEquals(true, json.get("transition_complete"));
            verify(fixture.actor, times(2)).changeMap(any(MapleMap.class), any(Portal.class));
            verify(fixture.actor, times(2)).setMapTransitionComplete();
            fixture.assertDrained();
        }
    }

    @Test void nativeAdmissionAndUnsupportedDefinitionsKeepEntryFactsAccurate() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.outbound.setPortalStatus(false);
            var closed = fixture.enter(17);
            assertEquals(PortalResult.Outcome.NOT_ENTERED, closed.outcome());
            assertTrue(closed.dispatch().started());
            assertFalse(closed.transition().observed());

            fixture.outbound.setPortalStatus(true);
            fixture.position.set(new Point(2_000, 0));
            var far = fixture.enter(17);
            assertEquals(PortalResult.Outcome.NOT_ENTERED, far.outcome());
            assertFalse(far.transition().acknowledgementStarted());

            fixture.position.set(new Point(100, 0));
            fixture.outbound.setScriptName("unrelated_script");
            var scripted = fixture.enter(17);
            assertEquals(PortalResult.Outcome.UNSUPPORTED, scripted.outcome());
            assertFalse(scripted.dispatch().started());
            fixture.outbound.setScriptName(null);
            fixture.outbound.setTarget("missing_entry");
            var absentEntry = fixture.enter(17);
            assertEquals(PortalResult.Outcome.UNSUPPORTED, absentEntry.outcome());
            assertEquals("portal_target_entry_unavailable", absentEntry.reason());
            assertFalse(absentEntry.dispatch().started());
            verify(fixture.actor, never()).changeMap(any(MapleMap.class), any(Portal.class));
            verify(fixture.actor, never()).setMapTransitionComplete();
            fixture.assertDrained();
        }
    }

    @Test void queuedCancellationWaitsForTheCarrierAndDoesNotEnterThePortal() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocker = fixture.caller.acceptWork();
            fixture.caller.submit("interaction", blocker, () -> {}, () -> {
                entered.countDown(); await(release);
            }, () -> {});
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var parent = fixture.caller.acceptWork();
            var result = new AtomicReference<PortalResult>();
            var call = CompletableFuture.runAsync(() -> fixture.caller.scope().runTracked(parent,
                    () -> result.set(fixture.caller.call(NativeActionModule.ENTER_PORTAL, new PortalRequest(17), parent).result())));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (fixture.runtime.snapshot().lane().pending() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(2, fixture.runtime.snapshot().lane().pending());
                fixture.caller.cancel(parent);
                assertFalse(call.isDone());
            } finally { release.countDown(); }
            call.get(3, TimeUnit.SECONDS);
            assertEquals(PortalResult.Outcome.CANCELLED, result.get().outcome());
            assertFalse(result.get().dispatch().started());
            verify(fixture.actor, never()).changeMap(any(MapleMap.class), any(Portal.class));
            fixture.assertDrained();
        }
    }

    @Test void cancellationAndPauseAfterEntryFinishItsNativeAcknowledgementAndKeepTheEffect() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.onWarp = () -> { entered.countDown(); await(release); };
            var parent = fixture.caller.acceptWork();
            var result = new AtomicReference<PortalResult>();
            var call = CompletableFuture.runAsync(() -> fixture.caller.scope().runTracked(parent,
                    () -> result.set(fixture.caller.call(NativeActionModule.ENTER_PORTAL, new PortalRequest(17), parent).result())));
            CharacterRuntime.ModuleStop stop;
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                fixture.caller.cancel(parent);
                stop = fixture.runtime.stop(fixture.nativeRef, CharacterRuntime.StopMode.PAUSE);
                assertFalse(stop.complete());
                assertFalse(call.isDone());
            } finally { release.countDown(); }
            call.get(3, TimeUnit.SECONDS);
            assertEquals(PortalResult.Outcome.ENTERED, result.get().outcome());
            assertTrue(result.get().cancelRequested());
            assertTrue(result.get().transition().complete());
            assertSame(fixture.town, fixture.currentMap.get());
            assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(Duration.ofSeconds(3)));
            assertEquals(ModuleCall.Status.UNAVAILABLE,
                    fixture.caller.call(NativeActionModule.ENTER_PORTAL, new PortalRequest(20)).status());
            fixture.assertDrained();
        }
    }

    @Test void sourceEpochLossAfterEntryKeepsThePhysicalTransferWithoutInventingAcknowledgement() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.onWarp = () -> fixture.stopTicket = ExecutionScope.tryBeginWorldStop();
            var result = fixture.enter(17);
            assertEquals(PortalResult.Outcome.UNKNOWN, result.outcome());
            assertTrue(result.dispatch().started());
            assertNull(result.after());
            assertNull(result.sceneChanged());
            assertFalse(result.transition().acknowledgementStarted());
            assertSame(fixture.town, fixture.currentMap.get());
            assertTrue(fixture.changingMaps.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("portal_test_gate_timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class Fixture implements AutoCloseable {
        final Server server = mock(Server.class);
        final Character actor = mock(Character.class);
        final Client client = mock(Client.class);
        final Client previousClient = mock(Client.class);
        final MapleMap training = mock(MapleMap.class);
        final MapleMap town = mock(MapleMap.class);
        final MapPortal outbound = new MapPortal();
        final MapPortal inbound = new MapPortal();
        final AtomicReference<MapleMap> currentMap = new AtomicReference<>(training);
        final AtomicReference<Point> position = new AtomicReference<>(new Point(100, 0));
        final AtomicBoolean changingMaps = new AtomicBoolean();
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final CharacterRuntime.ModuleRef nativeRef;
        final GameContext.CharacterRef actorRef;
        Optional<ExecutionScope.WorldStopTicket> stopTicket = Optional.empty();
        Runnable onWarp = () -> {};

        Fixture() {
            WorldEpoch.onWorldStarted();
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var storage = mock(PlayerStorage.class);
            var maps = mock(MapManager.class);
            when(server.isOnline()).thenReturn(true);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannel(1)).thenReturn(channel);
            when(channel.getId()).thenReturn(1);
            when(channel.getMapFactory()).thenReturn(maps);
            when(client.getChannelServer()).thenReturn(channel);
            when(world.getPlayerStorage()).thenReturn(storage);
            when(channel.getPlayerStorage()).thenReturn(storage);
            when(storage.getCharacterById(41)).thenReturn(actor);
            when(actor.getId()).thenReturn(41);
            when(actor.getClient()).thenReturn(client);
            when(client.getPlayer()).thenReturn(actor);
            when(client.getChannel()).thenReturn(1);
            when(client.isLoggedIn()).thenReturn(true);
            when(actor.getMap()).thenAnswer(call -> currentMap.get());
            when(actor.getMapId()).thenAnswer(call -> currentMap.get().getId());
            when(actor.getPosition()).thenAnswer(call -> new Point(position.get()));
            when(actor.isChangingMaps()).thenAnswer(call -> changingMaps.get());
            when(actor.isAlive()).thenReturn(true);
            when(actor.getBuffSource(BuffStat.HOMING_BEACON)).thenReturn(-1);
            var cashShop = mock(CashShop.class);
            when(actor.getCashShop()).thenReturn(cashShop);
            when(training.getId()).thenReturn(104040000);
            when(town.getId()).thenReturn(100000000);
            when(training.getCharacterById(41)).thenReturn(actor);
            when(town.getCharacterById(41)).thenReturn(actor);
            when(training.getMonsters()).thenReturn(List.of());
            when(town.getMonsters()).thenReturn(List.of());
            when(maps.getMap(104040000)).thenReturn(training);
            when(maps.getMap(100000000)).thenReturn(town);
            outbound.setId(17); outbound.setName("east00"); outbound.setPosition(new Point(100, 0));
            outbound.setTargetMapId(100000000); outbound.setTarget("west00"); outbound.setPortalStatus(true);
            inbound.setId(20); inbound.setName("west00"); inbound.setPosition(new Point(-80, 0));
            inbound.setTargetMapId(104040000); inbound.setTarget("east00"); inbound.setPortalStatus(true);
            when(training.getPortal(17)).thenReturn(outbound);
            when(training.getPortal("east00")).thenReturn(outbound);
            when(town.getPortal(20)).thenReturn(inbound);
            when(town.getPortal("west00")).thenReturn(inbound);
            doAnswer(call -> {
                assertSame(client, ThreadLocalUtil.getCurrentClient());
                currentMap.set(call.getArgument(0));
                position.set(new Point(((Portal) call.getArgument(1)).getPosition()));
                changingMaps.set(true); onWarp.run(); return null;
            }).when(actor).changeMap(any(MapleMap.class), any(Portal.class));
            doAnswer(call -> {
                assertSame(client, ThreadLocalUtil.getCurrentClient());
                changingMaps.set(false); return null;
            }).when(actor).setMapTransitionComplete();
            var lane = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        ThreadLocalUtil.setCurrentClient(previousClient);
                        try { command.run(); } finally { ThreadLocalUtil.removeCurrentClient(); }
                    }));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), lane);
            actorRef = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "portal-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return actorRef; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", 41); }
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
            var nativeActions = new ModuleDefinition<NativeActionModule.Config, NativeActionModule>(
                    NativeActionModule.DEFINITION.typeId(), NativeActionModule.DEFINITION.controls(),
                    NativeActionModule.DEFINITION.limits(), config -> new NativeActionModule(server),
                    NativeActionModule.DEFINITION.exports());
            nativeRef = runtime.mount(nativeActions,
                    new ModuleSelection<>("native", NativeActionModule.DEFAULT_CONFIG, true)).reference();
        }

        PortalResult enter(int portalId) {
            var reply = caller.call(NativeActionModule.ENTER_PORTAL, new PortalRequest(portalId));
            assertEquals(ModuleCall.Status.SUCCEEDED, reply.status());
            return reply.result();
        }

        void assertDrained() {
            assertEquals(0, runtime.snapshot().lane().pending());
            var nativeModule = runtime.snapshot().modules().stream()
                    .filter(module -> module.instanceId().equals("native")).findFirst().orElseThrow();
            if (nativeModule.scope() != null) {
                assertEquals(0, nativeModule.scope().acceptedWork());
                assertEquals(0, nativeModule.scope().runningBodies());
                assertEquals(0, nativeModule.scope().outstandingResources());
            }
        }

        @Override public void close() throws Exception {
            runtime.beginClose(stopTicket);
            runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
            ExecutionScope.tryBeginWorldStop();
        }
    }
}
