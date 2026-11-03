package soloMapling.game.loot;

import soloMapling.game.action.NativeActionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded per-object facts; full native inventory snapshots stay with their original short action. */
public record LootResult(Outcome outcome, String reason, List<ItemResult> items, long elapsedMs) {
    public enum Outcome { COMPLETED, CAPACITY_FULL, INDETERMINATE, CANCELLED, TIMED_OUT, CONTEXT_CHANGED, DEAD, UNAVAILABLE, BUSY }
    public enum ItemOutcome { COLLECTED, NOT_FOUND, UNSUPPORTED, OWNER_REJECTED, TOO_FRESH, CAPACITY_FULL,
        PICKUP_RESTRICTED, UNREACHABLE, NATIVE_REJECTED, INDETERMINATE }

    public record ItemResult(int objectId, Integer itemId, Integer groundQuantity, int gainedQuantity,
                             ItemOutcome outcome, String reason, NativeActionResult.Outcome nativeOutcome,
                             boolean dispatchStarted) {
        public Map<String, Object> toMap() {
            var value = new LinkedHashMap<String, Object>();
            value.put("object_id", objectId);
            value.put("item_id", itemId);
            value.put("ground_quantity", groundQuantity);
            value.put("gained_quantity", gainedQuantity);
            value.put("outcome", outcome.name());
            value.put("reason", reason);
            value.put("native_outcome", nativeOutcome == null ? null : nativeOutcome.name());
            value.put("dispatch_started", dispatchStarted);
            return value;
        }
    }

    public LootResult { items = List.copyOf(items); }

    public int collectedObjects() { return (int) items.stream().filter(item -> item.outcome() == ItemOutcome.COLLECTED).count(); }
    public int collectedQuantity() {
        return items.stream().filter(item -> item.outcome() == ItemOutcome.COLLECTED).mapToInt(ItemResult::gainedQuantity).sum();
    }

    public Map<String, Object> toMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("outcome", outcome.name());
        value.put("reason", reason);
        value.put("items", items.stream().map(ItemResult::toMap).toList());
        value.put("collected_objects", collectedObjects());
        value.put("collected_quantity", collectedQuantity());
        value.put("elapsed_ms", elapsedMs);
        return value;
    }
}
