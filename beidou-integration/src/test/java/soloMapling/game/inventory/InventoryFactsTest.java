package soloMapling.game.inventory;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.server.ItemInformationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.InventoryMetadataTestContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real native bags and insertion/capacity rules; native item metadata and the owning client are explicit seams. */
class InventoryFactsTest {
    private Character character;
    private Client client;
    private ItemInformationProvider information;
    private MockedStatic<ItemInformationProvider> metadata;
    private final Map<InventoryType, Inventory> bags = new HashMap<>();

    @BeforeEach void nativeBags() throws Exception {
        InventoryMetadataTestContext.install();
        character = mock(Character.class);
        client = mock(Client.class);
        when(character.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(character);
        information = mock(ItemInformationProvider.class);
        when(information.getSlotMax(any(), anyInt())).thenReturn((short) 100);
        metadata = mockStatic(ItemInformationProvider.class);
        metadata.when(ItemInformationProvider::getInstance).thenReturn(information);
        for (var type : List.of(InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.USE,
                InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
            var bag = new Inventory(character, type, (byte) 2);
            bags.put(type, bag);
            when(character.getInventory(type)).thenReturn(bag);
        }
    }

    @AfterEach void closeMetadataBoundary() { metadata.close(); }

    @Test void ordinaryBagFactsDistinguishStackingNewSlotsAndNativeRestrictions() {
        var etc = bags.get(InventoryType.ETC);
        etc.addItem(new Item(4000000, (short) 0, (short) 90));
        etc.addItem(new Item(4000001, (short) 0, (short) 1));
        bags.get(InventoryType.USE).addItem(new Item(2070000, (short) 0, (short) 10));
        var snapshot = InventoryFacts.read(character);
        assertEquals(InventoryFacts.ORDINARY_TYPES, snapshot.bags().stream().map(InventorySnapshot.Bag::inventoryType).toList());
        assertEquals(0, snapshot.bag(InventoryType.ETC).freeSlots());
        assertEquals(2, snapshot.bag(InventoryType.EQUIP).freeSlots());
        assertEquals(new InventorySnapshot.ItemSlot(InventoryType.ETC, (short) 1, 4000000, 90),
                snapshot.bag(InventoryType.ETC).items().getFirst());

        var stack = InventoryFacts.capacity(character, new Item(4000000, (short) 0, (short) 10));
        assertTrue(stack.fits());
        assertEquals(0, stack.newSlotsRequired());
        var newStack = InventoryFacts.capacity(character, new Item(4000002, (short) 0, (short) 1));
        assertEquals(ItemCapacity.Outcome.NO_SPACE, newStack.outcome());
        assertEquals(1, newStack.newSlotsRequired());
        assertEquals(1, InventoryFacts.capacity(character, new Equip(1302000, (short) 0)).newSlotsRequired());
        assertEquals(1, InventoryFacts.capacity(character, new Item(2070000, (short) 0, (short) 10)).newSlotsRequired());

        when(information.isPickupRestricted(4000000)).thenReturn(true);
        assertEquals(ItemCapacity.Outcome.PICKUP_RESTRICTED,
                InventoryFacts.capacity(character, new Item(4000000, (short) 0, (short) 10)).outcome());
        assertEquals(90, etc.countById(4000000));
        assertEquals(0, etc.getNumFreeSlot());
    }

    @Test void actualFlagMismatchNeedsANewSlotEvenWhenNativeGenericPrecheckWouldMerge() {
        var etc = bags.get(InventoryType.ETC);
        var existing = new Item(4000000, (short) 0, (short) 90);
        existing.setFlag((short) 1);
        etc.addItem(existing);
        etc.addItem(new Item(4000001, (short) 0, (short) 1));
        var incoming = new Item(4000000, (short) 0, (short) 10);

        assertEquals(1, InventoryManipulator.checkSpaceProgressively(client, 4000000, 10, "", 0, false));
        var capacity = InventoryFacts.capacity(character, incoming);
        assertEquals(ItemCapacity.Outcome.NO_SPACE, capacity.outcome());
        assertEquals(1, capacity.newSlotsRequired());
        assertFalse(InventoryManipulator.addFromDrop(client, incoming, false));
        assertEquals(90, etc.countById(4000000));
    }

    @Test void nativeInsertionTriggersWatchedCapacityAndNativeRemovalFreesIt() {
        var etc = bags.get(InventoryType.ETC);
        etc.addItem(new Item(4000000, (short) 0, (short) 100));
        var use = bags.get(InventoryType.USE);
        use.addItem(new Item(2000000, (short) 0, (short) 100));
        use.addItem(new Item(2000001, (short) 0, (short) 100));
        var rule = new CapacityRule(Set.of(InventoryType.ETC), 0);
        assertFalse(rule.evaluate(InventoryFacts.read(character)).triggered());

        assertTrue(InventoryManipulator.addFromDrop(client, new Item(4000001, (short) 0, (short) 1), false));
        var full = InventoryFacts.read(character);
        var status = rule.evaluate(full);
        assertTrue(status.triggered());
        assertEquals(List.of(InventoryType.ETC), status.triggeredTypes());
        assertEquals("minimum_free_slots_reached", status.reason());
        var sales = new SaleRules(Set.of(4000001), Set.of(), Map.of()).candidates(full);
        assertEquals(List.of(new SaleItem(InventoryType.ETC, (short) 2, 4000001, 1)), sales);
        assertEquals(1, etc.countById(4000001), "observing and selecting candidates does not remove inventory");

        InventoryManipulator.removeFromSlot(client, InventoryType.ETC, (short) 2, (short) 1, false);
        var after = InventoryFacts.read(character);
        assertEquals(1, after.bag(InventoryType.ETC).freeSlots());
        assertFalse(rule.evaluate(after).triggered());
    }

    @Test void saleRulesKeepProtectedIdsAndOneTotalReserveAcrossActualStacks() {
        var etc = bags.get(InventoryType.ETC);
        etc.setSlotLimit(6);
        etc.addItem(new Item(4000000, (short) 0, (short) 100));
        etc.addItem(new Item(4000000, (short) 0, (short) 100));
        etc.addItem(new Item(4000001, (short) 0, (short) 3));
        etc.addItem(new Item(4000002, (short) 0, (short) 9));
        etc.addItem(new Item(4000000, (short) 0, (short) 50));
        var rule = new SaleRules(Set.of(4000000, 4000001), Set.of(4000001), Map.of(4000000, 150));

        assertEquals(List.of(new SaleItem(InventoryType.ETC, (short) 2, 4000000, 50),
                        new SaleItem(InventoryType.ETC, (short) 5, 4000000, 50)),
                rule.candidates(InventoryFacts.read(character)));
        assertEquals(250, etc.countById(4000000));
        assertEquals(3, etc.countById(4000001));
        assertEquals(9, etc.countById(4000002));
    }
}
