package soloMapling.game.navigation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual outcome of one navigation task; progress is absent when the provider was already busy. */
public record NavigationResult(Outcome outcome, String reason, Progress progress) {
    public enum Outcome { ARRIVED, BUSY, BLOCKED, CANCELLED, TIMED_OUT, UNSUPPORTED, NO_PATH, READ_FAILED, CONTEXT_CHANGED }
    public record Position(int x, int y) { }
    public record Progress(Position target, Position position, boolean stable, int steps, long elapsedMs,
                           List<String> edgeTypes) { }

    /** Preserve the shared function's existing JSON contract. */
    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        if (reason != null) result.put("reason", reason);
        if (progress != null) {
            result.put("target", Map.of("x", progress.target().x(), "y", progress.target().y()));
            if (progress.position() != null) {
                result.put("position", Map.of("x", progress.position().x(), "y", progress.position().y()));
            }
            result.put("stable", progress.stable());
            result.put("steps", progress.steps());
            result.put("elapsed_ms", progress.elapsedMs());
            result.put("edge_types", progress.edgeTypes());
        }
        return result;
    }
}
