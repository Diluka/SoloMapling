package soloMapling.game.nativeaction;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.CashShop;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.McpControlModule;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.action.ReviveRequest;
import soloMapling.game.action.ReviveResult;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real click handler, native respawn method and woven event decision on one actual runtime lane. */
class NativeActionModuleTest {
    @BeforeEach void nativeContext() { CharacterTestContext.install(); Job.values(); }

    @Test void aliveRoleUsesTheSameTypedAndJsonOperationWithoutDispatchAndModulesCoexist() throws Exception {
        try (var fixture = new Fixture(50)) {
            var typed = fixture.caller.call(NativeActionModule.REVIVE, ReviveRequest.NORMAL);
            assertEquals(ModuleCall.Status.SUCCEEDED, typed.status());
            assertEquals(ReviveResult.Outcome.ALREADY_ALIVE, typed.result().outcome());
            var json = (Map<?, ?>) fixture.caller.call("native.revive", Map.of()).get("result");
            assertEquals("ALREADY_ALIVE", json.get("outcome"));
            assertEquals(false, json.get("dispatch_started"));
            verify(fixture.actor, never()).respawn(anyInt());
            assertTrue(fixture.runtime.snapshot().modules().stream()
                    .allMatch(module -> module.state() == CharacterRuntime.ModuleState.ACTIVE));
            fixture.assertDrained();
        }
    }

    @Test void normalClickRunsTheNativeRespawnAndConfirmsTheNewSceneBeforeReturning() throws Exception {
        try (var fixture = new Fixture(0)) {
            var result = fixture.revive();
            assertEquals(ReviveResult.Outcome.REVIVED, result.outcome());
            assertEquals(0, result.before().hp());
            assertEquals(50, result.after().hp());
            assertEquals(100000001, result.after().mapId());
            assertEquals(new Point(10, 20), new Point(result.after().x(), result.after().y()));
            assertTrue(result.dispatch().started());
            assertTrue(result.dispatch().returned());
            assertFalse(result.dispatch().handlerError());
            assertEquals(ReviveResult.EventRoute.NOT_PRESENT, result.event().route());
            assertTrue(result.event().standardRespawnStarted());
            assertTrue(result.event().standardRespawnReturned());
            assertTrue(result.event().respawnReturned());
            assertEquals(100000001, result.event().requestedReturnMap());
            assertTrue(result.sceneChanged());
            verify(fixture.actor).cancelAllBuffs(false);
            assertSame(fixture.previousClient, fixture.restoredClient.get());
            fixture.assertDrained();
        }
    }

    @Test void roleRevivedDuringNativeAdmissionSkipsTheClickAtTheDispatchPoint() throws Exception {
        try (var fixture = new Fixture(0)) {
            when(fixture.client.isLoggedIn()).thenAnswer(call -> { fixture.hp.set(27); return true; });
            var result = fixture.revive();
            assertEquals(ReviveResult.Outcome.ALREADY_ALIVE, result.outcome());
            assertEquals(0, result.before().hp());
            assertEquals(27, result.after().hp());
            assertFalse(result.dispatch().started());
            assertEquals(100000000, result.after().mapId());
            verify(fixture.actor, never()).respawn(anyInt());
            fixture.assertDrained();
        }
    }

    @Test void eventPermissionKeepsTheStandardNativeRoute() throws Exception {
        try (var fixture = new Fixture(0)) {
            fixture.event(true, false);
            var result = fixture.revive();
            assertEquals(ReviveResult.Outcome.REVIVED, result.outcome());
            assertEquals(ReviveResult.EventRoute.STANDARD_ALLOWED, result.event().route());
            assertTrue(result.event().consulted());
            assertTrue(result.event().decisionReturned());
            assertTrue(result.event().allowsStandardPath());
            assertTrue(result.event().standardRespawnReturned());
            fixture.assertDrained();
        }
    }

