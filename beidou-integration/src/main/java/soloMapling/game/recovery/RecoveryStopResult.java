package soloMapling.game.recovery;

import java.util.Map;

public record RecoveryStopResult(Outcome outcome, RecoveryStatus status) {
    public enum Outcome { STOPPED, ALREADY_IDLE }

    public Map<String, Object> toMap() {
        var result = status.toMap();
        result.put("outcome", outcome.name());
        return result;
    }
}
