package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.provider.Data;
import org.gms.provider.DataDirectoryEntry;
import org.gms.provider.DataFileEntry;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.DataType;
import org.gms.provider.wz.WZFiles;
import org.gms.server.ItemInformationProvider;
import org.gms.server.Trade;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real bot FSM, native item rules, native inventories and native meso cancellation. */
class BotTradeSMFailureTest {
    private static final int UNTRADEABLE = 2000991;
    private static final int SALE_ITEM = 2000992;
    private static final int OTHER_ITEM = 2000993;

    @BeforeAll
    static void contextAndItemMetadata() throws Exception {
        CharacterTestContext.install();
        DataProvider provider = itemMetadata();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        // The provider constructor normally reads monster-card JDBC data. Keep that
        // external input in memory while running the actual item restriction rules.
        try (MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class);
             MockedStatic<DataProviderFactory> data = mockStatic(DataProviderFactory.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            data.when(() -> DataProviderFactory.getDataProvider(any(WZFiles.class))).thenReturn(provider);
            ItemInformationProvider.getInstance();
        }
    }

    @Test
    void untradeableAdvertisedItemCancelsAndRefundsNativeBuyerEscrow() {
        assertTrue(ItemInformationProvider.getInstance().isDropRestricted(UNTRADEABLE));
        Sale sale = sale(901, UNTRADEABLE, (byte) 24);
        try {
            sale.fsm.update();
            assertAbortedWithoutPayment(sale);
            assertEquals(0, sale.seller.inventory.countById(UNTRADEABLE));
        } finally {
            release(sale);
        }
    }

    @Test
    void fullNativeSellerInventoryCannotAdvanceToCollectPayment() {
        assertFalse(ItemInformationProvider.getInstance().isDropRestricted(SALE_ITEM));
        Sale sale = sale(903, SALE_ITEM, (byte) 1);
        Item blocker = new Item(OTHER_ITEM, (short) 0, (short) 1);
        sale.seller.inventory.addItem(blocker);
        try {
            sale.fsm.update();
            assertAbortedWithoutPayment(sale);
            assertEquals(1, sale.seller.inventory.countById(OTHER_ITEM));
            assertEquals(0, sale.seller.inventory.countById(SALE_ITEM));
        } finally {
            release(sale);
        }
    }

    @Test
    void changedSellerEscrowIsRejectedAgainImmediatelyBeforeConfirmation() {
        Sale sale = sale(905, SALE_ITEM, (byte) 24);
        try {
            sale.fsm.update();
            assertEquals(BotTradeSM.TradeState.RESPONDING, sale.fsm.getTradeState());
            assertEquals(SALE_ITEM, sale.seller.trade.getItems().getFirst().getItemId());
            sale.fsm.update();
            sale.fsm.update();
            assertEquals(BotTradeSM.TradeState.CONFIRMING, sale.fsm.getTradeState());
            Item replacement = new Item(OTHER_ITEM, (short) 1, (short) 1);
            assertTrue(sale.seller.trade.swapItem(replacement));
            sale.fsm.update();
            assertAbortedWithoutPayment(sale);
        } finally {
            release(sale);
        }
    }

    private static void assertAbortedWithoutPayment(Sale sale) {
        assertTrue(sale.fsm.isTradeComplete());
        assertEquals(BotTradeSM.TradeState.COMPLETED, sale.fsm.getTradeState());
        assertNull(sale.seller.character.getTrade());
        assertNull(sale.buyer.character.getTrade());
        assertEquals(0, sale.seller.wallet.get());
        assertEquals(1_000, sale.buyer.wallet.get());
        assertEquals(0, sale.buyer.trade.soloGetMeso());
        assertTrue(sale.seller.trade.getItems().isEmpty());
        sale.fsm.update();
        Trade.cancelTrade(sale.buyer.character, Trade.TradeResult.PARTNER_CANCEL);
        assertEquals(1_000, sale.buyer.wallet.get(), "A repeated cancellation must not refund twice");
        verify(sale.bot, never()).setLastTradeResult(Trade.TradeResult.SUCCESSFUL);
    }

