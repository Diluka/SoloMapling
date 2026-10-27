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
        when(backend.chatTimingCapabilities()).thenReturn(Map.of("chat_minimum_interval_ms", 200L,
                "native_clock_update_interval_ms", 777L));
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
        assertEquals(List.of("chat", "npc_center_open", "npc_cancel", "npc_reply"), service.publicCapabilities().get("actions"));
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
            for (int i = 1; i < 7; i++) {
                ActorActRequest request = i % 3 == 0 ? replyRequest(opened, 37L, "select", 0)
                        : i % 2 == 0 ? npcRequest(opened, "npc_center_open", null) : chatRequest(opened, "排队" + i);
                actions.add(service.act("admin", "action-queued-" + i, request));
            }
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
            verify(backend, never()).npcCenterOpen(any());
            verify(backend, never()).npcReply(any(), anyLong(), any(), any());
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

    @Test
    void capabilitiesPublishTheActualNativeTimingFieldsWithoutAddingDefaultsOrOtherBackendData() {
        ActorControlService service = service(4, 20, 64);
        when(backend.chatTimingCapabilities()).thenReturn(Map.of("chat_minimum_interval_ms", 200,
                "native_clock_update_interval_ms", 319L, "internal_value", "not-public"));
        var capabilities = service.publicCapabilities();
        assertEquals(true, capabilities.get("actor_accepting_requests"));
        assertEquals(200, capabilities.get("chat_minimum_interval_ms"));
        assertEquals(319L, capabilities.get("native_clock_update_interval_ms"));
        assertFalse(capabilities.containsKey("internal_value"));
        verify(backend).chatTimingCapabilities();
    }

    @Test
    void offlineCapabilitiesDoNotInitializeOrReadNativeTimingAndPublishNoClockValue() {
        ActorControlService service = service(4, 20, 64);
        AcceptanceObservationRuntime.onWorldStopped();
        var capabilities = service.publicCapabilities();
        assertEquals(false, capabilities.get("actor_accepting_requests"));
        assertFalse(capabilities.containsKey("native_clock_update_interval_ms"));
        assertFalse(capabilities.containsKey("chat_minimum_interval_ms"));
        verify(backend, never()).chatTimingCapabilities();
    }

    @Test
    void missingNativeTimingKeepsItsFixedServiceUnavailableFailureWithoutPublishingZero() {
        ActorControlService service = service(4, 20, 64);
        ObservationException unavailable = new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, "actor_chat_timing_unavailable");
        when(backend.chatTimingCapabilities()).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(ObservationException.class, service::publicCapabilities));
    }

    @Test
    void slowNativeTimingReadDoesNotHoldTheControlLockOrPublishThePreviousWorldsTiming() throws Exception {
        ActorControlService service = service(4, 20, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.chatTimingCapabilities()).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return Map.of("chat_minimum_interval_ms", 200, "native_clock_update_interval_ms", 319L);
        });
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try { outcome.set(service.publicCapabilities()); }
            catch (Throwable failure) { outcome.set(failure); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AcceptanceObservationRuntime.onWorldStopped();
            service.onWorldStopping();
            AcceptanceObservationRuntime.onWorldStarted();
            service.onWorldStarted();
        } finally {
            complete.countDown();
            reader.join(5_000);
        }
        assertFalse(reader.isAlive());
        Map<?, ?> capabilities = assertInstanceOf(Map.class, outcome.get());
        assertEquals(false, capabilities.get("actor_accepting_requests"));
        assertFalse(capabilities.containsKey("native_clock_update_interval_ms"));
        assertFalse(capabilities.containsKey("chat_minimum_interval_ms"));
    }

    @Test
    void npcOpenAndCancelUseTheSameActionQueueAndKeepOnlyTheirFixedNativeEvidence() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareNpc(service);
        var opened = open(service, "open-npc");
        executors.getFirst().runNext();
        var center = service.act("admin", "center-one", npcRequest(opened, "npc_center_open", null));
        assertEquals("npc_center_open", center.get("action"));
        assertEquals("QUEUED", center.get("status"));
        verify(backend, never()).npcCenterOpen(any());
        executors.getFirst().runNext();
        var result = service.get("admin", id(center), 0, 100);
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals("center_dialogue_opened", result(result).get("observed_outcome"));
        assertEquals(true, result(result).get("active"));
        assertEquals(37L, result(result).get("dialogue_version"));
        assertEquals(37L, result(result).get("packet_sequence"));
        assertEquals(9900001, result(result).get("npc_template_id"));
        assertEquals(4, result(result).get("message_type"));
        assertEquals(false, result(result).get("cancel_too_late"));
        assertFalse(result.toString().contains("不能进入历史的中心正文"));
        assertFalse(result(result).containsKey("text"));
        assertFalse(result(result).containsKey("parameters"));
        var cancel = service.act("admin", "cancel-center", npcRequest(opened, "npc_cancel", 37L));
        executors.getFirst().runNext();
        var cancelled = service.get("admin", id(cancel), 0, 100);
        assertEquals("SUCCEEDED", cancelled.get("status"));
        assertEquals("dialogue_cancelled", result(cancelled).get("observed_outcome"));
        assertEquals(false, result(cancelled).get("active"));
        assertEquals(true, result(cancelled).get("native_dialogue_cleanup_verified"));
        assertEquals("OPEN", session(cancelled).get("state"));
        var ordering = inOrder(backend);
        ordering.verify(backend).npcCenterOpen(handle);
        ordering.verify(backend).npcCancel(handle, 37L);
        verify(backend, never()).chat(any(), any());
        verify(backend, never()).close(any());
        Map<?, ?> capability = (Map<?, ?>) service.publicCapabilities().get("npc_dialogue");
        assertEquals(List.of("native_ui_center"), capability.get("entrypoints"));
        assertEquals(List.of(4, 12), capability.get("message_types"));
        assertEquals("session_packet_sequence", capability.get("version_scope"));
        assertEquals(true, capability.get("cancel_requires_latest_version"));
        assertEquals(500, capability.get("npc_minimum_interval_ms"));
        assertEquals(AcceptanceClient.nativeDialogueCapabilities(), capability);
    }

    @Test
    void npcMetadataDeduplicationSurvivesCloseAndDifferentVersionsCannotReuseTheSameId() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareNpc(service);
        var opened = open(service, "open-npc");
        executors.getFirst().runNext();
        ActorActRequest centerRequest = npcRequest(opened, "npc_center_open", null);
        var center = service.act("admin", "center-one", centerRequest);
        assertEquals(id(center), id(service.act("admin", "center-one", centerRequest)));
        executors.getFirst().runNext();
        ActorActRequest cancelRequest = npcRequest(opened, "npc_cancel", 37L);
        var cancel = service.act("admin", "cancel-center", cancelRequest);
        assertEquals("request_id_conflict", assertThrows(ObservationException.class,
                () -> service.act("admin", "cancel-center", npcRequest(opened, "npc_cancel", 38L))).reason());
        executors.getFirst().runNext();
        service.close("admin", "close-npc", closeRequest(opened));
        executors.getFirst().runNext();
        assertEquals(id(center), id(service.act("admin", "center-one", centerRequest)));
        assertEquals(id(cancel), id(service.act("admin", "cancel-center", cancelRequest)));
        assertEquals("CLOSED", session(service.act("admin", "cancel-center", cancelRequest)).get("state"));
        verify(backend).npcCenterOpen(handle);
        verify(backend).npcCancel(handle, 37L);
        var requestsField = ActorControlService.class.getDeclaredField("requests");
        requestsField.setAccessible(true);
        String history = requestsField.get(service).toString();
        assertFalse(history.contains("不能进入历史的中心正文"));
        assertTrue(history.contains("dialogueVersion=37"));
        assertTrue(history.contains("textHash=null"));
    }

    @Test
    void npcTaggedParametersAreValidatedBeforeAnyNativeWorkOrHistoryIsAccepted() {
        ActorControlService service = service(4, 20, 64);
        prepareNpc(service);
        var opened = open(service, "open-npc");
        executors.getFirst().runNext();
        ActorActRequest base = npcRequest(opened, "npc_center_open", null);
        for (ActorActRequest.Parameters invalid : List.of(new ActorActRequest.Parameters("wrong text"),
                new ActorActRequest.Parameters(null, 37L))) {
            assertEquals("invalid_action_parameters", assertThrows(ObservationException.class,
                    () -> service.act("admin", "invalid-open", new ActorActRequest(base.sessionId(), base.expectedGeneration(),
                            "npc_center_open", invalid, base.expectedContext()))).reason());
        }
        for (Long invalidVersion : java.util.Arrays.asList(null, 0L, -1L)) {
            assertEquals("invalid_dialogue_version", assertThrows(ObservationException.class,
                    () -> service.act("admin", "invalid-cancel", npcRequest(opened, "npc_cancel", invalidVersion))).reason());
        }
        assertEquals("invalid_action_parameters", assertThrows(ObservationException.class,
                () -> service.act("admin", "invalid-chat", new ActorActRequest(base.sessionId(), base.expectedGeneration(),
                        "chat", new ActorActRequest.Parameters("正文", 37L), base.expectedContext()))).reason());
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).validateNpcCenterOpen(any());
        verify(backend, never()).validateNpcCancel(any(), anyLong());
        verify(backend, never()).npcCenterOpen(any());
        verify(backend, never()).npcCancel(any(), anyLong());
    }

    @Test
    void staleNpcVersionsAreCheckedAtAcceptanceAndAgainAfterTheyLeaveTheQueue() {
        ActorControlService service = service(4, 20, 64);
        prepareNpc(service);
        var opened = open(service, "open-npc");
        executors.getFirst().runNext();
        doThrow(new ObservationException(HttpStatus.GONE, "stale_dialogue")).when(backend).validateNpcCancel(handle, 36L);
        assertEquals("stale_dialogue", assertThrows(ObservationException.class,
                () -> service.act("admin", "old-version", npcRequest(opened, "npc_cancel", 36L))).reason());
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        doNothing().doThrow(new ObservationException(HttpStatus.GONE, "stale_dialogue"))
                .when(backend).validateNpcCancel(handle, 37L);
        var action = service.act("admin", "became-old", npcRequest(opened, "npc_cancel", 37L));
        executors.getFirst().runNext();
        var failed = service.get("admin", id(action), 0, 100);
        assertEquals("FAILED", failed.get("status"));
        assertEquals(Map.of("reason", "stale_dialogue"), failed.get("error"));
        assertEquals("action_not_dispatched", result(failed).get("observed_outcome"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertEquals("OPEN", session(failed).get("state"));
        verify(backend, never()).npcCancel(any(), anyLong());
        verify(backend, never()).close(any());
    }

    @Test
    void npcQueuedCancellationLeavesTheNativeDialogueAndActorUntouched() {
        ActorControlService service = service(4, 20, 64);
        prepareNpc(service);
        var opened = open(service, "open-npc");
        executors.getFirst().runNext();
        var action = service.act("admin", "cancel-center", npcRequest(opened, "npc_cancel", 37L));
        service.cancel("admin", "cancel-operation", id(action));
        executors.getFirst().runNext();
        var cancelled = service.get("admin", id(action), 0, 100);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals("action_cancelled_before_dispatch", result(cancelled).get("observed_outcome"));
        assertEquals(false, result(cancelled).get("native_dispatch_started"));
        assertEquals("OPEN", session(cancelled).get("state"));
        verify(backend, never()).npcCancel(any(), anyLong());
        verify(backend, never()).close(any());
    }

    @Test
    void npcFailedAndUnknownNativeOutcomesKeepTheirOwnSemanticsAndNeverExposeMenuText() {
        ActorControlService service = service(4, 30, 64);
        prepareNpc(service);
        var opened = open(service, "open-npc");
        executors.getFirst().runNext();
        for (String action : List.of("npc_center_open", "npc_cancel")) {
            boolean opening = "npc_center_open".equals(action);
            for (String status : List.of("FAILED", "INDETERMINATE")) {
                String expected = opening ? "FAILED".equals(status) ? "native_center_not_opened" : "native_center_outcome_unknown"
                        : "FAILED".equals(status) ? "native_dialogue_not_cancelled" : "native_dialogue_outcome_unknown";
                Map<String, Object> metadata = new java.util.LinkedHashMap<>();
                metadata.put("native_dispatch_started", true);
                metadata.put("active", null);
                metadata.put("packet_cursor_after", null);
                metadata.put("text", "不能泄漏的未知中心正文");
                Map<String, Object> outcome = Map.of("status", status, "error_reason", expected, "result", metadata);
                if (opening) when(backend.npcCenterOpen(handle)).thenReturn(outcome);
                else when(backend.npcCancel(handle, 37L)).thenReturn(outcome);
                var operation = service.act("admin", action + "-" + status, npcRequest(opened, action, opening ? null : 37L));
                executors.getFirst().runNext();
                var finished = service.get("admin", id(operation), 0, 100);
                assertEquals(status, finished.get("status"));
                assertEquals(expected, result(finished).get("observed_outcome"));
                assertEquals(Map.of("reason", expected), finished.get("error"));
                assertFalse(finished.toString().contains("不能泄漏的未知中心正文"));
                assertTrue(result(finished).containsKey("active"));
                assertNull(result(finished).get("active"));
                assertEquals("OPEN", session(finished).get("state"));
            }
        }
        when(backend.npcCenterOpen(handle)).thenThrow(new IllegalStateException("private NPC failure body"));
        var unknown = service.act("admin", "npc-uncaught", npcRequest(opened, "npc_center_open", null));
        executors.getFirst().runNext();
        var finished = service.get("admin", id(unknown), 0, 100);
        assertEquals("INDETERMINATE", finished.get("status"));
        assertEquals("native_npc_outcome_unknown", result(finished).get("observed_outcome"));
        assertFalse(finished.toString().contains("private NPC"));
        verify(backend, never()).close(any());
    }

    @Test
    void npcActionsPreserveAllCloseHistoryAndQueuedWorkCannotOpenDialoguesAfterClose() {
        ActorControlService service = service(4, 6, 64);
        prepareNpc(service);
        var first = open(service, "open-first");
        executors.getFirst().runNext();
        var second = service.open("admin", "open-second", new ActorOpenRequest("secondary", 0, 1));
        executors.getLast().runNext();
        var firstAction = service.act("admin", "center-first", npcRequest(first, "npc_center_open", null));
        var secondAction = service.act("admin", "cancel-second", npcRequest(second, "npc_cancel", 37L));
        assertEquals("actor_history_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "center-over-capacity", npcRequest(first, "npc_center_open", null))).reason());
        service.close("admin", "close-first", closeRequest(first));
        service.close("admin", "close-second", closeRequest(second));
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(firstAction), 0, 100).get("status"));
        assertEquals("CLOSING", session(service.get("admin", id(firstAction), 0, 100)).get("state"));
        executors.getFirst().runNext();
        executors.getLast().drain();
        assertEquals("CANCELLED", service.get("admin", id(secondAction), 0, 100).get("status"));
        assertEquals("CLOSED", session(service.get("admin", id(secondAction), 0, 100)).get("state"));
        verify(backend, never()).npcCenterOpen(any());
        verify(backend, never()).npcCancel(any(), anyLong());
        assertEquals(6, service.publicCapabilities().get("operation_history_size"));
    }

    @Test
    void lateNpcCancellationCannotUndoAnOpenedDialogueOrRestoreAClosingSession() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareNpc(service);
        var opened = open(service, "open-npc");
        executors.getFirst().runNext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.npcCenterOpen(handle)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return npcSuccess(true);
        });
        var operation = service.act("admin", "center-running", npcRequest(opened, "npc_center_open", null));
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.cancel("admin", "cancel-late-open", id(operation));
            service.close("admin", "close-npc", closeRequest(opened));
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
        var finished = service.get("admin", id(operation), 0, 100);
        assertEquals("SUCCEEDED", finished.get("status"));
        assertEquals("center_dialogue_opened", result(finished).get("observed_outcome"));
        assertEquals(true, result(finished).get("active"));
        assertEquals(true, result(finished).get("cancel_too_late"));
        assertEquals("CLOSING", session(finished).get("state"));
        executors.getFirst().runNext();
        assertEquals("CLOSED", session(service.get("admin", id(operation), 0, 100)).get("state"));
        verify(backend).close(handle);
    }

    @Test
    void npcRepliesDispatchTheirExactKindsAndKeepOnlyBoundedMetadataFromTheNativeOutcome() {
        ActorControlService service = service(4, 20, 64);
        prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        var selected = service.act("admin", "select-welfare", replyRequest(opened, 37L, "select", 0));
        executors.getFirst().runNext();
        var selectedResult = service.get("admin", id(selected), 0, 100);
        assertEquals("npc_reply", selectedResult.get("action"));
        assertEquals("SUCCEEDED", selectedResult.get("status"));
        assertEquals("dialogue_replied", result(selectedResult).get("observed_outcome"));
        assertEquals("select", result(selectedResult).get("kind"));
        assertEquals(0, result(selectedResult).get("selection"));
        assertEquals(38L, result(selectedResult).get("dialogue_version"));
        assertEquals(true, result(selectedResult).get("reply_version_consumed"));
        assertEquals("welfare", result(selectedResult).get("dialogue_path"));
        assertEquals(true, result(selectedResult).get("active"));
        assertEquals(false, result(selectedResult).get("cancel_too_late"));
        assertFalse(result(selectedResult).containsKey("text"));
        assertFalse(result(selectedResult).containsKey("scriptPath"));
        assertFalse(result(selectedResult).containsKey("choices"));

        var declined = service.act("admin", "decline-welfare", replyRequest(opened, 38L, "decline", null));
        executors.getFirst().runNext();
        var declinedResult = service.get("admin", id(declined), 0, 100);
        assertEquals("SUCCEEDED", declinedResult.get("status"));
        assertEquals("dialogue_declined", result(declinedResult).get("observed_outcome"));
        assertEquals("decline", result(declinedResult).get("kind"));
        assertNull(result(declinedResult).get("selection"));
        assertEquals("closed", result(declinedResult).get("dialogue_path"));
        assertEquals(false, result(declinedResult).get("active"));
        assertEquals(true, result(declinedResult).get("native_dialogue_cleanup_verified"));
        verify(backend).npcReply(handle, 37L, "select", 0);
        verify(backend).npcReply(handle, 38L, "decline", null);
        verify(backend, never()).npcCancel(any(), anyLong());
        verify(backend, never()).close(any());
    }

    @Test
    void knownNpcReplyIdsRecoverTheOriginalFactAfterVersionConsumptionAndAfterClose() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        ActorActRequest request = replyRequest(opened, 37L, "select", 71);
        var action = service.act("admin", "reply-one", request);
        executors.getFirst().runNext();
        doThrow(new ObservationException(HttpStatus.GONE, "stale_dialogue"))
                .when(backend).validateNpcReply(handle, 37L, "select", 71);
        assertEquals(id(action), id(service.act("admin", "reply-one", request)));
        assertEquals("stale_dialogue", assertThrows(ObservationException.class,
                () -> service.act("admin", "new-reply-old-version", request)).reason());
        for (ActorActRequest changed : List.of(replyRequest(opened, 37L, "select", 0),
                replyRequest(opened, 37L, "decline", null), replyRequest(opened, 38L, "select", 71))) {
            assertEquals("request_id_conflict", assertThrows(ObservationException.class,
                    () -> service.act("admin", "reply-one", changed)).reason());
        }
        service.close("admin", "close-reply", closeRequest(opened));
        executors.getFirst().runNext();
        var recovered = service.act("admin", "reply-one", request);
        assertEquals("SUCCEEDED", recovered.get("status"));
        assertEquals("CLOSED", session(recovered).get("state"));
        verify(backend).npcReply(handle, 37L, "select", 71);
        var field = ActorControlService.class.getDeclaredField("requests");
        field.setAccessible(true);
        String history = field.get(service).toString();
        assertTrue(history.contains("replyKind=select"));
        assertTrue(history.contains("selection=71"));
        assertFalse(history.contains("不能进入历史的菜单正文"));
    }

    @Test
    void aConcurrentDuplicateRecoversTheAcceptedReplyWhenItsSlowerValidationSeesTheConsumedVersion() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        ActorActRequest request = replyRequest(opened, 37L, "select", 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean firstValidation = new AtomicBoolean(true);
        AtomicBoolean consumed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (firstValidation.compareAndSet(true, false)) {
                entered.countDown();
                assertTrue(complete.await(5, TimeUnit.SECONDS));
            }
            if (consumed.get()) throw new ObservationException(HttpStatus.GONE, "stale_dialogue");
            return null;
        }).when(backend).validateNpcReply(handle, 37L, "select", 0);
        when(backend.npcReply(handle, 37L, "select", 0)).thenAnswer(invocation -> {
            consumed.set(true);
            return replySuccess(37L, "select", 0);
        });
        AtomicReference<Object> duplicate = new AtomicReference<>();
        Thread slower = Thread.ofVirtual().start(() -> {
            try { duplicate.set(service.act("admin", "same-reply", request)); }
            catch (Throwable failure) { duplicate.set(failure); }
        });
        Map<String, Object> accepted;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            accepted = service.act("admin", "same-reply", request);
            executors.getFirst().runNext();
            assertTrue(consumed.get());
        } finally {
            complete.countDown();
            slower.join(5_000);
        }
        assertFalse(slower.isAlive());
        Map<?, ?> recovered = assertInstanceOf(Map.class, duplicate.get());
        assertEquals(id(accepted), recovered.get("operation_id"));
        assertEquals("SUCCEEDED", recovered.get("status"));
        assertEquals(2, service.publicCapabilities().get("operation_history_size"));
        verify(backend).npcReply(handle, 37L, "select", 0);
    }

    @Test
    void npcReplyParametersRejectUnsupportedKindsAndInvalidSelectionsBeforeNativeValidation() {
        ActorControlService service = service(4, 20, 64);
        prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        for (String kind : java.util.Arrays.asList(null, "accept", "next", "script")) {
            assertEquals("unsupported_reply_kind", assertThrows(ObservationException.class,
                    () -> service.act("admin", "invalid-kind", replyRequest(opened, 37L, kind, null))).reason());
        }
        for (Integer selection : java.util.Arrays.asList(null, -1)) {
            assertEquals("invalid_npc_selection", assertThrows(ObservationException.class,
                    () -> service.act("admin", "invalid-selection", replyRequest(opened, 37L, "select", selection))).reason());
        }
        assertEquals("invalid_action_parameters", assertThrows(ObservationException.class,
                () -> service.act("admin", "decline-with-selection", replyRequest(opened, 37L, "decline", 0))).reason());
        assertEquals("invalid_dialogue_version", assertThrows(ObservationException.class,
                () -> service.act("admin", "zero-version", replyRequest(opened, 0L, "select", 0))).reason());
        ActorActRequest original = chatRequest(opened, "聊天正文");
        assertEquals("invalid_action_parameters", assertThrows(ObservationException.class,
                () -> service.act("admin", "mixed-chat", new ActorActRequest(original.sessionId(), original.expectedGeneration(),
                        "chat", new ActorActRequest.Parameters("聊天正文", null, "select", 0), original.expectedContext()))).reason());
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).validateNpcReply(any(), anyLong(), any(), any());
        verify(backend, never()).npcReply(any(), anyLong(), any(), any());
    }

    @Test
    void incompleteOrUnofferedNpcRepliesAreRejectedAtAcceptanceAndAfterQueueingWithoutDispatch() {
        ActorControlService service = service(4, 30, 64);
        prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        int sequence = 0;
        for (String reason : List.of("npc_menu_incomplete", "npc_selection_not_offered", "npc_reply_path_not_supported",
                "dialogue_not_active", "stale_dialogue")) {
            HttpStatus status = "stale_dialogue".equals(reason) ? HttpStatus.GONE : HttpStatus.CONFLICT;
            doThrow(new ObservationException(status, reason)).when(backend).validateNpcReply(handle, 37L, "select", 0);
            int historyBefore = (int) service.publicCapabilities().get("operation_history_size");
            assertEquals(reason, assertThrows(ObservationException.class,
                    () -> service.act("admin", "rejected-" + reason, replyRequest(opened, 37L, "select", 0))).reason());
            assertEquals(historyBefore, service.publicCapabilities().get("operation_history_size"));
            doNothing().doThrow(new ObservationException(status, reason)).when(backend).validateNpcReply(handle, 37L, "select", 0);
            var accepted = service.act("admin", "queued-" + sequence++, replyRequest(opened, 37L, "select", 0));
            executors.getFirst().runNext();
            var failed = service.get("admin", id(accepted), 0, 100);
            assertEquals("FAILED", failed.get("status"));
            assertEquals(Map.of("reason", reason), failed.get("error"));
            assertEquals(false, result(failed).get("native_dispatch_started"));
            assertEquals("OPEN", session(failed).get("state"));
        }
        verify(backend, never()).npcReply(any(), anyLong(), any(), any());
        verify(backend, never()).close(any());
    }

    @Test
    void npcReplyRechecksSceneAtDequeueAndQueuedCancellationDoesNotConsumeTheDialogue() {
        ActorControlService service = service(4, 20, 64);
        AcceptanceObservationService observations = prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        var cancelled = service.act("admin", "cancelled-select", replyRequest(opened, 37L, "select", 0));
        service.cancel("admin", "cancel-operation", id(cancelled));
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(cancelled), 0, 100).get("status"));
        assertEquals("action_cancelled_before_dispatch", result(service.get("admin", id(cancelled), 0, 100)).get("observed_outcome"));
        var moved = service.act("admin", "moved-select", replyRequest(opened, 37L, "select", 0));
        when(observations.contextForActor(any())).thenReturn(Map.of("scene_ref", "scene-replaced",
                "runtime_generation", opened.get("runtime_generation")));
        executors.getFirst().runNext();
        assertEquals(Map.of("reason", "actor_context_mismatch"), service.get("admin", id(moved), 0, 100).get("error"));
        verify(backend, never()).npcReply(any(), anyLong(), any(), any());
        verify(backend, never()).close(any());
    }

    @Test
    void replyActionsPreserveTheFirstCloseHistoryAndCloseStopsEveryUndispatchedReply() {
        ActorControlService service = service(4, 6, 64);
        prepareReply(service);
        var first = open(service, "open-first");
        executors.getFirst().runNext();
        var second = service.open("admin", "open-second", new ActorOpenRequest("secondary", 0, 1));
        executors.getLast().runNext();
        var firstReply = service.act("admin", "select-first", replyRequest(first, 37L, "select", 0));
        var secondReply = service.act("admin", "decline-second", replyRequest(second, 38L, "decline", null));
        assertEquals("actor_history_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "reply-over-capacity", replyRequest(first, 37L, "select", 71))).reason());
        service.close("admin", "close-first", closeRequest(first));
        service.close("admin", "close-second", closeRequest(second));
        executors.getFirst().runNext();
        assertEquals("CLOSING", session(service.get("admin", id(firstReply), 0, 100)).get("state"));
        executors.getFirst().runNext();
        executors.getLast().drain();
        for (var reply : List.of(firstReply, secondReply)) {
            var completed = service.get("admin", id(reply), 0, 100);
            assertEquals("CANCELLED", completed.get("status"));
            assertEquals("CLOSED", session(completed).get("state"));
        }
        verify(backend, never()).npcReply(any(), anyLong(), any(), any());
        assertEquals(6, service.publicCapabilities().get("operation_history_size"));
    }

    @Test
    void aDispatchedNpcReplyRetainsItsNativeFactAfterLateCancelAndClose() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.npcReply(handle, 37L, "select", 0)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return replySuccess(37L, "select", 0);
        });
        var reply = service.act("admin", "select-running", replyRequest(opened, 37L, "select", 0));
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.cancel("admin", "cancel-late-reply", id(reply));
            service.close("admin", "close-reply", closeRequest(opened));
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        var result = service.get("admin", id(reply), 0, 100);
        assertFalse(worker.isAlive());
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals("dialogue_replied", result(result).get("observed_outcome"));
        assertEquals(true, result(result).get("cancel_too_late"));
        assertEquals(true, result(result).get("reply_version_consumed"));
        assertEquals("CLOSING", session(result).get("state"));
        executors.getFirst().runNext();
        assertEquals("CLOSED", session(service.get("admin", id(reply), 0, 100)).get("state"));
        verify(backend).npcReply(handle, 37L, "select", 0);
    }

    @Test
    void replySuccessNeedsMatchingKindSelectionConsumedVersionAndNativePostState() {
        ActorControlService service = service(4, 40, 64);
        prepareReply(service);
        var opened = open(service, "open-reply");
        executors.getFirst().runNext();
        List<Map<String, Object>> invalidMetadata = new ArrayList<>();
        for (Map.Entry<String, Object> mutation : List.<Map.Entry<String, Object>>of(Map.entry("kind", "decline"), Map.entry("selection", 71),
                Map.entry("reply_version_consumed", false), Map.entry("dialogue_version", 37L), Map.entry("active", false))) {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>((Map<String, Object>) replySuccess(37L, "select", 0).get("result"));
            metadata.put(mutation.getKey(), mutation.getValue());
            invalidMetadata.add(metadata);
        }
        int request = 0;
        for (Map<String, Object> invalid : invalidMetadata) {
            when(backend.npcReply(handle, 37L, "select", 0)).thenReturn(Map.of("status", "SUCCEEDED", "result", invalid));
            var action = service.act("admin", "invalid-evidence-" + request++, replyRequest(opened, 37L, "select", 0));
            executors.getFirst().runNext();
            var completed = service.get("admin", id(action), 0, 100);
            assertEquals("INDETERMINATE", completed.get("status"));
            assertEquals("native_dialogue_reply_outcome_unknown", result(completed).get("observed_outcome"));
        }
        Map<String, Object> unknown = new java.util.LinkedHashMap<>();
        unknown.put("native_dispatch_started", false);
        unknown.put("reply_version_consumed", true);
        unknown.put("kind", "select");
        unknown.put("selection", 0);
        unknown.put("dialogue_path", null);
        unknown.put("text", "不能进入历史的未知回复正文");
        when(backend.npcReply(handle, 37L, "select", 0)).thenReturn(Map.of("status", "INDETERMINATE", "result", unknown));
        var action = service.act("admin", "unknown-evidence", replyRequest(opened, 37L, "select", 0));
        executors.getFirst().runNext();
        var completed = service.get("admin", id(action), 0, 100);
        assertEquals("INDETERMINATE", completed.get("status"));
        assertEquals(true, result(completed).get("reply_version_consumed"));
        assertEquals(false, result(completed).get("native_dispatch_started"));
        assertEquals(false, result(completed).get("cancel_too_late"));
        assertTrue(result(completed).containsKey("dialogue_path"));
        assertNull(result(completed).get("dialogue_path"));
        assertFalse(completed.toString().contains("不能进入历史的未知回复正文"));
        assertEquals("OPEN", session(completed).get("state"));
    }

    private AcceptanceObservationService prepareReply(ActorControlService service) {
        AcceptanceObservationService observations = prepareChat(service);
        when(backend.npcReply(eq(handle), anyLong(), any(), any())).thenAnswer(invocation -> replySuccess(
                invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));
        return observations;
    }

    private static Map<String, Object> replySuccess(long version, String kind, Integer selection) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        boolean selecting = "select".equals(kind);
        result.put("observed_outcome", selecting ? "dialogue_replied" : "dialogue_declined");
        result.put("native_dispatch_started", true);
        result.put("reply_version_consumed", true);
        result.put("dialogue_version", version + 1);
        result.put("packet_sequence", version + 1);
        result.put("npc_template_id", 9900001);
        result.put("message_type", selecting ? 12 : 0);
        result.put("active", selecting);
        result.put("native_dialogue_cleanup_verified", !selecting);
        result.put("kind", kind);
        result.put("selection", selection);
        result.put("dialogue_path", selecting ? "welfare" : "closed");
        result.put("packet_cursor_before", version);
        result.put("packet_cursor_after", version + 1);
        result.put("text", "不能进入历史的菜单正文");
        result.put("scriptPath", "not-public");
        result.put("choices", List.of("not-public"));
        return Map.of("status", "SUCCEEDED", "result", result);
    }

    private static ActorActRequest replyRequest(Map<String, Object> opened, Long version, String kind, Integer selection) {
        return new ActorActRequest(sessionId(opened), generation(opened), "npc_reply",
                new ActorActRequest.Parameters(null, version, kind, selection),
                new ActorActRequest.ExpectedContext("scene-current", (String) opened.get("runtime_generation")));
    }

    private void prepareNpc(ActorControlService service) {
        prepareChat(service);
        when(backend.npcCenterOpen(handle)).thenReturn(npcSuccess(true));
        when(backend.npcCancel(eq(handle), anyLong())).thenReturn(npcSuccess(false));
    }

    private static Map<String, Object> npcSuccess(boolean opening) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("observed_outcome", opening ? "center_dialogue_opened" : "dialogue_cancelled");
        result.put("native_dispatch_started", true);
        result.put("dialogue_version", 37L);
        result.put("packet_sequence", 37L);
        result.put("npc_template_id", 9900001);
        result.put("message_type", 4);
        result.put("active", opening);
        result.put("native_dialogue_cleanup_verified", !opening);
        result.put("packet_cursor_before", opening ? 36L : 37L);
        result.put("packet_cursor_after", opening ? 37L : 38L);
        result.put("text", "不能进入历史的中心正文");
        result.put("parameters", Map.of("selection", "not-public"));
        return Map.of("status", "SUCCEEDED", "result", result);
    }

    private static ActorActRequest npcRequest(Map<String, Object> opened, String action, Long version) {
        return new ActorActRequest(sessionId(opened), generation(opened), action, new ActorActRequest.Parameters(null, version),
                new ActorActRequest.ExpectedContext("scene-current", (String) opened.get("runtime_generation")));
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
