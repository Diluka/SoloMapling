package soloMapling.FreeMarket;

import org.gms.client.inventory.Item;
import org.gms.server.maps.PlayerShopItem;

/** Mutable quotes belong to artificial shops; native player shop prices stay unchanged. */
public final class ArtificialShopItem extends PlayerShopItem {
    private volatile int quotedPrice;

    public ArtificialShopItem(Item item, short bundles, int price) {
        super(item, bundles, price);
        quotedPrice = price;
    }

    @Override
    public int getPrice() {
        return quotedPrice;
    }

    public static void reprice(PlayerShopItem item, int price) {
        if (!(item instanceof ArtificialShopItem artificial)) {
            throw new IllegalArgumentException("Only artificial shop items support dynamic quotes");
        }
        if (price < 0) {
            throw new IllegalArgumentException("Shop price must not be negative");
        }
        artificial.quotedPrice = price;
    }
}
