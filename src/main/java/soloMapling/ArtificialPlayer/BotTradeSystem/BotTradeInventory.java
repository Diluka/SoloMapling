package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.Item;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class BotTradeInventory {

    private List<Item> itemsForSale;
    private final Map<Item, Item> nativeStock = new IdentityHashMap<>();

    public BotTradeInventory() {
        itemsForSale = new ArrayList<>(9);
    }

    protected List<Item> getListItemsForSale() {
        return this.itemsForSale;
    }

    public synchronized void setItemForSaleMain(Item item) {
        if (getListItemsForSale().isEmpty()) {
            getListItemsForSale().add(item); // Add if empty
        } else {
            getListItemsForSale().set(0, item); // Replace if there's an item at index 0
        }
    }

    protected void addItemForSale(Item item) {
        getListItemsForSale().add(item);
    }

    protected void removeItemForSale(Item item) {
        getListItemsForSale().remove(item);
    }

    public Item getMainItemForSale() {
        if (!getListItemsForSale().isEmpty()) {
            return getListItemsForSale().getFirst();
        }
        return null;
    }

    protected synchronized void resetItemsForSale() {
        this.itemsForSale = new ArrayList(9);
        nativeStock.clear();
    }

    /** Materialize existing bot stock once; later offers use its native remainder or return. */
    synchronized Item stockForTrade(Character owner, Item advertised, short quantity) {
        if (advertised == null || quantity <= 0) return null;
        Inventory inventory = owner.getInventory(advertised.getInventoryType());
        Item stocked = nativeStock.get(advertised);
        if (stocked != null) {
            Item owned = inventory.getItem(stocked.getPosition());
            // Native cancellation may merge a stack or create a new stack object.
            boolean sameReturnedStack = owned != null && !(advertised instanceof org.gms.client.inventory.Equip)
                    && owned.getItemId() == stocked.getItemId() && owned.getFlag() == stocked.getFlag()
                    && owned.getOwner().equals(stocked.getOwner());
            return (owned == stocked || sameReturnedStack) && owned.getQuantity() >= quantity ? owned : null;
        }
        if (advertised.getQuantity() < quantity) return null;
        stocked = advertised.copy();
        if (inventory.addItem(stocked) < 0) return null;
        nativeStock.put(advertised, stocked);
        return stocked;
    }

    synchronized void recordReservation(Item advertised, Item source, Item reserved) {
        // Native cancellation adds the reserved object back and updates its position.
        if (source.getQuantity() == 0) nativeStock.put(advertised, reserved);
    }

}
