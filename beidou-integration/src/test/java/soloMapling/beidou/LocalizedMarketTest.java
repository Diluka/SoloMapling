package soloMapling.beidou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.FreeMarket.FMShopDescriptionManager;
import soloMapling.FreeMarket.HiredMerchantArtificial;
import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.maps.PlayerShopItem;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises shop descriptions and merchant dialogue through their production consumers. */
class LocalizedMarketTest {
    @AfterEach
    void resetLanguage() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void newlyCreatedShopDescriptionsUseTheStartupLanguageOrEnglishFallback() {
        CharacterTestContext.install();
        for (String language : List.of("en-US", "zh-CN", "fr-FR")) {
            org.springframework.core.env.Environment environment = mock(org.springframework.core.env.Environment.class);
            when(environment.getProperty(SoloMaplingLanguageConfig.KEY, "")).thenReturn(language);
            SoloMaplingLanguageConfig.configure(environment);
            Character owner = mock(Character.class);
            when(owner.getClient()).thenReturn(new BotClient(0, 1));
            when(owner.getPosition()).thenReturn(new java.awt.Point(0, 0));
            when(owner.getName()).thenReturn("ProbeOwner");
            HiredMerchantArtificial merchant = new HiredMerchantArtificial(owner, "", 5030000, 1700020010, "ProbeOwner");
            merchant.setTier("B");
            merchant.setMapId(910000022);
            merchant.setPrimary(HiredMerchantArtificial.shopTypes.Common);
            merchant.addItem(new PlayerShopItem(new Item(2000000, (short) 1, (short) 1), (short) 1, 50));

            new FMShopDescriptionManager().generateDescription(merchant);

            assertFalse(merchant.getDescription().isBlank());
            assertFalse(merchant.getDescription().contains("%OWNER%"));
            assertEquals("zh-CN".equals(language), hasChinese(merchant.getDescription()));
        }
    }

    @Test
    void merchantShoutsUseTheSelectedLanguageAndReplaceItemAndPrice() throws Exception {
        BotSM bot = merchantDialogueBot();
        Method selling = SellingMerchantBot.class.getDeclaredMethod("buildSellingMessage", BotSM.class, String.class);
        Method buying = BuyingMerchantBot.class.getDeclaredMethod("buildBuyingMessage", BotSM.class, String.class, int.class);
        selling.setAccessible(true);
        buying.setAccessible(true);

        for (String language : List.of("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(language);
            String sell = (String) selling.invoke(null, bot, "ProbeOre");
            String buy = (String) buying.invoke(null, bot, "ProbeOre", 50_000_000);

            assertNotNull(sell, language + " selling bot is silent");
            assertNotNull(buy, language + " buying bot is silent");
            assertTrue(sell.toLowerCase(Locale.ROOT).contains("probeore"));
            assertTrue(buy.toLowerCase(Locale.ROOT).contains("probeore"));
            assertTrue(buy.toLowerCase(Locale.ROOT).contains("50m"));
            assertFalse(sell.contains("%ITEM%"));
            assertFalse(buy.contains("%ITEM%") || buy.contains("%PRICE%"));
            assertEquals("zh-CN".equals(language), hasChinese(sell));
            assertEquals("zh-CN".equals(language), hasChinese(buy));
        }
    }

    private static BotSM merchantDialogueBot() throws Exception {
        BotSM bot = mock(BotSM.class);
        for (String name : List.of("dialoguePath", "botType")) {
            Field field = BotSM.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bot, name.equals("dialoguePath") ? "MerchantBotDialogue.yaml" : "MerchantBot");
        }
        return bot;
    }

    private static boolean hasChinese(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= 0x4e00 && codePoint <= 0x9fff);
    }

}
