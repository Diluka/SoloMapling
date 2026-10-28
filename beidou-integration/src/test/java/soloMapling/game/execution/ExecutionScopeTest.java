package soloMapling.game.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.game.context.WorldEpoch;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.game.execution.ExecutionScope.BodyState.*;
import static soloMapling.game.execution.ExecutionScope.DrainResult.*;
import static soloMapling.game.execution.ExecutionScope.State.*;

/** Local work facts use real threads and latches, without any game host or controller. */
class ExecutionScopeTest {
    @Test
    void cleanupCapacityIsExplicitAndResourceOwnersCannotConfigureItAway() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionScope.Limits(1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionScope.Limits(1, 0, 0, -1));
        assertDoesNotThrow(() -> new ExecutionScope.Limits(1, 0, 0, 0));
        assertDoesNotThrow(() -> new ExecutionScope.Limits(1, 0, 0, 1));
        // Budgets are independently compared as counts; their mathematical sum may exceed int.
        var large = assertDoesNotThrow(() -> new ExecutionScope.Limits(Integer.MAX_VALUE, 1, 0, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, large.maxWork());
        assertEquals(Integer.MAX_VALUE, large.maxCleanupWork());
    }
    @BeforeEach
    void startWorldEpoch() { WorldEpoch.onWorldStarted(); }

    @AfterEach
    void stopWorldEpoch() { ExecutionScope.tryBeginWorldStop(); }

    @Test
    void independentScopesNeverAuthorizeForeignWork() {
        ExecutionScope first = scope(2, 2);
        ExecutionScope second = scope(2, 2);
        ExecutionScope.WorkHandle own = first.acceptWork();
        assertSame(first, own.scope());
        assertSame(first.acceptedEpoch(), own.acceptedEpoch());
        assertInvalid("foreign_work", () -> second.requestCancel(own));
        assertInvalid("foreign_work", () -> second.runTracked(own, () -> fail("foreign work ran")));
        assertInvalid("foreign_work", () -> second.publishOnce(own, () -> fail("foreign result applied")));
        assertFalse(own.cancelRequested());
        assertTrue(first.runTracked(own, () -> { }));
        first.beginDrain();
        assertEquals(DRAINED, first.awaitDrained(Duration.ZERO));
        assertTrue(second.isActive());
        assertTrue(second.runTracked(second.acceptWork(), () -> { }));
    }

    @Test
    void activeWorkCapacityIsBoundedAndSettlingOneWorkAllowsAnother() {
        ExecutionScope scope = scope(2, 1);
        ExecutionScope.WorkHandle first = scope.acceptWork();
        ExecutionScope.WorkHandle second = scope.acceptWork();
        assertInvalid("work_capacity_exceeded", scope::acceptWork);
        assertEquals(2, scope.snapshot().acceptedWork());
        assertEquals(1, scope.snapshot().diagnostics().size());
        assertThrows(UnsupportedOperationException.class, () -> scope.snapshot().diagnostics().clear());
        first.reject();
        assertTrue(first.isSettled());
        assertEquals(1, scope.snapshot().acceptedWork());
        ExecutionScope.WorkHandle replacement = scope.acceptWork();
        assertNotSame(first, replacement);
        assertEquals(2, scope.snapshot().acceptedWork());
        second.reject();
        replacement.reject();
        assertEquals(0, scope.snapshot().acceptedWork());
        scope.beginDrain();
        assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void oneHandleCannotRerunButNewLegitimateWorkCanRepeatTheSameAction() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle first = scope.acceptWork();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(scope.runTracked(first, calls::incrementAndGet));
        assertFalse(scope.runTracked(first, calls::incrementAndGet));
        assertTrue(scope.runTracked(scope.acceptWork(), calls::incrementAndGet));
        assertEquals(2, calls.get());
        assertEquals(EXITED, first.snapshot().bodyState());
        assertTrue(first.isSettled());
        assertEquals(0, scope.snapshot().acceptedWork());
        assertEquals(0, scope.snapshot().runningBodies());
    }

