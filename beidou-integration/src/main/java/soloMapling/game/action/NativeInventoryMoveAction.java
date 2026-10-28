package soloMapling.game.action;

import org.gms.client.inventory.InventoryType;

/** One move between positive slots in the same ordinary inventory. */
public record NativeInventoryMoveAction(String inventoryType, int sourceSlot, int targetSlot) {
    public NativeInventoryMoveAction {
        if (inventoryType == null || !switch (inventoryType) {
            case "EQUIP", "USE", "SETUP", "ETC", "CASH" -> true;
            default -> false;
        }) throw new NativeActionService.Invalid("invalid_inventory_type");
        if (sourceSlot < 1 || sourceSlot > 127 || targetSlot < 1 || targetSlot > 127 || sourceSlot == targetSlot) {
            throw new NativeActionService.Invalid("invalid_inventory_move_slots");
        }
    }

    InventoryType nativeType() { return InventoryType.valueOf(inventoryType); }
}
