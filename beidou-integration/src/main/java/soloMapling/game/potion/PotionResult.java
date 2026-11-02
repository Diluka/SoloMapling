package soloMapling.game.potion;

import org.gms.client.Disease;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observed potion effects and the two provider-owned cooldowns at the native command's completion. */
public record PotionResult(Outcome outcome, int itemId, PotionItem item, State before, State after,
                           int hpRestored, int mpRestored, List<Disease> removedDiseases,
                           Cooldowns cooldowns, boolean dispatched, boolean cancelRequested) {
    public enum Outcome { APPLIED, NO_EFFECT, COOLDOWN, UNSUPPORTED, DEAD, CANCELLED }

    public record State(boolean alive, int hp, int mp, int maxHp, int maxMp, List<Disease> diseases) {
        public State { diseases = List.copyOf(diseases); }

        public Map<String, Object> toMap() {
            return Map.of("alive", alive, "hp", hp, "mp", mp, "max_hp", maxHp, "max_mp", maxMp,
                    "diseases", diseases.stream().map(Enum::name).toList());
        }
    }

    public record Cooldowns(long restoreMs, long cleanseMs) { }

    public PotionResult { removedDiseases = List.copyOf(removedDiseases); }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        result.put("itemId", itemId);
        result.put("supported_item", item == null ? null : item.name());
        result.put("cooldown_group", item == null ? null : item.group().id());
        result.put("effective", outcome == Outcome.APPLIED);
        result.put("before", before.toMap());
        result.put("after", after.toMap());
        result.put("hp_restored", hpRestored);
        result.put("mp_restored", mpRestored);
        result.put("removed_diseases", removedDiseases.stream().map(Enum::name).toList());
        result.put("remaining_diseases", after.diseases().stream().map(Enum::name).toList());
        result.put("cooldowns", Map.of("restore_ms", cooldowns.restoreMs(), "cleanse_ms", cooldowns.cleanseMs()));
        result.put("dispatch_started", dispatched);
        result.put("cancel_requested", cancelRequested);
        return result;
    }
}
