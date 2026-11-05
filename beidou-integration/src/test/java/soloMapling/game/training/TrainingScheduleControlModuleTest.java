package soloMapling.game.training;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.Shop;
import org.gms.server.ItemInformationProvider;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.action.PortalResult;
import soloMapling.game.action.PickupRequest;
import soloMapling.game.action.PickupResult;
import soloMapling.game.action.ReviveResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.hunting.HuntingControlModule;
import soloMapling.game.hunting.HuntingSnapshot;
import soloMapling.game.hunting.HuntingStartOptions;
import soloMapling.game.hunting.HuntingStartResult;
import soloMapling.game.hunting.HuntingStopResult;
import soloMapling.game.inventory.CapacityRule;
import soloMapling.game.inventory.InventoryFacts;
import soloMapling.game.inventory.SaleItem;
import soloMapling.game.inventory.SaleRules;
import soloMapling.game.loot.LootControlModule;
import soloMapling.game.loot.LootStatus;
import soloMapling.game.nativeaction.NativeActionModule;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.recovery.RecoveryControlModule;
import soloMapling.game.recovery.RecoveryStatus;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;
import soloMapling.game.shop.ShopActionModule;
import soloMapling.game.shop.ShopResult;
import soloMapling.game.travel.TravelPlanModule;
import soloMapling.game.travel.TravelPlanRequest;
import soloMapling.game.travel.TravelPlanResult;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real schedule/runtime/inventory rules; world movement, hunting and shop actions are protocol boundaries. */
class TrainingScheduleControlModuleTest {
    private static final int TRAINING = 104040000, TOWN = 100000000, MARKET = 100000100, STORE = 100000102;
    private static final int NPC_ID = 1011100, KEPT = 4000001, JUNK = 4000000, UNSALEABLE = 4000002;
    private static final AtomicInteger IDS = new AtomicInteger(9200);

    @BeforeEach void startWorld() throws Exception { InventoryMetadataTestContext.install(); WorldEpoch.onWorldStarted(); }
    @AfterEach void stopWorld() { ExecutionScope.tryBeginWorldStop(); }

