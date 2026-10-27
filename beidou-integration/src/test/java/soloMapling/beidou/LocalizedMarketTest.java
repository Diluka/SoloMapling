package soloMapling.beidou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.FreeMarket.FMShopDescGen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercises the real resource-to-shop-text paths without creating game characters or shops. */
class LocalizedMarketTest {
    @AfterEach
    void resetLanguage() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void chineseShopWordsLoadFromTheLocalizedPack() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");

        assertTrue(ShopText.path("thief").contains("FMNameDesc-zh-CN/"));
        List<String> descriptions = ShopText.words("thief");
        assertFalse(descriptions.isEmpty());
        assertTrue(descriptions.stream().anyMatch(LocalizedMarketTest::hasChinese));
        assertTrue(hasChinese(ShopText.description("thief")));
    }

    @Test
    void untranslatedNamesAndFacesKeepTheEnglishLists() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        List<String> englishNames = ShopText.words("ign");
        List<String> englishFaces = ShopText.words("emojis");
        assertFalse(englishNames.isEmpty());
        assertFalse(englishFaces.isEmpty());

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");

        assertEquals("FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt", ShopText.path("ign"));
        assertEquals("FreeMarket/FMNameDesc/emojiFaces.txt", ShopText.path("emojis"));
        assertEquals(englishNames, ShopText.words("ign"));
        assertEquals(englishFaces, ShopText.words("emojis"));
    }

    @Test
    void missingLanguagePackFallsBackToEnglishShopWords() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        List<String> englishDescriptions = ShopText.words("thief");
        String englishWelcome = ShopText.welcome("ProbeOwner");

        SoloMaplingLanguageConfig.setLanguageTag("fr-FR");

        assertEquals("FreeMarket/FMNameDesc/thiefDesc.txt", ShopText.path("thief"));
        assertEquals(englishDescriptions, ShopText.words("thief"));
        assertEquals(englishWelcome, ShopText.welcome("ProbeOwner"));
        assertEquals("Welcome to ProbeOwner's Shop!", englishWelcome);
    }

    @Test
    void chineseGreetingOfferAndCurrencyTextReachTheShopGenerators() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");

        String greeting = ShopText.welcome("ProbeOwner");
        assertTrue(greeting.contains("ProbeOwner"));
        assertTrue(hasChinese(greeting));
        assertFalse(greeting.contains("%OWNER%"));
        assertTrue(hasChinese(ShopText.offerable()));
        String currencies = ShopText.currencies();
        assertNotNull(currencies);
        assertTrue(hasChinese(currencies));
        assertTrue(currencies.startsWith("|") && currencies.endsWith("|"));
    }

    @Test
    void chineseShopBordersRespectRenderedWidth() {
        assertEquals(7, ShopText.width("商铺ABC"));
        String bordered = ShopText.border("金币商行", 19);
        assertTrue(bordered.contains("金币商行"));
        assertTrue(ShopText.width(bordered) <= 19);
        assertThrows(IllegalArgumentException.class, () -> ShopText.border("中文店铺名称太长了", 19));
    }

    @Test
    void cachedClanNamesFollowLanguageChangesWithinTheSameProcess() {
        for (String language : List.of("zh-CN", "en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(language);
            List<String> available = ShopText.words("fmclan");
            assertFalse(available.isEmpty());

            String clan = ShopText.clan();
            assertTrue(available.contains(clan), language + " reused a clan from the previous language: " + clan);
            assertEquals("zh-CN".equals(language), hasChinese(clan));
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

    private static final class ShopText extends FMShopDescGen {
        private static String path(String type) { return resolveFilePath(type); }
        private static List<String> words(String type) { return getStoreDescriptionLines(type); }
        private static String description(String type) { return getRandomStoreDescription(type); }
        private static String welcome(String owner) { return getWelcomeDescription(owner); }
        private static String offerable() { return getOfferableDescription(); }
        private static String currencies() { return advertiseRWTCurrencies(); }
        private static String clan() { return getRandomTopFMClan(); }
        private static int width(String text) { return displayWidth(text); }
        private static String border(String text, int maximum) { return asciiBorderString(text, maximum); }
    }
}
