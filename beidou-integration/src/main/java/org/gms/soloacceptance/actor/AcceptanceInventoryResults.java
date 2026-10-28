package org.gms.soloacceptance.actor;

import soloMapling.game.action.InventoryMoveResult;
import soloMapling.game.action.NativeActionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** Typed native inventory facts projected to MCP; no inventory mutation or success inference. */
final class AcceptanceInventoryResults {
    private AcceptanceInventoryResults() { }

    static Map<String, Object> resultFields(InventoryMoveResult result) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("native_dispatch_started", result.dispatch() == NativeActionResult.Dispatch.STARTED);
        fields.put("observed_outcome", switch (result.outcome()) {
            case SUCCEEDED -> "native_inventory_move_observed";
            case CANCELLED -> "inventory_move_cancelled_before_dispatch";
            case FAILED -> result.dispatch() == NativeActionResult.Dispatch.NOT_STARTED
                    ? "inventory_move_not_dispatched" : "native_inventory_move_not_observed";
            case INDETERMINATE -> "native_inventory_outcome_unknown";
        });
        fields.put("sender_character_id", result.senderCharacterId());
        fields.put("native_handler_error", result.handlerError());
        InventoryMoveResult.Evidence evidence = result.evidence();
        fields.put("evidence_source", evidence.source().name());
        fields.put("identity_verified", evidence.identityVerified());
        fields.put("changed", evidence.changed());
        fields.put("inventory_type", evidence.inventoryType());
        fields.put("before", slots(evidence.before()));
        fields.put("after", slots(evidence.after()));
        return fields;
    }

    private static Map<String, Object> slots(InventoryMoveResult.Slots slots) {
        return slots == null ? null : Map.of("source", slot(slots.source()), "target", slot(slots.target()));
    }

    private static Map<String, Object> slot(InventoryMoveResult.Slot slot) {
        return Map.of("slot", slot.slot(), "present", slot.present(), "item_id", slot.itemId(), "quantity", slot.quantity());
    }
}
