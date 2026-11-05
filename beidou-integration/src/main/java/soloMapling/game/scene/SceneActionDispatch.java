package soloMapling.game.scene;

import org.gms.server.maps.MapObject;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.runtime.CharacterRuntime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/** The current scene intent and its original owner's exit proof; physical execution remains in the role lane. */
public final class SceneActionDispatch {
    public enum Priority { NORMAL, TACTICAL_AVOID, EQUIPMENT_PICKUP, LETHAL_ESCAPE }
    public enum Status { GRANTED, WAIT, UNAVAILABLE }
    public record Decision(Status status, Arrangement arrangement, String reason) { }

    public final class Arrangement {
        private final long version;
        private final Priority priority;
        private final CharacterRuntime.ModuleContext provider;
        private final CharacterCommandLane.ControlPermit nativeProvider;
        private final ExecutionScope.WorkHandle work;
        private final MapObject target;
        private final CompletionStage<Void> predecessors;
        private boolean valid = true;
        private volatile String reason;

        private Arrangement(long version, Priority priority, CharacterRuntime.ModuleContext provider,
                CharacterCommandLane.ControlPermit nativeProvider, ExecutionScope.WorkHandle work,
                MapObject target, String reason, CompletionStage<Void> predecessors) {
            this.version = version; this.priority = priority; this.provider = provider;
            this.work = work; this.target = target; this.reason = reason; this.predecessors = predecessors;
            this.nativeProvider = nativeProvider;
        }

        public long version() { return version; }
        public Priority priority() { return priority; }
        public String reason() { return reason; }
        public boolean current() { synchronized (SceneActionDispatch.this) { return ready(this); } }
        public boolean valid() { synchronized (SceneActionDispatch.this) { return valid && current == this; } }
        public void requireCurrent() {
            synchronized (SceneActionDispatch.this) {
                binding.requireCurrent();
                if (!ready(this)) throw new NativeActionService.Invalid("scene_arrangement_invalid");
            }
        }
    }

    private final SceneActorBinding binding;
    private Arrangement current;
    private long version;

    SceneActionDispatch(SceneActorBinding binding) { this.binding = binding; }

    /** finiteActivity is an operation's existing work, never a persistent observation/control loop. */
    public synchronized Decision request(Priority priority, CharacterRuntime.ModuleContext provider,
            ExecutionScope.WorkHandle finiteActivity, MapObject exactTarget, String reason) {
        binding.requireCurrent();
        if (finiteActivity.scope() != provider.scope()) throw new IllegalArgumentException("foreign_scene_intent_work");
        return request(priority, provider, null, finiteActivity, exactTarget, reason);
    }

    public synchronized Decision requestNative(CharacterCommandLane.ControlPermit provider,
            ExecutionScope.WorkHandle finiteActivity, MapObject exactTarget, String reason) {
        binding.requireCurrent();
        if (finiteActivity.scope() != provider.ownerScope() || provider.characterRef() != binding.characterRef()) {
            throw new IllegalArgumentException("foreign_scene_native_intent");
        }
        return request(Priority.NORMAL, null, provider, finiteActivity, exactTarget, reason);
    }

    private Decision request(Priority priority, CharacterRuntime.ModuleContext provider,
            CharacterCommandLane.ControlPermit nativeProvider, ExecutionScope.WorkHandle finiteActivity,
            MapObject exactTarget, String reason) {
        if (!finiteActivity.scope().isActive() || finiteActivity.cancelRequested()
                || nativeProvider != null && nativeProvider.revoked()) {
            return new Decision(Status.UNAVAILABLE, null, "intent_owner_unavailable");
        }
        if (current != null && current.valid && !current.work.isSettled()) {
            if (current.work == finiteActivity && current.target == exactTarget && current.priority == priority) {
                return new Decision(ready(current) ? Status.GRANTED : Status.WAIT, current, current.reason);
            }
            if (priority.compareTo(current.priority) <= 0) return new Decision(Status.WAIT, null, "current_intent_retained");
        }
        CompletionStage<Void> predecessors = CompletableFuture.completedFuture(null);
        if (current != null) {
            var previous = current;
            previous.valid = false;
            previous.reason = "preempted_by_" + reason;
            predecessors = CompletableFuture.allOf(previous.predecessors.toCompletableFuture(),
                    previous.work.completion().toCompletableFuture());
            if (!previous.work.isSettled()) previous.work.scope().requestCancel(previous.work);
        }
        current = new Arrangement(++version, priority, provider, nativeProvider, finiteActivity, exactTarget, reason, predecessors);
        return new Decision(ready(current) ? Status.GRANTED : Status.WAIT, current,
                ready(current) ? reason : "previous_intent_draining");
    }

    private boolean ready(Arrangement arrangement) {
        return current == arrangement && arrangement.valid && binding.active()
                && arrangement.work.scope().isActive() && !arrangement.work.cancelRequested()
                && (arrangement.nativeProvider == null || !arrangement.nativeProvider.revoked())
                && arrangement.predecessors.toCompletableFuture().isDone()
                && !arrangement.predecessors.toCompletableFuture().isCompletedExceptionally()
                && System.nanoTime() >= binding.characterState().actionLockedUntilNanos();
    }

    public synchronized void release(Arrangement arrangement, String reason) {
        if (arrangement == null || arrangement != current) return;
        arrangement.valid = false;
        arrangement.reason = reason;
    }

    public synchronized void requireImmediateOpportunity() {
        binding.requireCurrent();
        if (System.nanoTime() < binding.characterState().actionLockedUntilNanos()
                || current != null && current.valid && !current.work.isSettled() && current.priority != Priority.NORMAL) {
            throw new NativeActionService.Invalid("scene_immediate_opportunity_busy");
        }
    }

    public synchronized long actionLockRemainingMs() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(binding.characterState().actionLockedUntilNanos() - System.nanoTime()));
    }

    public synchronized Priority currentPriority() {
        return current != null && current.valid && !current.work.isSettled() ? current.priority : Priority.NORMAL;
    }

    public synchronized Map<String, Object> snapshot() {
        if (current == null) return Map.of("present", false, "action_lock_remaining_ms", actionLockRemainingMs());
        return Map.of("present", true, "version", current.version, "priority", current.priority.name(),
                "provider", current.provider == null ? "native_command" : current.provider.reference().instanceId(),
                "work_id", current.work.snapshot().id(), "target_oid", current.target == null ? 0 : current.target.getObjectId(),
                "valid", current.valid, "ready", ready(current), "reason", current.reason,
                "action_lock_remaining_ms", actionLockRemainingMs());
    }

    synchronized void leave() {
        if (current != null) {
            current.valid = false;
            current.reason = "scene_membership_ended";
            if (!current.work.isSettled()) current.work.scope().requestCancel(current.work);
        }
    }
}
