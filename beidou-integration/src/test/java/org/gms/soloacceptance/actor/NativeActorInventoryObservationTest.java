package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Inventory observations are fresh native facts and explicitly bounded, not a second inventory. */
class NativeActorInventoryObservationTest {
    private Character character;

    @BeforeEach
    void prepare() {
        CharacterTestContext.install();
        character = mock(Character.class);
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE,
                InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
            when(character.getInventory(type)).thenReturn(new Inventory(character, type, (byte) 127));
        }
    }

    @Test
    void observationsIncludeSlot127AndCopyValuesInsteadOfRetainingMutableItems() {
        Item item = new Item(2000000, (short) 127, (short) 7);
        character.getInventory(InventoryType.USE).addItemFromDB(item);
        Map<String, Object> before = NativeActorBackend.inventorySnapshot(character);
        assertEquals(true, before.get("complete"));
        assertEquals(5, inventories(before).size());
        Map<String, Object> use = inventory(before, "USE");
        assertEquals(127, use.get("slot_limit"));
        assertEquals(true, use.get("complete"));
        assertEquals(List.of(Map.of("slot", 127, "item_id", 2000000, "quantity", 7)), use.get("items"));
        item.setQuantity((short) 3);
        Map<String, Object> after = NativeActorBackend.inventorySnapshot(character);
        assertEquals(7, items(use).getFirst().get("quantity"));
        assertEquals(3, items(inventory(after, "USE")).getFirst().get("quantity"));
        assertThrows(UnsupportedOperationException.class, () -> before.put("complete", false));
        assertThrows(UnsupportedOperationException.class, () -> inventories(before).clear());
        assertThrows(UnsupportedOperationException.class, () -> use.put("complete", false));
        assertThrows(UnsupportedOperationException.class, () -> items(use).clear());
        assertThrows(UnsupportedOperationException.class, () -> items(use).getFirst().put("quantity", 99));
    }

    @Test
    void nativeSlotLimitsAboveThePublicBoundAreExplicitlyIncomplete() {
        Inventory expanded = new Inventory(character, InventoryType.USE, (byte) 128);
        expanded.addItemFromDB(new Item(2000000, (short) 127, (short) 7));
        expanded.addItemFromDB(new Item(2000001, (short) 128, (short) 9));
        when(character.getInventory(InventoryType.USE)).thenReturn(expanded);
        Map<String, Object> snapshot = NativeActorBackend.inventorySnapshot(character);
        assertEquals(false, snapshot.get("complete"));
        Map<String, Object> use = inventory(snapshot, "USE");
        assertEquals(128, use.get("slot_limit"));
        assertEquals(false, use.get("complete"));
        assertEquals(List.of(Map.of("slot", 127, "item_id", 2000000, "quantity", 7)), use.get("items"));
        assertEquals(true, inventory(snapshot, "ETC").get("complete"));
    }

    @Test
    void itemValuesAreReadWhileHoldingTheNativeInventoryLockAndTheLockIsReleased() {
        Inventory inventory = mock(Inventory.class);
        Item item = mock(Item.class);
        AtomicBoolean locked = new AtomicBoolean();
        doAnswer(call -> { assertFalse(locked.getAndSet(true)); return null; }).when(inventory).lockInventory();
        doAnswer(call -> { assertTrue(locked.getAndSet(false)); return null; }).when(inventory).unlockInventory();
        when(inventory.getSlotLimit()).thenReturn((byte) 1);
        when(inventory.list()).thenAnswer(call -> { assertTrue(locked.get()); return List.of(item); });
        when(inventory.getItem((short) 1)).thenAnswer(call -> { assertTrue(locked.get()); return item; });
        when(item.getPosition()).thenAnswer(call -> { assertTrue(locked.get()); return (short) 1; });
        when(item.getItemId()).thenAnswer(call -> { assertTrue(locked.get()); return 2000000; });
        when(item.getQuantity()).thenAnswer(call -> { assertTrue(locked.get()); return (short) 7; });
        when(character.getInventory(InventoryType.USE)).thenReturn(inventory);
        assertEquals(List.of(Map.of("slot", 1, "item_id", 2000000, "quantity", 7)),
                inventory(NativeActorBackend.inventorySnapshot(character), "USE").get("items"));
        assertFalse(locked.get());
        verify(inventory).lockInventory();
        verify(inventory).unlockInventory();
    }

    @Test
    void failedNativeReadsReleaseTheLockAndDoNotFabricateACompleteObservation() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSlotLimit()).thenReturn((byte) 1);
        when(inventory.list()).thenThrow(new IllegalStateException("unavailable"));
        when(inventory.getItem((short) 1)).thenThrow(new IllegalStateException("unavailable"));
        when(character.getInventory(InventoryType.USE)).thenReturn(inventory);
        assertThrows(RuntimeException.class, () -> NativeActorBackend.inventorySnapshot(character));
        verify(inventory).lockInventory();
        verify(inventory).unlockInventory();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inventories(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("inventories");
    }

    private static Map<String, Object> inventory(Map<String, Object> snapshot, String type) {
        return inventories(snapshot).stream().filter(item -> type.equals(item.get("inventory_type"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> inventory) {
        return (List<Map<String, Object>>) inventory.get("items");
    }
}
