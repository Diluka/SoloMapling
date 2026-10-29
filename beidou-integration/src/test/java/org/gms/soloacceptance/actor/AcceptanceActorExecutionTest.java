package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.soloacceptance.ObservationException;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.action.ChatEvidenceSource;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.population.RobotPopulationService;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual shared scopes and one physical bounded FIFO; native persistence is an explicit mocked boundary. */
class AcceptanceActorExecutionTest {
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private World world;
    private Channel channel;
    private AcceptanceClient client;
    private Character character;
    private ThreadPoolExecutor executor;
    private AcceptanceActorExecution execution;
    private boolean expectedUnreleasedOrigin;

    @BeforeEach
    void oneSessionAndOneRealSerialQueue() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        backend = mock(NativeActorBackend.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        client = mock(AcceptanceClient.class);
        character = mock(Character.class);
        when(character.getId()).thenReturn(73);
        handle = handleAtCurrentEpoch();
        when(backend.isCurrent(any())).thenReturn(true);
        doAnswer(invocation -> {
            NativeActorHandle exact = invocation.getArgument(0);
            ScopeResources.DrainPermit permit = invocation.getArgument(1);
            permit.requireExecuting(exact);
            return verifiedCleanup();
        }).when(backend).close(any(), any());
        executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                Thread.ofVirtual().uncaughtExceptionHandler((thread, failure) -> { }).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        RobotPopulationService population = new RobotPopulationService(new StandardEnvironment());
        population.onWorldStarted();
        execution = new AcceptanceActorExecution(backend, "helper-module", WorldEpoch.snapshot().token(), executor, population);
    }

    @AfterEach
    void stopOnlyThisFixtureWithoutInventingResourceRelease() throws Exception {
        try {
            execution.beginDrain(ExecutionScope.tryBeginWorldStop());
            if (!execution.resourceReleased() && execution.adoptedHandle() != null) {
                try {
                    NativeActorHandle exactHandle = execution.adoptedHandle();
                    ScopeResources.CleanupAttempt cleanup = execution.cleanupAttempt();
                    execution.submitCleanup(cleanup, () -> execution.cleanup(cleanup, exactHandle), () -> { });
                    await(cleanup.work());
                } catch (RuntimeException unavailable) {
                    if (!expectedUnreleasedOrigin) throw unavailable;
                }
            }
            execution.closeLane();
            execution.finalized();
        } finally {
            if (!executor.isShutdown()) {
                execution.refresh();
                Map<String, Object> state = execution.diagnostics();
                Map<?, ?> lane = (Map<?, ?>) state.get("lane");
                if ("CLOSED".equals(state.get("state"))) {
                    // The host publishes CLOSED before shutting its executor down outside the host lock.
                    // A concurrent finalizer can observe that legitimate interval; termination is awaited below.
                    assertTrue(execution.resourceReleased());
                    assertTrue(execution.scope().snapshot().unloadComplete());
                    assertEquals(true, lane.get("closed"));
                } else {
                    // Unknown native ownership remains visible. Only terminate this completed test's private executor;
                    // this does not prove root close or erase its outstanding resource registration.
                    assertEquals("CLOSING", state.get("state"));
                    assertEquals(false, lane.get("closed"));
                    assertTrue(executor.getQueue().isEmpty());
                    assertEquals(0, execution.scope().snapshot().acceptedWork());
                    executor.shutdown();
                }
            }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            ExecutionScope.tryBeginWorldStop();
        }
    }

