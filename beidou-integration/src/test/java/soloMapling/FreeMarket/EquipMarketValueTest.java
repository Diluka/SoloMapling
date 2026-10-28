package soloMapling.FreeMarket;

import org.gms.client.inventory.Equip;
import org.gms.server.ItemInformationProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.itemPool.UpgradeSimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class EquipMarketValueTest {
    @Test
    void aScrolledEarringIncludesItsUpgradeCostWhileAnEyeAccessoryKeepsItsBaseQuote() throws Exception {
        InventoryMetadataTestContext.install();
        Equip cleanEarring = new Equip(1032001, (short) 0, 5);
        Equip cleanEyeAccessory = new Equip(1022000, (short) 0, 5);
        ItemInformationProvider nativeItems = mock(ItemInformationProvider.class);
        when(nativeItems.getEquipById(1032001)).thenReturn(cleanEarring);
        when(nativeItems.getEquipById(1022000)).thenReturn(cleanEyeAccessory);
        try (MockedStatic<ItemInformationProvider> metadata = mockStatic(ItemInformationProvider.class)) {
            metadata.when(ItemInformationProvider::getInstance).thenReturn(nativeItems);
            Equip earring = new Equip(1032001, (short) 1, 4);
            earring.setInt((short) 2);
            Equip eyeAccessory = new Equip(1022000, (short) 1, 4);
            eyeAccessory.setInt((short) 2);

            // The current item pool has a 1,000-meso clean earring and a 1,000,000-meso INT 70% scroll.
            // Its expected upgrade cost is 1,430,000 mesos; the normal 60% selling discount gives 858,000.
            assertEquals(858_000, UpgradeSimulator.getEquipMarketValue(earring));
            assertEquals(100, UpgradeSimulator.getEquipMarketValue(eyeAccessory));
        }
    }
}
