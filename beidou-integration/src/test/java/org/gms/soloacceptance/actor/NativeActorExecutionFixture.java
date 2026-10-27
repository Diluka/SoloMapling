package org.gms.soloacceptance.actor;

import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Actual accepted work and owned-result registration for the native adapter contract tests. */
final class NativeActorExecutionFixture implements NativeActorBackend.OpenOwnership {
    final ExecutionScope scope = new ExecutionScope(WorldEpoch.snapshot().token(),
            new ExecutionScope.OwnerStamp(new Object(), new Object()), new ExecutionScope.Limits(32, 8, 16, 1));
    final ExecutionScope.WorkHandle openWork = scope.acceptWork();
    private ScopeResources.Registration registration;
    private World world;
    private Channel channel;
    private NativeActorHandle handle;
    private NativeActorBackend backend;

    static NativeActorExecutionFixture owned(NativeActorHandle handle) {
        NativeActorExecutionFixture fixture = new NativeActorExecutionFixture();
        fixture.open(() -> {
            fixture.bindOrigin(handle.world(), handle.channel());
            fixture.adopt(handle);
            return handle;
        });
        return fixture;
    }

    <T> T open(Supplier<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        scope.runTracked(openWork, () -> {
            registration = scope.resources().beginOwnedResult(openWork, "native_actor");
            result.set(body.get());
        });
        return result.get();
    }

    NativeActorHandle open(NativeActorBackend backend, java.util.function.BooleanSupplier cancelled) {
        this.backend = backend;
        return open(() -> backend.open("primary", null, null, cancelled, this));
    }

    <T> T cleanup(Function<ScopeResources.DrainPermit, T> body) {
        ScopeResources.CleanupAttempt attempt = registration.acquireCleanup();
        AtomicReference<T> result = new AtomicReference<>();
        scope.runTracked(attempt.work(), () -> result.set(body.apply(attempt.permit())));
        return result.get();
    }

    Map<String, Object> close(NativeActorBackend backend) {
        return cleanup(permit -> {
            Map<String, Object> result = backend.close(handle, permit);
            if (Boolean.TRUE.equals(result.get("native_cleanup_verified"))
                    && Boolean.TRUE.equals(result.get("persisted_state_verified"))) {
                registration.confirmReleased(permit, handle);
            }
            return result;
        });
    }

    void stopWorld() {
        scope.joinWorldStop(ExecutionScope.beginWorldStop());
        scope.beginDrain();
    }

    @Override public void bindOrigin(World world, Channel channel) {
        registration.requirePendingCleanupOrigin();
        if (this.world != null && (this.world != world || this.channel != channel)) {
            throw new IllegalStateException("open origin replaced");
        }
        this.world = world;
        this.channel = channel;
    }

    @Override public void adopt(NativeActorHandle handle) {
        if (handle.world() != world || handle.channel() != channel) throw new IllegalStateException("foreign open result");
        registration.attach(handle, () -> { });
        this.handle = handle;
    }

    @Override public void rollback(NativeActorHandle handle) {
        if (this.handle != handle) throw new IllegalStateException("foreign rollback result");
        close(backend);
    }

    @Override public void confirmAbsent() { registration.confirmAbsent(); }

    @Override public void creationFailed(String fixedReason) {
        if (handle == null) registration.creationFailed(fixedReason);
    }

    @Override public void requireOrigin(World world, Channel channel) {
        if (this.world != world || this.channel != channel) throw new IllegalStateException("foreign cleanup origin");
        registration.requirePendingCleanupOrigin();
    }
}
