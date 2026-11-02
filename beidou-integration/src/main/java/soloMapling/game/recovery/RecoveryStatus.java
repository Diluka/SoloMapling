package soloMapling.game.recovery;

import soloMapling.game.potion.PotionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded observations of the current or most recently stopped monitoring run. */
public record RecoveryStatus(State state, String reason, long checks, long restoreRequests, long cleanseRequests,
                             long restorations, long cleanses, PotionResult.State observation,
                             PotionResult lastRestore, PotionResult lastCleanse) {
    public enum State { IDLE, RUNNING, STOPPING }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("state", state.name());
        result.put("reason", reason);
        result.put("checks", checks);
        result.put("restore_requests", restoreRequests);
        result.put("cleanse_requests", cleanseRequests);
        result.put("restorations", restorations);
        result.put("cleanses", cleanses);
        result.put("observation", observation == null ? null : observation.toMap());
        result.put("last_restore", lastRestore == null ? null : lastRestore.toMap());
        result.put("last_cleanse", lastCleanse == null ? null : lastCleanse.toMap());
        return result;
    }
}
