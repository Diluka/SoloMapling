package org.gms.server;

import org.gms.constants.inventory.EquipSlot;

/** Read-only package bridge to the original WZ equipment-slot metadata. */
public final class SoloItemMetadata {
    private SoloItemMetadata() { }

    public static boolean ordinaryEquipmentFits(int itemId, int slot) {
        ItemInformationProvider information = ItemInformationProvider.getInstance();
        return !information.isCash(itemId)
                && EquipSlot.getFromTextSlot(information.getEquipmentSlot(itemId)).isAllowed(slot, false);
    }
}
