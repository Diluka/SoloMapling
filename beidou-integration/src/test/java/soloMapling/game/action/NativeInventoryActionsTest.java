package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.channel.handlers.ItemMoveHandler;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.MapleMap;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;

/** Real ItemMoveHandler and Inventory, with explicit host/clock/item-metadata seams. */
class NativeInventoryActionsTest {
    private final NativeInventoryActions service = new NativeInventoryActions();
    private Server server;
    private Character character;
    private Client client;
    private MapleMap map;
    private Inventory inventory;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private GameContext.SceneRef scene;
    private CharacterCommandLane.ControlPermit permit;
    private ManualExecutor executor;
    private MockedStatic<Server> serverStatic;
    private MockedStatic<ItemInformationProvider> itemStatic;

    @BeforeEach void fixture() throws Exception {
        InventoryMetadataTestContext.install(); WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getCurrentTime()).thenReturn(1000L);
        serverStatic = mockStatic(Server.class); serverStatic.when(Server::getInstance).thenReturn(server);
        ItemInformationProvider items = mock(ItemInformationProvider.class);
        when(items.getSlotMax(any(), anyInt())).thenReturn((short) 100);
        itemStatic = mockStatic(ItemInformationProvider.class); itemStatic.when(ItemInformationProvider::getInstance).thenReturn(items);
        World world = mock(World.class); Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
        when(server.getWorld(0)).thenReturn(world); when(world.getChannel(1)).thenReturn(channel);
        PlayerStorage worldPlayers = mock(PlayerStorage.class), channelPlayers = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(worldPlayers); when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        character = mock(Character.class); client = mock(Client.class);
        when(character.getId()).thenReturn(41); when(character.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(character); when(client.getChannel()).thenReturn(1);
        when(client.isLoggedIn()).thenReturn(true);
        when(character.getAutoBanManager()).thenReturn(mock(AutobanManager.class));
        when(worldPlayers.getCharacterById(41)).thenReturn(character); when(channelPlayers.getCharacterById(41)).thenReturn(character);
        map = mock(MapleMap.class); when(character.getMap()).thenReturn(map); when(map.getCharacterById(41)).thenReturn(character);
        inventory = new Inventory(character, InventoryType.USE, (byte) 24);
        when(character.getInventory(InventoryType.USE)).thenReturn(inventory);
        inventory.addItemFromDB(new Item(2000000, (short) 1, (short) 10));
        var actor = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), character, client, world, channel);
        scene = new GameContext.SceneRef(actor, map, null, null);
        scope = new ExecutionScope(actor.epoch(), new ExecutionScope.Limits(16, 1, 8, 1));
        executor = new ManualExecutor(); lane = new CharacterCommandLane(8, 1, executor);
        permit = lane.bindGrantedPermit(scope, actor, lane.declareGrant(scope), () -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @AfterEach void cleanup() {
        if (lane != null) lane.close(); if (executor != null) executor.shutdown(); ThreadLocalUtil.removeCurrentClient();
        if (itemStatic != null) itemStatic.close(); if (serverStatic != null) serverStatic.close(); ExecutionScope.tryBeginWorldStop();
    }

    @Test void nativeMoveHasImmutableFactsAndConsumerCompletionPrecedesRealSettlement() {
        var work = scope.acceptWork(); var handle = prepare(work);
        AtomicReference<Boolean> settledAtResult = new AtomicReference<>();
        handle.result().thenAccept(result -> settledAtResult.set(work.isSettled()));
        assertEquals(NativeActionService.SubmissionStatus.PREPARED, handle.receipt().status());
        service.submitMove(handle);
        assertFalse(handle.dispatchStarted()); assertFalse(work.isSettled()); assertFalse(handle.result().toCompletableFuture().isDone());
        executor.runNext(); var result = handle.result().toCompletableFuture().join();
        assertEquals(SUCCEEDED, result.outcome()); assertTrue(result.evidence().identityVerified());
        assertEquals(10, result.evidence().before().source().quantity());
        assertFalse(result.evidence().before().target().present());
        assertFalse(result.evidence().after().source().present());
        assertEquals(10, result.evidence().after().target().quantity());
        assertFalse(settledAtResult.get()); assertTrue(work.isSettled());
        inventory.getItem((short) 2).setQuantity((short) 99);
        assertEquals(10, result.evidence().after().target().quantity());
        verify(client).sendPacket(any());
    }

    @Test void realNativeSwapPreservesBothObjectsAndQuantities() {
        Item source = inventory.getItem((short) 1), target = new Item(2000001, (short) 2, (short) 3);
        inventory.addItemFromDB(target);
        assertEquals(SUCCEEDED, run().outcome());
        assertSame(target, inventory.getItem((short) 1)); assertSame(source, inventory.getItem((short) 2));
    }

    @Test void realNativeMergeAndOverflowAreObservedRatherThanSimulated() {
        inventory.addItemFromDB(new Item(2000000, (short) 2, (short) 95));
        InventoryMoveResult overflow = run();
        assertEquals(SUCCEEDED, overflow.outcome());
        assertEquals(5, overflow.evidence().after().source().quantity()); assertEquals(100, overflow.evidence().after().target().quantity());
        inventory.getItem((short) 2).setQuantity((short) 20);
        InventoryMoveResult merged = run();
        assertEquals(SUCCEEDED, merged.outcome()); assertFalse(merged.evidence().after().source().present());
        assertEquals(25, merged.evidence().after().target().quantity());
    }

    @Test void nativeSpamClockMayRejectDispatchWithoutMovingAnything() {
        when(character.getAutoBanManager().getLastSpam(6)).thenReturn(900L);
        InventoryMoveResult result = run();
        assertEquals(FAILED, result.outcome()); assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch());
        assertEquals("native_inventory_unchanged", result.fixedReason()); assertFalse(result.evidence().changed());
        assertNotNull(inventory.getItem((short) 1)); assertNull(inventory.getItem((short) 2));
    }

    @Test void duplicateHandleAndDuplicateWorkNeverCreateAnotherCarrier() {
        var work = scope.acceptWork(); var first = prepare(work); var receipt = service.submitMove(first);
        assertSame(receipt, service.submitMove(first));
        var duplicate = prepare(work);
        assertEquals(NativeActionService.SubmissionRejection.WORK_ALREADY_BOUND, service.submitMove(duplicate).rejection());
        assertEquals(1, executor.queue.size()); assertFalse(work.cancelRequested()); assertFalse(work.isSettled());
        executor.runNext(); assertEquals(SUCCEEDED, first.result().toCompletableFuture().join().outcome());
        verify(client, times(1)).sendPacket(any());
    }

    @Test void queuedCancellationSettlesOnlyAfterActualCarrierDeparture() {
        var work = scope.acceptWork(); var handle = prepare(work); service.submitMove(handle); scope.requestCancel(work);
        assertFalse(work.isSettled()); assertFalse(handle.result().toCompletableFuture().isDone());
        executor.runNext(); assertEquals(CANCELLED, handle.result().toCompletableFuture().join().outcome());
        assertFalse(handle.dispatchStarted()); assertTrue(work.isSettled()); verify(client, never()).sendPacket(any());
    }

    @Test void nativeStateRejectionRestoresPreviousClientAndPreservesSlots() {
        Client previous = mock(Client.class); ThreadLocalUtil.setCurrentClient(previous); when(client.isLoggedIn()).thenReturn(false);
        InventoryMoveResult result = run();
        assertEquals("native_packet_state_rejected", result.fixedReason());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, result.dispatch()); assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertNotNull(inventory.getItem((short) 1)); assertNull(inventory.getItem((short) 2));
    }

    @Test void handlerFailureAfterRealChangeCannotEraseObservedFactsOrClaimRetraction() {
        var work = scope.acceptWork(); var handle = prepare(work);
        doAnswer(call -> { scope.requestCancel(work); throw new AssertionError("private exception"); }).when(client).sendPacket(any());
        service.submitMove(handle); executor.runNext(); InventoryMoveResult result = handle.result().toCompletableFuture().join();
        assertEquals(SUCCEEDED, result.outcome()); assertTrue(result.handlerError()); assertTrue(result.cancelRequested());
        assertFalse(result.toString().contains("private exception"));
    }

    @Test void completedMoveFactsSurviveWorldStopWithoutRefreshingTheirEpoch() {
        doAnswer(call -> { ExecutionScope.tryBeginWorldStop(); return null; }).when(client).sendPacket(any());
        InventoryMoveResult result = run();
        assertEquals(SUCCEEDED, result.outcome()); assertTrue(result.evidence().identityVerified());
        assertEquals(scene.actor().epoch().value(), result.epoch()); assertFalse(WorldEpoch.snapshot().active());
    }

    @Test void completedMoveFactsSurviveSceneChangeWithoutGrantingTheOldSceneNewControl() {
        MapleMap next = mock(MapleMap.class);
        doAnswer(call -> { when(character.getMap()).thenReturn(next); return null; }).when(client).sendPacket(any());
        InventoryMoveResult result = run();
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(10, result.evidence().after().target().quantity());
        assertSame(next, character.getMap());
        assertThrows(GameContext.Invalid.class, () -> NativeContextChecks.requireSceneCurrent(server, scene));
    }

    @Test void handlerFailureWithoutChangeIsUnknownAndAlwaysReleasesNativePacket() {
        Client previous = mock(Client.class); ThreadLocalUtil.setCurrentClient(previous);
        AtomicReference<io.netty.buffer.ByteBuf> bytes = new AtomicReference<>();
        try (MockedConstruction<ItemMoveHandler> handlers = mockConstruction(ItemMoveHandler.class, (handler, context) -> {
            when(handler.validateState(any())).thenReturn(true);
            doAnswer(call -> { bytes.set((io.netty.buffer.ByteBuf) field(call.getArgument(0), "byteBuf"));
                throw new AssertionError("native handler failure"); }).when(handler).handlePacket(any(), any());
        })) {
            InventoryMoveResult result = run();
            assertEquals(INDETERMINATE, result.outcome()); assertTrue(result.handlerError());
            assertEquals(0, bytes.get().refCnt()); assertSame(previous, ThreadLocalUtil.getCurrentClient());
        }
    }

    @Test void replacedNativeInventoryCannotSupplyPositiveIdentityEvidence() {
        doAnswer(call -> { when(character.getInventory(InventoryType.USE)).thenReturn(new Inventory(character, InventoryType.USE, (byte) 24));
            return null; }).when(client).sendPacket(any());
        InventoryMoveResult result = run();
        assertEquals(INDETERMINATE, result.outcome()); assertFalse(result.evidence().identityVerified());
        assertEquals(10, result.evidence().after().target().quantity());
    }

    @Test void noStrongNativeInputsSurviveResultCallbacksAndSynchronousReentryDoesNotWait() throws Exception {
        var handle = prepare(scope.acceptWork()); executor.reject = true;
        AtomicReference<NativeActionService.SubmissionReceipt> reentrant = new AtomicReference<>();
        var callback = handle.result().thenAccept(result -> { reentrant.set(service.submitMove(handle));
            assertEquals(NativeActionService.SubmissionStatus.SUBMITTING, reentrant.get().status());
            try { assertNull(((AtomicReference<?>) field(handle, "pending")).get()); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertSame(service.submitMove(handle), reentrant.get());
        callback.toCompletableFuture().join();
        assertInstanceOf(WeakReference.class, field(handle, "work"));
        assertEquals(NativeActionService.SubmissionStatus.REJECTED, handle.receipt().status());
    }

    @Test void onlyPositiveDistinctOrdinaryInventorySlotsCanReachTheMoveHandler() {
        for (String type : new String[]{"EQUIPPED", "UNDEFINED", "CANHOLD", "use", "", null}) {
            assertThrows(NativeActionService.Invalid.class, () -> new NativeInventoryMoveAction(type, 1, 2));
        }
        for (int[] slots : new int[][]{{0, 2}, {1, 0}, {-1, 2}, {1, -1}, {1, 1}, {128, 2}, {1, 128}}) {
            assertThrows(NativeActionService.Invalid.class, () -> new NativeInventoryMoveAction("USE", slots[0], slots[1]));
        }
        var handle = service.prepareMove(server, lane, permit, scope.acceptWork(), scene, new NativeInventoryMoveAction("USE", 1, 25));
        service.submitMove(handle); executor.runNext(); assertFalse(handle.dispatchStarted());
        assertEquals("inventory_slot_out_of_range", handle.result().toCompletableFuture().join().fixedReason());
    }

    private NativeInventoryActions.MoveHandle prepare(ExecutionScope.WorkHandle work) {
        return service.prepareMove(server, lane, permit, work, scene, new NativeInventoryMoveAction("USE", 1, 2));
    }
    private InventoryMoveResult run() { var handle = prepare(scope.acceptWork()); service.submitMove(handle); executor.runNext(); return handle.result().toCompletableFuture().join(); }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>(); boolean stopped; boolean reject;
        public int commandQueueCapacity() { return 8; }
        public void execute(Runnable command) { if (stopped || reject) throw new RejectedExecutionException(); queue.add(command); }
        void runNext() { queue.remove().run(); }
        public void shutdown() { stopped = true; }
        public java.util.List<Runnable> shutdownNow() { stopped = true; return java.util.List.copyOf(queue); }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped && queue.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
