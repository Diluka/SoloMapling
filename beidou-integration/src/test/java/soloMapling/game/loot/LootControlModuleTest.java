package soloMapling.game.loot;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Item;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.McpControlModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.action.PickupRequest;
import soloMapling.game.action.PickupResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.nativeaction.NativeActionModule;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real control/directory work; the native provider is the controlled boundary, not an asset-effect claim. */
class LootControlModuleTest {
    @BeforeEach void startWorld() throws Exception { InventoryMetadataTestContext.install(); WorldEpoch.onWorldStarted(); }
    @AfterEach void stopWorld() { ExecutionScope.tryBeginWorldStop(); }

    @Test void ordinaryOwnDropGetsOneImmediateExactOpportunityWithoutMovingOrReplayingAfterBusy() throws Exception {
        try (var fixture = new Fixture()) {
            var submitted = new AtomicReference<PickupRequest>();
            when(fixture.nativeActions.pickup(any(), any(), any())).thenAnswer(call -> {
                PickupRequest input = call.getArgument(0);
                submitted.set(input);
                return PickupResult.skipped(input.objectId(), PickupResult.Outcome.BUSY, "scene_immediate_opportunity_busy");
            });
            var foreign = fixture.ground(42, new Point(0, 0));
            foreign.lockItem();
            try { assertTrue(foreign.canBePickedBy(fixture.actor), "the foreign FFA drop is natively pickable"); }
            finally { foreign.unlockItem(); }
            var distantOwn = fixture.ground(41, new Point(1000, 1000));
            var own = fixture.ground(41, new Point(0, 0));

            await(() -> fixture.loot.snapshot().sequence() == 1);
            int scansAfterAttempt = fixture.scans.get();
            await(() -> fixture.scans.get() >= scansAfterAttempt + 3);
            var request = submitted.get();
            assertNotNull(request);
            assertEquals(own.getObjectId(), request.objectId());
            assertSame(own, request.binding().target());
            assertTrue(request.binding().immediate());
            assertNull(request.binding().arrangement(), "an ordinary opportunity does not claim movement");
            assertEquals(PickupResult.Outcome.BUSY, fixture.loot.snapshot().latest().outcome());
            assertEquals(new Point(0, 0), fixture.actor.getPosition());
            assertFalse(foreign.isPickedUp());
            assertFalse(distantOwn.isPickedUp());
            verify(fixture.nativeActions).pickup(any(), any(), any());
            verifyNoInteractions(fixture.navigation);
            assertEquals(LootControlResult.Outcome.STOPPED, fixture.loot.stop().outcome());
            fixture.assertLootDrained();
        }
    }

    @Test void stopWaitsForTheAcceptedProviderBodyAndRetainsItsUnknownOutcomeReceipt() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var providerWork = new AtomicReference<ExecutionScope.WorkHandle>();
            when(fixture.nativeActions.pickup(any(), any(), any())).thenAnswer(call -> {
                PickupRequest input = call.getArgument(0);
                providerWork.set(call.getArgument(2));
                entered.countDown();
                awaitEvenWhenInterrupted(release);
                return new PickupResult(PickupResult.Outcome.INDETERMINATE, "native_pickup_outcome_unknown",
                        input.objectId(), 4000000, 3, 2, true, false, true, true, true);
            });
            var target = fixture.ground(41, new Point(0, 0));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stopped = CompletableFuture.supplyAsync(fixture.loot::stop);
            try {
                await(() -> fixture.loot.snapshot().state() == LootStatus.State.STOPPING);
                assertTrue(providerWork.get().cancelRequested());
                assertFalse(providerWork.get().isSettled());
                assertFalse(stopped.isDone(), "cancellation does not settle the still-running provider body");
                assertTrue(fixture.lootContext.scope().snapshot().acceptedWork() > 0);
            } finally { release.countDown(); }

            var result = stopped.get(5, TimeUnit.SECONDS);
            assertEquals(LootControlResult.Outcome.STOPPED, result.outcome());
            assertTrue(providerWork.get().isSettled());
            assertEquals(PickupResult.Outcome.INDETERMINATE, result.status().latest().outcome());
            assertEquals(2, result.status().latest().inventoryQuantityDelta());
            assertEquals(1, result.status().indeterminateSequence());
            assertEquals(2, result.status().collectedQuantity(), "retains the provider's confirmed increment, not a new asset claim");
            verify(fixture.nativeActions).pickup(any(), any(), any());
            verifyNoInteractions(fixture.navigation);
            fixture.assertLootDrained();

