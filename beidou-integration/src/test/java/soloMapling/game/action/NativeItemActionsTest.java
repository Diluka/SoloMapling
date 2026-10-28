package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.server.SoloItemMetadata;
import org.gms.server.StatEffect;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;

/** Real native handlers and InventoryManipulator; explicit in-memory map/metadata/effect boundaries. */
class NativeItemActionsTest {
    private Server server;
    private Character actor;
    private Client client;
    private MapleMap map;
    private ItemInformationProvider items;
    private final Map<InventoryType, Inventory> inventories = new HashMap<>();
    private final Map<Integer, MapObject> objects = new HashMap<>();
    private final AtomicInteger nextObject = new AtomicInteger(800), hp = new AtomicInteger(20), mp = new AtomicInteger(5);
    private MockedStatic<Server> servers;
    private MockedStatic<ItemInformationProvider> metadata;
    private MockedStatic<SoloItemMetadata> slotMetadata;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private CharacterCommandLane.ControlPermit permit;
    private GameContext.SceneRef scene;
    private ManualExecutor executor;

    @BeforeEach void fixture() throws Exception {
        InventoryMetadataTestContext.install(); WorldEpoch.onWorldStarted();
        server = mock(Server.class); when(server.isOnline()).thenReturn(true); when(server.getCurrentTime()).thenReturn(1000L);
        servers = mockStatic(Server.class); servers.when(Server::getInstance).thenReturn(server);
        items = mock(ItemInformationProvider.class);
        when(items.getSlotMax(any(), anyInt())).thenReturn((short) 100);
        when(items.canWearEquipment(any(), any(Equip.class), anyInt())).thenReturn(true);
        metadata = mockStatic(ItemInformationProvider.class); metadata.when(ItemInformationProvider::getInstance).thenReturn(items);
        slotMetadata = mockStatic(SoloItemMetadata.class);
        slotMetadata.when(() -> SoloItemMetadata.ordinaryEquipmentFits(anyInt(), anyInt())).thenAnswer(call -> {
            int item = call.getArgument(0), slot = call.getArgument(1);
            return item / 10000 == 130 && slot == -11 || item / 10000 == 140 && slot == -11;
        });
        actor = mock(Character.class); client = mock(Client.class); map = mock(MapleMap.class);
        when(actor.getId()).thenReturn(41); when(actor.getClient()).thenReturn(client); when(client.getPlayer()).thenReturn(actor);
        var nativeClient = Character.class.getDeclaredField("client"); nativeClient.setAccessible(true); nativeClient.set(actor, client);
        when(client.getChannel()).thenReturn(1); when(client.isLoggedIn()).thenReturn(true);
        when(actor.getMap()).thenReturn(map); when(map.getCharacterById(41)).thenReturn(actor);
        when(actor.getPosition()).thenAnswer(call -> new Point(0, 0)); when(actor.getPartyId()).thenReturn(-1);
        when(actor.isAlive()).thenReturn(true); when(actor.needQuestItem(anyInt(), anyInt())).thenReturn(true);
        when(actor.getAutoBanManager()).thenReturn(mock(AutobanManager.class));
        when(actor.getHp()).thenAnswer(call -> hp.get()); when(actor.getMp()).thenAnswer(call -> mp.get());
        when(actor.getCurrentMaxHp()).thenReturn(100); when(actor.getCurrentMaxMp()).thenReturn(50);
        for (InventoryType type : List.of(InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.USE,
                InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
            Inventory inventory = new Inventory(actor, type, (byte) 24); inventories.put(type, inventory);
            when(actor.getInventory(type)).thenReturn(inventory);
        }
        when(actor.getTotalWatk()).thenAnswer(call -> inventories.get(InventoryType.EQUIPPED).list().stream()
                .filter(Equip.class::isInstance).map(Equip.class::cast).mapToInt(Equip::getWatk).sum());
        when(map.getMapObject(anyInt())).thenAnswer(call -> objects.get(call.getArgument(0)));
        when(map.getMapObjects()).thenAnswer(call -> new ArrayList<>(objects.values()));
        doAnswer(call -> {
            Item source = call.getArgument(2); Point point = call.getArgument(3);
            ground(source, point); return null;
        }).when(map).spawnItemDrop(eq(actor), eq(actor), any(Item.class), any(Point.class), anyBoolean(), anyBoolean());
        doAnswer(call -> { MapItem item = call.getArgument(1); item.setPickedUp(true); objects.remove(item.getObjectId()); return null; })
                .when(map).pickItemDrop(any(), any(MapItem.class));
        doCallRealMethod().when(actor).pickupItem(any(MapObject.class));
        doCallRealMethod().when(actor).pickupItem(any(MapObject.class), anyInt());
        World world = mock(World.class); Channel channel = mock(Channel.class); when(channel.getId()).thenReturn(1);
        when(server.getWorld(0)).thenReturn(world); when(world.getChannel(1)).thenReturn(channel);
        PlayerStorage worldPlayers = mock(PlayerStorage.class), channelPlayers = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(worldPlayers); when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        when(worldPlayers.getCharacterById(41)).thenReturn(actor); when(channelPlayers.getCharacterById(41)).thenReturn(actor);
        var reference = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), actor, client, world, channel);
        scene = new GameContext.SceneRef(reference, map, null, null);
        scope = new ExecutionScope(reference.epoch(), new ExecutionScope.Limits(16, 1, 8, 1));
        executor = new ManualExecutor(); lane = new CharacterCommandLane(8, 1, executor);
        permit = lane.bindGrantedPermit(scope, reference, lane.declareGrant(scope),
                () -> NativeContextChecks.requireCharacterCurrent(server, reference));
    }

    @AfterEach void close() {
        if (lane != null) lane.close(); if (executor != null) executor.shutdown();
        ThreadLocalUtil.removeCurrentClient();
        if (slotMetadata != null) slotMetadata.close(); if (metadata != null) metadata.close(); if (servers != null) servers.close();
        ExecutionScope.tryBeginWorldStop();
    }

    @Test void finiteTypedArgumentsRejectImplicitDropEquipAndZeroQuantity() {
        assertThrows(NativeActionService.Invalid.class, () -> new NativeItemAction.Drop("ETC", 0, 1));
        assertThrows(NativeActionService.Invalid.class, () -> new NativeItemAction.Drop("ETC", 1, 0));
        assertThrows(NativeActionService.Invalid.class, () -> new NativeItemAction.Drop("EQUIPPED", 1, 1));
        assertThrows(NativeActionService.Invalid.class, () -> new NativeItemAction.Equip(1, -14));
        assertThrows(NativeActionService.Invalid.class, () -> new NativeItemAction.Equip(1, -111));
        assertThrows(NativeActionService.Invalid.class, () -> new NativeItemAction.Unequip(-11, -1));
        assertThrows(NativeActionService.Invalid.class, () -> new NativeItemAction.Pickup(0));
    }

    @Test void realNativeEquipAndUnequipMoveTheExactEquipAndExposeLiveAttributes() {
        Equip sword = equip(1302000, 1, 7); inventories.get(InventoryType.EQUIP).addItemFromDB(sword);
        NativeBasicResult worn = run(new NativeItemAction.Equip(1, -11));
        assertEquals(SUCCEEDED, worn.outcome()); assertSame(sword, inventories.get(InventoryType.EQUIPPED).getItem((short) -11));
        assertNull(inventories.get(InventoryType.EQUIP).getItem((short) 1));
        assertEquals(7, ((Map<?, ?>) ((Map<?, ?>) worn.facts().get("after")).get("stats")).get("watk"));
        NativeBasicResult removed = run(new NativeItemAction.Unequip(-11, 2));
        assertEquals(SUCCEEDED, removed.outcome()); assertSame(sword, inventories.get(InventoryType.EQUIP).getItem((short) 2));
        assertEquals(0, ((Map<?, ?>) ((Map<?, ?>) removed.facts().get("after")).get("stats")).get("watk"));
        sword.setWatk((short) 99);
        assertEquals(7, ((Map<?, ?>) ((Map<?, ?>) worn.facts().get("after")).get("stats")).get("watk"));
    }

    @Test void nativeTwoHandedWeaponAutomaticallyUnequipsShieldAndReportsAllAffectedSlots() {
        Equip sword = equip(1402000, 1, 9), shield = equip(1092000, -10, 1);
        inventories.get(InventoryType.EQUIP).addItemFromDB(sword); inventories.get(InventoryType.EQUIPPED).addItemFromDB(shield);
        when(items.isTwoHanded(1402000)).thenReturn(true);
        NativeBasicResult result = run(new NativeItemAction.Equip(1, -11));
        assertEquals(SUCCEEDED, result.outcome()); assertNull(inventories.get(InventoryType.EQUIPPED).getItem((short) -10));
        assertSame(shield, inventories.get(InventoryType.EQUIP).getItem((short) 2));
        Map<?, ?> after = (Map<?, ?>) result.facts().get("after");
        assertEquals(1, ((List<?>) after.get("inventory")).size()); assertEquals(1, ((List<?>) after.get("equipped")).size());
        assertEquals(9, ((Map<?, ?>) after.get("stats")).get("watk"));
    }

    @Test void wrongEquipmentPartAndEmptySourceAreRejectedBeforeNativeAnticheatOrNullDereference() {
        inventories.get(InventoryType.EQUIP).addItemFromDB(equip(1302000, 1, 7));
        NativeBasicResult wrong = run(new NativeItemAction.Equip(1, -1));
        assertEquals("equipment_slot_mismatch", wrong.fixedReason()); assertFalse(wrong.handlerError());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, wrong.dispatch());
        NativeBasicResult empty = run(new NativeItemAction.Equip(2, -11));
        assertEquals("inventory_source_empty", empty.fixedReason()); verify(client, never()).sendPacket(any());
    }

    @Test void nativeEquipmentCooldownIsFailureWithIdenticalBeforeAfterRatherThanPretendSuccess() {
        inventories.get(InventoryType.EQUIP).addItemFromDB(equip(1302000, 1, 7));
        when(actor.getAutoBanManager().getLastSpam(6)).thenReturn(900L);
        NativeBasicResult result = run(new NativeItemAction.Equip(1, -11));
        assertEquals(FAILED, result.outcome()); assertEquals(result.facts().get("before"), result.facts().get("after"));
        assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch()); assertEquals(false, result.facts().get("effect_observed"));
    }

    @Test void ordinaryPotionUsesRealHandlerAndSeparatelyReportsConsumedQuantityAndRecovery() {
        potion(); NativeBasicResult result = run(new NativeItemAction.Use(1));
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(9, inventories.get(InventoryType.USE).getItem((short) 1).getQuantity());
        assertEquals(1, result.facts().get("consumed_quantity")); assertEquals(50, result.facts().get("hp_delta"));
        assertEquals(0, result.facts().get("mp_delta")); assertEquals(true, result.facts().get("recovery_observed"));
    }

    @Test void fullHealthStillConsumesPotionWithoutClaimingHpRecovered() {
        potion(); hp.set(100); NativeBasicResult result = run(new NativeItemAction.Use(1));
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(1, result.facts().get("consumed_quantity"));
        assertEquals(0, result.facts().get("hp_delta")); assertEquals(false, result.facts().get("recovery_observed"));
    }

    @Test void handlerFailureAfterConsumptionPreservesInventoryFactsAndDoesNotPretendEffectCompleted() {
        StatEffect effect = potion(); when(effect.applyTo(actor)).thenThrow(new IllegalStateException("private diagnostic"));
        NativeBasicResult result = run(new NativeItemAction.Use(1));
        assertEquals(INDETERMINATE, result.outcome()); assertEquals(1, result.facts().get("consumed_quantity"));
        assertTrue(result.handlerError()); assertEquals("native_item_effect_unknown", result.fixedReason());
        assertFalse(result.toString().contains("private diagnostic"));
    }

    @Test void townScrollAndBuffPotionsAreNotSilentlyPromotedToOrdinaryItemUse() {
        inventories.get(InventoryType.USE).addItemFromDB(new Item(2030000, (short) 1, (short) 2));
        assertEquals("unsupported_use_item", run(new NativeItemAction.Use(1)).fixedReason());
        inventories.get(InventoryType.USE).removeSlot((short) 1); StatEffect effect = potion();
        when(effect.getMpCon()).thenReturn((short) 1);
        assertEquals("unsupported_use_item", run(new NativeItemAction.Use(1)).fixedReason());
        verify(client, never()).sendPacket(any());
    }

    @Test void pickupUsesExactGroundIdentityAndActualNativeInventoryGrowth() {
        MapItem item = ground(new Item(4000000, (short) 1, (short) 5), new Point(5, 5));
        NativeBasicResult result = run(new NativeItemAction.Pickup(item.getObjectId()));
        assertEquals(SUCCEEDED, result.outcome(), result.toString()); assertTrue(item.isPickedUp()); assertFalse(objects.containsKey(item.getObjectId()));
        assertEquals(5, inventories.get(InventoryType.ETC).countById(4000000));
        assertEquals(5, result.facts().get("inventory_quantity_delta")); assertEquals(true, result.facts().get("ground_removed"));
    }

    @Test void distantOrAlreadyPickedGroundDoesNotDispatchOrClaimInventoryGrowth() {
        MapItem item = ground(new Item(4000000, (short) 1, (short) 5), new Point(801, 0));
        assertEquals("ground_item_out_of_range", run(new NativeItemAction.Pickup(item.getObjectId())).fixedReason());
        item.setPosition(new Point(0, 0)); item.setPickedUp(true);
        assertEquals("ground_item_changed", run(new NativeItemAction.Pickup(item.getObjectId())).fixedReason());
        verify(client, never()).sendPacket(any()); assertEquals(0, inventories.get(InventoryType.ETC).countById(4000000));
    }

    @Test void dropReportsActualPartialQuantityAndNewGroundOid() {
        inventories.get(InventoryType.ETC).addItemFromDB(new Item(4000000, (short) 1, (short) 8));
        NativeBasicResult result = run(new NativeItemAction.Drop("ETC", 1, 3));
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(5, inventories.get(InventoryType.ETC).getItem((short) 1).getQuantity());
        assertEquals(3, result.facts().get("removed_quantity")); assertEquals("RETAINED_OBJECT_OBSERVED", result.facts().get("ground_outcome"));
        assertEquals(1, ((List<?>) result.facts().get("ground_objects")).size());
    }

    @Test void nativeDisappearingDropDoesNotInventARecoverableGroundObject() {
        inventories.get(InventoryType.ETC).addItemFromDB(new Item(4000000, (short) 1, (short) 3));
        when(items.isDropRestricted(4000000)).thenReturn(true);
        NativeBasicResult result = run(new NativeItemAction.Drop("ETC", 1, 3));
        assertEquals(SUCCEEDED, result.outcome()); assertEquals("NO_RETAINED_OBJECT_OBSERVED", result.facts().get("ground_outcome"));
        assertEquals(List.of(), result.facts().get("ground_objects")); verify(map).disappearingItemDrop(eq(actor), eq(actor), any(), any());
    }

    @Test void dropSendFailureAfterInventoryRemovalDoesNotClaimGroundDropCompleted() {
        inventories.get(InventoryType.ETC).addItemFromDB(new Item(4000000, (short) 1, (short) 3));
        doThrow(new IllegalStateException("private send failure")).when(client).sendPacket(any());
        NativeBasicResult result = run(new NativeItemAction.Drop("ETC", 1, 3));
        assertEquals(INDETERMINATE, result.outcome()); assertTrue(result.handlerError());
        assertEquals(3, result.facts().get("removed_quantity")); assertEquals(List.of(), result.facts().get("ground_objects"));
        verify(map, never()).spawnItemDrop(any(MapObject.class), any(), any(), any(), anyBoolean(), anyBoolean());
        verify(map, never()).disappearingItemDrop(any(), any(), any(), any());
    }

    @Test void equipSendFailureAfterSlotMoveDoesNotClaimAttributeRefreshCompleted() {
        Equip sword = equip(1302000, 1, 7); inventories.get(InventoryType.EQUIP).addItemFromDB(sword);
        doThrow(new IllegalStateException("private send failure")).when(client).sendPacket(any());
        NativeBasicResult result = run(new NativeItemAction.Equip(1, -11));
        assertEquals(INDETERMINATE, result.outcome()); assertTrue(result.handlerError());
        assertSame(sword, inventories.get(InventoryType.EQUIPPED).getItem((short) -11));
        verify(actor, never()).equipChanged();
    }

    @Test void confirmedGroundObjectSurvivesLaterNativeFailureWithoutBeingReplayed() {
        inventories.get(InventoryType.ETC).addItemFromDB(new Item(4000000, (short) 1, (short) 3));
        doAnswer(call -> { ground(call.getArgument(2), call.getArgument(3)); throw new IllegalStateException("late map hook"); })
                .when(map).spawnItemDrop(eq(actor), eq(actor), any(Item.class), any(Point.class), anyBoolean(), anyBoolean());
        NativeBasicResult result = run(new NativeItemAction.Drop("ETC", 1, 3));
        assertEquals(SUCCEEDED, result.outcome()); assertTrue(result.handlerError());
        assertEquals("RETAINED_OBJECT_OBSERVED", result.facts().get("ground_outcome"));
        verify(map, times(1)).spawnItemDrop(eq(actor), eq(actor), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test void queuedCancellationCannotEnterNativeItemSideEffects() {
        potion(); var work = scope.acceptWork(); var handle = NativeItemActions.prepare(server, lane, permit, work, scene, new NativeItemAction.Use(1));
        NativeBasicActions.submit(handle); scope.requestCancel(work);
        assertFalse(work.isSettled()); executor.runNext(); assertEquals(CANCELLED, handle.result().toCompletableFuture().join().outcome());
        assertEquals(10, inventories.get(InventoryType.USE).getItem((short) 1).getQuantity()); verify(client, never()).sendPacket(any());
    }

    private NativeBasicResult run(NativeItemAction action) {
        var work = scope.acceptWork(); var handle = NativeItemActions.prepare(server, lane, permit, work, scene, action);
        NativeBasicActions.submit(handle); executor.runNext(); assertTrue(work.isSettled()); return handle.result().toCompletableFuture().join();
    }
    private Equip equip(int itemId, int slot, int watk) {
        Equip equip = new Equip(itemId, (short) slot); equip.setWatk((short) watk); return equip;
    }
    private MapItem ground(Item item, Point point) {
        MapItem ground = new MapItem(item, point, actor, actor, client, (byte) 2, true);
        ground.setObjectId(nextObject.incrementAndGet()); ground.setDropTime(System.currentTimeMillis() - 1000);
        objects.put(ground.getObjectId(), ground); return ground;
    }
    private StatEffect potion() {
        inventories.get(InventoryType.USE).addItemFromDB(new Item(2000000, (short) 1, (short) 10));
        StatEffect effect = mock(StatEffect.class); when(effect.getHp()).thenReturn((short) 50); when(effect.getStatups()).thenReturn(List.of());
        when(items.getItemEffect(2000000)).thenReturn(effect);
        when(effect.applyTo(actor)).thenAnswer(call -> { hp.set(Math.min(100, hp.get() + 50)); return true; });
        return effect;
    }
    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>(); private boolean shutdown;
        public int commandQueueCapacity() { return 8; }
        public void execute(Runnable command) { if (shutdown || queue.size() >= 8) throw new RejectedExecutionException(); queue.add(command); }
        void runNext() { queue.remove().run(); }
        public void shutdown() { shutdown = true; }
        public List<Runnable> shutdownNow() { throw new UnsupportedOperationException(); }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
