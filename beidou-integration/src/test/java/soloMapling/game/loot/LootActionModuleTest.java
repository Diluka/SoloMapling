package soloMapling.game.loot;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
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
import org.mockito.MockedStatic;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public module calls, real runtime/short native pickup/inventory; navigation and metadata are named boundaries. */
class LootActionModuleTest {
    @BeforeEach void startWorld() throws Exception { InventoryMetadataTestContext.install(); WorldEpoch.onWorldStarted(); }
    @AfterEach void stopWorld() { ExecutionScope.tryBeginWorldStop(); }

    @Test void explicitCollectionApproachesThenProvesActualNativeInventoryGrowth() throws Exception {
        try (var fixture = new Fixture()) {
            var drop = fixture.ground(4000000, 5, new Point(160, 0), true);
            var result = fixture.collect(new LootRequest(List.of(drop.getObjectId()), 5_000));
            assertEquals(LootResult.Outcome.COMPLETED, result.outcome(), result.toString());
            assertEquals(1, result.collectedObjects());
            assertEquals(5, result.collectedQuantity());
            assertEquals(5, fixture.bags.get(InventoryType.ETC).countById(4000000));
            assertTrue(drop.isPickedUp());
            assertFalse(fixture.objects.containsKey(drop.getObjectId()));
            assertEquals(new Point(160, 0), fixture.position.get());
            verify(fixture.navigation).execute(argThat(target -> target.x() == 160 && target.y() == 0
                    && target.timeoutMs() > 0 && target.timeoutMs() <= 5_000), any(), any());
            var item = result.items().getFirst();
            assertEquals(LootResult.ItemOutcome.COLLECTED, item.outcome());
            assertTrue(item.dispatchStarted());
            fixture.assertDrained();
        }
    }

