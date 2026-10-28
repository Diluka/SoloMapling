package soloMapling.beidou;

import org.gms.server.maps.PlayerShopItem;
import org.gms.client.inventory.Equip;
import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.ArtificialShopItem;
import soloMapling.itemPool.ScrollInfoManager;

import static org.junit.jupiter.api.Assertions.*;

class FrameworkCompatibilityTest {
    @Test
    void earringsSelectTheirOwnScrollsWithoutTreatingEyeAccessoriesAsEarrings() {
        var scrolls = ScrollInfoManager.getScrollInfo(new Equip(1032000, (short) 1), "int");
        assertNotNull(scrolls);
        assertEquals(2040302, scrolls.getPercent_10());
        assertEquals(2040301, scrolls.getPercent_60());
        assertNull(ScrollInfoManager.getScrollInfo(new Equip(1022000, (short) 1), "int"));
    }

    @Test
    void dynamicQuotesChangeOnlyArtificialShopItems() {
        PlayerShopItem nativeItem = new PlayerShopItem(null, (short) 1, 100);
        PlayerShopItem artificialItem = new ArtificialShopItem(null, (short) 1, 100);

        ArtificialShopItem.reprice(artificialItem, 80);

        assertEquals(80, artificialItem.getPrice());
        assertEquals(100, nativeItem.getPrice());
        assertThrows(IllegalArgumentException.class, () -> ArtificialShopItem.reprice(nativeItem, 80));
        assertEquals(100, nativeItem.getPrice());
    }

    @Test
    void invalidQuoteDoesNotReplaceTheLastAcceptedPrice() {
        PlayerShopItem item = new ArtificialShopItem(null, (short) 1, 100);
        assertThrows(IllegalArgumentException.class, () -> ArtificialShopItem.reprice(item, -1));
        assertEquals(100, item.getPrice());
    }
}
