package soloMapling.game.scene;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;

/** A bounded observation, not another character/monster state store or a promise of future survival. */
public record IncomingDamageEstimate(Coverage coverage, long observedNanos, int windowMs,
        int hpLossLower, int hpLossUpper, List<Threat> threats, List<String> missing) {
    public enum Coverage { SUPPORTED, PARTIAL, UNKNOWN }
    public record PathSample(Point position, int elapsedMs) { }
    public record Threat(int monsterOid, int monsterId, Rectangle possibleContactArea,
                         int firstPossibleMs, int hpLossUpper, String motionModel) { }

    public IncomingDamageEstimate { threats = List.copyOf(threats); missing = List.copyOf(missing); }

    public Map<String, Object> toMap() {
        return Map.of("coverage", coverage.name(), "observed_at_nanos", observedNanos, "window_ms", windowMs,
                "hp_loss_lower", hpLossLower, "hp_loss_upper", hpLossUpper, "model", "contact_physical_v1",
                "threat_oids", threats.stream().map(Threat::monsterOid).toList(),
                "threats", threats.stream().map(threat -> Map.of("object_id", threat.monsterOid(), "template_id", threat.monsterId(),
                        "first_possible_ms", threat.firstPossibleMs(), "hp_loss_upper", threat.hpLossUpper(), "motion_model", threat.motionModel())).toList(),
                "missing", missing);
    }
}
