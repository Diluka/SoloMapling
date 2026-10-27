package soloMapling.game.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.game.context.WorldEpoch;

import java.time.Duration;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ScopeResourcesTest {
    @BeforeEach
    void startWorld() { WorldEpoch.onWorldStarted(); }

    @AfterEach
    void stopWorld() { WorldEpoch.onWorldStopping(); }

    @Test
    void unknownCreationAndUnfinishedHandshakesKeepTheirCapacityUntilAbsenceIsConfirmed() {
        ExecutionScope scope = scope(2);
        ScopeResources resources = scope.resources();
        var first = resources.beginRegistration("listener");
        var second = resources.beginRegistration("timer");
        assertFailure("resource_capacity_exceeded", () -> resources.beginRegistration("continuation"));
        first.creationFailed("provider_result_unknown");
        first.creationFailed("provider_result_unknown");
        assertEquals(2, resources.snapshot().outstanding());
        assertEquals(1, resources.snapshot().unverified());
        assertEquals(1, resources.snapshot().pending());
        assertFailure("resource_handshake_settled", () -> first.attach(new Object(), () -> { }));
        assertFailure("resource_capacity_exceeded", () -> resources.beginRegistration("continuation"));
        first.confirmAbsent();
        first.confirmAbsent();
        assertEquals(1, resources.snapshot().outstanding());
        assertEquals(1, resources.snapshot().absentTotal());
        var third = resources.beginRegistration("continuation");
        second.confirmAbsent();
        third.confirmAbsent();
        assertEquals(0, resources.snapshot().outstanding());
        assertEquals(3, resources.snapshot().registeredTotal());
        assertEquals(3, resources.snapshot().absentTotal());
    }

    @Test
    void lateAttachmentSignalsOnlyItsExactResourceOutsideTheMonitorWithoutClaimingRelease() {
        ExecutionScope scope = scope(2);
        ScopeResources resources = scope.resources();
        var pending = resources.beginRegistration("listener");
        scope.beginDrain();
        assertEquals(1, resources.snapshot().pending());
        Object identity = new Object();
        AtomicInteger signals = new AtomicInteger();
        Runnable signal = () -> {
            assertFalse(Thread.holdsLock(scope.monitor));
            signals.incrementAndGet();
            resources.snapshot();
        };
        pending.attach(identity, signal);
        pending.attach(identity, () -> fail("a duplicate attach must not replace the original adapter"));
        assertEquals(1, signals.get());
        assertEquals(0, resources.snapshot().cancellationSignalFailures());
        assertEquals(1, resources.snapshot().attached());
        assertEquals(1, resources.snapshot().outstanding());
        assertEquals(0, resources.snapshot().releasedTotal());
        assertFailure("resource_identity_already_attached", pending::confirmAbsent);
        var cleanup = pending.acquireCleanup();
        assertTrue(cleanup.permit().isValidFor(identity));
        assertTrue(scope.runTracked(cleanup.work(), () -> {
            cleanup.permit().requireExecuting(identity);
            pending.confirmReleased(cleanup.permit(), identity);
            pending.confirmReleased(cleanup.permit(), identity);
            assertEquals(0, resources.snapshot().outstanding());
            assertFalse(cleanup.work().isSettled());
        }));
        assertTrue(cleanup.work().isSettled());
        assertEquals(1, resources.snapshot().releasedTotal());
        assertFalse(cleanup.permit().isValidFor(identity));
        pending.attach(identity, () -> fail("a duplicate attach after release cannot revive a resource"));
        assertEquals(1, signals.get());
        assertEquals(0, resources.snapshot().outstanding());
    }

    @Test
    void stoppingOneScopeDoesNotSignalAnotherAndCancelFailuresKeepExactOwnership() {
        ExecutionScope first = scope(2);
        ExecutionScope second = scope(2);
        AtomicInteger secondSignals = new AtomicInteger();
        var firstRegistration = first.resources().beginRegistration("listener");
        Object identity = new Object();
        firstRegistration.attach(identity, () -> {
            assertFalse(Thread.holdsLock(first.monitor));
            throw new IllegalStateException("private-provider-message");
        });
        second.resources().beginRegistration("listener").attach(new Object(), secondSignals::incrementAndGet);
        first.beginDrain();
        first.resources().requestCancellation();
        assertEquals(0, secondSignals.get());
        assertEquals(1, first.resources().snapshot().outstanding());
        assertEquals(1, first.resources().snapshot().cancellationSignals());
        assertEquals(1, first.resources().snapshot().cancellationSignalFailures());
        assertFalse(first.resources().snapshot().toString().contains("private-provider-message"));
        assertEquals(0, second.resources().snapshot().cancellationSignals());
        assertFailure("resource_handshake_settled", () -> firstRegistration.attach(new Object(), () -> { }));
        assertTrue(firstRegistration.acquireCleanup().permit().isValidFor(identity));
    }

    @Test
    void cancellationCallbacksAreReservedBeforeTheyRunAndCannotWaitForTheirOwnExit() {
        ExecutionScope scope = scope(2);
        Object firstIdentity = new Object();
        Object secondIdentity = new Object();
        var first = scope.resources().beginRegistration("first_listener");
        var second = scope.resources().beginRegistration("second_listener");
        AtomicReference<ScopeResources.CleanupAttempt> firstProof = new AtomicReference<>();
        AtomicReference<ScopeResources.CleanupAttempt> secondProof = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        Runnable cancellation = () -> {
            assertFalse(Thread.holdsLock(scope.monitor));
            if (callbacks.incrementAndGet() == 1) {
                first.confirmReleased(firstProof.get().permit(), firstIdentity);
                second.confirmReleased(secondProof.get().permit(), secondIdentity);
                assertEquals(0, scope.resources().snapshot().outstanding());
                // Both signals were reserved before either adapter ran, even if iteration order differs.
                assertEquals(1, scope.snapshot().pendingCallbacks());
                assertEquals(1, scope.snapshot().runningBodies());
            }
            assertFalse(scope.snapshot().unloadComplete());
            assertEquals(ExecutionScope.DrainResult.SELF_WAIT, scope.awaitDrained(Duration.ZERO));
        };
        first.attach(firstIdentity, cancellation);
        second.attach(secondIdentity, cancellation);
        firstProof.set(first.acquireCleanup());
        secondProof.set(second.acquireCleanup());
        assertTrue(scope.runTracked(firstProof.get().work(), () -> firstProof.get().permit().requireExecuting(firstIdentity)));
        assertTrue(scope.runTracked(secondProof.get().work(), () -> secondProof.get().permit().requireExecuting(secondIdentity)));
        scope.beginDrain();
        assertEquals(2, callbacks.get());
        assertEquals(0, scope.resources().snapshot().cancellationSignalFailures());
        assertEquals(0, scope.snapshot().pendingCallbacks());
        assertTrue(scope.snapshot().unloadComplete());
        assertEquals(ExecutionScope.DrainResult.DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void anErrorInOneCancellationAdapterCannotStrandOtherReservedSignals() {
        ExecutionScope scope = scope(2);
        AtomicInteger entered = new AtomicInteger();
        Runnable cancellation = () -> {
            assertFalse(Thread.holdsLock(scope.monitor));
            if (entered.incrementAndGet() == 1) throw new AssertionError("private-cancellation-error");
        };
        scope.resources().beginRegistration("first_listener").attach(new Object(), cancellation);
        scope.resources().beginRegistration("second_listener").attach(new Object(), cancellation);
        assertDoesNotThrow(scope::beginDrain);
        assertEquals(2, entered.get());
        assertEquals(0, scope.snapshot().pendingCallbacks());
        assertEquals(0, scope.snapshot().runningBodies());
        assertEquals(1, scope.resources().snapshot().cancellationSignalFailures());
        assertEquals(2, scope.resources().snapshot().outstanding());
        assertFalse(scope.snapshot().unloadComplete());
        assertNotEquals(ExecutionScope.DrainResult.DRAINED, scope.awaitDrained(Duration.ZERO));
        scope.resources().requestCancellation();
        assertEquals(2, entered.get());
        assertFalse(scope.resources().snapshot().toString().contains("private-cancellation-error"));
    }

    @Test
    void equalObjectsAreDistinctResourcesAndCannotReplaceAnAttachedIdentity() {
        ExecutionScope scope = scope(3);
        String firstIdentity = new String("equal-value");
        String secondIdentity = new String("equal-value");
        assertEquals(firstIdentity, secondIdentity);
        assertNotSame(firstIdentity, secondIdentity);
        var first = scope.resources().beginRegistration("reservation");
        var second = scope.resources().beginRegistration("reservation");
        first.attach(firstIdentity, () -> { });
        second.attach(secondIdentity, () -> { });
        first.attach(firstIdentity, () -> fail("duplicate attachment must be idempotent"));
        assertFailure("resource_handshake_settled", () -> first.attach(secondIdentity, () -> { }));
        var duplicateOwner = scope.resources().beginRegistration("reservation");
        assertFailure("resource_already_owned", () -> duplicateOwner.attach(firstIdentity, () -> { }));
        duplicateOwner.confirmAbsent();
        var cleanup = first.acquireCleanup();
        assertTrue(cleanup.permit().isValidFor(firstIdentity));
        assertFalse(cleanup.permit().isValidFor(secondIdentity));
        assertFailure("resource_cleanup_identity_mismatch", () -> second.confirmReleased(cleanup.permit(), secondIdentity));
        assertTrue(scope.runTracked(cleanup.work(), () -> first.confirmReleased(cleanup.permit(), firstIdentity)));
        assertEquals(1, scope.resources().snapshot().outstanding());
    }

    @Test
    void cleanupSingleflightUsesActualBodyExitRatherThanAnEarlyCompletionView() throws Exception {
        ExecutionScope scope = scope(1);
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("native_actor");
        registration.attach(identity, () -> { });
        scope.beginDrain();
        var first = registration.acquireCleanup();
        assertSame(first, registration.acquireCleanup());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                assertTrue(scope.runTracked(first.work(), () -> {
                    first.permit().requireExecuting(identity);
                    entered.countDown();
                    await(exit);
                    // A timed-out native cleanup has no actual release proof yet.
                }));
            } catch (Throwable error) { failure.set(error); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var completionView = first.completion().toCompletableFuture();
            completionView.complete(null);
            assertTrue(completionView.isDone());
            assertFalse(first.work().isSettled());
            assertSame(first, registration.acquireCleanup());
        } finally {
            exit.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue(first.work().isSettled());
        assertEquals(1, scope.resources().snapshot().outstanding());
        var retry = registration.acquireCleanup();
        assertNotSame(first, retry);
        assertFalse(first.permit().isValidFor(identity));
        assertTrue(scope.runTracked(retry.work(), () -> registration.confirmReleased(retry.permit(), identity)));
        assertEquals(0, scope.resources().snapshot().outstanding());
    }

    @Test
    void releaseProofRequiresThisActualCleanupBodyRatherThanTheOriginOrAnotherWork() {
        ExecutionScope scope = scope(1);
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("native_actor");
        registration.attach(identity, () -> { });
        var cleanup = registration.acquireCleanup();
        assertFailure("resource_cleanup_not_executing", () -> cleanup.permit().requireExecuting(identity));
        assertFailure("resource_release_unproven", () -> registration.confirmReleased(cleanup.permit(), identity));
        var ordinary = scope.acceptWork();
        assertTrue(scope.runTracked(ordinary, () -> {
            assertFailure("resource_cleanup_not_executing", () -> cleanup.permit().requireExecuting(identity));
            assertFailure("resource_release_unproven", () -> registration.confirmReleased(cleanup.permit(), identity));
        }));
        assertEquals(1, scope.resources().snapshot().outstanding());
        assertTrue(scope.runTracked(cleanup.work(), () -> {
            assertFailure("resource_origin_not_executing", () -> scope.resources().beginOwnedResult(cleanup.work(), "cleanup_child"));
            registration.confirmReleased(cleanup.permit(), identity);
        }));
    }

    @Test
    void nestedCancellationPublicationAndSettlementCannotBorrowAnOuterCleanupBody() {
        ExecutionScope scope = scope(2);
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("native_actor");
        AtomicReference<ScopeResources.CleanupAttempt> activeCleanup = new AtomicReference<>();
        AtomicInteger rejectedCallbacks = new AtomicInteger();
        Runnable cannotBorrow = () -> {
            var cleanup = activeCleanup.get();
            assertFalse(Thread.holdsLock(scope.monitor));
            assertFailure("resource_cleanup_not_executing", () -> cleanup.permit().requireExecuting(identity));
            assertFailure("resource_origin_not_executing", () -> scope.resources().beginOwnedResult(cleanup.work(), "borrowed_result"));
            rejectedCallbacks.incrementAndGet();
        };
        registration.attach(identity, cannotBorrow);
        var publication = scope.acceptWork();
        var carrier = scope.acceptWork();
        assertTrue(carrier.claimCarrier());
        assertTrue(scope.runTracked(publication, () -> { }));
        assertTrue(scope.runTracked(carrier, () -> { }));
        var cleanup = registration.acquireCleanup();
        activeCleanup.set(cleanup);
        assertTrue(scope.runTracked(cleanup.work(), () -> {
            cleanup.permit().requireExecuting(identity);
            scope.resources().requestCancellation();
            cleanup.permit().requireExecuting(identity);
            assertTrue(scope.publishOnce(publication, cannotBorrow));
            cleanup.permit().requireExecuting(identity);
            assertTrue(carrier.settleCarrier(cannotBorrow));
            cleanup.permit().requireExecuting(identity);
            registration.confirmReleased(cleanup.permit(), identity);
        }));
        assertEquals(3, rejectedCallbacks.get());
        assertEquals(0, scope.resources().snapshot().cancellationSignalFailures());
        assertEquals(0, scope.resources().snapshot().outstanding());
    }

    @Test
    void onlyTheExecutingAcceptedFactoryCanRegisterItsLateOwnedResultAndPendingCleanup() {
        ExecutionScope scope = scope(3);
        ExecutionScope foreign = scope(1);
        var origin = scope.acceptWork();
        var other = scope.acceptWork();
        var foreignWork = foreign.acceptWork();
        var normal = scope.resources().beginRegistration("listener");
        assertFailure("resource_origin_not_executing", () -> scope.resources().beginOwnedResult(origin, "native_actor"));
        AtomicReference<ScopeResources.Registration> late = new AtomicReference<>();
        assertTrue(scope.runTracked(origin, () -> {
            assertFailure("resource_origin_not_executing", () -> scope.resources().beginOwnedResult(other, "native_actor"));
            assertFailure("resource_origin_not_executing", () -> scope.resources().beginOwnedResult(foreignWork, "native_actor"));
            assertFailure("resource_pending_cleanup_not_owned", normal::requirePendingCleanupOrigin);
            scope.beginDrain();
            assertThrows(ExecutionScope.Invalid.class, () -> scope.resources().beginRegistration("late_listener"));
            var owned = scope.resources().beginOwnedResult(origin, "native_actor");
            late.set(owned);
            owned.requirePendingCleanupOrigin();
            owned.creationFailed("partial_login_unknown");
            owned.requirePendingCleanupOrigin();
        }));
        assertEquals(1, scope.resources().snapshot().unverified());
        assertFailure("resource_pending_cleanup_not_owned", late.get()::requirePendingCleanupOrigin);
        assertFailure("resource_origin_not_executing", () -> scope.resources().beginOwnedResult(origin, "native_actor"));
        late.get().confirmAbsent();
        normal.confirmAbsent();
    }

    @Test
    void aWorldStopTicketPreservesOnlyItsExactWindowAndANewStartInvalidatesThePermit() {
        ExecutionScope scope = scope(1);
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("native_actor");
        registration.attach(identity, () -> { });
        var cleanup = registration.acquireCleanup();
        scope.beginDrain();
        var stop = ExecutionScope.beginWorldStop();
        scope.joinWorldStop(stop);
        assertTrue(cleanup.permit().isValidFor(identity));
        WorldEpoch.onWorldStarted();
        assertFalse(cleanup.permit().isValidFor(identity));
        AtomicBoolean called = new AtomicBoolean();
        assertFalse(scope.runTracked(cleanup.work(), () -> called.set(true)));
        assertFalse(called.get());
        assertEquals(1, scope.resources().snapshot().outstanding());
        assertFailure("resource_cleanup_window_expired", registration::acquireCleanup);
    }

    @Test
    void anAlreadyEnteredCleanupMayConfirmOnlyItsOldReleaseFactAfterANewWorldStarts() throws Exception {
        ExecutionScope oldScope = scope(1);
        Object reusedIdentity = new Object();
        var oldRegistration = oldScope.resources().beginRegistration("native_actor");
        oldRegistration.attach(reusedIdentity, () -> { });
        var cleanup = oldRegistration.acquireCleanup();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch nativeResult = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                assertTrue(oldScope.runTracked(cleanup.work(), () -> {
                    cleanup.permit().requireExecuting(reusedIdentity);
                    entered.countDown();
                    await(nativeResult);
                    assertFalse(cleanup.permit().isValidFor(reusedIdentity));
                    assertFailure("resource_cleanup_not_executing", () -> cleanup.permit().requireExecuting(reusedIdentity));
                    oldRegistration.confirmReleased(cleanup.permit(), reusedIdentity);
                }));
            } catch (Throwable error) { failure.set(error); }
        });
        ExecutionScope newScope;
        ScopeResources.Registration newRegistration;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            oldScope.joinWorldStop(ExecutionScope.beginWorldStop());
            WorldEpoch.onWorldStarted();
            newScope = scope(1);
            newRegistration = newScope.resources().beginRegistration("native_actor");
            newRegistration.attach(reusedIdentity, () -> { });
        } finally {
            nativeResult.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(0, oldScope.resources().snapshot().outstanding());
        assertTrue(oldRegistration.released());
        assertEquals(1, newScope.resources().snapshot().outstanding());
        assertFalse(newRegistration.released());
        assertFailure("resource_cleanup_identity_mismatch", () -> newRegistration.confirmReleased(cleanup.permit(), reusedIdentity));
        assertFalse(cleanup.permit().isValidFor(reusedIdentity));
        assertFalse(oldScope.runTracked(cleanup.work(), () -> fail("the old permit cannot enter again")));
        oldRegistration.confirmReleased(cleanup.permit(), reusedIdentity);
        assertEquals(1, oldScope.resources().snapshot().releasedTotal());
    }

    @Test
    void aDelayedReleaseFactFromTheFirstAttemptDoesNotNeedTheNewestAttemptPermit() {
        ExecutionScope scope = scope(1);
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("native_actor");
        registration.attach(identity, () -> { });
        var first = registration.acquireCleanup();
        assertTrue(scope.runTracked(first.work(), () -> first.permit().requireExecuting(identity)));
        var retry = registration.acquireCleanup();
        assertNotSame(first, retry);
        registration.confirmReleased(first.permit(), identity);
        assertFalse(retry.permit().isValidFor(identity));
        assertFalse(scope.runTracked(retry.work(), () -> fail("release was already confirmed")));
        assertEquals(1, scope.resources().snapshot().releasedTotal());
    }

    @Test
    void releasedRegistrationsKeepOnlyAWeakIdentityAndCannotMatchNullAfterItIsCleared() throws Exception {
        ExecutionScope scope = scope(1);
        Object identity = new Object();
        var registration = scope.resources().beginRegistration("native_actor");
        registration.attach(identity, () -> { });
        assertSame(identity, registration.identity());
        Field strong = registration.getClass().getDeclaredField("identity");
        Field weak = registration.getClass().getDeclaredField("releasedIdentity");
        Field cancel = registration.getClass().getDeclaredField("cancelSignal");
        strong.setAccessible(true);
        weak.setAccessible(true);
        cancel.setAccessible(true);
        assertSame(identity, strong.get(registration));
        var cleanup = registration.acquireCleanup();
        assertTrue(scope.runTracked(cleanup.work(), () -> registration.confirmReleased(cleanup.permit(), identity)));
        assertTrue(registration.released());
        assertSame(identity, registration.identity());
        assertNull(strong.get(registration));
        assertNull(cancel.get(registration));
        WeakReference<?> released = assertInstanceOf(WeakReference.class, weak.get(registration));
        assertSame(identity, released.get());
        registration.attach(identity, () -> fail("released identity must not revive"));
        registration.confirmReleased(cleanup.permit(), identity);
        released.clear(); // Deterministic loss of terminal identity, independent of GC scheduling.
        assertNull(registration.identity());
        assertTrue(registration.released());
        assertFailure("resource_cleanup_identity_mismatch", () -> registration.confirmReleased(cleanup.permit(), null));
        assertFailure("resource_handshake_settled", () -> registration.attach(new Object(), () -> { }));
        assertEquals(0, scope.resources().snapshot().outstanding());
        assertEquals(1, scope.resources().snapshot().releasedTotal());
    }

    @Test
    void attachmentAndUnknownCreationRaceSettleOneHandshakeWithoutLosingTheRegistration() throws Exception {
        ExecutionScope scope = scope(1);
        var registration = scope.resources().beginRegistration("listener");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread attach = racer(start, rejected, failure, () -> registration.attach(new Object(), () -> { }));
        Thread unknown = racer(start, rejected, failure, () -> registration.creationFailed("creation_unknown"));
        start.countDown();
        attach.join(5000);
        unknown.join(5000);
        assertFalse(attach.isAlive());
        assertFalse(unknown.isAlive());
        assertNull(failure.get());
        assertEquals(1, rejected.get());
        assertEquals(1, scope.resources().snapshot().outstanding());
        assertEquals(1, scope.resources().snapshot().attached() + scope.resources().snapshot().unverified());
        assertEquals(0, scope.resources().snapshot().releasedTotal());
    }

    @Test
    void publicReportsAreBoundedCountersAndKindsAndFailuresRejectFreeFormText() {
        ExecutionScope scope = scope(1);
        for (String kind : new String[]{"", " ", "native actor", "a".repeat(65), "private\nbody"}) {
            assertFailure("invalid_resource_kind", () -> scope.resources().beginRegistration(kind));
        }
        var registration = scope.resources().beginRegistration("native_actor");
        assertFailure("invalid_resource_failure", () -> registration.creationFailed("arbitrary exception message"));
        assertEquals(1, scope.resources().snapshot().pending());
        registration.creationFailed("provider_unknown");
        assertFalse(scope.resources().snapshot().toString().contains("provider_unknown"));
        assertEquals("native_actor", registration.kind());
    }

    @Test
    void aFullNormalBudgetCannotPreventTheOwnedCleanupWhichItsBodyNeedsToExit() throws Exception {
        ExecutionScope scope = new ExecutionScope(WorldEpoch.snapshot().token(),
                new ExecutionScope.OwnerStamp(new Object(), new Object()), new ExecutionScope.Limits(1, 1, 4, 1));
        Object resource = new Object();
        var registration = scope.resources().beginRegistration("blocking_resource");
        AtomicInteger cancelRequests = new AtomicInteger();
        registration.attach(resource, cancelRequests::incrementAndGet);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch actuallyStopped = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var normal = scope.acceptWork();
        Thread body = Thread.ofVirtual().start(() -> {
            try {
                scope.runTracked(normal, () -> { entered.countDown(); await(actuallyStopped); });
            } catch (Throwable problem) { failure.set(problem); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scope.beginDrain();
            assertEquals(1, cancelRequests.get());
            assertEquals(ExecutionScope.BodyState.RUNNING, normal.snapshot().bodyState());
            assertEquals(ExecutionScope.DrainResult.TIMED_OUT, scope.awaitDrained(Duration.ZERO));

            // NORMAL=1 is full; a distinct reserved cleanup handle must still be admitted.
            var cleanup = assertDoesNotThrow(registration::acquireCleanup);
            assertEquals(2, scope.snapshot().acceptedWork());
            assertTrue(scope.runTracked(cleanup.work(), () -> {
                cleanup.permit().requireExecuting(resource);
                registration.confirmReleased(cleanup.permit(), resource);
                actuallyStopped.countDown();
            }));
            body.join(5000);
            assertFalse(body.isAlive());
            assertNull(failure.get());
            assertTrue(registration.released());
            assertEquals(ExecutionScope.DrainResult.DRAINED, scope.awaitDrained(Duration.ofSeconds(5)));
        } finally {
            actuallyStopped.countDown();
            body.join(5000);
            assertFalse(body.isAlive());
        }
    }

    @Test
    void cleanupBudgetIsBoundedAndRejectionKeepsTheOtherResourceOwnedUntilCapacityReturns() {
        ExecutionScope scope = new ExecutionScope(WorldEpoch.snapshot().token(),
                new ExecutionScope.OwnerStamp(new Object(), new Object()), new ExecutionScope.Limits(1, 2, 4, 1));
        Object firstResource = new Object();
        Object secondResource = new Object();
        var first = scope.resources().beginRegistration("first_resource");
        var second = scope.resources().beginRegistration("second_resource");
        first.attach(firstResource, () -> { });
        second.attach(secondResource, () -> { });
        scope.beginDrain();
        var firstCleanup = first.acquireCleanup();
        assertSame(firstCleanup, first.acquireCleanup(), "single-flight does not spend another reserve");
        ExecutionScope.Invalid rejected = assertThrows(ExecutionScope.Invalid.class, second::acquireCleanup);
        assertEquals("cleanup_work_capacity_exceeded", rejected.reason());
        assertEquals(2, scope.resources().snapshot().outstanding());
        assertSame(secondResource, second.identity());
        assertFalse(second.released());
        assertEquals(1, scope.snapshot().acceptedWork());
        assertEquals(ExecutionScope.DrainResult.TIMED_OUT, scope.awaitDrained(Duration.ZERO));

        assertTrue(scope.runTracked(firstCleanup.work(), () -> first.confirmReleased(firstCleanup.permit(), firstResource)));
        var secondCleanup = second.acquireCleanup();
        assertTrue(scope.runTracked(secondCleanup.work(), () -> second.confirmReleased(secondCleanup.permit(), secondResource)));
        assertEquals(2, scope.resources().snapshot().releasedTotal());
        assertEquals(ExecutionScope.DrainResult.DRAINED, scope.awaitDrained(Duration.ZERO));
    }

    private static ExecutionScope scope(int maxResources) {
        return new ExecutionScope(WorldEpoch.snapshot().token(), new ExecutionScope.OwnerStamp(new Object(), new Object()),
                new ExecutionScope.Limits(32, maxResources, 16, maxResources));
    }

    private static void assertFailure(String message, Runnable action) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, action::run);
        assertEquals(message, failure.getMessage());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test_latch_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Thread racer(CountDownLatch start, AtomicInteger rejected, AtomicReference<Throwable> failure,
                                Runnable action) {
        return Thread.ofVirtual().start(() -> {
            await(start);
            try { action.run(); }
            catch (IllegalStateException expected) { rejected.incrementAndGet(); }
            catch (Throwable unexpected) { failure.set(unexpected); }
        });
    }
}
