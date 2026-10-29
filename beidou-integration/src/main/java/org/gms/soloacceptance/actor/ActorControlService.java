package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.gms.soloacceptance.AcceptanceObservationRuntime;
import org.gms.soloacceptance.AcceptanceObservationService;
import org.gms.soloacceptance.ObservationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeInventoryActions;
import soloMapling.game.action.NativeInventoryMoveAction;
import soloMapling.game.action.InventoryMoveResult;
import soloMapling.game.action.NativeBasicActions;
import soloMapling.game.action.NativeBasicResult;
import soloMapling.game.action.NativeMovementActions;
import soloMapling.game.action.NativeAttackActions;
import soloMapling.game.action.NativeItemActions;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.script.ScriptResult;
import soloMapling.game.script.nativeenv.NativeScriptExecution;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptResult;

import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final ObjectMapper SCRIPT_JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final long SCRIPT_CLEANUP_WAIT_MS = 3000;
    private static final int SCRIPT_OUTPUT_EVENTS = 256;
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
                text == null ? null : textDigest(text), dialogueVersion, replyKind, selection,
                request.parameters() == null ? null : request.parameters().inventoryType(),
                request.parameters() == null ? null : request.parameters().sourceSlot(),
                request.parameters() == null ? null : request.parameters().targetSlot(),
                request.parameters() == null ? null : request.parameters().direction(),
                request.parameters() == null ? null : request.parameters().durationMs(),
                request.parameters() == null ? null : request.parameters().targetOid(),
                request.parameters() == null ? null : request.parameters().objectId(),
                request.parameters() == null ? null : request.parameters().quantity(), context);
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
            if ("inventory_move".equals(request.action())) backend.validateInventoryMove(handle, inventoryMove(request.parameters()));
            if (ActorBasicRequests.supports(request.action())) backend.validateBasic(handle, request.action(), request.parameters());
            validateActionContext(handle, context);
        } catch (RuntimeException failure) {
            synchronized (monitor) { prior = duplicate(key, fingerprint); }
            if (prior != null) return refreshedResponse(prior, 0, 100);
            throw failure;
        }
        synchronized (session.submissionMonitor) {
            // Native script hosts retain their real CM/QM/map objects between invocations.
            // A new direct action must not race those owned native contexts.
            if (!session.execution.nativeScriptContexts().isEmpty()) {
                synchronized (monitor) {
                    prior = duplicate(key, fingerprint);
                    if (prior == null) throw error(HttpStatus.CONFLICT, "native_script_context_active");
                }
                return refreshedResponse(prior, 0, 100);
            }
            Operation accepted;
            synchronized (monitor) {
                // Concurrent callers may have accepted this ID while native validation was running.
                prior = duplicate(key, fingerprint);
                if (prior == null) {
                    if (nativeScriptInFlight(session)) throw error(HttpStatus.CONFLICT, "native_script_context_active");
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
                } else if ("inventory_move".equals(accepted.action)) {
                    NativeActionService.SubmissionReceipt receipt = submitInventoryMove(acceptedSession, accepted, acceptedHandle,
                            inventoryMove(request.parameters()), context);
                    if (receipt != null && receipt.status() == NativeActionService.SubmissionStatus.REJECTED) {
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_queue_full");
                    }
                } else if (ActorBasicRequests.supports(accepted.action)) {
                    NativeActionService.SubmissionReceipt receipt = submitBasic(acceptedSession, accepted, acceptedHandle,
                            request.parameters(), context);
                    if (receipt != null && receipt.status() == NativeActionService.SubmissionStatus.REJECTED) {
                        if (receipt.rejection() == NativeActionService.SubmissionRejection.CAPACITY
                                || receipt.rejection() == NativeActionService.SubmissionRejection.EXECUTOR) {
                            throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_queue_full");
                        }
                        synchronized (monitor) {
                            // Inline authority/state rejection may already have a precise native
                            // result (for example movement_busy). Preserve that same operation.
                            if (!terminal(accepted.status)) throw error(HttpStatus.CONFLICT, "actor_command_rejected");
                        }
                    }
                } else {
                    try {
                        CharacterCommandLane.Submission submission = session.execution.submitAction(accepted.work,
                                () -> runAction(acceptedSession, accepted, acceptedHandle, text, dialogueVersion,
                                        replyKind, selection, context), () -> workSettled(acceptedSession, accepted));
                        requireSubmitted(submission, accepted);
                    } catch (Throwable failure) {
                        if (!actualWork.snapshot().carrierClaimed()) {
                            synchronized (monitor) {
                                if (actionCancelled(session, accepted)) cancelledBeforeAction(accepted);
                                else {
                                    accepted.error = Map.of("reason", safeReason(failure, "actor_command_rejected"));
                                    finish(accepted, "FAILED", "actor_command_rejected");
                                }
                            }
                            actualWork.reject();
                        }
                        if (failure instanceof ObservationException known) throw known;
                    }
                }
                synchronized (monitor) { return response(accepted, 0, 100); }
            }
        }
        return refreshedResponse(prior, 0, 100);
    }

    public Map<String, Object> scriptRun(String owner, String requestId, ActorScriptRequest request) {
        return scriptInvocation(owner, requestId, request, false);
    }

    public Map<String, Object> scriptCancel(String owner, String requestId, ActorScriptCancelRequest request) {
        validateRequestId(requestId);
        if (request == null) throw error(HttpStatus.BAD_REQUEST, "invalid_script_request");
        return scriptInvocation(owner, request.targetRequestId(), request.invocation(), true);
    }

    /** Protocol history references the runtime's original work; scripts never enter the short-command lane. */
    private Map<String, Object> scriptInvocation(String owner, String requestId, ActorScriptRequest request,
                                                  boolean cancel) {
        Operation operation = prepareScriptInvocation(owner, requestId, request, cancel, 0);
        return awaitScript(operation, request.timeoutMs());
    }

    private Operation prepareScriptInvocation(String owner, String requestId, ActorScriptRequest request,
                                               boolean cancel, long sinceOutput) {
        validateOwner(owner);
        validateRequestId(requestId);
        if (sinceOutput < 0) throw error(HttpStatus.BAD_REQUEST, "invalid_script_output_cursor");
        NativeScriptRequest script = validatedScript(request);
        ScriptFingerprint fingerprint = new ScriptFingerprint(request.expectedGeneration(),
                textDigest(script.source()), scriptParameterDigest(script.parameters()), script.timeoutMs(),
                scriptInvocationDigest(script));
        RequestKey key = new RequestKey("SCRIPT", owner, request.sessionId(), requestId);
        Operation prior;
        Session session;
        synchronized (monitor) {
            prior = duplicate(key, fingerprint);
            if (sinceOutput > (prior == null ? 0 : prior.outputSequence)) {
                throw error(HttpStatus.BAD_REQUEST, "script_output_cursor_ahead");
            }
            session = prior == null ? ownedSession(owner, request.sessionId(), request.expectedGeneration()) : prior.session;
        }
        if (prior == null) {
            synchronized (session.submissionMonitor) {
                if (!cancel) {
                    synchronized (monitor) {
                        prior = duplicate(key, fingerprint);
                        if (prior == null && operations.values().stream().anyMatch(operation -> operation.session == session
                                && ("ACTION".equals(operation.kind) || "SCRIPT".equals(operation.kind))
                                && operation.work != null && !operation.work.isSettled())) {
                            throw error(HttpStatus.CONFLICT, "actor_work_in_flight");
                        }
                    }
                }
                Operation accepted;
                synchronized (monitor) {
                    prior = duplicate(key, fingerprint);
                    if (prior == null) {
                        requireAccepting();
                        requireActionSession(session, null);
                        if (operations.size() + reservedFirstCloses() + 1 > maxHistory
                                || requests.size() + reservedFirstCloses() + 1 > maxHistory) {
                            throw error(HttpStatus.TOO_MANY_REQUESTS, "actor_history_capacity_exceeded");
                        }
                        accepted = operation("SCRIPT", session);
                        accepted.nativeScriptOperation = script.operation();
                        accepted.scriptRequestId = requestId;
                        requests.put(key, new RequestRecord(fingerprint, accepted.id));
                        if (cancel) requestCancellation(accepted);
                    } else accepted = null;
                }
                if (accepted != null) {
                    prior = accepted;
                    if (cancel) {
                        // This invocation has no carrier. An existing target Context may remain open.
                        accepted.work.reject();
                        scriptNotStarted(accepted, script, "CANCELLED", null);
                    } else startNativeScript(accepted, script);
                }
            }
        }
        if (cancel) {
            synchronized (monitor) {
                if (!terminal(prior.status) && !prior.cancelRequested) requestCancellation(prior);
            }
        }
        return prior;
    }

    /** Authentication and acceptance finish before HTTP headers are committed. */
    public StreamingResponseBody scriptStream(String owner, String requestId, ActorScriptRequest request, long sinceOutput) {
        Operation operation = prepareScriptInvocation(owner, requestId, request, false, sinceOutput);
        return output -> streamScript(operation, sinceOutput, request.timeoutMs(), output);
    }

    private void streamScript(Operation operation, long sinceOutput, long timeoutMs, OutputStream output) throws IOException {
        writeScriptEvent(output, Map.of("type", "started", "request_id", operation.scriptRequestId,
                "operation_id", operation.id));
        long cursor = sinceOutput;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + SCRIPT_CLEANUP_WAIT_MS);
        while (true) {
            List<Map<String, Object>> items;
            Map<String, Object> gap = null;
            Map<String, Object> finished = null;
            synchronized (monitor) {
                long oldest = operation.output.isEmpty() ? operation.outputSequence + 1 : (long) operation.output.getFirst().get("seq");
                if (cursor < oldest - 1) {
                    gap = Map.of("type", "gap", "request_id", operation.scriptRequestId, "operation_id", operation.id,
                            "oldest_cursor", oldest, "latest_cursor", operation.outputSequence);
                    cursor = oldest - 1;
                }
                long after = cursor;
                items = operation.output.stream().filter(item -> (long) item.get("seq") > after).toList();
                if (!items.isEmpty()) cursor = (long) items.getLast().get("seq");
                if (terminal(operation.status)) finished = response(operation, 0, 100);
                else if (System.nanoTime() >= deadline) {
                    scriptAwaitExpired(operation);
                    finished = response(operation, 0, 100);
                } else if (items.isEmpty() && gap == null) {
                    try { TimeUnit.NANOSECONDS.timedWait(monitor, deadline - System.nanoTime()); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("script_stream_interrupted", stopped); }
                    continue;
                }
            }
            if (gap != null) writeScriptEvent(output, gap);
            for (Map<String, Object> item : items) writeScriptEvent(output, item);
            if (finished != null) {
                writeScriptEvent(output, Map.of("type", "result", "operation", finished));
                return;
            }
        }
    }

    private static void writeScriptEvent(OutputStream output, Map<String, Object> event) throws IOException {
        output.write(SCRIPT_JSON.writeValueAsBytes(event));
        output.write('\n');
        output.flush();
    }

    private void scriptOutput(Operation operation, NativeScriptExecution.Output output) {
        synchronized (monitor) {
            operation.outputTruncated |= output.truncated();
            operation.output.addLast(Map.of("type", "output", "request_id", operation.scriptRequestId,
                    "operation_id", operation.id, "seq", ++operation.outputSequence,
                    "channel", output.channel(), "text", output.text(), "output_truncated", output.truncated()));
            while (operation.output.size() > SCRIPT_OUTPUT_EVENTS) operation.output.removeFirst();
            monitor.notifyAll();
        }
    }

    /** Reads the existing operation/work association; this is no second execution ledger. */
    private boolean nativeScriptInFlight(Session session) {
        return operations.values().stream().anyMatch(operation -> operation.session == session && "SCRIPT".equals(operation.kind)
                && operation.work != null && !operation.work.isSettled());
    }

    private void startNativeScript(Operation operation, NativeScriptRequest request) {
        ExecutionScope.WorkHandle exact = operation.work;
        try {
            NativeScriptExecution execution = operation.session.execution.runNativeScript(exact, request,
                    output -> scriptOutput(operation, output));
            synchronized (monitor) {
                operation.nativeScriptExecution = execution;
                if (operation.cancelRequested) execution.cancel();
                else running(operation, "native_script_execution_started");
            }
            execution.completion().whenComplete((result, failure) -> nativeScriptCompleted(operation, result, failure));
        } catch (Throwable failure) {
            if (!exact.snapshot().carrierClaimed()) {
                exact.reject();
                scriptNotStarted(operation, request, exact.cancelRequested() ? "CANCELLED" : "RUNTIME_ERROR",
                        exact.cancelRequested() ? null : safeReason(failure, "native_script_execution_rejected"));
            } else synchronized (monitor) {
                requestCancellation(operation);
                operation.error = Map.of("reason", "native_script_cleanup_unverified");
                finish(operation, "INDETERMINATE", "native_script_cleanup_unverified");
            }
        }
    }

    private void nativeScriptCompleted(Operation operation, NativeScriptResult script, Throwable failure) {
        operation.session.execution.refresh();
        synchronized (monitor) {
            if (failure != null || script == null) {
                operation.error = Map.of("reason", "native_script_cleanup_unverified");
                finish(operation, "INDETERMINATE", "native_script_cleanup_unverified");
                return;
            }
            operation.result = script.toMap();
            String status = script.status().name();
            boolean requiresClosed = "close".equals(operation.nativeScriptOperation)
                    || script.status() == ScriptResult.Status.CANCELLED || script.status() == ScriptResult.Status.TIMED_OUT;
            boolean verified = script.engineExited() && script.invocationSettled()
                    && (!requiresClosed || script.contextClosed() && "CLOSED".equals(script.contextState()))
                    && script.status() != ScriptResult.Status.CLEANUP_ERROR;
            if (!"SUCCEEDED".equals(status)) {
                Map<String, Object> problem = new LinkedHashMap<>();
                problem.put("reason", "native_script_" + status.toLowerCase(java.util.Locale.ROOT));
                if (script.error() != null) {
                    problem.put("message", script.error().message());
                    problem.put("line", script.error().line());
                    problem.put("column", script.error().column());
                }
                operation.error = java.util.Collections.unmodifiableMap(problem);
            }
            if (verified) {
                operation.nativeScriptExecution = null;
                operation.moduleContext = null;
                operation.work = null;
            }
            finish(operation, !verified ? "INDETERMINATE" : "SUCCEEDED".equals(status) ? "SUCCEEDED"
                    : "CANCELLED".equals(status) ? "CANCELLED" : "FAILED",
                    "native_script_" + status.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void scriptNotStarted(Operation operation, NativeScriptRequest request, String status, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("result", null);
        result.put("stdout", ""); result.put("stderr", ""); result.put("output_truncated", false);
        result.put("error", reason == null ? null : Map.of("message", reason));
        result.put("engine_started", false); result.put("engine_exited", true);
        result.put("context_id", request.contextId());
        boolean newContext = "start".equals(request.operation());
        String contextState = newContext ? "NOT_STARTED" : "UNKNOWN";
        boolean contextClosed = newContext;
        if (!newContext) {
            try {
                for (Map<String, Object> context : operation.session.execution.nativeScriptContexts()) {
                    if (request.contextId().equals(context.get("context_id")) && request.kind().equals(context.get("kind"))) {
                        contextState = context.get("context_state") instanceof String observed ? observed : "UNKNOWN";
                        contextClosed = Boolean.TRUE.equals(context.get("context_closed"));
                        break;
                    }
                }
            } catch (RuntimeException unavailable) {
                // No matching current fact is not proof that the pre-existing Context was closed.
            }
        }
        result.put("context_closed", contextClosed); result.put("work_settled", operation.work.isSettled());
        result.put("invocation_settled", operation.work.isSettled());
        result.put("context_state", contextState);
        operation.session.execution.refresh();
        synchronized (monitor) {
            operation.result = java.util.Collections.unmodifiableMap(result);
            if (reason != null) operation.error = Map.of("reason", reason);
            if (operation.work.isSettled()) {
                operation.work = null;
                operation.moduleContext = null;
            }
            finish(operation, "CANCELLED".equals(status) ? "CANCELLED" : "FAILED", "script_not_started");
        }
    }

    private Map<String, Object> awaitScript(Operation operation, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + SCRIPT_CLEANUP_WAIT_MS);
        boolean interrupted = false;
        synchronized (monitor) {
            while (operation.work != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
                catch (InterruptedException stopped) { interrupted = true; break; }
            }
            if (operation.work != null) {
                scriptAwaitExpired(operation);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        operation.session.execution.refresh();
        synchronized (monitor) { return response(operation, 0, 100); }
    }

    private void scriptAwaitExpired(Operation operation) {
        if (!operation.cancelRequested) requestCancellation(operation);
        operation.error = Map.of("reason", "script_cleanup_unverified");
        if (operation.result.isEmpty()) operation.result = Map.of("engine_exited", false,
                "context_closed", false, "work_settled", false);
        finish(operation, "INDETERMINATE", "script_cleanup_unverified");
    }

    private static NativeScriptRequest validatedScript(ActorScriptRequest request) {
        if (request == null) throw error(HttpStatus.BAD_REQUEST, "invalid_script_request");
        validateScriptSession(request.sessionId(), request.expectedGeneration());
        String source = request.source();
        if (source == null || source.length() > 65536 || request.timeoutMs() < 1 || request.timeoutMs() > 5000
                || !validScriptUnicode(source)) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_script_request");
        }
        if (request.kind() == null || !List.of("npc", "quest", "portal", "reactor", "event").contains(request.kind())
                || request.operation() == null || !List.of("start", "invoke", "close").contains(request.operation())) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_native_script_request");
        }
        if (request.contextId() != null && !request.contextId().matches("[A-Za-z0-9_.-]{1,128}")) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_native_script_context");
        }
        if (("start".equals(request.operation()) && request.contextId() != null)
                || (!"start".equals(request.operation()) && request.contextId() == null)
                || (request.entry() != null && !request.entry().matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}"))
                || ("invoke".equals(request.operation()) && !source.isBlank() && request.entry() != null)
                || ("close".equals(request.operation()) && (!source.isBlank() || request.entry() != null
                        || !request.args().isEmpty() || !request.options().isEmpty()))) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_native_script_request");
        }
        try {
            String parametersJson = scriptJson(request.parameters());
            String optionsJson = scriptJson(request.options());
            String argsJson = scriptJson(request.args());
            if (parametersJson.getBytes(StandardCharsets.UTF_8).length + optionsJson.getBytes(StandardCharsets.UTF_8).length
                    + argsJson.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw error(HttpStatus.BAD_REQUEST, "invalid_script_parameters");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> detachedParameters = SCRIPT_JSON.readValue(parametersJson, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> detachedOptions = SCRIPT_JSON.readValue(optionsJson, Map.class);
            @SuppressWarnings("unchecked")
            List<Object> detachedArgs = SCRIPT_JSON.readValue(argsJson, List.class);
            return new NativeScriptRequest(request.kind(), request.operation(), request.contextId(),
                    detachedOptions, request.entry(), detachedArgs, source, detachedParameters, request.timeoutMs());
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_native_script_request");
        }
    }

    /** Hash only detached invocation data; the operation history never retains script bodies or native objects. */
    private static String scriptInvocationDigest(NativeScriptRequest script) {
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("kind", script.kind()); invocation.put("operation", script.operation());
        invocation.put("context_id", script.contextId()); invocation.put("options", script.options());
        invocation.put("entry", script.entry()); invocation.put("args", script.args());
        try { return textDigest(SCRIPT_JSON.writeValueAsString(invocation)); }
        catch (JsonProcessingException invalid) { throw error(HttpStatus.BAD_REQUEST, "invalid_native_script_request"); }
    }

    private static void validateScriptSession(String sessionId, Long generation) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_.-]{1,128}") || generation == null || generation < 1) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_actor_session");
        }
    }

    private static String scriptParameterDigest(Map<String, Object> parameters) {
        return textDigest(scriptJson(parameters));
    }

    private static String scriptJson(Object value) {
        try {
            requireScriptJson(value, 0);
            String json = SCRIPT_JSON.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw error(HttpStatus.BAD_REQUEST, "invalid_script_parameters");
            }
            return json;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_script_parameters");
        }
    }

    /** Validate limits on the JSON tree produced by the HTTP parser before accepting work. */
    private static void requireScriptJson(Object value, int depth) {
        if (depth > 16) throw error(HttpStatus.BAD_REQUEST, "invalid_script_parameters");
        if (value instanceof String text) {
            if (!validScriptUnicode(text)) throw error(HttpStatus.BAD_REQUEST, "invalid_script_parameters");
        } else if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw error(HttpStatus.BAD_REQUEST, "invalid_script_parameters");
        } else if (value instanceof Map<?, ?> object) {
            for (var entry : object.entrySet()) {
                if (!validScriptUnicode((String) entry.getKey())) {
                    throw error(HttpStatus.BAD_REQUEST, "invalid_script_parameters");
                }
                requireScriptJson(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> array) {
            for (Object item : array) requireScriptJson(item, depth + 1);
        }
    }

    private static boolean validScriptUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }

    public Map<String, Object> get(String owner, String operationId, long sinceCursor, int limit) {
        return get(owner, operationId, sinceCursor, limit, 0);
    }

    public Map<String, Object> get(String owner, String operationId, long sinceCursor, int limit, long sinceOutput) {
        validateOwner(owner);
        validateWindow(sinceCursor, limit);
        if (sinceOutput < 0) throw error(HttpStatus.BAD_REQUEST, "invalid_script_output_cursor");
        Operation operation;
        synchronized (monitor) {
            operation = ownedOperation(owner, operationId);
            if (sinceCursor > operation.sequence) throw error(HttpStatus.BAD_REQUEST, "operation_cursor_ahead");
            if (sinceOutput > operation.outputSequence) throw error(HttpStatus.BAD_REQUEST, "script_output_cursor_ahead");
        }
        Map<String, Object> response = refreshedResponse(operation, sinceCursor, limit);
        if ("SCRIPT".equals(operation.kind)) {
            synchronized (monitor) { response.put("script_output", scriptOutputWindow(operation, sinceOutput)); }
        }
        return response;
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
                actor = actorSnapshot(session, handle);
                actor.put("runtime", session.execution.diagnostics());
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
            result.put("enabled_tools", List.of("actor_open", "actor_close", "operation_get", "operation_cancel", "game_act",
                    "script_run"));
            result.put("script_execution", Map.of("language", "javascript", "minimum_timeout_ms", 1,
                    "maximum_timeout_ms", 5000, "default_timeout_ms", 1000, "maximum_source_characters", 65536,
                    "maximum_parameters_bytes", 16384, "execution_owner", "character_runtime",
                    "streaming", Map.of("transport", "ndjson", "delivery", "optional_files",
                            "file_arguments", List.of("output_file", "error_file"), "cursor_argument", "since_output",
                            "event_capacity", SCRIPT_OUTPUT_EVENTS, "maximum_stdout_bytes", 16384, "maximum_stderr_bytes", 16384)));
            result.put("native_environments", backend.nativeScriptEnvironments());
            result.put("actions", List.of("chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move",
                    "move", "stop", "attack", "pickup", "drop", "use_item", "equip", "unequip"));
            result.put("movement", NativeMovementActions.capabilities());
            result.put("attack", NativeAttackActions.capabilities());
            result.put("items", NativeItemActions.capabilities());
            result.put("inventory_move", Map.of("inventory_types", List.of("EQUIP", "USE", "SETUP", "ETC", "CASH"),
                    "minimum_slot", 1, "maximum_slot", 127, "native_minimum_interval_ms", 300,
                    "semantics", "native_whole_slot_move_swap_or_merge"));
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
            if (!cancelled(session, operation)) {
                session.execution.bindActor(handle);
                Map<String, Object> snapshot = actorSnapshot(session, handle);
                if (!backend.isCurrent(handle) || !Boolean.TRUE.equals(snapshot.get("logged_in_world"))) {
                    throw error(HttpStatus.CONFLICT, "native_actor_not_online");
                }
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
                    () -> validateActionEntry(session, operation, handle, context));
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

    private NativeActionService.SubmissionReceipt submitInventoryMove(Session session, Operation operation,
                                                                     NativeActorHandle handle, NativeInventoryMoveAction request,
                                                                     ActorActRequest.ExpectedContext context) {
        ExecutionScope.WorkHandle actualWork = operation.work;
        try {
            NativeInventoryActions.MoveHandle action = session.execution.prepareInventoryMove(actualWork, handle, request,
                    () -> validateActionEntry(session, operation, handle, context));
            synchronized (monitor) { operation.inventoryAction = action; }
            action.result().whenComplete((result, failure) -> inventoryCompleted(operation, action, result, failure));
            validateActionContext(handle, context);
            return session.execution.submitInventoryMove(action);
        } catch (Throwable failure) {
            ExecutionScope.WorkSnapshot fact = actualWork.snapshot();
            synchronized (monitor) {
                if (!terminal(operation.status)) {
                    if (fact.carrierClaimed() && !fact.settled() && !fact.rejected()) {
                        operation.step = "native_inventory_submission_confirmation_pending";
                        event(operation, operation.step);
                    } else if (actionCancelled(session, operation)) cancelledBeforeAction(operation);
                    else inventoryFailedBeforeDispatch(operation, safeReason(failure, "native_inventory_validation_failed"));
                }
            }
            if (!fact.carrierClaimed()) {
                synchronized (monitor) { operation.inventoryAction = null; }
                actualWork.reject();
            }
            return null;
        }
    }

    private void inventoryCompleted(Operation operation, NativeInventoryActions.MoveHandle source,
                                    InventoryMoveResult result, Throwable failure) {
        CharacterRuntime.ModuleContext acceptedContext;
        ExecutionScope.WorkHandle acceptedWork;
        synchronized (monitor) {
            if (operation.inventoryAction != source || terminal(operation.status)) return;
            acceptedContext = operation.moduleContext;
            acceptedWork = operation.work;
        }
        if (failure == null && result != null && acceptedContext != null && acceptedWork != null) {
            try { acceptedContext.recordResult(acceptedWork, result); }
            catch (RuntimeException diagnosticFailure) { /* A late fact does not restore module authority. */ }
        }
        synchronized (monitor) {
            if (operation.inventoryAction != source || terminal(operation.status)) return;
            if (failure == null && result != null) finishInventoryMove(operation, result);
            else inventoryResultUnavailable(operation, source.dispatchStarted());
        }
    }

    private void finishInventoryMove(Operation operation, InventoryMoveResult outcome) {
        operation.dispatchStarted = outcome.dispatch() == NativeActionResult.Dispatch.STARTED;
        if (outcome.outcome() == NativeActionResult.Outcome.CANCELLED) {
            cancelledBeforeAction(operation);
            return;
        }
        String reason = !operation.dispatchStarted && operation.actionEntryFailure != null
                ? operation.actionEntryFailure : outcome.fixedReason();
        if (outcome.outcome() == NativeActionResult.Outcome.FAILED && !operation.dispatchStarted) {
            inventoryFailedBeforeDispatch(operation, reason == null ? "native_inventory_validation_failed" : reason);
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>(AcceptanceInventoryResults.resultFields(outcome));
        fields.put("cancel_too_late", operation.dispatchStarted && operation.cancelRequested);
        operation.result = fields;
        operation.error = outcome.outcome() == NativeActionResult.Outcome.SUCCEEDED ? null
                : Map.of("reason", reason == null ? "native_inventory_outcome_unknown" : reason);
        finish(operation, outcome.outcome().name(), outcome.outcome() == NativeActionResult.Outcome.SUCCEEDED
                ? "native_inventory_move_observed" : reason == null ? "native_inventory_outcome_unknown" : reason);
    }

    private void inventoryFailedBeforeDispatch(Operation operation, String reason) {
        operation.result = Map.of("observed_outcome", "inventory_move_not_dispatched", "native_dispatch_started", false,
                "cancel_too_late", false);
        operation.error = Map.of("reason", reason);
        finish(operation, "FAILED", reason);
    }

    private void inventoryResultUnavailable(Operation operation, boolean started) {
        operation.dispatchStarted = started;
        if (!started && actionCancelled(operation.session, operation)) cancelledBeforeAction(operation);
        else if (!started) inventoryFailedBeforeDispatch(operation, "native_inventory_result_unavailable");
        else {
            operation.result = Map.of("observed_outcome", "native_inventory_outcome_unknown", "native_dispatch_started", true,
                    "cancel_too_late", operation.cancelRequested);
            operation.error = Map.of("reason", "native_inventory_outcome_unknown");
            finish(operation, "INDETERMINATE", "native_inventory_outcome_unknown");
        }
    }

    private static NativeInventoryMoveAction inventoryMove(ActorActRequest.Parameters parameters) {
        if (parameters == null || parameters.inventoryType() == null || parameters.sourceSlot() == null
                || parameters.targetSlot() == null) throw error(HttpStatus.BAD_REQUEST, "invalid_inventory_move");
        try { return new NativeInventoryMoveAction(parameters.inventoryType(), parameters.sourceSlot(), parameters.targetSlot()); }
        catch (IllegalArgumentException | NativeActionService.Invalid invalid) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_inventory_move");
        }
    }

    private NativeActionService.SubmissionReceipt submitBasic(Session session, Operation operation, NativeActorHandle handle,
                                                              ActorActRequest.Parameters parameters,
                                                              ActorActRequest.ExpectedContext context) {
        ExecutionScope.WorkHandle actualWork = operation.work;
        try {
            NativeBasicActions.Handle action = session.execution.prepareBasic(actualWork, handle, operation.action, parameters,
                    () -> validateActionEntry(session, operation, handle, context));
            synchronized (monitor) { operation.basicAction = action; }
            action.result().whenComplete((result, failure) -> basicCompleted(operation, action, result, failure));
            validateActionContext(handle, context);
            return session.execution.submitBasic(action);
        } catch (Throwable failure) {
            ExecutionScope.WorkSnapshot fact = actualWork.snapshot();
            synchronized (monitor) {
                if (!terminal(operation.status)) {
                    if (fact.carrierClaimed() && !fact.settled() && !fact.rejected()) {
                        operation.step = "native_basic_submission_confirmation_pending";
                        event(operation, operation.step);
                    } else if (actionCancelled(session, operation)) cancelledBeforeAction(operation);
                    else basicFailedBeforeDispatch(operation, safeReason(failure, "native_basic_validation_failed"));
                }
            }
            if (!fact.carrierClaimed()) {
                synchronized (monitor) { operation.basicAction = null; }
                actualWork.reject();
            }
            return null;
        }
    }

    private void basicCompleted(Operation operation, NativeBasicActions.Handle source, NativeBasicResult result, Throwable failure) {
        CharacterRuntime.ModuleContext acceptedContext;
        ExecutionScope.WorkHandle acceptedWork;
        synchronized (monitor) {
            if (operation.basicAction != source || terminal(operation.status)) return;
            acceptedContext = operation.moduleContext;
            acceptedWork = operation.work;
        }
        if (failure == null && result != null && acceptedContext != null && acceptedWork != null) {
            try { acceptedContext.recordResult(acceptedWork, result); }
            catch (RuntimeException diagnosticFailure) { /* A late native fact does not restore control authority. */ }
        }
        synchronized (monitor) {
            if (operation.basicAction != source || terminal(operation.status)) return;
            if (failure == null && result != null) finishBasic(operation, result);
            else basicResultUnavailable(operation, source.dispatchStarted());
        }
    }

    private void finishBasic(Operation operation, NativeBasicResult outcome) {
        operation.dispatchStarted = outcome.dispatch() == NativeActionResult.Dispatch.STARTED;
        if (outcome.outcome() == NativeActionResult.Outcome.CANCELLED && !operation.dispatchStarted) {
            cancelledBeforeAction(operation);
            return;
        }
        String reason = !operation.dispatchStarted && operation.actionEntryFailure != null
                ? operation.actionEntryFailure : outcome.fixedReason();
        if (outcome.outcome() == NativeActionResult.Outcome.FAILED && !operation.dispatchStarted) {
            basicFailedBeforeDispatch(operation, reason == null ? "native_basic_validation_failed" : reason);
            return;
        }
        String observed = switch (outcome.outcome()) {
            case SUCCEEDED -> "native_" + operation.action + "_observed";
            case FAILED -> "native_" + operation.action + "_not_observed";
            case CANCELLED -> "native_" + operation.action + "_stopped";
            default -> "native_" + operation.action + "_outcome_unknown";
        };
        Map<String, Object> fields = new LinkedHashMap<>(outcome.facts());
        fields.put("observed_outcome", observed);
        fields.put("native_dispatch_started", operation.dispatchStarted);
        fields.put("sender_character_id", outcome.senderCharacterId());
        fields.put("native_handler_error", outcome.handlerError());
        fields.put("native_cancel_requested", outcome.cancelRequested());
        boolean stoppedMovement = "move".equals(operation.action) && Boolean.TRUE.equals(outcome.facts().get("movement_stopped"))
                && !Boolean.TRUE.equals(outcome.facts().get("completed_duration"));
        fields.put("cancel_too_late", operation.dispatchStarted && operation.cancelRequested && !stoppedMovement);
        operation.result = fields;
        operation.error = outcome.outcome() == NativeActionResult.Outcome.SUCCEEDED
                || outcome.outcome() == NativeActionResult.Outcome.CANCELLED ? null
                : Map.of("reason", reason == null ? observed : reason);
        finish(operation, outcome.outcome().name(), reason == null ? observed : reason);
    }

    private void basicFailedBeforeDispatch(Operation operation, String reason) {
        operation.result = Map.of("observed_outcome", "action_not_dispatched", "native_dispatch_started", false,
                "cancel_too_late", false);
        operation.error = Map.of("reason", reason);
        finish(operation, "FAILED", reason);
    }

    private void basicResultUnavailable(Operation operation, boolean started) {
        operation.dispatchStarted = started;
        if (!started && actionCancelled(operation.session, operation)) cancelledBeforeAction(operation);
        else if (!started) basicFailedBeforeDispatch(operation, "native_basic_result_unavailable");
        else {
            operation.result = Map.of("observed_outcome", "native_" + operation.action + "_outcome_unknown",
                    "native_dispatch_started", true, "cancel_too_late", operation.cancelRequested);
            operation.error = Map.of("reason", "native_basic_outcome_unknown");
            finish(operation, "INDETERMINATE", "native_basic_outcome_unknown");
        }
    }

    private void validateActionEntry(Session session, Operation operation, NativeActorHandle handle,
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
            synchronized (monitor) { operation.actionEntryFailure = safeReason(failure, "native_action_validation_failed"); }
            throw failure;
        } catch (RuntimeException unavailable) {
            synchronized (monitor) { operation.actionEntryFailure = "actor_observation_unavailable"; }
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
        }
    }

    private void chatCompleted(Operation operation, NativeActionService.ActionHandle source,
                               NativeActionResult result, Throwable failure) {
        CharacterRuntime.ModuleContext acceptedContext;
        ExecutionScope.WorkHandle acceptedWork;
        synchronized (monitor) {
            if (operation.chatAction != source || terminal(operation.status)) return;
            acceptedContext = operation.moduleContext;
            acceptedWork = operation.work;
        }
        if (failure == null && result != null && acceptedContext != null && acceptedWork != null) {
            try { acceptedContext.recordResult(acceptedWork, result); }
            catch (RuntimeException diagnosticFailure) {
                // Recording a late fact must not erase the native result or restore control authority.
            }
        }
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
        String reason = outcome.dispatch() == NativeActionResult.Dispatch.NOT_STARTED && operation.actionEntryFailure != null
                ? operation.actionEntryFailure : AcceptanceChatResults.errorReason(outcome);
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
        if (ActorBasicRequests.supports(action)) return; // Typed parameters are checked by validateBasic.
        switch (action) {
            case "chat" -> backend.validateChat(handle, text);
            case "inventory_move" -> { /* The typed inventory parameters are validated separately. */ }
            case "npc_center_open" -> backend.validateNpcCenterOpen(handle);
            case "npc_cancel" -> backend.validateNpcCancel(handle, dialogueVersion);
            case "npc_reply" -> backend.validateNpcReply(handle, dialogueVersion, replyKind, selection);
            default -> throw error(HttpStatus.BAD_REQUEST, "unsupported_action");
        }
    }

    private static void validateActionParameters(String action, ActorActRequest.Parameters parameters) {
        if (ActorBasicRequests.supports(action)) {
            ActorBasicRequests.validate(action, parameters);
            return;
        }
        if (action == null || !List.of("chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move").contains(action)) {
            throw error(HttpStatus.BAD_REQUEST, "unsupported_action");
        }
        if (parameters == null) throw error(HttpStatus.BAD_REQUEST, "invalid_action_parameters");
        if ("inventory_move".equals(action)) {
            inventoryMove(parameters);
            return;
        }
        if ("npc_cancel".equals(action) || "npc_reply".equals(action)) {
            if (parameters.dialogueVersion() == null || parameters.dialogueVersion() < 1) {
                throw error(HttpStatus.BAD_REQUEST, "invalid_dialogue_version");
            }
            if ("npc_reply".equals(action)) {
                if (parameters.kind() == null || !List.of("select", "decline").contains(parameters.kind())) {
                    throw error(HttpStatus.BAD_REQUEST, "unsupported_reply_kind");
                }
                if ("select".equals(parameters.kind()) && (parameters.selection() == null || parameters.selection() < 0)) {
                    throw error(HttpStatus.BAD_REQUEST, "invalid_npc_selection");
                }
            }
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
                session.closeEvidence = copy(cleanup);
                session.releaseRequested = true;
                session.releaseState = "CLOSED";
                if (operation != null) {
                    Map<String, Object> result = copy(cleanup);
                    result.put("observed_outcome", "native_actor_closed");
                    result.put("cancel_too_late", operation.cancelRequested);
                    operation.result = result;
                    operation.step = "native_actor_close_waiting_settlement";
                    event(operation, operation.step);
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
            if (backend.isCurrent(handle)) snapshot = actorSnapshot(session, handle);
        } catch (ObservationException failure) {
            synchronized (monitor) {
                if (session.handle != handle || !"OPEN".equals(session.state)) return;
            }
            throw failure;
        } catch (RuntimeException ignored) {
            synchronized (monitor) {
                if (session.handle != handle || !"OPEN".equals(session.state)) return;
            }
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

    private Map<String, Object> actorSnapshot(Session session, NativeActorHandle handle) {
        Map<String, Object> result = copy(session.execution.observeOrigin());
        if (observations != null) result.put("scene", observations.contextForActor(handle.characterRef()));
        return result;
    }

    private void release(Session session, String state) {
        session.state = state;
        session.lastActor = null;
        session.handle = null;
        session.releaseRequested = true;
        session.releaseState = state;
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
            try {
                operation.work = "OPEN".equals(kind) ? session.execution.acceptOpeningWork() : session.execution.acceptWork();
                if ("ACTION".equals(kind) || "SCRIPT".equals(kind)) operation.moduleContext = session.execution.moduleContext();
                if ("OPEN".equals(kind)) {
                    ExecutionScope.WorkHandle exact = operation.work;
                    exact.completion().whenComplete((fact, failure) -> rootCompleted(session));
                }
            }
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
            attempt.work().completion().whenComplete((fact, failure) -> closeCompleted(session, operation, attempt.work()));
            session.execution.submitCleanup(attempt, () -> runClose(session, operation, exactHandle, attempt),
                    () -> cleanupSettled(session, operation, attempt.work())).whenComplete((submission, failure) -> {
                if (failure != null) {
                    if (!attempt.work().snapshot().carrierClaimed()) attempt.work().reject();
                    synchronized (monitor) { cleanupSubmissionFailed(session, operation, "actor_close_cleanup_unverified_scope"); }
                } else if (submission.status() == CharacterCommandLane.Status.REJECTED
                        || submission.status() == CharacterCommandLane.Status.CLEANUP_PENDING
                        || submission.status() == CharacterCommandLane.Status.DUPLICATE) {
                    synchronized (monitor) { cleanupSubmissionFailed(session, operation, "actor_queue_full"); }
                }
            });
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
            boolean nativeVerified = session.closeEvidence != null && session.execution.resourceReleased();
            if (work.snapshot().rejected() || !nativeVerified && (operation != null && !terminal(operation.status)
                    || operation == null && session.cleanupScheduled)) {
                cleanupSubmissionFailed(session, operation, "actor_close_cleanup_unverified_scope");
            }
            if (operation != null && !nativeVerified && terminal(operation.status)) operation.work = null;
            released = session.releaseRequested && session.execution.resourceReleased();
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
            released = session.releaseRequested && session.execution.resourceReleased();
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
            if ("inventory_move".equals(operation.action) && !terminal(operation.status)) {
                inventoryResultUnavailable(operation, nativeDispatchStarted(operation));
            }
            if (ActorBasicRequests.supports(operation.action) && !terminal(operation.status)) {
                basicResultUnavailable(operation, nativeDispatchStarted(operation));
            }
            releaseActionSlot(operation);
            operation.work = null;
            operation.chatAction = null;
            operation.inventoryAction = null;
            operation.basicAction = null;
            operation.moduleContext = null;
            monitor.notifyAll();
        }
    }

    private static void closeReleasedLane(Session session) {
        session.execution.beginDrain(Optional.empty());
        session.execution.closeLane();
    }

    private void rootCompleted(Session session) {
        boolean closed = session.execution.finalized();
        synchronized (monitor) {
            if (closed && session.releaseRequested) releaseFinalized(session);
        }
    }

    private void closeCompleted(Session session, Operation operation, ExecutionScope.WorkHandle exactWork) {
        boolean closed = session.execution.finalized();
        synchronized (monitor) {
            if (session.cleanupWork != exactWork) return;
            if (closed && session.closeEvidence != null) {
                if (operation != null && !terminal(operation.status)) {
                    Map<String, Object> result = copy(session.closeEvidence);
                    result.put("observed_outcome", "native_actor_closed");
                    result.put("cancel_too_late", operation.cancelRequested);
                    operation.result = result;
                    operation.error = null;
                    finish(operation, "SUCCEEDED", "native_actor_closed");
                }
                releaseFinalized(session);
            } else if (session.closeEvidence != null && operation != null && !terminal(operation.status)) {
                cleanupSubmissionFailed(session, operation, "native_actor_cleanup_unverified");
            }
            if (operation != null && operation.work == exactWork) operation.work = null;
            if (session.cleanupWork == exactWork && exactWork.isSettled()) {
                session.cleanupScheduled = false;
                session.cleanupWork = null;
            }
            monitor.notifyAll();
        }
    }

    private void releaseFinalized(Session session) {
        session.state = session.releaseState;
        session.lastActor = null;
        session.handle = null;
        profiles.remove(session.profile, session);
        session.cleanupScheduled = false;
        monitor.notifyAll();
    }

    /** A terminal operation is not permission to reuse a cleanup whose carrier is still settling. */
    private static boolean cleanupInFlight(Session session) {
        if (session.cleanupScheduled && session.cleanupWork != null && session.cleanupWork.isSettled()) {
            // A verified close is finalized by its exact completion callback; do not steal that reference in a stop race.
            if (session.closeEvidence != null && session.releaseRequested) return true;
            session.cleanupScheduled = false;
            session.cleanupWork = null;
        }
        return session.cleanupScheduled;
    }

    private void requestCancellation(Operation operation) {
        operation.session.execution.cancel(operation.work);
        if (operation.nativeScriptExecution != null) operation.nativeScriptExecution.cancel();
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
        if (!terminal(operation.status) && !operation.cancelRequested && nativeDispatchStarted(operation)
                && ActorBasicRequests.supports(operation.action) && !"native_basic_dispatch_started".equals(operation.step)) {
            running(operation, "native_basic_dispatch_started");
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
        sessionData.put("runtime", session.execution.diagnostics());
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
        if ("SCRIPT".equals(operation.kind)) response.put("script_output", scriptOutputWindow(operation, 0));
        return response;
    }

    private Map<String, Object> scriptOutputWindow(Operation operation, long sinceOutput) {
        long oldest = operation.output.isEmpty() ? operation.outputSequence + 1 : (long) operation.output.getFirst().get("seq");
        List<Map<String, Object>> items = operation.output.stream()
                .filter(item -> (long) item.get("seq") > sinceOutput).toList();
        long cursor = items.isEmpty() ? sinceOutput : (long) items.getLast().get("seq");
        return Map.of("items", items, "next_cursor", cursor, "oldest_cursor", oldest,
                "latest_cursor", operation.outputSequence, "cursor_expired", sinceOutput < oldest - 1,
                "output_truncated", operation.outputTruncated);
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
        if (operation.inventoryAction != null && operation.inventoryAction.dispatchStarted()) operation.dispatchStarted = true;
        if (operation.basicAction != null && operation.basicAction.dispatchStarted()) operation.dispatchStarted = true;
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
    private record ScriptFingerprint(long generation, String sourceDigest, String parametersDigest, long timeoutMs,
                                     String invocationDigest) { }
    private record ActionFingerprint(long generation, String action, String textHash, Long dialogueVersion,
                                     String replyKind, Integer selection, String inventoryType, Integer sourceSlot,
                                     Integer targetSlot, String direction, Integer durationMs, Integer targetOid,
                                     Integer objectId, Integer quantity, ActorActRequest.ExpectedContext context) { }
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
        boolean releaseRequested;
        String releaseState;
        Map<String, Object> closeEvidence;
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
            this.execution = new AcceptanceActorExecution(backend, id, admittedEpoch, executor);
        }
    }
    private static final class Operation {
        final String id;
        final String kind;
        final Session session;
        final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();
        final ArrayDeque<Map<String, Object>> output = new ArrayDeque<>();
        String status = "QUEUED";
        String step = "queued";
        boolean cancelRequested;
        boolean dispatchStarted;
        boolean actionSlotHeld;
        ExecutionScope.WorkHandle work;
        NativeActionService.ActionHandle chatAction;
        NativeInventoryActions.MoveHandle inventoryAction;
        NativeScriptExecution nativeScriptExecution;
        String nativeScriptOperation;
        String scriptRequestId;
        long outputSequence;
        boolean outputTruncated;
        NativeBasicActions.Handle basicAction;
        CharacterRuntime.ModuleContext moduleContext;
        String actionEntryFailure;
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
