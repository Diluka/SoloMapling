package soloMapling.game.inventory;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Call from the bound character's existing lane; these reads do not enqueue work or retain native items. */
public final class InventoryFacts {
    public static final List<InventoryType> ORDINARY_TYPES = List.of(InventoryType.EQUIP, InventoryType.USE,
            InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH);

    private InventoryFacts() { }

    public static InventorySnapshot read(Character character) {
        var bags = new ArrayList<InventorySnapshot.Bag>();
        for (var type : ORDINARY_TYPES) {
            var inventory = character.getInventory(type);
            inventory.lockInventory();
            try {
                var items = inventory.list().stream().sorted(Comparator.comparingInt(Item::getPosition))
                        .map(item -> new InventorySnapshot.ItemSlot(type, item.getPosition(), item.getItemId(),
                                item.getQuantity())).toList();
                bags.add(new InventorySnapshot.Bag(type, Byte.toUnsignedInt(inventory.getSlotLimit()),
                        inventory.getNumFreeSlot(), items));
            } finally { inventory.unlockInventory(); }
        }
        return new InventorySnapshot(bags);
    }

    /** The caller supplies a supported ordinary item of positive quantity, for example a locked MapItem's item. */
    public static ItemCapacity capacity(Character character, Item item) {
        var type = item.getInventoryType();
        var inventory = character.getInventory(type);
        var client = character.getClient();
        inventory.lockInventory();
        try {
            int nativeCapacity = InventoryManipulator.checkSpaceProgressively(client, item.getItemId(),
                    item.getQuantity(), item.getOwner(), 0, false);
            int slots;
            if (type == InventoryType.EQUIP || ItemConstants.isRechargeable(item.getItemId()) || item.getPetId() >= 0) {
                slots = 1;
            } else {
                int slotMax = ItemInformationProvider.getInstance().getSlotMax(client, item.getItemId());
                // Native addFromDrop additionally matches flags; its generic capacity precheck only matches owner.
                int stackRoom = inventory.listById(item.getItemId()).stream()
                        .filter(existing -> existing.getFlag() == item.getFlag()
                                && existing.getOwner().equals(item.getOwner()))
                        .mapToInt(existing -> Math.max(0, slotMax - existing.getQuantity())).sum();
                int remaining = Math.max(0, item.getQuantity() - stackRoom);
                slots = slotMax > 0 ? (int) Math.ceil((double) remaining / slotMax) : 1;
            }
            int free = inventory.getNumFreeSlot();
            var outcome = nativeCapacity == 0 ? ItemCapacity.Outcome.PICKUP_RESTRICTED
                    : slots <= free ? ItemCapacity.Outcome.AVAILABLE : ItemCapacity.Outcome.NO_SPACE;
            return new ItemCapacity(type, item.getItemId(), item.getQuantity(), free, slots, outcome);
        } finally { inventory.unlockInventory(); }
    }
}