    @Test void eventTakeoverCanReviveWithoutRunningTheHandlersStandardRespawn() throws Exception {
        try (var fixture = new Fixture(0)) {
            fixture.event(false, true);
            var result = fixture.revive();
            assertEquals(ReviveResult.Outcome.REVIVED, result.outcome());
            assertEquals(33, result.after().hp());
            assertEquals(100000000, result.after().mapId());
            assertEquals(ReviveResult.EventRoute.TAKEN_OVER, result.event().route());
            assertFalse(result.event().allowsStandardPath());
            assertFalse(result.event().standardRespawnStarted());
            verify(fixture.actor, never()).respawn(anyInt());
            fixture.assertDrained();
        }
    }

    @Test void originalRockSpiritCallbackCanTakeOverWithItsNativeMapRespawnSignature() throws Exception {
        try (var fixture = new Fixture(0);
             var engine = GraalJSScriptEngine.create(null, Context.newBuilder("js").allowHostAccess(HostAccess.ALL))) {
            engine.eval(Files.readString(Path.of("upstream/BeiDou-Server/gms-server/scripts/event/RockSpirit.js"),
                    StandardCharsets.UTF_8));
            engine.put("exitMap", fixture.returnMap);
            var event = fixture.event(false, false);
            doAnswer(call -> {
                assertSame(event, call.getArgument(1));
                assertSame(fixture.actor, call.getArgument(2));
                return engine.invokeFunction("playerRevive", event, fixture.actor);
            }).when(event).invokeScriptFunction(eq("playerRevive"), any(), any());
            doAnswer(call -> { fixture.currentEvent.set(null); return null; })
                    .when(event).unregisterPlayer(fixture.actor);

            var result = fixture.revive();

            assertEquals(ReviveResult.Outcome.REVIVED, result.outcome());
            assertEquals(ReviveResult.EventRoute.TAKEN_OVER, result.event().route());
            assertEquals(50, result.after().hp());
            assertEquals(fixture.returnMap.getId(), result.after().mapId());
            assertTrue(result.event().respawnReturned());
            assertFalse(result.event().standardRespawnStarted());
            verify(event).unregisterPlayer(fixture.actor);
            verify(fixture.actor).respawn(event, fixture.returnMap.getId());
            verify(fixture.actor).cancelAllBuffs(false);
            fixture.assertDrained();
        }
    }

    @Test void eventTakeoverWithoutAReviveReportsTheCurrentDeadStateAndKeepsEventOwnership() throws Exception {
        try (var fixture = new Fixture(0)) {
            var event = fixture.event(false, false);
            var result = fixture.revive();
            assertEquals(ReviveResult.Outcome.NOT_REVIVED, result.outcome());
            assertEquals(ReviveResult.EventRoute.TAKEN_OVER, result.event().route());
            assertFalse(result.after().alive());
            assertEquals(0, result.after().hp());
            assertSame(event, fixture.actor.getEventInstance());
            assertFalse(event.isEventDisposed());
            assertEquals("OWNER_MANAGED_UNOBSERVED", result.toMap().get("event_work_completion"));
            fixture.assertDrained();
        }
    }

    @Test void queuedCallCancellationPreventsTheNativeClickButWaitsForItsCarrier() throws Exception {
        try (var fixture = new Fixture(0)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocker = fixture.caller.acceptWork();
            fixture.caller.submit("interaction", blocker, () -> {}, () -> {
                entered.countDown();
                await(release);
            }, () -> {});
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var parent = fixture.caller.acceptWork();
            var result = new AtomicReference<ReviveResult>();
            var call = CompletableFuture.runAsync(() -> fixture.caller.scope().runTracked(parent,
                    () -> result.set(fixture.caller.call(NativeActionModule.REVIVE, ReviveRequest.NORMAL, parent).result())));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (fixture.runtime.snapshot().lane().pending() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(2, fixture.runtime.snapshot().lane().pending());
                fixture.caller.cancel(parent);
                assertFalse(call.isDone());
            } finally { release.countDown(); }
            call.get(3, TimeUnit.SECONDS);
            assertEquals(ReviveResult.Outcome.CANCELLED, result.get().outcome());
            assertFalse(result.get().dispatch().started());
            verify(fixture.actor, never()).respawn(anyInt());
            assertEquals(0, fixture.hp.get());
            fixture.assertDrained();
        }
    }

