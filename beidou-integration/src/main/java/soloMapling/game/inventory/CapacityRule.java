package soloMapling.game.inventory;

import org.gms.client.inventory.InventoryType;

import java.util.Set;

/** Return-to-shop threshold; any watched bag at or below the configured free-slot count triggers it. */
public record CapacityRule(Set<InventoryType> inventoryTypes, int minimumFreeSlots) {
    public CapacityRule {
        inventoryTypes = Set.copyOf(inventoryTypes);
        if (inventoryTypes.isEmpty() || !InventoryFacts.ORDINARY_TYPES.containsAll(inventoryTypes)
                || minimumFreeSlots < 0) throw new IllegalArgumentException("invalid_capacity_rule");
    }

    public CapacityStatus evaluate(InventorySnapshot snapshot) {
        return new CapacityStatus(snapshot.bags().stream().filter(bag -> inventoryTypes.contains(bag.inventoryType())
                        && bag.freeSlots() <= minimumFreeSlots).map(InventorySnapshot.Bag::inventoryType).toList(),
                minimumFreeSlots);
    }
}
