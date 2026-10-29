package soloMapling.game.navigation;

import org.gms.net.server.Server;
import soloMapling.game.navigation.NavigationResult.Outcome;
import soloMapling.ArtificialPlayer.GCMoveSystem.RuntimeNavigationEngine;
import soloMapling.game.action.NativeMovementActions;
import soloMapling.game.action.NativeMovementGeometry;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ActionModule;

import java.awt.Point;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One target-directed navigation task, using the role's original native lane and module scope. */
public final class NavigationActionModule implements ActionModule<MoveToRequest, NavigationResult> {
    public record Config(int arrivalTolerancePx, int timeoutMs, int stalledTimeoutMs) { }
    public static final Config DEFAULT_CONFIG = new Config(8, 30_000, 3_000);
    public static final ModuleDefinition<Config, NavigationActionModule> DEFINITION = new ModuleDefinition<>(
            "navigation", Set.of("movement"), new ExecutionScope.Limits(16, 2, 4, 2),
            config -> new NavigationActionModule(config, Server.getInstance()), List.of(new ModuleFunction<>(
            "navigation.moveTo", "在当前实际场景内规划并移动到指定坐标，等待抵达或失败及收尾。",
            List.of(new ModuleFunction.Parameter("x", ModuleFunction.Type.INTEGER, true, "目标横坐标"),
                    new ModuleFunction.Parameter("y", ModuleFunction.Type.INTEGER, true, "目标纵坐标"),
                    new ModuleFunction.Parameter("timeout_ms", ModuleFunction.Type.INTEGER, false,
                            "本次规划及移动预算1..120000ms，实际取与模块期限的较小值")),
            "返回outcome、实际末位置、稳定状态、执行步数和经过的基础边类型。",
            (module, context, work, args) -> module.execute(new MoveToRequest(
                    (int) ((Number) args.get("x")).doubleValue(), (int) ((Number) args.get("y")).doubleValue(),
                    (int) ((Number) args.getOrDefault("timeout_ms", MoveToRequest.MAX_TIMEOUT_MS)).doubleValue()),
                    context, work).toMap())));

    private final Config config;
    private final Server server;
    private final NativeMovementActions movement = new NativeMovementActions();
    private volatile CharacterRuntime.ModuleContext context;
    private Job active;

    public NavigationActionModule(Config config, Server server) {
        this.config = config;
        this.server = server;
    }

    public static Config parseConfig(Map<String, Object> values) {
        return new Config(integer(values, "arrival_tolerance_px", DEFAULT_CONFIG.arrivalTolerancePx(), 0, 32_767),
                integer(values, "timeout_ms", DEFAULT_CONFIG.timeoutMs(), 1, 120_000),
                integer(values, "stalled_timeout_ms", DEFAULT_CONFIG.stalledTimeoutMs(), 1, 120_000));
    }

    private static int integer(Map<String, Object> values, String key, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(values.getOrDefault(key, fallback).toString());
        if (value < minimum || value > maximum) throw new IllegalArgumentException("invalid_navigation_config:" + key);
        return value;
    }

