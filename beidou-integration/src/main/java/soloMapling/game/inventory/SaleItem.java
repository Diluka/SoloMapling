package soloMapling.game.inventory;

import org.gms.client.inventory.InventoryType;

import java.util.Map;

/** Exact currently observed slot and quantity requested for one native sale. */
public record SaleItem(InventoryType inventoryType, short slot, int expectedItemId, int quantity) {
    public SaleItem {
        if (!InventoryFacts.ORDINARY_TYPES.contains(inventoryType) || slot < 1 || expectedItemId < 1
                || quantity < 1 || quantity > Short.MAX_VALUE) throw new IllegalArgumentException("invalid_sale_item");
    }

    public Map<String, Object> toMap() {
        return Map.of("inventory_type", inventoryType.name(), "slot", (int) slot,
                "expected_item_id", expectedItemId, "quantity", quantity);
    }
}