    @Test void aCapacityRejectedReadSettlesBeforeTheSameScheduleRunUsesItsNextObservationOpportunity() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 0, false)) {
            eventually(() -> f.schedule.status().stage() == TrainingScheduleStatus.Stage.HUNTING);
            f.schedule.stop();
            f.recovery.stop();
            f.loot.stop();
            var owner = f.contexts.get("hunting");
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var queued = new ArrayList<ExecutionScope.WorkHandle>();
            var blocker = owner.acceptWork();
            queued.add(blocker);
            assertEquals(CharacterCommandLane.Status.ENQUEUED, owner.submit("hunting-observe", blocker, () -> {},
                    () -> { entered.countDown(); awaitActualRelease(release); }, () -> {}).status());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int count = 0; count < 6; count++) {
                    var work = owner.acceptWork();
                    queued.add(work);
                    assertEquals(CharacterCommandLane.Status.ENQUEUED,
                            owner.submit("hunting-observe", work, () -> {}, () -> {}, () -> {}).status());
                }
                assertEquals(7, f.runtime.snapshot().lane().pending());
                var started = f.schedule.start();
                long runId = started.status().runId();
                eventually(() -> f.schedule.status().state() == TrainingScheduleStatus.State.FAILED
                        || f.schedule.status().toMap().get("observation_admission") != null);
                assertEquals(TrainingScheduleStatus.State.RUNNING, f.schedule.status().state(), f.schedule.status().toString());
                var admission = assertInstanceOf(Map.class, f.schedule.status().toMap().get("observation_admission"));
                assertEquals("REJECTED", admission.get("status"));
                assertEquals("CAPACITY", admission.get("rejection"));
                var step = assertInstanceOf(Map.class, admission.get("step"));
                assertEquals(true, step.get("rejected"));
                assertEquals(true, step.get("skipped"));
                assertEquals(true, step.get("carrier_settled"));
                assertEquals(true, step.get("settled"));
                assertEquals(false, assertInstanceOf(Map.class, admission.get("parent")).get("cancel_requested"));
                assertEquals(true, admission.get("scope_active"));
                assertEquals(7, f.runtime.snapshot().lane().pending());
                var scope = f.runtime.context(f.scheduleRef).scope().snapshot();
                assertEquals(1, scope.acceptedWork(), "busy reads return their work before waiting for the next opportunity");
                release.countDown();
                queued.forEach(work -> work.completion().toCompletableFuture().join());
                f.loot.start();
                eventually(() -> f.schedule.status().stage() == TrainingScheduleStatus.Stage.HUNTING);
                assertEquals(runId, f.schedule.status().runId());
                assertEquals(TrainingScheduleStatus.State.RUNNING, f.schedule.status().state());
                f.schedule.stop();
                f.loot.stop();
                assertEquals(0, f.runtime.context(f.scheduleRef).scope().snapshot().acceptedWork());
                assertEquals(0, f.runtime.snapshot().lane().pending());
            } finally { release.countDown(); }
        }
    }

    @Test void anUnknownPickupDuringInitialTravelCannotBecomeTheFirstCycleBaseline() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 0, false, TOWN)) {
            assertTrue(f.blockedCallEntered.await(5, TimeUnit.SECONDS));
            var drop = new MapItem(new Item(JUNK, (short) 0, (short) 1), new Point(f.position.get()),
                    f.actor, f.actor, f.client, (byte) 0, true);
            drop.setObjectId(800001);
            drop.setDropTime(System.currentTimeMillis() - 1_000);
            var unknownPickup = new CountDownLatch(1);
            var unknown = new PickupResult(
                    PickupResult.Outcome.INDETERMINATE, "native_pickup_outcome_unknown", drop.getObjectId(),
                    JUNK, 1, 0, true, false, true, true, false);
            doAnswer(call -> { unknownPickup.countDown(); return unknown; })
                    .when(f.nativeActions).pickup(any(), any(), any());
            // Prepare the native outcome before publishing a drop to an already-running control.
            when(f.maps.get(TOWN).getMapObject(drop.getObjectId())).thenReturn(drop);
            when(f.maps.get(TOWN).getMapObjects()).thenReturn(List.of(drop));
            try {
                assertTrue(unknownPickup.await(5, TimeUnit.SECONDS));
                eventually(() -> f.loot.snapshot().indeterminateSequence() > 0);
                assertEquals(TrainingScheduleStatus.Stage.TO_TRAINING, f.schedule.status().stage());
            }
            finally { f.releaseBlockedCall.countDown(); }
            eventually(() -> "loot_indeterminate".equals(f.schedule.status().reason()));
            assertEquals(TrainingScheduleStatus.State.BLOCKED, f.schedule.status().state());
            assertNull(f.schedule.status().currentCycle());
            assertEquals(0, f.huntingIds.get());
            assertEquals(0, f.sales.get());
        }
    }

    @Test void replacingTheLootProviderAcrossAnUnavailableWindowDoesNotReuseItsCycleCounters() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 0, false)) {
            eventually(() -> f.schedule.status().currentCycle() != null);
            assertEquals(ExecutionScope.DrainResult.DRAINED,
                    f.runtime.stop(f.lootRef, CharacterRuntime.StopMode.UNLOAD).await(Duration.ofSeconds(5)));
            eventually(() -> "loot_unavailable".equals(f.schedule.status().reason()));
            var replacement = new LootControlModule(new LootControlModule.Config(10, 30_000), f.server);
            f.mount("replacement-loot", LootControlModule.DEFINITION, LootControlModule.DEFAULT_CONFIG, replacement);
            eventually(() -> "loot_provider_changed".equals(f.schedule.status().reason()));
            assertEquals(TrainingScheduleStatus.State.BLOCKED, f.schedule.status().state());
            assertEquals(0, f.schedule.status().cyclesCompleted());
            assertEquals(0, f.sales.get());
            assertEquals(LootStatus.State.RUNNING, replacement.snapshot().state());
        }
    }

    @Test void activationOwnsSuccessiveCyclesAndRetainsOnlyTheLatestTwoWithoutAScriptCaller() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 3, true)) {
            eventually(() -> f.schedule.status().cyclesCompleted() == 3);
            var status = f.schedule.status();
            assertEquals(TrainingScheduleStatus.Stage.HUNTING, status.stage());
            assertEquals(TRAINING, status.currentMapId());
            assertEquals(TrainingScheduleStartResult.Outcome.ALREADY_RUNNING, f.schedule.start().outcome());
            assertEquals(List.of(2L, 3L), status.recentCycles().stream().map(TrainingScheduleStatus.Cycle::index).toList());
            assertEquals(1, f.midwayTimeouts.get(), "a real partial move is retried from its changed position");
            assertEquals(0, f.runtime.snapshot().modules().stream().filter(m -> m.typeId().equals("mcp-control")).count());
            for (var cycle : status.recentCycles()) {
                assertTrue(cycle.complete());
                assertEquals(1, cycle.huntingStarted().bags().getFirst().freeSlots());
                assertEquals(0, cycle.capacityTriggered().bags().getFirst().freeSlots());
                assertEquals(1, cycle.returned().bags().getFirst().freeSlots());
                assertEquals(1, cycle.loot().collectedObjects());
                assertEquals(1, cycle.loot().collectedQuantity());
                assertEquals(1, cycle.sale().soldQuantity());
                assertEquals(5, cycle.sale().mesoAfter() - cycle.sale().mesoBefore());
                assertEquals(List.of(TOWN, MARKET, STORE, MARKET, TOWN, TRAINING),
                        cycle.portalSteps().stream().map(TrainingScheduleStatus.PortalStep::toMapId).toList());
            }
            assertEquals(7, f.bags.get(InventoryType.ETC).countById(KEPT));
            long owned = f.hunting.get().activityId();
            long checks = f.recovery.status().checks();
            var paused = f.runtime.stop(f.scheduleRef, CharacterRuntime.StopMode.PAUSE);
            assertEquals(ExecutionScope.DrainResult.DRAINED, paused.await(Duration.ofSeconds(5)));
            assertTrue(f.stoppedHunts.contains(owned));
            verify(f.hunter, never()).stop();
            assertEquals(RecoveryStatus.State.RUNNING, f.recovery.status().state());
            eventually(() -> f.recovery.status().checks() > checks);
            f.assertScheduleDrained();
        }
    }

    @Test void blockedShopWaitsForNewCandidatesOrFreeSpaceWithoutRepeatingTrips() throws Exception {
        try (var f = new Fixture(UNSALEABLE, 1, 0, 1, false)) {
            eventually(() -> f.schedule.status().state() == TrainingScheduleStatus.State.BLOCKED);
            assertEquals("no_saleable_items", f.schedule.status().reason());
            assertEquals(STORE, f.mapId());
            int plans = f.plans.size();
            Thread.sleep(100);
            assertEquals(plans, f.plans.size());
            assertEquals(0, f.shopOpens.get());
            f.mutateInventory(() -> {
                f.bags.get(InventoryType.ETC).removeItem((short) 2);
                f.bags.get(InventoryType.ETC).addItem(new Item(JUNK, (short) 0, (short) 1));
            });
            eventually(() -> f.schedule.status().cyclesCompleted() == 1);
            assertEquals(1, f.sales.get());
            assertEquals(1, f.closedShops.size());
        }
        try (var f = new Fixture(JUNK, 2, 1, 1, false)) {
            eventually(() -> f.sales.get() == 1 && f.schedule.status().state() == TrainingScheduleStatus.State.BLOCKED);
            assertEquals(STORE, f.mapId());
            assertEquals(1, f.bags.get(InventoryType.ETC).countById(JUNK), "configured reserve remains and still fills its slot");
            assertEquals(0, f.bags.get(InventoryType.ETC).getNumFreeSlot());
            assertEquals(1, f.schedule.status().currentCycle().sale().soldQuantity());
            int plans = f.plans.size();
            Thread.sleep(100);
            assertEquals(1, f.shopOpens.get());
            assertEquals(1, f.sales.get());
            assertEquals(plans, f.plans.size());
            f.mutateInventory(() -> f.bags.get(InventoryType.ETC).removeItem((short) 2));
            eventually(() -> f.schedule.status().cyclesCompleted() == 1);
            assertEquals(TRAINING, f.mapId());
            assertEquals(7, f.bags.get(InventoryType.ETC).countById(KEPT));
            assertEquals(TrainingScheduleStopResult.Outcome.STOPPED, f.schedule.stop().outcome());
            assertEquals(TrainingScheduleStatus.State.IDLE, f.schedule.status().state());
        }
    }

    @Test void pauseWaitsForNavigationBodyAndReactivationPlansFromTheActualIntermediateMap() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 1, false, MARKET)) {
            assertTrue(f.blockedCallEntered.await(5, TimeUnit.SECONDS));
            assertEquals(MARKET, f.mapId());
            long checks = f.recovery.status().checks();
            var paused = f.runtime.stop(f.scheduleRef, CharacterRuntime.StopMode.PAUSE);
            eventually(() -> f.blockedWork.get().cancelRequested());
            assertEquals(ExecutionScope.DrainResult.TIMED_OUT, paused.await(Duration.ZERO));
            assertFalse(f.blockedWork.get().isSettled());
            eventually(() -> f.recovery.status().checks() > checks);
            assertEquals(RecoveryStatus.State.RUNNING, f.recovery.status().state());
            f.releaseBlockedCall.countDown();
            assertEquals(ExecutionScope.DrainResult.DRAINED, paused.await(Duration.ofSeconds(5)));
            f.assertScheduleDrained();
            int portalCount = f.portalDestinations.size();
            Thread.sleep(80);
            assertEquals(portalCount, f.portalDestinations.size(), "paused schedule dispatches no next portal");
            assertEquals(MARKET, f.mapId());
            int plans = f.plans.size();
            f.scheduleRef = f.runtime.activate(f.scheduleRef);
            eventually(() -> f.huntingIds.get() == 2 && f.schedule.status().currentCycle() != null);
            assertEquals(new TravelPlanRequest(MARKET, STORE), f.plans.get(plans));
            assertEquals(TRAINING, f.mapId());
            assertEquals(1, f.sales.get());
        }
    }

    @Test void pauseDuringSaleKeepsTheAppliedItemsAndMesosAndClosesOnlyItsInteraction() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 1, false, -1, true)) {
            assertTrue(f.blockedCallEntered.await(5, TimeUnit.SECONDS));
            assertEquals(0, f.bags.get(InventoryType.ETC).countById(JUNK));
            assertEquals(5, f.mesos.get());
            long checks = f.recovery.status().checks();
            var paused = f.runtime.stop(f.scheduleRef, CharacterRuntime.StopMode.PAUSE);
            eventually(() -> f.blockedWork.get().cancelRequested());
            assertEquals(ExecutionScope.DrainResult.TIMED_OUT, paused.await(Duration.ZERO));
            assertNotNull(f.activeShop.get());
            eventually(() -> f.recovery.status().checks() > checks);
            f.releaseBlockedCall.countDown();
            assertEquals(ExecutionScope.DrainResult.DRAINED, paused.await(Duration.ofSeconds(5)));
            assertTrue(f.blockedWork.get().isSettled());
            assertEquals(List.of(1L), f.closedShops);
            assertNull(f.activeShop.get());
            assertEquals(1, f.schedule.status().currentCycle().sale().soldQuantity());
            assertEquals(0, f.bags.get(InventoryType.ETC).countById(JUNK));
            assertEquals(5, f.mesos.get());
            assertEquals(7, f.bags.get(InventoryType.ETC).countById(KEPT));
            assertEquals(RecoveryStatus.State.RUNNING, f.recovery.status().state());
            f.assertScheduleDrained();
        }
    }

    @Test void deathRevivesTheSameRunAndReturnsFromTheActualRespawnMapBeforeHuntingAgain() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 0, false)) {
            eventually(() -> f.huntingIds.get() == 1 && f.mapId() == TRAINING
                    && f.schedule.status().currentCycle() != null);
            long run = f.schedule.status().runId();
            when(f.nativeActions.revive(any(), any(), any())).thenAnswer(call -> {
                CharacterRuntime.ModuleContext context = call.getArgument(1);
                ExecutionScope.WorkHandle work = call.getArgument(2);
                var before = f.reviveState();
                f.onLane(context, work, "revive", () -> {
                    f.alive.set(true); f.currentMap.set(f.maps.get(TOWN)); f.position.set(new Point());
                });
                return f.reviveResult(ReviveResult.Outcome.REVIVED, ReviveResult.EventRoute.STANDARD_ALLOWED,
                        before, f.reviveState());
            });
            f.mutateInventory(() -> f.alive.set(false));

            eventually(() -> f.huntingIds.get() == 2 && f.schedule.status().stage() == TrainingScheduleStatus.Stage.HUNTING);

            assertEquals(run, f.schedule.status().runId());
            assertTrue(f.stoppedHunts.contains(1L));
            assertEquals(TRAINING, f.mapId());
            assertEquals(2, f.plans.stream().filter(plan -> plan.equals(new TravelPlanRequest(TOWN, TRAINING))).count());
            verify(f.nativeActions, times(1)).revive(any(), any(), any());
        }
    }

    @Test void eventOwnedRevivalIsNotReplayedAndItsLaterCompletionResumesTheSchedule() throws Exception {
        try (var f = new Fixture(JUNK, 1, 0, 0, false)) {
            eventually(() -> f.huntingIds.get() == 1 && f.mapId() == TRAINING
                    && f.schedule.status().currentCycle() != null);
            long run = f.schedule.status().runId();
            when(f.nativeActions.revive(any(), any(), any())).thenAnswer(call ->
                    f.reviveResult(ReviveResult.Outcome.NOT_REVIVED, ReviveResult.EventRoute.TAKEN_OVER,
                            f.reviveState(), f.reviveState()));
            f.mutateInventory(() -> f.alive.set(false));
            eventually(() -> "revive_waiting_event".equals(f.schedule.status().reason()));
            assertEquals(TrainingScheduleStatus.State.BLOCKED, f.schedule.status().state());
            assertTrue(f.stoppedHunts.contains(1L));
            // A changed position refreshes observations, but it grants no permission to replay the event.
            f.mutateInventory(() -> f.position.set(new Point(20, 0)));
            Thread.sleep(80);
            verify(f.nativeActions, times(1)).revive(any(), any(), any());
            assertFalse(f.alive.get());
            assertEquals(TrainingScheduleStatus.State.BLOCKED, f.schedule.status().state());
            assertEquals("revive_waiting_event", f.schedule.status().reason());
            f.mutateInventory(() -> {
                f.currentMap.set(f.maps.get(TOWN)); f.position.set(new Point()); f.alive.set(true);
            });

            eventually(() -> f.huntingIds.get() == 2 && f.mapId() == TRAINING);

            assertEquals(run, f.schedule.status().runId());
            verify(f.nativeActions, times(1)).revive(any(), any(), any());
        }
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    private static void awaitActualRelease(CountDownLatch release) {
        boolean interrupted = false;
        while (true) {
            try { release.await(); break; }
            catch (InterruptedException stopped) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Fixture implements AutoCloseable {
        final int id = IDS.incrementAndGet();
        final Server server = mock(Server.class);
        final Character actor = mock(Character.class);
        final Client client = mock(Client.class);
        final Map<Integer, MapleMap> maps = new HashMap<>();
        final Map<InventoryType, Inventory> bags = new EnumMap<>(InventoryType.class);
        final Map<Integer, MapObject> drops = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger dropIds = new AtomicInteger(1000);
        final Map<String, CharacterRuntime.ModuleContext> contexts = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicReference<MapleMap> currentMap = new AtomicReference<>();
        final AtomicReference<Point> position = new AtomicReference<>(new Point());
        final AtomicBoolean present = new AtomicBoolean(true);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicReference<Shop> activeShop = new AtomicReference<>();
        final AtomicInteger mesos = new AtomicInteger();
        final AtomicLong huntingIds = new AtomicLong();
        final AtomicReference<HuntingSnapshot> hunting = new AtomicReference<>(huntSnapshot(0, false, 0));
        final HuntingControlModule hunter = mock(HuntingControlModule.class);
        final ShopActionModule shop = mock(ShopActionModule.class);
        final NativeActionModule nativeActions = mock(NativeActionModule.class);
        final ItemInformationProvider information = mock(ItemInformationProvider.class);
        final RecoveryControlModule recovery;
        final LootControlModule loot;
        final CharacterRuntime.ModuleRef lootRef;
        final CharacterRuntime runtime;
        final TrainingScheduleControlModule schedule;
        final List<TravelPlanRequest> plans = new CopyOnWriteArrayList<>();
        final List<Integer> portalDestinations = new CopyOnWriteArrayList<>();
        final List<Long> stoppedHunts = new CopyOnWriteArrayList<>();
        final List<Long> closedShops = new CopyOnWriteArrayList<>();
        final AtomicInteger shopOpens = new AtomicInteger(), sales = new AtomicInteger(), midwayTimeouts = new AtomicInteger();
        final CountDownLatch blockedCallEntered = new CountDownLatch(1), releaseBlockedCall = new CountDownLatch(1);
        final AtomicReference<ExecutionScope.WorkHandle> blockedWork = new AtomicReference<>();
        CharacterRuntime.ModuleRef scheduleRef;
        long interactionId;

        Fixture(int lootId, int lootQuantity, int reserve, int lootActivities, boolean partialFirstMove) throws Exception {
            this(lootId, lootQuantity, reserve, lootActivities, partialFirstMove, -1, false);
        }
        Fixture(int lootId, int lootQuantity, int reserve, int lootActivities, boolean partialFirstMove, int holdNavigationMap) throws Exception {
            this(lootId, lootQuantity, reserve, lootActivities, partialFirstMove, holdNavigationMap, false);
        }
        Fixture(int lootId, int lootQuantity, int reserve, int lootActivities, boolean partialFirstMove,
                int holdNavigationMap, boolean holdSale) throws Exception {
            var world = mock(World.class); var channel = mock(Channel.class); var storage = mock(PlayerStorage.class);
            when(server.isOnline()).thenReturn(true); when(server.getCurrentTime()).thenReturn(100_000L);
            when(server.getWorld(0)).thenReturn(world); when(world.getChannel(1)).thenReturn(channel);
            when(world.getPlayerStorage()).thenReturn(storage); when(channel.getPlayerStorage()).thenReturn(storage);
            when(channel.getId()).thenReturn(1); when(storage.getCharacterById(id)).thenAnswer(call -> present.get() ? actor : null);
            when(actor.getId()).thenReturn(id); when(actor.getClient()).thenReturn(client); when(client.getPlayer()).thenReturn(actor);
            when(client.getChannel()).thenReturn(1); when(actor.getMap()).thenAnswer(call -> currentMap.get());
            when(actor.getMapId()).thenAnswer(call -> mapId()); when(actor.getPosition()).thenAnswer(call -> new Point(position.get()));
            when(actor.isAlive()).thenAnswer(call -> alive.get()); when(actor.getHp()).thenAnswer(call -> alive.get() ? 200 : 0); when(actor.getCurrentMaxHp()).thenReturn(200);
            when(actor.getMp()).thenReturn(80); when(actor.getCurrentMaxMp()).thenReturn(80); when(actor.getChair()).thenReturn(-1);
            when(actor.getShop()).thenAnswer(call -> activeShop.get()); when(actor.getMeso()).thenAnswer(call -> mesos.get());
            for (var type : InventoryType.values()) {
                var bag = new Inventory(actor, type, (byte) 2); bags.put(type, bag); when(actor.getInventory(type)).thenReturn(bag);
            }
            bags.get(InventoryType.ETC).addItem(new Item(KEPT, (short) 0, (short) 7));
            for (int mapId : List.of(TRAINING, TOWN, MARKET, STORE)) {
                var map = mock(MapleMap.class); maps.put(mapId, map); when(map.getId()).thenReturn(mapId);
                when(map.getMapObjects()).thenAnswer(call -> mapId == TRAINING ? new ArrayList<>(drops.values()) : List.of());
                when(map.getMapObject(anyInt())).thenAnswer(call -> mapId == TRAINING ? drops.get(call.getArgument(0)) : null);
                when(map.getCharacterById(id)).thenAnswer(call -> present.get() && currentMap.get() == map ? actor : null);
            }
            addPortal(TRAINING, 17, "east00", TOWN, "west00", 60);
            addPortal(TOWN, 20, "west00", TRAINING, "east00", -60);
            addPortal(TOWN, 23, "in00", MARKET, "out00", 60);
            addPortal(MARKET, 14, "out00", TOWN, "in00", -60);
            addPortal(MARKET, 17, "in01", STORE, "out01", 60);
            addPortal(STORE, 2, "out01", MARKET, "in01", -60);
            for (var map : maps.values()) {
                var portals = portalLists.get(map.getId());
                when(map.getPortals()).thenReturn(portals);
            }
            var npc = mock(NPC.class); when(npc.getId()).thenReturn(NPC_ID); when(npc.getPosition()).thenReturn(new Point(-20, 0));
            when(maps.get(STORE).getNPCById(NPC_ID)).thenReturn(npc);
            currentMap.set(maps.get(TOWN));
            var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        try (var metadata = mockStatic(ItemInformationProvider.class)) {
                            metadata.when(ItemInformationProvider::getInstance).thenReturn(information);
                            command.run();
                        }
                    }));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), executor);
            var ref = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "training-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return ref; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", id); }
                @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                    permit.requireExecuting(actor); present.set(false); return new ReleaseProof(true);
                }
            };
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> { runtime.reserveOrigin(opening).adopt(binding); runtime.bindAdoptedOrigin(); }, () -> {});
            opening.completion().toCompletableFuture().join();

            doAnswer(call -> { contexts.put("hunting", call.getArgument(0)); return null; }).when(hunter).onActivated(any());
            when(hunter.snapshot()).thenAnswer(call -> hunting.get());
            when(hunter.start(any())).thenAnswer(call -> {
                long next = huntingIds.incrementAndGet();
                boolean fill = next <= lootActivities;
                if (fill) onLane(contexts.get("hunting"), null, "hunting-observe", () -> {
                    var drop = new MapItem(new Item(lootId, (short) 0, (short) lootQuantity), new Point(position.get()),
                            actor, actor, client, (byte) 0, true);
                    drop.setObjectId(dropIds.incrementAndGet());
                    drop.setDropTime(System.currentTimeMillis() - 1_000);
                    drops.put(drop.getObjectId(), drop);
                });
                var state = huntSnapshot(next, true, fill ? 1 : 0); hunting.set(state);
                return new HuntingStartResult(HuntingStartResult.Outcome.STARTED, "started", state);
            });
            when(hunter.stop(anyLong())).thenAnswer(call -> {
                long expected = call.getArgument(0); stoppedHunts.add(expected); var state = hunting.get();
                if (state.activityId() != expected) return new HuntingStopResult(HuntingStopResult.Outcome.ACTIVITY_CHANGED, "changed", state);
                var stopped = huntSnapshot(expected, false, state.kills()); hunting.set(stopped);
                return new HuntingStopResult(HuntingStopResult.Outcome.STOPPED, "stopped", stopped);
            });
            mount("hunting", HuntingControlModule.DEFINITION, HuntingControlModule.DEFAULT_CONFIG, hunter);
            loot = new LootControlModule(new LootControlModule.Config(10, 30_000), server);
            lootRef = mount("loot", LootControlModule.DEFINITION, LootControlModule.DEFAULT_CONFIG, loot).reference();

            var travel = mock(TravelPlanModule.class);
            when(travel.plan(any(), any(), any())).thenAnswer(call -> {
                TravelPlanRequest request = call.getArgument(0); plans.add(request);
                return new TravelPlanResult(TravelPlanResult.Outcome.FOUND, request.fromMapId(), request.toMapId(), route(request.fromMapId(), request.toMapId()));
            });
            mount("travel", TravelPlanModule.DEFINITION, TravelPlanModule.DEFAULT_CONFIG, travel);
            var navigation = mock(NavigationActionModule.class); var firstMove = new AtomicBoolean(partialFirstMove);
            var heldNavigation = new AtomicBoolean();
            when(navigation.execute(any(), any(), any())).thenAnswer(call -> {
                soloMapling.game.navigation.MoveToRequest request = call.getArgument(0);
                CharacterRuntime.ModuleContext context = call.getArgument(1); ExecutionScope.WorkHandle work = call.getArgument(2);
                if (mapId() == holdNavigationMap && heldNavigation.compareAndSet(false, true)) hold(work);
                if (work.cancelRequested()) return new NavigationResult(NavigationResult.Outcome.CANCELLED, "cancelled", null);
                boolean timeout = firstMove.compareAndSet(true, false);
                var destination = new Point(timeout ? (position.get().x + request.x()) / 2 : request.x(), request.y());
                onLane(context, work, "movement", () -> position.set(destination));
                if (timeout) midwayTimeouts.incrementAndGet();
                return new NavigationResult(timeout ? NavigationResult.Outcome.TIMED_OUT : NavigationResult.Outcome.ARRIVED,
                        timeout ? "partial_move" : "arrived", new NavigationResult.Progress(
                        new NavigationResult.Position(request.x(), request.y()), new NavigationResult.Position(destination.x, destination.y),
                        true, 1, 1, List.of("WALK")));
            });
            mount("navigation", NavigationActionModule.DEFINITION, NavigationActionModule.DEFAULT_CONFIG, navigation);
            when(nativeActions.enterPortal(any(), any(), any())).thenAnswer(call -> {
                soloMapling.game.action.PortalRequest request = call.getArgument(0); CharacterRuntime.ModuleContext context = call.getArgument(1);
                ExecutionScope.WorkHandle work = call.getArgument(2); var beforeMap = currentMap.get(); var beforePosition = position.get();
                var exit = beforeMap.getPortal(request.portalId()); var target = maps.get(exit.getTargetMapId());
                var entry = portalLists.get(target.getId()).stream().filter(p -> p.getName().equals(exit.getTarget())).findFirst().orElseThrow();
                onLane(context, work, "portal", () -> { currentMap.set(target); position.set(entry.getPosition()); });
                portalDestinations.add(target.getId());
                return new PortalResult(PortalResult.Outcome.ENTERED, id, runtime.acceptedEpoch().toString(), request.portalId(),
                        endpoint(beforeMap, exit), endpoint(target, entry), portalState(beforeMap, beforePosition), portalState(target, position.get()),
                        new PortalResult.Dispatch(true, true, false), new PortalResult.Transition(true, true, true, false, true), true, false, null);
            });
            when(nativeActions.pickup(any(), any(), any())).thenAnswer(call -> {
                PickupRequest request = call.getArgument(0); CharacterRuntime.ModuleContext context = call.getArgument(1);
                ExecutionScope.WorkHandle work = call.getArgument(2); MapItem target = request.binding().target();
                int itemId = target.getItem().getItemId(), quantity = target.getItem().getQuantity();
                onLane(context, work, "pickup", () -> {
                    bags.get(InventoryType.ETC).addItem(new Item(itemId, (short) 0, (short) quantity));
                    target.setPickedUp(true); drops.remove(target.getObjectId());
                });
                return new PickupResult(PickupResult.Outcome.COLLECTED, null, request.objectId(), itemId,
                        quantity, quantity, true, true, true, false, false);
            });
            mount("native", NativeActionModule.DEFINITION, NativeActionModule.DEFAULT_CONFIG, nativeActions);

            doAnswer(call -> { contexts.put("shop", call.getArgument(0)); return null; }).when(shop).onActivated(any());
            when(shop.open(any(), any(), any())).thenAnswer(call -> {
                CharacterRuntime.ModuleContext context = call.getArgument(1); ExecutionScope.WorkHandle work = call.getArgument(2);
                onLane(context, work, "shop", () -> { var actual = mock(Shop.class); when(actual.getId()).thenReturn(NPC_ID); activeShop.set(actual); });
                shopOpens.incrementAndGet(); interactionId++;
                return shopResult(ShopResult.Operation.OPEN, ShopResult.Outcome.OPENED, List.of(), mesos.get(), mesos.get());
            });
            var heldSale = new AtomicBoolean();
            when(shop.sell(any(), any(), any())).thenAnswer(call -> {
                soloMapling.game.shop.ShopSellRequest request = call.getArgument(0); CharacterRuntime.ModuleContext context = call.getArgument(1);
                ExecutionScope.WorkHandle work = call.getArgument(2); int beforeMeso = mesos.get(); var rows = new ArrayList<ShopResult.Sale>();
                onLane(context, work, "shop", () -> {
                    for (SaleItem item : request.items()) {
                        var bag = bags.get(item.inventoryType()); var actual = bag.getItem(item.slot()); int beforeQuantity = actual.getQuantity();
                        int previousMeso = mesos.get(); bag.removeItem(item.slot(), (short) item.quantity(), false); mesos.addAndGet(5 * item.quantity());
                        var after = bag.getItem(item.slot());
                        rows.add(new ShopResult.Sale(item, ShopResult.SaleOutcome.SOLD, item.expectedItemId(), after == null ? 0 : after.getItemId(),
                                beforeQuantity, after == null ? 0 : after.getQuantity(), previousMeso, mesos.get(), true, false, null));
                    }
                });
                sales.incrementAndGet();
                if (holdSale && heldSale.compareAndSet(false, true)) hold(work);
                return shopResult(ShopResult.Operation.SELL, ShopResult.Outcome.SOLD, rows, beforeMeso, mesos.get());
            });
            when(shop.close(anyLong())).thenAnswer(call -> {
                long expected = call.getArgument(0);
                if (expected != interactionId) return shopResult(ShopResult.Operation.CLOSE, ShopResult.Outcome.ALREADY_CLOSED, List.of(), mesos.get(), mesos.get());
                onLane(contexts.get("shop"), null, "shop", () -> activeShop.set(null)); closedShops.add(expected);
                return shopResult(ShopResult.Operation.CLOSE, ShopResult.Outcome.CLOSED, List.of(), mesos.get(), mesos.get());
            });
            mount("shop", ShopActionModule.DEFINITION, ShopActionModule.DEFAULT_CONFIG, shop);
            recovery = new RecoveryControlModule(RecoveryControlModule.DEFAULT_CONFIG, server);
            mount("recovery", RecoveryControlModule.DEFINITION, RecoveryControlModule.DEFAULT_CONFIG, recovery);
            schedule = new TrainingScheduleControlModule(new TrainingScheduleControlModule.Config(TRAINING, STORE, NPC_ID,
                    HuntingStartOptions.Style.PATROL, new CapacityRule(Set.of(InventoryType.ETC), 0),
                    new SaleRules(Set.of(JUNK, KEPT), Set.of(KEPT), reserve == 0 ? Map.of() : Map.of(JUNK, reserve)), 10, true), server);
            scheduleRef = mount("training", TrainingScheduleControlModule.DEFINITION, TrainingScheduleControlModule.DEFAULT_CONFIG, schedule).reference();
        }

        private final Map<Integer, List<Portal>> portalLists = new HashMap<>();
        private void addPortal(int mapId, int id, String name, int targetMap, String targetName, int x) {
            var portal = mock(Portal.class); when(portal.getId()).thenReturn(id); when(portal.getName()).thenReturn(name);
            when(portal.getTargetMapId()).thenReturn(targetMap); when(portal.getTarget()).thenReturn(targetName);
            when(portal.getPosition()).thenReturn(new Point(x, 0)); when(portal.getPortalState()).thenReturn(true); when(portal.getPortalStatus()).thenReturn(true);
            portalLists.computeIfAbsent(mapId, ignored -> new ArrayList<>()).add(portal); when(maps.get(mapId).getPortal(id)).thenReturn(portal);
        }

        private List<TravelPlanResult.Leg> route(int from, int to) {
            var sequence = List.of(TRAINING, TOWN, MARKET, STORE); int index = sequence.indexOf(from), end = sequence.indexOf(to);
            var result = new ArrayList<TravelPlanResult.Leg>(); int step = index < end ? 1 : -1;
            while (index != end) {
                int target = sequence.get(index + step); var exit = portalLists.get(sequence.get(index)).stream()
                        .filter(p -> p.getTargetMapId() == target).findFirst().orElseThrow();
                result.add(new TravelPlanResult.Leg(sequence.get(index), exit.getId(), exit.getName(), target, exit.getTarget())); index += step;
            }
            return result;
        }

        private <C, M extends RuntimeModule> CharacterRuntime.MountedModule<M> mount(String name, ModuleDefinition<C, M> definition, C config, M instance) {
            var replacement = new ModuleDefinition<C, M>(definition.typeId(), definition.controls(), definition.limits(), ignored -> instance, definition.exports());
            return runtime.mount(replacement, new ModuleSelection<>(name, config, true));
        }
        private int mapId() { return currentMap.get().getId(); }
        private void mutateInventory(Runnable change) { onLane(contexts.get("hunting"), null, "hunting-observe", change); }
        private void hold(ExecutionScope.WorkHandle work) {
            blockedWork.set(work); blockedCallEntered.countDown(); awaitActualRelease(releaseBlockedCall);
        }
        private void onLane(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle parent, String control, Runnable body) {
            var work = parent == null ? context.acceptWork() : context.acceptWork(parent);
            var failure = new AtomicReference<Throwable>();
            context.submit(control, work, () -> {}, () -> { try { body.run(); } catch (RuntimeException | Error e) { failure.set(e); } }, () -> {});
            work.completion().toCompletableFuture().join();
            if (failure.get() instanceof Error error) throw error;
            if (failure.get() instanceof RuntimeException exception) throw exception;
        }
        private ReviveResult.State reviveState() {
            return new ReviveResult.State(alive.get(), alive.get() ? 200 : 0, 80, mapId(), position.get().x, position.get().y, false);
        }
        private ReviveResult reviveResult(ReviveResult.Outcome outcome, ReviveResult.EventRoute route,
                                          ReviveResult.State before, ReviveResult.State after) {
            boolean standard = route == ReviveResult.EventRoute.STANDARD_ALLOWED;
            return new ReviveResult(outcome, id, runtime.acceptedEpoch().toString(), before, after,
                    new ReviveResult.Dispatch(true, true, false),
                    new ReviveResult.Event(route, true, true, standard, standard, standard, standard, standard,
                            standard ? TOWN : null),
                    new ReviveResult.Transition(standard, standard, standard, false, standard),
                    before.mapId() != after.mapId(), false, null);
        }
        private PortalResult.Endpoint endpoint(MapleMap map, Portal portal) {
            return new PortalResult.Endpoint(map.getId(), portal.getId(), portal.getName(), portal.getPosition().x, portal.getPosition().y);
        }
        private PortalResult.State portalState(MapleMap map, Point point) {
            return new PortalResult.State(0, 1, map.getId(), System.identityHashCode(map), point.x, point.y, true, false);
        }
        private ShopResult shopResult(ShopResult.Operation operation, ShopResult.Outcome outcome, List<ShopResult.Sale> rows, int before, int after) {
            return new ShopResult(operation, outcome, null, id, NPC_ID, 700001, NPC_ID, interactionId, before, after, rows, true, false);
        }
        private static HuntingSnapshot huntSnapshot(long id, boolean active, int quantity) {
            return new HuntingSnapshot(active ? HuntingControlModule.State.HUNTING : HuntingControlModule.State.IDLE, id,
                    "PATROL", "PATROL", null, 0, quantity > 0 ? 1 : 0, quantity, 0,
                    active ? null : "stopped", Map.of());
        }
        void assertScheduleDrained() {
            assertEquals(TrainingScheduleStatus.State.IDLE, schedule.status().state());
            var module = runtime.snapshot().modules().stream().filter(m -> m.instanceId().equals("training")).findFirst().orElseThrow();
            assertEquals(CharacterRuntime.ModuleState.SUSPENDED, module.state());
            var scope = module.scope();
            if (scope != null) {
                assertEquals(0, scope.acceptedWork()); assertEquals(0, scope.runningBodies()); assertEquals(0, scope.pendingCarriers());
                assertEquals(0, scope.outstandingResources());
            }
        }
        @Override public void close() throws Exception {
            releaseBlockedCall.countDown(); schedule.stop(); runtime.beginClose(Optional.empty());
            runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var cleanup = runtime.acquireOriginCleanup(); runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
        }
    }
}
