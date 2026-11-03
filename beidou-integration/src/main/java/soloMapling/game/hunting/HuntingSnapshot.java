package soloMapling.game.hunting;

import soloMapling.game.loot.LootResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Safe facts of the current or most recently completed activity, without native role/map references. */
public record HuntingSnapshot(HuntingControlModule.State state, long activityId, String requestedStyle,
                              String style, Long durationMs, long elapsedMs, int engagements, int kills,
                              int navigationCalls, int lootCalls, int collectedObjects, int collectedQuantity,
                              boolean collecting,
                              LootResult.Outcome lootOutcome, String lootReason,
                              String endReason, Map<String, Object> placement) {
    public HuntingSnapshot { placement = Collections.unmodifiableMap(new LinkedHashMap<>(placement)); }

    public Map<String, Object> toMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("state", state.name());
        value.put("activity_id", activityId);
        value.put("requested_style", requestedStyle);
        value.put("style", style);
        value.put("duration_ms", durationMs);
        value.put("elapsed_ms", elapsedMs);
        value.put("engagements", engagements);
        value.put("kills", kills);
        value.put("navigation_calls", navigationCalls);
        value.put("loot_calls", lootCalls);
        value.put("collected_objects", collectedObjects);
        value.put("collected_quantity", collectedQuantity);
        value.put("collecting", collecting);
        value.put("loot_outcome", lootOutcome == null ? null : lootOutcome.name());
        value.put("loot_reason", lootReason);
        value.put("end_reason", endReason);
        value.put("placement", placement);
        return value;
    }

    Map<String, Object> response(String outcome, String reason) {
        var value = new LinkedHashMap<String, Object>();
        value.put("outcome", outcome);
        value.put("state", state.name());
        value.put("reason", reason);
        value.put("activity", toMap());
        return value;
    }
}
