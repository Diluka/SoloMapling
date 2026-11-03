package soloMapling.game.inventory;

import org.gms.client.inventory.InventoryType;

import java.util.Map;

/** Physical bag capacity and native pickup restriction for one currently observed item. */
public record ItemCapacity(InventoryType inventoryType, int itemId, int quantity, int freeSlots,
                           int newSlotsRequired, Outcome outcome) {
    public enum Outcome { AVAILABLE, NO_SPACE, PICKUP_RESTRICTED }

    public boolean fits() { return outcome == Outcome.AVAILABLE; }

    public Map<String, Object> toMap() {
        return Map.of("inventory_type", inventoryType.name(), "item_id", itemId, "quantity", quantity,
                "free_slots", freeSlots, "new_slots_required", newSlotsRequired, "outcome", outcome.name());
    }
}
