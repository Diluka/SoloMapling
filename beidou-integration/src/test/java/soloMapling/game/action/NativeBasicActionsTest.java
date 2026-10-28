package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.PacketHandler;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.game.action.NativeActionResult.Dispatch.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;

/** Shared command contracts; native effect seams are explicit and never represent gameplay acceptance. */
class NativeBasicActionsTest {
    private Server server;
    private Character character;
    private Client client;
    private GameContext.CharacterRef actor;
    private GameContext.SceneRef scene;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private CharacterCommandLane.ControlPermit permit;
    private ManualExecutor executor;

    @BeforeEach void fixture() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        PlayerStorage worldPlayers = mock(PlayerStorage.class), channelPlayers = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        character = mock(Character.class);
        client = mock(Client.class);
        when(character.getId()).thenReturn(41);
        when(character.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(character);
        when(client.getChannel()).thenReturn(1);
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        MapleMap map = mock(MapleMap.class);
        when(character.getMap()).thenReturn(map);
        when(map.getCharacterById(41)).thenReturn(character);
        actor = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), character, client, world, channel);
        scene = new GameContext.SceneRef(actor, map, null, null);
        scope = new ExecutionScope(actor.epoch(), new ExecutionScope.Limits(16, 1, 8, 1));
        executor = new ManualExecutor();
        lane = new CharacterCommandLane(8, 1, executor);
        permit = lane.bindGrantedPermit(scope, actor, lane.declareGrant(scope),
                () -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @AfterEach void cleanup() {
        if (lane != null) lane.close();
        if (executor != null) executor.shutdown();
        ThreadLocalUtil.removeCurrentClient();
        ExecutionScope.tryBeginWorldStop();
    }

    @Test void onePreparedHandleAndWorkOwnExactlyOneCarrierAndOneNativeEffect() {
        AtomicInteger effects = new AtomicInteger();
        var work = scope.acceptWork();
        var first = prepare(work, effect(effects));
        var receipt = NativeBasicActions.submit(first);
        assertSame(receipt, NativeBasicActions.submit(first));
        var duplicateWork = prepare(work, effect(effects));
        assertEquals(NativeActionService.SubmissionRejection.WORK_ALREADY_BOUND,
                NativeBasicActions.submit(duplicateWork).rejection());
        assertEquals(1, executor.queue.size());
        assertFalse(work.isSettled());
        assertFalse(work.cancelRequested());
        assertEquals(0, effects.get());
        executor.runNext();
        assertEquals(SUCCEEDED, first.result().toCompletableFuture().join().outcome());
        assertEquals(FAILED, duplicateWork.result().toCompletableFuture().join().outcome());
        assertTrue(work.isSettled());
        assertEquals(1, effects.get());
        assertSame(receipt, NativeBasicActions.submit(first));
        assertTrue(executor.queue.isEmpty());
    }

    @Test void resultCompletionIsNotPhysicalCarrierSettlement() {
        var work = scope.acceptWork();
        var handle = prepare(work, effect(new AtomicInteger()));
        var callback = handle.result().thenAccept(result -> {
            assertEquals(SUCCEEDED, result.outcome());
            assertFalse(work.isSettled());
            assertEquals(1, lane.snapshot().pending());
        });
        NativeBasicActions.submit(handle);
        executor.runNext();
        callback.toCompletableFuture().join();
        assertTrue(work.isSettled());
        assertEquals(0, lane.snapshot().pending());
    }

    @Test void queuedCancellationKeepsThePhysicalSlotUntilTheCarrierLeavesWithoutAnEffect() {
        AtomicInteger effects = new AtomicInteger();
        var work = scope.acceptWork();
        var handle = prepare(work, effect(effects));
        NativeBasicActions.submit(handle);
        scope.requestCancel(work);
        assertEquals(1, lane.snapshot().pending());
        assertFalse(work.isSettled());
        assertFalse(handle.result().toCompletableFuture().isDone());
        executor.runNext();
        var result = handle.result().toCompletableFuture().join();
        assertEquals(CANCELLED, result.outcome());
        assertEquals(NOT_STARTED, result.dispatch());
        assertTrue(result.cancelRequested());
        assertEquals(0, effects.get());
        assertTrue(work.isSettled());
        assertEquals(0, lane.snapshot().pending());
    }

    @Test void revokedGrantRejectsNewSubmissionWithoutOccupyingTheLane() {
        AtomicInteger effects = new AtomicInteger();
        var work = scope.acceptWork();
        var handle = prepare(work, effect(effects));
        permit.revoke();
        assertEquals(NativeActionService.SubmissionRejection.PERMIT, NativeBasicActions.submit(handle).rejection());
        assertEquals(NOT_STARTED, handle.result().toCompletableFuture().join().dispatch());
        assertTrue(work.isSettled());
        assertTrue(executor.queue.isEmpty());
        assertEquals(0, lane.snapshot().pending());
        assertEquals(0, effects.get());
    }

    @Test void validationAndActualHandlerEntryStraddleTheDispatchCheckpoint() {
        var work = scope.acceptWork();
        AtomicReference<NativeBasicActions.CommandHandle> reference = new AtomicReference<>();
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        ByteBuf packet = Unpooled.buffer().writeIntLE(123);
        PacketHandler handler = mock(PacketHandler.class);
        when(handler.validateState(client)).thenAnswer(call -> {
            assertSame(client, ThreadLocalUtil.getCurrentClient());
            assertFalse(reference.get().dispatchStarted());
            return true;
        });
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            assertTrue(reference.get().dispatchStarted());
            assertSame(client, ThreadLocalUtil.getCurrentClient());
            changed.set(true);
            return null;
        }).when(handler).handlePacket(any(), same(client));
        var handle = prepare(work, context -> {
            context.dispatch(handler, packet);
            return context.result(SUCCEEDED, null, facts(changed.get()));
        });
        reference.set(handle);
        NativeBasicActions.submit(handle);
        assertFalse(handle.dispatchStarted());
        executor.runNext();
        assertEquals(STARTED, handle.result().toCompletableFuture().join().dispatch());
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertEquals(0, packet.refCnt());
        verify(handler).handlePacket(any(), same(client));
    }

    @Test void nativeStateRejectionNeverEntersHandlerAndAlwaysRestoresTheClientAndPacket() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        PacketHandler handler = mock(PacketHandler.class);
        ByteBuf packet = Unpooled.buffer().writeIntLE(1);
        var handle = prepare(scope.acceptWork(), context -> {
            context.dispatch(handler, packet);
            return context.result(SUCCEEDED, null, facts(true));
        });
        NativeBasicActions.submit(handle);
        executor.runNext();
        var result = handle.result().toCompletableFuture().join();
        assertEquals(FAILED, result.outcome());
        assertEquals(NOT_STARTED, result.dispatch());
        assertEquals("native_packet_state_rejected", result.fixedReason());
        assertFalse(handle.dispatchStarted());
        verify(handler, never()).handlePacket(any(), any());
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertEquals(0, packet.refCnt());
    }

    @Test void aRealObservedEffectSurvivesALaterHandlerFailureAndCancellation() {
        var work = scope.acceptWork();
        PacketHandler handler = mock(PacketHandler.class);
        when(handler.validateState(client)).thenReturn(true);
        AtomicInteger value = new AtomicInteger();
        doAnswer(call -> {
            value.incrementAndGet();
            scope.requestCancel(work);
            throw new AssertionError("private native failure");
        }).when(handler).handlePacket(any(), any());
        ByteBuf packet = Unpooled.buffer().writeByte(1);
        var handle = prepare(work, context -> {
            context.dispatch(handler, packet);
            return context.result(SUCCEEDED, null, facts(value.get() == 1));
        });
        NativeBasicActions.submit(handle);
        executor.runNext();
        var result = handle.result().toCompletableFuture().join();
        assertEquals(SUCCEEDED, result.outcome());
        assertEquals(STARTED, result.dispatch());
        assertTrue(result.handlerError());
        assertTrue(result.cancelRequested());
        assertEquals(1, value.get());
        assertEquals(0, packet.refCnt());
        assertNull(ThreadLocalUtil.getCurrentClient());
        assertFalse(result.toString().contains("private native failure"));
    }

    @Test void cancellationRecheckedAfterNativeEntryCannotRewriteItAsNotDispatched() {
        var work = scope.acceptWork();
        PacketHandler handler = mock(PacketHandler.class);
        when(handler.validateState(client)).thenReturn(true);
        doAnswer(call -> { scope.requestCancel(work); return null; }).when(handler).handlePacket(any(), any());
        var handle = prepare(work, context -> {
            context.dispatch(handler, Unpooled.buffer().writeByte(1));
            context.requireCurrent();
            return context.result(SUCCEEDED, null, facts(true));
        });
        NativeBasicActions.submit(handle);
        executor.runNext();
        var result = handle.result().toCompletableFuture().join();
        assertTrue(handle.dispatchStarted());
        assertEquals(STARTED, result.dispatch());
        assertEquals(INDETERMINATE, result.outcome());
        assertTrue(result.cancelRequested());
    }

    @Test void terminalCallbacksRetainNoNativeInputsAndCanReenterTheSameReceiptWithoutWaiting() throws Exception {
        Object capturedInput = new Object();
        var handle = prepare(scope.acceptWork(), context -> {
            assertNotNull(capturedInput);
            throw new IllegalStateException("private input should not survive");
        });
        executor.reject = true;
        AtomicReference<NativeActionService.SubmissionReceipt> reentrant = new AtomicReference<>();
        var callback = handle.result().thenAccept(result -> {
            reentrant.set(NativeBasicActions.submit(handle));
            assertEquals(NativeActionService.SubmissionStatus.SUBMITTING, reentrant.get().status());
            try { assertNull(((AtomicReference<?>) field(handle, "pending")).get()); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            assertFalse(result.toString().contains("private input"));
        });
        assertSame(NativeBasicActions.submit(handle), reentrant.get());
        callback.toCompletableFuture().join();
        assertInstanceOf(WeakReference.class, field(handle, "work"));
        assertEquals(NativeActionService.SubmissionStatus.REJECTED, handle.receipt().status());
    }

    @Test void cleanupWorkAndAForeignCharacterReferenceCannotPrepareANativeCommand() {
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("fixture");
        registration.attach(identity, () -> { });
        var cleanup = registration.acquireCleanup();
        assertEquals("cleanup_work_not_an_action", assertThrows(NativeActionService.Invalid.class,
                () -> prepare(cleanup.work(), effect(new AtomicInteger()))).reason());
        assertFalse(cleanup.work().snapshot().carrierClaimed());
        assertTrue(scope.runTracked(cleanup.work(), () -> registration.confirmReleased(cleanup.permit(), identity)));
        var lookalike = new GameContext.CharacterRef(actor.epoch(), character, client, actor.world(), actor.channel());
        var wrongScene = new GameContext.SceneRef(lookalike, scene.map(), null, null);
        var work = scope.acceptWork();
        assertEquals("action_context_mismatch", assertThrows(NativeActionService.Invalid.class,
                () -> NativeBasicActions.prepare(server, lane, permit, work, wrongScene, "pickup",
                        effect(new AtomicInteger()))).reason());
        assertFalse(work.snapshot().carrierClaimed());
        work.reject();
    }

    private NativeBasicActions.CommandHandle prepare(ExecutionScope.WorkHandle work, NativeBasicActions.Operation operation) {
        return NativeBasicActions.prepare(server, lane, permit, work, scene, "pickup", operation);
    }

    private NativeBasicActions.Operation effect(AtomicInteger counter) {
        PacketHandler handler = mock(PacketHandler.class);
        when(handler.validateState(client)).thenReturn(true);
        doAnswer(call -> { counter.incrementAndGet(); return null; }).when(handler).handlePacket(any(), any());
        return context -> {
            int before = counter.get();
            context.dispatch(handler, Unpooled.buffer().writeByte(1));
            return context.result(SUCCEEDED, null, facts(counter.get() == before + 1));
        };
    }

    private static Map<String, Object> facts(boolean changed) {
        return Map.of("effect_observed", changed, "evidence_source", "NATIVE_TEST_EFFECT");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean stopped;
        private boolean reject;
        @Override public int commandQueueCapacity() { return 8; }
        @Override public void execute(Runnable command) {
            if (stopped || reject) throw new RejectedExecutionException();
            queue.add(command);
        }
        void runNext() { queue.remove().run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return List.copyOf(queue); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