    @Test
    void pendingRegistrationPrecedesNativeCreationAndAnAdoptedFailureRetainsTheExactResource() throws Exception {
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        AtomicReference<NativeActorHandle> atFailure = new AtomicReference<>();
        AtomicInteger settled = new AtomicInteger();
        assertEquals(CharacterCommandLane.Status.ENQUEUED, execution.submitOpen(open, () -> {
            NativeActorBackend.OpenOwnership ownership = execution.beginOpen(open);
            assertEquals(1, execution.scope().resources().snapshot().pending());
            ownership.bindOrigin(world, channel);
            ownership.adopt(handle);
            assertEquals(0, execution.scope().resources().snapshot().pending());
            assertEquals(1, execution.scope().resources().snapshot().attached());
            atFailure.set(execution.adoptedHandle());
            throw new IllegalStateException("native-open-failed-after-adoption");
        }, settled::incrementAndGet).status());

        await(open);
        assertSame(handle, atFailure.get());
        assertSame(handle, execution.adoptedHandle());
        assertEquals(ExecutionScope.BodyState.EXITED, open.snapshot().bodyState());
        assertEquals(1, settled.get());
        execution.beginDrain(Optional.empty());
        assertFalse(execution.resourceReleased());
        assertEquals(1, execution.scope().snapshot().outstandingResources());
        assertNotEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
        verify(backend, never()).close(any(), any());
    }

    @Test
    void inlineRollbackRunsOnTheOpenBodyWithoutWaitingBehindItselfAndCachesOnlyVerifiedCleanup() throws Exception {
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        AtomicInteger settled = new AtomicInteger();
        AtomicReference<Thread> openThread = new AtomicReference<>();
        doAnswer(invocation -> {
            assertSame(openThread.get(), Thread.currentThread());
            ScopeResources.DrainPermit permit = invocation.getArgument(1);
            permit.requireExecuting(handle);
            assertTrue(executor.getQueue().isEmpty(), "rollback must not enqueue cleanup behind its own OPEN");
            return verifiedCleanup();
        }).when(backend).close(eq(handle), any());
        execution.submitOpen(open, () -> {
            openThread.set(Thread.currentThread());
            NativeActorBackend.OpenOwnership ownership = adopt(open, handle);
            ownership.rollback(handle);
            ownership.rollback(handle);
            assertTrue(execution.resourceReleased());
        }, settled::incrementAndGet);

        await(open);
        execution.beginDrain(Optional.empty());
        assertEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
        assertEquals(1, settled.get());
        assertEquals(1, execution.scope().resources().snapshot().releasedTotal());
        verify(backend, times(1)).close(eq(handle), any());
    }

