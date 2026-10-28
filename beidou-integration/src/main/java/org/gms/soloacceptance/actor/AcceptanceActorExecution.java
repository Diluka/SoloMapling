package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.action.NativeInventoryActions;
import soloMapling.game.action.NativeInventoryMoveAction;
import soloMapling.game.action.NativeBasicActions;
import soloMapling.game.action.NativeMovementActions;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RuntimeEvents;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionStage;

/** MCP consumes one host. Native ownership, scopes, grants, the lane and its executor belong to that host. */
final class AcceptanceActorExecution {
    private final NativeActorBackend backend;
    private final CharacterRuntime runtime;
    private final String moduleInstance;
    private volatile AcceptanceRuntimeOrigin origin;
    private volatile McpControlModule module;
    private volatile Map<String, Object> cleanupEvidence;
    private volatile Map<String, Object> diagnostics = Map.of();
    private long moduleGeneration;

    AcceptanceActorExecution(NativeActorBackend backend, String moduleInstance, WorldEpoch.Token epoch,
                             ExecutorService executor) {
        this.backend = backend;
        this.moduleInstance = moduleInstance;
        runtime = new CharacterRuntime(epoch, new CharacterRuntime.Options(8, 1,
                new ExecutionScope.Limits(16, 1, 16, 1), 64, 1), executor);
        refreshDiagnostics();
    }

    CharacterRuntime runtime() { return runtime; }
    ExecutionScope scope() { return runtime.lifecycleScope(); }
    ExecutionScope moduleScope() { return requireModule().context().scope(); }
    CharacterRuntime.ModuleContext moduleContext() { return requireModule().context(); }

    ExecutionScope.WorkHandle acceptOpeningWork() {
        ExecutionScope.WorkHandle work = runtime.acceptOpeningWork();
        trackRoot(work);
        return work;
    }

    ExecutionScope.WorkHandle acceptWork() {
        try { return requireModule().acceptWork(); }
        catch (CharacterRuntime.Invalid failure) { throw controlFailure(failure); }
    }

    void cancel(ExecutionScope.WorkHandle work) {
        if (work == null) return;
        if (work.scope() == runtime.lifecycleScope()) runtime.lifecycleScope().requestCancel(work);
        else requireModule().context().cancel(work);
    }

    NativeActorHandle adoptedHandle() {
        AcceptanceRuntimeOrigin source = origin;
        return source == null ? null : source.adoptedHandle();
    }

    boolean resourceReleased() { return runtime.originReleased(); }

    NativeActorBackend.OpenOwnership beginOpen(ExecutionScope.WorkHandle work) {
        AcceptanceRuntimeOrigin source = new AcceptanceRuntimeOrigin(runtime, backend, work);
        origin = source;
        return source;
    }

    CharacterCommandLane.Submission submitOpen(ExecutionScope.WorkHandle work, Runnable body, Runnable settled) {
        return runtime.submitOpening(work, body, settled);
    }

    void bindActor(NativeActorHandle exactHandle) {
        if (adoptedHandle() != exactHandle) throw unverified();
        runtime.bindAdoptedOrigin();
        McpControlModule attached = new McpControlModule(moduleInstance);
        module = attached;
        CharacterRuntime.ModuleRef reference = runtime.mount(attached.spec(), attached, true);
        runtime.context(reference); // Mounting without an active context is not OPEN success.
        moduleGeneration = reference.generation();
        refreshDiagnostics();
    }

    CharacterCommandLane.Submission submitAction(ExecutionScope.WorkHandle work, Runnable body, Runnable settled) {
        return requireModule().submitInteraction(work, () -> { }, body, settled);
    }

    NativeActionService.ActionHandle prepareChat(ExecutionScope.WorkHandle work, NativeActorHandle exactHandle,
                                                String text, Runnable mcpPreflight) {
        if (adoptedHandle() != exactHandle) throw new ObservationException(HttpStatus.CONFLICT, "actor_session_stale");
        return requireModule().prepareChat(work, mcpPreflight, (lane, permit, original, character) ->
                backend.prepareChat(exactHandle, lane, permit, original, new NativeChatAction(text)));
    }

