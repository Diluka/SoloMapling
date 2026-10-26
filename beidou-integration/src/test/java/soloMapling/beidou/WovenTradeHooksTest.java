package soloMapling.beidou;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.config.GameConfig;
import org.gms.server.Trade;
import org.gms.server.Trade.TradeResult;
import org.gms.util.I18nUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native escrow and handshake run unchanged; only external services are stubbed. */
class WovenTradeHooksTest {
    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void successNotifiesBothBotsOnceAfterBothSettlements() {
        try (MockedStatic<GameConfig> config = configuration();
             MockedStatic<Inventory> inventory = mockStatic(Inventory.class);
             MockedStatic<I18nUtil> messages = suppressTradeLog()) {
            inventory.when(() -> Inventory.checkSpotsAndOwnership(any(), anyList())).thenReturn(true);
            Participant first = participant(701, 1000);
            Participant second = participant(702, 1000);
            pair(first, second);
            List<TradeResult> firstResults = new ArrayList<>();
            List<TradeResult> secondResults = new ArrayList<>();
            first.trade.setTradeResultCallback(firstResults::add);
            second.trade.setTradeResultCallback(secondResults::add);
            first.trade.setMesoBot(100);
            second.trade.setMesoBot(200);
            Trade.completeTrade(first.character);
            assertTrue(firstResults.isEmpty());
            assertTrue(secondResults.isEmpty());
            Trade.completeTrade(second.character);
            assertEquals(List.of(TradeResult.SUCCESSFUL), firstResults);
            assertEquals(List.of(TradeResult.SUCCESSFUL), secondResults);
            assertEquals(1100, first.wallet.get());
            assertEquals(900, second.wallet.get());
            assertNull(first.character.getTrade());
            assertNull(second.character.getTrade());
            // The normal packet handler skips completion once getTrade() is null.
            Trade.cancelTrade(first.character, TradeResult.PARTNER_CANCEL);
            assertEquals(1, firstResults.size());
            assertEquals(1, secondResults.size());
            BotRegistry.unregister(first.character);
            BotRegistry.unregister(second.character);
        }
    }

    @Test
    void failedNativeCapacityCheckRefundsEscrowAndNeverReportsSuccess() {
        try (MockedStatic<GameConfig> config = configuration();
             MockedStatic<Inventory> inventory = mockStatic(Inventory.class);
             MockedStatic<I18nUtil> messages = suppressTradeLog()) {
            inventory.when(() -> Inventory.checkSpotsAndOwnership(any(), anyList())).thenReturn(false);
            Participant first = participant(703, 1000);
            Participant second = participant(704, 1000);
            pair(first, second);
            List<TradeResult> results = new ArrayList<>();
            first.trade.setTradeResultCallback(results::add);
            first.trade.setMesoBot(300);
            Trade.completeTrade(first.character);
            Trade.completeTrade(second.character);
            assertEquals(List.of(TradeResult.UNSUCCESSFUL), results);
            assertEquals(1000, first.wallet.get());
            assertEquals(1000, second.wallet.get());
            Trade.cancelTrade(first.character, TradeResult.PARTNER_CANCEL);
            assertEquals(1, results.size());
            assertEquals(1000, first.wallet.get());
            BotRegistry.unregister(first.character);
            BotRegistry.unregister(second.character);
        }
    }

    private static MockedStatic<GameConfig> configuration() {
        MockedStatic<GameConfig> config = mockStatic(GameConfig.class);
        config.when(() -> GameConfig.getServerInt("trade_limit_meso_under_level")).thenReturn(-1);
        return config;
    }

    private static MockedStatic<I18nUtil> suppressTradeLog() {
        MockedStatic<I18nUtil> messages = mockStatic(I18nUtil.class);
        // Native logTrade catches logging failures. Stop its name formatting before
        // it touches the WZ-backed ItemInformationProvider singleton in this test.
        messages.when(() -> I18nUtil.getLogMessage(eq("Trade.info.inviteTrade.logTrade.msg1"), any(), any()))
                .thenThrow(new IllegalStateException("No WZ logging in isolated trade test"));
        messages.when(() -> I18nUtil.getMessage(anyString(), any(Object[].class))).thenReturn("test");
        return messages;
    }

    private static Participant participant(int id, int balance) {
        Character character = mock(Character.class);
        BotClient client = mock(BotClient.class);
        AtomicInteger wallet = new AtomicInteger(balance);
        AtomicReference<Trade> active = new AtomicReference<>();
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn("test-bot-" + id);
        when(character.getClient()).thenReturn(client);
        when(character.getMeso()).thenAnswer(ignored -> wallet.get());
        when(character.canHoldMeso(anyInt())).thenReturn(true);
        when(character.canHoldUniques(anyList())).thenReturn(true);
        when(character.getTrade()).thenAnswer(ignored -> active.get());
        doAnswer(call -> { active.set(call.getArgument(0)); return null; })
                .when(character).setTrade(nullable(Trade.class));
        doAnswer(call -> { wallet.addAndGet(call.getArgument(0)); return null; })
                .when(character).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        when(client.getPlayer()).thenReturn(character);
        Trade trade = new Trade((byte) 0, character);
        active.set(trade);
        BotRegistry.register(character);
        return new Participant(character, trade, wallet);
    }

    private static void pair(Participant first, Participant second) {
        first.trade.setPartner(second.trade);
        second.trade.setPartner(first.trade);
    }

    private record Participant(Character character, Trade trade, AtomicInteger wallet) { }
}
