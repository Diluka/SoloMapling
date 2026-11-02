package soloMapling.game.hunting;

import java.util.Map;

public record HuntingStartResult(Outcome outcome, String reason, HuntingSnapshot activity) {
    public enum Outcome { STARTED, BUSY, UNAVAILABLE, DEAD }
    public Map<String, Object> toMap() { return activity.response(outcome.name(), reason); }
}
