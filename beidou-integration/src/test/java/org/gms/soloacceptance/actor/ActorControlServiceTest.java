package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.AcceptanceObservationRuntime;
import org.gms.soloacceptance.AcceptanceObservationService;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.action.ChatEvidence;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.action.NativeInventoryActions;
import soloMapling.game.action.NativeInventoryMoveAction;
import soloMapling.game.action.InventoryMoveResult;
import soloMapling.game.action.NativeBasicActions;
import soloMapling.game.action.NativeBasicResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;
import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActorControlServiceTest {
    private NativeActorBackend backend;
    private ChatDispatch chatDispatch;
    private final Map<NativeActionService.ActionHandle, PreparedChat> preparedChats =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private NativeActorHandle handle;
    private NativeActorHandle secondaryHandle;
    private AcceptanceClient client;
    private final List<ManualExecutor> executors = new ArrayList<>();
    private final List<ActorControlService> services = new ArrayList<>();

    @BeforeEach
    void prepare() {
        CharacterTestContext.install();
        AcceptanceObservationRuntime.onWorldStarted();
        backend = mock(NativeActorBackend.class);
        chatDispatch = mock(ChatDispatch.class);
        when(backend.chatTimingCapabilities()).thenReturn(Map.of("chat_minimum_interval_ms", 200L,
                "native_clock_update_interval_ms", 777L));
        client = mock(AcceptanceClient.class);
        handle = handleFixture(client);
        secondaryHandle = handleFixture(mock(AcceptanceClient.class), 74);
        doAnswer(invocation -> adoptOpen(invocation.getArgument(4), "secondary".equals(invocation.getArgument(0))
                ? secondaryHandle : handle)).when(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        when(backend.isCurrent(handle)).thenReturn(true);
        when(backend.snapshot(handle)).thenReturn(Map.of("character_id", 73, "logged_in_world", true,
                "persisted", true, "packet_events", List.of()));
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenReturn(Map.of("native_cleanup_verified", true, "persisted_state_verified", true));
        when(backend.isCurrent(secondaryHandle)).thenReturn(true);
        when(backend.snapshot(secondaryHandle)).thenReturn(Map.of("character_id", 74, "logged_in_world", true,
                "persisted", true, "packet_events", List.of()));
        when(backend.close(eq(secondaryHandle), any(ScopeResources.DrainPermit.class))).thenReturn(
                Map.of("native_cleanup_verified", true, "persisted_state_verified", true));
    }

    @AfterEach
    void stopFixtures() {
        var stop = ExecutionScope.tryBeginWorldStop();
        services.forEach(service -> service.onWorldStopping(stop));
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
        verify(backend, never()).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));

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
        verify(backend, times(1)).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        verify(backend, times(1)).close(eq(handle), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void openActionObservationAndCloseProjectOneHostWhileRootAndMcpWorkHaveDifferentScopes() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opening = open(service, "open-one-host");
        String runtimeId = (String) runtimeView(opening).get("id");
        AcceptanceActorExecution execution = execution(service, opening);
        assertNotEquals(opening.get("runtime_generation"), runtimeId);
        assertSame(execution.scope(), operationWork(service, opening).scope());
        executors.getFirst().runNext();
        var opened = service.get("admin", id(opening), 0, 100);
        assertEquals("SUCCEEDED", opened.get("status"));
        assertEquals(runtimeId, runtimeView(opened).get("id"));
        assertNotSame(execution.scope(), execution.moduleScope());
        assertEquals(1, execution.scope().snapshot().outstandingResources());
        assertEquals(0, execution.moduleScope().snapshot().outstandingResources());
        Map<?, ?> module = (Map<?, ?>) runtimeView(opened).get("module");
        assertEquals("mcp-control", module.get("type_id"));
        assertEquals(sessionId(opened), module.get("instance_id"));
        assertEquals("ACTIVE", module.get("state"));
        assertEquals(List.of("controller"), module.get("slots"));

        ActorActRequest request = chatRequest(opened, "同一个runtime的聊天证据");
        var action = service.act("admin", "chat-one-host", request);
        assertSame(execution.moduleScope(), operationWork(service, action).scope());
        assertEquals(runtimeId, runtimeView(action).get("id"));
        executors.getFirst().runNext();
        assertEquals("SUCCEEDED", service.get("admin", id(action), 0, 100).get("status"));
        when(client.packetEvents(0, 100)).thenReturn(Map.of("items", List.of(), "next_cursor", 0L,
                "latest_cursor", 0L, "oldest_cursor", 0L, "truncated", false, "cursor_expired", false));
        var observed = service.observe("admin", observeRequest(opened, 0, 100));
        Map<?, ?> runtime = (Map<?, ?>) ((Map<?, ?>) observed.get("actor")).get("runtime");
        assertEquals(runtimeId, runtime.get("id"));
        assertEquals(opened.get("runtime_generation"), runtime.get("epoch"));
        assertEquals(73, runtime.get("character_id"));
        assertTrue(((List<?>) runtime.get("slots")).contains(Map.of(
                "id", "controller", "capacity", 1, "occupants", List.of(sessionId(opened)))));
        assertFalse(runtime.toString().contains(request.parameters().text()));
        Map<?, ?> events = (Map<?, ?>) runtime.get("events");
        assertEquals("runtime_event_sequence", events.get("cursor_scope"));
        List<?> items = (List<?>) events.get("items");
        assertFalse(items.isEmpty());
        for (Object item : items) assertEquals(runtimeId, ((Map<?, ?>) item).get("runtime_id"));

        var closing = service.close("admin", "close-one-host", closeRequest(opened));
        assertEquals(runtimeId, runtimeView(closing).get("id"));
        executors.getFirst().runNext();
        var closed = service.get("admin", id(closing), 0, 100);
        assertEquals("SUCCEEDED", closed.get("status"));
        assertEquals(runtimeId, runtimeView(closed).get("id"));
        assertEquals("CLOSED", runtimeView(closed).get("state"));
    }

    @Test
    void actorDiagnosticsRetainTheLatest64SafeFactsWithoutConsumingOrMixingSessions() throws Exception {
        ActorControlService service = service(4, 128, 64);
        prepareChat(service);
        var opening = open(service, "event-window-open");
        executors.getFirst().runNext();
        var opened = service.get("admin", id(opening), 0, 100);
        String firstRuntime = (String) runtimeView(opened).get("id");
        Map<?, ?> firstWindow = (Map<?, ?>) runtimeView(opened).get("events");
        List<?> initialItems = (List<?>) firstWindow.get("items");
        List<?> savedItems = List.copyOf(initialItems);
        for (int i = 0; i < 30; i++) {
            var action = service.act("admin", "window-action-" + i, chatRequest(opened, "诊断不保留聊天正文" + i));
            executors.getFirst().runNext();
            assertEquals("SUCCEEDED", service.get("admin", id(action), 0, 100).get("status"));
        }
        when(client.packetEvents(0, 100)).thenReturn(Map.of("items", List.of(), "next_cursor", 0L,
                "latest_cursor", 0L, "oldest_cursor", 0L, "truncated", false, "cursor_expired", false));
        var observed = service.observe("admin", observeRequest(opened, 0, 100));
        Map<?, ?> observedRuntime = (Map<?, ?>) ((Map<?, ?>) observed.get("actor")).get("runtime");
        Map<?, ?> window = (Map<?, ?>) observedRuntime.get("events");
        List<?> items = (List<?>) window.get("items");
        assertEquals(64, items.size());
        assertEquals(true, window.get("cursor_expired"));
        assertEquals(false, window.get("truncated"));
        long previous = 0;
        for (Object entry : items) {
            Map<?, ?> event = (Map<?, ?>) entry;
            long sequence = ((Number) event.get("sequence")).longValue();
            assertTrue(sequence > previous);
            assertEquals(firstRuntime, event.get("runtime_id"));
            previous = sequence;
        }
        assertEquals(previous, window.get("next_cursor"));
        assertFalse(window.toString().contains("诊断不保留聊天正文"));
        assertEquals(savedItems, initialItems, "an earlier response must remain a stable snapshot");
        var repeated = service.observe("admin", observeRequest(opened, 0, 100));
        assertEquals(window, ((Map<?, ?>) ((Map<?, ?>) repeated.get("actor")).get("runtime")).get("events"));

        var closing = service.close("admin", "event-window-close", closeRequest(opened));
        executors.getFirst().drain();
        var closed = service.get("admin", id(closing), 0, 100);
        assertEquals("CLOSED", runtimeView(closed).get("state"));
        List<?> closedEvents = (List<?>) ((Map<?, ?>) runtimeView(closed).get("events")).get("items");
        assertTrue(closedEvents.stream().anyMatch(event -> "MODULE_UNLOADED".equals(((Map<?, ?>) event).get("kind"))));
        assertTrue(closedEvents.stream().anyMatch(event -> "CLOSED".equals(((Map<?, ?>) event).get("kind"))));
        var reopening = open(service, "event-window-reopen");
        executors.getLast().runNext();
        var reopened = service.get("admin", id(reopening), 0, 100);
        String nextRuntime = (String) runtimeView(reopened).get("id");
        assertNotEquals(firstRuntime, nextRuntime);
        Map<?, ?> nextWindow = (Map<?, ?>) runtimeView(reopened).get("events");
        List<?> nextEvents = (List<?>) nextWindow.get("items");
        assertEquals(false, nextWindow.get("cursor_expired"));
        assertEquals(1L, ((Map<?, ?>) nextEvents.getFirst()).get("sequence"));
        for (Object event : nextEvents) assertEquals(nextRuntime, ((Map<?, ?>) event).get("runtime_id"));
        assertEquals(savedItems, firstWindow.get("items"));
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
        verify(backend, never()).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
    }

    @Test
    void cancellationWhileNativeOpenIsRunningWaitsForVerifiedCleanupBeforeReleasingTheProfile() throws Exception {
        ActorControlService service = service(4, 20, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        doAnswer(invocation -> {
            NativeActorBackend.OpenOwnership ownership = invocation.getArgument(4);
            ownership.bindOrigin(handle.world(), handle.channel());
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            ownership.adopt(handle);
            return handle;
        }).when(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
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
        verify(backend, times(1)).close(eq(handle), any(ScopeResources.DrainPermit.class));
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
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void absentCleanupOrPersistenceEvidenceIsIndeterminateAndCannotReleaseTheProfile() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-one");
        executors.getFirst().runNext();
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenReturn(Map.of("native_cleanup_verified", true, "persisted_state_verified", false));
        var closing = service.close("admin", "close-one", closeRequest(opened));
        executors.getFirst().runNext();
        var uncertain = service.get("admin", id(closing), 0, 100);
        assertEquals("INDETERMINATE", uncertain.get("status"));
        assertEquals("QUARANTINED", session(uncertain).get("state"));
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        assertEquals("profile_in_use", assertThrows(ObservationException.class, () -> open(service, "open-two")).reason());
    }

    @Test
    void aStoppedWorldCanCleanItsLateActorBeforeTheNextWorldStarts() throws Exception {
        ActorControlService service = service(4, 20, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean sawCancellation = new AtomicBoolean();
        doAnswer(invocation -> {
            BooleanSupplier cancelled = invocation.getArgument(3);
            NativeActorBackend.OpenOwnership ownership = invocation.getArgument(4);
            ownership.bindOrigin(handle.world(), handle.channel());
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            sawCancellation.set(cancelled.getAsBoolean());
            ownership.adopt(handle);
            return handle;
        }).when(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        var opened = open(service, "open-one");
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.onWorldStopping();
            assertEquals(1, service.publicCapabilities().get("active_sessions"));
            assertEquals(false, service.publicCapabilities().get("actor_accepting_requests"));
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
        assertTrue(sawCancellation.get());
        assertEquals("CANCELLED", service.get("admin", id(opened), 0, 100).get("status"));
        verify(backend, times(1)).close(eq(handle), any(ScopeResources.DrainPermit.class));
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        AcceptanceObservationRuntime.onWorldStarted();
        service.onWorldStarted();
        var newWorld = open(service, "open-new-world");
        assertNotEquals(opened.get("runtime_generation"), newWorld.get("runtime_generation"));
        assertTrue(generation(newWorld) > generation(opened));
    }

    @Test
    void aNewWorldCannotAuthorizeCleanupOfThePreviousWorldsLateActor() throws Exception {
        ActorControlService service = service(4, 20, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        doAnswer(invocation -> {
            NativeActorBackend.OpenOwnership ownership = invocation.getArgument(4);
            ownership.bindOrigin(handle.world(), handle.channel());
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            ownership.adopt(handle);
            return handle;
        }).when(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        var opened = open(service, "open-old-world");
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.onWorldStopping();
            AcceptanceObservationRuntime.onWorldStarted();
            service.onWorldStarted();
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
        var uncertain = service.get("admin", id(opened), 0, 100);
        assertEquals("INDETERMINATE", uncertain.get("status"));
        assertEquals("QUARANTINED", session(uncertain).get("state"));
        assertEquals(false, session(uncertain).get("actor_current"));
        assertNull(session(uncertain).get("actor"));
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        assertEquals("profile_in_use", assertThrows(ObservationException.class,
                () -> open(service, "open-new-world")).reason());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void anAdoptedOpenResultThatNeverReturnsStillHasAnOwnedCleanupAndCanBeVerifiedLater() throws Exception {
        ActorControlService service = service(4, 20, 64);
        doAnswer(invocation -> {
            NativeActorBackend.OpenOwnership ownership = invocation.getArgument(4);
            adoptOpen(ownership, handle);
            ownership.creationFailed("actor_open_cleanup_unverified");
            throw new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, "actor_open_cleanup_unverified");
        }).when(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenAnswer(invocation -> {
            invocation.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(handle);
            return Map.of("native_cleanup_verified", false, "persisted_state_verified", false);
        });
        var opened = open(service, "open-adopted-failure");
        executors.getFirst().runNext();
        var failure = service.get("admin", id(opened), 0, 100);
        assertEquals("INDETERMINATE", failure.get("status"));
        assertEquals("QUARANTINED", session(failure).get("state"));
        AcceptanceActorExecution execution = execution(service, opened);
        assertSame(handle, execution.adoptedHandle());
        assertEquals(1, execution.scope().snapshot().outstandingResources());
        assertFalse(execution.scope().snapshot().unloadComplete());
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenAnswer(invocation -> {
            invocation.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(handle);
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", true);
        });
        var closed = service.close("admin", "verify-adopted-resource", closeRequest(opened));
        executors.getFirst().runNext();
        assertEquals("SUCCEEDED", service.get("admin", id(closed), 0, 100).get("status"));
        assertEquals(0, execution.scope().snapshot().outstandingResources());
        assertTrue(execution.scope().snapshot().unloadComplete());
        assertNull(execution.adoptedHandle());
        verify(backend, times(2)).close(eq(handle), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void backendInlineRollbackBeforeOpenReturnsCannotTriggerASecondQueuedCleanup() throws Exception {
        ActorControlService service = service(4, 20, 64);
        doAnswer(invocation -> {
            NativeActorBackend.OpenOwnership ownership = invocation.getArgument(4);
            adoptOpen(ownership, handle);
            ownership.rollback(handle);
            throw new ObservationException(HttpStatus.CONFLICT, "actor_native_entry_unverified");
        }).when(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenAnswer(invocation -> {
            invocation.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(handle);
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", true);
        });
        var opened = open(service, "open-inline-rollback");
        executors.getFirst().runNext();
        assertEquals("FAILED", service.get("admin", id(opened), 0, 100).get("status"));
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        assertTrue(execution(service, opened).scope().snapshot().unloadComplete());
        service.onWorldStopping();
        executors.getFirst().drain();
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
        verify(backend, never()).close(isNull(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void aNewWorldStopsItsOwnActorWithoutRenewingAnOlderQuarantinedResourcesPermit() throws Exception {
        ActorControlService service = service(4, 20, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        doAnswer(invocation -> {
            NativeActorBackend.OpenOwnership ownership = invocation.getArgument(4);
            ownership.bindOrigin(handle.world(), handle.channel());
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            ownership.adopt(handle);
            return handle;
        }).when(backend).open(eq("primary"), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        var old = open(service, "open-old-resource");
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.onWorldStopping();
            AcceptanceObservationRuntime.onWorldStarted();
            service.onWorldStarted();
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        NativeActorHandle secondary = handleFixture(mock(AcceptanceClient.class), 74);
        doAnswer(invocation -> adoptOpen(invocation.getArgument(4), secondary)).when(backend).open(
                eq("secondary"), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        when(backend.isCurrent(secondary)).thenReturn(true);
        when(backend.snapshot(secondary)).thenReturn(Map.of("character_id", 74, "logged_in_world", true));
        when(backend.close(eq(secondary), any(ScopeResources.DrainPermit.class))).thenAnswer(invocation -> {
            invocation.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(secondary);
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", true);
        });
        var current = service.open("admin", "open-current-resource", new ActorOpenRequest("secondary", 0, 1));
        executors.getLast().runNext();
        assertDoesNotThrow(() -> { service.onWorldStopping(); });
        executors.getLast().drain();
        assertEquals("QUARANTINED", session(service.get("admin", id(old), 0, 100)).get("state"));
        assertEquals("CLOSED", session(service.get("admin", id(current), 0, 100)).get("state"));
        assertEquals(1, execution(service, old).scope().snapshot().outstandingResources());
        assertFalse(execution(service, old).scope().snapshot().unloadComplete());
        assertTrue(execution(service, current).scope().snapshot().unloadComplete());
        verify(backend, never()).close(eq(handle), any(ScopeResources.DrainPermit.class));
        verify(backend).close(eq(secondary), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void repeatedWorldStopAndExplicitCloseShareTheSameInFlightNativeCleanup() throws Exception {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-before-close");
        executors.getFirst().runNext();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenAnswer(invocation -> {
            invocation.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(handle);
            entered.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", true);
        });
        var closing = service.close("admin", "close-before-stop", closeRequest(opened));
        Thread worker = Thread.ofVirtual().start(executors.getFirst()::runNext);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.onWorldStopping();
            service.onWorldStopping();
            assertFalse(execution(service, opened).scope().snapshot().unloadComplete());
        } finally {
            complete.countDown();
            worker.join(5_000);
        }
        assertEquals("SUCCEEDED", service.get("admin", id(closing), 0, 100).get("status"));
        assertTrue(execution(service, opened).scope().snapshot().unloadComplete());
        assertTrue(executors.getFirst().queue.isEmpty());
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
        verify(backend, never()).close(isNull(), any(ScopeResources.DrainPermit.class));
    }

    private static Map<?, ?> runtimeView(Map<String, Object> operation) {
        return (Map<?, ?>) session(operation).get("runtime");
    }

    @SuppressWarnings("unchecked")
    private static ExecutionScope.WorkHandle operationWork(ActorControlService service, Map<String, Object> operation) throws Exception {
        var operations = ActorControlService.class.getDeclaredField("operations");
        operations.setAccessible(true);
        Object item = ((Map<String, ?>) operations.get(service)).get(id(operation));
        var work = item.getClass().getDeclaredField("work");
        work.setAccessible(true);
        return (ExecutionScope.WorkHandle) work.get(item);
    }

    @SuppressWarnings("unchecked")
    private static AcceptanceActorExecution execution(ActorControlService service, Map<String, Object> operation) throws Exception {
        var sessions = ActorControlService.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        Object session = ((Map<String, ?>) sessions.get(service)).get(sessionId(operation));
        var execution = session.getClass().getDeclaredField("execution");
        execution.setAccessible(true);
        return (AcceptanceActorExecution) execution.get(session);
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
        verify(backend, times(2)).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
    }

    @Test
    void aCloseRetryCannotSpendAnotherSessionsFirstCloseAndStoppingStillCleansAtCapacity() {
        ActorControlService service = service(4, 4, 64);
        NativeActorHandle secondary = handleFixture(mock(AcceptanceClient.class), 74);
        doAnswer(invocation -> adoptOpen(invocation.getArgument(4), secondary)).when(backend).open(eq("secondary"), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
        when(backend.isCurrent(secondary)).thenReturn(true);
        when(backend.snapshot(secondary)).thenReturn(Map.of("character_id", 74, "logged_in_world", true));
        when(backend.close(eq(secondary), any(ScopeResources.DrainPermit.class))).thenReturn(Map.of("native_cleanup_verified", true, "persisted_state_verified", true));
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenReturn(
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

        service.onWorldStopping();
        executors.getFirst().runNext();
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        assertEquals(4, service.publicCapabilities().get("operation_history_size"));
        assertEquals("CLOSED", session(service.get("admin", id(failedClose), 0, 100)).get("state"));
        verify(backend, times(2)).close(eq(handle), any(ScopeResources.DrainPermit.class));
        verify(backend).close(eq(secondary), any(ScopeResources.DrainPermit.class));
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
        verify(backend, never()).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
    }

    @Test
    void aStopDuringProfileValidationRejectsOpenAsOfflineBeforeAllocatingAnExecutor() {
        AtomicInteger factories = new AtomicInteger();
        ActorControlService service = new ActorControlService(backend, 4, 20, 64, profile -> {
            factories.incrementAndGet();
            return new ManualExecutor();
        }, Duration.ZERO);
        services.add(service);
        service.onWorldStarted();
        doAnswer(invocation -> {
            ExecutionScope.tryBeginWorldStop();
            return null;
        }).when(backend).validateProfile("primary", 0, 1);
        ObservationException failure = assertThrows(ObservationException.class, () -> open(service, "open-validation-stop"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        assertEquals("actor_world_offline", failure.reason());
        assertEquals(0, factories.get());
        assertEquals(0, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
    }

    @Test
    void aStopInTheExecutorFactoryRejectsOpenAndShutsDownItsPrestartedPrivateWorker() throws Exception {
        AtomicReference<ThreadPoolExecutor> created = new AtomicReference<>();
        ActorControlService service = new ActorControlService(backend, 4, 20, 64, profile -> {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8), Thread.ofVirtual().factory());
            executor.prestartAllCoreThreads();
            created.set(executor);
            ExecutionScope.tryBeginWorldStop();
            return executor;
        }, Duration.ZERO);
        services.add(service);
        service.onWorldStarted();
        try {
            ObservationException failure = assertThrows(ObservationException.class, () -> open(service, "open-factory-stop"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
            assertEquals("actor_world_offline", failure.reason());
            assertTrue(created.get().isShutdown());
            assertTrue(created.get().awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, service.publicCapabilities().get("active_sessions"));
            assertEquals(0, service.publicCapabilities().get("idempotency_records"));
        } finally {
            if (created.get() != null) created.get().shutdownNow();
        }
    }

    @Test
    void aStopDuringLaneConstructionPreservesTheOfflineStatusAndClosesItsPrivateExecutor() {
        AtomicBoolean stopOnce = new AtomicBoolean(true);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), Thread.ofVirtual().factory()) {
            @Override public int getCorePoolSize() {
                if (stopOnce.compareAndSet(true, false)) ExecutionScope.tryBeginWorldStop();
                return super.getCorePoolSize();
            }
        };
        ActorControlService service = new ActorControlService(backend, 4, 20, 64, profile -> executor, Duration.ZERO);
        services.add(service);
        service.onWorldStarted();
        try {
            ObservationException failure = assertThrows(ObservationException.class, () -> open(service, "open-constructor-stop"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
            assertEquals("actor_world_offline", failure.reason());
            assertTrue(executor.isShutdown());
            assertEquals(0, service.publicCapabilities().get("operation_history_size"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anOpenScopeAdmissionStopAfterConstructionClosesTheUnregisteredSessionAndExecutor() {
        ManualExecutor executor = new ManualExecutor();
        ActorControlService service = new ActorControlService(backend, 4, 20, 64, profile -> executor, Duration.ZERO);
        services.add(service);
        service.onWorldStarted();
        AtomicBoolean stopped = new AtomicBoolean();
        try (org.mockito.MockedStatic<WorldEpoch> epoch = mockStatic(WorldEpoch.class, CALLS_REAL_METHODS)) {
            epoch.when(WorldEpoch::snapshot).thenAnswer(invocation -> {
                boolean acceptingWork = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(ExecutionScope.class.getName())
                                && frame.getMethodName().equals("acceptWork"));
                if (acceptingWork && stopped.compareAndSet(false, true)) ExecutionScope.tryBeginWorldStop();
                return invocation.callRealMethod();
            });
            ObservationException failure = assertThrows(ObservationException.class, () -> open(service, "open-accept-stop"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
            assertEquals("actor_world_offline", failure.reason());
            assertTrue(stopped.get());
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.queue.isEmpty());
        assertEquals(0, service.publicCapabilities().get("active_sessions"));
        assertEquals(0, service.publicCapabilities().get("operation_history_size"));
    }

    @Test
    void anActionStopAtScopeAdmissionKeepsTheExistingHistoryAndReportsOffline() throws Exception {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-action-admission");
        executors.getFirst().runNext();
        ExecutionScope scope = execution(service, opened).moduleScope();
        var monitorField = ExecutionScope.class.getDeclaredField("monitor");
        monitorField.setAccessible(true);
        Object scopeMonitor = monitorField.get(scope);
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread caller;
        synchronized (scopeMonitor) {
            caller = Thread.ofVirtual().start(() -> {
                try { outcome.set(service.act("admin", "action-admission-stop", chatRequest(opened, "受理停服竞态"))); }
                catch (Throwable failure) { outcome.set(failure); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = java.util.Arrays.stream(caller.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(ExecutionScope.class.getName()) && frame.getMethodName().equals("acceptWork"));
                if (!waiting) Thread.sleep(1);
            }
            assertTrue(waiting, "action reached the real scope admission while its epoch was still active");
            ExecutionScope.tryBeginWorldStop();
        }
        caller.join(5_000);
        assertFalse(caller.isAlive());
        ObservationException failure = assertInstanceOf(ObservationException.class, outcome.get());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        assertEquals("actor_world_offline", failure.reason());
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        assertEquals(1, service.publicCapabilities().get("idempotency_records"));
        assertTrue(executors.getFirst().queue.isEmpty());
        verify(chatDispatch, never()).execute(any(), any());
    }

    @Test
    void unexpectedBackendFailureIsPrivateAndRetainsAQuarantineInsteadOfAssumingRollback() {
        ActorControlService service = service(4, 20, 64);
        doAnswer(invocation -> {
            NativeActorBackend.OpenOwnership ownership = invocation.getArgument(4);
            ownership.creationFailed("actor_open_cleanup_unverified");
            throw new IllegalStateException("password=must-not-escape");
        }).when(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
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
        verify(backend).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
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
        verify(backend, never()).close(eq(handle), any(ScopeResources.DrainPermit.class));
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
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void actorObservationReturnsCurrentSceneAndPacketWindowWithoutConsumingControlHistoryOrQueue() {
        ActorControlService service = service(4, 2, 64);
        AcceptanceObservationService observations = mock(AcceptanceObservationService.class);
        Map<String, Object> scene = Map.of("scene_ref", "scene-current", "map_id", 10000,
                "runtime_generation", AcceptanceObservationRuntime.snapshot().generation());
        when(observations.contextForActor(handle.characterRef())).thenReturn(scene);
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
    void anUnavailableNativeContextReadDoesNotTurnTheOpenActorIntoALostSession() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-read-failure");
        executors.getFirst().runNext();
        service.get("admin", id(opened), 0, 100);
        ObservationException expected = new ObservationException(HttpStatus.SERVICE_UNAVAILABLE,
                "actor_observation_unavailable");
        when(backend.isCurrent(handle)).thenThrow(expected);

        assertSame(expected, assertThrows(ObservationException.class,
                () -> service.get("admin", id(opened), 0, 100)));
        doReturn(true).when(backend).isCurrent(handle);
        var recovered = service.get("admin", id(opened), 0, 100);
        assertEquals("OPEN", session(recovered).get("state"));
        assertEquals(true, session(recovered).get("actor_current"));
        assertSame(handle, service.currentActor("admin", sessionId(opened), generation(opened)).orElseThrow());
        var close = service.close("admin", "close-after-read-failure", closeRequest(opened));
        executors.getFirst().runNext();
        assertEquals("CLOSED", session(service.get("admin", id(close), 0, 100)).get("state"));
    }

    @Test
    void anUnexpectedProjectionReadFailureRemainsUnavailableAndDoesNotPreventNativeClose() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "open-projection-failure");
        executors.getFirst().runNext();
        service.get("admin", id(opened), 0, 100);
        when(backend.snapshot(handle)).thenThrow(new IllegalStateException("private-projection-detail"));

        ObservationException failure = assertThrows(ObservationException.class,
                () -> service.get("admin", id(opened), 0, 100));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        assertEquals("actor_observation_unavailable", failure.reason());
        assertFalse(failure.toString().contains("private-projection-detail"));
        var close = service.close("admin", "close-during-projection-failure", closeRequest(opened));
        executors.getFirst().runNext();
        assertEquals("CLOSED", session(service.get("admin", id(close), 0, 100)).get("state"));
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void aSceneReadConflictCanRecoverWithoutReplacingOrClosingTheNativeActor() {
        ActorControlService service = service(4, 20, 64);
        AcceptanceObservationService observations = prepareChat(service);
        var opened = open(service, "open-scene-conflict");
        executors.getFirst().runNext();
        service.get("admin", id(opened), 0, 100);
        ObservationException conflict = new ObservationException(HttpStatus.CONFLICT, "actor_context_mismatch");
        when(observations.contextForActor(handle.characterRef())).thenThrow(conflict);

        assertSame(conflict, assertThrows(ObservationException.class,
                () -> service.get("admin", id(opened), 0, 100)));
        doReturn(Map.of("scene_ref", "scene-restored",
                "runtime_generation", AcceptanceObservationRuntime.snapshot().generation()))
                .when(observations).contextForActor(any(GameContext.CharacterRef.class));
        var recovered = service.get("admin", id(opened), 0, 100);
        assertEquals("OPEN", session(recovered).get("state"));
        assertEquals("scene-restored", ((Map<?, ?>) actor(recovered).get("scene")).get("scene_ref"));
        verify(backend, never()).close(eq(handle), any(ScopeResources.DrainPermit.class));
        verify(backend, times(1)).open(anyString(), any(), any(), any(), any(NativeActorBackend.OpenOwnership.class));
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
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenReturn(Map.of("native_cleanup_verified", false, "persisted_state_verified", false));
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
        verify(chatDispatch, never()).execute(any(), any());
        executors.getFirst().runNext();
        executors.getFirst().runNext();
        var ordering = inOrder(chatDispatch);
        ordering.verify(chatDispatch).execute(handle, secretText);
        ordering.verify(chatDispatch).execute(handle, "第二条");
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
        verify(chatDispatch, times(2)).execute(any(), any());
        assertEquals(7, service.publicCapabilities().get("maximum_pending_actions_per_session"));
        assertEquals(8, service.publicCapabilities().get("actor_executor_queue_capacity"));
        assertEquals(List.of("chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move",
                "move", "stop", "attack", "pickup", "drop", "use_item", "equip", "unequip"), service.publicCapabilities().get("actions"));
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
        verify(chatDispatch, never()).execute(any(), any());
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
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        when(observations.contextForActor(any(GameContext.CharacterRef.class))).thenReturn(Map.of("scene_ref", "scene-next",
                "runtime_generation", opened.get("runtime_generation")));
        ObservationException asynchronousFailure = assertThrows(ObservationException.class, executors.getFirst()::runNext);
        assertEquals("actor_context_mismatch", asynchronousFailure.reason());
        var failed = service.get("admin", id(action), 0, 100);
        assertEquals("FAILED", failed.get("status"));
        assertEquals(Map.of("reason", "actor_context_mismatch"), failed.get("error"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertEquals("OPEN", session(failed).get("state"));
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void contextChangeAfterChatPreparationRejectsSubmissionAndClearsItsUnclaimedOperation() throws Exception {
        ActorControlService service = service(4, 20, 64);
        AcceptanceObservationService observations = prepareChat(service);
        var opened = open(service, "open-post-prepare-context");
        executors.getFirst().runNext();
        Map<String, Object> original = Map.of("scene_ref", "scene-current",
                "runtime_generation", opened.get("runtime_generation"));
        Map<String, Object> changed = Map.of("scene_ref", "scene-changed-after-prepare",
                "runtime_generation", opened.get("runtime_generation"));
        when(observations.contextForActor(any(GameContext.CharacterRef.class))).thenReturn(original, original, changed);
        ActorActRequest request = chatRequest(opened, "准备完成后场景失效");

        var failed = service.act("admin", "post-prepare-context", request);

        assertEquals("FAILED", failed.get("status"));
        assertEquals(Map.of("reason", "actor_context_mismatch"), failed.get("error"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertEquals("OPEN", session(failed).get("state"));
        assertNull(operationWork(service, failed));
        var operations = ActorControlService.class.getDeclaredField("operations");
        operations.setAccessible(true);
        Object operation = ((Map<?, ?>) operations.get(service)).get(id(failed));
        var chatAction = operation.getClass().getDeclaredField("chatAction");
        chatAction.setAccessible(true);
        assertNull(chatAction.get(operation));
        assertTrue(executors.getFirst().queue.isEmpty());
        assertEquals(0, execution(service, opened).moduleScope().snapshot().acceptedWork());
        assertEquals(id(failed), id(service.act("admin", "post-prepare-context", request)));
        verify(backend, times(1)).prepareChat(eq(handle), any(), any(), any(), any());
        verify(backend, never()).submitChat(any());
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void anErrorAfterChatPreparationFailsPrivatelyAndReleasesItsUnclaimedWorkWithoutClosingTheActor() throws Exception {
        ActorControlService service = service(4, 20, 64);
        AcceptanceObservationService observations = prepareChat(service);
        var opened = open(service, "open-post-prepare-error");
        executors.getFirst().runNext();
        Map<String, Object> original = Map.of("scene_ref", "scene-current",
                "runtime_generation", opened.get("runtime_generation"));
        AtomicInteger contextReads = new AtomicInteger();
        String privateFailure = "private AssertionError after preparation must not escape";
        doAnswer(invocation -> {
            if (contextReads.incrementAndGet() == 3) throw new AssertionError(privateFailure);
            return original;
        }).when(observations).contextForActor(any(GameContext.CharacterRef.class));
        ActorActRequest request = chatRequest(opened, "准备后读取失败的聊天原文");

        var failed = service.act("admin", "post-prepare-error", request);

        assertEquals(3, contextReads.get());
        assertEquals("FAILED", failed.get("status"));
        assertEquals(Map.of("reason", "native_chat_validation_failed"), failed.get("error"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertFalse(failed.toString().contains(privateFailure));
        assertFalse(failed.toString().contains(request.parameters().text()));
        assertEquals("OPEN", session(failed).get("state"));
        assertNull(operationWork(service, failed));
        var operations = ActorControlService.class.getDeclaredField("operations");
        operations.setAccessible(true);
        Object operation = ((Map<?, ?>) operations.get(service)).get(id(failed));
        var chatAction = operation.getClass().getDeclaredField("chatAction");
        chatAction.setAccessible(true);
        assertNull(chatAction.get(operation));
        assertTrue(executors.getFirst().queue.isEmpty());
        assertEquals(0, execution(service, opened).moduleScope().snapshot().acceptedWork());
        assertEquals(id(failed), id(service.act("admin", "post-prepare-error", request)));
        verify(backend, times(1)).prepareChat(eq(handle), any(), any(), any(), any());
        verify(backend, never()).submitChat(any());
        verify(chatDispatch, never()).execute(any(), any());
        var closing = service.close("admin", "close-post-prepare-error", closeRequest(opened));
        executors.getFirst().runNext();
        assertEquals("SUCCEEDED", service.get("admin", id(closing), 0, 100).get("status"));
        assertEquals("CLOSED", session(service.get("admin", id(closing), 0, 100)).get("state"));
        verify(backend, times(1)).close(eq(handle), any(ScopeResources.DrainPermit.class));
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
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void backendRejectionBeforeItsNativeDispatchIsNotConfusedWithTheCoordinatorBoundary() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        doNothing().doThrow(new ObservationException(HttpStatus.CONFLICT, "actor_session_stale"))
                .when(backend).validateChat(handle, "原生再验证");
        var action = service.act("admin", "chat-one", chatRequest(opened, "原生再验证"));
        executors.getFirst().runNext();
        var failed = service.get("admin", id(action), 0, 100);
        assertEquals("FAILED", failed.get("status"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertEquals(false, result(failed).get("cancel_too_late"));
        assertEquals("OPEN", session(failed).get("state"));
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void failedUnknownAndUnexpectedNativeChatOutcomesDoNotMutateTheActorLeaseOrLeakText() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        NativeActionResult rateLimited = new NativeActionResult(
                NativeActionResult.Outcome.FAILED, NativeActionResult.Dispatch.STARTED, 73, false, false,
                "native_chat_not_broadcast", chatEvidence(31L, 31L, null, ChatEvidence.Completeness.COMPLETE));
        NativeActionResult unknown = new NativeActionResult(
                NativeActionResult.Outcome.INDETERMINATE, NativeActionResult.Dispatch.STARTED, 73, false, true,
                "native_chat_outcome_unknown", chatEvidence(31L, null, null, ChatEvidence.Completeness.UNAVAILABLE));
        when(chatDispatch.execute(handle, "限频样例")).thenReturn(rateLimited);
        when(chatDispatch.execute(handle, "未知样例")).thenReturn(unknown);
        when(chatDispatch.execute(handle, "异常样例")).thenThrow(new IllegalStateException("private text and password must not escape"));
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
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        when(chatDispatch.execute(handle, "正在发言")).thenAnswer(invocation -> {
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
        verify(chatDispatch).execute(handle, "正在发言");
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
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
        when(chatDispatch.execute(handle, "占住执行线程")).thenAnswer(invocation -> {
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
            assertEquals(6, executor.getQueue().size());
            assertCleanupReservedBehindModules(service, closing);
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
            verify(chatDispatch, times(1)).execute(any(), any());
            verify(backend, never()).npcCenterOpen(any());
            verify(backend, never()).npcReply(any(), anyLong(), any(), any());
            verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
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
        verify(chatDispatch, never()).execute(any(), any());
    }

    @Test
    void worldStoppingCancelsEveryUndispatchedActionWithoutRestoringAnOpenSession() {
        ActorControlService service = service(4, 20, 64);
        prepareChat(service);
        var opened = open(service, "open-chat");
        executors.getFirst().runNext();
        var first = service.act("admin", "chat-first", chatRequest(opened, "尚未发一"));
        var second = service.act("admin", "chat-second", chatRequest(opened, "尚未发二"));
        service.onWorldStopping();
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(first), 0, 100).get("status"));
        assertEquals("QUARANTINED", session(service.get("admin", id(first), 0, 100)).get("state"));
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(second), 0, 100).get("status"));
        assertEquals("QUARANTINED", session(service.get("admin", id(second), 0, 100)).get("state"));
        executors.getFirst().runNext();
        assertEquals("CLOSED", session(service.get("admin", id(second), 0, 100)).get("state"));
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
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
        assertEquals(Map.of("reason", "actor_queue_full"), rejected.get("error"));
        assertEquals("OPEN", session(rejected).get("state"));
        assertEquals(1, service.publicCapabilities().get("active_sessions"));
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void chatPreparationFailureSettlesItsUnqueuedWorkWithoutClosingTheActorOrHoldingAnActionSlot() throws Exception {
        ActorControlService service = service(4, 30, 64);
        prepareChat(service);
        var opened = open(service, "open-prepare-failure");
        executors.getFirst().runNext();
        doThrow(new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable"))
                .when(backend).prepareChat(any(), any(), any(), any(), any());
        var failed = actOrRecover(service, "prepare-failure", chatRequest(opened, "准备失败"));
        verify(backend, never()).submitChat(any());
        assertUnqueuedChatFailureAndAllActionSlots(service, opened, failed);
    }

    @Test
    void chatSubmissionFailureBeforeEnqueueSettlesItsWorkAndLeavesEveryActionSlotAvailable() throws Exception {
        ActorControlService service = service(4, 30, 64);
        prepareChat(service);
        var opened = open(service, "open-submit-failure");
        executors.getFirst().runNext();
        doThrow(new IllegalStateException("private submission failure must not escape"))
                .when(backend).submitChat(any());
        var failed = actOrRecover(service, "submit-failure", chatRequest(opened, "提交失败"));
        assertUnqueuedChatFailureAndAllActionSlots(service, opened, failed);
    }

    @Test
    void aChatSubmissionThrowAfterEnqueueCannotRefundOrReplayTheAcceptedNativeWork() throws Exception {
        ActorControlService service = service(4, 30, 64);
        prepareChat(service);
        var opened = open(service, "open-enqueued-submission-failure");
        executors.getFirst().runNext();
        AtomicReference<PreparedChat> actual = new AtomicReference<>();
        doAnswer(invocation -> {
            PreparedChat prepared = preparedChats.get(invocation.getArgument(0));
            actual.set(prepared);
            prepared.submit();
            throw new IllegalStateException("private failure after enqueue must not escape");
        }).when(backend).submitChat(any());
        ActorActRequest request = chatRequest(opened, "已经受理的原生工作");
        var first = actOrRecover(service, "submit-after-enqueue", request);
        assertEquals(1, executors.getFirst().queue.size());
        assertFalse(actual.get().work.isSettled());
        assertFalse(actual.get().result.isDone());
        assertEquals("OPEN", session(first).get("state"));
        verify(chatDispatch, never()).execute(any(), any());

        doAnswer(invocation -> preparedChats.get(invocation.getArgument(0)).submit()).when(backend).submitChat(any());
        for (int i = 0; i < 6; i++) service.act("admin", "queued-after-throw-" + i, chatRequest(opened, "后续排队" + i));
        assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "eighth-after-throw", chatRequest(opened, "仍不能提前退款"))).reason());
        assertEquals(8, service.publicCapabilities().get("operation_history_size"));
        executors.getFirst().runNext();
        assertTrue(actual.get().work.isSettled());
        var completed = service.get("admin", id(first), 0, 100);
        assertEquals("SUCCEEDED", completed.get("status"));
        assertEquals(id(first), id(service.act("admin", "submit-after-enqueue", request)));
        var closing = service.close("admin", "close-after-enqueued-throw", closeRequest(opened));
        executors.getFirst().drain();
        assertEquals("SUCCEEDED", service.get("admin", id(closing), 0, 100).get("status"));
        verify(chatDispatch, times(1)).execute(any(), any());
    }

    @Test
    void aChatSubmissionErrorAfterEnqueueKeepsItsWorkAndLateTypedSuccessWithoutReplayingTheChat() throws Exception {
        ActorControlService service = service(4, 30, 64);
        prepareChat(service);
        var opened = open(service, "open-enqueued-submission-error");
        executors.getFirst().runNext();
        AtomicReference<PreparedChat> actual = new AtomicReference<>();
        doAnswer(invocation -> {
            PreparedChat prepared = preparedChats.get(invocation.getArgument(0));
            actual.set(prepared);
            prepared.submit();
            throw new AssertionError("private AssertionError after enqueue must not escape");
        }).when(backend).submitChat(any());
        ActorActRequest request = chatRequest(opened, "错误发生前已经受理的原生工作");
        var first = service.act("admin", "submit-error-after-enqueue", request);
        assertEquals(1, executors.getFirst().queue.size());
        assertFalse(actual.get().work.isSettled());
        assertFalse(actual.get().result.isDone());
        assertEquals("OPEN", session(first).get("state"));
        assertFalse(first.toString().contains("private AssertionError"));
        verify(chatDispatch, never()).execute(any(), any());

        doAnswer(invocation -> preparedChats.get(invocation.getArgument(0)).submit()).when(backend).submitChat(any());
        for (int i = 0; i < 6; i++) service.act("admin", "queued-after-error-" + i, chatRequest(opened, "后续排队" + i));
        assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "eighth-after-error", chatRequest(opened, "错误不代表提前退款"))).reason());
        assertEquals(8, service.publicCapabilities().get("operation_history_size"));
        executors.getFirst().runNext();
        assertTrue(actual.get().work.isSettled());
        assertEquals(NativeActionResult.Outcome.SUCCEEDED, actual.get().result.getNow(null).outcome());
        var completed = service.get("admin", id(first), 0, 100);
        assertEquals("SUCCEEDED", completed.get("status"));
        assertEquals(true, result(completed).get("native_dispatch_started"));
        assertEquals(true, result(completed).get("echo_matched"));
        assertEquals(id(first), id(service.act("admin", "submit-error-after-enqueue", request)));
        var closing = service.close("admin", "close-after-enqueued-error", closeRequest(opened));
        executors.getFirst().drain();
        assertEquals("SUCCEEDED", service.get("admin", id(closing), 0, 100).get("status"));
        verify(chatDispatch, times(1)).execute(any(), any());
    }

    private Map<String, Object> actOrRecover(ActorControlService service, String requestId, ActorActRequest request) {
        try {
            return service.act("admin", requestId, request);
        } catch (ObservationException controlledFailure) {
            return service.act("admin", requestId, request);
        }
    }

    private void assertUnqueuedChatFailureAndAllActionSlots(ActorControlService service, Map<String, Object> opened,
                                                          Map<String, Object> failed) throws Exception {
        assertEquals("FAILED", failed.get("status"));
        assertEquals("OPEN", session(failed).get("state"));
        assertEquals(false, result(failed).get("native_dispatch_started"));
        assertFalse(failed.toString().contains("private"));
        assertNull(operationWork(service, failed));
        assertTrue(executors.getFirst().queue.isEmpty());
        assertEquals(0, execution(service, opened).moduleScope().snapshot().acceptedWork());
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
        prepareChat(service);
        for (int i = 0; i < 7; i++) service.act("admin", "queued-after-failure-" + i, chatRequest(opened, "排队" + i));
        assertEquals(7, executors.getFirst().queue.size());
        assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "eighth-after-failure", chatRequest(opened, "八条不能受理"))).reason());
        var closing = service.close("admin", "close-after-failure", closeRequest(opened));
        assertEquals(7, executors.getFirst().queue.size());
        assertCleanupReservedBehindModules(service, closing);
        executors.getFirst().drain();
        assertEquals("SUCCEEDED", service.get("admin", id(closing), 0, 100).get("status"));
        verify(chatDispatch, never()).execute(any(), any());
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
        verify(chatDispatch, never()).execute(any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        for (Long invalidVersion : java.util.Arrays.asList(null, 0L, -1L)) {
            assertEquals("invalid_dialogue_version", assertThrows(ObservationException.class,
                    () -> service.act("admin", "invalid-cancel", npcRequest(opened, "npc_cancel", invalidVersion))).reason());
        }
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
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        verify(backend).close(eq(handle), any(ScopeResources.DrainPermit.class));
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
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        assertEquals("invalid_dialogue_version", assertThrows(ObservationException.class,
                () -> service.act("admin", "zero-version", replyRequest(opened, 0L, "select", 0))).reason());
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
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
        when(observations.contextForActor(any(GameContext.CharacterRef.class))).thenReturn(Map.of("scene_ref", "scene-replaced",
                "runtime_generation", opened.get("runtime_generation")));
        executors.getFirst().runNext();
        assertEquals(Map.of("reason", "actor_context_mismatch"), service.get("admin", id(moved), 0, 100).get("error"));
        verify(backend, never()).npcReply(any(), anyLong(), any(), any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
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
                new ActorActRequest.Parameters(null, version, kind, selection, null, null, null, null, null, null, null, null),
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
        return new ActorActRequest(sessionId(opened), generation(opened), action, new ActorActRequest.Parameters(null, version, null, null, null, null, null, null, null, null, null, null),
                new ActorActRequest.ExpectedContext("scene-current", (String) opened.get("runtime_generation")));
    }

    @Test
    void inventoryMoveUsesOneHostAndItsFullParametersBindTheIdempotencyKey() throws Exception {
        ActorControlService service = service(4, 32, 64);
        prepareInventoryMove(service);
        var opened = open(service, "open-inventory");
        executors.getFirst().runNext();
        var request = inventoryRequest(opened, "ETC", 1, 2);
        var queued = service.act("admin", "inventory-one", request);
        assertEquals("QUEUED", queued.get("status"));
        assertEquals(id(queued), id(service.act("admin", "inventory-one", request)));
        for (ActorActRequest changed : List.of(inventoryRequest(opened, "USE", 1, 2),
                inventoryRequest(opened, "ETC", 3, 2), inventoryRequest(opened, "ETC", 1, 3))) {
            assertEquals("request_id_conflict", assertThrows(ObservationException.class,
                    () -> service.act("admin", "inventory-one", changed)).reason());
        }
        assertSame(execution(service, opened).moduleScope(), operationWork(service, queued).scope());
        assertEquals(runtimeView(opened).get("id"), runtimeView(queued).get("id"));
        executors.getFirst().runNext();
        var moved = service.get("admin", id(queued), 0, 100);
        assertEquals("SUCCEEDED", moved.get("status"));
        assertEquals("native_inventory_move_observed", result(moved).get("observed_outcome"));
        assertEquals(true, result(moved).get("changed"));
        assertNull(operationWork(service, moved));
        verify(backend, times(1)).prepareInventoryMove(eq(handle), any(), any(), any(), any());
        verify(backend, times(1)).submitInventoryMove(any());
        verify(backend, never()).close(any(), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void cancelledInventoryWorkKeepsItsSlotUntilRealLaneSettlementAndReservesCloseCapacity() {
        ActorControlService service = service(4, 40, 64);
        prepareInventoryMove(service);
        var opened = open(service, "open-inventory-cancel");
        executors.getFirst().runNext();
        List<Map<String, Object>> actions = new ArrayList<>();
        for (int i = 0; i < 7; i++) actions.add(service.act("admin", "inventory-queued-" + i,
                inventoryRequest(opened, "ETC", 1, 2)));
        service.cancel("admin", "cancel-inventory", id(actions.getFirst()));
        assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "inventory-eighth", inventoryRequest(opened, "ETC", 1, 2))).reason());
        executors.getFirst().runNext();
        var cancelled = service.get("admin", id(actions.getFirst()), 0, 100);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(false, result(cancelled).get("native_dispatch_started"));
        service.act("admin", "inventory-after-settlement", inventoryRequest(opened, "ETC", 1, 2));
        var closing = service.close("admin", "close-inventory-full", closeRequest(opened));
        executors.getFirst().drain();
        assertEquals("SUCCEEDED", service.get("admin", id(closing), 0, 100).get("status"));
        verify(backend, times(1)).close(eq(handle), any(ScopeResources.DrainPermit.class));
    }

    @Test
    void inventoryMoveRejectsNonMoveSemanticsBeforeTakingHistoryOrWork() {
        ActorControlService service = service(4, 32, 64);
        prepareInventoryMove(service);
        var opened = open(service, "open-inventory-input");
        executors.getFirst().runNext();
        for (ActorActRequest request : List.of(inventoryRequest(opened, "EQUIPPED", 1, 2),
                inventoryRequest(opened, "ETC", 0, 2), inventoryRequest(opened, "ETC", 1, -1),
                inventoryRequest(opened, "ETC", 1, 128), inventoryRequest(opened, "ETC", 1, 1))) {
            assertEquals("invalid_inventory_move", assertThrows(ObservationException.class,
                    () -> service.act("admin", "invalid-inventory", request)).reason());
        }
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).prepareInventoryMove(any(), any(), any(), any(), any());
    }

    @Test
    void basicActionParametersBindEveryNewFingerprintFieldAndUseOneHostWork() throws Exception {
        ActorControlService service = service(4, 128, 64);
        prepareBasic(service, null);
        var opened = open(service, "basic-fingerprint-open");
        executors.getFirst().runNext();
        var requests = List.of(
                basicRequest(opened, "move", Map.of("direction", "LEFT", "duration_ms", 300)),
                basicRequest(opened, "attack", Map.of("target_oid", 11)),
                basicRequest(opened, "pickup", Map.of("object_id", 21)),
                basicRequest(opened, "drop", Map.of("inventory_type", "ETC", "source_slot", 1, "quantity", 1)),
                basicRequest(opened, "use_item", Map.of("source_slot", 1)),
                basicRequest(opened, "equip", Map.of("source_slot", 1, "target_slot", -11)),
                basicRequest(opened, "unequip", Map.of("source_slot", -11, "target_slot", 1)),
                basicRequest(opened, "stop", Map.of()));
        int index = 0;
        for (var request : requests) {
            String requestId = "basic-id-" + index++;
            var accepted = service.act("admin", requestId, request);
            assertEquals(id(accepted), id(service.act("admin", requestId, request)));
            if (operationWork(service, accepted) != null) {
                assertSame(execution(service, opened).moduleScope(), operationWork(service, accepted).scope());
                assertEquals(runtimeView(opened).get("id"), runtimeView(accepted).get("id"));
            }
            for (String field : switch (request.action()) {
                case "move" -> List.of("direction", "duration_ms");
                case "attack" -> List.of("target_oid");
                case "pickup" -> List.of("object_id");
                case "drop" -> List.of("inventory_type", "source_slot", "quantity");
                case "use_item" -> List.of("source_slot");
                case "equip", "unequip" -> List.of("source_slot", "target_slot");
                default -> List.<String>of();
            }) {
                Map<String, Object> changed = basicParameterFields(request.parameters());
                Object old = changed.get(field);
                changed.put(field, old instanceof String value ? "direction".equals(field) ? "RIGHT" : "USE"
                        : "source_slot".equals(field) && "unequip".equals(request.action()) ? -7
                        : "target_slot".equals(field) && "equip".equals(request.action()) ? -7 : (Integer) old + 1);
                assertEquals("request_id_conflict", assertThrows(ObservationException.class,
                        () -> service.act("admin", requestId, basicRequest(opened, request.action(), changed))).reason());
            }
            executors.getFirst().drain();
            var finished = service.get("admin", id(accepted), 0, 100);
            assertEquals("SUCCEEDED", finished.get("status"));
            assertEquals("native_" + request.action() + "_observed", result(finished).get("observed_outcome"));
            assertNull(operationWork(service, finished));
        }
    }

    @Test
    void aSecondMoveReturnsItsPreciseBusyOperationWithoutCancellingTheOriginalMove() throws Exception {
        ActorControlService service = service(4, 64, 64);
        AtomicReference<HeldBasic> active = new AtomicReference<>();
        prepareBasic(service, active);
        var opened = open(service, "open-busy-movement");
        executors.getFirst().runNext();
        var request = basicRequest(opened, "move", Map.of("direction", "RIGHT", "duration_ms", 3000));
        var first = service.act("admin", "busy-first", request);
        HeldBasic firstNative = active.get();
        var firstWork = operationWork(service, first);
        assertEquals("RUNNING", first.get("status"));

        var busy = assertDoesNotThrow(() -> service.act("admin", "busy-second", request));
        assertEquals("FAILED", busy.get("status"));
        assertEquals("movement_busy", ((Map<?, ?>) busy.get("error")).get("reason"));
        assertEquals(false, result(busy).get("native_dispatch_started"));
        assertEquals(id(busy), id(service.act("admin", "busy-second", request)));
        assertSame(firstNative, active.get());
        assertFalse(firstWork.cancelRequested());
        assertFalse(firstWork.isSettled());
        assertEquals("RUNNING", service.get("admin", id(first), 0, 100).get("status"));
        verify(backend, times(2)).prepareMovement(any(), any(), any(), any(), eq("move"), any(), any());

        firstNative.complete(false);
        assertTrue(firstWork.isSettled());
        assertEquals("SUCCEEDED", service.get("admin", id(first), 0, 100).get("status"));
    }

    @Test
    void basicCapacityRejectionStays429AndItsOriginalIdRemainsRecoverableWithoutEarlyRefund() throws Exception {
        ActorControlService service = service(4, 64, 64);
        AtomicReference<HeldBasic> rejected = new AtomicReference<>();
        prepareBasic(service, null, rejected);
        var opened = open(service, "open-basic-capacity");
        executors.getFirst().runNext();
        var request = basicRequest(opened, "pickup", Map.of("object_id", 7));
        var failure = assertThrows(ObservationException.class, () -> service.act("admin", "basic-capacity", request));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.status());
        assertEquals("actor_queue_full", failure.reason());
        HeldBasic nativeWork = rejected.get();
        try {
            var recovered = service.act("admin", "basic-capacity", request);
            assertEquals("QUEUED", recovered.get("status"));
            assertSame(nativeWork.work, operationWork(service, recovered));
            var ordinary = basicRequest(opened, "drop", Map.of("inventory_type", "ETC", "source_slot", 1, "quantity", 1));
            for (int i = 0; i < 6; i++) service.act("admin", "capacity-ordinary-" + i, ordinary);
            nativeWork.failResult("native_action_queue_rejected");
            assertEquals("FAILED", service.get("admin", id(recovered), 0, 100).get("status"));
            assertFalse(nativeWork.work.isSettled());
            assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                    () -> service.act("admin", "capacity-before-settlement", ordinary)).reason());
            nativeWork.work.carrierSettled();
            service.act("admin", "capacity-after-settlement", ordinary);
            var repeated = service.act("admin", "basic-capacity", request);
            assertEquals(id(recovered), id(repeated));
            assertEquals("FAILED", repeated.get("status"));
            verify(backend, times(1)).prepareBasic(any(), any(), any(), any(), eq("pickup"), any());
            verify(backend, times(1)).submitBasic(eq(nativeWork.handle), any());
            executors.getFirst().drain();
        } finally {
            if (!nativeWork.result.isDone()) nativeWork.failResult("native_action_queue_rejected");
            nativeWork.work.carrierSettled();
        }
    }

    @Test
    void queuedBasicCancellationKeepsTheQuotaUntilTheOriginalCarrierSettles() {
        ActorControlService service = service(4, 64, 64);
        prepareBasic(service, null);
        var opened = open(service, "basic-queue-open");
        executors.getFirst().runNext();
        var request = basicRequest(opened, "drop", Map.of("inventory_type", "ETC", "source_slot", 1, "quantity", 1));
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (int i = 0; i < 7; i++) accepted.add(service.act("admin", "basic-queue-" + i, request));
        service.cancel("admin", "basic-cancel", id(accepted.getFirst()));
        assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "basic-eight", request)).reason());
        executors.getFirst().runNext();
        assertEquals("CANCELLED", service.get("admin", id(accepted.getFirst()), 0, 100).get("status"));
        service.act("admin", "basic-after-real-settlement", request);
        var close = service.close("admin", "basic-full-close", closeRequest(opened));
        executors.getFirst().drain();
        assertEquals("SUCCEEDED", service.get("admin", id(close), 0, 100).get("status"));
        verify(backend, times(1)).close(eq(handle), any());
    }

    @Test
    void cancellingDispatchedMovementPreservesItsPositionFactsAndWaitsForActualParentSettlement() throws Exception {
        ActorControlService service = service(4, 64, 64);
        AtomicReference<HeldBasic> held = new AtomicReference<>();
        prepareBasic(service, held);
        var opened = open(service, "basic-held-open");
        executors.getFirst().runNext();
        var moving = service.act("admin", "basic-held-move", basicRequest(opened, "move", Map.of("direction", "RIGHT", "duration_ms", 3000)));
        assertEquals("RUNNING", moving.get("status"));
        var parent = operationWork(service, moving);
        assertFalse(parent.isSettled());
        service.cancel("admin", "cancel-held", id(moving));
        for (int i = 0; i < 6; i++) service.act("admin", "basic-after-held-" + i,
                basicRequest(opened, "pickup", Map.of("object_id", 20 + i)));
        assertEquals("actor_action_capacity_exceeded", assertThrows(ObservationException.class,
                () -> service.act("admin", "before-parent-settled", basicRequest(opened, "pickup", Map.of("object_id", 40)))).reason());
        held.get().complete(true);
        assertTrue(parent.isSettled());
        var stopped = service.get("admin", id(moving), 0, 100);
        assertEquals("CANCELLED", stopped.get("status"));
        assertEquals(true, result(stopped).get("native_dispatch_started"));
        assertEquals(true, result(stopped).get("movement_stopped"));
        assertEquals(false, result(stopped).get("cancel_too_late"));
        assertEquals(Map.of("x", 10, "y", 0), result(stopped).get("after"));
        service.act("admin", "after-parent-settled", basicRequest(opened, "pickup", Map.of("object_id", 40)));
        executors.getFirst().drain();
    }

    @Test
    void rootCloseWaitsForTheInFlightMovementCarrierBeforeEnteringNativeLogout() throws Exception {
        ActorControlService service = service(4, 64, 64);
        AtomicReference<HeldBasic> held = new AtomicReference<>();
        prepareBasic(service, held);
        var opened = open(service, "basic-close-held-open");
        executors.getFirst().runNext();
        var moving = service.act("admin", "basic-close-held-move", basicRequest(opened, "move", Map.of("direction", "RIGHT", "duration_ms", 3000)));
        var work = operationWork(service, moving);
        var closing = service.close("admin", "close-while-moving", closeRequest(opened));
        assertEquals("CLOSING", session(closing).get("state"));
        assertFalse(work.isSettled());
        assertTrue(work.cancelRequested());
        assertTrue(executors.getFirst().queue.isEmpty());
        verify(backend, never()).close(any(), any());
        held.get().complete(true);
        assertTrue(work.isSettled());
        assertEquals(1, executors.getFirst().queue.size());
        verify(backend, never()).close(any(), any());
        executors.getFirst().drain();
        var closed = service.get("admin", id(closing), 0, 100);
        assertEquals("SUCCEEDED", closed.get("status"));
        assertEquals("CLOSED", session(closed).get("state"));
        assertEquals("CLOSED", runtimeView(closed).get("state"));
        verify(backend, times(1)).close(eq(handle), any());
    }

    @Test
    void basicActionsRejectMissingAndOutOfRangeFieldsBeforeAllocatingHistory() {
        ActorControlService service = service(4, 64, 64);
        prepareBasic(service, null);
        var opened = open(service, "basic-invalid-open");
        executors.getFirst().runNext();
        for (var request : List.of(basicRequest(opened, "move", Map.of("direction", "RIGHT")),
                basicRequest(opened, "attack", Map.of("target_oid", 0)),
                basicRequest(opened, "pickup", Map.of()),
                basicRequest(opened, "drop", Map.of("inventory_type", "ETC", "source_slot", 1)),
                basicRequest(opened, "use_item", Map.of()),
                basicRequest(opened, "equip", Map.of("source_slot", 1)),
                basicRequest(opened, "unequip", Map.of("target_slot", 1)),
                basicRequest(opened, "drop", Map.of("inventory_type", "EQUIPPED", "source_slot", -11, "quantity", 1)),
                basicRequest(opened, "equip", Map.of("source_slot", 1, "target_slot", -14)),
                basicRequest(opened, "unequip", Map.of("source_slot", -101, "target_slot", 1)))) {
            assertEquals("invalid_basic_action_parameters", assertThrows(ObservationException.class,
                    () -> service.act("admin", "invalid-basic", request)).reason());
        }
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).prepareBasic(any(), any(), any(), any(), anyString(), any());
        verify(backend, never()).prepareMovement(any(), any(), any(), any(), anyString(), any(), any());
    }

    /** Native behavior is an explicit seam; host scopes, lane carriers, cancellation and drain stay real. */
    private void prepareBasic(ActorControlService service, AtomicReference<HeldBasic> holdMovement) {
        prepareBasic(service, holdMovement, null);
    }

    private void prepareBasic(ActorControlService service, AtomicReference<HeldBasic> holdMovement,
                               AtomicReference<HeldBasic> capacityRejection) {
        AcceptanceObservationService observations = mock(AcceptanceObservationService.class);
        when(observations.contextForActor(any(GameContext.CharacterRef.class))).thenReturn(Map.of("scene_ref", "scene-current",
                "runtime_generation", AcceptanceObservationRuntime.snapshot().generation()));
        service.setObservations(observations);
        Map<NativeBasicActions.Handle, java.util.function.Supplier<NativeActionService.SubmissionReceipt>> prepared = new IdentityHashMap<>();
        doAnswer(invocation -> {
            NativeActorHandle actor = invocation.getArgument(0);
            CharacterCommandLane lane = invocation.getArgument(1);
            CharacterCommandLane.ControlPermit permit = invocation.getArgument(2);
            ExecutionScope.WorkHandle work = invocation.getArgument(3);
            String action = invocation.getArgument(4);
            NativeBasicActions.CommandHandle command = mock(NativeBasicActions.CommandHandle.class);
            HeldBasic state = new HeldBasic(command, actor, work, action);
            if (capacityRejection != null && "pickup".equals(action)) {
                capacityRejection.set(state);
                when(state.receipt.status()).thenReturn(NativeActionService.SubmissionStatus.REJECTED);
                when(state.receipt.rejection()).thenReturn(NativeActionService.SubmissionRejection.CAPACITY);
                prepared.put(command, () -> {
                    assertTrue(work.claimCarrier());
                    work.scope().runTracked(work, () -> { });
                    return state.receipt;
                });
                return command;
            }
            prepared.put(command, () -> {
                lane.submit(work, permit, () -> { state.dispatched.set(true); state.completeResult(false); },
                        () -> { if (!state.result.isDone()) state.completeResult(true); });
                return state.receipt;
            });
            return command;
        }).when(backend).prepareBasic(any(), any(), any(), any(), anyString(), any());
        doAnswer(invocation -> {
            NativeActorHandle actor = invocation.getArgument(0);
            CharacterRuntime.ModuleContext context = invocation.getArgument(2);
            ExecutionScope.WorkHandle work = invocation.getArgument(3);
            String action = invocation.getArgument(4);
            Runnable preflight = invocation.getArgument(6);
            assertSame(context.scope(), work.scope());
            NativeBasicActions.Handle command = mock(NativeBasicActions.Handle.class);
            HeldBasic state = new HeldBasic(command, actor, work, action);
            prepared.put(command, () -> {
                assertTrue(work.claimCarrier());
                work.scope().runTracked(work, preflight);
                if (holdMovement != null && "move".equals(action) && holdMovement.get() != null) {
                    when(state.receipt.status()).thenReturn(NativeActionService.SubmissionStatus.REJECTED);
                    when(state.receipt.rejection()).thenReturn(NativeActionService.SubmissionRejection.PERMIT);
                    state.failResult("movement_busy");
                    work.carrierSettled();
                    return state.receipt;
                }
                state.dispatched.set(true);
                if (holdMovement != null && "move".equals(action)) holdMovement.set(state);
                else state.complete(false);
                return state.receipt;
            });
            return command;
        }).when(backend).prepareMovement(any(), any(), any(), any(), anyString(), any(), any());
        doAnswer(invocation -> prepared.get(invocation.getArgument(0)).get()).when(backend).submitBasic(any(), any());
    }

    private static void assertCleanupReservedBehindModules(ActorControlService service, Map<String, Object> closing) throws Exception {
        var cleanup = operationWork(service, closing);
        assertNotNull(cleanup);
        assertTrue(cleanup.isCleanup());
        assertFalse(cleanup.snapshot().carrierClaimed());
        assertFalse(cleanup.isSettled());
    }

    private static final class HeldBasic {
        final NativeBasicActions.Handle handle;
        final NativeActorHandle actor;
        final ExecutionScope.WorkHandle work;
        final String action;
        final CompletableFuture<NativeBasicResult> result = new CompletableFuture<>();
        final AtomicBoolean dispatched = new AtomicBoolean();
        final NativeActionService.SubmissionReceipt receipt = mock(NativeActionService.SubmissionReceipt.class);
        HeldBasic(NativeBasicActions.Handle handle, NativeActorHandle actor, ExecutionScope.WorkHandle work, String action) {
            this.handle = handle; this.actor = actor; this.work = work; this.action = action;
            when(handle.result()).thenReturn(result.minimalCompletionStage());
            when(handle.dispatchStarted()).thenAnswer(ignored -> dispatched.get());
            when(handle.receipt()).thenReturn(receipt);
            when(receipt.status()).thenReturn(NativeActionService.SubmissionStatus.ENQUEUED);
        }
        void complete(boolean cancelled) { completeResult(cancelled); work.carrierSettled(); }
        void failResult(String reason) {
            result.complete(new NativeBasicResult(action, NativeActionResult.Outcome.FAILED, NativeActionResult.Dispatch.NOT_STARTED,
                    actor.characterRef().character().getId(), work.acceptedEpoch().value(), work.cancelRequested(), false, reason, Map.of()));
        }
        void completeResult(boolean cancelled) {
            Map<String, Object> fields = !dispatched.get() ? Map.of() : "move".equals(action) && cancelled
                    ? Map.of("effect_observed", true, "evidence_source", "NATIVE_TEST_MOVEMENT",
                    "movement_stopped", true, "completed_duration", false,
                    "before", Map.of("x", 0, "y", 0), "after", Map.of("x", 10, "y", 0))
                    : Map.of("effect_observed", true, "evidence_source", "NATIVE_TEST_BOUNDARY");
            result.complete(new NativeBasicResult(action, cancelled ? NativeActionResult.Outcome.CANCELLED : NativeActionResult.Outcome.SUCCEEDED,
                    dispatched.get() ? NativeActionResult.Dispatch.STARTED : NativeActionResult.Dispatch.NOT_STARTED,
                    actor.characterRef().character().getId(), work.acceptedEpoch().value(), work.cancelRequested(), false,
                    cancelled ? "movement_cancelled" : null, fields));
        }
    }

    private static ActorActRequest basicRequest(Map<String, Object> opened, String action, Map<String, Object> fields) {
        return new ActorActRequest(sessionId(opened), generation(opened), action, new ActorActRequest.Parameters(null, null, null, null,
                (String) fields.get("inventory_type"), (Integer) fields.get("source_slot"), (Integer) fields.get("target_slot"),
                (String) fields.get("direction"), (Integer) fields.get("duration_ms"), (Integer) fields.get("target_oid"),
                (Integer) fields.get("object_id"), (Integer) fields.get("quantity")),
                new ActorActRequest.ExpectedContext("scene-current", (String) opened.get("runtime_generation")));
    }

    private static Map<String, Object> basicParameterFields(ActorActRequest.Parameters p) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (p.inventoryType() != null) result.put("inventory_type", p.inventoryType());
        if (p.sourceSlot() != null) result.put("source_slot", p.sourceSlot());
        if (p.targetSlot() != null) result.put("target_slot", p.targetSlot());
        if (p.direction() != null) result.put("direction", p.direction());
        if (p.durationMs() != null) result.put("duration_ms", p.durationMs());
        if (p.targetOid() != null) result.put("target_oid", p.targetOid());
        if (p.objectId() != null) result.put("object_id", p.objectId());
        if (p.quantity() != null) result.put("quantity", p.quantity());
        return result;
    }

    private void prepareInventoryMove(ActorControlService service) {
        AcceptanceObservationService observations = mock(AcceptanceObservationService.class);
        when(observations.contextForActor(any(GameContext.CharacterRef.class))).thenReturn(Map.of("scene_ref", "scene-current",
                "runtime_generation", AcceptanceObservationRuntime.snapshot().generation()));
        service.setObservations(observations);
        Map<NativeInventoryActions.MoveHandle, java.util.function.Supplier<NativeActionService.SubmissionReceipt>> prepared =
                new IdentityHashMap<>();
        doAnswer(invocation -> {
            NativeActorHandle actor = invocation.getArgument(0);
            CharacterCommandLane lane = invocation.getArgument(1);
            CharacterCommandLane.ControlPermit permit = invocation.getArgument(2);
            ExecutionScope.WorkHandle work = invocation.getArgument(3);
            NativeInventoryMoveAction request = invocation.getArgument(4);
            NativeInventoryActions.MoveHandle handle = mock(NativeInventoryActions.MoveHandle.class);
            NativeActionService.SubmissionReceipt receipt = mock(NativeActionService.SubmissionReceipt.class);
            CompletableFuture<InventoryMoveResult> result = new CompletableFuture<>();
            AtomicBoolean dispatched = new AtomicBoolean();
            when(handle.result()).thenReturn(result.minimalCompletionStage());
            when(handle.dispatchStarted()).thenAnswer(ignored -> dispatched.get());
            when(handle.receipt()).thenReturn(receipt);
            when(receipt.status()).thenReturn(NativeActionService.SubmissionStatus.ENQUEUED);
            prepared.put(handle, () -> {
                lane.submit(work, permit, () -> {
                    dispatched.set(true);
                    var before = new InventoryMoveResult.Slots(new InventoryMoveResult.Slot(request.sourceSlot(), true, 4161001, 1),
                            new InventoryMoveResult.Slot(request.targetSlot(), false, 0, 0));
                    var after = new InventoryMoveResult.Slots(new InventoryMoveResult.Slot(request.sourceSlot(), false, 0, 0),
                            new InventoryMoveResult.Slot(request.targetSlot(), true, 4161001, 1));
                    result.complete(new InventoryMoveResult(NativeActionResult.Outcome.SUCCEEDED, NativeActionResult.Dispatch.STARTED,
                            actor.characterRef().character().getId(), work.cancelRequested(), false, null,
                            new InventoryMoveResult.Evidence(InventoryMoveResult.Source.NATIVE_INVENTORY_SLOTS,
                                    work.acceptedEpoch().value(), request.inventoryType(), before, after, true, true)));
                }, () -> {
                    if (!result.isDone()) result.complete(new InventoryMoveResult(NativeActionResult.Outcome.CANCELLED,
                            NativeActionResult.Dispatch.NOT_STARTED, actor.characterRef().character().getId(), true, false,
                            "inventory_move_cancelled_before_dispatch", InventoryMoveResult.Evidence.none(work.acceptedEpoch().value())));
                });
                return receipt;
            });
            return handle;
        }).when(backend).prepareInventoryMove(any(), any(), any(), any(), any());
        doAnswer(invocation -> prepared.get(invocation.getArgument(0)).get()).when(backend).submitInventoryMove(any());
    }

    private static ActorActRequest inventoryRequest(Map<String, Object> opened, String inventoryType, int source, int target) {
        return new ActorActRequest(sessionId(opened), generation(opened), "inventory_move",
                new ActorActRequest.Parameters(null, null, null, null, inventoryType, source, target, null, null, null, null, null),
                new ActorActRequest.ExpectedContext("scene-current", (String) opened.get("runtime_generation")));
    }

    private AcceptanceObservationService prepareChat(ActorControlService service) {
        AcceptanceObservationService observations = mock(AcceptanceObservationService.class);
        when(observations.contextForActor(any(GameContext.CharacterRef.class))).thenReturn(Map.of("scene_ref", "scene-current",
                "runtime_generation", AcceptanceObservationRuntime.snapshot().generation()));
        service.setObservations(observations);
        doAnswer(invocation -> chatSuccess(invocation.getArgument(0))).when(chatDispatch).execute(any(), any());
        doAnswer(invocation -> {
            NativeActorHandle actor = invocation.getArgument(0);
            PreparedChat prepared = new PreparedChat(actor, invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), observations);
            preparedChats.put(prepared.handle, prepared);
            return prepared.handle;
        }).when(backend).prepareChat(any(), any(), any(), any(), any());
        doAnswer(invocation -> preparedChats.get(invocation.getArgument(0)).submit())
                .when(backend).submitChat(any());
        return observations;
    }

    private NativeActionResult chatSuccess() { return chatSuccess(handle); }

    private static NativeActionResult chatSuccess(NativeActorHandle actor) {
        return new NativeActionResult(NativeActionResult.Outcome.SUCCEEDED, NativeActionResult.Dispatch.STARTED,
                actor.characterRef().character().getId(), false, false, null, new ChatEvidence(
                ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, "fixture-self-packets", actor.characterRef().epoch().value(),
                31L, 32L, 32L, ChatEvidence.Completeness.COMPLETE, false));
    }

    private ChatEvidence chatEvidence(Long before, Long after, Long matched,
                                      ChatEvidence.Completeness completeness) {
        return new ChatEvidence(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, "fixture-self-packets",
                handle.characterRef().epoch().value(), before, after, matched, completeness, false);
    }

    /** Only the native behavior is mocked: admission, physical queue and factual settlement remain real. */
    private interface ChatDispatch {
        NativeActionResult execute(NativeActorHandle actor, String text);
    }

    private final class PreparedChat {
        final NativeActionService.ActionHandle handle = mock(NativeActionService.ActionHandle.class);
        final NativeActionService.SubmissionReceipt receipt = mock(NativeActionService.SubmissionReceipt.class);
        final CompletableFuture<NativeActionResult> result = new CompletableFuture<>();
        final AtomicBoolean dispatched = new AtomicBoolean();
        final AtomicBoolean submitted = new AtomicBoolean();
        final AtomicReference<NativeActionService.SubmissionStatus> status =
                new AtomicReference<>(NativeActionService.SubmissionStatus.PREPARED);
        final AtomicReference<NativeActionService.SubmissionRejection> rejection =
                new AtomicReference<>(NativeActionService.SubmissionRejection.NONE);
        final NativeActorHandle actor;
        final CharacterCommandLane lane;
        final CharacterCommandLane.ControlPermit permit;
        final ExecutionScope.WorkHandle work;
        final NativeChatAction action;
        final AcceptanceObservationService observations;
        final Map<String, Object> expectedScene;

        PreparedChat(NativeActorHandle actor, CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                     ExecutionScope.WorkHandle work, NativeChatAction action, AcceptanceObservationService observations) {
            this.actor = actor;
            this.lane = lane;
            this.permit = permit;
            this.work = work;
            this.action = action;
            this.observations = observations;
            expectedScene = observations.contextForActor(actor.characterRef());
            when(handle.result()).thenReturn(result.minimalCompletionStage());
            when(handle.dispatchStarted()).thenAnswer(invocation -> dispatched.get());
            when(handle.receipt()).thenReturn(receipt);
            when(receipt.workId()).thenReturn(work.snapshot().id());
            when(receipt.status()).thenAnswer(invocation -> status.get());
            when(receipt.rejection()).thenAnswer(invocation -> rejection.get());
        }

        NativeActionService.SubmissionReceipt submit() {
            if (!submitted.compareAndSet(false, true)) return receipt;
            status.set(NativeActionService.SubmissionStatus.SUBMITTING);
            CharacterCommandLane.Submission submission = lane.submit(work, permit, this::execute, this::settled);
            if (submission.status() == CharacterCommandLane.Status.ENQUEUED) {
                status.set(NativeActionService.SubmissionStatus.ENQUEUED);
            } else {
                status.set(NativeActionService.SubmissionStatus.REJECTED);
                rejection.set(submission.status() == CharacterCommandLane.Status.DUPLICATE
                        ? NativeActionService.SubmissionRejection.WORK_ALREADY_BOUND
                        : NativeActionService.SubmissionRejection.valueOf(submission.rejection().name()));
            }
            return receipt;
        }

        void execute() {
            try {
                backend.validateChat(actor, action.text());
                if (!expectedScene.equals(observations.contextForActor(actor.characterRef()))) {
                    throw new ObservationException(HttpStatus.CONFLICT, "actor_context_mismatch");
                }
                dispatched.set(true);
                NativeActionResult outcome = chatDispatch.execute(actor, action.text());
                result.complete(new NativeActionResult(outcome.outcome(), outcome.dispatch(), outcome.senderCharacterId(),
                        work.cancelRequested() || outcome.cancelRequested(), outcome.handlerError(), outcome.fixedReason(),
                        outcome.evidence()));
            } catch (Throwable failure) {
                String reason = dispatched.get() ? "native_chat_outcome_unknown"
                        : failure instanceof ObservationException known ? known.reason() : "native_action_validation_failed";
                result.complete(new NativeActionResult(dispatched.get() ? NativeActionResult.Outcome.INDETERMINATE
                                : NativeActionResult.Outcome.FAILED, dispatched.get() ? NativeActionResult.Dispatch.STARTED
                                : NativeActionResult.Dispatch.NOT_STARTED, actor.characterRef().character().getId(), work.cancelRequested(), dispatched.get(), reason,
                        ChatEvidence.none(work.acceptedEpoch().value())));
            }
        }

        void settled() {
            if (result.isDone()) return;
            boolean cancelled = work.cancelRequested() || !WorldEpoch.snapshot().active()
                    || WorldEpoch.snapshot().token() != work.acceptedEpoch();
            result.complete(new NativeActionResult(cancelled ? NativeActionResult.Outcome.CANCELLED : NativeActionResult.Outcome.FAILED,
                    NativeActionResult.Dispatch.NOT_STARTED, actor.characterRef().character().getId(), cancelled, false,
                    cancelled ? "chat_cancelled_before_dispatch" : "native_action_queue_rejected",
                    ChatEvidence.none(work.acceptedEpoch().value())));
        }
    }

    private static ActorActRequest chatRequest(Map<String, Object> opened, String text) {
        return new ActorActRequest(sessionId(opened), generation(opened), "chat", new ActorActRequest.Parameters(text, null, null, null, null, null, null, null, null, null, null, null),
                new ActorActRequest.ExpectedContext("scene-current", (String) opened.get("runtime_generation")));
    }

    private static NativeActorHandle handleFixture(AcceptanceClient client) {
        return handleFixture(client, 73);
    }

    private static NativeActorHandle handleFixture(AcceptanceClient client, int characterId) {
        NativeActorHandle result = mock(NativeActorHandle.class);
        org.gms.client.Character character = mock(org.gms.client.Character.class);
        org.gms.net.server.world.World world = mock(org.gms.net.server.world.World.class);
        org.gms.net.server.channel.Channel channel = mock(org.gms.net.server.channel.Channel.class);
        when(character.getId()).thenReturn(characterId);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        when(result.client()).thenReturn(client);
        when(result.character()).thenReturn(character);
        when(result.world()).thenReturn(world);
        when(result.channel()).thenReturn(channel);
        when(result.characterRef()).thenReturn(new GameContext.CharacterRef(
                WorldEpoch.snapshot().token(), character, client, world, channel));
        return result;
    }

    private static NativeActorHandle adoptOpen(NativeActorBackend.OpenOwnership ownership, NativeActorHandle result) {
        ownership.bindOrigin(result.world(), result.channel());
        ownership.adopt(result);
        return result;
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

    @Test
    void malformedScriptInputsAreRejectedBeforeTakingHistoryOrScopeCapacity() throws Exception {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "script-validation-open");
        executors.getFirst().runNext();
        Object nested = 1;
        for (int index = 0; index < 17; index++) nested = Map.of("nested", nested);
        for (Map<String, Object> parameters : List.<Map<String, Object>>of(Map.of("large", "x".repeat(16384)),
                Map.of("infinite", Double.POSITIVE_INFINITY), Map.of("deep", nested), Map.of("text", "\ud800"), Map.of("\ud800", 1))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class, () -> service.scriptRun("admin", "invalid-script",
                    scriptRequest(sessionId(opened), generation(opened), "1", parameters, 1000L, new ScriptBinding("npc", "start", null, Map.of(), null, List.of())))).status());
        }
        for (String source : List.of("x".repeat(65537), "\ud800", "\udc00")) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class, () -> service.scriptRun("admin", "invalid-script",
                    scriptRequest(sessionId(opened), generation(opened), source, Map.of(), 1000L, new ScriptBinding("npc", "start", null, Map.of(), null, List.of())))).status());
        }
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                () -> service.scriptRun("admin", "standalone-source-without-native-context", new ActorScriptRequest(
                        sessionId(opened), generation(opened), null, null, null, Map.of(), null, List.of(), "42", Map.of(), 1000L))).status());
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        assertEquals(0, execution(service, opened).moduleScope().snapshot().acceptedWork());
    }

    @Test
    void scriptErrorsAndDeadlineReturnSerializableResultsOnlyAfterActualSettlement() throws Exception {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "script-errors-open");
        executors.getFirst().runNext();
        prepareNativeAdapter();
        for (var sample : Map.of("parse", "const = ;", "runtime", "throw new Error('失败')", "deadline", "while (true) {}" ).entrySet()) {
            long deadline = "deadline".equals(sample.getKey()) ? 100L : 5000L;
            var outcome = service.scriptRun("admin", "script-error-" + sample.getKey(),
                    scriptRequest(sessionId(opened), generation(opened), sample.getValue(), Map.of(), deadline, new ScriptBinding("npc", "start", null, Map.of(), null, List.of())));
            assertEquals("FAILED", outcome.get("status"));
            assertEquals(switch (sample.getKey()) {
                case "parse" -> "PARSE_ERROR";
                case "runtime" -> "RUNTIME_ERROR";
                default -> "TIMED_OUT";
            }, result(outcome).get("status"));
            assertEquals(true, result(outcome).get("engine_exited"));
            assertEquals(true, result(outcome).get("context_closed"));
            assertEquals(true, result(outcome).get("work_settled"));
            assertNull(operationWork(service, outcome));
            assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(outcome));
        }
        assertEquals(0, execution(service, opened).moduleScope().snapshot().runningBodies());
        assertEquals(0, execution(service, opened).moduleScope().snapshot().pendingCarriers());
    }

    @Test
    void rootCloseWaitsForNativeLoopExitBeforeNativeReleaseAndKeepsItsHistoryReservation() throws Exception {
        ActorControlService service = service(4, 3, 64);
        var opened = open(service, "script-closing-open");
        executors.getFirst().runNext();
        AcceptanceActorExecution execution = execution(service, opened);
        TransportNativeAdapter adapter = prepareNativeAdapter();
        var running = CompletableFuture.supplyAsync(() -> service.scriptRun("admin", "script-closing",
                scriptRequest(sessionId(opened), generation(opened), "entered(); while (true) {}", Map.of(), 5000L,
                        new ScriptBinding("npc", "start", null, Map.of(), null, List.of()))));
        assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), "native_guest_loop_must_really_enter_before_root_close");
        awaitScriptBody(execution);
        assertEquals("actor_work_in_flight", assertThrows(ObservationException.class, () -> service.scriptRun("admin", "too-many-scripts",
                scriptRequest(sessionId(opened), generation(opened), "1", Map.of(), 5000L,
                        new ScriptBinding("npc", "start", null, Map.of(), null, List.of())))).reason());
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenAnswer(invocation -> {
            assertEquals(0, execution.moduleScope().snapshot().acceptedWork());
            assertEquals(0, execution.moduleScope().snapshot().runningBodies());
            assertEquals(0, execution.moduleScope().snapshot().pendingCarriers());
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", true);
        });
        var close = service.close("admin", "script-closing-close", closeRequest(opened));
        var stopped = running.get(5, TimeUnit.SECONDS);
        assertEquals("CANCELLED", stopped.get("status"));
        assertEquals(true, result(stopped).get("work_settled"));
        executors.getFirst().drain();
        assertEquals("SUCCEEDED", service.get("admin", id(close), 0, 100).get("status"));
        assertEquals("CLOSED", service.get("admin", id(close), 0, 100).get("session") instanceof Map<?, ?> state ? state.get("state") : null);
    }

    private static void awaitScriptBody(AcceptanceActorExecution execution) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (execution.moduleScope().snapshot().runningBodies() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(execution.moduleScope().snapshot().runningBodies() > 0, "script entered its original module scope");
    }

    @Test
    void nativeCancellationCheckpointFingerprintsTheEnvironmentWithoutStartingAnyAdapter() throws Exception {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "native-cancel-open");
        executors.getFirst().runNext();
        var environment = new ScriptBinding("npc", "start", null, Map.of("npc_id", 9010000), null, List.of());
        var request = scriptRequest(sessionId(opened), generation(opened), "", Map.of(), 1000L, environment);
        var cancelled = service.scriptCancel("admin", "native-early-cancel", scriptCancelRequest(
                sessionId(opened), generation(opened), "native-late-start", request.source(), request.parameters(),
                request.timeoutMs(), environment));
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(false, result(cancelled).get("engine_started"));
        assertEquals(true, result(cancelled).get("invocation_settled"));
        assertEquals("NOT_STARTED", result(cancelled).get("context_state"));
        assertEquals(id(cancelled), id(service.scriptRun("admin", "native-late-start", request)));
        verify(backend, never()).nativeScriptAdapter(any(), anyString());
        assertNull(operationWork(service, cancelled));
        assertEquals(0, execution(service, opened).moduleScope().snapshot().acceptedWork());
        for (var changed : List.of(
                new ScriptBinding("quest", "start", null, environment.options(), null, List.of()),
                new ScriptBinding("npc", "start", null, Map.of("npc_id", 9010001), null, List.of()),
                new ScriptBinding("npc", "invoke", "context-1", Map.of(), "reply", List.of(1, 0, 2)))) {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ObservationException.class,
                    () -> service.scriptRun("admin", "native-late-start", scriptRequest(sessionId(opened),
                            generation(opened), "", Map.of(), 1000L, changed))).status());
        }
    }

    @Test
    void cancellingBeforeInvokeOrCloseAdmissionPreservesTheExistingContextAndItsCleanupObligation() throws Exception {
        ActorControlService service = service(4, 24, 64);
        var opened = open(service, "native-early-existing-open"); executors.getFirst().runNext();
        TransportNativeAdapter adapter = prepareNativeAdapter();
        var started = service.scriptRun("admin", "native-early-existing-start", scriptRequest(
                sessionId(opened), generation(opened), "count", Map.of(), 5000L,
                new ScriptBinding("npc", "start", null, Map.of(), null, List.of())));
        assertEquals("SUCCEEDED", started.get("status"));
        String contextId = (String) result(started).get("context_id");
        var nativeScope = execution(service, opened).moduleScope();
        for (String operation : List.of("invoke", "close")) {
            var binding = new ScriptBinding("npc", operation, contextId, Map.of(), null, List.of());
            String source = "invoke".equals(operation) ? "count++; count" : "";
            var request = scriptRequest(sessionId(opened), generation(opened), source, Map.of(), 5000L, binding);
            String target = "native-early-existing-" + operation;
            var cancelled = service.scriptCancel("admin", target + "-cancel", scriptCancelRequest(
                    sessionId(opened), generation(opened), target, source, request.parameters(), request.timeoutMs(), binding));
            assertEquals("CANCELLED", cancelled.get("status"));
            assertEquals(contextId, result(cancelled).get("context_id"));
            assertEquals("OPEN", result(cancelled).get("context_state"));
            assertEquals(false, result(cancelled).get("context_closed"));
            assertEquals(false, result(cancelled).get("engine_started"));
            assertEquals(true, result(cancelled).get("invocation_settled"));
            assertEquals(true, result(cancelled).get("work_settled"));
            var recovered = service.scriptRun("admin", target, request);
            assertEquals(id(cancelled), id(recovered));
            assertEquals(result(cancelled), result(recovered));
            var read = service.scriptRun("admin", target + "-read", scriptRequest(sessionId(opened), generation(opened),
                    "count", Map.of(), 5000L, new ScriptBinding("npc", "invoke", contextId, Map.of(), null, List.of())));
            assertEquals("SUCCEEDED", read.get("status"));
            assertEquals(0, ((Number) result(read).get("result")).intValue(), "the cancelled invocation never ran");
            assertEquals(0, adapter.closes.get(), "cancelling an unstarted call does not close its target");
            assertEquals(1, nativeScope.snapshot().outstandingResources());
            assertEquals(0, nativeScope.snapshot().acceptedWork());
        }
        var close = new ScriptBinding("npc", "close", contextId, Map.of(), null, List.of());
        var closed = service.scriptRun("admin", "native-early-existing-final-close", scriptRequest(
                sessionId(opened), generation(opened), "", Map.of(), 5000L, close));
        assertEquals("SUCCEEDED", closed.get("status"));
        assertEquals("CLOSED", result(closed).get("context_state"));
        assertEquals(true, result(closed).get("context_closed"));
        assertEquals(1, adapter.closes.get());
        assertEquals(0, nativeScope.snapshot().outstandingResources());
        assertEquals(0, nativeScope.snapshot().acceptedWork());

        var staleCancelled = service.scriptCancel("admin", "native-early-gone-cancel", scriptCancelRequest(
                sessionId(opened), generation(opened), "native-early-gone", "", Map.of(), 5000L, close));
        assertEquals("UNKNOWN", result(staleCancelled).get("context_state"));
        assertEquals(false, result(staleCancelled).get("context_closed"), "absence is not release evidence");
    }

    @Test
    void nativeDescriptorValidationRejectsMalformedRequestsBeforeAllocatingHistoryOrWork() {
        ActorControlService service = service(4, 20, 64);
        var opened = open(service, "native-validation-open");
        executors.getFirst().runNext();
        var invalid = List.of(
                new ScriptBinding("unknown", "start", null, Map.of(), null, List.of()),
                new ScriptBinding("npc", "unknown", null, Map.of(), null, List.of()),
                new ScriptBinding(null, "start", null, Map.of(), null, List.of()),
                new ScriptBinding("npc", null, null, Map.of(), null, List.of()),
                new ScriptBinding("npc", "start", "unexpected-context", Map.of(), null, List.of()),
                new ScriptBinding("npc", "invoke", null, Map.of(), "reply", List.of()),
                new ScriptBinding("npc", "invoke", "bad/context", Map.of(), "reply", List.of()),
                new ScriptBinding("npc", "invoke", "context", Map.of(), "bad.entry", List.of()),
                new ScriptBinding("npc", "close", "context", Map.of("unexpected", true), null, List.of()),
                new ScriptBinding("npc", "close", "context", Map.of(), "reply", List.of()),
                new ScriptBinding("npc", "close", "context", Map.of(), null, List.of(1)));
        for (var environment : invalid) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                    () -> service.scriptRun("admin", "invalid-native", scriptRequest(sessionId(opened), generation(opened),
                            "", Map.of(), 1000L, environment))).status());
        }
        var invoked = new ScriptBinding("npc", "invoke", "context", Map.of(), "reply", List.of());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                () -> service.scriptRun("admin", "invalid-native", scriptRequest(sessionId(opened), generation(opened),
                        "1+1", Map.of(), 1000L, invoked))).status());
        var tooLarge = new ScriptBinding("npc", "start", null, Map.of("text", "x".repeat(8200)), null, List.of());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                () -> service.scriptRun("admin", "invalid-native", scriptRequest(sessionId(opened), generation(opened),
                        "", Map.of("text", "x".repeat(8200)), 1000L, tooLarge))).status());
        assertEquals(1, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).nativeScriptAdapter(any(), anyString());
    }

    @Test
    void nativeTransportRetainsOneRuntimeContextAndProjectsOpenInvocationAndExplicitCloseDifferently() throws Exception {
        ActorControlService service = service(4, 24, 64);
        var opened = open(service, "native-context-open"); executors.getFirst().runNext();
        TransportNativeAdapter adapter = prepareNativeAdapter();
        List<Map<String, Object>> descriptors = List.of(Map.of("kind", "npc", "globals", Map.of("cm", "test.NativeObject")));
        when(backend.nativeScriptEnvironments()).thenReturn(descriptors);
        var environment = new ScriptBinding("npc", "start", null, Map.of(), null, List.of());
        var request = scriptRequest(sessionId(opened), generation(opened), "parameters.base * 2", Map.of("base", 21), 5000L, environment);
        var started = service.scriptRun("admin", "native-start", request);
        assertEquals("SUCCEEDED", started.get("status"), () -> String.valueOf(started.get("result")));
        assertEquals(42, ((Number) result(started).get("result")).intValue());
        assertEquals("OPEN", result(started).get("context_state"));
        assertEquals(false, result(started).get("context_closed"), "a settled invocation may retain its native context");
        assertEquals(true, result(started).get("invocation_settled"));
        assertEquals(true, result(started).get("work_settled"));
        assertNull(operationWork(service, started));
        assertEquals(1, execution(service, opened).moduleScope().snapshot().outstandingResources());
        assertEquals(id(started), id(service.scriptRun("admin", "native-start", request)));
        assertEquals(1, adapter.starts.get());
        String contextId = (String) result(started).get("context_id");
        assertEquals(descriptors, service.publicCapabilities().get("native_environments"));
        var invoke = new ScriptBinding("npc", "invoke", contextId, Map.of(), "next", List.of(7));
        var reply = service.scriptRun("admin", "native-reply", scriptRequest(sessionId(opened), generation(opened),
                "", Map.of(), 5000L, invoke));
        assertEquals("SUCCEEDED", reply.get("status"));
        assertEquals(1, ((Number) ((Map<?, ?>) result(reply).get("result")).get("count")).intValue());
        assertEquals(contextId, result(reply).get("context_id"));
        prepareChat(service);
        assertEquals("native_script_context_active", assertThrows(ObservationException.class,
                () -> service.act("admin", "chat-busy", chatRequest(opened, "context remains active"))).reason());
        var closeEnvironment = new ScriptBinding("npc", "close", contextId, Map.of(), null, List.of());
        var closed = service.scriptRun("admin", "native-close", scriptRequest(sessionId(opened), generation(opened),
                "", Map.of(), 5000L, closeEnvironment));
        assertEquals("SUCCEEDED", closed.get("status"));
        assertEquals("CLOSED", result(closed).get("context_state"));
        assertEquals(true, result(closed).get("context_closed"));
        assertEquals(1, adapter.closes.get());
        assertEquals(0, execution(service, opened).moduleScope().snapshot().outstandingResources());
        var restarted = service.scriptRun("admin", "native-after-close",
                scriptRequest(sessionId(opened), generation(opened), "42", Map.of(), 5000L, environment));
        assertEquals("SUCCEEDED", restarted.get("status"));
        service.scriptRun("admin", "native-after-close-final", scriptRequest(sessionId(opened), generation(opened), "", Map.of(), 5000L,
                new ScriptBinding("npc", "close", (String) result(restarted).get("context_id"), Map.of(), null, List.of())));
    }

    @Test
    void nativeTransportCancellationStopsAnEnteredLoopAndClearsItsOwnedContextBeforeReturning() throws Exception {
        ActorControlService service = service(4, 24, 64);
        var opened = open(service, "native-loop-open"); executors.getFirst().runNext();
        TransportNativeAdapter adapter = prepareNativeAdapter();
        var environment = new ScriptBinding("npc", "start", null, Map.of(), null, List.of());
        var request = scriptRequest(sessionId(opened), generation(opened), "entered(); while (true) {}", Map.of(), 5000L, environment);
        var running = CompletableFuture.supplyAsync(() -> service.scriptRun("admin", "native-loop", request));
        assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), () -> running.isDone() ? String.valueOf(running.join()) : "native guest did not enter");
        var nativeScope = execution(service, opened).moduleScope();
        assertTrue(nativeScope.snapshot().runningBodies() > 0);
        assertEquals(0, ((Map<?, ?>) runtimeView(service.get("admin", id(opened), 0, 100)).get("lane")).get("pending"));
        var cancelled = service.scriptCancel("admin", "native-loop-cancel", scriptCancelRequest(sessionId(opened), generation(opened),
                "native-loop", request.source(), request.parameters(), request.timeoutMs(), environment));
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals("CLOSED", result(cancelled).get("context_state"));
        assertEquals(true, result(cancelled).get("engine_exited"));
        assertEquals(true, result(cancelled).get("context_closed"));
        assertEquals(true, result(cancelled).get("invocation_settled"));
        assertEquals(id(cancelled), id(running.get(5, TimeUnit.SECONDS)));
        assertEquals(0, nativeScope.snapshot().acceptedWork());
        assertEquals(0, nativeScope.snapshot().runningBodies());
        assertEquals(0, nativeScope.snapshot().pendingCarriers());
        assertEquals(0, nativeScope.snapshot().outstandingResources());
    }

    @Test
    void nativeAdmissionWaitsForExistingActionsByRejectingBeforeCreatingAnotherWorkOrAdapter() {
        ActorControlService service = service(4, 24, 64);
        prepareChat(service);
        var opened = open(service, "native-action-race-open"); executors.getFirst().runNext();
        var queued = service.act("admin", "native-prior-chat", chatRequest(opened, "already accepted"));
        int history = ((Number) service.publicCapabilities().get("operation_history_size")).intValue();
        var environment = new ScriptBinding("npc", "start", null, Map.of("npc_id", 9010000), null, List.of());
        assertEquals("actor_work_in_flight", assertThrows(ObservationException.class,
                () -> service.scriptRun("admin", "native-racing-start", scriptRequest(sessionId(opened), generation(opened),
                        "", Map.of(), 1000L, environment))).reason());
        assertEquals(history, service.publicCapabilities().get("operation_history_size"));
        verify(backend, never()).nativeScriptAdapter(any(), anyString());
        service.cancel("admin", "cancel-chat", id(queued)); executors.getFirst().drain();
    }

    @Test
    void actorCloseReleasesThePersistentNativeContextBeforeTheSameRootsNativeRole() throws Exception {
        ActorControlService service = service(4, 24, 64);
        var opened = open(service, "native-root-close-open"); executors.getFirst().runNext();
        TransportNativeAdapter adapter = prepareNativeAdapter();
        var environment = new ScriptBinding("npc", "start", null, Map.of(), null, List.of());
        var started = service.scriptRun("admin", "native-root-context", scriptRequest(sessionId(opened), generation(opened),
                "1", Map.of(), 5000L, environment));
        assertEquals("SUCCEEDED", started.get("status"), () -> String.valueOf(started.get("result")));
        var sameRoot = execution(service, opened);
        var nativeScope = sameRoot.moduleScope();
        assertEquals(1, nativeScope.snapshot().outstandingResources());
        when(backend.close(eq(handle), any(ScopeResources.DrainPermit.class))).thenAnswer(invocation -> {
            assertEquals(1, adapter.closes.get());
            assertEquals(0, nativeScope.snapshot().outstandingResources());
            assertEquals(0, nativeScope.snapshot().acceptedWork());
            assertEquals(0, nativeScope.snapshot().runningBodies());
            assertEquals(0, nativeScope.snapshot().pendingCarriers());
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", true);
        });
        var closing = service.close("admin", "native-root-close", closeRequest(opened));
        // Drive the original close until native script cleanup and the role carrier have both completed.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Map<String, Object> closed;
        do {
            executors.getFirst().drain();
            closed = service.get("admin", id(closing), 0, 100);
            if (!"QUEUED".equals(closed.get("status")) && !"RUNNING".equals(closed.get("status"))) break;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        assertEquals("SUCCEEDED", closed.get("status"));
        assertEquals("CLOSED", session(closed).get("state"));
        assertEquals("CLOSED", runtimeView(closed).get("state"));
    }

    @Test
    void theNativeFactoryAndDeclarationsResolveTheFiveRealAdaptersForTheExactOrdinaryRole() {
        org.gms.client.Character exactCharacter = handle.character();
        when(client.getPlayer()).thenReturn(exactCharacter);
        when(backend.nativeScriptAdapter(any(), anyString())).thenCallRealMethod();
        when(backend.nativeScriptEnvironments()).thenCallRealMethod();
        assertInstanceOf(org.gms.soloscripting.NativeDialogueScripts.class, backend.nativeScriptAdapter(handle, "npc"));
        assertInstanceOf(org.gms.soloscripting.NativeDialogueScripts.class, backend.nativeScriptAdapter(handle, "quest"));
        assertInstanceOf(org.gms.soloscripting.NativePortalScripts.class, backend.nativeScriptAdapter(handle, "portal"));
        assertInstanceOf(org.gms.soloscripting.NativeReactorScripts.class, backend.nativeScriptAdapter(handle, "reactor"));
        assertInstanceOf(org.gms.soloscripting.NativeEventScripts.class, backend.nativeScriptAdapter(handle, "event"));
        assertEquals(List.of("npc", "quest", "portal", "reactor", "event"), backend.nativeScriptEnvironments().stream()
                .map(environment -> environment.get("kind")).toList());
        for (Map<String, Object> environment : backend.nativeScriptEnvironments()) {
            assertNotNull(environment.get("globals"));
        }
        when(backend.isCurrent(handle)).thenReturn(false);
        assertThrows(ObservationException.class, () -> backend.nativeScriptAdapter(handle, "npc"));
    }

    private TransportNativeAdapter prepareNativeAdapter() {
        TransportNativeAdapter adapter = new TransportNativeAdapter();
        when(backend.nativeScriptAdapter(eq(handle), eq("npc"))).thenReturn(adapter);
        return adapter;
    }

    @Test
    void admittedNativeParametersKeepTheirValueThroughBindingWithinThePublicJsonBudget() throws Exception {
        ActorControlService service = service(4, 24, 64);
        var opened = open(service, "native-budget-open"); executors.getFirst().runNext();
        TransportNativeAdapter adapter = prepareNativeAdapter();
        var start = new ScriptBinding("npc", "start", null, Map.of("npc_id", 9900001), null, List.of());
        List<String> payloads = List.of("x".repeat(5000), "\\".repeat(5000), "\"".repeat(5000),
                "雪".repeat(5000), "😀".repeat(2000));
        for (int index = 0; index < payloads.size(); index++) {
            String payload = payloads.get(index);
            var accepted = service.scriptRun("admin", "native-budget-" + index,
                    scriptRequest(sessionId(opened), generation(opened), "parameters.payload",
                            Map.of("payload", payload), 5000L, start));
            assertEquals("SUCCEEDED", accepted.get("status"), () -> String.valueOf(accepted.get("result")));
            assertEquals(payload, result(accepted).get("result"));
            String contextId = (String) result(accepted).get("context_id");
            var closed = service.scriptRun("admin", "native-budget-close-" + index,
                    scriptRequest(sessionId(opened), generation(opened), "", Map.of(), 5000L,
                            new ScriptBinding("npc", "close", contextId, Map.of(), null, List.of())));
            assertEquals(true, result(closed).get("context_closed"));
        }
        Object history = service.publicCapabilities().get("operation_history_size");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                () -> service.scriptRun("admin", "native-budget-overflow",
                        scriptRequest(sessionId(opened), generation(opened), "parameters.payload",
                                Map.of("payload", "x".repeat(16384)), 5000L, start))).status());
        assertEquals(history, service.publicCapabilities().get("operation_history_size"));
        assertEquals(payloads.size(), adapter.starts.get());
        assertEquals(payloads.size(), adapter.closes.get());
    }

    @Test
    void nativeAdmissionDetachesNestedInputsBeforeAnAdapterCanObserveCallerMutation() throws Exception {
        ActorControlService service = service(4, 24, 64);
        var opened = open(service, "native-snapshot-open"); executors.getFirst().runNext();
        TransportNativeAdapter adapter = new TransportNativeAdapter();
        Map<String, Object> nestedParameter = new java.util.LinkedHashMap<>(Map.of("value", 21));
        Map<String, Object> nestedOption = new java.util.LinkedHashMap<>(Map.of("value", 7));
        List<Object> nestedArgs = new ArrayList<>(List.of(new java.util.LinkedHashMap<>(Map.of("value", 3))));
        var environment = new ScriptBinding("npc", "start", null, Map.of("payload", nestedOption), null, nestedArgs);
        when(backend.nativeScriptAdapter(eq(handle), eq("npc"))).thenAnswer(invocation -> {
            nestedParameter.put("value", 99); nestedOption.put("value", 88);
            ((Map<String, Object>) nestedArgs.getFirst()).put("value", 77);
            return adapter;
        });
        var result = service.scriptRun("admin", "native-snapshot", scriptRequest(sessionId(opened), generation(opened),
                "parameters.payload.value", Map.of("payload", nestedParameter), 5000L, environment));
        assertEquals("SUCCEEDED", result.get("status"), () -> String.valueOf(result.get("result")));
        assertEquals(21, ((Number) result(result).get("result")).intValue());
        assertEquals(7, ((Number) ((Map<?, ?>) adapter.accepted.options().get("payload")).get("value")).intValue());
        assertEquals(3, ((Number) ((Map<?, ?>) adapter.accepted.args().getFirst()).get("value")).intValue());
        String contextId = (String) result(result).get("context_id");
        service.scriptRun("admin", "native-snapshot-close", scriptRequest(sessionId(opened), generation(opened), "", Map.of(), 5000L,
                new ScriptBinding("npc", "close", contextId, Map.of(), null, List.of())));
    }

    private record ScriptBinding(String kind, String operation, String contextId, Map<String, Object> options,
                                 String entry, List<Object> args) { }

    private static ActorScriptRequest scriptRequest(String sessionId, long generation, String source,
                                                     Map<String, Object> parameters, long timeoutMs, ScriptBinding binding) {
        return new ActorScriptRequest(sessionId, generation, binding.kind(), binding.operation(), binding.contextId(),
                binding.options(), binding.entry(), binding.args(), source, parameters, timeoutMs);
    }

    private static ActorScriptCancelRequest scriptCancelRequest(String sessionId, long generation, String targetRequestId,
                                                                 String source, Map<String, Object> parameters, long timeoutMs,
                                                                 ScriptBinding binding) {
        return new ActorScriptCancelRequest(sessionId, generation, targetRequestId, binding.kind(), binding.operation(),
                binding.contextId(), binding.options(), binding.entry(), binding.args(), source, parameters, timeoutMs);
    }

    /** Native business is replaced only here; the production runtime, engine, work and resource ledger are real. */
    private static final class TransportNativeAdapter implements NativeScriptAdapter {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile NativeScriptRequest accepted;
        @Override public Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception {
            accepted = request;
            starts.incrementAndGet();
            GraalJSScriptEngine engine = GraalJSScriptEngine.create(null, Context.newBuilder("js")
                    .allowHostAccess(HostAccess.NONE));
            session.captureEngine(engine, "transport-test.js");
            engine.put("entered", (ProxyExecutable) values -> {
                entered.countDown(); return null;
            });
            engine.eval("var count = 0; function next(value) { return {count: ++count, value: value}; }");
            return Map.of("started", true);
        }
        @Override public Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception {
            return session.invocable().invokeFunction(request.entry(), request.args().toArray());
        }
        @Override public void close(NativeScriptSession session) { closes.incrementAndGet(); }
        @Override public Map<String, Object> describe(NativeScriptSession session) { return Map.of("kind", "npc"); }
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

    private static final class ManualExecutor extends AbstractExecutorService
            implements CharacterCommandLane.SerialBoundedExecutor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;
        private boolean running;

        @Override public int commandQueueCapacity() { return 8; }
        @Override public synchronized void shutdown() { shutdown = true; notifyAll(); }
        @Override public synchronized List<Runnable> shutdownNow() {
            // Accepted commands must still depart through the lane's settlement wrapper.
            shutdown();
            return List.of();
        }
        @Override public synchronized boolean isShutdown() { return shutdown; }
        @Override public synchronized boolean isTerminated() { return shutdown && queue.isEmpty() && !running; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long budget = unit.toNanos(timeout);
            long began = System.nanoTime();
            synchronized (this) {
                while (!isTerminated()) {
                    long remaining = budget - (System.nanoTime() - began);
                    if (remaining <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
                return true;
            }
        }
        @Override public synchronized void execute(Runnable command) {
            if (shutdown || queue.size() >= commandQueueCapacity()) throw new RejectedExecutionException();
            queue.addLast(command);
        }
        void runNext() {
            Runnable command;
            synchronized (this) {
                if (running) throw new IllegalStateException("test executor already running");
                command = queue.pollFirst();
                if (command == null) return;
                running = true;
            }
            try {
                command.run();
            } finally {
                synchronized (this) { running = false; notifyAll(); }
            }
        }
        void drain() {
            for (int attempts = 0; attempts < 20; attempts++) {
                synchronized (this) { if (queue.isEmpty()) return; }
                runNext();
            }
            throw new AssertionError("test executor did not drain");
        }
    }
}
