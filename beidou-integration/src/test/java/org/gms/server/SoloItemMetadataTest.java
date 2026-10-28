package org.gms.server;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.InventoryMetadataTestContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SoloItemMetadataTest {
    @Test void originalWzSlotDefinitionControlsOrdinaryEquipmentWithoutTriggeringNativeAnticheat() throws Exception {
        InventoryMetadataTestContext.install();
        ItemInformationProvider information = mock(ItemInformationProvider.class);
        when(information.getEquipmentSlot(1302000)).thenReturn("Wp");
        when(information.getEquipmentSlot(1040002)).thenReturn("Ma");
        when(information.getEquipmentSlot(1112000)).thenReturn("Ri");
        try (MockedStatic<ItemInformationProvider> source = mockStatic(ItemInformationProvider.class)) {
            source.when(ItemInformationProvider::getInstance).thenReturn(information);
            assertTrue(SoloItemMetadata.ordinaryEquipmentFits(1302000, -11));
            assertFalse(SoloItemMetadata.ordinaryEquipmentFits(1302000, -1));
            assertTrue(SoloItemMetadata.ordinaryEquipmentFits(1040002, -5));
            assertTrue(SoloItemMetadata.ordinaryEquipmentFits(1112000, -15));
            assertFalse(SoloItemMetadata.ordinaryEquipmentFits(1112000, -14));
            when(information.isCash(1302000)).thenReturn(true);
            assertFalse(SoloItemMetadata.ordinaryEquipmentFits(1302000, -111));
            assertFalse(SoloItemMetadata.ordinaryEquipmentFits(1302000, -11));
        }
    }
}
