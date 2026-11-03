package soloMapling.game.travel;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.provider.Data;
import org.gms.provider.DataDirectoryEntry;
import org.gms.provider.DataProvider;
import org.gms.provider.LocalizedDataProvider;
import org.gms.provider.wz.XMLWZFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;
import soloMapling.server.MapleVersionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real native WZ parsing and runtime calls; only the world identity and small metadata sets are isolated. */
class TravelPlanModuleTest {
    @TempDir Path temporary;
    private int originalVersion;

    @BeforeEach void activeWorldVersion() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        originalVersion = MapleVersionManager.version;
        MapleVersionManager.version = 55;
    }

    @AfterEach void stoppedWorld() {
        MapleVersionManager.version = originalVersion;
        ExecutionScope.tryBeginWorldStop();
    }

    @Test void fixedTrainingLoopUsesTheRealSixWzPortalsWithoutNavigationOrCharacterAccess() throws Exception {
        var source = new XMLWZFile(Path.of("upstream/BeiDou-Server/gms-server/wz/Map.wz"));
        try (var fixture = new Fixture(source, TravelPlanModule.DEFAULT_CONFIG)) {
            var outward = fixture.plan(104040000, 100000102);
            assertEquals(TravelPlanResult.Outcome.FOUND, outward.outcome());
            assertEquals(List.of(
                    new TravelPlanResult.Leg(104040000, 17, "east00", 100000000, "west00"),
                    new TravelPlanResult.Leg(100000000, 23, "in00", 100000100, "out00"),
                    new TravelPlanResult.Leg(100000100, 17, "in01", 100000102, "out01")), outward.route());
            var returning = fixture.plan(100000102, 104040000);
            assertEquals(List.of(
                    new TravelPlanResult.Leg(100000102, 2, "out01", 100000100, "in01"),
                    new TravelPlanResult.Leg(100000100, 14, "out00", 100000000, "in00"),
                    new TravelPlanResult.Leg(100000000, 20, "west00", 104040000, "east00")), returning.route());
            assertEquals(outward.toMap(), fixture.caller.call("travel.plan", Map.of(
                    "fromMapId", 104040000, "toMapId", 100000102)).get("result"));
            assertEquals(TravelPlanResult.Outcome.FOUND, fixture.plan(100000102, 100000102).outcome());
            assertTrue(fixture.plan(100000102, 100000102).route().isEmpty());
            verifyNoInteractions(fixture.actor, fixture.client, fixture.world, fixture.channel);
            assertEquals(Set.of("travel-test-caller", "travel"), fixture.runtime.snapshot().modules().stream()
                    .map(module -> module.typeId()).collect(java.util.stream.Collectors.toSet()));
            fixture.assertDrained();
        }
    }

    @Test void supportedShortestRouteRetainsPortalIdentityAndClassifiesUnavailableConnections() throws Exception {
        Path root = temporary.resolve("base");
        writeMap(root, 1, "", portal(7, "parallel", 2, "alternate", 2, "")
                + portal(5, "toTwo", 2, "fromOne", 2, "")
                + portal(3, "toThree", 3, "fromOne", 2, "")
                + portal(1, "scriptShortcut", 4, "fromTwo", 2, "transport"));
        writeMap(root, 2, "", portal(0, "fromOne", 999999999, "", 0, "")
                + portal(1, "alternate", 999999999, "", 0, "") + portal(8, "toFour", 4, "fromTwo", 2, ""));
        writeMap(root, 3, "", portal(0, "fromOne", 999999999, "", 0, "")
                + portal(9, "toFour", 4, "fromThree", 2, ""));
        writeMap(root, 4, "", portal(0, "fromTwo", 999999999, "", 0, "")
                + portal(1, "fromThree", 999999999, "", 0, ""));
        writeMap(root, 5, "", "");
        writeMap(root, 6, "", portal(3, "scripted", 7, "entry", 2, "transport"));
        writeMap(root, 7, "", portal(0, "entry", 999999999, "", 0, ""));
        writeMap(root, 8, "", portal(1, "unresolvedEntry", 9, "missing", 2, ""));
        writeMap(root, 9, "", portal(0, "entry", 999999999, "", 0, ""));
        writeMap(root, 120000000, "", "");
        try (var fixture = new Fixture(new XMLWZFile(root), TravelPlanModule.DEFAULT_CONFIG)) {
            var route = fixture.plan(1, 4);
            assertEquals(TravelPlanResult.Outcome.FOUND, route.outcome());
            assertEquals(List.of(new TravelPlanResult.Leg(1, 5, "toTwo", 2, "fromOne"),
                    new TravelPlanResult.Leg(2, 8, "toFour", 4, "fromTwo")), route.route());
            assertEquals(route, fixture.plan(1, 4));
            assertEquals(5, fixture.plan(1, 2).route().getFirst().exitPortalId());
            assertEquals(TravelPlanResult.Outcome.NO_ROUTE, fixture.plan(4, 1).outcome());
            assertEquals(TravelPlanResult.Outcome.NO_ROUTE, fixture.plan(1, 5).outcome());
            assertEquals(TravelPlanResult.Outcome.UNSUPPORTED, fixture.plan(6, 7).outcome());
            assertEquals(TravelPlanResult.Outcome.UNSUPPORTED, fixture.plan(8, 9).outcome());
            assertEquals(TravelPlanResult.Outcome.UNSUPPORTED, fixture.plan(1, 12345).outcome());
            assertEquals(TravelPlanResult.Outcome.UNSUPPORTED, fixture.plan(1, 120000000).outcome());
            fixture.assertDrained();
        }
    }

    @Test void localizedPortalDataAndOneLevelLinkUseTheCompleteFallbackDirectory() throws Exception {
        Path root = temporary.resolve("base"), translated = temporary.resolve("translated");
        writeMap(root, 1, "", portal(2, "original", 2, "arrival", 2, ""));
        writeMap(root, 2, "", portal(0, "arrival", 999999999, "", 0, ""));
        writeMap(root, 11, "<string name=\"link\" value=\"1\"/>", "");
        writeMap(translated, 1, "", portal(4, "localized", 2, "arrival", 2, ""));
        try (var fixture = new Fixture(new LocalizedDataProvider(new XMLWZFile(translated), new XMLWZFile(root)),
                TravelPlanModule.DEFAULT_CONFIG)) {
            assertEquals(List.of(new TravelPlanResult.Leg(1, 4, "localized", 2, "arrival")), fixture.plan(1, 2).route());
            assertEquals(List.of(new TravelPlanResult.Leg(11, 4, "localized", 2, "arrival")), fixture.plan(11, 2).route());
        }
    }

    @Test void cancellationAndModulePauseWaitForTheAdmittedMetadataReadToActuallyExit() throws Exception {
        Path root = temporary.resolve("base");
        writeMap(root, 1, "", portal(1, "out", 2, "in", 2, ""));
        writeMap(root, 2, "", portal(0, "in", 999999999, "", 0, ""));
        var nativeSource = new XMLWZFile(root);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocked = new DataProvider() {
            @Override public Data getData(String path) { entered.countDown(); await(release); return nativeSource.getData(path); }
            @Override public DataDirectoryEntry getRoot() { return nativeSource.getRoot(); }
        };
        try (var fixture = new Fixture(blocked, TravelPlanModule.DEFAULT_CONFIG)) {
            var parent = fixture.caller.acceptWork();
            var originalScope = fixture.runtime.context(fixture.reference).scope();
            var call = CompletableFuture.runAsync(() -> fixture.caller.scope().runTracked(parent,
                    () -> fixture.caller.call(TravelPlanModule.PLAN, new TravelPlanRequest(1, 2), parent)));
            CharacterRuntime.ModuleStop stop;
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                fixture.caller.cancel(parent);
                stop = fixture.runtime.stop(fixture.reference, CharacterRuntime.StopMode.PAUSE);
                assertFalse(stop.complete());
                assertFalse(call.isDone());
            } finally { release.countDown(); }
            assertInstanceOf(CancellationException.class, assertThrows(CompletionException.class, call::join).getCause());
            assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(Duration.ofSeconds(3)));
            assertEquals(0, originalScope.snapshot().acceptedWork());
            assertEquals(0, originalScope.snapshot().runningBodies());
            assertEquals(0, originalScope.snapshot().pendingCarriers());
            assertEquals(0, fixture.runtime.snapshot().lane().pending());
        }
    }

    @Test void deadlineAndMetadataFailureDoNotBecomeNoRouteResults() throws Exception {
        Path root = temporary.resolve("base");
        writeMap(root, 1, "", ""); writeMap(root, 2, "", "");
        var nativeSource = new XMLWZFile(root);
        var slow = new DataProvider() {
            @Override public Data getData(String path) {
                try { Thread.sleep(25); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                return nativeSource.getData(path);
            }
            @Override public DataDirectoryEntry getRoot() { return nativeSource.getRoot(); }
        };
        try (var fixture = new Fixture(slow, new TravelPlanModule.Config(1))) {
            assertEquals("travel_plan_timed_out", assertThrows(CancellationException.class, () -> fixture.plan(1, 2)).getMessage());
            fixture.assertDrained();
        }
        var failed = new DataProvider() {
            @Override public Data getData(String path) { throw new IllegalStateException("unreadable_map"); }
            @Override public DataDirectoryEntry getRoot() { return nativeSource.getRoot(); }
        };
        try (var fixture = new Fixture(failed, TravelPlanModule.DEFAULT_CONFIG)) {
            assertEquals("unreadable_map", assertThrows(IllegalStateException.class, () -> fixture.plan(1, 2)).getMessage());
            fixture.assertDrained();
        }
    }

    private static void writeMap(Path root, int id, String info, String portals) throws Exception {
        Path file = root.resolve("Map/Map" + id / 100000000 + "/" + String.format("%09d", id) + ".img.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<imgdir name=\"map\"><imgdir name=\"info\">" + info
                + "</imgdir><imgdir name=\"portal\">" + portals + "</imgdir></imgdir>");
    }

    private static String portal(int id, String name, int target, String entry, int type, String script) {
        return "<imgdir name=\"" + id + "\"><string name=\"pn\" value=\"" + name
                + "\"/><string name=\"tn\" value=\"" + entry + "\"/><int name=\"tm\" value=\"" + target
                + "\"/><int name=\"pt\" value=\"" + type + "\"/><int name=\"x\" value=\"0\"/>"
                + "<int name=\"y\" value=\"0\"/><string name=\"script\" value=\"" + script + "\"/></imgdir>";
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("metadata_gate_timed_out"); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
    }

    private static final class Fixture implements AutoCloseable {
        final Character actor = mock(Character.class);
        final Client client = mock(Client.class);
        final World world = mock(World.class);
        final Channel channel = mock(Channel.class);
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final CharacterRuntime.ModuleRef reference;

        Fixture(DataProvider data, TravelPlanModule.Config config) {
            when(actor.getId()).thenReturn(71);
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 8, 2)), executor);
            var ref = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "travel-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return ref; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", 71); }
                @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                    permit.requireExecuting(actor); return new ReleaseProof(true);
                }
            };
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(binding); runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            var callerContext = new AtomicReference<CharacterRuntime.ModuleContext>();
            runtime.mount(new ModuleDefinition<Void, RuntimeModule>("travel-test-caller", Set.of(),
                    new ExecutionScope.Limits(8, 0, 4, 0), ignored -> new RuntimeModule() {
                        @Override public void onActivated(CharacterRuntime.ModuleContext context) { callerContext.set(context); }
                    }, List.of()), new ModuleSelection<>("caller", null, true));
            caller = callerContext.get();
            var definition = new ModuleDefinition<TravelPlanModule.Config, TravelPlanModule>("travel", Set.of(),
                    TravelPlanModule.DEFINITION.limits(), value -> new TravelPlanModule(value, data), TravelPlanModule.DEFINITION.exports());
            reference = runtime.mount(definition, new ModuleSelection<>("travel", config, true)).reference();
            clearInvocations(actor, client, world, channel);
        }

        TravelPlanResult plan(int from, int to) {
            return caller.call(TravelPlanModule.PLAN, new TravelPlanRequest(from, to)).result();
        }

        void assertDrained() {
            var snapshot = runtime.snapshot().modules().stream().filter(module -> module.typeId().equals("travel")).findFirst().orElseThrow().scope();
            assertEquals(0, snapshot.acceptedWork()); assertEquals(0, snapshot.runningBodies());
            assertEquals(0, snapshot.pendingCarriers()); assertEquals(0, snapshot.outstandingResources());
            assertEquals(0, runtime.snapshot().lane().pending());
        }

        @Override public void close() {
            runtime.beginClose(Optional.empty());
            runtime.whenModulesDrained().toCompletableFuture().join();
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(3)));
            executor.shutdown();
        }
    }
}
