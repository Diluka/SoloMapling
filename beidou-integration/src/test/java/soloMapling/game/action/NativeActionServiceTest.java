package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.channel.handlers.GeneralChatHandler;
import org.gms.net.server.world.World;
import org.gms.server.ChatLogger;
import org.gms.server.maps.MapleMap;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native handler entry with an explicit host/clock/evidence seam, without a database or sockets. */
class NativeActionServiceTest {
    private final NativeActionService service = new NativeActionService();
    private Server server;
    private Character character;
    private Client client;
    private MapleMap map;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private GameContext.CharacterRef actor;
    private GameContext.SceneRef scene;
    private CharacterCommandLane.ControlPermit permit;
    private ManualExecutor executor;
    private MockedStatic<Server> serverStatic;
    private MockedStatic<ChatLogger> logs;

    @BeforeEach void fixture() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getCurrentTime()).thenReturn(1000L);
        serverStatic = mockStatic(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(server);
        logs = mockStatic(ChatLogger.class);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        PlayerStorage worldPlayers = mock(PlayerStorage.class);
        PlayerStorage channelPlayers = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        character = mock(Character.class);
        client = mock(Client.class);
        when(character.getId()).thenReturn(41);
        when(character.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(character);
        when(client.getChannel()).thenReturn(1);
        when(client.getLanguage()).thenReturn(3);
        when(client.isLoggedIn()).thenReturn(true);
        when(character.getAutoBanManager()).thenReturn(mock(AutobanManager.class));
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        map = mock(MapleMap.class);
        when(character.getMap()).thenReturn(map);
        when(map.getCharacterById(41)).thenReturn(character);
        actor = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), character, client, world, channel);
        scene = new GameContext.SceneRef(actor, map, null, null);
        scope = new ExecutionScope(actor.epoch(), new ExecutionScope.OwnerStamp(new Object(), new Object()),
                new ExecutionScope.Limits(16, 1, 8, 1));
        executor = new ManualExecutor();
        lane = new CharacterCommandLane(8, 1, executor);
        permit = lane.bindGrantedPermit(scope, actor, lane.declareGrant(scope),
                () -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @AfterEach void cleanup() {
        lane.close(); executor.shutdown();
        ThreadLocalUtil.removeCurrentClient();
        logs.close(); serverStatic.close(); WorldEpoch.onWorldStopping();
    }

    @Test void acceptanceNativeEntryAndOutcomeAreThreeDifferentFacts() {
        var work = scope.acceptWork();
        var action = prepare(work, "普通中文", ChatEvidenceSource.none());
        assertEquals(NativeActionService.SubmissionStatus.PREPARED, action.receipt().status());
        var receipt = service.submitChat(action);
        assertEquals(NativeActionService.SubmissionStatus.ENQUEUED, receipt.status());
        assertFalse(action.dispatchStarted());
        assertFalse(action.result().toCompletableFuture().isDone());
        assertFalse(work.isSettled());
        executor.runNext();
        var outcome = action.result().toCompletableFuture().join();
        assertEquals(NativeActionResult.Dispatch.STARTED, outcome.dispatch());
        assertEquals(NativeActionResult.Outcome.INDETERMINATE, outcome.outcome());
        assertEquals(ChatEvidence.Source.NONE, outcome.evidence().source());
        assertTrue(work.isSettled());
        verify(map, times(1)).broadcastMessage(any());
    }

    @Test void duplicateHandleAndDuplicateWorkCannotCreateASecondCarrier() {
        var work = scope.acceptWork();
        var first = prepare(work, "第一条", ChatEvidenceSource.none());
        var receipt = service.submitChat(first);
        assertSame(receipt, service.submitChat(first));
        var conflicting = prepare(work, "不同请求", ChatEvidenceSource.none());
        var rejected = new NativeActionService().submitChat(conflicting);
        assertEquals(NativeActionService.SubmissionRejection.WORK_ALREADY_BOUND, rejected.rejection());
        assertEquals("native_action_work_already_bound", conflicting.result().toCompletableFuture().join().fixedReason());
        assertFalse(work.cancelRequested());
        assertFalse(work.isSettled());
        assertEquals(1, executor.queue.size());
        executor.runNext();
        assertSame(receipt, service.submitChat(first));
        service.submitChat(prepare(scope.acceptWork(), "第一条", ChatEvidenceSource.none()));
        executor.runNext();
        verify(map, times(2)).broadcastMessage(any());
    }

    @Test void synchronousRejectionCallbackCanResubmitWithoutWaitingForItsOwnReceipt() {
        var action = prepare(scope.acceptWork(), "拒绝", ChatEvidenceSource.none());
        AtomicReference<NativeActionService.SubmissionReceipt> reentrant = new AtomicReference<>();
        AtomicReference<NativeActionService.SubmissionStatus> during = new AtomicReference<>();
        action.result().thenAccept(result -> {
            reentrant.set(service.submitChat(action));
            during.set(reentrant.get().status());
        });
        executor.reject = true;
        var receipt = service.submitChat(action);
        assertSame(receipt, reentrant.get());
        assertEquals(NativeActionService.SubmissionStatus.SUBMITTING, during.get());
        assertEquals(NativeActionService.SubmissionStatus.REJECTED, receipt.status());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, action.result().toCompletableFuture().join().dispatch());
        assertFalse(action.dispatchStarted());
    }

    @Test void aRealAsynchronousCarrierMayFinishBeforeTheOriginalSubmitReturnsWithoutReentryWaiting() throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        ThreadPoolExecutor early = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8)) {
            @Override public void execute(Runnable command) {
                super.execute(command);
                try { assertTrue(exited.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
            @Override protected void afterExecute(Runnable task, Throwable failure) { exited.countDown(); }
        };
        CharacterCommandLane asyncLane = new CharacterCommandLane(8, 1, early);
        try {
            var asyncPermit = asyncLane.bindGrantedPermit(scope, actor, asyncLane.declareGrant(scope), () -> { });
            var work = scope.acceptWork();
            var action = service.prepareChat(server, asyncLane, asyncPermit, work, scene,
                    new NativeChatAction("不会派发"), ChatEvidenceSource.none());
            AtomicReference<NativeActionService.SubmissionReceipt> reentrant = new AtomicReference<>();
            AtomicReference<NativeActionService.SubmissionStatus> during = new AtomicReference<>();
            action.result().thenAccept(result -> {
                reentrant.set(service.submitChat(action)); during.set(reentrant.get().status());
            });
            scope.requestCancel(work); // Avoid thread-local host mocks; this test owns only carrier ordering.
            var receipt = service.submitChat(action);
            assertSame(receipt, reentrant.get());
            assertEquals(NativeActionService.SubmissionStatus.SUBMITTING, during.get());
            assertEquals(NativeActionService.SubmissionStatus.ENQUEUED, receipt.status());
            assertEquals(NativeActionResult.Outcome.CANCELLED, action.result().toCompletableFuture().join().outcome());
        } finally {
            asyncLane.close(); early.shutdown(); assertTrue(early.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledQueuedWorkCompletesWithoutEnteringTheHandlerAndOnlySettlesAfterDeparture() {
        var work = scope.acceptWork();
        var action = prepare(work, "取消", ChatEvidenceSource.none());
        service.submitChat(action);
        scope.requestCancel(work);
        assertFalse(work.isSettled());
        assertFalse(action.result().toCompletableFuture().isDone());
        executor.runNext();
        assertEquals(NativeActionResult.Outcome.CANCELLED, action.result().toCompletableFuture().join().outcome());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, action.result().toCompletableFuture().join().dispatch());
        assertTrue(work.isSettled());
        verify(map, never()).broadcastMessage(any());
    }

    @Test void invalidNativeStateRestoresThePreviousClientAndDoesNotClaimNativeEntry() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        when(client.isLoggedIn()).thenReturn(false);
        var action = prepare(scope.acceptWork(), "未登录", ChatEvidenceSource.none());
        service.submitChat(action); executor.runNext();
        var result = action.result().toCompletableFuture().join();
        assertEquals("native_packet_state_rejected", result.fixedReason());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, result.dispatch());
        assertFalse(action.dispatchStarted());
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        verify(map, never()).broadcastMessage(any());
    }

    @Test void handlerErrorAfterNativeBroadcastCannotErasePositiveEvidenceOrThePreviousClient() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        logs.when(() -> ChatLogger.log(client, "General", "事实保留")).thenThrow(new AssertionError("private detail"));
        var action = prepare(scope.acceptWork(), "事实保留", positive(actor.epoch().value(), false));
        service.submitChat(action); executor.runNext();
        var result = action.result().toCompletableFuture().join();
        assertEquals(NativeActionResult.Outcome.SUCCEEDED, result.outcome());
        assertTrue(result.handlerError());
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertFalse(result.toString().contains("事实保留"));
        assertFalse(result.toString().contains("private detail"));
    }

    @Test void handlerErrorWithoutEvidenceRemainsStartedAndUnknown() {
        logs.when(() -> ChatLogger.log(client, "General", "未知")).thenThrow(new AssertionError("private detail"));
        var action = prepare(scope.acceptWork(), "未知", ChatEvidenceSource.none());
        service.submitChat(action); executor.runNext();
        var result = action.result().toCompletableFuture().join();
        assertEquals(NativeActionResult.Outcome.INDETERMINATE, result.outcome());
        assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch());
        assertTrue(result.handlerError());
    }

    @Test void nativePacketBufferIsActuallyReleasedAfterTheHandlerThrows() {
        AtomicReference<io.netty.buffer.ByteBuf> bytes = new AtomicReference<>();
        try (MockedConstruction<GeneralChatHandler> handlers = mockConstruction(GeneralChatHandler.class,
                (handler, context) -> {
                    when(handler.validateState(any())).thenReturn(true);
                    doAnswer(call -> {
                        bytes.set((io.netty.buffer.ByteBuf) field(call.getArgument(0), "byteBuf"));
                        throw new AssertionError("native handler failure");
                    }).when(handler).handlePacket(any(), any());
                })) {
            var action = prepare(scope.acceptWork(), "资源释放", ChatEvidenceSource.none());
            service.submitChat(action); executor.runNext();
            assertNotNull(bytes.get());
            assertEquals(0, bytes.get().refCnt());
            assertEquals(NativeActionResult.Dispatch.STARTED, action.result().toCompletableFuture().join().dispatch());
            assertEquals(NativeActionResult.Outcome.INDETERMINATE, action.result().toCompletableFuture().join().outcome());
        }
    }

    @Test void probeCloseErrorCannotEraseAlreadyMatchedEvidence() {
        var action = prepare(scope.acceptWork(), "清理异常", positive(actor.epoch().value(), true));
        service.submitChat(action); executor.runNext();
        assertEquals(NativeActionResult.Outcome.SUCCEEDED, action.result().toCompletableFuture().join().outcome());
    }

    @Test void aMatchedPacketFromAnotherEpochCannotProveThisAction() {
        var action = prepare(scope.acceptWork(), "错代次", positive("old-epoch", false));
        service.submitChat(action); executor.runNext();
        assertEquals(NativeActionResult.Outcome.INDETERMINATE, action.result().toCompletableFuture().join().outcome());
        assertEquals(ChatEvidence.Source.NONE, action.result().toCompletableFuture().join().evidence().source());
    }

    @Test void cancellationAfterEntryDoesNotRetractAnObservedBroadcast() {
        var work = scope.acceptWork();
        logs.when(() -> ChatLogger.log(client, "General", "已经发出")).thenAnswer(call -> {
            scope.requestCancel(work); return null;
        });
        var action = prepare(work, "已经发出", positive(actor.epoch().value(), false));
        service.submitChat(action); executor.runNext();
        var result = action.result().toCompletableFuture().join();
        assertEquals(NativeActionResult.Outcome.SUCCEEDED, result.outcome());
        assertTrue(result.cancelRequested());
        assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch());
    }

    @Test void resultConsumersSeeNoStrongPendingInputsAndOnlyAWeakOriginalWork() throws Exception {
        var action = prepare(scope.acceptWork(), "不能留在终态", positive(actor.epoch().value(), false));
        AtomicReference<Object> retained = new AtomicReference<>(new Object());
        action.result().thenAccept(result -> {
            try { retained.set(field(action, "pending")); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        service.submitChat(action); executor.runNext();
        assertNull(((AtomicReference<?>) retained.get()).get());
        assertInstanceOf(WeakReference.class, field(action, "work"));
        assertFalse(action.result().toCompletableFuture().join().toString().contains("不能留在终态"));
    }

    @Test void cleanupWorkIsRejectedBeforeItCanBeClaimedOrCancelledAsAnAction() {
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("fixture");
        registration.attach(identity, () -> { });
        var cleanup = registration.acquireCleanup();
        assertEquals("cleanup_work_not_an_action", assertThrows(NativeActionService.Invalid.class,
                () -> prepare(cleanup.work(), "无权", ChatEvidenceSource.none())).reason());
        assertFalse(cleanup.work().snapshot().carrierClaimed());
        assertTrue(scope.runTracked(cleanup.work(), () -> registration.confirmReleased(cleanup.permit(), identity)));
    }

    @Test void encodingAndOrdinaryTextValidationPreserveTheNativeUtf16Boundary() {
        assertDoesNotThrow(() -> NativeActionService.validateChat(actor, "中".repeat(127)));
        for (String invalid : new String[]{"", " ", "@help", "/help", "中".repeat(128)}) {
            assertEquals("invalid_chat_text", assertThrows(NativeActionService.Invalid.class,
                    () -> NativeActionService.validateChat(actor, invalid)).reason());
        }
        assertEquals("chat_text_not_encodable", assertThrows(NativeActionService.Invalid.class,
                () -> NativeActionService.validateChat(actor, "无法编码😀")).reason());
    }

    private NativeActionService.ActionHandle prepare(ExecutionScope.WorkHandle work, String text, ChatEvidenceSource source) {
        return service.prepareChat(server, lane, permit, work, scene, new NativeChatAction(text), source);
    }

    private static ChatEvidenceSource positive(String epoch, boolean closeError) {
        return (actor, action) -> new ChatEvidenceSource.Probe() {
            public ChatEvidence observe() { return new ChatEvidence(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET,
                    "test-feed", epoch, 0L, 1L, 1L, ChatEvidence.Completeness.COMPLETE, false); }
            public void close() { if (closeError) throw new AssertionError("private probe detail"); }
        };
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean stopped; boolean reject;
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