    @Test void missingOwnedByAnotherAndFullBagAreAccurateWithoutInventingAnEntry() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.bags.get(InventoryType.ETC).addItem(new Item(4000000, (short) 0, (short) 100));
            fixture.bags.get(InventoryType.ETC).addItem(new Item(4000001, (short) 0, (short) 100));
            var other = fixture.ground(4000002, 1, new Point(0, 0), false);
            var full = fixture.ground(4000003, 1, new Point(0, 0), true);
            var result = fixture.collect(new LootRequest(List.of(999, other.getObjectId(), full.getObjectId()), 5_000));
            assertEquals(LootResult.Outcome.CAPACITY_FULL, result.outcome());
            assertEquals(List.of(LootResult.ItemOutcome.NOT_FOUND, LootResult.ItemOutcome.OWNER_REJECTED,
                    LootResult.ItemOutcome.CAPACITY_FULL), result.items().stream().map(LootResult.ItemResult::outcome).toList());
            assertEquals(0, result.collectedQuantity());
            assertEquals(0, fixture.bags.get(InventoryType.ETC).countById(4000003));
            assertFalse(other.isPickedUp());
            assertFalse(full.isPickedUp());
            verify(fixture.navigation, never()).execute(any(), any(), any());
            fixture.assertDrained();
        }
    }

    @Test void cancellationWaitsForApproachExitAndRetainsTheFirstNativePickup() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.ground(4000000, 3, new Point(0, 0), true);
            var next = fixture.ground(4000001, 1, new Point(200, 0), true);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var navigationWork = new AtomicReference<ExecutionScope.WorkHandle>();
            doAnswer(call -> {
                navigationWork.set(call.getArgument(2)); entered.countDown(); awaitEvenWhenInterrupted(release);
                return new NavigationResult(NavigationResult.Outcome.CANCELLED, "navigation_cancelled", null);
            }).when(fixture.navigation).execute(any(), any(), any());
            var callerWork = fixture.caller.acceptWork();
            var result = CompletableFuture.supplyAsync(() -> {
                var value = new AtomicReference<LootResult>();
                fixture.caller.scope().runTracked(callerWork, () -> value.set(fixture.caller.call(LootActionModule.COLLECT,
                        new LootRequest(List.of(first.getObjectId(), next.getObjectId()), 5_000), callerWork).result()));
                return value.get();
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(3, fixture.bags.get(InventoryType.ETC).countById(4000000));
                fixture.caller.cancel(callerWork);
                assertTrue(navigationWork.get().cancelRequested());
                assertFalse(result.isDone());
                assertTrue(fixture.lootScope().acceptedWork() > 0);
            } finally { release.countDown(); }
            var stopped = result.get(5, TimeUnit.SECONDS);
            callerWork.completion().toCompletableFuture().join();
            assertEquals(LootResult.Outcome.CANCELLED, stopped.outcome());
            assertEquals(1, stopped.collectedObjects());
            assertEquals(3, stopped.collectedQuantity());
            assertFalse(next.isPickedUp());
            fixture.assertDrained();
        }
    }

    @Test void uncertainNativePickupPreservesTheObservedDeltaAndDoesNotReplayOrContinue() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.ground(4000000, 2, new Point(0, 0), true);
            var next = fixture.ground(4000001, 1, new Point(0, 0), true);
            doThrow(new IllegalStateException("native_map_unavailable"))
                    .when(fixture.map).pickItemDrop(any(), eq(first));
            var result = fixture.collect(new LootRequest(List.of(first.getObjectId(), next.getObjectId()), 5_000));
            assertEquals(LootResult.Outcome.INDETERMINATE, result.outcome());
            assertEquals(1, result.items().size());
            assertEquals(2, result.items().getFirst().gainedQuantity());
            assertEquals(0, result.collectedQuantity(), "an unconfirmed removal is not a confirmed collection");
            assertEquals(2, fixture.bags.get(InventoryType.ETC).countById(4000000));
            assertFalse(first.isPickedUp());
            assertFalse(next.isPickedUp());
            assertEquals(0, fixture.bags.get(InventoryType.ETC).countById(4000001));
            fixture.assertDrained();
        }
    }

    @Test void deadlineDoesNotReturnBeforeItsQueuedObservationHasActuallyExited() throws Exception {
        try (var fixture = new Fixture()) {
            var drop = fixture.ground(4000000, 1, new Point(0, 0), true);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocker = fixture.lootContext.acceptWork();
            fixture.lootContext.submit("loot", blocker, () -> {}, () -> {
                entered.countDown(); awaitEvenWhenInterrupted(release);
            }, () -> {});
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var result = CompletableFuture.supplyAsync(() -> fixture.collect(new LootRequest(List.of(drop.getObjectId()), 20)));
            try {
                Thread.sleep(60);
                assertFalse(result.isDone());
                assertEquals(0, fixture.bags.get(InventoryType.ETC).countById(4000000));
            } finally { release.countDown(); }
            assertEquals(LootResult.Outcome.TIMED_OUT, result.get(5, TimeUnit.SECONDS).outcome());
            assertFalse(drop.isPickedUp());
            fixture.assertDrained();
        }
    }

    private static void awaitEvenWhenInterrupted(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() > 0) {
            try { release.await(10, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Fixture implements AutoCloseable {
        final Server server = mock(Server.class);
        final Character actor = mock(Character.class);
        final Client client = mock(Client.class);
        final MapleMap map = mock(MapleMap.class);
        final ItemInformationProvider information = mock(ItemInformationProvider.class);
        final NavigationActionModule navigation = mock(NavigationActionModule.class);
        final Map<InventoryType, Inventory> bags = new EnumMap<>(InventoryType.class);
        final Map<Integer, MapObject> objects = new ConcurrentHashMap<>();
        final AtomicReference<Point> position = new AtomicReference<>(new Point(0, 0));
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final CharacterRuntime.ModuleContext lootContext;
        final ThreadPoolExecutor executor;
        final MockedStatic<Server> singleton;
        final MockedStatic<ItemInformationProvider> metadata;

        Fixture() throws Exception {
            when(server.isOnline()).thenReturn(true); when(server.getCurrentTime()).thenReturn(1_000L);
            when(information.getSlotMax(any(), anyInt())).thenReturn((short) 100);
            singleton = mockStatic(Server.class); singleton.when(Server::getInstance).thenReturn(server);
            metadata = mockStatic(ItemInformationProvider.class); metadata.when(ItemInformationProvider::getInstance).thenReturn(information);
            when(actor.getId()).thenReturn(41); when(actor.getClient()).thenReturn(client);
            var nativeClient = Character.class.getDeclaredField("client"); nativeClient.setAccessible(true); nativeClient.set(actor, client);
            when(client.getPlayer()).thenReturn(actor); when(client.getChannel()).thenReturn(1); when(client.isLoggedIn()).thenReturn(true);
            when(actor.getMap()).thenReturn(map); when(actor.getMapId()).thenReturn(10_000);
            when(map.getId()).thenReturn(10_000); when(map.getCharacterById(41)).thenReturn(actor);
            when(actor.getPosition()).thenAnswer(call -> new Point(position.get()));
            when(actor.getPartyId()).thenReturn(-1); when(actor.isAlive()).thenReturn(true);
            when(actor.needQuestItem(anyInt(), anyInt())).thenReturn(true);
            when(actor.getAutoBanManager()).thenReturn(mock(AutobanManager.class));
            for (var type : List.of(InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.USE,
                    InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
                var inventory = new Inventory(actor, type, (byte) 2); bags.put(type, inventory);
                when(actor.getInventory(type)).thenReturn(inventory);
            }
            when(map.getMapObject(anyInt())).thenAnswer(call -> objects.get(call.getArgument(0)));
            when(map.getMapObjects()).thenAnswer(call -> new ArrayList<>(objects.values()));
            doAnswer(call -> {
                MapItem item = call.getArgument(1); item.setPickedUp(true); objects.remove(item.getObjectId()); return null;
            }).when(map).pickItemDrop(any(), any(MapItem.class));
            doCallRealMethod().when(actor).pickupItem(any(MapObject.class));
            doCallRealMethod().when(actor).pickupItem(any(MapObject.class), anyInt());
            var world = mock(World.class); var channel = mock(Channel.class); var storage = mock(PlayerStorage.class);
            when(server.getWorld(0)).thenReturn(world); when(world.getChannel(1)).thenReturn(channel); when(channel.getId()).thenReturn(1);
            when(world.getPlayerStorage()).thenReturn(storage); when(channel.getPlayerStorage()).thenReturn(storage);
            when(storage.getCharacterById(41)).thenReturn(actor);
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        try (var servers = mockStatic(Server.class); var items = mockStatic(ItemInformationProvider.class)) {
                            servers.when(Server::getInstance).thenReturn(server);
                            items.when(ItemInformationProvider::getInstance).thenReturn(information);
                            command.run();
                        }
                    }));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), executor);
            var reference = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "loot-test"; }
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
                runtime.reserveOrigin(opening).adopt(binding); runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            caller = runtime.mount(McpControlModule.DEFINITION, new ModuleSelection<>("mcp", Map.of(), true)).instance().context();
            var navigationDefinition = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), config -> navigation, NavigationActionModule.DEFINITION.exports());
            runtime.mount(navigationDefinition, new ModuleSelection<>("navigation", NavigationActionModule.DEFAULT_CONFIG, true));
            when(navigation.execute(any(), any(), any())).thenAnswer(call -> {
                MoveToRequest target = call.getArgument(0); position.set(new Point(target.x(), target.y()));
                return new NavigationResult(NavigationResult.Outcome.ARRIVED, null,
                        new NavigationResult.Progress(new NavigationResult.Position(target.x(), target.y()),
                                new NavigationResult.Position(target.x(), target.y()), true, 2, 10, List.of("WALK")));
            });
            var loot = runtime.mount(LootActionModule.DEFINITION, new ModuleSelection<>("loot", LootActionModule.DEFAULT_CONFIG, true));
            lootContext = runtime.context(loot.reference());
        }

        MapItem ground(int itemId, int quantity, Point point, boolean owned) {
            var owner = owned ? actor : mock(Character.class);
            if (!owned) { when(owner.getId()).thenReturn(42); when(owner.getPartyId()).thenReturn(-1); }
            var drop = new MapItem(new Item(itemId, (short) 0, (short) quantity), point, owner, owner, client, (byte) 0, true);
            drop.setDropTime(System.currentTimeMillis() - 2_000); drop.setObjectId(1_000 + objects.size());
            objects.put(drop.getObjectId(), drop); return drop;
        }

        LootResult collect(LootRequest request) {
            var call = caller.call(LootActionModule.COLLECT, request);
            assertEquals(ModuleCall.Status.SUCCEEDED, call.status());
            return call.result();
        }

        ExecutionScope.ScopeSnapshot lootScope() {
            return runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals("loot"))
                    .findFirst().orElseThrow().scope();
        }

        void assertDrained() {
            assertEquals(0, runtime.snapshot().lane().pending());
            assertEquals(0, lootScope().acceptedWork()); assertEquals(0, lootScope().runningBodies());
            assertEquals(0, lootScope().pendingCarriers()); assertEquals(0, lootScope().outstandingResources());
        }

        @Override public void close() throws Exception {
            try {
                runtime.beginClose(Optional.empty()); runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
                var cleanup = runtime.acquireOriginCleanup();
                runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
                assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
            } finally {
                executor.shutdownNow(); metadata.close(); singleton.close();
            }
        }
    }
}
