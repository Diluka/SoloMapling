package soloMapling.game.action;

import java.util.LinkedHashMap;
import java.util.Map;

/** One native pickup's bounded receipts; it neither approaches a target nor owns a collection batch. */
public record PickupResult(Outcome outcome, String reason, int objectId, Integer itemId,
                           int groundQuantity, int inventoryQuantityDelta, boolean dispatched,
                           boolean groundRemoved, boolean identityVerified, boolean handlerError,
                           boolean cancelRequested) {
    public enum Outcome { COLLECTED, REJECTED, BUSY, CAPACITY_FULL, CANCELLED, PREEMPTED, INDETERMINATE,
        UNAVAILABLE, CONTEXT_CHANGED }

    public static PickupResult skipped(int objectId, Outcome outcome, String reason) {
        return new PickupResult(outcome, reason, objectId, null, 0, 0, false, false, false, false, false);
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        result.put("reason", reason);
        result.put("object_id", objectId);
        result.put("item_id", itemId);
        result.put("ground_quantity_before", groundQuantity);
        result.put("inventory_quantity_delta", inventoryQuantityDelta);
        result.put("dispatch_started", dispatched);
        result.put("ground_removed", groundRemoved);
        result.put("identity_verified", identityVerified);
        result.put("handler_error", handlerError);
        result.put("cancel_requested", cancelRequested);
        return result;
    }
}