    @Test
    void cancellingQueuedWorkSkipsItsBodyWithoutClosingTheScope() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertTrue(work.claimCarrier());
        assertFalse(work.claimCarrier());
        scope.requestCancel(work);
        scope.requestCancel(work);
        assertTrue(work.cancelRequested());
        assertFalse(scope.runTracked(work, () -> fail("cancelled body ran")));
        assertEquals(NOT_STARTED, work.snapshot().bodyState());
        assertTrue(work.snapshot().skipped());
        assertTrue(work.snapshot().queueDeparted());
        assertFalse(work.isSettled());
        assertFalse(work.completion().toCompletableFuture().isDone());
        work.carrierSettled();
        assertTrue(work.isSettled());
        assertTrue(work.completion().toCompletableFuture().isDone());
        assertTrue(scope.isActive());
        assertTrue(scope.runTracked(scope.acceptWork(), () -> { }));
    }

    @Test
    void cancellingFutureAndCompletionViewCannotProveTheExecutingBodyHasExited() throws Exception {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertTrue(work.claimCarrier());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> carrier = executor.submit(() -> {
                try {
                    scope.runTracked(work, () -> { entered.countDown(); awaitUninterruptibly(release); });
                } finally {
                    work.carrierSettled();
                    exited.countDown();
                }
            });
            await(entered);
            CompletableFuture<ExecutionScope.WorkSnapshot> cancelledView = work.completion().toCompletableFuture();
            assertTrue(cancelledView.cancel(true));
            assertTrue(cancelledView.isDone());
            assertTrue(carrier.cancel(true));
            assertTrue(carrier.isDone());
            scope.requestCancel(work);
            scope.beginDrain();
            assertEquals(RUNNING, work.snapshot().bodyState());
            assertEquals(1, scope.snapshot().runningBodies());
            assertEquals(1, scope.snapshot().pendingCarriers());
            assertFalse(work.isSettled());
            assertFalse(work.completion().toCompletableFuture().isDone());
            assertEquals(TIMED_OUT, scope.awaitDrained(Duration.ZERO));
            release.countDown();
            await(exited);
            assertEquals(DRAINED, scope.awaitDrained(Duration.ofSeconds(2)));
            assertEquals(EXITED, work.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).bodyState());
            assertTrue(scope.snapshot().unloadComplete());
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void bodyExitDoesNotSettleItsStillClaimedCarrier() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertTrue(work.claimCarrier());
        assertTrue(scope.runTracked(work, () -> { }));
        assertEquals(EXITED, work.snapshot().bodyState());
        assertEquals(0, scope.snapshot().runningBodies());
        assertEquals(1, scope.snapshot().pendingCarriers());
        assertFalse(work.isSettled());
        assertFalse(work.completion().toCompletableFuture().isDone());
        scope.beginDrain();
        assertEquals(TIMED_OUT, scope.awaitDrained(Duration.ZERO));
        work.carrierSettled();
        assertTrue(work.isSettled());
        assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void settlementHookRemainsTrackedUntilItsRealExitAndCannotWaitForItself() throws Exception {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertTrue(work.claimCarrier());
        assertTrue(scope.runTracked(work, () -> { }));
        scope.beginDrain();
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        AtomicReference<ExecutionScope.DrainResult> self = new AtomicReference<>();
        AtomicInteger hooks = new AtomicInteger();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Boolean> settlement = executor.submit(() -> work.settleCarrier(() -> {
                hooks.incrementAndGet();
                self.set(scope.awaitDrained(Duration.ofSeconds(1)));
                hookEntered.countDown();
                awaitUninterruptibly(releaseHook);
            }));
            await(hookEntered);
            assertEquals(SELF_WAIT, self.get());
            assertEquals(1, scope.snapshot().runningBodies());
            assertEquals(1, scope.snapshot().pendingCarriers());
            assertFalse(work.completion().toCompletableFuture().isDone());
            assertEquals(TIMED_OUT, scope.awaitDrained(Duration.ZERO));
            releaseHook.countDown();
            assertTrue(settlement.get(2, TimeUnit.SECONDS));
            assertFalse(work.settleCarrier(hooks::incrementAndGet));
            assertEquals(1, hooks.get());
            assertTrue(work.isSettled());
            assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
        } finally {
            releaseHook.countDown();
            shutdown(executor);
        }
    }

    @Test
    void bodyExceptionPropagatesAfterItsFactualExitWithoutRerunningTheHandle() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        IllegalStateException failure = new IllegalStateException("local-body-test-failure");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> scope.runTracked(work, () -> { throw failure; })));
        assertEquals(EXITED, work.snapshot().bodyState());
        assertEquals(0, scope.snapshot().runningBodies());
        assertTrue(work.isSettled());
        assertTrue(work.completion().toCompletableFuture().isDone());
        assertFalse(scope.runTracked(work, () -> fail("failed handle reran")));
        scope.beginDrain();
        assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void failedSettlementHookIsNotMistakenForAReleasedAndDrainedWork() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertTrue(work.claimCarrier());
        scope.runTracked(work, () -> { });
        IllegalStateException failure = new IllegalStateException("local-settlement-test-failure");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> work.settleCarrier(() -> { throw failure; })));
        assertTrue(work.snapshot().carrierSettled());
        assertTrue(work.snapshot().settlementFailed());
        assertFalse(work.isSettled());
        assertEquals(0, scope.snapshot().runningBodies());
        scope.beginDrain();
        assertEquals(UNVERIFIED, scope.awaitDrained(Duration.ZERO));
        assertFalse(scope.snapshot().unloadComplete());
        assertEquals(DRAINING, scope.snapshot().state());
    }

    @Test
    void nestedSynchronousWorkCountsEachBodyUntilTheOuterBodyExits() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle outer = scope.acceptWork();
        ExecutionScope.WorkHandle inner = scope.acceptWork();
        assertTrue(scope.runTracked(outer, () -> {
            assertEquals(1, scope.snapshot().runningBodies());
            assertTrue(scope.runTracked(inner, () -> {
                assertEquals(2, scope.snapshot().runningBodies());
                scope.beginDrain();
                assertEquals(SELF_WAIT, scope.awaitDrained(Duration.ofSeconds(1)));
            }));
            assertEquals(1, scope.snapshot().runningBodies());
            assertFalse(scope.snapshot().unloadComplete());
            assertEquals(SELF_WAIT, scope.awaitDrained(Duration.ofSeconds(1)));
        }));
        assertEquals(0, scope.snapshot().runningBodies());
        assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void nestedDifferentScopeCallbackFindsOuterScopeWhenDetectingSelfWait() {
        ExecutionScope outerScope = scope();
        ExecutionScope innerScope = scope();
        ExecutionScope.WorkHandle outer = outerScope.acceptWork();
        ExecutionScope.WorkHandle inner = innerScope.acceptWork();
        assertTrue(outerScope.runTracked(outer, () -> assertTrue(innerScope.runTracked(inner, () -> {
            outerScope.beginDrain();
            innerScope.beginDrain();
            assertEquals(SELF_WAIT, outerScope.awaitDrained(Duration.ofSeconds(1)));
            assertEquals(SELF_WAIT, innerScope.awaitDrained(Duration.ofSeconds(1)));
        }))));
        assertEquals(DRAINED, outerScope.awaitDrained(Duration.ZERO));
        assertEquals(DRAINED, innerScope.awaitDrained(Duration.ZERO));
    }

    @Test
    void controlledPublicationIsAppliedOnceAndLatePublicationCannotReviveADrainedScope() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        AtomicInteger results = new AtomicInteger();
        assertTrue(scope.runTracked(work, () -> {
            assertTrue(scope.publishOnce(work, () -> {
                assertEquals(2, scope.snapshot().runningBodies());
                results.incrementAndGet();
            }));
            assertFalse(scope.publishOnce(work, results::incrementAndGet));
        }));
        assertEquals(1, results.get());
        assertTrue(work.snapshot().resultApplied());
        ExecutionScope.WorkHandle late = scope.acceptWork();
        assertTrue(scope.runTracked(late, () -> { }));
        scope.beginDrain();
        assertFalse(scope.publishOnce(late, results::incrementAndGet));
        assertEquals(1, results.get());
        assertEquals(0, scope.snapshot().acceptedWork());
        assertEquals(CLOSED, scope.snapshot().state());
        assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
        assertInvalid("scope_not_active", scope::acceptWork);
    }

    @Test
    void completedBodyStageDoesNotHideAStillExecutingCompletionApplication() throws Exception {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        scope.runTracked(work, () -> { });
        assertTrue(work.completion().toCompletableFuture().isDone());
        CountDownLatch applying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Boolean> publication = executor.submit(() -> scope.publishOnce(work, () -> {
                applying.countDown();
                awaitUninterruptibly(release);
            }));
            await(applying);
            assertTrue(work.completion().toCompletableFuture().isDone());
            assertEquals(1, work.snapshot().publicationBodies());
            assertFalse(work.isSettled());
            scope.beginDrain();
            assertEquals(TIMED_OUT, scope.awaitDrained(Duration.ZERO));
            release.countDown();
            assertTrue(publication.get(2, TimeUnit.SECONDS));
            assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void bodyApplySettlementAndCompletionListenersAllRunOutsideTheLedgerLock() throws Exception {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertTrue(work.claimCarrier());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<Void> listener = work.completion().thenRun(() -> assertReadableFromAnotherThread(scope, executor))
                    .toCompletableFuture();
            assertTrue(scope.runTracked(work, () -> {
                assertReadableFromAnotherThread(scope, executor);
                assertTrue(scope.publishOnce(work, () -> assertReadableFromAnotherThread(scope, executor)));
            }));
            assertTrue(work.settleCarrier(() -> assertReadableFromAnotherThread(scope, executor)));
            listener.get(2, TimeUnit.SECONDS);
            assertTrue(work.isSettled());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void replacingTheWorldEpochRejectsOldAdmissionAndOldResultApplication() {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle pending = scope.acceptWork();
        WorldEpoch.onWorldStarted();
        assertFalse(scope.isActive());
        assertInvalid("scope_not_active", scope::acceptWork);
        assertFalse(scope.runTracked(pending, () -> fail("old world body ran")));
        assertFalse(scope.publishOnce(pending, () -> fail("old world result applied")));
        scope.beginDrain();
        assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void interruptedExternalDrainPreservesTheFlagAndDoesNotInventCompletion() throws Exception {
        ExecutionScope scope = scope();
        ExecutionScope.WorkHandle work = scope.acceptWork();
        assertTrue(work.claimCarrier());
        scope.beginDrain();
        AtomicReference<ExecutionScope.DrainResult> result = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread waiter = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            result.set(scope.awaitDrained(Duration.ofSeconds(1)));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        waiter.join(2000);
        assertFalse(waiter.isAlive());
        assertEquals(INTERRUPTED, result.get());
        assertTrue(stillInterrupted.get());
        assertFalse(scope.snapshot().unloadComplete());
        assertFalse(scope.runTracked(work, () -> fail("draining body ran")));
        work.carrierSettled();
        assertEquals(DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    private static ExecutionScope scope() { return scope(4, 4); }

    private static ExecutionScope scope(int workLimit, int diagnosticsLimit) {
        return new ExecutionScope(WorldEpoch.snapshot().token(), new ExecutionScope.Limits(workLimit, 0, diagnosticsLimit, 0));
    }

    private static void assertInvalid(String reason, Runnable action) {
        ExecutionScope.Invalid failure = assertThrows(ExecutionScope.Invalid.class, action::run);
        assertEquals(reason, failure.reason());
        assertEquals(reason, failure.getMessage());
    }

    private static void assertReadableFromAnotherThread(ExecutionScope scope, ExecutorService executor) {
        Future<ExecutionScope.ScopeSnapshot> read = executor.submit(scope::snapshot);
        try {
            assertNotNull(read.get(2, TimeUnit.SECONDS));
        } catch (Exception failure) {
            throw new AssertionError("caller code or completion listener retained the scope ledger lock", failure);
        } finally {
            read.cancel(true);
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(2, TimeUnit.SECONDS), "controlled worker did not reach its checkpoint");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("controlled worker was not released");
                try {
                    if (latch.await(remaining, TimeUnit.NANOSECONDS)) return;
                } catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "controlled test workers did not leave");
    }
}
