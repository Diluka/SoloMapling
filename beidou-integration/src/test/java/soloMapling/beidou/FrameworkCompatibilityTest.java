package soloMapling.beidou;

import org.gms.server.maps.PlayerShopItem;
import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.ArtificialShopItem;

import static org.junit.jupiter.api.Assertions.*;

class FrameworkCompatibilityTest {
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
