package soloMapling.game.hunting;

import java.util.Map;

public record HuntingStopResult(Outcome outcome, String reason, HuntingSnapshot activity) {
    public enum Outcome { STOPPED, ALREADY_IDLE }
    public Map<String, Object> toMap() { return activity.response(outcome.name(), reason); }
}