            int scansBeforeRestart = fixture.scans.get();
            assertEquals(LootControlResult.Outcome.STARTED, fixture.loot.start().outcome());
            await(() -> fixture.scans.get() >= scansBeforeRestart + 3);
            var restarted = fixture.loot.snapshot();
            assertSame(target, fixture.map.getMapObject(target.getObjectId()));
            assertEquals(1, restarted.sequence());
            assertEquals(1, restarted.indeterminateSequence());
            assertEquals(2, restarted.collectedQuantity());
            assertEquals(PickupResult.Outcome.INDETERMINATE, restarted.latest().outcome());
            verify(fixture.nativeActions).pickup(any(), any(), any());
            fixture.loot.stop();
            fixture.assertLootDrained();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "bounded control observation did not arrive");
    }

    private static void awaitEvenWhenInterrupted(CountDownLatch release) throws InterruptedException {
        boolean interrupted = false;
        try {
            while (release.getCount() > 0) {
                try { assertTrue(release.await(5, TimeUnit.SECONDS), "provider release was not supplied"); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static final class Fixture implements AutoCloseable {
        final Server server = mock(Server.class);
        final Character actor = mock(Character.class);
        final Client client = mock(Client.class);
        final MapleMap map = mock(MapleMap.class);
        final ItemInformationProvider information = mock(ItemInformationProvider.class);
        final NativeActionModule nativeActions = mock(NativeActionModule.class);
        final NavigationActionModule navigation = mock(NavigationActionModule.class);
        final Map<Integer, MapObject> objects = new ConcurrentHashMap<>();
        final AtomicInteger scans = new AtomicInteger();
        final AtomicInteger objectIds = new AtomicInteger(1000);
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext lootContext;
        final LootControlModule loot;
        final ThreadPoolExecutor executor;

        Fixture() throws Exception {
            when(server.isOnline()).thenReturn(true);
            when(actor.getId()).thenReturn(41);
            when(actor.getClient()).thenReturn(client);
            when(client.getPlayer()).thenReturn(actor);
            when(client.getChannel()).thenReturn(1);
            when(actor.getMap()).thenReturn(map);
            when(actor.getMapId()).thenReturn(10_000);
            when(actor.getPosition()).thenAnswer(call -> new Point(0, 0));
            when(actor.getPartyId()).thenReturn(-1);
            when(actor.isAlive()).thenReturn(true);
            when(map.getId()).thenReturn(10_000);
            when(map.getCharacterById(41)).thenReturn(actor);
            when(map.getMapObject(anyInt())).thenAnswer(call -> objects.get(call.getArgument(0)));
            when(map.getMapObjects()).thenAnswer(call -> { scans.incrementAndGet(); return new ArrayList<>(objects.values()); });
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var storage = mock(PlayerStorage.class);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannel(1)).thenReturn(channel);
            when(channel.getId()).thenReturn(1);
            when(world.getPlayerStorage()).thenReturn(storage);
            when(channel.getPlayerStorage()).thenReturn(storage);
            when(storage.getCharacterById(41)).thenReturn(actor);
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        try (var items = mockStatic(ItemInformationProvider.class)) {
                            items.when(ItemInformationProvider::getInstance).thenReturn(information);
                            command.run();
                        }
                    }));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), executor);
            var reference = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var origin = new RoleBinding() {
                @Override public String kind() { return "loot-routing-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return reference; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", 41); }
                @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                    permit.requireExecuting(actor); return new ReleaseProof(true);
                }
            };
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(origin);
                runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            runtime.mount(McpControlModule.DEFINITION, new ModuleSelection<>("mcp", Map.of(), true));
            var natives = new ModuleDefinition<NativeActionModule.Config, NativeActionModule>(
                    NativeActionModule.DEFINITION.typeId(), NativeActionModule.DEFINITION.controls(),
                    NativeActionModule.DEFINITION.limits(), config -> nativeActions, NativeActionModule.DEFINITION.exports());
            runtime.mount(natives, new ModuleSelection<>("native", NativeActionModule.DEFAULT_CONFIG, true));
            var navigationDefinition = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), config -> navigation, NavigationActionModule.DEFINITION.exports());
            runtime.mount(navigationDefinition, new ModuleSelection<>("navigation", NavigationActionModule.DEFAULT_CONFIG, true));
            clearInvocations(nativeActions, navigation);
            loot = new LootControlModule(LootControlModule.DEFAULT_CONFIG, server);
            var definition = new ModuleDefinition<LootControlModule.Config, LootControlModule>(
                    LootControlModule.DEFINITION.typeId(), LootControlModule.DEFINITION.controls(),
                    LootControlModule.DEFINITION.limits(), config -> loot, LootControlModule.DEFINITION.exports());
            var mounted = runtime.mount(definition, new ModuleSelection<>("loot", LootControlModule.DEFAULT_CONFIG, true));
            lootContext = runtime.context(mounted.reference());
        }

        MapItem ground(int ownerId, Point point) {
            var owner = ownerId == 41 ? actor : mock(Character.class);
            if (owner != actor) { when(owner.getId()).thenReturn(ownerId); when(owner.getPartyId()).thenReturn(-1); }
            var drop = new MapItem(new Item(4000000, (short) 0, (short) 3), point, owner, owner, client, (byte) 2, true);
            drop.setDropTime(System.currentTimeMillis() - 20_000);
            drop.setObjectId(objectIds.incrementAndGet());
            objects.put(drop.getObjectId(), drop);
            return drop;
        }

        void assertLootDrained() {
            var scope = lootContext.scope().snapshot();
            assertEquals(0, scope.acceptedWork());
            assertEquals(0, scope.runningBodies());
            assertEquals(0, scope.pendingCarriers());
            assertEquals(0, scope.outstandingResources());
        }

        @Override public void close() throws Exception {
            try {
                runtime.beginClose(Optional.empty());
                runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
                var cleanup = runtime.acquireOriginCleanup();
                runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
                assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
            } finally { executor.shutdownNow(); }
        }
    }
}
