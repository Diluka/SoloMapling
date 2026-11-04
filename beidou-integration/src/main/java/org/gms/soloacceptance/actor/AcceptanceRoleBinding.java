package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.game.context.GameContext;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.RoleBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Ordinary MCP origin policy around one exact native handle; runtime owns its resource ledger. */
final class AcceptanceRoleBinding implements RoleBinding {
    private final NativeActorBackend backend;
    private final NativeActorHandle handle;
    private volatile Map<String, Object> cleanupEvidence;

    AcceptanceRoleBinding(NativeActorBackend backend, NativeActorHandle handle) {
        this.backend = Objects.requireNonNull(backend);
        this.handle = Objects.requireNonNull(handle);
    }

    @Override public String kind() { return "mcp_acceptance"; }
    @Override public Object identity() { return handle; }
    @Override public GameContext.CharacterRef characterRef() { return handle.characterRef(); }
    @Override public void requireCurrent() { backend.requireCurrentRole(handle); }

    @Override public Map<String, Object> observe() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(backend.snapshot(handle)));
    }

    @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
        AcceptanceActorContexts.requireDrainPermit(handle, permit);
        if (cleanupEvidence == null) {
            Map<String, Object> evidence = backend.close(handle, permit);
            if (evidence == null || !Boolean.TRUE.equals(evidence.get("native_cleanup_verified"))
                    || !Boolean.TRUE.equals(evidence.get("persisted_state_verified"))) throw unverified();
            cleanupEvidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        }
        return new ReleaseProof(true);
    }

    Map<String, Object> cleanupEvidence() {
        Map<String, Object> exact = cleanupEvidence;
        return exact == null ? null : new LinkedHashMap<>(exact);
    }

    private static ObservationException unverified() {
        return new ObservationException(HttpStatus.CONFLICT, "native_actor_cleanup_unverified");
    }
}
