package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.AcceptanceObservationRuntime;
import org.gms.soloacceptance.AcceptanceObservationService;
import org.gms.soloacceptance.ObservationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Owns control leases and operations; native registration and persistence stay in the backend. */
@Service
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = {"enabled", "actors-enabled"},
        havingValue = "true", matchIfMissing = false)
public final class ActorControlService {
    private static final int DEFAULT_MAX_SESSIONS = 4;
    private static final int DEFAULT_MAX_HISTORY = 256;
    private static final int DEFAULT_MAX_EVENTS = 64;
    private final Object monitor = new Object();
    private final NativeActorBackend backend;
    private final int maxSessions;
    private final int maxHistory;
    private final int maxEvents;
    private final Function<String, ExecutorService> executorFactory;
    private final Duration stoppingWait;
    private final AtomicLong nextGeneration = new AtomicLong();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, Session> profiles = new LinkedHashMap<>();
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private final Map<RequestKey, RequestRecord> requests = new LinkedHashMap<>();
    private boolean accepting;
    private String worldGeneration;
    private AcceptanceObservationService observations;

    @Autowired
    public ActorControlService(NativeActorBackend backend) {
        this(backend, DEFAULT_MAX_SESSIONS, DEFAULT_MAX_HISTORY, DEFAULT_MAX_EVENTS,
                ActorControlService::newExecutor, Duration.ofSeconds(5));
    }

    ActorControlService(NativeActorBackend backend, int maxSessions, int maxHistory, int maxEvents,
                        Function<String, ExecutorService> executorFactory, Duration stoppingWait) {
        if (maxSessions < 1 || maxHistory < 1 || maxEvents < 1 || stoppingWait.isNegative()) {
            throw new IllegalArgumentException("invalid actor control limits");
        }
        this.backend = backend;
        this.maxSessions = maxSessions;
        this.maxHistory = maxHistory;
        this.maxEvents = maxEvents;
        this.executorFactory = executorFactory;
        this.stoppingWait = stoppingWait;
    }

    @Autowired(required = false)
    public void setObservations(AcceptanceObservationService observations) {
        this.observations = observations;
    }

    public void onWorldStarted() {
        synchronized (monitor) {
            AcceptanceObservationRuntime.State runtime = AcceptanceObservationRuntime.snapshot();
            worldGeneration = runtime.generation();
            accepting = runtime.active();
            monitor.notifyAll();
        }
    }

    /** Bounded draining: unfinished native cleanup keeps its profile quarantined across world starts. */
    public void onWorldStopping() {
        synchronized (monitor) {
            accepting = false;
            worldGeneration = null;
            for (Session session : new ArrayList<>(profiles.values())) {
                Operation opening = operations.get(session.openingOperation);
                if (opening != null && !terminal(opening.status)) requestCancellation(opening);
                if (session.handle != null && (opening == null || terminal(opening.status))
                        && !session.cleanupScheduled && !"CLOSED".equals(session.state)) {
                    session.cleanupScheduled = true;
                    session.state = "CLOSING";
                    submit(session, () -> runClose(session, null), null);
                }
            }
            long deadline = System.nanoTime() + stoppingWait.toNanos();
            while (profiles.values().stream().anyMatch(session -> session.cleanupScheduled
                    || "OPENING".equals(session.state))) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (Session session : profiles.values()) {
                if (!"CLOSED".equals(session.state)) session.state = "QUARANTINED";
            }
        }
    }

