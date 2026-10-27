package org.gms.soloacceptance.actor;

import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;

import java.util.Map;
import java.util.Objects;

/** The native OPEN factory's one handshake, wholly owned by the runtime root. */
final class AcceptanceRuntimeOrigin implements NativeActorBackend.OpenOwnership {
    private final CharacterRuntime runtime;
    private final NativeActorBackend backend;
    private final CharacterRuntime.OriginResource origin;
    private final Object monitor = new Object();
    private volatile AcceptanceRoleBinding binding;
    private World originWorld;
    private Channel originChannel;

    AcceptanceRuntimeOrigin(CharacterRuntime runtime, NativeActorBackend backend,
                            ExecutionScope.WorkHandle openingWork) {
        this.runtime = Objects.requireNonNull(runtime);
        this.backend = Objects.requireNonNull(backend);
        origin = runtime.reserveOrigin(openingWork);
    }

    @Override public void bindOrigin(World world, Channel channel) {
        origin.requirePendingCleanupOrigin();
        synchronized (monitor) {
            if (world == null || channel == null || originWorld != null
                    && (originWorld != world || originChannel != channel)) throw unverified();
            originWorld = world;
            originChannel = channel;
        }
    }

    @Override public void adopt(NativeActorHandle exactHandle) {
        Objects.requireNonNull(exactHandle);
        AcceptanceRoleBinding exact;
        synchronized (monitor) {
            if (originWorld == null || exactHandle.world() != originWorld || exactHandle.channel() != originChannel
                    || binding != null && binding.identity() != exactHandle) throw unverified();
            if (binding == null) binding = new AcceptanceRoleBinding(backend, exactHandle);
            exact = binding;
        }
        // Retain the known source debt before the runtime performs any further native/currentness checks.
        // The original handle/ref is never refreshed or coerced to a different world epoch here.
        origin.adopt(exact);
    }

    @Override public void rollback(NativeActorHandle exactHandle) {
        if (adoptedHandle() != exactHandle) throw unverified();
        rollbackInline();
    }

    @Override public void confirmAbsent() {
        if (binding != null) throw unverified();
        origin.confirmAbsent();
    }

    @Override public void creationFailed(String fixedReason) {
        if (binding == null) origin.creationFailed(fixedReason);
    }

    @Override public void requireOrigin(World world, Channel channel) {
        synchronized (monitor) {
            if (originWorld == null || originWorld != world || originChannel != channel) throw unverified();
        }
        origin.requirePendingCleanupOrigin();
    }

    NativeActorHandle adoptedHandle() {
        AcceptanceRoleBinding exact = binding;
        return exact == null ? null : (NativeActorHandle) exact.identity();
    }

    Map<String, Object> cleanupEvidence() {
        AcceptanceRoleBinding exact = binding;
        return exact == null ? null : exact.cleanupEvidence();
    }

    Map<String, Object> rollbackInline() {
        Map<String, Object> verified = cleanupEvidence();
        if (verified != null && runtime.originReleased()) return verified;
        if (binding == null) throw unverified();
        ScopeResources.CleanupAttempt attempt = runtime.acquireOriginCleanup();
        runtime.lifecycleScope().runTracked(attempt.work(), () -> runtime.releaseOrigin(attempt));
        verified = cleanupEvidence();
        if (verified == null || !runtime.originReleased()) throw unverified();
        return verified;
    }

    private static ObservationException unverified() {
        return new ObservationException(HttpStatus.CONFLICT, "native_actor_cleanup_unverified");
    }
}