    @Test
    void cleanupNeedsBothNativeProofsAndReusesAnInflightAttemptBeforeAllowingAnExplicitRetry() throws Exception {
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        execution.submitOpen(open, () -> adopt(open, handle), () -> { });
        await(open);
        execution.beginDrain(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            ScopeResources.DrainPermit permit = invocation.getArgument(1);
            permit.requireExecuting(handle);
            entered.countDown();
            awaitLatch(complete);
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", false);
        }).when(backend).close(eq(handle), any());
        ScopeResources.CleanupAttempt first = execution.cleanupAttempt();
        execution.submitCleanup(first, () -> {
            try { execution.cleanup(first, handle); }
            catch (Throwable failure) { firstFailure.set(failure); }
        }, () -> { });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertSame(first, execution.cleanupAttempt());
            assertFalse(execution.resourceReleased());
        } finally { complete.countDown(); }
        await(first.work());
        assertInstanceOf(ObservationException.class, firstFailure.get());
        assertFalse(execution.resourceReleased());
        assertEquals(1, execution.scope().snapshot().outstandingResources());
        assertNotEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));

        doAnswer(invocation -> {
            ((ScopeResources.DrainPermit) invocation.getArgument(1)).requireExecuting(handle);
            return verifiedCleanup();
        }).when(backend).close(eq(handle), any());
        ScopeResources.CleanupAttempt retry = execution.cleanupAttempt();
        assertNotSame(first, retry);
        execution.submitCleanup(retry, () -> execution.cleanup(retry, handle), () -> { });
        await(retry.work());
        assertTrue(execution.resourceReleased());
        assertEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
        verify(backend, times(2)).close(eq(handle), any());
    }

    @Test
    void aLateOpenResultUsesItsAcceptedStopWindowEvenWhenTheHandleCapturedTheStoppedEpoch() throws Exception {
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<NativeActorHandle> late = new AtomicReference<>();
        AtomicInteger publications = new AtomicInteger();
        execution.submitOpen(open, () -> {
            NativeActorBackend.OpenOwnership ownership = execution.beginOpen(open);
            ownership.bindOrigin(world, channel);
            entered.countDown();
            awaitLatch(complete);
            NativeActorHandle result = handleAtCurrentEpoch();
            late.set(result);
            ownership.adopt(result);
            assertFalse(execution.publishOpen(open, publications::incrementAndGet));
            ownership.rollback(result);
        }, () -> { });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ExecutionScope.WorldStopTicket stop = ExecutionScope.tryBeginWorldStop().orElseThrow();
            execution.beginDrain(Optional.of(stop));
        } finally { complete.countDown(); }
        await(open);
        assertNotNull(late.get());
        assertNotSame(open.acceptedEpoch(), late.get().characterRef().epoch());
        assertTrue(execution.resourceReleased());
        assertEquals(0, publications.get());
        assertEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
        verify(backend).close(eq(late.get()), any());
        verify(backend, never()).close(eq(handle), any());
    }

    @Test
    void aNewWorldCannotRenewAnOldLateResultsCleanupPermitOrPublishItsActor() throws Exception {
        expectedUnreleasedOrigin = true;
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<NativeActorHandle> late = new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        execution.submitOpen(open, () -> {
            NativeActorBackend.OpenOwnership ownership = execution.beginOpen(open);
            ownership.bindOrigin(world, channel);
            entered.countDown();
            awaitLatch(complete);
            NativeActorHandle result = handleAtCurrentEpoch();
            late.set(result);
            ownership.adopt(result);
            try { ownership.rollback(result); }
            catch (Throwable failure) { refused.set(failure); }
            assertFalse(execution.publishOpen(open, () -> fail("stale actor must not publish")));
        }, () -> { });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            execution.beginDrain(Optional.of(ExecutionScope.tryBeginWorldStop().orElseThrow()));
            WorldEpoch.onWorldStarted();
        } finally { complete.countDown(); }
        await(open);
        assertNotNull(refused.get());
        assertSame(late.get(), execution.adoptedHandle());
        assertFalse(execution.resourceReleased());
        assertEquals(1, execution.scope().snapshot().outstandingResources());
        assertNotEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
        verify(backend, never()).close(any(), any());
    }

    @Test
    void submittingTheSameInternalWorkCannotReplaceItsBodyOrSettlementHook() throws Exception {
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger replacements = new AtomicInteger();
        execution.submitOpen(open, () -> {
            bodies.incrementAndGet();
            execution.beginOpen(open).confirmAbsent();
            entered.countDown();
            awaitLatch(complete);
        }, settled::incrementAndGet);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(CharacterCommandLane.Status.DUPLICATE,
                    execution.submitOpen(open, replacements::incrementAndGet, replacements::incrementAndGet).status());
        } finally { complete.countDown(); }
        await(open);
        assertEquals(CharacterCommandLane.Status.DUPLICATE,
                execution.submitOpen(open, replacements::incrementAndGet, replacements::incrementAndGet).status());
        assertEquals(1, bodies.get());
        assertEquals(1, settled.get());
        assertEquals(0, replacements.get());
    }

    @Test
    void bodyExitAndResourceAbsenceDoNotProveDrainWhileItsSettlementHookStillRuns() throws Exception {
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        CountDownLatch settling = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicInteger settlements = new AtomicInteger();
        execution.submitOpen(open, () -> execution.beginOpen(open).confirmAbsent(), () -> {
            settlements.incrementAndGet();
            settling.countDown();
            awaitLatch(complete);
        });
        try {
            assertTrue(settling.await(5, TimeUnit.SECONDS));
            assertEquals(ExecutionScope.BodyState.EXITED, open.snapshot().bodyState());
            assertFalse(open.snapshot().carrierSettled());
            assertTrue(execution.resourceReleased());
            execution.beginDrain(Optional.empty());
            assertNotEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
            assertFalse(execution.scope().snapshot().unloadComplete());
        } finally { complete.countDown(); }
        await(open);
        assertEquals(1, settlements.get());
        assertEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
    }

    @Test
    void typedChatPreparationDoesNotQueueAndDuplicateSubmissionUsesOnlyTheOriginalCarrier() throws Exception {
        bindOwnedActor();
        wireRealChatService();
        ExecutionScope.WorkHandle blocker = execution.acceptWork();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        execution.submitAction(blocker, () -> {
            blocked.countDown();
            awaitLatch(release);
        }, () -> { });
        ExecutionScope.WorkHandle chat = execution.acceptWork();
        assertSame(execution.moduleScope(), chat.scope());
        assertNotSame(execution.scope(), chat.scope());
        AtomicInteger preflightCalls = new AtomicInteger();
        NativeActionService.ActionHandle action;
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            long acceptedCarriers = executor.getTaskCount();
            action = execution.prepareChat(chat, handle, "排队后取消的普通聊天", () -> {
                preflightCalls.incrementAndGet();
                fail("a cancelled queued chat must not enter native preflight");
            });
            assertEquals(NativeActionService.SubmissionStatus.PREPARED, action.receipt().status());
            assertEquals(acceptedCarriers, executor.getTaskCount());
            assertTrue(executor.getQueue().isEmpty());
            assertFalse(chat.snapshot().carrierClaimed());
            assertFalse(action.dispatchStarted());

            NativeActionService.SubmissionReceipt receipt = execution.submitChat(action);
            assertEquals(NativeActionService.SubmissionStatus.ENQUEUED, receipt.status());
            assertEquals(acceptedCarriers + 1, executor.getTaskCount());
            assertEquals(1, executor.getQueue().size());
            assertSame(receipt, execution.submitChat(action));
            assertEquals(acceptedCarriers + 1, executor.getTaskCount());
            assertEquals(1, executor.getQueue().size());
            assertFalse(action.result().toCompletableFuture().isDone());
            execution.cancel(chat);
            assertEquals(1, executor.getQueue().size());
            assertFalse(chat.snapshot().queueDeparted());
            assertFalse(chat.isSettled());
        } finally { release.countDown(); }

        await(blocker);
        await(chat);
        NativeActionResult result = action.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(NativeActionResult.Outcome.CANCELLED, result.outcome());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, result.dispatch());
        assertEquals(0, preflightCalls.get());
        assertFalse(action.dispatchStarted());
        assertTrue(chat.snapshot().queueDeparted());
        assertTrue(chat.snapshot().carrierSettled());
        assertEquals(ExecutionScope.BodyState.NOT_STARTED, chat.snapshot().bodyState());
        execution.recordResult(chat, result);
        assertFalse(execution.resourceReleased(), "chat cancellation cannot close the actor");
        verify(backend, times(1)).prepareChat(eq(handle), any(), any(), eq(chat), any());
        verify(backend, times(2)).submitChat(action);

        execution.beginDrain(Optional.empty());
        ScopeResources.CleanupAttempt cleanup = execution.cleanupAttempt();
        execution.submitCleanup(cleanup, () -> execution.cleanup(cleanup, handle), () -> { });
        await(cleanup.work());
        assertEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
    }

    @Test
    void typedChatResultCanBeTerminalWhileItsRealCarrierStillBlocksTheSameLanesCleanup() throws Exception {
        bindOwnedActor();
        wireRealChatService();
        ExecutionScope.WorkHandle chat = execution.acceptWork();
        assertSame(execution.moduleScope(), chat.scope());
        assertNotSame(execution.scope(), chat.scope());
        CountDownLatch preflightEntered = new CountDownLatch(1);
        CountDownLatch releasePreflight = new CountDownLatch(1);
        CountDownLatch resultCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseResultCallback = new CountDownLatch(1);
        NativeActionService.ActionHandle action = execution.prepareChat(chat, handle, "派发前取消并等待结算", () -> {
            preflightEntered.countDown();
            awaitLatch(releasePreflight);
        });
        var callback = action.result().thenAccept(result -> {
            resultCallbackEntered.countDown();
            awaitLatch(releaseResultCallback);
        });
        ScopeResources.CleanupAttempt cleanup;
        try {
            assertEquals(NativeActionService.SubmissionStatus.ENQUEUED, execution.submitChat(action).status());
            assertTrue(preflightEntered.await(5, TimeUnit.SECONDS));
            execution.cancel(chat);
            releasePreflight.countDown();
            assertTrue(resultCallbackEntered.await(5, TimeUnit.SECONDS));
            NativeActionResult result = action.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(NativeActionResult.Outcome.CANCELLED, result.outcome());
            assertEquals(NativeActionResult.Dispatch.NOT_STARTED, result.dispatch());
            assertEquals(ExecutionScope.BodyState.EXITED, chat.snapshot().bodyState());
            assertFalse(chat.snapshot().carrierSettled());
            assertFalse(chat.isSettled());
            assertFalse(chat.completion().toCompletableFuture().isDone());

            execution.beginDrain(Optional.empty());
            cleanup = execution.cleanupAttempt();
            var pendingCleanup = execution.submitCleanup(cleanup, () -> execution.cleanup(cleanup, handle), () -> { });
            assertFalse(pendingCleanup.toCompletableFuture().isDone());
            assertEquals(0, executor.getQueue().size());
            assertFalse(execution.scope().snapshot().unloadComplete());
            assertNotEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
            verify(backend, never()).close(any(), any());
        } finally {
            releasePreflight.countDown();
            releaseResultCallback.countDown();
        }

        callback.toCompletableFuture().get(5, TimeUnit.SECONDS);
        await(chat);
        await(cleanup.work());
        assertTrue(chat.snapshot().carrierSettled());
        assertTrue(execution.resourceReleased());
        assertEquals(ExecutionScope.DrainResult.DRAINED, execution.awaitDrained(Duration.ZERO));
        verify(backend).submitChat(action);
        verify(backend).close(eq(handle), any());
    }

    private void bindOwnedActor() throws Exception {
        ExecutionScope.WorkHandle open = execution.acceptOpeningWork();
        execution.submitOpen(open, () -> {
            adopt(open, handle);
            execution.bindActor(handle);
        }, () -> { });
        await(open);
    }

    private void wireRealChatService() {
        NativeActionService actions = new NativeActionService();
        Server server = mock(Server.class);
        MapleMap map = mock(MapleMap.class);
        doAnswer(invocation -> {
            CharacterCommandLane lane = invocation.getArgument(1);
            CharacterCommandLane.ControlPermit permit = invocation.getArgument(2);
            ExecutionScope.WorkHandle work = invocation.getArgument(3);
            NativeChatAction action = invocation.getArgument(4);
            return actions.prepareChat(server, lane, permit, work,
                    new GameContext.SceneRef(permit.characterRef(), map, null, null), action, ChatEvidenceSource.none());
        }).when(backend).prepareChat(eq(handle), any(), any(), any(), any());
        doAnswer(invocation -> actions.submitChat(invocation.getArgument(0))).when(backend).submitChat(any());
    }

    private NativeActorBackend.OpenOwnership adopt(ExecutionScope.WorkHandle open, NativeActorHandle exact) {
        NativeActorBackend.OpenOwnership ownership = execution.beginOpen(open);
        ownership.bindOrigin(world, channel);
        ownership.adopt(exact);
        return ownership;
    }

    private NativeActorHandle handleAtCurrentEpoch() {
        NativeActorHandle result = mock(NativeActorHandle.class);
        when(result.world()).thenReturn(world);
        when(result.channel()).thenReturn(channel);
        when(result.client()).thenReturn(client);
        when(result.character()).thenReturn(character);
        when(result.characterRef()).thenReturn(new GameContext.CharacterRef(WorldEpoch.snapshot().token(),
                character, client, world, channel));
        return result;
    }

    private static Map<String, Object> verifiedCleanup() {
        return Map.of("native_cleanup_verified", true, "persisted_state_verified", true);
    }

    private static void await(ExecutionScope.WorkHandle work) throws Exception {
        work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