    public Map<String, Object> open(String owner, String requestId, ActorOpenRequest request) {
        validateOwner(owner);
        validateRequestId(requestId);
        if (request == null || request.profileId() == null
                || !request.profileId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_profile_id");
        }
        if (request.world() != null && request.world() < 0
                || request.channel() != null && request.channel() < 1) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_actor_location");
        }
        Operation prior;
        synchronized (monitor) {
            RequestKey key = new RequestKey("OPEN", owner, request.profileId(), requestId);
            prior = duplicate(key, request);
            if (prior == null) {
                requireAccepting();
                backend.validateProfile(request.profileId(), request.world(), request.channel());
                if (profiles.containsKey(request.profileId())) throw error(HttpStatus.CONFLICT, "profile_in_use");
                if (profiles.size() >= maxSessions) throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_capacity_exceeded");
                requireHistoryCapacity(true);
                // An accepted open reserves one first-close attempt, including its request record.
                if (operations.size() + reservedFirstCloses() + 2 > maxHistory
                        || requests.size() + reservedFirstCloses() + 2 > maxHistory) {
                    throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_history_capacity_exceeded");
                }
                Session session = new Session("session-" + UUID.randomUUID(), nextGeneration.incrementAndGet(),
                        owner, request.profileId(), worldGeneration, executorFactory.apply(request.profileId()));
                Operation operation = operation("OPEN", session);
                session.openingOperation = operation.id;
                sessions.put(session.id, session);
                profiles.put(session.profile, session);
                requests.put(key, new RequestRecord(request, operation.id));
                submit(session, () -> runOpen(session, operation, request), operation);
                return response(operation, 0, 100);
            }
        }
        return refreshedResponse(prior, 0, 100);
    }

    public Map<String, Object> close(String owner, String requestId, ActorCloseRequest request) {
        validateOwner(owner);
        validateRequestId(requestId);
        if (request == null || request.sessionId() == null || request.expectedGeneration() == null
                || request.expectedGeneration() < 1) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_actor_session");
        }
        Operation prior;
        synchronized (monitor) {
            RequestKey key = new RequestKey("CLOSE", owner, request.sessionId(), requestId);
            prior = duplicate(key, request);
            if (prior == null) {
                requireAccepting();
                Session session = ownedSession(owner, request.sessionId(), request.expectedGeneration());
                if (!sameWorld(session)) throw error(HttpStatus.GONE, "stale_actor_session");
                if (!("OPEN".equals(session.state) || "LOST".equals(session.state)
                        || "QUARANTINED".equals(session.state)) || session.handle == null || session.cleanupScheduled) {
                    throw error(HttpStatus.CONFLICT, "actor_not_closeable");
                }
                requireCloseHistoryCapacity(session);
                session.firstCloseReserved = false;
                Operation operation = operation("CLOSE", session);
                session.state = "CLOSING";
                session.cleanupScheduled = true;
                requests.put(key, new RequestRecord(request, operation.id));
                submit(session, () -> runClose(session, operation), operation);
                return response(operation, 0, 100);
            }
        }
        return refreshedResponse(prior, 0, 100);
    }

    public Map<String, Object> get(String owner, String operationId, long sinceCursor, int limit) {
        validateOwner(owner);
        validateWindow(sinceCursor, limit);
        Operation operation;
        synchronized (monitor) {
            operation = ownedOperation(owner, operationId);
            if (sinceCursor > operation.sequence) throw error(HttpStatus.BAD_REQUEST, "operation_cursor_ahead");
        }
        return refreshedResponse(operation, sinceCursor, limit);
    }

    public Map<String, Object> cancel(String owner, String requestId, String operationId) {
        validateOwner(owner);
        validateRequestId(requestId);
        Operation operation;
        synchronized (monitor) {
            operation = ownedOperation(owner, operationId);
            // Cancellation has no input other than its immutable target. Repeating it is
            // naturally idempotent and must not consume the history reserved for cleanup.
            if (!terminal(operation.status) && !operation.cancelRequested) requestCancellation(operation);
        }
        return refreshedResponse(operation, 0, 100);
    }

    /** Read-only lookup for observation; game actions must remain on the session's serial executor. */
    public Optional<NativeActorHandle> currentActor(String owner, String sessionId, long expectedGeneration) {
        validateOwner(owner);
        Session session;
        synchronized (monitor) {
            requireAccepting();
            session = ownedSession(owner, sessionId, expectedGeneration);
            if (!sameWorld(session)) throw error(HttpStatus.GONE, "stale_actor_session");
        }
        refreshActor(session);
        synchronized (monitor) {
            requireAccepting();
            if (!sameWorld(session)) throw error(HttpStatus.GONE, "stale_actor_session");
            return "OPEN".equals(session.state) ? Optional.ofNullable(session.handle) : Optional.empty();
        }
    }

    public Map<String, Object> publicCapabilities() {
        synchronized (monitor) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled_tools", List.of("actor_open", "actor_close", "operation_get", "operation_cancel"));
            result.put("actions", List.of());
            result.put("maximum_active_sessions", maxSessions);
            result.put("active_sessions", profiles.size());
            result.put("maximum_operation_history", maxHistory);
            result.put("operation_history_size", operations.size());
            result.put("maximum_idempotency_records", maxHistory);
            result.put("idempotency_records", requests.size());
            result.put("history_policy", "process_lifetime_reject_at_capacity");
            result.put("idempotency_scope", "process_lifetime");
            result.put("reserved_first_closes", reservedFirstCloses());
            result.put("close_retry_history_policy", "unreserved_capacity_only_then_quarantine_until_lifecycle_cleanup");
            result.put("actor_accepting_requests", live());
            result.put("operation_event_kind", "lifecycle");
            result.put("close_cancellation", "native_cleanup_continues_after_acceptance");
            result.put("quarantined_profiles", profiles.values().stream()
                    .filter(session -> "QUARANTINED".equals(session.state)).map(session -> session.profile).toList());
            return result;
        }
    }

    private void runOpen(Session session, Operation operation, ActorOpenRequest request) {
        NativeActorHandle handle = null;
        try {
            synchronized (monitor) {
                if (cancelled(session, operation)) {
                    cancelledBeforeOpen(session, operation);
                    return;
                }
                running(operation, "native_actor_open_started");
            }
            handle = backend.open(session.profile, request.world(), request.channel(), () -> cancelled(session, operation));
            synchronized (monitor) { session.handle = handle; }
            Map<String, Object> snapshot = actorSnapshot(handle);
            if (!backend.isCurrent(handle) || !Boolean.TRUE.equals(snapshot.get("logged_in_world"))) {
                throw error(HttpStatus.CONFLICT, "native_actor_not_online");
            }
            synchronized (monitor) {
                if (!cancelled(session, operation)) {
                    session.lastActor = copy(snapshot);
                    session.state = "OPEN";
                    operation.result = Map.of("observed_outcome", "native_actor_online", "actor", copy(snapshot));
                    finish(operation, "SUCCEEDED", "native_actor_online");
                    return;
                }
                session.state = "CLOSING";
                session.cleanupScheduled = true;
            }
            Map<String, Object> cleanup = backend.close(handle);
            verifyCleanup(cleanup);
            synchronized (monitor) {
                session.cleanupScheduled = false;
                release(session, "CANCELLED");
                operation.result = Map.of("observed_outcome", "native_actor_cancelled_after_cleanup", "cleanup", copy(cleanup));
                finish(operation, "CANCELLED", "native_actor_cancelled_after_cleanup");
            }
        } catch (Throwable failure) {
            handleOpenFailure(session, operation, handle, failure);
        }
    }

    private void handleOpenFailure(Session session, Operation operation, NativeActorHandle handle, Throwable failure) {
        boolean rolledBack = handle == null && (failure instanceof ObservationException || failure instanceof CancellationException)
                && !safeReason(failure, "native_actor_open_failed").contains("cleanup_unverified");
        Map<String, Object> cleanup = null;
        if (handle != null) {
            try {
                cleanup = backend.close(handle);
                verifyCleanup(cleanup);
                rolledBack = true;
            } catch (Throwable ignored) {
                rolledBack = false;
            }
        }
        synchronized (monitor) {
            session.cleanupScheduled = false;
            boolean cancelled = operation.cancelRequested || !sameWorld(session);
            if (rolledBack) release(session, cancelled ? "CANCELLED" : "FAILED");
            else session.state = "QUARANTINED";
            if (cleanup != null) operation.result = Map.of("cleanup", copy(cleanup));
            operation.error = rolledBack && cancelled ? null
                    : Map.of("reason", rolledBack ? safeReason(failure, "native_actor_open_failed") : "native_actor_cleanup_unverified");
            finish(operation, rolledBack ? cancelled ? "CANCELLED" : "FAILED" : "INDETERMINATE",
                    rolledBack ? "native_actor_open_failed" : "native_actor_cleanup_unverified");
        }
    }

    private void runClose(Session session, Operation operation) {
        synchronized (monitor) {
            if (operation != null) running(operation, "native_actor_close_started");
            session.state = "CLOSING";
        }
        try {
            Map<String, Object> cleanup = backend.close(session.handle);
            verifyCleanup(cleanup);
            synchronized (monitor) {
                session.cleanupScheduled = false;
                release(session, "CLOSED");
                if (operation != null) {
                    Map<String, Object> result = copy(cleanup);
                    result.put("observed_outcome", "native_actor_closed");
                    result.put("cancel_too_late", operation.cancelRequested);
                    operation.result = result;
                    finish(operation, "SUCCEEDED", "native_actor_closed");
                }
                monitor.notifyAll();
            }
        } catch (Throwable failure) {
            synchronized (monitor) {
                session.cleanupScheduled = false;
                session.state = "QUARANTINED";
                session.lastActor = null;
                if (operation != null) {
                    operation.error = Map.of("reason", safeReason(failure, "native_actor_close_failed"));
                    finish(operation, "INDETERMINATE", "native_actor_cleanup_unverified");
                }
                monitor.notifyAll();
            }
        }
    }

    private static void verifyCleanup(Map<String, Object> result) {
        if (result == null || !Boolean.TRUE.equals(result.get("native_cleanup_verified"))
                || !Boolean.TRUE.equals(result.get("persisted_state_verified"))) {
            throw error(HttpStatus.CONFLICT, "native_actor_cleanup_unverified");
        }
    }

    private void refreshActor(Session session) {
        NativeActorHandle handle;
        synchronized (monitor) {
            if (!"OPEN".equals(session.state) || session.handle == null) return;
            if (!sameWorld(session)) {
                session.state = "LOST";
                session.lastActor = null;
                return;
            }
            handle = session.handle;
        }
        Map<String, Object> snapshot = null;
        try {
            if (backend.isCurrent(handle)) snapshot = actorSnapshot(handle);
        } catch (RuntimeException ignored) {
            // An unavailable diagnostic projection is not permission to publish a stale actor.
        }
        synchronized (monitor) {
            if (session.handle != handle || !"OPEN".equals(session.state)) return;
            if (!sameWorld(session) || snapshot == null || !Boolean.TRUE.equals(snapshot.get("logged_in_world"))) {
                session.state = "LOST";
                session.lastActor = null;
            } else session.lastActor = copy(snapshot);
        }
    }

    private Map<String, Object> refreshedResponse(Operation operation, long cursor, int limit) {
        refreshActor(operation.session);
        synchronized (monitor) {
            return response(operation, cursor, limit);
        }
    }

    private Map<String, Object> actorSnapshot(NativeActorHandle handle) {
        Map<String, Object> result = copy(backend.snapshot(handle));
        if (observations != null) result.put("scene", observations.contextForActor(handle.character()));
        return result;
    }

    private void release(Session session, String state) {
        session.state = state;
        session.lastActor = null;
        session.handle = null;
        profiles.remove(session.profile, session);
        session.executor.shutdown();
        monitor.notifyAll();
    }

    private void cancelledBeforeOpen(Session session, Operation operation) {
        release(session, "CANCELLED");
        operation.result = Map.of("observed_outcome", "open_cancelled_before_dispatch");
        finish(operation, "CANCELLED", "open_cancelled_before_dispatch");
    }

    private Operation operation(String kind, Session session) {
        Operation operation = new Operation("op-" + UUID.randomUUID(), kind, session);
        operations.put(operation.id, operation);
        event(operation, "queued");
        return operation;
    }

    private void submit(Session session, Runnable task, Operation operation) {
        try {
            session.executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            session.cleanupScheduled = false;
            if (operation != null) {
                operation.error = Map.of("reason", "actor_queue_full");
                finish(operation, "FAILED", "actor_queue_full");
                if ("OPEN".equals(operation.kind) && session.handle == null) release(session, "FAILED");
                else session.state = sameWorld(session) ? "OPEN" : "QUARANTINED";
            } else {
                session.state = "QUARANTINED";
                monitor.notifyAll();
                return;
            }
            throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_queue_full");
        }
    }

    private void requestCancellation(Operation operation) {
        operation.cancelRequested = true;
        operation.status = "CANCEL_REQUESTED";
        operation.step = "cancellation_requested";
        event(operation, "cancellation_requested");
    }

    private void running(Operation operation, String step) {
        operation.status = operation.cancelRequested ? "CANCEL_REQUESTED" : "RUNNING";
        operation.step = step;
        event(operation, step);
    }

    private void finish(Operation operation, String status, String step) {
        operation.status = status;
        operation.step = step;
        event(operation, step);
        monitor.notifyAll();
    }

    private void event(Operation operation, String kind) {
        operation.sequence++;
        operation.events.addLast(Map.of("seq", operation.sequence, "at", Instant.now().toString(), "kind", kind));
        while (operation.events.size() > maxEvents) operation.events.removeFirst();
    }

    private Map<String, Object> response(Operation operation, long sinceCursor, int limit) {
        Session session = operation.session;
        Map<String, Object> sessionData = new LinkedHashMap<>();
        sessionData.put("session_id", session.id);
        sessionData.put("session_generation", session.generation);
        sessionData.put("profile_id", session.profile);
        sessionData.put("state", session.state);
        sessionData.put("runtime_generation", session.runtimeGeneration);
        sessionData.put("actor_current", "OPEN".equals(session.state));
        sessionData.put("actor", session.lastActor);
        long oldest = operation.events.isEmpty() ? operation.sequence + 1 : (long) operation.events.getFirst().get("seq");
        List<Map<String, Object>> items = operation.events.stream()
                .filter(item -> (long) item.get("seq") > sinceCursor).limit(limit).toList();
        long cursor = items.isEmpty() ? Math.min(sinceCursor, operation.sequence) : (long) items.getLast().get("seq");
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("items", items);
        events.put("next_cursor", cursor);
        events.put("oldest_cursor", oldest);
        events.put("latest_cursor", operation.sequence);
        events.put("cursor_expired", sinceCursor < oldest - 1);
        events.put("truncated", cursor < operation.sequence);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema_version", 1);
        response.put("operation_id", operation.id);
        response.put("kind", operation.kind);
        response.put("status", operation.status);
        response.put("step", operation.step);
        response.put("session_id", session.id);
        response.put("session_generation", session.generation);
        response.put("profile_id", session.profile);
        response.put("runtime_generation", session.runtimeGeneration);
        response.put("cancel_requested", operation.cancelRequested);
        response.put("result", operation.result);
        response.put("error", operation.error);
        response.put("session", sessionData);
        response.put("events", events);
        return response;
    }

    private Operation duplicate(RequestKey key, Object fingerprint) {
        RequestRecord prior = requests.get(key);
        if (prior == null) return null;
        if (!prior.fingerprint.equals(fingerprint)) throw error(HttpStatus.CONFLICT, "request_id_conflict");
        return operations.get(prior.operationId);
    }

    private Session ownedSession(String owner, String sessionId, long generation) {
        Session session = sessions.get(sessionId);
        if (session == null) throw error(HttpStatus.NOT_FOUND, "actor_session_not_found");
        if (!session.owner.equals(owner)) throw error(HttpStatus.FORBIDDEN, "actor_owner_mismatch");
        if (generation != session.generation) throw error(HttpStatus.GONE, "stale_actor_session");
        return session;
    }

    private Operation ownedOperation(String owner, String operationId) {
        Operation operation = operations.get(operationId);
        if (operation == null) throw error(HttpStatus.NOT_FOUND, "operation_not_found");
        if (!operation.session.owner.equals(owner)) throw error(HttpStatus.FORBIDDEN, "actor_owner_mismatch");
        return operation;
    }

    private void requireHistoryCapacity(boolean newOperation) {
        if (requests.size() >= maxHistory || newOperation && operations.size() >= maxHistory) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_history_capacity_exceeded");
        }
    }

    private int reservedFirstCloses() {
        return (int) profiles.values().stream().filter(session -> session.firstCloseReserved).count();
    }

    private void requireCloseHistoryCapacity(Session session) {
        int otherReservations = reservedFirstCloses() - (session.firstCloseReserved ? 1 : 0);
        if (operations.size() + otherReservations + 1 > maxHistory
                || requests.size() + otherReservations + 1 > maxHistory) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, session.firstCloseReserved
                    ? "actor_history_capacity_exceeded" : "actor_close_retry_history_capacity_exceeded");
        }
    }

    private void requireAccepting() {
        if (!live()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_world_offline");
    }

    private boolean live() {
        AcceptanceObservationRuntime.State runtime = AcceptanceObservationRuntime.snapshot();
        return accepting && runtime.active() && runtime.generation().equals(worldGeneration);
    }

    private boolean sameWorld(Session session) {
        return live() && session.runtimeGeneration.equals(worldGeneration);
    }

    private boolean cancelled(Session session, Operation operation) {
        synchronized (monitor) { return operation.cancelRequested || !sameWorld(session); }
    }

    private static boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED", "INDETERMINATE").contains(status);
    }

    private static void validateOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) throw error(HttpStatus.UNAUTHORIZED, "authentication_required");
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_request_id");
        }
    }

    private static void validateWindow(long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > 500) throw error(HttpStatus.BAD_REQUEST, "invalid_operation_window");
    }

    private static String safeReason(Throwable failure, String fallback) {
        return failure instanceof ObservationException observation && observation.reason().matches("[a-z0-9_]{1,96}")
                ? observation.reason() : fallback;
    }

    private static Map<String, Object> copy(Map<String, Object> value) { return new LinkedHashMap<>(value); }
    private static ObservationException error(HttpStatus status, String reason) { return new ObservationException(status, reason); }

    private static ExecutorService newExecutor(String profile) {
        return new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                Thread.ofVirtual().name("acceptance-actor-" + profile + "-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    private record RequestKey(String kind, String owner, String target, String requestId) { }
    private record RequestRecord(Object fingerprint, String operationId) { }
    private static final class Session {
        final String id;
        final long generation;
        final String owner;
        final String profile;
        final String runtimeGeneration;
        final ExecutorService executor;
        String state = "OPENING";
        String openingOperation;
        NativeActorHandle handle;
        Map<String, Object> lastActor;
        boolean cleanupScheduled;
        boolean firstCloseReserved = true;
        Session(String id, long generation, String owner, String profile, String runtimeGeneration, ExecutorService executor) {
            this.id = id;
            this.generation = generation;
            this.owner = owner;
            this.profile = profile;
            this.runtimeGeneration = runtimeGeneration;
            this.executor = executor;
        }
    }
    private static final class Operation {
        final String id;
        final String kind;
        final Session session;
        final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();
        String status = "QUEUED";
        String step = "queued";
        boolean cancelRequested;
        long sequence;
        Map<String, Object> result = Map.of();
        Map<String, Object> error;
        Operation(String id, String kind, Session session) {
            this.id = id;
            this.kind = kind;
            this.session = session;
        }
    }
}
