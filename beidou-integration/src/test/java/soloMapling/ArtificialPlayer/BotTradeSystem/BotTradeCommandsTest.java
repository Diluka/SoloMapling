package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.server.Trade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real native escrow and inventory objects, with no datasource or game server. */
class BotTradeCommandsTest {
    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void repeatedQuoteDoesNotCreateOrReserveTheBudgetTwice() {
        Participant bot = participant(801, 0);
        try {
            BotTradeCommands.setMeso(bot.character, 100);
            BotTradeCommands.setMeso(bot.character, 100);
            assertEquals(100, bot.trade.soloGetMeso());
            assertEquals(0, bot.wallet.get());
            verify(bot.character, times(1)).gainMeso(100, false, true, false);
        } finally {
            BotRegistry.unregister(bot.character);
        }
    }

    @Test
    void loweringAndRestoringQuoteReusesReturnedBudget() {
        Participant bot = participant(802, 0);
        try {
            BotTradeCommands.setMeso(bot.character, 100);
            BotTradeCommands.setMeso(bot.character, 40);
            assertEquals(60, bot.wallet.get());
            BotTradeCommands.setMeso(bot.character, 100);
            assertEquals(0, bot.wallet.get());
            assertEquals(100, bot.trade.soloGetMeso());
            verify(bot.character, times(1)).gainMeso(100, false, true, false);
        } finally {
            BotRegistry.unregister(bot.character);
        }
    }

    @Test
    void quotePreparesOnlyTheShortfallInAnExistingBotWallet() {
        Participant bot = participant(803, 25);
        try {
            BotTradeCommands.setMeso(bot.character, 100);
            assertEquals(0, bot.wallet.get());
            assertEquals(100, bot.trade.soloGetMeso());
            verify(bot.character).gainMeso(75, false, true, false);
        } finally {
            BotRegistry.unregister(bot.character);
        }
    }

    @Test
    void unregisteredCharactersCannotReceivePreparedFunds() {
        Character player = mock(Character.class);
        assertThrows(IllegalArgumentException.class, () -> BotTradeCommands.setMeso(player, 100));
        verify(player, never()).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void advertisedStockIsMaterializedOnceAndCannotBeReservedAgainWhileEscrowed() {
        Character owner = mock(Character.class);
        Inventory inventory = new Inventory(owner, InventoryType.USE, (byte) 24);
        when(owner.getInventory(InventoryType.USE)).thenReturn(inventory);
        BotTradeInventory stock = new BotTradeInventory();
        Item advertised = new Item(2000000, (short) 0, (short) 1);
        Item owned = stock.stockForTrade(owner, advertised, (short) 1);
        assertNotNull(owned);
        assertSame(owned, stock.stockForTrade(owner, advertised, (short) 1));
        assertEquals(1, inventory.countById(2000000));
        Item reserved = owned.copy();
        inventory.removeItem(owned.getPosition(), (short) 1, false);
        stock.recordReservation(advertised, owned, reserved);
        assertNull(stock.stockForTrade(owner, advertised, (short) 1));
        assertEquals(0, inventory.countById(2000000));
    }

    @Test
    void cancelledNativeStackIsReusedWithoutCreatingNewStock() {
        Character owner = mock(Character.class);
        Inventory inventory = new Inventory(owner, InventoryType.USE, (byte) 24);
        when(owner.getInventory(InventoryType.USE)).thenReturn(inventory);
        BotTradeInventory stock = new BotTradeInventory();
        Item advertised = new Item(2000000, (short) 0, (short) 1);
        Item owned = stock.stockForTrade(owner, advertised, (short) 1);
        Item reserved = owned.copy();
        inventory.removeItem(owned.getPosition(), (short) 1, false);
        stock.recordReservation(advertised, owned, reserved);
        // Native stack return creates a new object and updates the escrow object's position.
        Item returned = reserved.copy();
        short returnedSlot = inventory.addItem(returned);
        reserved.setPosition(returnedSlot);
        assertSame(returned, stock.stockForTrade(owner, advertised, (short) 1));
        assertEquals(1, inventory.countById(2000000));
    }

    private static Participant participant(int id, int balance) {
        Character character = mock(Character.class);
        BotClient client = mock(BotClient.class);
        AtomicInteger wallet = new AtomicInteger(balance);
        when(character.getId()).thenReturn(id);
        when(character.getClient()).thenReturn(client);
        when(character.getMeso()).thenAnswer(ignored -> wallet.get());
        doAnswer(call -> { wallet.addAndGet(call.getArgument(0)); return null; })
                .when(character).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        Trade trade = new Trade((byte) 0, character);
        when(character.getTrade()).thenReturn(trade);
        BotRegistry.register(character);
        return new Participant(character, trade, wallet);
    }

    private record Participant(Character character, Trade trade, AtomicInteger wallet) { }
}
