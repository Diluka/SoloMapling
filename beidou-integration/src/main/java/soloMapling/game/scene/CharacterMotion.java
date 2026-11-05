package soloMapling.game.scene;

import org.gms.net.server.Server;
import org.gms.constants.game.CharacterStance;
import soloMapling.ArtificialPlayer.GCMoveSystem.RuntimeNavigationEngine;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeMovementActions;
import soloMapling.game.action.NativeMovementGeometry;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.navigation.NavigationResult.Outcome;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleTask;

import java.awt.Point;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One scene member's physical sessions; native role facts remain authoritative. */
public final class CharacterMotion {
    private final SceneActorBinding binding;
    private final Server server;
    private final NativeMovementActions movement;
    private final Point entryPosition;
    private Job active;
    private ModuleTask entryLanding;

    CharacterMotion(SceneActorBinding binding, Server server) {
        this.binding = binding;
        this.server = server;
        entryPosition = new Point(binding.characterRef().character().getPosition());
        movement = new NativeMovementActions(binding::awaitWork);
    }

    public NativeMovementActions movement() {
        binding.requireCurrent();
        return movement;
    }

    /** Called on the acknowledged entry lane; only this unchanged native entry may start an idle fall. */
    void startEntryLanding() {
        var actor = binding.characterRef().character();
        if (!actor.isAlive() || !entryPosition.equals(actor.getPosition())
                || CharacterStance.isClimbing(actor.getStance()) || binding.nativeScene().map().getFootholds() == null) return;
        var ground = RuntimeNavigationEngine.groundPoint(binding.nativeScene().map(), entryPosition);
        if (ground == null || ground.y - entryPosition.y <= 1) return;
        synchronized (this) {
            entryLanding = binding.startTask("scene-entry-fall-" + actor.getId(), work -> {
                var result = moveTo(new MoveToRequest(entryPosition.x, ground.y), NavigationActionModule.DEFAULT_CONFIG,
                        binding.context(), work, SceneMembershipModule.CONTROL);
                binding.context().recordActivity(work, "map_entry_landing_" + result.outcome().name().toLowerCase(
                        java.util.Locale.ROOT), "SCENE_ENTRY");
            });
        }
    }

    void leave() {
        synchronized (this) {
            if (active != null) active.stopRequested = true;
        }
        movement.leaveScene();
    }

    public NavigationResult moveTo(MoveToRequest input, NavigationActionModule.Config config,
                                   CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        return moveTo(input, config, context, work, "movement");
    }

    private NavigationResult moveTo(MoveToRequest input, NavigationActionModule.Config config,
                                   CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work, String control) {
        var job = new Job(context, work, new Point(input.x(), input.y()), Math.min(config.timeoutMs(), input.timeoutMs()), control);
        job.scene = binding.nativeScene();
        boolean ownsMotion = false;
        Outcome outcome = null;
        String reason = null;
        try {
            binding.awaitWork(work);
            ModuleTask landing;
            synchronized (this) { landing = "movement".equals(control) ? entryLanding : null; }
            // A normal navigation request may follow login immediately; wait for this exact entry carrier,
            // without cancelling it or extending the caller's original deadline.
            while (landing != null && !landing.work().isSettled()) {
                checkpoint(job);
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) {
                    job.interrupted = true;
                    throw new Halt(Outcome.CANCELLED, "navigation_cancelled");
                }
            }
            synchronized (this) {
                binding.requireCurrent();
                if (SceneMembershipModule.CONTROL.equals(control)
                        && !entryPosition.equals(binding.characterRef().character().getPosition())) {
                    return new NavigationResult(Outcome.CONTEXT_CHANGED, "entry_position_changed", null);
                }
                if (active != null || !movement.tryBeginNavigation()) {
                    return new NavigationResult(Outcome.BUSY, "navigation_busy", null);
                }
                active = job;
                ownsMotion = true;
            }
            var actor = context.prepareNative(control, work, () -> {},
                    (lane, permit, original, character) -> character);
            binding.requireCurrent();
            job.scene = NativeContextChecks.captureScene(server, actor);
            if (actor != binding.characterRef()) throw new NativeActionService.Invalid("scene_actor_mismatch");
            job.position = new Point(actor.character().getPosition());
            checkpoint(job);
            job.engine = !actor.character().isChangingMaps() && entryPosition.equals(job.position)
                    ? RuntimeNavigationEngine.planFromEntry(actor.character(), job.target, () -> checkpoint(job))
                    : RuntimeNavigationEngine.plan(actor.character(), job.target, () -> checkpoint(job));
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
        } catch (CharacterRuntime.Invalid changed) {
            if (!"scene_membership_ended".equals(changed.reason())) throw changed;
            outcome = Outcome.CONTEXT_CHANGED; reason = changed.reason();
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
                if (ownsMotion) {
                    synchronized (this) { active = null; movement.endNavigation(); }
                }
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
        var failure = new AtomicReference<Throwable>();
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
                binding.requireCurrent();
                NativeContextChecks.requireSceneCurrent(server, job.scene);
                if (stopping && job.scene.actor().character().getHp() <= 0) {
                    var character = job.scene.actor().character();
                    job.position = new Point(character.getPosition());
                    frame.set(new RuntimeNavigationEngine.Frame(job.position, 0, 0, character.getStance(),
                            0, job.engine.tickMs(), true, RuntimeNavigationEngine.Mode.IDLE));
                    return;
                }
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
            } catch (RuntimeException | Error unavailable) { failure.set(unavailable); }
        };
        try {
            if (cleanup == null) job.context.submit(job.control, step, () -> {}, body, () -> {});
            else job.context.submitCleanup(cleanup, body, () -> {});
        } catch (RuntimeException rejected) {
            step.reject();
            throw rejected;
        }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof RuntimeException runtime) throw runtime;
        if (failure.get() instanceof Error error) throw error;
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
        binding.requireCurrent();
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
        final String control;
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
        Job(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work, Point target, int timeoutMs, String control) {
            this.context = context; this.work = work; this.target = target; this.timeoutMs = timeoutMs; this.control = control;
        }
    }
}
