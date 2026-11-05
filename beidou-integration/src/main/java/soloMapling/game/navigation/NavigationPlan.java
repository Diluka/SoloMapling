package soloMapling.game.navigation;

import soloMapling.ArtificialPlayer.GCMoveSystem.RuntimeNavigationEngine;
import soloMapling.game.context.GameContext;
import soloMapling.game.scene.SceneActorBinding;

import java.awt.Point;
import java.util.Map;
import java.util.LinkedHashMap;

/** A trusted local plan; its native identity and physics session never cross the JSON boundary. */
public final class NavigationPlan {
    public enum Outcome { PLANNED, NO_PATH, UNSUPPORTED, READ_FAILED, CONTEXT_CHANGED, CANCELLED, TIMED_OUT }
    private final GameContext.SceneRef scene;
    private final Point start;
    private final Point target;
    private final RuntimeNavigationEngine.Session engine;
    private final long observedNanos;
    private final int timeoutMs;
    private final NavigationActionModule.Config executionConfig;
    private final long deadlineNanos;
    private final Outcome outcome;
    private final String reason;
    private RuntimeNavigationEngine.Prediction prediction;

    private NavigationPlan(GameContext.SceneRef scene, Point start, Point target, RuntimeNavigationEngine.Session engine,
                           int timeoutMs, Outcome outcome, String reason, NavigationActionModule.Config executionConfig, long deadlineNanos) {
        this.scene = scene; this.start = new Point(start); this.target = new Point(target);
        this.engine = engine; observedNanos = System.nanoTime();
        this.timeoutMs = timeoutMs; this.outcome = outcome; this.reason = reason;
        this.executionConfig = executionConfig; this.deadlineNanos = deadlineNanos;
    }

    public static NavigationPlan prepare(SceneActorBinding binding, MoveToRequest input, NavigationActionModule.Config config, Runnable checkpoint) {
        binding.requireCurrent();
        int budget = Math.min(config.timeoutMs(), input.timeoutMs());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget);
        var actor = binding.characterRef().character();
        var start = new Point(actor.getPosition());
        var target = new Point(input.x(), input.y());
        RuntimeNavigationEngine.Session engine;
        try {
            engine = binding.scene().characters().motion(binding).atAcknowledgedEntry()
                    ? RuntimeNavigationEngine.planFromEntry(actor, target, checkpoint)
                    : RuntimeNavigationEngine.plan(actor, target, checkpoint);
        } catch (RuntimeNavigationEngine.Failure failure) {
            var outcome = failure.reason().startsWith("UNSUPPORTED") ? Outcome.UNSUPPORTED
                    : "NO_PATH".equals(failure.reason()) ? Outcome.NO_PATH : Outcome.READ_FAILED;
            return new NavigationPlan(binding.nativeScene(), start, target, null, budget, outcome, failure.reason(), config, deadline);
        }
        checkpoint.run();
        if (!start.equals(actor.getPosition())) return new NavigationPlan(binding.nativeScene(), start, target,
                null, budget, Outcome.CONTEXT_CHANGED, "navigation_plan_start_changed", config, deadline);
        var plan = new NavigationPlan(binding.nativeScene(), start, target, engine, budget, Outcome.PLANNED, null, config, deadline);
        plan.prediction = engine.preview(budget, checkpoint);
        return plan;
    }

    public GameContext.SceneRef scene() { return scene; }
    public Point start() { return new Point(start); }
    public Point target() { return new Point(target); }
    public RuntimeNavigationEngine.Session engine() { return engine; }
    public long observedNanos() { return observedNanos; }
    public int timeoutMs() { return timeoutMs; }
    public NavigationActionModule.Config executionConfig() { return executionConfig; }
    public int remainingMs() { return (int) Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())); }
    public Outcome outcome() { return outcome; }
    public String reason() { return reason; }
    public RuntimeNavigationEngine.Prediction prediction() { return prediction; }
    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>(Map.of("map_id", scene.map().getId(), "start", Map.of("x", start.x, "y", start.y),
                "target", Map.of("x", target.x, "y", target.y), "rules", "native_ground_navigation_v1",
                "execution", "trusted_java_scene_executor", "planned_at_nanos", observedNanos, "outcome", outcome.name()));
        if (reason != null) result.put("reason", reason);
        return Map.copyOf(result);
    }
}