    private static Sale sale(int sellerId, int advertisedId, byte capacity) {
        Participant seller = participant(sellerId, 0, true, capacity);
        Participant buyer = participant(sellerId + 1, 1_000, false, (byte) 24);
        seller.trade.setPartner(buyer.trade);
        buyer.trade.setPartner(seller.trade);
        buyer.trade.setMeso(500);
        assertEquals(500, buyer.wallet.get(), "The real native trade must reserve the buyer's funds first");
        BotSM bot = mock(BotSM.class);
        BotTradeInventory stock = new BotTradeInventory();
        stock.setItemForSaleMain(new Item(advertisedId, (short) 0, (short) 1));
        BotTradeWants wants = new BotTradeWants();
        wants.setMesoWanted(500);
        when(bot.getChr()).thenReturn(seller.character);
        when(bot.getTradeInventory()).thenReturn(stock);
        when(bot.getTradeWants()).thenReturn(wants);
        CharacterStorage.addActiveBot(sellerId, bot);
        return new Sale(seller, buyer, bot, new BotTradeSM(bot));
    }

    private static Participant participant(int id, int balance, boolean bot, byte capacity) {
        Character character = mock(Character.class);
        Client client = bot ? mock(BotClient.class) : mock(Client.class);
        AtomicInteger wallet = new AtomicInteger(balance);
        AtomicReference<Trade> active = new AtomicReference<>();
        Inventory inventory = new Inventory(character, InventoryType.USE, capacity);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn("sale-test-" + id);
        when(character.getClient()).thenReturn(client);
        when(character.getInventory(InventoryType.USE)).thenReturn(inventory);
        when(character.getMeso()).thenAnswer(ignored -> wallet.get());
        when(character.getTrade()).thenAnswer(ignored -> active.get());
        doAnswer(call -> { active.set(call.getArgument(0)); return null; })
                .when(character).setTrade(nullable(Trade.class));
        doAnswer(call -> { wallet.addAndGet(call.getArgument(0)); return null; })
                .when(character).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        when(client.getPlayer()).thenReturn(character);
        Trade trade = new Trade((byte) 0, character);
        active.set(trade);
        if (bot) BotRegistry.register(character);
        return new Participant(character, trade, wallet, inventory);
    }

    private static void release(Sale sale) {
        CharacterStorage.removeActiveBot(sale.seller.character.getId());
        BotRegistry.unregister(sale.seller.character);
    }

    private static DataProvider itemMetadata() {
        DataProvider provider = mock(DataProvider.class);
        DataDirectoryEntry root = mock(DataDirectoryEntry.class);
        DataDirectoryEntry consume = mock(DataDirectoryEntry.class);
        DataFileEntry file = mock(DataFileEntry.class);
        when(root.getSubdirectories()).thenReturn(List.of(consume));
        when(consume.getName()).thenReturn("Consume");
        when(consume.getFiles()).thenReturn(List.of(file));
        when(file.getName()).thenReturn("0200.img");
        when(provider.getRoot()).thenReturn(root);
        Data empty = mock(Data.class);
        when(provider.getData(anyString())).thenReturn(empty);
        Data container = mock(Data.class);
        when(provider.getData("Consume/0200.img")).thenReturn(container);
        Data blocked = itemInfo(true);
        Data sale = itemInfo(false);
        Data other = itemInfo(false);
        when(container.getChildByPath("0" + UNTRADEABLE)).thenReturn(blocked);
        when(container.getChildByPath("0" + SALE_ITEM)).thenReturn(sale);
        when(container.getChildByPath("0" + OTHER_ITEM)).thenReturn(other);
        return provider;
    }

    private static Data itemInfo(boolean blocked) {
        Data item = mock(Data.class);
        Data tradeBlock = integer(blocked ? 1 : 0);
        Data slotMax = integer(100);
        when(item.getChildByPath("info/tradeBlock")).thenReturn(tradeBlock);
        when(item.getChildByPath("info/slotMax")).thenReturn(slotMax);
        return item;
    }

    private static Data integer(int value) {
        Data data = mock(Data.class);
        when(data.getType()).thenReturn(DataType.INT);
        when(data.getData()).thenReturn(value);
        return data;
    }

    private record Participant(Character character, Trade trade, AtomicInteger wallet, Inventory inventory) { }
    private record Sale(Participant seller, Participant buyer, BotSM bot, BotTradeSM fsm) { }
}
