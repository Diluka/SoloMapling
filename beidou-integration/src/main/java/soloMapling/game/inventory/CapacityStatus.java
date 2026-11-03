package soloMapling.game.inventory;

import org.gms.client.inventory.InventoryType;

import java.util.List;
import java.util.Map;

public record CapacityStatus(List<InventoryType> triggeredTypes, int minimumFreeSlots) {
    public CapacityStatus { triggeredTypes = List.copyOf(triggeredTypes); }

    public boolean triggered() { return !triggeredTypes.isEmpty(); }
    public String reason() { return triggered() ? "minimum_free_slots_reached" : "capacity_available"; }

    public Map<String, Object> toMap() {
        return Map.of("triggered", triggered(), "reason", reason(), "minimum_free_slots", minimumFreeSlots,
                "inventory_types", triggeredTypes.stream().map(InventoryType::name).toList());
    }
}
