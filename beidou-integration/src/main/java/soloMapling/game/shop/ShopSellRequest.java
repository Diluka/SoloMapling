package soloMapling.game.shop;

import soloMapling.game.inventory.SaleItem;

import java.util.List;

/** An explicit finite sale intent; native shop rules decide each item's eligibility and value. */
public record ShopSellRequest(List<SaleItem> items) {
    public ShopSellRequest {
        items = List.copyOf(items);
        if (items.isEmpty() || items.size() > 32) throw new IllegalArgumentException("invalid_shop_sale_count");
    }
}
