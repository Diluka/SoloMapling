package org.gms.soloacceptance.actor;

import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** One MCP session's thin ownership adapter; operations and policy remain in ActorControlService. */
final class AcceptanceActorExecution {
    private final NativeActorBackend backend;
    private final ExecutionScope scope;
    private volatile CharacterCommandLane lane;
    private volatile CharacterCommandLane.Grant grant;
    private volatile CharacterCommandLane.AdmissionPermit admission;
    // The factory creates this session-private executor. The lane borrows the same queue, never another one.
    private final ExecutorService ownedExecutor;
    private final Object ownershipMonitor = new Object();
    private volatile CharacterCommandLane.ControlPermit control;
    private volatile ScopeResources.Registration registration;
    private volatile NativeActorHandle adopted;
    private volatile Map<String, Object> cleanupEvidence;
    private World originWorld;
    private Channel originChannel;

    AcceptanceActorExecution(NativeActorBackend backend, Object sessionIdentity, WorldEpoch.Token acceptedEpoch,
                             ExecutorService executor) {
        this.backend = backend;
        this.ownedExecutor = executor;
        scope = new ExecutionScope(acceptedEpoch, new ExecutionScope.OwnerStamp(sessionIdentity, new Object()),
                new ExecutionScope.Limits(16, 1, 16, 1));
        try {
            lane = new CharacterCommandLane(8, 1, executor);
            grant = lane.declareGrant(scope);
            admission = lane.admissionPermit(scope);
        } catch (RuntimeException failure) {
            if (lane != null) lane.close();
            ownedExecutor.shutdown();
            WorldEpoch.State current = WorldEpoch.snapshot();
            if ((!current.active() || current.token() != acceptedEpoch)
                    && (failure instanceof IllegalStateException && "scope_not_current".equals(failure.getMessage())
                    || failure instanceof ExecutionScope.Invalid invalid && "scope_not_active".equals(invalid.reason()))) {
                throw new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, "actor_world_offline");
            }
            throw failure;
        }
    }

    ExecutionScope scope() { return scope; }
    ExecutionScope.WorkHandle acceptWork() { return scope.acceptWork(); }
    void cancel(ExecutionScope.WorkHandle work) { if (work != null) scope.requestCancel(work); }
    NativeActorHandle adoptedHandle() { return adopted; }
    boolean resourceReleased() { return scope.resources().snapshot().outstanding() == 0; }

    NativeActorBackend.OpenOwnership beginOpen(ExecutionScope.WorkHandle work) {
        ScopeResources.Registration owned = scope.resources().beginOwnedResult(work, "native_actor");
        registration = owned;
        return new NativeActorBackend.OpenOwnership() {
            @Override public void bindOrigin(World world, Channel channel) {
                owned.requirePendingCleanupOrigin();
                synchronized (ownershipMonitor) {
                    if (originWorld != null && (originWorld != world || originChannel != channel)) throw unverified();
                    originWorld = world;
                    originChannel = channel;
                }
            }

            @Override public void adopt(NativeActorHandle handle) {
                synchronized (ownershipMonitor) {
                    if (originWorld == null || handle.world() != originWorld || handle.channel() != originChannel
                            || adopted != null && adopted != handle) throw unverified();
                    adopted = handle;
                }
                owned.attach(handle, () -> { /* The service schedules cleanup on the same lane. */ });
            }

            @Override public void rollback(NativeActorHandle handle) {
                if (adopted != handle) throw unverified();
                rollbackInline();
            }

            @Override public void confirmAbsent() { owned.confirmAbsent(); }

            @Override public void creationFailed(String reason) {
                if (adopted == null && "PENDING".equals(owned.state())) owned.creationFailed(reason);
            }

            @Override public void requireOrigin(World world, Channel channel) {
                synchronized (ownershipMonitor) {
                    if (originWorld == null || originWorld != world || originChannel != channel) throw unverified();
                }
                owned.requirePendingCleanupOrigin();
            }
        };
    }

    CharacterCommandLane.Submission submitOpen(ExecutionScope.WorkHandle work, Runnable body, Runnable settled) {
        return lane.submitInitialization(work, admission, body, settled);
    }

    void bindActor(NativeActorHandle handle) {
        if (adopted != handle) throw unverified();
        control = lane.bindGrantedPermit(scope, handle.characterRef(), grant, () -> {
            if (!backend.isCurrent(handle)) {
                throw new ObservationException(HttpStatus.CONFLICT, "actor_session_stale");
            }
        });
    }

    CharacterCommandLane.Submission submitAction(ExecutionScope.WorkHandle work, Runnable body, Runnable settled) {
        CharacterCommandLane.ControlPermit permit = control;
        if (permit == null) throw unverified();
        return lane.submit(work, permit, body, settled);
    }

    NativeActionService.ActionHandle prepareChat(ExecutionScope.WorkHandle work, NativeActorHandle exactHandle,
                                                String text, Runnable mcpPreflight) {
        CharacterCommandLane currentLane = lane;
        CharacterCommandLane.Grant currentGrant = grant;
        if (adopted != exactHandle || currentLane == null || currentGrant == null || control == null) {
            throw new ObservationException(HttpStatus.CONFLICT, "actor_session_stale");
        }
        CharacterCommandLane.ControlPermit permit = currentLane.bindGrantedPermit(scope,
                exactHandle.characterRef(), currentGrant, mcpPreflight);
        return backend.prepareChat(exactHandle, currentLane, permit, work, new NativeChatAction(text));
    }

    NativeActionService.SubmissionReceipt submitChat(NativeActionService.ActionHandle action) {
        return backend.submitChat(action);
    }

    boolean publishOpen(ExecutionScope.WorkHandle work, Runnable apply) { return scope.publishOnce(work, apply); }

    void beginDrain(Optional<ExecutionScope.WorldStopTicket> stop) {
        // A stop broadcast may include old quarantines; it cannot renew their cleanup window.
        stop.filter(ticket -> ticket.sourceEpoch() == scope.acceptedEpoch()).ifPresent(scope::joinWorldStop);
        scope.beginDrain();
        CharacterCommandLane.Grant current = grant;
        if (current != null) current.revoke();
    }

    ScopeResources.CleanupAttempt cleanupAttempt() {
        ScopeResources.Registration owned = registration;
        if (owned == null || adopted == null) throw unverified();
        return owned.acquireCleanup();
    }

    CharacterCommandLane.Submission submitCleanup(ScopeResources.CleanupAttempt attempt, Runnable body, Runnable settled) {
        return lane.submitOwnedCleanup(attempt, body, settled);
    }

    Map<String, Object> cleanup(ScopeResources.CleanupAttempt attempt, NativeActorHandle exactHandle) {
        if (adopted != exactHandle || registration == null) throw unverified();
        Map<String, Object> evidence = backend.close(exactHandle, attempt.permit());
        if (evidence == null || !Boolean.TRUE.equals(evidence.get("native_cleanup_verified"))
                || !Boolean.TRUE.equals(evidence.get("persisted_state_verified"))) throw unverified();
        registration.confirmReleased(attempt.permit(), exactHandle);
        cleanupEvidence = new LinkedHashMap<>(evidence);
        return new LinkedHashMap<>(evidence);
    }

    Map<String, Object> rollbackInline() {
        Map<String, Object> verified = cleanupEvidence;
        if (verified != null) return new LinkedHashMap<>(verified);
        NativeActorHandle exactHandle = adopted;
        ScopeResources.CleanupAttempt attempt = cleanupAttempt();
        AtomicReference<Map<String, Object>> outcome = new AtomicReference<>();
        scope.runTracked(attempt.work(), () -> outcome.set(cleanup(attempt, exactHandle)));
        verified = outcome.get();
        if (verified == null) verified = cleanupEvidence;
        if (verified == null) throw unverified();
        return new LinkedHashMap<>(verified);
    }

    void closeLane() {
        CharacterCommandLane current = lane;
        if (current != null) current.close();
        ownedExecutor.shutdown();
        if (resourceReleased()) {
            lane = null;
            grant = null;
            admission = null;
            control = null;
            registration = null;
            adopted = null;
            synchronized (ownershipMonitor) { originWorld = null; originChannel = null; }
        }
    }

    ExecutionScope.DrainResult awaitDrained(Duration timeout) { return scope.awaitDrained(timeout); }

    private static ObservationException unverified() {
        return new ObservationException(HttpStatus.CONFLICT, "native_actor_cleanup_unverified");
    }
}
