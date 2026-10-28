package soloMapling.game.action;

import java.util.List;

/** Finite ordinary-item operations; no caller-supplied item ID, handler or packet. */
public sealed interface NativeItemAction {
    String action();

    record Pickup(int objectId) implements NativeItemAction {
        public Pickup { if (objectId < 1) throw invalid("invalid_ground_object"); }
        public String action() { return "pickup"; }
    }
    record Drop(String inventoryType, int sourceSlot, int quantity) implements NativeItemAction {
        public Drop {
            if (inventoryType == null || !List.of("EQUIP", "USE", "SETUP", "ETC", "CASH").contains(inventoryType)) {
                throw invalid("invalid_inventory_type");
            }
            positive(sourceSlot);
            if (quantity < 1 || quantity > Short.MAX_VALUE) throw invalid("invalid_item_quantity");
        }
        public String action() { return "drop"; }
    }
    record Use(int sourceSlot) implements NativeItemAction {
        public Use { positive(sourceSlot); }
        public String action() { return "use_item"; }
    }
    record Equip(int sourceSlot, int targetSlot) implements NativeItemAction {
        public Equip { positive(sourceSlot); equipped(targetSlot); }
        public String action() { return "equip"; }
    }
    record Unequip(int sourceSlot, int targetSlot) implements NativeItemAction {
        public Unequip { equipped(sourceSlot); positive(targetSlot); }
        public String action() { return "unequip"; }
    }

    static List<Integer> equipmentSlots() {
        return List.of(-1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11, -12, -13,
                -15, -16, -17, -18, -19, -49, -50);
    }
    private static void positive(int slot) {
        if (slot < 1 || slot > 127) throw invalid("invalid_inventory_slot");
    }
    private static void equipped(int slot) {
        if (!equipmentSlots().contains(slot)) throw invalid("invalid_equipment_slot");
    }
    private static NativeActionService.Invalid invalid(String reason) { return new NativeActionService.Invalid(reason); }
}
