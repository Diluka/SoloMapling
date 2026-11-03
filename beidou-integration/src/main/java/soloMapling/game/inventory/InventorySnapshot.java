package soloMapling.game.inventory;

import org.gms.client.inventory.InventoryType;

import java.util.List;
import java.util.Map;

/** A detached observation of the five ordinary bags, never an inventory owner. */
public record InventorySnapshot(List<Bag> bags) {
    public InventorySnapshot { bags = List.copyOf(bags); }

    public Bag bag(InventoryType type) {
        return bags.stream().filter(bag -> bag.inventoryType() == type).findFirst().orElseThrow();
    }

    public Map<String, Object> toMap() {
        return Map.of("inventories", bags.stream().map(Bag::toMap).toList());
    }

    public record Bag(InventoryType inventoryType, int slotLimit, int freeSlots, List<ItemSlot> items) {
        public Bag { items = List.copyOf(items); }

        public Map<String, Object> toMap() {
            return Map.of("inventory_type", inventoryType.name(), "slot_limit", slotLimit,
                    "free_slots", freeSlots, "items", items.stream().map(ItemSlot::toMap).toList());
        }
    }

    public record ItemSlot(InventoryType inventoryType, short slot, int itemId, int quantity) {
        public Map<String, Object> toMap() {
            return Map.of("slot", (int) slot, "item_id", itemId, "quantity", quantity);
        }
    }
}