    NativeActionService.SubmissionReceipt submitChat(NativeActionService.ActionHandle action) {
        return backend.submitChat(action);
    }

    NativeInventoryActions.MoveHandle prepareInventoryMove(ExecutionScope.WorkHandle work, NativeActorHandle exactHandle,
                                                           NativeInventoryMoveAction action, Runnable mcpPreflight) {
        if (adoptedHandle() != exactHandle) throw new ObservationException(HttpStatus.CONFLICT, "actor_session_stale");
        return requireModule().prepareInventoryMove(work, mcpPreflight, (lane, permit, original, character) ->
                backend.prepareInventoryMove(exactHandle, lane, permit, original, action));
    }

    NativeActionService.SubmissionReceipt submitInventoryMove(NativeInventoryActions.MoveHandle action) {
        return backend.submitInventoryMove(action);
    }

    NativeBasicActions.Handle prepareBasic(ExecutionScope.WorkHandle work, NativeActorHandle exactHandle,
                                           String action, ActorActRequest.Parameters parameters, Runnable mcpPreflight) {
        if (adoptedHandle() != exactHandle) throw new ObservationException(HttpStatus.CONFLICT, "actor_session_stale");
        McpControlModule active = requireModule();
        if ("move".equals(action) || "stop".equals(action)) {
            return backend.prepareMovement(exactHandle, active.movement(), active.context(), work,
                    action, parameters, mcpPreflight);
        }
        return active.prepareBasic(ActorBasicRequests.control(action), work, mcpPreflight,
                (lane, permit, original, character) -> backend.prepareBasic(exactHandle, lane, permit, original, action, parameters));
    }

    NativeActionService.SubmissionReceipt submitBasic(NativeBasicActions.Handle action) {
        return backend.submitBasic(action, requireModule().movement());
    }

    void recordResult(ExecutionScope.WorkHandle work, NativeActionResult result) {
        requireModule().recordResult(work, result);
    }

