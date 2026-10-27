package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.AcceptanceObservationRuntime;
import org.gms.soloacceptance.AcceptanceObservationService;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActorControlServiceTest {
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private AcceptanceClient client;
    private final List<ManualExecutor> executors = new ArrayList<>();
    private final List<ActorControlService> services = new ArrayList<>();

    @BeforeEach
    void prepare() {
        CharacterTestContext.install();
        AcceptanceObservationRuntime.onWorldStarted();
        backend = mock(NativeActorBackend.class);
        handle = mock(NativeActorHandle.class);
        client = mock(AcceptanceClient.class);
        when(handle.client()).thenReturn(client);
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

    @Test
    void actorObservationReturnsCurrentSceneAndPacketWindowWithoutConsumingControlHistoryOrQueue() {
        ActorControlService service = service(4, 2, 64);
        AcceptanceObservationService observations = mock(AcceptanceObservationService.class);
        org.gms.client.Character character = mock(org.gms.client.Character.class);
        when(handle.character()).thenReturn(character);
        Map<String, Object> scene = Map.of("scene_ref", "scene-current", "map_id", 10000,
                "runtime_generation", AcceptanceObservationRuntime.snapshot().generation());
        when(observations.contextForActor(character)).thenReturn(scene);
        service.setObservations(observations);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        Map<String, Object> window = Map.of("items", List.of(Map.of("sequence", 1L, "text", "实际公告")),
                "next_cursor", 1L, "latest_cursor", 2L, "oldest_cursor", 1L, "truncated", true,
                "cursor_expired", false, "capacity", 256);
        when(client.packetEvents(0, 1)).thenReturn(window);

        var first = service.observe("admin", observeRequest(opened, 0, 1));
        var repeated = service.observe("admin", observeRequest(opened, 0, 1));
        assertEquals(1, first.get("schema_version"));
        assertNotNull(Instant.parse((String) first.get("captured_at")));
        assertEquals("actor", first.get("scope"));
        assertEquals(opened.get("runtime_generation"), first.get("runtime_generation"));
        assertEquals("server_diagnostic", first.get("information_level"));
        assertEquals("best_effort", first.get("snapshot_consistency"));
        assertEquals(sessionId(opened), first.get("session_id"));
        assertEquals(generation(opened), first.get("session_generation"));
        assertEquals("primary", first.get("profile_id"));
        assertEquals(scene, ((Map<?, ?>) first.get("actor")).get("scene"));
        assertEquals(List.of(), ((Map<?, ?>) first.get("actor")).get("packet_events"));
        assertEquals(window, first.get("events"));
        assertEquals(first.get("events"), repeated.get("events"));
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        assertEquals(1, service.publicCapabilities().get("idempotency_records"));
        assertEquals(1, service.publicCapabilities().get("reserved_first_closes"));
        assertEquals(AcceptanceClient.packetObservationCapabilities(), service.publicCapabilities().get("packet_observation"));
        assertTrue(executors.getFirst().queue.isEmpty());
        verify(client, times(2)).packetEvents(0, 1);
    }

    @Test
    void actorObservationValidatesOwnerIdentityAndWindowBeforeReadingPacketText() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ObservationException.class,
                () -> service.observe(null, observeRequest(opened, 0, 1))).status());
        for (ActorObserveRequest invalid : List.of(
                new ActorObserveRequest(null, 1L, 0L, 1),
                new ActorObserveRequest("bad session", 1L, 0L, 1),
                new ActorObserveRequest(sessionId(opened), null, 0L, 1),
                new ActorObserveRequest(sessionId(opened), 0L, 0L, 1))) {
            assertEquals("invalid_actor_session", assertThrows(ObservationException.class,
                    () -> service.observe("admin", invalid)).reason());
        }
        assertEquals("invalid_actor_session", assertThrows(ObservationException.class,
                () -> service.observe("admin", null)).reason());
        for (ActorObserveRequest invalid : List.of(observeRequest(opened, -1, 1),
                observeRequest(opened, 0, 0), observeRequest(opened, 0, 501))) {
            assertEquals("invalid_packet_window", assertThrows(ObservationException.class,
                    () -> service.observe("admin", invalid)).reason());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ObservationException.class,
                () -> service.observe("another-admin", observeRequest(opened, 0, 1))).status());
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class, () -> service.observe("admin",
                new ActorObserveRequest(sessionId(opened), generation(opened) + 1, 0L, 1))).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ObservationException.class, () -> service.observe("admin",
                new ActorObserveRequest("session-unknown", 1L, 0L, 1))).status());
        assertEquals("actor_not_observable", assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(opened, 0, 1))).reason());
        verifyNoInteractions(client);
    }

    @Test
    void actorObservationRejectsClosingClosedCancelledAndOldWorldSessionsBeforeReadingText() {
        ActorControlService service = service(4, 20, 64);
        var cancelled = open(service, "open-cancelled");
        service.cancel("admin", "cancel-opening", id(cancelled));
        executors.getLast().runNext();
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(cancelled, 0, 1))).status());

        var opened = open(service, "open-current");
        executors.getLast().runNext();
        service.close("admin", "close-current", closeRequest(opened));
        assertEquals("actor_not_observable", assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(opened, 0, 1))).reason());
        executors.getLast().runNext();
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(opened, 0, 1))).status());

        var current = open(service, "open-next");
        executors.getLast().runNext();
        AcceptanceObservationRuntime.onWorldStopped();
        assertEquals("actor_world_offline", assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(current, 0, 1))).reason());
        AcceptanceObservationRuntime.onWorldStarted();
        service.onWorldStarted();
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(current, 0, 1))).status());
        verifyNoInteractions(client);
    }

    @Test
    void actorObservationRejectsLostAndQuarantinedSessionsBeforeReadingText() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        when(backend.isCurrent(handle)).thenReturn(false);
        assertEquals("stale_actor_session", assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(opened, 0, 1))).reason());
        assertEquals("LOST", session(service.get("admin", id(opened), 0, 100)).get("state"));
        when(backend.close(handle)).thenReturn(Map.of("native_cleanup_verified", false, "persisted_state_verified", false));
        service.close("admin", "close-lost", closeRequest(opened));
        executors.getFirst().runNext();
        assertEquals("QUARANTINED", session(service.get("admin", id(opened), 0, 100)).get("state"));
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(opened, 0, 1))).status());
        verifyNoInteractions(client);
    }

    @Test
    void actorObservationPreservesPacketCursorErrorsWithoutAllocatingAnOperation() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        when(client.packetEvents(Long.MAX_VALUE, 500)).thenThrow(
                new ObservationException(HttpStatus.BAD_REQUEST, "packet_cursor_ahead"));
        ObservationException failure = assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(opened, Long.MAX_VALUE, 500)));
        assertEquals(HttpStatus.BAD_REQUEST, failure.status());
        assertEquals("packet_cursor_ahead", failure.reason());
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        assertEquals(1, service.publicCapabilities().get("idempotency_records"));
        assertTrue(executors.getFirst().queue.isEmpty());
    }

    @Test
    void closingDuringASlowActorSnapshotDoesNotBlockOrExposeTheClosedSessionsPacketText() throws Exception {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.snapshot(handle)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return Map.of("logged_in_world", true);
        });
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread reader = observeAsync(service, observeRequest(opened, 0, 1), outcome);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.close("admin", "close-during-observation", closeRequest(opened));
            executors.getFirst().runNext();
        } finally {
            complete.countDown();
            reader.join(5_000);
        }
        assertFalse(reader.isAlive());
        ObservationException failure = assertInstanceOf(ObservationException.class, outcome.get());
        assertEquals(HttpStatus.CONFLICT, failure.status());
        assertEquals("runtime_changed", failure.reason());
        verifyNoInteractions(client);
    }

    @Test
    void stoppingDuringAPacketWindowReadRejectsTheCollectedTextAndDoesNotHoldTheControlLock() throws Exception {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(client.packetEvents(0, 1)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return Map.of("items", List.of(Map.of("text", "旧会话正文")));
        });
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread reader = observeAsync(service, observeRequest(opened, 0, 1), outcome);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AcceptanceObservationRuntime.onWorldStopped();
            service.onWorldStopping();
            executors.getFirst().runNext();
            assertEquals(0, service.publicCapabilities().get("active_sessions"));
        } finally {
            complete.countDown();
            reader.join(5_000);
        }
        assertFalse(reader.isAlive());
        ObservationException failure = assertInstanceOf(ObservationException.class, outcome.get());
        assertEquals(HttpStatus.CONFLICT, failure.status());
        assertEquals("runtime_changed", failure.reason());
    }

    @Test
    void nativeIdentityLossDuringThePacketReadRejectsItsTextAndMarksTheSessionLost() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        when(client.packetEvents(0, 1)).thenAnswer(invocation -> {
            when(backend.isCurrent(handle)).thenReturn(false);
            return Map.of("items", List.of(Map.of("text", "失效角色正文")));
        });
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class,
                () -> service.observe("admin", observeRequest(opened, 0, 1))).status());
        assertEquals("LOST", session(service.get("admin", id(opened), 0, 100)).get("state"));
    }

    @Test
    void chatActionsUseTheExistingSerialOperationsAndNeverReplayAKnownRequestAfterClosing() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        String secretText = "聊天验收 unique-private-body";
        ActorActRequest request = chatRequest(opened, secretText);
        var first = service.act("admin", "chat-one", request);
        assertEquals("QUEUED", first.get("status"));
        assertEquals("ACTION", first.get("kind"));
        assertEquals("chat", first.get("action"));
        assertEquals(id(first), id(service.act("admin", "chat-one", request)));
        var second = service.act("admin", "chat-two", chatRequest(opened, "第二条"));
        assertEquals(2, executors.getFirst().queue.size());
        verify(backend, never()).chat(any(), any());
        executors.getFirst().runNext();
        executors.getFirst().runNext();
        var ordering = inOrder(backend);
        ordering.verify(backend).chat(handle, secretText);
        ordering.verify(backend).chat(handle, "第二条");
        var firstResult = service.get("admin", id(first), 0, 100);
        assertEquals("SUCCEEDED", firstResult.get("status"));
        assertEquals(false, result(firstResult).get("cancel_too_late"));
        assertEquals(true, result(firstResult).get("native_dispatch_started"));
        assertEquals(true, result(firstResult).get("echo_matched"));
        assertEquals(73, result(firstResult).get("sender_character_id"));
        assertEquals(31L, result(firstResult).get("packet_cursor_before"));
        assertEquals(32L, result(firstResult).get("echo_sequence"));
        assertEquals(32L, result(firstResult).get("packet_cursor_after"));
        assertEquals("SUCCEEDED", service.get("admin", id(second), 0, 100).get("status"));
        assertFalse(service.get("admin", id(first), 0, 100).toString().contains(secretText));
        var requestsField = ActorControlService.class.getDeclaredField("requests");
        requestsField.setAccessible(true);
        assertFalse(requestsField.get(service).toString().contains(secretText));
        assertTrue(requestsField.get(service).toString().contains("ActionFingerprint"));
        service.close("admin", "close-chat", closeRequest(opened));
        executors.getFirst().runNext();
        var replay = service.act("admin", "chat-one", request);
        assertEquals(id(first), id(replay));
        assertEquals("SUCCEEDED", replay.get("status"));
        assertEquals("CLOSED", session(replay).get("state"));
        verify(backend, times(2)).chat(any(), any());
        assertEquals(7, service.publicCapabilities().get("maximum_pending_actions_per_session"));
        assertEquals(8, service.publicCapabilities().get("actor_executor_queue_capacity"));
        assertEquals(List.of("chat"), service.publicCapabilities().get("actions"));
    }

    @Test
    void actionIdReuseWithChangedTextActionContextOrGenerationConflictsBeforeASecondDispatch() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        ActorActRequest original = chatRequest(opened, "原文");
        service.act("admin", "chat-one", original);
        for (ActorActRequest changed : List.of(chatRequest(opened, "不同正文"),
                new ActorActRequest(original.sessionId(), original.expectedGeneration(), "npc", original.parameters(), original.expectedContext()),
                new ActorActRequest(original.sessionId(), original.expectedGeneration() + 1, "chat", original.parameters(), original.expectedContext()),
                new ActorActRequest(original.sessionId(), original.expectedGeneration(), "chat", original.parameters(),
                        new ActorActRequest.ExpectedContext("scene-other", original.expectedContext().runtimeGeneration())))) {
            assertEquals("request_id_conflict", assertThrows(ObservationException.class,
                    () -> service.act("admin", "chat-one", changed)).reason());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ObservationException.class,
                () -> service.act("another-admin", "new-chat", original)).status());
        verify(backend, times(1)).validateChat(handle, "原文");
        verify(backend, never()).chat(any(), any());
    }

    @Test
    void cancellingAQueuedChatDoesNotCloseTheActorAndOnlyReleasesItsSlotAfterDequeue() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        var action = service.act("admin", "chat-one", chatRequest(opened, "取消这条"));
        assertEquals("CANCEL_REQUESTED", service.cancel("admin", "cancel-chat", id(action)).get("status"));
        executors.getFirst().runNext();
        var cancelled = service.get("admin", id(action), 0, 100);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals("chat_cancelled_before_dispatch", result(cancelled).get("observed_outcome"));
        assertEquals(false, result(cancelled).get("native_dispatch_started"));
        assertEquals("OPEN", session(cancelled).get("state"));
        verify(backend, never()).chat(any(), any());
        verify(backend, never()).close(any());
        var next = service.act("admin", "chat-two", chatRequest(opened, "可以发这条"));
        executors.getFirst().runNext();
        assertEquals("SUCCEEDED", service.get("admin", id(next), 0, 100).get("status"));
    }

    @Test
    void sceneContextIsCheckedAgainAtDequeueAndOrdinaryFailureCannotReleaseOrReopenTheActor() {
        ActorControlService service = service(4, 20, 64);
        AcceptanceObservationService observations = prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        var action = service.act("admin", "chat-one", chatRequest(opened, "排队正文"));
        when(observations.contextForActor(any())).thenReturn(Map.of("scene_ref", "scene-next",
                "runtime_generation", opened.get("runtime_generation")));
        executors.getFirst().runNext();
        var failed = service.get("admin", id(action), 0, 100);
        assertEquals("FAILED", failed.get("status"));
        assertEquals(Map.of("reason", "actor_context_mismatch"), failed.get("error"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertEquals("OPEN", session(failed).get("state"));
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        verify(backend, never()).chat(any(), any());
        verify(backend, never()).close(any());
    }

    @Test
    void nativeValidationFailuresBeforeDispatchStayFailedWithoutQuarantiningTheSession() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        doNothing().doThrow(new ObservationException(HttpStatus.BAD_REQUEST, "chat_text_not_encodable"))
                .when(backend).validateChat(handle, "需要再验证");
        var action = service.act("admin", "chat-one", chatRequest(opened, "需要再验证"));
        executors.getFirst().runNext();
        var failed = service.get("admin", id(action), 0, 100);
        assertEquals("FAILED", failed.get("status"));
        assertEquals(Map.of("reason", "chat_text_not_encodable"), failed.get("error"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertEquals("OPEN", session(failed).get("state"));
        verify(backend, never()).chat(any(), any());
        verify(backend, never()).close(any());
    }

    @Test
    void backendRejectionBeforeItsNativeDispatchIsNotConfusedWithTheCoordinatorBoundary() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        when(backend.chat(handle, "原生再验证")).thenThrow(new ObservationException(HttpStatus.CONFLICT, "actor_session_stale"));
        var action = service.act("admin", "chat-one", chatRequest(opened, "原生再验证"));
        executors.getFirst().runNext();
        var failed = service.get("admin", id(action), 0, 100);
        assertEquals("FAILED", failed.get("status"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertEquals(false, result(failed).get("cancel_too_late"));
        assertEquals("OPEN", session(failed).get("state"));
        verify(backend, never()).close(any());
    }

    @Test
    void failedUnknownAndUnexpectedNativeChatOutcomesDoNotMutateTheActorLeaseOrLeakText() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        when(backend.chat(handle, "限频样例")).thenReturn(Map.of("status", "FAILED", "error_reason", "native_chat_not_broadcast",
                "result", Map.of("native_dispatch_started", true, "echo_matched", false)));
        Map<String, Object> unknown = new java.util.LinkedHashMap<>(Map.of("native_dispatch_started", true,
                "echo_matched", false, "native_handler_error", true, "text", "不得保留的正文"));
        unknown.put("packet_cursor_after", null);
        when(backend.chat(handle, "未知样例")).thenReturn(Map.of("status", "INDETERMINATE", "error_reason", "native_chat_outcome_unknown",
                "result", unknown));
        when(backend.chat(handle, "异常样例")).thenThrow(new IllegalStateException("private text and password must not escape"));
        for (String text : List.of("限频样例", "未知样例", "异常样例")) {
            var action = service.act("admin", "chat-" + text.hashCode(), chatRequest(opened, text));
            executors.getFirst().runNext();
            var finished = service.get("admin", id(action), 0, 100);
            assertEquals("限频样例".equals(text) ? "FAILED" : "INDETERMINATE", finished.get("status"));
            assertEquals("OPEN", session(finished).get("state"));
            assertFalse(finished.toString().contains("不得保留的正文"));
            assertFalse(finished.toString().contains("private text"));
            if ("未知样例".equals(text)) {
                assertEquals(true, result(finished).get("native_handler_error"));
                assertTrue(result(finished).containsKey("packet_cursor_after"));
                assertNull(result(finished).get("packet_cursor_after"));
            }
        }
        verify(backend, never()).close(any());
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
    }

    @Test
    void lateCancellationPreservesTheObservedBroadcastAndCannotRestoreAClosingSession() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.chat(handle, "正在发言")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return chatSuccess();
        });
        var action = service.act("admin", "chat-one", chatRequest(opened, "正在发言"));
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.cancel("admin", "cancel-running-chat", id(action));
            service.close("admin", "close-chat", closeRequest(opened));
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
        var finished = service.get("admin", id(action), 0, 100);
        assertEquals("SUCCEEDED", finished.get("status"));
        assertEquals(true, result(finished).get("cancel_too_late"));
        assertEquals(true, result(finished).get("echo_matched"));
        assertEquals("CLOSING", session(finished).get("state"));
        executors.getFirst().runNext();
        assertEquals("CLOSED", session(service.get("admin", id(action), 0, 100)).get("state"));
        verify(backend).chat(handle, "正在发言");
        verify(backend).close(handle);
    }

    @Test
    void sevenOccupiedActionSlotsKeepCancelledQueuedTasksCountedAndLeaveAPhysicalCloseSlot() throws Exception {
        CountDownLatch openedTask = new CountDownLatch(1);
        CountDownLatch allTasks = new CountDownLatch(9);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), Thread.ofVirtual().factory()) {
            @Override protected void afterExecute(Runnable task, Throwable failure) {
                openedTask.countDown();
                allTasks.countDown();
            }
        };
        ActorControlService service = new ActorControlService(backend, 4, 40, 64, profile -> executor, Duration.ZERO);
        services.add(service);
        service.onWorldStarted();
        prepareChat(service);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.chat(handle, "占住执行线程")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return chatSuccess();
        });
        try {
            var opened = open(service, "open-chat");
            assertTrue(openedTask.await(5, TimeUnit.SECONDS));
            List<Map<String, Object>> actions = new ArrayList<>();
            actions.add(service.act("admin", "chat-running", chatRequest(opened, "占住执行线程")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 1; i < 7; i++) actions.add(service.act("admin", "chat-queued-" + i, chatRequest(opened, "排队" + i)));
            assertEquals(6, executor.getQueue().size());
            service.cancel("admin", "cancel-still-queued", id(actions.get(1)));
            assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                    () -> service.act("admin", "chat-eighth", chatRequest(opened, "不能挤占关闭槽"))).reason());
            assertEquals(6, executor.getQueue().size());
            var closing = service.close("admin", "close-with-full-actions", closeRequest(opened));
            assertEquals(7, executor.getQueue().size());
            assertEquals("actor_not_actable", assertThrows(ObservationException.class,
                    () -> service.act("admin", "chat-after-close", chatRequest(opened, "关闭后不能排队"))).reason());
            complete.countDown();
            assertTrue(allTasks.await(5, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", service.get("admin", id(actions.getFirst()), 0, 100).get("status"));
            for (var queued : actions.subList(1, actions.size())) {
                var cancelled = service.get("admin", id(queued), 0, 100);
                assertEquals("CANCELLED", cancelled.get("status"));
                assertEquals("CLOSED", session(cancelled).get("state"));
            }
            assertEquals("SUCCEEDED", service.get("admin", id(closing), 0, 100).get("status"));
            verify(backend, times(1)).chat(any(), any());
            verify(backend).close(handle);
        } finally {
            complete.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actionsCannotSpendAnySessionsFirstCloseHistoryAndCloseCancelsTheirUndispatchedWork() {
        ActorControlService service = service(4, 6, 64);
        prepareChat(service);
        var first = open(service, "open-first");
        executors.getFirst().runNext();
        var second = service.open("admin", "open-second", new ActorOpenRequest("secondary", 0, 1));
        executors.getLast().runNext();
        var firstAction = service.act("admin", "chat-first", chatRequest(first, "第一组"));
        var secondAction = service.act("admin", "chat-second", chatRequest(second, "第二组"));
        assertEquals("actor_history_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "chat-over-history", chatRequest(first, "不能占用关闭历史"))).reason());
        var closeFirst = service.close("admin", "close-first", closeRequest(first));
        var closeSecond = service.close("admin", "close-second", closeRequest(second));
        executors.getFirst().runNext();
        assertEquals("CLOSING", session(service.get("admin", id(firstAction), 0, 100)).get("state"));
        executors.getFirst().runNext();
        executors.getLast().drain();
        assertEquals("CANCELLED", service.get("admin", id(firstAction), 0, 100).get("status"));
        assertEquals("CANCELLED", service.get("admin", id(secondAction), 0, 100).get("status"));
        assertEquals("SUCCEEDED", service.get("admin", id(closeFirst), 0, 100).get("status"));
        assertEquals("SUCCEEDED", service.get("admin", id(closeSecond), 0, 100).get("status"));
        assertEquals(6, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).chat(any(), any());
    }

    @Test
    void worldStoppingCancelsEveryUndispatchedActionWithoutRestoringAnOpenSession() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        var first = service.act("admin", "chat-first", chatRequest(opened, "尚未发一"));
        var second = service.act("admin", "chat-second", chatRequest(opened, "尚未发二"));
        AcceptanceObservationRuntime.onWorldStopped();
        service.onWorldStopping();
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(first), 0, 100).get("status"));
        assertEquals("QUARANTINED", session(service.get("admin", id(first), 0, 100)).get("state"));
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(second), 0, 100).get("status"));
        assertEquals("QUARANTINED", session(service.get("admin", id(second), 0, 100)).get("state"));
        executors.getFirst().runNext();
        assertEquals("CLOSED", session(service.get("admin", id(second), 0, 100)).get("state"));
        verify(backend, never()).chat(any(), any());
        verify(backend).close(handle);
    }

    @Test
    void aRejectedActionSubmissionReturnsItsSlotAndKeepsTheActorLease() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        executors.getFirst().shutdown();
        ActorActRequest request = chatRequest(opened, "不会排队");
        assertEquals("actor_queue_full", assertThrows(ObservationException.class,
                () -> service.act("admin", "chat-rejected", request)).reason());
        var rejected = service.act("admin", "chat-rejected", request);
        assertEquals("FAILED", rejected.get("status"));
        assertEquals("OPEN", session(rejected).get("state"));
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        verify(backend, never()).chat(any(), any());
        verify(backend, never()).close(any());
    }

    private AcceptanceObservationService prepareChat(ActorControlService service) {
        AcceptanceObservationService observations = mock(AcceptanceObservationService.class);
        org.gms.client.Character character = mock(org.gms.client.Character.class);
        when(handle.character()).thenReturn(character);
        when(observations.contextForActor(any())).thenReturn(Map.of("scene_ref", "scene-current",
                "runtime_generation", AcceptanceObservationRuntime.snapshot().generation()));
        service.setObservations(observations);
        when(backend.chat(any(), any())).thenReturn(chatSuccess());
        return observations;
    }

    private static Map<String, Object> chatSuccess() {
        return Map.of("status", "SUCCEEDED", "result", Map.of("observed_outcome", "native_chat_broadcast",
                "native_dispatch_started", true, "echo_matched", true, "sender_character_id", 73,
                "echo_sequence", 32L, "packet_cursor_before", 31L, "packet_cursor_after", 32L));
    }

    private static ActorActRequest chatRequest(Map<String, Object> opened, String text) {
        return new ActorActRequest(sessionId(opened), generation(opened), "chat", new ActorActRequest.Parameters(text),
                new ActorActRequest.ExpectedContext("scene-current", (String) opened.get("runtime_generation")));
    }

    private static Thread observeAsync(ActorControlService service, ActorObserveRequest request,
                                       AtomicReference<Object> outcome) {
        return Thread.ofVirtual().start(() -> {
            try {
                outcome.set(service.observe("admin", request));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
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
    private static ActorObserveRequest observeRequest(Map<String, Object> value, long cursor, int limit) {
        return new ActorObserveRequest(sessionId(value), generation(value), cursor, limit);
    }
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
