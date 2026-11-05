package soloMapling.game.tactics;

import soloMapling.game.scene.IncomingDamageEstimate;

import java.util.Map;

public record EngagementAdmission(Outcome outcome, String reason, IncomingDamageEstimate risk) {
    public enum Outcome { ALLOWED, WAIT, UNAVAILABLE }
    public Map<String, Object> toMap() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name()); result.put("reason", reason);
        if (risk != null) result.put("risk", risk.toMap());
        return Map.copyOf(result);
    }
}