    Map<String, Object> observeOrigin() {
        try {
            Map<String, Object> facts = new LinkedHashMap<>(runtime.observeOrigin());
            refreshDiagnostics();
            return facts;
        } catch (ObservationException known) { throw known; }
        catch (ExecutionScope.Invalid | CharacterRuntime.Invalid changed) {
            WorldEpoch.State current = WorldEpoch.snapshot();
            if (!current.active()) throw new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, "actor_world_offline");
            if (current.token() != runtime.acceptedEpoch()) throw new ObservationException(HttpStatus.GONE, "stale_actor_session");
            throw new ObservationException(HttpStatus.CONFLICT, "actor_context_mismatch");
        }
    }

    boolean publishOpen(ExecutionScope.WorkHandle work, Runnable apply) {
        return runtime.lifecycleScope().publishOnce(work, apply);
    }

    void beginDrain(Optional<ExecutionScope.WorldStopTicket> stop) {
        runtime.beginClose(stop);
        refreshDiagnostics();
    }

    ScopeResources.CleanupAttempt cleanupAttempt() {
        ScopeResources.CleanupAttempt attempt = runtime.acquireOriginCleanup();
        trackRoot(attempt.work());
        return attempt;
    }

    CompletionStage<CharacterCommandLane.Submission> submitCleanup(ScopeResources.CleanupAttempt attempt, Runnable body, Runnable settled) {
        // A movement clock can still own work while the native lane is idle. Root cleanup
        // waits for the host's factual module drain, without blocking that same lane.
        return runtime.whenModulesDrained().thenApply(ignored -> runtime.submitOriginCleanup(attempt, body, settled));
    }

    Map<String, Object> cleanup(ScopeResources.CleanupAttempt attempt, NativeActorHandle exactHandle) {
        if (adoptedHandle() != exactHandle) throw unverified();
        if (!runtime.releaseOrigin(attempt).released()) throw unverified();
        return rememberCleanup();
    }

    Map<String, Object> rollbackInline() {
        runtime.beginClose(Optional.empty());
        AcceptanceRuntimeOrigin source = origin;
        if (source == null) throw unverified();
        Map<String, Object> proof = source.rollbackInline();
        cleanupEvidence = new LinkedHashMap<>(proof);
        refreshDiagnostics();
        return new LinkedHashMap<>(proof);
    }

    void closeLane() { // Compatibility name: only the host can close its lane.
        runtime.beginClose(Optional.empty());
        finalized();
    }

    boolean finalized() {
        boolean closed = runtime.tryFinalizeClose();
        refreshDiagnostics();
        if (closed) { origin = null; module = null; }
        return closed;
    }

    ExecutionScope.DrainResult awaitDrained(Duration timeout) {
        ExecutionScope.DrainResult result = runtime.awaitClosed(timeout);
        finalized();
        return result;
    }

    Map<String, Object> diagnostics() { return diagnostics; }
    void refresh() { refreshDiagnostics(); }

    private void trackRoot(ExecutionScope.WorkHandle work) {
        work.completion().whenComplete((ignored, failure) -> finalized());
    }

    private Map<String, Object> rememberCleanup() {
        AcceptanceRuntimeOrigin source = origin;
        Map<String, Object> proof = source == null ? cleanupEvidence : source.cleanupEvidence();
        if (proof == null || !runtime.originReleased()) throw unverified();
        cleanupEvidence = new LinkedHashMap<>(proof);
        return new LinkedHashMap<>(proof);
    }

    private McpControlModule requireModule() {
        McpControlModule attached = module;
        if (attached == null) throw new ObservationException(HttpStatus.CONFLICT, "actor_not_actable");
        return attached;
    }

    private ObservationException controlFailure(CharacterRuntime.Invalid failure) {
        WorldEpoch.State current = WorldEpoch.snapshot();
        if (!current.active() || current.token() != runtime.acceptedEpoch()) {
            return new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, "actor_world_offline");
        }
        return new ObservationException(HttpStatus.CONFLICT, "actor_not_actable");
    }

    private void refreshDiagnostics() {
        CharacterRuntime.Snapshot state = runtime.snapshot();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", state.runtimeId()); info.put("epoch", state.epoch());
        info.put("state", state.state().name()); info.put("origin_kind", state.originKind());
        info.put("character_id", state.characterId());
        Map<String, Object> attached = new LinkedHashMap<>();
        attached.put("type_id", "mcp-control"); attached.put("instance_id", moduleInstance);
        CharacterRuntime.ModuleSnapshot selected = state.modules().stream()
                .filter(entry -> entry.instanceId().equals(moduleInstance)).findFirst().orElse(null);
        if (selected != null) moduleGeneration = selected.generation();
        attached.put("generation", moduleGeneration);
        attached.put("state", selected == null ? moduleGeneration == 0 ? "NOT_ATTACHED" : "UNLOADED" : selected.state().name());
        info.put("module", java.util.Collections.unmodifiableMap(attached));
        info.put("lane", Map.of("pending", state.lane().pending(), "ordinary", state.lane().ordinary(),
                "cleanup", state.lane().cleanup(), "closed", state.lane().closed()));
        RuntimeEvents.Window window = runtime.events(0, 64);
        List<Map<String, Object>> items = window.events().stream().map(event -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", event.sequence()); item.put("kind", event.kind().name());
            item.put("runtime_id", event.runtimeId()); item.put("module_id", event.moduleId());
            item.put("module_generation", event.moduleGeneration()); item.put("work_id", event.workId());
            item.put("reason", event.reason()); item.put("outcome", event.outcome());
            return java.util.Collections.unmodifiableMap(item);
        }).toList();
        info.put("events", Map.of("cursor_scope", "runtime_event_sequence", "items", items,
                "next_cursor", window.nextCursor(), "cursor_expired", window.cursorExpired(), "truncated", window.truncated()));
        diagnostics = java.util.Collections.unmodifiableMap(info);
    }

    private static ObservationException unverified() {
        return new ObservationException(HttpStatus.CONFLICT, "native_actor_cleanup_unverified");
    }
}