    @Override public void onActivated(CharacterRuntime.ModuleContext context) { this.context = context; }
    @Override public synchronized void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        if (active != null) active.stopRequested = true;
    }

    public CharacterRuntime.ModuleContext context() { return context; }
    public NativeMovementActions movement() { return movement; }

    /** Java callers use the same target execution as the exported module function. */
    @Override public NavigationResult execute(MoveToRequest input, CharacterRuntime.ModuleContext context,
                                              ExecutionScope.WorkHandle work) {
        var job = new Job(context, work, new Point(input.x(), input.y()), Math.min(config.timeoutMs(), input.timeoutMs()));
        synchronized (this) {
            if (active != null) return new NavigationResult(Outcome.BUSY, "navigation_busy", null);
            active = job;
        }
        Outcome outcome = null;
        String reason = null;
        try {
            var actor = context.prepareNative("movement", work, () -> {},
                    (lane, permit, original, character) -> character);
            job.scene = NativeContextChecks.captureScene(server, actor);
            job.position = new Point(actor.character().getPosition());
            checkpoint(job);
            job.engine = RuntimeNavigationEngine.plan(actor.character(), job.target, () -> checkpoint(job));
            job.registration = context.scope().resources().beginOwnedResult(work, "navigation_motion");
            job.registration.attach(job, () -> job.stopRequested = true);
            long lastProgress = System.nanoTime();
            int stableFrames = 0;
            while (true) {
                if (outcome == null) {
                    try { checkpoint(job); }
                    catch (Halt stop) { outcome = stop.outcome; reason = stop.reason; }
                    if (outcome == null && System.nanoTime() - lastProgress
                            >= TimeUnit.MILLISECONDS.toNanos(config.stalledTimeoutMs())) {
                        outcome = Outcome.BLOCKED; reason = "navigation_no_progress";
                    }
                }
                Point before = job.position;
                RuntimeNavigationEngine.Frame frame;
                try { frame = step(job, outcome != null); }
                catch (Halt stop) { outcome = stop.outcome; reason = stop.reason; continue; }
                catch (NativeActionService.Invalid blocked) {
                    if (outcome != null) throw blocked;
                    reason = blocked.reason();
                    outcome = switch (reason) {
                        case "movement_slow_unsupported", "movement_mode_unsupported", "movement_surface_unsupported" -> Outcome.UNSUPPORTED;
                        default -> Outcome.BLOCKED;
                    };
                    if (job.steps == 0) break;
                    continue;
                }
                job.stable = frame.stable();
                if (!before.equals(job.position)) lastProgress = System.nanoTime();
                if (outcome == null && job.stable && distance(job.position, job.target) <= config.arrivalTolerancePx()) {
                    outcome = Outcome.ARRIVED;
                }
                if (outcome != null) {
                    stableFrames = job.stable ? stableFrames + 1 : 0;
                    if (stableFrames >= 2) break;
                }
                try { Thread.sleep(job.engine.tickMs()); }
                catch (InterruptedException interrupted) {
                    job.interrupted = true;
                    job.stopRequested = true;
                    if (outcome == null) { outcome = Outcome.CANCELLED; reason = "navigation_cancelled"; }
                }
            }
        } catch (Halt stop) {
            outcome = stop.outcome; reason = stop.reason;
        } catch (RuntimeNavigationEngine.Failure failed) {
            reason = failed.reason();
            outcome = reason.startsWith("UNSUPPORTED") ? Outcome.UNSUPPORTED
                    : "NO_PATH".equals(reason) ? Outcome.NO_PATH : Outcome.READ_FAILED;
        } catch (CancellationException cancelled) {
            outcome = Outcome.CANCELLED; reason = "navigation_cancelled";
            job.interrupted |= Thread.interrupted();
        } catch (GameContext.Invalid changed) {
            outcome = Outcome.CONTEXT_CHANGED; reason = changed.reason().name();
        } finally {
            try {
                if (job.registration != null) {
                    var cleanup = job.registration.acquireCleanup();
                    context.scope().runTracked(cleanup.work(), () -> {
                        cleanup.permit().requireExecuting(job);
                        job.registration.confirmReleased(cleanup.permit(), job);
                    });
                }
            } finally {
                synchronized (this) { active = null; }
                if (job.interrupted) Thread.currentThread().interrupt();
            }
        }
        return new NavigationResult(outcome, reason,
                new NavigationResult.Progress(new NavigationResult.Position(input.x(), input.y()),
                        job.position == null ? null : new NavigationResult.Position(job.position.x, job.position.y),
                        job.stable, job.steps, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.started),
                        List.copyOf(job.modes)));
    }

    private RuntimeNavigationEngine.Frame step(Job job, boolean stopping) {
        var frame = new AtomicReference<RuntimeNavigationEngine.Frame>();
        var failure = new AtomicReference<RuntimeException>();
        ScopeResources.CleanupAttempt cleanup = stopping ? job.registration.acquireCleanup() : null;
        var step = cleanup == null ? job.context.acceptWork() : cleanup.work();
        Runnable body = () -> {
            try {
                if (cleanup != null) cleanup.permit().requireExecuting(job);
                else {
                    checkpoint(job);
                    NativeMovementGeometry.requireOrdinaryMovementMode(job.scene.actor().character(), job.scene.map());
                    if (job.engine.attributesChanged()) throw new Halt(Outcome.BLOCKED, "attributes_changed");
                }
                NativeContextChecks.requireSceneCurrent(server, job.scene);
                var next = job.engine.nextFrame(stopping);
                boolean applied = NativeMovementActions.dispatchNavigationFrame(server, job.scene, step, next.position(),
                        next.velocityX(), next.velocityY(), next.stance(), next.footholdId(), next.durationMs());
                Point actual = job.scene.actor().character().getPosition();
                job.position = new Point(actual);
                if (!applied || !actual.equals(next.position())
                        || job.scene.actor().character().getStance() != next.stance()) {
                    throw new IllegalStateException("native_navigation_frame_unverified");
                }
                job.steps++;
                if (next.mode() != RuntimeNavigationEngine.Mode.IDLE) job.modes.add(next.mode().name());
                frame.set(next);
            } catch (RuntimeException unavailable) { failure.set(unavailable); }
        };
        try {
            if (cleanup == null) job.context.submit("movement", step, () -> {}, body, () -> {});
            else job.context.submitCleanup(cleanup, body, () -> {});
        } catch (RuntimeException rejected) {
            step.reject();
            throw rejected;
        }
        step.completion().toCompletableFuture().join();
        if (failure.get() != null) throw failure.get();
        if (frame.get() == null) {
            if (!stopping) throw new Halt(Outcome.CANCELLED, "navigation_step_cancelled");
            throw new CancellationException("navigation_cleanup_not_entered");
        }
        return frame.get();
    }

    private void checkpoint(Job job) {
        if (Thread.interrupted()) { job.interrupted = true; job.stopRequested = true; }
        if (job.stopRequested || job.work.cancelRequested()) throw new Halt(Outcome.CANCELLED, "navigation_cancelled");
        if (System.nanoTime() - job.started >= TimeUnit.MILLISECONDS.toNanos(job.timeoutMs)) {
            throw new Halt(Outcome.TIMED_OUT, "navigation_timeout");
        }
        NativeContextChecks.requireSceneCurrent(server, job.scene);
    }

    private static int distance(Point actual, Point target) {
        return Math.max(Math.abs(actual.x - target.x), Math.abs(actual.y - target.y));
    }

    private static final class Halt extends RuntimeException {
        final Outcome outcome;
        final String reason;
        Halt(Outcome outcome, String reason) { super(reason, null, false, false); this.outcome = outcome; this.reason = reason; }
    }

    private static final class Job {
        final CharacterRuntime.ModuleContext context;
        final ExecutionScope.WorkHandle work;
        final Point target;
        final int timeoutMs;
        final long started = System.nanoTime();
        final Set<String> modes = new LinkedHashSet<>();
        volatile boolean stopRequested;
        boolean interrupted;
        GameContext.SceneRef scene;
        RuntimeNavigationEngine.Session engine;
        ScopeResources.Registration registration;
        Point position;
        boolean stable;
        int steps;
        Job(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work, Point target, int timeoutMs) {
            this.context = context; this.work = work; this.target = target; this.timeoutMs = timeoutMs;
        }
    }
}
