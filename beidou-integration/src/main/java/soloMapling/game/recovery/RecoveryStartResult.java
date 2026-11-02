package soloMapling.game.recovery;

import java.util.Map;

public record RecoveryStartResult(Outcome outcome, RecoveryStatus status) {
    public enum Outcome { STARTED, ALREADY_RUNNING, BUSY, UNAVAILABLE }

    public Map<String, Object> toMap() {
        var result = status.toMap();
        result.put("outcome", outcome.name());
        return result;
    }
}