    @Test void cancellationAndPauseAfterTheEffectKeepReviveFactsAndWaitForNativeExit() throws Exception {
        try (var fixture = new Fixture(0)) {
            var reached = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.onHpUpdate = () -> { reached.countDown(); await(release); };
            var parent = fixture.caller.acceptWork();
            var result = new AtomicReference<ReviveResult>();
            var call = CompletableFuture.runAsync(() -> fixture.caller.scope().runTracked(parent,
                    () -> result.set(fixture.caller.call(NativeActionModule.REVIVE, ReviveRequest.NORMAL, parent).result())));
            CharacterRuntime.ModuleStop stop;
            try {
                assertTrue(reached.await(3, TimeUnit.SECONDS));
                fixture.caller.cancel(parent);
                stop = fixture.runtime.stop(fixture.nativeRef, CharacterRuntime.StopMode.PAUSE);
                assertFalse(stop.complete());
                assertFalse(call.isDone());
            } finally { release.countDown(); }
            call.get(3, TimeUnit.SECONDS);
            assertEquals(ReviveResult.Outcome.REVIVED, result.get().outcome());
            assertTrue(result.get().cancelRequested());
            assertEquals(50, result.get().after().hp());
            assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(Duration.ofSeconds(3)));
            assertEquals(ModuleCall.Status.UNAVAILABLE,
                    fixture.caller.call(NativeActionModule.REVIVE, ReviveRequest.NORMAL).status());
            fixture.assertDrained();
        }
    }

    @Test void sourceEpochLossAfterTheNativeEffectKeepsTheEffectAndReportsUnconfirmedPostState() throws Exception {
        try (var fixture = new Fixture(0)) {
            fixture.onHpUpdate = () -> fixture.stopTicket = ExecutionScope.tryBeginWorldStop();
            var result = fixture.revive();
            assertEquals(ReviveResult.Outcome.UNKNOWN, result.outcome());
            assertTrue(result.dispatch().started());
            assertTrue(result.event().respawnReturned());
            assertNull(result.after());
            assertEquals(50, fixture.hp.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("native_test_gate_timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class Fixture implements AutoCloseable {
        final Character actor = mock(Character.class, invocation -> invocation.getMethod().getName().equals("respawn")
                ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
        final Client client = mock(Client.class);
        final Client previousClient = mock(Client.class);
        final MapleMap initialMap = mock(MapleMap.class);
        final MapleMap returnMap = mock(MapleMap.class);
        final AtomicInteger hp;
        final AtomicReference<Client> restoredClient = new AtomicReference<>();
        final AtomicReference<MapleMap> currentMap = new AtomicReference<>(initialMap);
        final AtomicReference<EventInstanceManager> currentEvent = new AtomicReference<>();
        final AtomicReference<Point> position = new AtomicReference<>(new Point(0, 0));
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final CharacterRuntime.ModuleRef nativeRef;
        Optional<ExecutionScope.WorldStopTicket> stopTicket = Optional.empty();
        Runnable onHpUpdate = () -> {};

        Fixture(int initialHp) {
            WorldEpoch.onWorldStarted();
            hp = new AtomicInteger(initialHp);
            var server = mock(Server.class);
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var storage = mock(PlayerStorage.class);
            when(server.isOnline()).thenReturn(true);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannel(1)).thenReturn(channel);
            when(channel.getId()).thenReturn(1);
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
            when(initialMap.getCharacterById(41)).thenReturn(actor);
            when(returnMap.getCharacterById(41)).thenReturn(actor);
            when(initialMap.getId()).thenReturn(100000000);
            when(initialMap.getReturnMapId()).thenReturn(100000001);
            when(returnMap.getId()).thenReturn(100000001);
            when(actor.getPosition()).thenAnswer(call -> new Point(position.get()));
            when(actor.getHp()).thenAnswer(call -> hp.get());
            when(actor.getMp()).thenReturn(10);
            when(actor.isAlive()).thenAnswer(call -> hp.get() > 0);
            var cashShop = mock(CashShop.class);
            when(actor.getCashShop()).thenReturn(cashShop);
            when(actor.getEventInstance()).thenAnswer(call -> currentEvent.get());
            doAnswer(call -> { currentMap.set(returnMap); position.set(new Point(10, 20)); return null; })
                    .when(actor).changeMap(anyInt());
            doAnswer(call -> {
                assertSame(client, ThreadLocalUtil.getCurrentClient());
                hp.set(call.getArgument(0)); onHpUpdate.run(); return null;
            }).when(actor).updateHp(anyInt());
            doCallRealMethod().when(actor).respawn(anyInt());
            doCallRealMethod().when(actor).respawn(nullable(EventInstanceManager.class), anyInt());
            var lane = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        ThreadLocalUtil.setCurrentClient(previousClient);
                        try { command.run(); } finally { ThreadLocalUtil.removeCurrentClient(); }
                    }));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), lane);
            var ref = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "native-action-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return ref; }
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
            var navigation = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), config -> new NavigationActionModule(config, server),
                    NavigationActionModule.DEFINITION.exports());
            runtime.mount(navigation, new ModuleSelection<>("navigation", NavigationActionModule.DEFAULT_CONFIG, true));
            var nativeActions = new ModuleDefinition<NativeActionModule.Config, NativeActionModule>(
                    NativeActionModule.DEFINITION.typeId(), NativeActionModule.DEFINITION.controls(),
                    NativeActionModule.DEFINITION.limits(), config -> new NativeActionModule(server),
                    NativeActionModule.DEFINITION.exports());
            nativeRef = runtime.mount(nativeActions, new ModuleSelection<>("native", NativeActionModule.DEFAULT_CONFIG, true)).reference();
        }

        EventInstanceManager event(boolean allowsStandard, boolean reviveInCallback) throws Exception {
            var event = mock(EventInstanceManager.class, CALLS_REAL_METHODS);
            doReturn(actor).when(event).getPlayerById(41);
            doReturn(false).when(event).isEventDisposed();
            doAnswer(call -> {
                assertSame(event, call.getArgument(1));
                assertSame(actor, call.getArgument(2));
                if (reviveInCallback) actor.updateHp(33);
                return allowsStandard;
            }).when(event).invokeScriptFunction(eq("playerRevive"), any(), any());
            currentEvent.set(event);
            return event;
        }

        ReviveResult revive() {
            var result = caller.call(NativeActionModule.REVIVE, ReviveRequest.NORMAL);
            assertEquals(ModuleCall.Status.SUCCEEDED, result.status());
            // This short lane command runs after the revive carrier and observes its restored ThreadLocal.
            if (WorldEpoch.snapshot().active()) {
                var read = caller.acceptWork();
                caller.submit("interaction", read, () -> {},
                        () -> restoredClient.set(ThreadLocalUtil.getCurrentClient()), () -> {});
                read.completion().toCompletableFuture().join();
            }
            return result.result();
        }

        void assertDrained() {
            assertEquals(0, runtime.snapshot().lane().pending());
            var nativeActions = runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals("native"))
                    .findFirst().orElseThrow();
            if (nativeActions.scope() != null) {
                assertEquals(0, nativeActions.scope().acceptedWork());
                assertEquals(0, nativeActions.scope().runningBodies());
                assertEquals(0, nativeActions.scope().outstandingResources());
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
