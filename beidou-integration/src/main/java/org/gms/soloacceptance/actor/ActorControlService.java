package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.AcceptanceObservationRuntime;
import org.gms.soloacceptance.AcceptanceObservationService;
import org.gms.soloacceptance.ObservationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;

import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Owns control leases and operations; native registration and persistence stay in the backend. */
@Service
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = {"enabled", "actors-enabled"},
        havingValue = "true", matchIfMissing = false)
public final class ActorControlService {
    private static final int DEFAULT_MAX_SESSIONS = 4;
    private static final int DEFAULT_MAX_HISTORY = 256;
    private static final int DEFAULT_MAX_EVENTS = 64;
    private static final int MAX_PENDING_ACTIONS = 7;
    private static final int EXECUTOR_QUEUE_CAPACITY = 8;
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
        onWorldStopping(ExecutionScope.tryBeginWorldStop());
    }

    public void onWorldStopping(Optional<ExecutionScope.WorldStopTicket> stop) {
        List<Session> draining;
        synchronized (monitor) {
            accepting = false;
            worldGeneration = null;
            draining = new ArrayList<>(profiles.values());
            for (Session session : draining) {
                cancelUndispatchedActions(session);
                Operation opening = operations.get(session.openingOperation);
                if (opening != null && !terminal(opening.status)) requestCancellation(opening);
            }
        }
        for (Session session : draining) {
            synchronized (session.submissionMonitor) {
                session.execution.beginDrain(stop);
                NativeActorHandle exactHandle = session.execution.adoptedHandle();
                boolean cleanup;
                ExecutionScope.WorkHandle waitFor = null;
                synchronized (monitor) {
                    Operation opening = operations.get(session.openingOperation);
                    cleanup = exactHandle != null && (opening == null || terminal(opening.status))
                            && !cleanupInFlight(session) && !session.execution.resourceReleased();
                    if (cleanup) {
                        session.cleanupScheduled = true;
                        session.state = "CLOSING";
                    } else if (!session.execution.resourceReleased() && stop.isPresent()
                            && stop.get().sourceEpoch() == session.execution.scope().acceptedEpoch()
                            && session.stopRetryTicket != stop.get()) {
                        waitFor = session.cleanupWork != null ? session.cleanupWork : opening == null ? null : opening.work;
                        if (waitFor != null && !waitFor.isSettled()) session.stopRetryTicket = stop.get();
                        else waitFor = null;
                    }
                }
                if (cleanup) submitCleanup(session, null, exactHandle);
                if (waitFor != null) {
                    ExecutionScope.WorkHandle exactWork = waitFor;
                    waitFor.completion().whenComplete((fact, failure) -> retryStoppedCleanup(session, exactWork));
                }
            }
        }
        long deadline = System.nanoTime() + stoppingWait.toNanos();
        for (Session session : draining) {
            session.execution.awaitDrained(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        }
        synchronized (monitor) {
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
        Operation accepted = null;
        synchronized (monitor) {
            RequestKey key = new RequestKey("OPEN", owner, request.profileId(), requestId);
            prior = duplicate(key, request);
            if (prior == null) {
                requireAccepting();
                backend.validateProfile(request.profileId(), request.world(), request.channel());
                requireAccepting();
                if (profiles.containsKey(request.profileId())) throw error(HttpStatus.CONFLICT, "profile_in_use");
                if (profiles.size() >= maxSessions) throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_capacity_exceeded");
                requireHistoryCapacity(true);
                // An accepted open reserves one first-close attempt, including its request record.
                if (operations.size() + reservedFirstCloses() + 2 > maxHistory
                        || requests.size() + reservedFirstCloses() + 2 > maxHistory) {
                    throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_history_capacity_exceeded");
                }
                WorldEpoch.Token admittedEpoch = WorldEpoch.snapshot().token();
                ExecutorService createdExecutor = executorFactory.apply(request.profileId());
                Session session = null;
                Operation operation;
                try {
                    requireAccepting();
                    session = new Session("session-" + UUID.randomUUID(), nextGeneration.incrementAndGet(),
                            owner, request.profileId(), worldGeneration, admittedEpoch, backend, createdExecutor);
                    operation = operation("OPEN", session);
                } catch (RuntimeException failure) {
                    if (session != null) closeReleasedLane(session);
                    else createdExecutor.shutdown();
                    throw failure;
                }
                session.openingOperation = operation.id;
                sessions.put(session.id, session);
                profiles.put(session.profile, session);
                requests.put(key, new RequestRecord(request, operation.id));
                accepted = operation;
            }
        }
        if (accepted != null) {
            Session session = accepted.session;
            Operation operation = accepted;
            synchronized (session.submissionMonitor) {
                CharacterCommandLane.Submission submission = session.execution.submitOpen(operation.work,
                        () -> runOpen(session, operation, request), () -> workSettled(session, operation));
                requireSubmitted(submission, operation);
            }
            synchronized (monitor) { return response(operation, 0, 100); }
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
        Session session;
        RequestKey key = new RequestKey("CLOSE", owner, request.sessionId(), requestId);
        synchronized (monitor) {
            prior = duplicate(key, request);
            session = prior == null ? ownedSession(owner, request.sessionId(), request.expectedGeneration()) : prior.session;
        }
        if (prior != null) return refreshedResponse(prior, 0, 100);
        synchronized (session.submissionMonitor) {
            Operation accepted;
            NativeActorHandle exactHandle;
            synchronized (monitor) {
                prior = duplicate(key, request);
                if (prior == null) {
                    requireAccepting();
                    if (!sameWorld(session)) throw error(HttpStatus.GONE, "stale_actor_session");
                    if (!("OPEN".equals(session.state) || "LOST".equals(session.state)
                            || "QUARANTINED".equals(session.state)) || session.handle == null || cleanupInFlight(session)) {
                        throw error(HttpStatus.CONFLICT, "actor_not_closeable");
                    }
                    requireCloseHistoryCapacity(session);
                    session.firstCloseReserved = false;
                    accepted = operation("CLOSE", session);
                    session.state = "CLOSING";
                    session.cleanupScheduled = true;
                    cancelUndispatchedActions(session);
                    requests.put(key, new RequestRecord(request, accepted.id));
                    exactHandle = session.handle;
                } else {
                    accepted = null;
                    exactHandle = null;
                }
            }
            if (accepted != null) {
                session.execution.beginDrain(Optional.empty());
                submitCleanup(session, accepted, exactHandle);
                synchronized (monitor) { return response(accepted, 0, 100); }
            }
        }
        return refreshedResponse(prior, 0, 100);
    }

    public Map<String, Object> act(String owner, String requestId, ActorActRequest request) {
        validateOwner(owner);
        validateRequestId(requestId);
        if (request == null || request.sessionId() == null
                || !request.sessionId().matches("[A-Za-z0-9_.-]{1,128}") || request.expectedGeneration() == null) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_actor_session");
        }
        String text = request.parameters() == null ? null : request.parameters().text();
        Long dialogueVersion = request.parameters() == null ? null : request.parameters().dialogueVersion();
        String replyKind = request.parameters() == null ? null : request.parameters().kind();
        Integer selection = request.parameters() == null ? null : request.parameters().selection();
        ActorActRequest.ExpectedContext context = request.expectedContext();
        ActionFingerprint fingerprint = new ActionFingerprint(request.expectedGeneration(), request.action(),
                text == null ? null : textDigest(text), dialogueVersion, replyKind, selection, context);
        RequestKey key = new RequestKey("ACTION", owner, request.sessionId(), requestId);
        Operation prior;
        Session session;
        NativeActorHandle handle;
        synchronized (monitor) {
            prior = duplicate(key, fingerprint);
            if (prior == null) {
                if (request.expectedGeneration() < 1) throw error(HttpStatus.BAD_REQUEST, "invalid_actor_session");
                validateActionParameters(request.action(), request.parameters());
                if (context == null || context.sceneRef() == null || context.runtimeGeneration() == null
                        || !context.sceneRef().matches("[A-Za-z0-9_.-]{1,128}")
                        || !context.runtimeGeneration().matches("[A-Za-z0-9_.-]{1,128}")) {
                    throw error(HttpStatus.BAD_REQUEST, "invalid_expected_context");
                }
                requireAccepting();
                session = ownedSession(owner, request.sessionId(), request.expectedGeneration());
                requireActionSession(session, null);
                handle = session.handle;
            } else {
                session = null;
                handle = null;
            }
        }
        if (prior != null) return refreshedResponse(prior, 0, 100);

        try {
            validateNativeAction(handle, request.action(), text, dialogueVersion, replyKind, selection);
            validateActionContext(handle, context);
        } catch (RuntimeException failure) {
            synchronized (monitor) { prior = duplicate(key, fingerprint); }
            if (prior != null) return refreshedResponse(prior, 0, 100);
            throw failure;
        }
        synchronized (session.submissionMonitor) {
            Operation accepted;
            synchronized (monitor) {
                // Concurrent callers may have accepted this ID while native validation was running.
                prior = duplicate(key, fingerprint);
                if (prior == null) {
                    requireAccepting();
                    if (ownedSession(owner, request.sessionId(), request.expectedGeneration()) != session) {
                        throw error(HttpStatus.GONE, "stale_actor_session");
                    }
                    requireActionSession(session, handle);
                    if (operations.size() + reservedFirstCloses() + 1 > maxHistory
                            || requests.size() + reservedFirstCloses() + 1 > maxHistory) {
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_history_capacity_exceeded");
                    }
                    if (session.pendingActions >= MAX_PENDING_ACTIONS) {
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_action_capacity_exceeded");
                    }
                    accepted = operation("ACTION", session);
                    accepted.action = request.action();
                    accepted.actionSlotHeld = true;
                    session.pendingActions++;
                    requests.put(key, new RequestRecord(fingerprint, accepted.id));
                } else accepted = null;
            }
            if (accepted != null) {
                Session acceptedSession = session;
                NativeActorHandle acceptedHandle = handle;
                ExecutionScope.WorkHandle actualWork = accepted.work;
                actualWork.completion().whenComplete((fact, failure) -> actionCarrierCompleted(accepted, actualWork));
                if ("chat".equals(accepted.action)) {
                    NativeActionService.SubmissionReceipt receipt = submitChat(acceptedSession, accepted, acceptedHandle, text, context);
                    if (receipt != null && receipt.status() == NativeActionService.SubmissionStatus.REJECTED) {
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_queue_full");
                    }
                } else {
                    CharacterCommandLane.Submission submission = session.execution.submitAction(accepted.work,
                            () -> runAction(acceptedSession, accepted, acceptedHandle, text, dialogueVersion,
                                    replyKind, selection, context), () -> workSettled(acceptedSession, accepted));
                    requireSubmitted(submission, accepted);
                }
                synchronized (monitor) { return response(accepted, 0, 100); }
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

    /** Diagnostic reads never consume operation history or enter the session's write queue. */
    public Map<String, Object> observe(String owner, ActorObserveRequest request) {
        validateOwner(owner);
        if (request == null || request.sessionId() == null
                || !request.sessionId().matches("[A-Za-z0-9_.-]{1,128}")
                || request.expectedGeneration() == null || request.expectedGeneration() < 1) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_actor_session");
        }
        long cursor = request.sinceCursor() == null ? 0 : request.sinceCursor();
        int limit = request.limit() == null ? 100 : request.limit();
        if (cursor < 0 || limit < 1 || limit > 500) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_packet_window");
        }
        Session session;
        NativeActorHandle handle;
        String generation;
        synchronized (monitor) {
            requireAccepting();
            session = ownedSession(owner, request.sessionId(), request.expectedGeneration());
            if (!sameWorld(session)) throw error(HttpStatus.GONE, "stale_actor_session");
            if (!"OPEN".equals(session.state)) {
                throw error(List.of("OPENING", "CLOSING").contains(session.state)
                        ? HttpStatus.CONFLICT : HttpStatus.GONE,
                        List.of("OPENING", "CLOSING").contains(session.state)
                                ? "actor_not_observable" : "stale_actor_session");
            }
            handle = session.handle;
            if (handle == null) throw error(HttpStatus.GONE, "stale_actor_session");
            generation = worldGeneration;
        }

        Map<String, Object> actor = null;
        Map<String, Object> events = null;
        boolean current;
        try {
            current = backend.isCurrent(handle);
            if (current) {
                actor = actorSnapshot(handle);
                current = Boolean.TRUE.equals(actor.get("logged_in_world"));
                if (current) {
                    synchronized (monitor) {
                        requireObservationUnchanged(session, handle, generation);
                    }
                    events = copy(handle.client().packetEvents(cursor, limit));
                    current = backend.isCurrent(handle);
                }
            }
        } catch (RuntimeException failure) {
            synchronized (monitor) {
                requireObservationUnchanged(session, handle, generation);
            }
            throw failure;
        }
        synchronized (monitor) {
            requireObservationUnchanged(session, handle, generation);
            if (!current) {
                session.state = "LOST";
                session.lastActor = null;
                throw error(HttpStatus.GONE, "stale_actor_session");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema_version", 1);
            result.put("captured_at", Instant.now().toString());
            result.put("scope", "actor");
            result.put("runtime_generation", generation);
            result.put("information_level", "server_diagnostic");
            result.put("snapshot_consistency", "best_effort");
            result.put("session_id", session.id);
            result.put("session_generation", session.generation);
            result.put("profile_id", session.profile);
            result.put("actor", actor);
            result.put("events", events);
            return result;
        }
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
        String timingGeneration;
        synchronized (monitor) { timingGeneration = live() ? worldGeneration : null; }
        Map<String, Object> timing = null;
        ObservationException timingFailure = null;
        if (timingGeneration != null) {
            try { timing = backend.chatTimingCapabilities(); }
            catch (ObservationException failure) { timingFailure = failure; }
        }
        synchronized (monitor) {
            boolean sameRuntime = timingGeneration != null && live() && timingGeneration.equals(worldGeneration);
            if (sameRuntime && timingFailure != null) throw timingFailure;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled_tools", List.of("actor_open", "actor_close", "operation_get", "operation_cancel", "game_act"));
            result.put("actions", List.of("chat", "npc_center_open", "npc_cancel", "npc_reply"));
            result.put("npc_dialogue", AcceptanceClient.nativeDialogueCapabilities());
            result.put("maximum_pending_actions_per_session", MAX_PENDING_ACTIONS);
            result.put("actor_executor_queue_capacity", EXECUTOR_QUEUE_CAPACITY);
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
            result.put("actor_accepting_requests", sameRuntime);
            if (sameRuntime) {
                result.put("chat_minimum_interval_ms", timing.get("chat_minimum_interval_ms"));
                result.put("native_clock_update_interval_ms", timing.get("native_clock_update_interval_ms"));
            }
            result.put("operation_event_kind", "lifecycle");
            result.put("close_cancellation", "native_cleanup_continues_after_acceptance");
            result.put("packet_observation", AcceptanceClient.packetObservationCapabilities());
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
            NativeActorBackend.OpenOwnership ownership = session.execution.beginOpen(operation.work);
            handle = backend.open(session.profile, request.world(), request.channel(), () -> cancelled(session, operation), ownership);
            if (session.execution.adoptedHandle() != handle) throw error(HttpStatus.CONFLICT, "native_actor_cleanup_unverified");
            synchronized (monitor) { session.handle = handle; }
            Map<String, Object> snapshot = actorSnapshot(handle);
            if (!backend.isCurrent(handle) || !Boolean.TRUE.equals(snapshot.get("logged_in_world"))) {
                throw error(HttpStatus.CONFLICT, "native_actor_not_online");
            }
            if (!cancelled(session, operation)) {
                session.execution.bindActor(handle);
                AtomicBoolean published = new AtomicBoolean();
                session.execution.publishOpen(operation.work, () -> {
                    synchronized (monitor) {
                        if (!cancelled(session, operation)) {
                            session.lastActor = copy(snapshot);
                            session.state = "OPEN";
                            operation.result = Map.of("observed_outcome", "native_actor_online", "actor", copy(snapshot));
                            finish(operation, "SUCCEEDED", "native_actor_online");
                            published.set(true);
                        }
                    }
                });
                if (published.get()) return;
            }
            synchronized (monitor) {
                if (!"CLOSED".equals(session.state)) {
                    session.state = "CLOSING";
                    session.cleanupScheduled = true;
                }
            }
            Map<String, Object> cleanup = session.execution.rollbackInline();
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
        if (handle == null) handle = session.execution.adoptedHandle();
        boolean rolledBack = session.execution.resourceReleased();
        Map<String, Object> cleanup = null;
        if (handle != null && !rolledBack) {
            try {
                cleanup = session.execution.rollbackInline();
                rolledBack = true;
            } catch (Throwable ignored) {
                rolledBack = false;
            }
        }
        synchronized (monitor) {
            if (!rolledBack && handle != null) session.handle = handle;
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

    private NativeActionService.SubmissionReceipt submitChat(Session session, Operation operation, NativeActorHandle handle,
                                                            String text, ActorActRequest.ExpectedContext context) {
        ExecutionScope.WorkHandle actualWork = operation.work;
        try {
            NativeActionService.ActionHandle action = session.execution.prepareChat(actualWork, handle, text,
                    () -> validateChatEntry(session, operation, handle, context));
            synchronized (monitor) { operation.chatAction = action; }
            action.result().whenComplete((result, failure) -> chatCompleted(operation, action, result, failure));
            // Bind the captured native scene to the caller's opaque context before the one physical submission.
            validateActionContext(handle, context);
            return session.execution.submitChat(action);
        } catch (Throwable failure) {
            ExecutionScope.WorkSnapshot fact = actualWork.snapshot();
            synchronized (monitor) {
                if (!terminal(operation.status)) {
                    if (fact.carrierClaimed() && !fact.settled() && !fact.rejected()) {
                        operation.step = "native_chat_submission_confirmation_pending";
                        event(operation, operation.step);
                    } else if (actionCancelled(session, operation)) cancelledBeforeAction(operation);
                    else chatFailedBeforeDispatch(operation, safeReason(failure, "native_chat_validation_failed"));
                }
            }
            // No carrier exists to account for an unsubmitted preparation failure.
            if (!fact.carrierClaimed()) {
                synchronized (monitor) { operation.chatAction = null; }
                actualWork.reject();
            }
            return null;
        }
    }

    private void validateChatEntry(Session session, Operation operation, NativeActorHandle handle,
                                   ActorActRequest.ExpectedContext context) {
        try {
            synchronized (monitor) {
                requireAccepting();
                if (ownedSession(session.owner, session.id, session.generation) != session) {
                    throw error(HttpStatus.GONE, "stale_actor_session");
                }
                requireActionSession(session, handle);
            }
            if (!backend.isCurrent(handle)) throw error(HttpStatus.CONFLICT, "actor_session_stale");
            validateActionContext(handle, context);
            synchronized (monitor) {
                requireAccepting();
                requireActionSession(session, handle);
            }
        } catch (ObservationException failure) {
            synchronized (monitor) { operation.chatEntryFailure = safeReason(failure, "native_chat_validation_failed"); }
            throw failure;
        } catch (RuntimeException unavailable) {
            synchronized (monitor) { operation.chatEntryFailure = "actor_observation_unavailable"; }
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
        }
    }

    private void chatCompleted(Operation operation, NativeActionService.ActionHandle source,
                               NativeActionResult result, Throwable failure) {
        synchronized (monitor) {
            if (operation.chatAction != source || terminal(operation.status)) return;
            if (failure == null && result != null) finishChat(operation, result);
            else chatResultUnavailable(operation, source.dispatchStarted());
        }
    }

    private void chatFailedBeforeDispatch(Operation operation, String reason) {
        operation.result = Map.of("observed_outcome", "chat_not_dispatched", "native_dispatch_started", false,
                "cancel_too_late", false);
        operation.error = Map.of("reason", reason);
        finish(operation, "FAILED", reason);
    }

    private void chatResultUnavailable(Operation operation, boolean started) {
        operation.dispatchStarted = started;
        if (!started && actionCancelled(operation.session, operation)) cancelledBeforeAction(operation);
        else if (!started) chatFailedBeforeDispatch(operation, "native_chat_result_unavailable");
        else {
            operation.result = Map.of("observed_outcome", "native_chat_outcome_unknown", "native_dispatch_started", true,
                    "cancel_too_late", operation.cancelRequested);
            operation.error = Map.of("reason", "native_chat_outcome_unknown");
            finish(operation, "INDETERMINATE", "native_chat_outcome_unknown");
        }
    }

    private void runAction(Session session, Operation operation, NativeActorHandle handle, String text, Long dialogueVersion,
                           String replyKind, Integer selection,
                           ActorActRequest.ExpectedContext context) {
        try {
            synchronized (monitor) {
                if (actionCancelled(session, operation)) {
                    cancelledBeforeAction(operation);
                    return;
                }
                requireActionSession(session, handle);
                running(operation, "chat".equals(operation.action) ? "chat_validation_started" : "npc_validation_started");
            }
            validateNativeAction(handle, operation.action, text, dialogueVersion, replyKind, selection);
            validateActionContext(handle, context);
            synchronized (monitor) {
                if (actionCancelled(session, operation)) {
                    cancelledBeforeAction(operation);
                    return;
                }
                requireActionSession(session, handle);
                operation.dispatchStarted = true;
                running(operation, "chat".equals(operation.action) ? "native_chat_dispatch_started" : "native_npc_dispatch_started");
            }
            Map<String, Object> outcome = switch (operation.action) {
                case "npc_center_open" -> backend.npcCenterOpen(handle);
                case "npc_cancel" -> backend.npcCancel(handle, dialogueVersion);
                case "npc_reply" -> backend.npcReply(handle, dialogueVersion, replyKind, selection);
                default -> throw error(HttpStatus.BAD_REQUEST, "unsupported_action");
            };
            synchronized (monitor) {
                finishNpc(operation, outcome, dialogueVersion, replyKind, selection);
            }
        } catch (ObservationException failure) {
            synchronized (monitor) {
                if (!operation.dispatchStarted && actionCancelled(session, operation)) cancelledBeforeAction(operation);
                else {
                    operation.result = Map.of("observed_outcome", "chat".equals(operation.action) ? "chat_not_dispatched" : "action_not_dispatched",
                            "native_dispatch_started", false, "cancel_too_late", false);
                    String reason = safeReason(failure, "chat".equals(operation.action) ? "native_chat_rejected" : "native_npc_rejected");
                    operation.error = Map.of("reason", reason);
                    finish(operation, "FAILED", reason);
                }
            }
        } catch (Throwable failure) {
            synchronized (monitor) {
                if (!operation.dispatchStarted && actionCancelled(session, operation)) cancelledBeforeAction(operation);
                else {
                    boolean chat = "chat".equals(operation.action);
                    String reason = operation.dispatchStarted ? chat ? "native_chat_outcome_unknown" : "native_npc_outcome_unknown"
                            : chat ? "native_chat_validation_failed" : "native_npc_validation_failed";
                    operation.result = Map.of("observed_outcome", operation.dispatchStarted
                                    ? reason : chat ? "chat_not_dispatched" : "action_not_dispatched",
                            "native_dispatch_started", operation.dispatchStarted,
                            "cancel_too_late", operation.dispatchStarted && operation.cancelRequested);
                    operation.error = Map.of("reason", reason);
                    finish(operation, operation.dispatchStarted ? "INDETERMINATE" : "FAILED", reason);
                }
            }
        }
    }

    private void finishChat(Operation operation, NativeActionResult outcome) {
        operation.dispatchStarted = outcome.dispatch() == NativeActionResult.Dispatch.STARTED;
        if (outcome.outcome() == NativeActionResult.Outcome.CANCELLED) {
            cancelledBeforeAction(operation);
            return;
        }
        String reason = outcome.dispatch() == NativeActionResult.Dispatch.NOT_STARTED && operation.chatEntryFailure != null
                ? operation.chatEntryFailure : AcceptanceChatResults.errorReason(outcome);
        if (outcome.outcome() == NativeActionResult.Outcome.FAILED && !operation.dispatchStarted) {
            chatFailedBeforeDispatch(operation, reason == null ? "native_chat_validation_failed" : reason);
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>(AcceptanceChatResults.resultFields(outcome));
        result.put("cancel_too_late", operation.dispatchStarted && operation.cancelRequested);
        operation.result = result;
        operation.error = outcome.outcome() == NativeActionResult.Outcome.SUCCEEDED ? null
                : Map.of("reason", reason == null ? "native_chat_outcome_unknown" : reason);
        finish(operation, outcome.outcome().name(), outcome.outcome() == NativeActionResult.Outcome.SUCCEEDED
                ? "native_chat_broadcast_observed" : reason == null ? "native_chat_outcome_unknown" : reason);
    }

    private void finishNpc(Operation operation, Map<String, Object> outcome, Long requestedVersion,
                           String replyKind, Integer selection) {
        boolean opening = "npc_center_open".equals(operation.action);
        boolean replying = "npc_reply".equals(operation.action);
        boolean selecting = replying && "select".equals(replyKind);
        String success = replying ? selecting ? "dialogue_replied" : "dialogue_declined"
                : opening ? "center_dialogue_opened" : "dialogue_cancelled";
        String failed = opening ? "native_center_not_opened" : "native_dialogue_not_cancelled";
        String unknown = replying ? "native_dialogue_reply_outcome_unknown"
                : opening ? "native_center_outcome_unknown" : "native_dialogue_outcome_unknown";
        String status = outcome != null && outcome.get("status") instanceof String value ? value : "INDETERMINATE";
        Map<?, ?> nativeResult = outcome != null && outcome.get("result") instanceof Map<?, ?> value ? value : Map.of();
        boolean dispatched = Boolean.TRUE.equals(nativeResult.get("native_dispatch_started"));
        boolean activeMatches = opening || selecting ? Boolean.TRUE.equals(nativeResult.get("active"))
                : Boolean.FALSE.equals(nativeResult.get("active"))
                && Boolean.TRUE.equals(nativeResult.get("native_dialogue_cleanup_verified"));
        boolean replyMatches = !replying || Boolean.TRUE.equals(nativeResult.get("reply_version_consumed"))
                && replyKind.equals(nativeResult.get("kind")) && java.util.Objects.equals(selection, nativeResult.get("selection"))
                && nativeResult.get("dialogue_version") instanceof Number next && next.longValue() > requestedVersion;
        if (!List.of("SUCCEEDED", "FAILED", "INDETERMINATE").contains(status) || replying && "FAILED".equals(status)
                || "SUCCEEDED".equals(status) && (!dispatched || !activeMatches
                || !replyMatches || !success.equals(nativeResult.get("observed_outcome")))) status = "INDETERMINATE";

        Map<String, Object> result = new LinkedHashMap<>();
        String observed = "SUCCEEDED".equals(status) ? success : "FAILED".equals(status) ? failed : unknown;
        result.put("observed_outcome", observed);
        result.put("native_dispatch_started", dispatched);
        for (String key : List.of("dialogue_version", "packet_sequence", "packet_cursor_before", "packet_cursor_after",
                "npc_template_id", "message_type", "selection")) {
            if (nativeResult.get(key) instanceof Number value) result.put(key, value);
            else if (nativeResult.containsKey(key) && nativeResult.get(key) == null) result.put(key, null);
        }
        for (String key : List.of("active", "native_dialogue_cleanup_verified", "native_handler_error", "reply_version_consumed")) {
            if (nativeResult.get(key) instanceof Boolean value) result.put(key, value);
            else if (nativeResult.containsKey(key) && nativeResult.get(key) == null) result.put(key, null);
        }
        if (nativeResult.get("kind") instanceof String kind && List.of("select", "decline").contains(kind)) result.put("kind", kind);
        if (nativeResult.get("dialogue_path") instanceof String path
                && List.of("center", "welfare", "transport_category", "transport_town", "unsupported", "closed").contains(path)) {
            result.put("dialogue_path", path);
        } else if (nativeResult.containsKey("dialogue_path") && nativeResult.get("dialogue_path") == null) result.put("dialogue_path", null);
        result.put("cancel_too_late", dispatched && operation.cancelRequested);
        operation.result = result;
        operation.error = "SUCCEEDED".equals(status) ? null : Map.of("reason", observed);
        finish(operation, status, observed);
    }

    private void validateNativeAction(NativeActorHandle handle, String action, String text, Long dialogueVersion,
                                      String replyKind, Integer selection) {
        switch (action) {
            case "chat" -> backend.validateChat(handle, text);
            case "npc_center_open" -> backend.validateNpcCenterOpen(handle);
            case "npc_cancel" -> backend.validateNpcCancel(handle, dialogueVersion);
            case "npc_reply" -> backend.validateNpcReply(handle, dialogueVersion, replyKind, selection);
            default -> throw error(HttpStatus.BAD_REQUEST, "unsupported_action");
        }
    }

    private static void validateActionParameters(String action, ActorActRequest.Parameters parameters) {
        if (action == null || !List.of("chat", "npc_center_open", "npc_cancel", "npc_reply").contains(action)) {
            throw error(HttpStatus.BAD_REQUEST, "unsupported_action");
        }
        if (parameters == null) throw error(HttpStatus.BAD_REQUEST, "invalid_action_parameters");
        if (!"npc_reply".equals(action) && (parameters.kind() != null || parameters.selection() != null)) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_action_parameters");
        }
        if ("chat".equals(action)) {
            if (parameters.dialogueVersion() != null) throw error(HttpStatus.BAD_REQUEST, "invalid_action_parameters");
        } else {
            if (parameters.text() != null) throw error(HttpStatus.BAD_REQUEST, "invalid_action_parameters");
            if ("npc_cancel".equals(action) || "npc_reply".equals(action)) {
                if (parameters.dialogueVersion() == null || parameters.dialogueVersion() < 1) {
                    throw error(HttpStatus.BAD_REQUEST, "invalid_dialogue_version");
                }
                if ("npc_reply".equals(action)) {
                    if (parameters.kind() == null || !List.of("select", "decline").contains(parameters.kind())) {
                        throw error(HttpStatus.BAD_REQUEST, "unsupported_reply_kind");
                    }
                    if ("select".equals(parameters.kind())) {
                        if (parameters.selection() == null || parameters.selection() < 0) {
                            throw error(HttpStatus.BAD_REQUEST, "invalid_npc_selection");
                        }
                    } else if (parameters.selection() != null) throw error(HttpStatus.BAD_REQUEST, "invalid_action_parameters");
                }
            } else if (parameters.dialogueVersion() != null) throw error(HttpStatus.BAD_REQUEST, "invalid_action_parameters");
        }
    }

    private void cancelledBeforeAction(Operation operation) {
        String outcome = "chat".equals(operation.action) ? "chat_cancelled_before_dispatch" : "action_cancelled_before_dispatch";
        operation.result = Map.of("observed_outcome", outcome,
                "native_dispatch_started", false, "cancel_too_late", false);
        operation.error = null;
        finish(operation, "CANCELLED", outcome);
    }

    private boolean actionCancelled(Session session, Operation operation) {
        return operation.cancelRequested || !sameWorld(session) || session.cleanupScheduled;
    }

    private void cancelUndispatchedActions(Session session) {
        for (Operation operation : operations.values()) {
            if (operation.session == session && "ACTION".equals(operation.kind) && !nativeDispatchStarted(operation)
                    && !terminal(operation.status) && !operation.cancelRequested) requestCancellation(operation);
        }
    }

    private void releaseActionSlot(Operation operation) {
        if (operation.actionSlotHeld) {
            operation.actionSlotHeld = false;
            operation.session.pendingActions--;
        }
        monitor.notifyAll();
    }

    private void requireActionSession(Session session, NativeActorHandle expectedHandle) {
        if (!sameWorld(session)) throw error(HttpStatus.GONE, "stale_actor_session");
        if (!"OPEN".equals(session.state) || session.handle == null) {
            boolean transition = List.of("OPENING", "CLOSING").contains(session.state);
            throw error(transition ? HttpStatus.CONFLICT : HttpStatus.GONE,
                    transition ? "actor_not_actable" : "stale_actor_session");
        }
        if (expectedHandle != null && session.handle != expectedHandle) throw error(HttpStatus.GONE, "stale_actor_session");
    }

    private void validateActionContext(NativeActorHandle handle, ActorActRequest.ExpectedContext expected) {
        if (observations == null) throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
        Map<String, Object> actual = observations.contextForActor(handle.characterRef());
        if (!expected.sceneRef().equals(actual.get("scene_ref"))
                || !expected.runtimeGeneration().equals(actual.get("runtime_generation"))) {
            throw error(HttpStatus.CONFLICT, "actor_context_mismatch");
        }
    }

    private static String textDigest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < text.length(); i++) {
                char unit = text.charAt(i);
                digest.update((byte) (unit >>> 8));
                digest.update((byte) unit);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private void runClose(Session session, Operation operation, NativeActorHandle exactHandle,
                          ScopeResources.CleanupAttempt attempt) {
        synchronized (monitor) {
            if (operation != null) running(operation, "native_actor_close_started");
            session.state = "CLOSING";
        }
        try {
            Map<String, Object> cleanup = session.execution.cleanup(attempt, exactHandle);
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
        } catch (ObservationException failure) {
            throw failure;
        } catch (RuntimeException ignored) {
            // A failed read is neither identity loss nor permission to publish the old projection.
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
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

    private void requireObservationUnchanged(Session session, NativeActorHandle handle, String generation) {
        if (!live() || !generation.equals(worldGeneration) || !generation.equals(session.runtimeGeneration)
                || session.handle != handle || !"OPEN".equals(session.state)) {
            throw error(HttpStatus.CONFLICT, "runtime_changed");
        }
    }

    private Map<String, Object> actorSnapshot(NativeActorHandle handle) {
        Map<String, Object> result = copy(backend.snapshot(handle));
        if (observations != null) result.put("scene", observations.contextForActor(handle.characterRef()));
        return result;
    }

    private void release(Session session, String state) {
        session.state = state;
        session.lastActor = null;
        session.handle = null;
        profiles.remove(session.profile, session);
        monitor.notifyAll();
    }

    private void cancelledBeforeOpen(Session session, Operation operation) {
        release(session, "CANCELLED");
        operation.result = Map.of("observed_outcome", "open_cancelled_before_dispatch");
        finish(operation, "CANCELLED", "open_cancelled_before_dispatch");
    }

    private Operation operation(String kind, Session session) {
        Operation operation = new Operation("op-" + UUID.randomUUID(), kind, session);
        if (!"CLOSE".equals(kind)) {
            try { operation.work = session.execution.acceptWork(); }
            catch (ExecutionScope.Invalid failure) {
                WorldEpoch.State current = WorldEpoch.snapshot();
                if ("scope_not_active".equals(failure.reason()) && (!current.active()
                        || current.token() != session.execution.scope().acceptedEpoch())) {
                    throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_world_offline");
                }
                throw failure;
            }
        }
        operations.put(operation.id, operation);
        event(operation, "queued");
        return operation;
    }

    private void submitCleanup(Session session, Operation operation, NativeActorHandle exactHandle) {
        try {
            ScopeResources.CleanupAttempt attempt = session.execution.cleanupAttempt();
            synchronized (monitor) {
                session.cleanupWork = attempt.work();
                if (operation != null) operation.work = attempt.work();
            }
            CharacterCommandLane.Submission submission = session.execution.submitCleanup(attempt,
                    () -> runClose(session, operation, exactHandle, attempt),
                    () -> cleanupSettled(session, operation, attempt.work()));
            if (submission.status() == CharacterCommandLane.Status.REJECTED
                    || submission.status() == CharacterCommandLane.Status.CLEANUP_PENDING
                    || submission.status() == CharacterCommandLane.Status.DUPLICATE) {
                synchronized (monitor) { cleanupSubmissionFailed(session, operation, "actor_queue_full"); }
            }
        } catch (RuntimeException unavailable) {
            synchronized (monitor) { cleanupSubmissionFailed(session, operation, "actor_close_cleanup_unverified_scope"); }
        }
    }

    /** One explicit stop intent may recheck an owned resource after the preceding physical carrier settles. */
    private void retryStoppedCleanup(Session session, ExecutionScope.WorkHandle previous) {
        synchronized (session.submissionMonitor) {
            NativeActorHandle exactHandle;
            synchronized (monitor) {
                if (!previous.isSettled() || session.execution.resourceReleased() || cleanupInFlight(session)) return;
                exactHandle = session.execution.adoptedHandle();
                if (exactHandle == null) return;
                session.cleanupScheduled = true;
                session.state = "CLOSING";
            }
            submitCleanup(session, null, exactHandle);
        }
    }

    private void cleanupSubmissionFailed(Session session, Operation operation, String reason) {
        session.cleanupScheduled = session.cleanupWork != null && !session.cleanupWork.isSettled();
        session.state = "QUARANTINED";
        session.lastActor = null;
        if (operation != null && !terminal(operation.status)) {
            operation.error = Map.of("reason", reason);
            finish(operation, "INDETERMINATE", "native_actor_cleanup_unverified");
        }
        monitor.notifyAll();
    }

    private void requireSubmitted(CharacterCommandLane.Submission submission, Operation operation) {
        if (submission.status() == CharacterCommandLane.Status.REJECTED) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_queue_full");
        }
    }

    private void cleanupSettled(Session session, Operation operation, ExecutionScope.WorkHandle work) {
        boolean released;
        synchronized (monitor) {
            if (work.snapshot().rejected() || operation != null && !terminal(operation.status)
                    || operation == null && session.cleanupScheduled) {
                cleanupSubmissionFailed(session, operation, "actor_close_cleanup_unverified_scope");
            }
            if (operation != null) operation.work = null;
            released = !profiles.containsValue(session) && session.execution.resourceReleased();
            if (released && session.cleanupWork == work) {
                session.cleanupScheduled = false;
                session.cleanupWork = null;
            }
            monitor.notifyAll();
        }
        if (released) closeReleasedLane(session);
    }

    private void workSettled(Session session, Operation operation) {
        boolean released;
        synchronized (monitor) {
            ExecutionScope.WorkHandle work = operation.work;
            if (!terminal(operation.status)) {
                if (work.snapshot().rejected()) {
                    operation.error = Map.of("reason", "actor_queue_full");
                    if ("ACTION".equals(operation.kind)) operation.result = Map.of("observed_outcome",
                            "chat".equals(operation.action) ? "chat_not_dispatched" : "action_not_dispatched",
                            "native_dispatch_started", false);
                    finish(operation, "FAILED", "actor_queue_full");
                    if ("OPEN".equals(operation.kind) && session.execution.resourceReleased()) release(session, "FAILED");
                } else if ("OPEN".equals(operation.kind)) {
                    if (session.execution.resourceReleased()) cancelledBeforeOpen(session, operation);
                    else {
                        session.state = "QUARANTINED";
                        operation.error = Map.of("reason", "native_actor_cleanup_unverified");
                        finish(operation, "INDETERMINATE", "native_actor_cleanup_unverified");
                    }
                } else if (operation.cancelRequested || !sameWorld(session) || session.cleanupScheduled) {
                    cancelledBeforeAction(operation);
                } else {
                    operation.error = Map.of("reason", "actor_command_rejected");
                    finish(operation, "FAILED", "actor_command_rejected");
                }
            }
            if (!"ACTION".equals(operation.kind)) operation.work = null;
            released = !profiles.containsValue(session) && session.execution.resourceReleased();
            monitor.notifyAll();
        }
        if (released) closeReleasedLane(session);
    }

    /** Pure receipt of the factual completion, after the lane's physical tail and settle hook have exited. */
    private void actionCarrierCompleted(Operation operation, ExecutionScope.WorkHandle exactWork) {
        synchronized (monitor) {
            if (operation.work != exactWork) return;
            if ("chat".equals(operation.action) && !terminal(operation.status)) {
                chatResultUnavailable(operation, nativeDispatchStarted(operation));
            }
            releaseActionSlot(operation);
            operation.work = null;
            operation.chatAction = null;
            monitor.notifyAll();
        }
    }

    private static void closeReleasedLane(Session session) {
        session.execution.beginDrain(Optional.empty());
        session.execution.closeLane();
    }

    /** A terminal operation is not permission to reuse a cleanup whose carrier is still settling. */
    private static boolean cleanupInFlight(Session session) {
        if (session.cleanupScheduled && session.cleanupWork != null && session.cleanupWork.isSettled()) {
            session.cleanupScheduled = false;
            session.cleanupWork = null;
        }
        return session.cleanupScheduled;
    }

    private void requestCancellation(Operation operation) {
        operation.session.execution.cancel(operation.work);
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
        if (!terminal(operation.status) && !operation.cancelRequested && nativeDispatchStarted(operation)
                && !"native_chat_dispatch_started".equals(operation.step) && "chat".equals(operation.action)) {
            running(operation, "native_chat_dispatch_started");
        }
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
        if (operation.action != null) response.put("action", operation.action);
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

    private static boolean nativeDispatchStarted(Operation operation) {
        if (operation.chatAction != null && operation.chatAction.dispatchStarted()) operation.dispatchStarted = true;
        return operation.dispatchStarted;
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
        return new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                Thread.ofVirtual().name("acceptance-actor-" + profile + "-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    private record RequestKey(String kind, String owner, String target, String requestId) { }
    private record RequestRecord(Object fingerprint, String operationId) { }
    private record ActionFingerprint(long generation, String action, String textHash, Long dialogueVersion,
                                     String replyKind, Integer selection,
                                     ActorActRequest.ExpectedContext context) { }
    private static final class Session {
        final String id;
        final long generation;
        final String owner;
        final String profile;
        final String runtimeGeneration;
        final Object submissionMonitor = new Object();
        final AcceptanceActorExecution execution;
        String state = "OPENING";
        String openingOperation;
        NativeActorHandle handle;
        Map<String, Object> lastActor;
        boolean cleanupScheduled;
        ExecutionScope.WorkHandle cleanupWork;
        ExecutionScope.WorldStopTicket stopRetryTicket;
        boolean firstCloseReserved = true;
        int pendingActions;
        Session(String id, long generation, String owner, String profile, String runtimeGeneration, WorldEpoch.Token admittedEpoch,
                NativeActorBackend backend, ExecutorService executor) {
            this.id = id;
            this.generation = generation;
            this.owner = owner;
            this.profile = profile;
            this.runtimeGeneration = runtimeGeneration;
            this.execution = new AcceptanceActorExecution(backend, this, admittedEpoch, executor);
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
        boolean dispatchStarted;
        boolean actionSlotHeld;
        ExecutionScope.WorkHandle work;
        NativeActionService.ActionHandle chatAction;
        String chatEntryFailure;
        String action;
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
