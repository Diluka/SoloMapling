package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.InventoryMetadataTestContext;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcceptanceBasicObservationsTest {
    private Character character;

    @BeforeEach
    void prepare() throws Exception {
        InventoryMetadataTestContext.install();
        character = mock(Character.class);
    }

    @Test
    void currentNativeDerivedStatsAreReadWithoutBorrowingTheInventoryLock() {
        when(character.getTotalStr()).thenReturn(12);
        when(character.getTotalDex()).thenReturn(13);
        when(character.getTotalInt()).thenReturn(14);
        when(character.getTotalLuk()).thenReturn(15);
        when(character.getTotalWatk()).thenReturn(17);
        when(character.getTotalMagic()).thenReturn(18);
        when(character.getCurrentMaxHp()).thenReturn(60);
        when(character.getCurrentMaxMp()).thenReturn(40);
        when(character.getHp()).thenReturn(50);
        when(character.getMp()).thenReturn(30);
        Map<String, Object> result = AcceptanceBasicObservations.stats(character);
        assertEquals(Map.of("str", 12, "dex", 13, "int", 14, "luk", 15, "watk", 17, "magic", 18,
                "max_hp", 60, "max_mp", 40, "hp", 50, "mp", 30), result);
        verify(character, never()).getInventory(any());
        when(character.getHp()).thenReturn(45);
        assertEquals(50, result.get("hp"));
        assertEquals(45, AcceptanceBasicObservations.stats(character).get("hp"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("hp", 0));
    }

    @Test
    void equippedFactsIncludeCashSlotsAndImmutableNativeAttributesUnderTheInventoryLock() {
        Inventory inventory = mock(Inventory.class);
        when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(inventory);
        AtomicBoolean locked = new AtomicBoolean();
        installLock(inventory, locked);
        Equip sword = equip(-11, 1302000), cash = equip(-111, 1702000);
        AtomicInteger strength = new AtomicInteger(3);
        when(sword.getStr()).thenAnswer(call -> { assertTrue(locked.get()); return (short) strength.get(); });
        when(sword.getFlag()).thenReturn((short) 8);
        when(inventory.list()).thenAnswer(call -> { assertTrue(locked.get()); return List.of(sword, cash); });

        Map<String, Object> before = AcceptanceBasicObservations.equipped(character);
        assertEquals(true, before.get("complete"));
        assertEquals(false, before.get("truncated"));
        assertEquals(2, before.get("count"));
        assertEquals(-111, rows(before).getFirst().get("slot"));
        Map<String, Object> swordFact = rows(before).get(1);
        assertEquals(8, swordFact.get("flags"));
        assertEquals(3, attributes(swordFact).get("str"));
        strength.set(7);
        assertEquals(3, attributes(swordFact).get("str"));
        assertEquals(7, attributes(rows(AcceptanceBasicObservations.equipped(character)).get(1)).get("str"));
        assertFalse(locked.get());
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        assertThrows(UnsupportedOperationException.class, () -> rows(before).clear());
        assertThrows(UnsupportedOperationException.class, () -> swordFact.clear());
        assertThrows(UnsupportedOperationException.class, () -> attributes(swordFact).clear());
    }

    @Test
    void equippedBoundsAndUnexpectedNativeTypesNeverClaimACompleteSnapshot() {
        Inventory inventory = mock(Inventory.class);
        when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(inventory);
        List<Item> expanded = new ArrayList<>();
        for (int i = 1; i <= 128; i++) expanded.add(equip(-i, 1302000));
        when(inventory.list()).thenReturn(expanded);
        var bounded = AcceptanceBasicObservations.equipped(character);
        assertEquals(128, bounded.get("count"));
        assertEquals(127, rows(bounded).size());
        assertEquals(true, bounded.get("truncated"));
        assertEquals(false, bounded.get("complete"));

        when(inventory.list()).thenReturn(List.of(new Item(2000000, (short) -1, (short) 1)));
        var unexpected = AcceptanceBasicObservations.equipped(character);
        assertEquals(false, unexpected.get("complete"));
        assertNull(rows(unexpected).getFirst().get("attributes"));
        Equip unexpectedPositive = equip(1, 1302000);
        when(inventory.list()).thenReturn(List.of(unexpectedPositive));
        assertEquals(false, AcceptanceBasicObservations.equipped(character).get("complete"));
    }

    @Test
    void aFailedEquippedReadReleasesTheNativeLockAndPropagatesUnavailable() {
        Inventory inventory = mock(Inventory.class);
        when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(inventory);
        AtomicBoolean locked = new AtomicBoolean();
        installLock(inventory, locked);
        when(inventory.list()).thenThrow(new IllegalStateException("native read unavailable"));
        assertThrows(IllegalStateException.class, () -> AcceptanceBasicObservations.equipped(character));
        assertFalse(locked.get());
        verify(inventory).unlockInventory();
    }

    @Test
    void sceneFactsCopyNativeGroundItemsAndMonsterValuesWithoutRetainingObjects() {
        MapleMap map = mock(MapleMap.class);
        MapItem drop = mock(MapItem.class), coins = mock(MapItem.class);
        Item item = mock(Item.class);
        Monster monster = mock(Monster.class);
        AtomicBoolean dropLocked = new AtomicBoolean();
        doAnswer(call -> { assertFalse(dropLocked.getAndSet(true)); return null; }).when(drop).lockItem();
        doAnswer(call -> { assertTrue(dropLocked.getAndSet(false)); return null; }).when(drop).unlockItem();
        when(drop.getItem()).thenAnswer(call -> { assertTrue(dropLocked.get()); return item; });
        when(item.getItemId()).thenAnswer(call -> { assertTrue(dropLocked.get()); return 2000000; });
        when(item.getQuantity()).thenAnswer(call -> { assertTrue(dropLocked.get()); return (short) 4; });
        when(drop.getObjectId()).thenReturn(30);
        when(drop.getPosition()).thenReturn(new Point(1, 2));
        when(coins.getObjectId()).thenReturn(20);
        when(coins.getPosition()).thenReturn(new Point(3, 4));
        when(coins.getMeso()).thenReturn(12);
        when(monster.getObjectId()).thenReturn(10);
        when(monster.getId()).thenReturn(100100);
        when(monster.getPosition()).thenReturn(new Point(5, 6));
        when(monster.getHp()).thenAnswer(call -> { assertFalse(dropLocked.get()); return 8; });
        when(monster.getMaxHp()).thenReturn(10);
        when(map.getMapObjects()).thenReturn(List.of(drop, monster, coins));

        Map<String, Object> result = AcceptanceBasicObservations.scene(map);
        var drops = page(result, "items");
        var monsters = page(result, "monsters");
        assertEquals("best_effort", result.get("snapshot_consistency"));
        assertEquals(2, drops.get("total_count"));
        assertEquals(20, rows(drops).getFirst().get("object_id"));
        assertEquals(12, rows(drops).getFirst().get("meso"));
        assertNull(rows(drops).getFirst().get("item_id"));
        assertEquals(2000000, rows(drops).get(1).get("item_id"));
        assertEquals(4, rows(drops).get(1).get("quantity"));
        assertEquals(8, rows(monsters).getFirst().get("hp"));
        when(monster.getHp()).thenReturn(3);
        assertEquals(8, rows(monsters).getFirst().get("hp"));
        assertFalse(dropLocked.get());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        assertThrows(UnsupportedOperationException.class, () -> rows(drops).getFirst().clear());
        assertThrows(UnsupportedOperationException.class, () -> position(rows(monsters).getFirst()).clear());
    }

    @Test
    void scenePagesAreBoundedSeparatelyAndNativeReadFailuresAreNotEmptySuccesses() {
        MapleMap map = mock(MapleMap.class);
        List<MapObject> objects = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            MapItem item = mock(MapItem.class);
            when(item.getObjectId()).thenReturn(i);
            when(item.getPosition()).thenReturn(new Point(i, 0));
            objects.add(item);
            Monster monster = mock(Monster.class);
            when(monster.getObjectId()).thenReturn(1000 + i);
            when(monster.getPosition()).thenReturn(new Point(i, 0));
            objects.add(monster);
        }
        when(map.getMapObjects()).thenReturn(objects);
        var result = AcceptanceBasicObservations.scene(map);
        for (String kind : List.of("items", "monsters")) {
            assertEquals(100, rows(page(result, kind)).size());
            assertEquals(101, page(result, kind).get("total_count"));
            assertEquals(true, page(result, kind).get("truncated"));
            assertEquals(false, page(result, kind).get("complete"));
        }
        MapItem broken = mock(MapItem.class);
        when(broken.getItem()).thenThrow(new IllegalStateException("native read unavailable"));
        when(map.getMapObjects()).thenReturn(List.of(broken));
        assertThrows(IllegalStateException.class, () -> AcceptanceBasicObservations.scene(map));
        verify(broken).lockItem();
        verify(broken).unlockItem();
        when(map.getMapObjects()).thenThrow(new IllegalStateException("map unavailable"));
        assertThrows(IllegalStateException.class, () -> AcceptanceBasicObservations.scene(map));
    }

    private static Equip equip(int slot, int itemId) {
        Equip equip = mock(Equip.class);
        when(equip.getPosition()).thenReturn((short) slot);
        when(equip.getItemId()).thenReturn(itemId);
        when(equip.getQuantity()).thenReturn((short) 1);
        return equip;
    }

    private static void installLock(Inventory inventory, AtomicBoolean locked) {
        doAnswer(call -> { assertFalse(locked.getAndSet(true)); return null; }).when(inventory).lockInventory();
        doAnswer(call -> { assertTrue(locked.getAndSet(false)); return null; }).when(inventory).unlockInventory();
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> fact) {
        return (List<Map<String, Object>>) fact.get("items");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> attributes(Map<String, Object> fact) {
        return (Map<String, Object>) fact.get("attributes");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> page(Map<String, Object> fact, String kind) {
        return (Map<String, Object>) fact.get(kind);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> position(Map<String, Object> fact) {
        return (Map<String, Object>) fact.get("position");
    }
}
