package soloMapling.game.shop;

import soloMapling.game.inventory.SaleItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observed native interaction and sale facts; no native shop or item reference leaves the provider. */
public record ShopResult(Operation operation, Outcome outcome, String reason, int characterId, int npcId,
                         int npcOid, int shopId, long interactionId, int mesoBefore, int mesoAfter, List<Sale> sales,
                         boolean nativeDispatched, boolean cancelRequested) {
    public enum Operation { OPEN, SELL, CLOSE }
    public enum Outcome { OPENED, ALREADY_OPEN, CLOSED, ALREADY_CLOSED, SOLD, PARTIAL, REJECTED, CANCELLED }
    public enum SaleOutcome { SOLD, ITEM_CHANGED, INSUFFICIENT_QUANTITY, NATIVE_WHOLE_STACK_REQUIRED,
        NATIVE_REJECTED, CANCELLED, NOT_ATTEMPTED }

    public record Sale(SaleItem request, SaleOutcome outcome, int actualItemIdBefore, int actualItemIdAfter,
                       int quantityBefore, int quantityAfter, int mesoBefore, int mesoAfter,
                       boolean dispatchStarted, boolean handlerError, String reason) {
        public int quantityRemoved() { return dispatchStarted ? Math.max(0, quantityBefore - quantityAfter) : 0; }
        public int mesoDelta() { return mesoAfter - mesoBefore; }
        public Map<String, Object> toMap() {
            var value = new LinkedHashMap<String, Object>();
            value.put("inventory_type", request.inventoryType().name()); value.put("slot", request.slot());
            value.put("expected_item_id", request.expectedItemId()); value.put("requested_quantity", request.quantity());
            value.put("outcome", outcome.name()); value.put("reason", reason);
            value.put("actual_item_id_before", actualItemIdBefore); value.put("actual_item_id_after", actualItemIdAfter);
            value.put("quantity_before", quantityBefore); value.put("quantity_after", quantityAfter);
            value.put("quantity_removed", quantityRemoved()); value.put("meso_before", mesoBefore);
            value.put("meso_after", mesoAfter); value.put("meso_delta", mesoDelta());
            value.put("dispatch_started", dispatchStarted); value.put("handler_error", handlerError);
            return value;
        }
    }

    public ShopResult { sales = List.copyOf(sales); }
    public int mesoDelta() { return mesoAfter - mesoBefore; }
    public int quantityRemoved() { return sales.stream().mapToInt(Sale::quantityRemoved).sum(); }
    public Map<String, Object> toMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("operation", operation.name()); value.put("outcome", outcome.name()); value.put("reason", reason);
        value.put("character_id", characterId); value.put("npc_id", npcId); value.put("npc_oid", npcOid);
        value.put("shop_id", shopId); value.put("interaction_id", interactionId);
        value.put("meso_before", interactionId == 0 ? null : mesoBefore);
        value.put("meso_after", interactionId == 0 ? null : mesoAfter);
        value.put("meso_delta", mesoDelta()); value.put("quantity_removed", quantityRemoved());
        value.put("sales", sales.stream().map(Sale::toMap).toList());
        value.put("native_dispatched", nativeDispatched); value.put("cancel_requested", cancelRequested);
        return value;
    }
}
