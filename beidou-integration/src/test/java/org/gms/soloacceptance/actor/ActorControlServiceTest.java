package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.AcceptanceObservationRuntime;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActorControlServiceTest {
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private final List<ManualExecutor> executors = new ArrayList<>();
    private final List<ActorControlService> services = new ArrayList<>();

    @BeforeEach
    void prepare() {
        CharacterTestContext.install();
        AcceptanceObservationRuntime.onWorldStarted();
        backend = mock(NativeActorBackend.class);
        handle = mock(NativeActorHandle.class);
        when(backend.open(anyString(), any(), any(), any())).thenReturn(handle);
        when(backend.isCurrent(handle)).thenReturn(true);
        when(backend.snapshot(handle)).thenReturn(Map.of("character_id", 73, "logged_in_world", true,
                "persisted", true, "packet_events", List.of()));
        when(backend.close(handle)).thenReturn(Map.of("native_cleanup_verified", true, "persisted_state_verified", true));
    }

    @AfterEach
    void stopFixtures() {
        AcceptanceObservationRuntime.onWorldStopped();
        services.forEach(ActorControlService::onWorldStopping);
        new ArrayList<>(executors).forEach(ManualExecutor::drain);
        executors.forEach(ManualExecutor::shutdownNow);
    }

    @Test
    void anAcceptedOpenIsNotReportedOnlineBeforeNativeEvidenceAndClosedRequestDoesNotReopen() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        assertEquals("QUEUED", opened.get("status"));
        assertEquals("OPENING", session(opened).get("state"));
        assertNull(session(opened).get("actor"));
        verify(backend, never()).open(anyString(), any(), any(), any());

        executors.getFirst().runNext();
        var completed = service.get("admin", id(opened), 0, 100);
        assertEquals("SUCCEEDED", completed.get("status"));
        assertEquals("OPEN", session(completed).get("state"));
        assertEquals(true, actor(completed).get("logged_in_world"));
        assertEquals("native_actor_online", result(completed).get("observed_outcome"));
        assertSame(handle, service.currentActor("admin", sessionId(opened), generation(opened)).orElseThrow());

        var closing = service.close("admin", "close-one", closeRequest(opened));
        assertEquals("CLOSING", session(closing).get("state"));
        executors.getFirst().runNext();
        var closed = service.get("admin", id(closing), 0, 100);
        assertEquals("SUCCEEDED", closed.get("status"));
        assertEquals("CLOSED", session(closed).get("state"));
        assertEquals(true, result(closed).get("native_cleanup_verified"));
        assertEquals(true, result(closed).get("persisted_state_verified"));
        assertNull(session(closed).get("actor"));

        var repeatedOpen = open(service, "open-one");
        assertEquals(id(opened), id(repeatedOpen));
        assertEquals("CLOSED", session(repeatedOpen).get("state"));
        var repeatedClose = service.close("admin", "close-one", closeRequest(opened));
        assertEquals(id(closing), id(repeatedClose));
        verify(backend, times(1)).open(anyString(), any(), any(), any());
        verify(backend, times(1)).close(handle);
    }

    @Test
    void requestReuseWithDifferentArgumentsCannotChangeTheOriginalControlLease() {
        ActorControlService service = service(4, 20, 64);
        var first = open(service, "open-one");
        var conflict = assertThrows(ObservationException.class,
                () -> service.open("admin", "open-one", new ActorOpenRequest("primary", 1, 1)));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("request_id_conflict", conflict.reason());
        assertEquals(id(first), id(open(service, "open-one")));
        assertEquals(1, executors.size());
    }

    @Test
    void oneProfileHasOneLeaseAndTheGlobalLimitDoesNotEvictAnActiveSession() {
        ActorControlService service = service(1, 20, 64);
        open(service, "open-one");
        assertEquals("profile_in_use", assertThrows(ObservationException.class,
                () -> open(service, "different-request")).reason());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ObservationException.class,
                () -> service.open("admin", "another-profile", new ActorOpenRequest("secondary", null, null))).status());
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        assertEquals(1, executors.size());
    }

    @Test
    void operationAndSessionOwnershipAreCheckedBeforeBackendObservationOrCancellation() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ObservationException.class,
                () -> service.get("other-admin", id(opened), 0, 100)).status());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ObservationException.class,
                () -> service.cancel("other-admin", "cancel-one", id(opened))).status());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ObservationException.class,
                () -> service.currentActor("other-admin", sessionId(opened), generation(opened))).status());
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class,
                () -> service.currentActor("admin", sessionId(opened), generation(opened) + 1)).status());
        verify(backend, never()).snapshot(any());
    }

    @Test
    void queuedOpenCancellationDoesNotCreateAnActorAndItsOldRequestCannotCreateOneLater() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        assertEquals("CANCEL_REQUESTED", service.cancel("admin", "cancel-one", id(opened)).get("status"));
        service.cancel("admin", "cancel-again", id(opened));
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(opened), 0, 100).get("status"));
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        assertEquals(1, service.publicCapabilities().get("idempotency_records"));
        assertEquals(id(opened), id(open(service, "open-one")));
        verify(backend, never()).open(anyString(), any(), any(), any());
    }

    @Test
    void cancellationWhileNativeOpenIsRunningWaitsForVerifiedCleanupBeforeReleasingTheProfile() throws Exception {
        ActorControlService service = service(4, 20, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.open(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return handle;
        });
        var opened = open(service, "open-one");
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("CANCEL_REQUESTED", service.cancel("admin", "cancel-one", id(opened)).get("status"));
            assertEquals(1, service.publicCapabilities().get("active_sessions"));
            assertEquals("profile_in_use", assertThrows(ObservationException.class, () -> open(service, "open-two")).reason());
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
        var cancelled = service.get("admin", id(opened), 0, 100);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(true, ((Map<?, ?>) result(cancelled).get("cleanup")).get("native_cleanup_verified"));
        verify(backend, times(1)).close(handle);
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
    }

    @Test
    void closeCancellationDoesNotClaimCleanupStoppedOrLeaveTheActorOnline() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        var closed = service.close("admin", "close-one", closeRequest(opened));
        service.cancel("admin", "cancel-close", id(closed));
        executors.getFirst().runNext();
        var done = service.get("admin", id(closed), 0, 100);
        assertEquals("SUCCEEDED", done.get("status"));
        assertEquals("CLOSED", session(done).get("state"));
        assertEquals(true, result(done).get("cancel_too_late"));
        verify(backend).close(handle);
    }

    @Test
    void absentCleanupOrPersistenceEvidenceIsIndeterminateAndCannotReleaseTheProfile() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        when(backend.close(handle)).thenReturn(Map.of("native_cleanup_verified", true, "persisted_state_verified", false));
        var closing = service.close("admin", "close-one", closeRequest(opened));
        executors.getFirst().runNext();
        var uncertain = service.get("admin", id(closing), 0, 100);
        assertEquals("INDETERMINATE", uncertain.get("status"));
        assertEquals("QUARANTINED", session(uncertain).get("state"));
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        assertEquals("profile_in_use", assertThrows(ObservationException.class, () -> open(service, "open-two")).reason());
    }

    @Test
    void aPreviousWorldCannotPublishALateActorAndItsProfileStaysReservedUntilCleanup() throws Exception {
        ActorControlService service = service(4, 20, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean sawCancellation = new AtomicBoolean();
        when(backend.open(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier cancelled = invocation.getArgument(3);
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            sawCancellation.set(cancelled.getAsBoolean());
            return handle;
        });
        var opened = open(service, "open-one");
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AcceptanceObservationRuntime.onWorldStopped();
            service.onWorldStopping();
            AcceptanceObservationRuntime.onWorldStarted();
            service.onWorldStarted();
            assertEquals("profile_in_use", assertThrows(ObservationException.class, () -> open(service, "open-new-world")).reason());
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        assertTrue(sawCancellation.get());
        assertEquals("CANCELLED", service.get("admin", id(opened), 0, 100).get("status"));
        verify(backend, times(1)).close(handle);
        var newWorld = open(service, "open-new-world");
        assertNotEquals(opened.get("runtime_generation"), newWorld.get("runtime_generation"));
        assertTrue(generation(newWorld) > generation(opened));
    }

    @Test
    void historyIsBoundedWithoutForgettingOldOpensAndNormalCloseHasAReservedSlot() {
        ActorControlService service = service(4, 4, 64);
        for (int attempt = 1; attempt <= 2; attempt++) {
            var opened = open(service, "open-" + attempt);
            executors.getLast().runNext();
            var closed = service.close("admin", "close-" + attempt, closeRequest(opened));
            executors.getLast().runNext();
            assertEquals("SUCCEEDED", service.get("admin", id(closed), 0, 100).get("status"));
        }
        assertEquals(4, service.publicCapabilities().get("operation_history_size"));
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ObservationException.class, () -> open(service, "open-three")).status());
        assertEquals("CLOSED", session(open(service, "open-1")).get("state"));
        verify(backend, times(2)).open(anyString(), any(), any(), any());
    }

    @Test
    void aCloseRetryCannotSpendAnotherSessionsFirstCloseAndStoppingStillCleansAtCapacity() {
        ActorControlService service = service(4, 4, 64);
        NativeActorHandle secondary = mock(NativeActorHandle.class);
        when(backend.open(eq("secondary"), any(), any(), any())).thenReturn(secondary);
        when(backend.isCurrent(secondary)).thenReturn(true);
        when(backend.snapshot(secondary)).thenReturn(Map.of("character_id", 74, "logged_in_world", true));
        when(backend.close(secondary)).thenReturn(Map.of("native_cleanup_verified", true, "persisted_state_verified", true));
        when(backend.close(handle)).thenReturn(
                Map.of("native_cleanup_verified", true, "persisted_state_verified", false),
                Map.of("native_cleanup_verified", true, "persisted_state_verified", true));

        var first = open(service, "open-primary");
        executors.getFirst().runNext();
        var second = service.open("admin", "open-secondary", new ActorOpenRequest("secondary", 0, 1));
        executors.getLast().runNext();
        var failedClose = service.close("admin", "close-primary", closeRequest(first));
        executors.getFirst().runNext();
        assertEquals("INDETERMINATE", service.get("admin", id(failedClose), 0, 100).get("status"));

        ObservationException retry = assertThrows(ObservationException.class,
                () -> service.close("admin", "retry-primary", closeRequest(first)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, retry.status());
        assertEquals("actor_close_retry_history_capacity_exceeded", retry.reason());
        assertEquals(3, service.publicCapabilities().get("operation_history_size"));
        assertEquals(1, service.publicCapabilities().get("reserved_first_closes"));
        assertEquals(id(failedClose), id(service.close("admin", "close-primary", closeRequest(first))));

        var secondClose = service.close("admin", "close-secondary", closeRequest(second));
        executors.getLast().runNext();
        assertEquals("SUCCEEDED", service.get("admin", id(secondClose), 0, 100).get("status"));
        assertEquals(4, service.publicCapabilities().get("operation_history_size"));
        assertEquals("QUARANTINED", session(service.get("admin", id(failedClose), 0, 100)).get("state"));
        assertThrows(ObservationException.class, () -> service.close("admin", "retry-primary", closeRequest(first)));

        AcceptanceObservationRuntime.onWorldStopped();
        service.onWorldStopping();
        executors.getFirst().runNext();
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        assertEquals(4, service.publicCapabilities().get("operation_history_size"));
        assertEquals("CLOSED", session(service.get("admin", id(failedClose), 0, 100)).get("state"));
        verify(backend, times(2)).close(handle);
        verify(backend).close(secondary);
    }

    @Test
    void lifecycleEventWindowsReportLossAndRejectAnAheadCursor() {
        ActorControlService service = service(4, 20, 2);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        var view = service.get("admin", id(opened), 0, 1);
        var events = events(view);
        assertEquals(true, events.get("cursor_expired"));
        assertEquals(true, events.get("truncated"));
        assertEquals(2L, events.get("next_cursor"));
        assertEquals(1, ((List<?>) events.get("items")).size());
        assertEquals("operation_cursor_ahead", assertThrows(ObservationException.class,
                () -> service.get("admin", id(opened), 99, 100)).reason());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                () -> service.get("admin", id(opened), 0, 501)).status());
    }

    @Test
    void aRejectedQueueDoesNotLeaveAnOperationForeverQueuedOrCreateNativeState() {
        ActorControlService service = new ActorControlService(backend, 4, 20, 64, profile -> {
            ManualExecutor executor = new ManualExecutor();
            executor.shutdown();
            return executor;
        }, Duration.ZERO);
        services.add(service);
        service.onWorldStarted();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ObservationException.class, () -> open(service, "open-one")).status());
        var duplicate = open(service, "open-one");
        assertEquals("FAILED", duplicate.get("status"));
        assertEquals("FAILED", session(duplicate).get("state"));
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        verify(backend, never()).open(anyString(), any(), any(), any());
    }

    @Test
    void unexpectedBackendFailureIsPrivateAndRetainsAQuarantineInsteadOfAssumingRollback() {
        ActorControlService service = service(4, 20, 64);
        when(backend.open(anyString(), any(), any(), any())).thenThrow(new IllegalStateException("password=must-not-escape"));
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        var failed = service.get("admin", id(opened), 0, 100);
        assertEquals("INDETERMINATE", failed.get("status"));
        assertEquals("QUARANTINED", session(failed).get("state"));
        assertFalse(failed.toString().contains("must-not-escape"));
        assertEquals("profile_in_use", assertThrows(ObservationException.class, () -> open(service, "open-two")).reason());
    }

    @Test
    void aNativeActorThatLeavesItsWorldIsNoLongerAControllableCurrentActor() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        when(backend.isCurrent(handle)).thenReturn(false);
        var lost = service.get("admin", id(opened), 0, 100);
        assertEquals("LOST", session(lost).get("state"));
        assertEquals(false, session(lost).get("actor_current"));
        assertTrue(service.currentActor("admin", sessionId(opened), generation(opened)).isEmpty());
    }

    @Test
    void repeatingASuccessfulOpenRefreshesItsCurrentActorWithoutRepeatingTheNativeOpen() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        when(backend.isCurrent(handle)).thenReturn(false);

        var duplicate = open(service, "open-one");
        assertEquals(id(opened), id(duplicate));
        assertEquals("SUCCEEDED", duplicate.get("status"));
        assertEquals("LOST", session(duplicate).get("state"));
        assertEquals(false, session(duplicate).get("actor_current"));
        assertNull(session(duplicate).get("actor"));
        verify(backend).open(anyString(), any(), any(), any());
    }

    @Test
    void cancellingATerminalOpenRefreshesItsCurrentActorInsteadOfReturningItsOldOnlineProjection() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        AcceptanceObservationRuntime.onWorldStopped();

        var cancelled = service.cancel("admin", "cancel-completed", id(opened));
        assertEquals("SUCCEEDED", cancelled.get("status"));
        assertEquals(false, cancelled.get("cancel_requested"));
        assertEquals("LOST", session(cancelled).get("state"));
        assertEquals(false, session(cancelled).get("actor_current"));
        assertNull(session(cancelled).get("actor"));
        verify(backend, never()).close(handle);
    }

    @Test
    void aSlowProjectionDoesNotHoldTheControlLockOrPublishAfterItsActorHasClosed() throws Exception {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.snapshot(handle)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return Map.of("character_id", 73, "logged_in_world", true);
        });
        List<Map<String, Object>> observation = new ArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> observation.add(service.get("admin", id(opened), 0, 100)));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AcceptanceObservationRuntime.onWorldStopped();
            service.onWorldStopping();
            assertEquals(false, service.publicCapabilities().get("actor_accepting_requests"));
            executors.getFirst().runNext();
        } finally {
            complete.countDown();
            reader.join(5_000);
        }
        assertFalse(reader.isAlive());
        assertEquals("CLOSED", session(observation.getFirst()).get("state"));
        assertNull(session(observation.getFirst()).get("actor"));
        verify(backend).close(handle);
    }

    private ActorControlService service(int sessionLimit, int historyLimit, int eventLimit) {
        ActorControlService service = new ActorControlService(backend, sessionLimit, historyLimit, eventLimit,
                profile -> {
                    ManualExecutor executor = new ManualExecutor();
                    executors.add(executor);
                    return executor;
                }, Duration.ZERO);
        services.add(service);
        service.onWorldStarted();
        return service;
    }

    private static Map<String, Object> open(ActorControlService service, String request) {
        return service.open("admin", request, new ActorOpenRequest("primary", 0, 1));
    }
    private static String id(Map<String, Object> value) { return (String) value.get("operation_id"); }
    private static String sessionId(Map<String, Object> value) { return (String) value.get("session_id"); }
    private static long generation(Map<String, Object> value) { return ((Number) value.get("session_generation")).longValue(); }
    private static ActorCloseRequest closeRequest(Map<String, Object> value) { return new ActorCloseRequest(sessionId(value), generation(value)); }
    @SuppressWarnings("unchecked") private static Map<String, Object> session(Map<String, Object> value) { return (Map<String, Object>) value.get("session"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> actor(Map<String, Object> value) { return (Map<String, Object>) session(value).get("actor"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> result(Map<String, Object> value) { return (Map<String, Object>) value.get("result"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> events(Map<String, Object> value) { return (Map<String, Object>) value.get("events"); }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;
        @Override public synchronized void shutdown() { shutdown = true; }
        @Override public synchronized List<Runnable> shutdownNow() { shutdown = true; List<Runnable> remaining = new ArrayList<>(queue); queue.clear(); return remaining; }
        @Override public synchronized boolean isShutdown() { return shutdown; }
        @Override public synchronized boolean isTerminated() { return shutdown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public synchronized void execute(Runnable command) { if (shutdown) throw new RejectedExecutionException(); queue.addLast(command); }
        void runNext() { Runnable command; synchronized (this) { command = queue.pollFirst(); } if (command != null) command.run(); }
        void drain() { for (int attempts = 0; attempts < 20; attempts++) { synchronized (this) { if (queue.isEmpty()) return; } runNext(); } throw new AssertionError("test executor did not drain"); }
    }
}
