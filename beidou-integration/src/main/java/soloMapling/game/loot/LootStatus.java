package soloMapling.game.loot;

import soloMapling.game.action.PickupResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** Monotonic pickup receipts let schedules take their own cycle baselines without owning this control. */
public record LootStatus(State state, String reason, long sequence, long collectedObjects, long collectedQuantity,
                          long indeterminateSequence, long equipmentActivityId, Integer equipmentObjectId,
                          PickupResult latest) {
    public enum State { IDLE, RUNNING, STOPPING }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("state", state.name());
        result.put("reason", reason);
        result.put("sequence", sequence);
        result.put("collected_objects", collectedObjects);
        result.put("collected_quantity", collectedQuantity);
        result.put("indeterminate_sequence", indeterminateSequence);
        result.put("equipment_activity_id", equipmentActivityId);
        result.put("equipment_object_id", equipmentObjectId);
        result.put("latest", latest == null ? null : latest.toMap());
        return result;
    }
}
