package soloMapling.beidou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotChatterSystem.TownChatterLines;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.ArtificialPlayer.ConversationScript;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class AmbientDialogueLanguageTest {

    @AfterEach
    void resetLanguage() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
        TownChatterLines.reload();
    }

    @Test
    void townExchangeCacheTracksLanguageChanges() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        List<List<String>> english = TownChatterLines.exchanges();
        assertFalse(english.isEmpty());
        assertEquals("you been to orbis yet?", english.getFirst().getFirst());

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        List<List<String>> chinese = TownChatterLines.exchanges();
        assertNotSame(english, chinese);
        assertEquals("你去过天空之城了吗？", chinese.getFirst().getFirst());
        assertEquals(english.size(), chinese.size());

        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals(english, TownChatterLines.exchanges());
    }

    @Test
    void conversationSelectionReloadsItsScriptPoolForTheCurrentLanguage() throws Exception {
        Constructor<ConversationManager> constructor = ConversationManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ConversationManager manager = constructor.newInstance();
        Method pick = ConversationManager.class.getDeclaredMethod("pickScript", int.class);
        pick.setAccessible(true);

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertNotNull(pick.invoke(manager, 2));
        assertEquals("你在哪练级？", debateFirstLine(manager));

        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertNotNull(pick.invoke(manager, 2));
        assertEquals("where do u train?", debateFirstLine(manager));

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertNotNull(pick.invoke(manager, 2));
        assertEquals("你在哪练级？", debateFirstLine(manager));
    }

    @SuppressWarnings("unchecked")
    private static String debateFirstLine(ConversationManager manager) throws Exception {
        Field scripts = ConversationManager.class.getDeclaredField("allScripts");
        scripts.setAccessible(true);
        return ((List<ConversationScript>) scripts.get(manager)).stream()
                .filter(script -> script.getId().equals("training_spot_debate"))
                .findFirst().orElseThrow().getLines().getFirst().getText();
    }
}
