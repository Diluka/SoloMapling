package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.MovePlayerHandler;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static soloMapling.game.action.NativeActionResult.Dispatch.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;
import static soloMapling.game.action.NativeActionService.SubmissionRejection.*;
import static soloMapling.game.action.NativeActionService.SubmissionStatus.*;

/** Module-owned bounded movement. Timers only wake; every native write uses the original character lane. */
public final class NativeMovementActions {
    public static final int TICK_MS = 100;
    @FunctionalInterface interface Wakeups { void schedule(Runnable body, int delayMs); }
    private static final class TimerHolder {
        private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "native-movement-clock"); thread.setDaemon(true); return thread;
        });
    }
    private final Object monitor = new Object();
    private final Wakeups wakeups;
    private final LongSupplier nanos;
    private Job active;

    public static Map<String, Object> capabilities() {
        return Map.ofEntries(Map.entry("directions", List.of("LEFT", "RIGHT")),
                Map.entry("minimum_duration_ms", 100), Map.entry("maximum_duration_ms", 3000),
                Map.entry("tick_interval_ms", TICK_MS), Map.entry("maximum_tick_delay_grace_ms", TICK_MS),
                Map.entry("terrain", "ordinary_ground_same_foothold_only"),
                Map.entry("spawn_settlement", Map.of("maximum_gap_px", 24, "maximum_ticks", 2,
                        "gravity_px_per_second_squared", 2000, "maximum_extra_duration_ms", 200)),
                Map.entry("excluded", List.of("jump", "general_fall", "swim", "ladder", "rope", "mount", "ice", "disease_slow", "teleport", "navigation")),
                Map.entry("stop_semantics", "stop_future_steps_then_verify_native_idle_and_drain"),
                Map.entry("clock_cancel_is_drain_proof", false));
    }

    public NativeMovementActions() {
        this((body, delay) -> TimerHolder.TIMER.schedule(body, delay, TimeUnit.MILLISECONDS), System::nanoTime);
    }
    NativeMovementActions(Wakeups wakeups, LongSupplier nanos) {
        this.wakeups = Objects.requireNonNull(wakeups); this.nanos = Objects.requireNonNull(nanos);
    }

    private record Pending(Server server, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work,
                           GameContext.SceneRef scene, NativeMovementAction action, Runnable preflight) { }

    public static final class MoveHandle implements NativeBasicActions.Handle {
        private final String action;
        private final int sender;
        private final String epoch;
        private final NativeActionService.SubmissionReceipt receipt;
        private final AtomicReference<Pending> pending;
        private final CompletableFuture<NativeBasicResult> result = new CompletableFuture<>();
        private volatile boolean dispatched;
        private volatile boolean initialized;
        private MoveHandle(Pending pending, boolean stop) {
            action = stop ? "stop" : "move"; sender = pending.scene.actor().character().getId();
            epoch = pending.scene.actor().epoch().value();
            receipt = new NativeActionService.SubmissionReceipt(pending.work.snapshot().id());
            this.pending = new AtomicReference<>(pending);
        }
        @Override public CompletionStage<NativeBasicResult> result() { return result.minimalCompletionStage(); }
        @Override public boolean dispatchStarted() { return dispatched; }
        @Override public NativeActionService.SubmissionReceipt receipt() { return receipt; }
    }

    private static final class Job {
        final MoveHandle primary;
        final Pending input;
        final List<MoveHandle> stops = new ArrayList<>();
        final Map<MoveHandle, Pending> stopInputs = new IdentityHashMap<>();
        final int mapId, worldId, channelId;
        ScopeResources.Registration registration;
        Step step;
        Pending idleInput;
        int wakeups;
        int callbacks;
        int steps;
        int simulatedMs;
        int spawnFrames, verticalVelocity, spawnFoothold;
        boolean spawning;
        long walkingStarted;
        int initialX, initialY, lastX, lastY;
        long started;
        boolean initialized, stopRequested, idleRequested, idleObserved, finishing, finished, retryCleanup;
        boolean handlerError;
        String reason;
        Job(MoveHandle primary, Pending input) {
            this.primary = primary; this.input = input;
            mapId = input.scene.map().getId(); worldId = input.scene.actor().world().getId();
            channelId = input.scene.actor().channel().getId();
        }
    }
    private static final class Step {
        final Pending input;
        final boolean idle;
        final int elapsedMs;
        ExecutionScope.WorkHandle work;
        boolean entered, dispatched;
        Step(Pending input, boolean idle, int elapsedMs) { this.input = input; this.idle = idle; this.elapsedMs = elapsedMs; }
    }

    public MoveHandle prepareMove(Server server, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work,
                                  GameContext.SceneRef scene, NativeMovementAction action, Runnable preflight) {
        Objects.requireNonNull(action);
        return prepare(server, context, work, scene, action, preflight, false);
    }
    public MoveHandle prepareStop(Server server, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work,
                                  GameContext.SceneRef scene, Runnable preflight) {
        return prepare(server, context, work, scene, null, preflight, true);
    }
    private MoveHandle prepare(Server server, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work,
                                GameContext.SceneRef scene, NativeMovementAction action, Runnable preflight, boolean stop) {
        Objects.requireNonNull(server); Objects.requireNonNull(context); Objects.requireNonNull(work);
        Objects.requireNonNull(scene); Objects.requireNonNull(preflight);
        if (work.isCleanup()) throw new NativeActionService.Invalid("cleanup_work_not_an_action");
        context.prepareNative("movement", work, preflight, (lane, permit, original, actor) -> {
            if (scene.actor() != actor || scene.actor().epoch() != work.acceptedEpoch()) {
                throw new NativeActionService.Invalid("action_context_mismatch");
            }
            return null;
        });
        return new MoveHandle(new Pending(server, context, work, scene, action, preflight), stop);
    }

    /** The parent carrier represents the whole clock/tick lifecycle, rather than a second native queue. */
    public NativeActionService.SubmissionReceipt submitMove(MoveHandle handle) {
        Objects.requireNonNull(handle);
        if (!handle.receipt.begin()) return handle.receipt;
        Pending input = handle.pending.get();
        if (!input.work.claimCarrier()) {
            handle.receipt.decide(REJECTED, WORK_ALREADY_BOUND);
            complete(handle, input, FAILED, "native_action_work_already_bound", Map.of());
            return handle.receipt;
        }
        Job[] owned = { null };
        try {
            boolean entered = input.context.scope().runTracked(input.work, () -> {
                input.preflight.run();
                NativeContextChecks.requireSceneCurrent(input.server, input.scene);
                input.context.prepareNative("movement", input.work, input.preflight, (lane, permit, work, actor) -> null);
                synchronized (monitor) {
                    if ("move".equals(handle.action) && active != null) throw new NativeActionService.Invalid("movement_busy");
                    if ("stop".equals(handle.action) && active != null) {
                        if (active.finishing || active.finished) throw new NativeActionService.Invalid("movement_draining");
                        if (active.input.context != input.context || active.input.scene.actor() != input.scene.actor()) {
                            throw new NativeActionService.Invalid("movement_context_mismatch");
                        }
                        owned[0] = active;
                        active.stops.add(handle); active.stopInputs.put(handle, input); active.stopRequested = true;
                        active.idleRequested = true; active.idleInput = input;
                        if (active.reason == null) active.reason = "stop_requested";
                        return;
                    }
                    Job job = new Job(handle, input);
                    owned[0] = job; active = job;
                }
                Job job = owned[0];
                Point position = input.scene.actor().character().getPosition();
                synchronized (monitor) {
                    job.initialX = job.lastX = position.x; job.initialY = job.lastY = position.y;
                    job.started = nanos.getAsLong();
                    job.spawning = "move".equals(handle.action) && NativeMovementGeometry.needsSpawnSettle(
                            input.scene.actor().character(), input.scene.map());
                    job.walkingStarted = job.spawning ? 0 : job.started;
                    if ("stop".equals(handle.action)) {
                        job.stopRequested = true; job.idleRequested = true; job.idleInput = input;
                    }
                }
                job.registration = input.context.scope().resources().beginOwnedResult(input.work, "movement_clock");
                job.registration.attach(job, () -> requestStop(job, "movement_module_stopped", false));
                if ("move".equals(handle.action)) schedule(job);
            });
            handle.initialized = true;
            if (!entered) {
                handle.receipt.decide(REJECTED, PERMIT);
                finishSingle(handle, input, CANCELLED, "movement_cancelled_before_dispatch", Map.of());
            } else {
                handle.receipt.decide(ENQUEUED, NONE);
                Job job = owned[0];
                synchronized (monitor) { if (job.primary == handle) job.initialized = true; }
                drive(job);
            }
        } catch (Throwable failure) {
            handle.initialized = true;
            handle.receipt.decide(REJECTED, PERMIT);
            Job job = owned[0];
            if (job != null && job.primary == handle) {
                synchronized (monitor) { job.initialized = true; job.reason = reason(failure); job.stopRequested = true; job.idleRequested = false; }
                maybeFinish(job);
            } else finishSingle(handle, input, input.work.cancelRequested() ? CANCELLED : FAILED, reason(failure), Map.of());
        }
        return handle.receipt;
    }

    private void requestStop(Job job, String reason, boolean idle) {
        synchronized (monitor) {
            if (job.finished) return;
            if (job.finishing) { job.retryCleanup = true; return; }
            job.stopRequested = true; job.idleRequested |= idle;
            if (reason != null && job.reason == null) job.reason = reason;
        }
        drive(job);
    }
    private void schedule(Job job) {
        synchronized (monitor) {
            if (job.stopRequested || job.finished || job.wakeups != 0) return;
            job.wakeups++;
        }
        try { wakeups.schedule(() -> wake(job), TICK_MS); }
        catch (Throwable failure) {
            synchronized (monitor) { job.wakeups--; job.stopRequested = true; job.reason = "movement_clock_unavailable"; }
            maybeFinish(job);
        }
    }
    private void wake(Job job) {
        synchronized (monitor) { job.wakeups--; job.callbacks++; }
        try {
            Pending input = job.input;
            if (input.work.cancelRequested()) requestStop(job, "movement_cancelled", job.primary.dispatched);
            else if (!input.context.scope().isActive() || !sameWorld(input)) requestStop(job, "movement_context_expired", false);
            else drive(job);
        } finally {
            synchronized (monitor) { job.callbacks--; }
            maybeFinish(job);
        }
    }
    private void drive(Job job) {
        boolean idle;
        int elapsed;
        synchronized (monitor) {
            if (!job.initialized || job.finished || job.finishing || job.step != null) return;
            if (job.stopRequested) {
                if (!job.idleRequested || job.idleObserved && job.stops.stream().allMatch(stop -> stop.dispatched)
                        || !job.input.context.scope().isActive() || !sameWorld(job.input)) {
                    // An already scheduled wakeup is allowed to exit naturally; Future.cancel is not a drain proof.
                    elapsed = 0; idle = false;
                } else { elapsed = 0; idle = true; }
            } else {
                long realElapsed = Math.max(0, TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - job.walkingStarted));
                elapsed = job.spawning ? TICK_MS
                        : (int) Math.min(TICK_MS, Math.min(job.input.action.durationMs(), realElapsed) - job.simulatedMs);
                if (elapsed < 1) return;
                idle = false;
            }
        }
        if (elapsed > 0 || idle) submitStep(job, idle, elapsed);
        else maybeFinish(job);
    }
    private void submitStep(Job job, boolean idle, int elapsedMs) {
        Pending input;
        Step step;
        synchronized (monitor) {
            if (job.step != null || job.finished || job.finishing) return;
            input = idle && job.idleInput != null ? job.idleInput : job.input;
            step = new Step(input, idle, elapsedMs); job.step = step;
        }
        try {
            ExecutionScope.WorkHandle work = input.context.acceptWork(); step.work = work;
            work.completion().whenComplete((fact, failure) -> stepSettled(job, step));
            input.context.submit("movement", work, input.preflight, () -> runStep(job, step), () -> { });
        } catch (Throwable failure) {
            synchronized (monitor) { job.reason = reason(failure); job.stopRequested = true; job.idleRequested = false; }
            if (step.work != null && !step.work.snapshot().carrierClaimed()) step.work.reject();
            else if (step.work == null) {
                synchronized (monitor) { if (job.step == step) job.step = null; }
                maybeFinish(job);
            }
        }
    }
    private void runStep(Job job, Step step) {
        synchronized (monitor) { step.entered = true; }
        Pending input = step.input;
        NativeBasicActions.Context context = new NativeBasicActions.Context(input.server, input.scene, step.work,
                step.idle ? "stop" : "move", () -> {
            synchronized (monitor) {
                step.dispatched = true;
                if (step.idle) {
                    if ("stop".equals(job.primary.action)) job.primary.dispatched = true;
                    for (MoveHandle stop : job.stops) stop.dispatched = true;
                } else job.primary.dispatched = true;
            }
        });
        try {
            if (!step.idle) {
                synchronized (monitor) {
                    if (job.stopRequested || input.work.cancelRequested()) return;
                    long deadlineStart = job.spawning ? job.started : job.walkingStarted;
                    int deadlineMs = job.spawning ? 200 : input.action.durationMs();
                    if (nanos.getAsLong() - deadlineStart > TimeUnit.MILLISECONDS.toNanos(deadlineMs + TICK_MS)) {
                        job.reason = "movement_duration_elapsed"; job.stopRequested = true; job.idleRequested = true; return;
                    }
                }
            }
            context.requireCurrent();
            Point before = input.scene.actor().character().getPosition();
            int x, y, foothold, velocity, vertical = 0;
            boolean settling = !step.idle && job.spawning;
            boolean landed = false;
            if (step.idle) {
                x = before.x; y = before.y; velocity = 0;
                var ground = input.scene.map().getFootholds().findBelow(new Point(x, y - 5));
                foothold = ground == null ? 0 : ground.getId();
            } else if (settling) {
                var next = NativeMovementGeometry.settle(input.scene.actor().character(), input.scene.map(), step.elapsedMs, job.verticalVelocity);
                if (job.spawnFrames >= 2 || job.spawnFoothold != 0 && job.spawnFoothold != next.footholdId()) {
                    throw new NativeActionService.Invalid("movement_spawn_settlement_rejected");
                }
                x = next.x(); y = next.y(); foothold = next.footholdId(); velocity = 0;
                vertical = next.verticalVelocity(); landed = next.landed();
            } else {
                var next = NativeMovementGeometry.step(input.scene.actor().character(), input.scene.map(), input.action.direction(), step.elapsedMs);
                x = next.x(); y = next.y(); foothold = next.footholdId(); velocity = next.velocity();
            }
            byte stance = (byte) (step.idle || settling ? 4 | input.scene.actor().character().getStance() & 1
                    : "LEFT".equals(input.action.direction()) ? 3 : 2);
            ByteBuf packet = Unpooled.buffer(24).writeZero(9).writeByte(1).writeByte(0)
                    .writeShortLE(x).writeShortLE(y).writeShortLE(velocity).writeShortLE(vertical)
                    .writeShortLE(foothold).writeByte(stance).writeShortLE(step.idle ? 0 : step.elapsedMs);
            context.dispatch(new MovePlayerHandler(), packet);
            Point after = input.scene.actor().character().getPosition();
            synchronized (monitor) {
                job.handlerError |= context.handlerError();
                job.lastX = after.x; job.lastY = after.y;
                if (after.x != x || after.y != y || input.scene.actor().character().getMap() != input.scene.map()) {
                    job.reason = "native_movement_outcome_unknown"; job.stopRequested = true; job.idleRequested = false;
                } else if (step.idle) job.idleObserved = input.scene.actor().character().getStance() == stance;
                else if (settling) {
                    job.spawnFrames++; job.verticalVelocity = vertical; job.spawnFoothold = foothold;
                    if (landed) { job.spawning = false; job.walkingStarted = nanos.getAsLong(); }
                } else {
                    job.steps++; job.simulatedMs += step.elapsedMs;
                    if (job.simulatedMs >= input.action.durationMs()
                            || nanos.getAsLong() - job.walkingStarted >= TimeUnit.MILLISECONDS.toNanos(input.action.durationMs())) {
                        job.stopRequested = true; job.idleRequested = true;
                    }
                }
            }
        } catch (Throwable failure) {
            synchronized (monitor) {
                job.handlerError |= context.handlerError(); job.reason = reason(failure);
                job.stopRequested = true; job.idleRequested = false;
            }
        }
    }
    private void stepSettled(Job job, Step step) {
        synchronized (monitor) { job.callbacks++; }
        try {
            boolean next;
            synchronized (monitor) {
                if (job.step != step) return;
                job.step = null;
                if (!step.entered || step.work.snapshot().rejected()) {
                    job.reason = "movement_tick_not_dispatched"; job.stopRequested = true; job.idleRequested = false;
                }
                if (step.idle && !job.idleObserved) { job.stopRequested = true; job.idleRequested = false; }
                next = !job.stopRequested;
            }
            if (next) schedule(job); else drive(job);
        } finally {
            synchronized (monitor) { job.callbacks--; }
            maybeFinish(job);
        }
    }
    private void maybeFinish(Job job) {
        synchronized (monitor) {
            if (!job.initialized || !job.stopRequested || job.finished || job.finishing || job.step != null
                    || job.wakeups != 0 || job.callbacks != 0 || job.stops.stream().anyMatch(stop -> !stop.initialized)) return;
            job.finishing = true; job.retryCleanup = false;
        }
        String cleanupFailure = null;
        try {
            if (job.registration != null) {
                if (job.registration.identity() == null) job.registration.confirmAbsent();
                else {
                    var cleanup = job.registration.acquireCleanup();
                    boolean entered = job.input.context.scope().runTracked(cleanup.work(), () -> {
                        cleanup.permit().requireExecuting(job);
                        job.registration.confirmReleased(cleanup.permit(), job);
                    });
                    if (!entered || !cleanup.work().isSettled() || !job.registration.released()) {
                        cleanupFailure = "movement_clock_cleanup_unverified";
                    }
                }
            }
        } catch (Throwable unavailable) { cleanupFailure = "movement_clock_cleanup_unverified"; }
        if (cleanupFailure != null) {
            boolean retry;
            synchronized (monitor) { job.finishing = false; retry = job.retryCleanup; job.retryCleanup = false; }
            // A stop ticket/cancellation signal can arrive after the clock observed the inactive world.
            // Keep the exact resource and carrier available to that original cleanup handshake.
            if (retry) { maybeFinish(job); return; }
            WorldEpoch.State world = WorldEpoch.snapshot();
            if (world.active() && world.token() != job.input.work.acceptedEpoch()
                    || job.input.context.scope().snapshot().state() != ExecutionScope.State.ACTIVE) {
                complete(job.primary, job.input, INDETERMINATE, cleanupFailure, facts(job), job.handlerError);
                for (MoveHandle stop : List.copyOf(job.stops)) {
                    Pending input = job.stopInputs.get(stop);
                    if (input != null) complete(stop, input, INDETERMINATE, cleanupFailure, facts(job), job.handlerError);
                }
            }
            return; // Unverified resource debt deliberately prevents parent settlement and root closure.
        }
        synchronized (monitor) { job.finished = true; }
        Map<String, Object> facts = facts(job);
        boolean moved = job.initialX != job.lastX || job.initialY != job.lastY;
        boolean completed = job.input.action != null && job.simulatedMs >= job.input.action.durationMs();
        boolean interrupted = job.input.work.cancelRequested() || !job.stops.isEmpty() || !sameWorld(job.input);
        NativeActionResult.Outcome outcome = "native_movement_outcome_unknown".equals(job.reason) ? INDETERMINATE
                : "stop".equals(job.primary.action) ? job.idleObserved ? SUCCEEDED : FAILED
                : completed ? moved ? SUCCEEDED : FAILED : interrupted ? CANCELLED : FAILED;
        if (outcome == SUCCEEDED && !job.primary.dispatched) outcome = FAILED;
        String reason = outcome == SUCCEEDED ? null : job.reason == null ? "native_movement_not_observed" : job.reason;
        completeAndSettle(job.primary, job.input, outcome, reason, facts, job.handlerError);
        for (MoveHandle stop : List.copyOf(job.stops)) {
            Pending input = job.stopInputs.get(stop);
            completeAndSettle(stop, input, job.idleObserved && stop.dispatched ? SUCCEEDED : FAILED,
                    job.idleObserved && stop.dispatched ? null : "native_stop_not_observed", facts, job.handlerError);
        }
        job.stops.clear(); job.stopInputs.clear();
        synchronized (monitor) { if (active == job) active = null; }
    }
    private Map<String, Object> facts(Job job) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("evidence_source", "NATIVE_MOVEMENT_POSITION");
        facts.put("effect_observed", job.idleObserved || job.initialX != job.lastX || job.initialY != job.lastY);
        facts.put("before", Map.of("x", job.initialX, "y", job.initialY));
        facts.put("after", Map.of("x", job.lastX, "y", job.lastY));
        facts.put("map_id", job.mapId);
        facts.put("world_id", job.worldId);
        facts.put("channel_id", job.channelId);
        facts.put("movement_steps", job.steps); facts.put("simulated_duration_ms", job.simulatedMs);
        facts.put("requested_duration_ms", job.input.action == null ? 0 : job.input.action.durationMs());
        facts.put("actual_elapsed_ms", Math.max(0, TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - job.started)));
        facts.put("spawn_settlement_ticks", job.spawnFrames);
        facts.put("completed_duration", job.input.action != null && job.simulatedMs >= job.input.action.durationMs());
        facts.put("moving", false); facts.put("movement_stopped", true); facts.put("native_idle_observed", job.idleObserved);
        facts.put("stop_reason", job.reason == null ? "duration_completed" : job.reason);
        return facts;
    }
    private static boolean sameWorld(Pending input) {
        WorldEpoch.State current = WorldEpoch.snapshot();
        return current.active() && current.token() == input.work.acceptedEpoch();
    }
    private static String reason(Throwable failure) {
        if (failure instanceof NativeActionService.Invalid invalid) return invalid.reason();
        if (failure instanceof CharacterRuntime.Invalid invalid) return invalid.reason();
        if (failure instanceof ExecutionScope.Invalid invalid) return invalid.reason();
        if (failure instanceof GameContext.Invalid) return "movement_context_expired";
        return "native_movement_unavailable";
    }
    private static void finishSingle(MoveHandle handle, Pending input, NativeActionResult.Outcome outcome, String reason, Map<String, Object> facts) {
        completeAndSettle(handle, input, outcome, reason, facts, false);
    }
    /** Only callers which have proved physical exit and resource release may settle here. */
    private static void completeAndSettle(MoveHandle handle, Pending input, NativeActionResult.Outcome outcome,
                                          String reason, Map<String, Object> facts, boolean handlerError) {
        try { complete(handle, input, outcome, reason, facts, handlerError); }
        catch (Throwable invalidResult) {
            handle.pending.set(null);
            handle.result.completeExceptionally(new IllegalStateException("native_movement_result_unavailable"));
        } finally { input.work.settleCarrier(() -> { }); }
    }
    private static void complete(MoveHandle handle, Pending input, NativeActionResult.Outcome outcome, String reason, Map<String, Object> facts) {
        complete(handle, input, outcome, reason, facts, false);
    }
    private static void complete(MoveHandle handle, Pending input, NativeActionResult.Outcome outcome, String reason,
                                 Map<String, Object> facts, boolean handlerError) {
        NativeBasicResult fact = new NativeBasicResult(handle.action, outcome, handle.dispatched ? STARTED : NOT_STARTED,
                handle.sender, handle.epoch, input.work.cancelRequested(), handlerError, reason, facts);
        handle.pending.set(null);
        handle.result.complete(fact);
    }
}
