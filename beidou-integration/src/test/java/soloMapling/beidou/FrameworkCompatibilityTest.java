package soloMapling.beidou;

import org.gms.server.maps.PlayerShopItem;
import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.ArtificialShopItem;
import soloMapling.itemPool.EquipType;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.*;

class FrameworkCompatibilityTest {
    @Test
    void earringsAndEyeAccessoriesKeepSeparateCacheCategories() {
        assertEquals(EquipType.EARRING, EquipType.getEquipTypeById(1032000));
        EnumMap<EquipType, String> cache = new EnumMap<>(EquipType.class);
        cache.put(EquipType.ACCESSORY, "eye accessory");
        cache.put(EquipType.EARRING, "earring");
        assertEquals(2, cache.size());
        assertEquals("eye accessory", cache.get(EquipType.ACCESSORY));
        assertEquals("earring", cache.get(EquipType.EARRING));
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
