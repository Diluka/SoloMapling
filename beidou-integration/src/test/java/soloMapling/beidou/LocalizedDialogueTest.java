package soloMapling.beidou;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.DialoguePackPaths;
import soloMapling.Environment.LocalizedResources;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.Environment.SoloResources;

import java.io.Reader;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LocalizedDialogueTest {
    private static final String PACK_ROOT = "ArtificialPlayer/";
    private static final Pattern TOKENS = Pattern.compile("\\{[A-Za-z_]+\\}|%[A-Z_]+%");

    @TempDir Path directory;

    @AfterEach
    void restoreLanguage() {
        SoloMaplingLanguageConfig.configure(null);
    }

    @Test
    void explicitSoloLanguageTakesPriorityOverTheHostLanguage() {
        SoloMaplingLanguageConfig.configure(environment(Map.of(
                "solomapling.language", " en-US ", "gms.service.language", "zh-CN")));

        assertEquals("en-US", SoloMaplingLanguageConfig.languageTag());
        assertEquals("BotDialoguePack", SoloMaplingLanguageConfig.dialoguePackDirectoryName());
    }

    @Test
    void blankSoloLanguageUsesTheHostLanguage() {
        SoloMaplingLanguageConfig.configure(environment(Map.of(
                "solomapling.language", " ", "gms.service.language", "zh-CN")));

        assertEquals("zh-CN", SoloMaplingLanguageConfig.languageTag());
        assertEquals("BotDialoguePack-zh-CN", SoloMaplingLanguageConfig.dialoguePackDirectoryName());
    }

    @Test
    void absentOrBlankConfigurationDefaultsToEnglish() {
        SoloMaplingLanguageConfig.configure(environment(Map.of(
                "solomapling.language", "", "gms.service.language", " ")));
        assertEquals("en-US", SoloMaplingLanguageConfig.languageTag());

        SoloMaplingLanguageConfig.configure(null);
        assertEquals("en-US", SoloMaplingLanguageConfig.languageTag());
    }

    @Test
    void chineseDialogueIsLoadedByThePublicHandlerWithContextTokensIntact() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");

        var dialogue = BotDialogueHandler.getDialogueCon(
                "SocialBotDialogue.yaml", "SocialBot", "CongratsLevelUp");

        assertNotNull(dialogue);
        assertTrue(dialogue.getDialogue().contains("恭喜升到 {PLAYER_LEVEL} {PLAYER_NAME}!"));
        assertTrue(dialogue.getDialogue().stream().anyMatch(line -> line.contains("{PLAYER_NAME}")));
    }

    @Test
    void englishAliasesKeepTheOriginalDialogue() {
        for (String language : List.of("en-US", "en", "EN-us")) {
            SoloMaplingLanguageConfig.setLanguageTag(language);
            var dialogue = BotDialogueHandler.getDialogueCon(
                    "SocialBotDialogue.yaml", "SocialBot", "SingleResponse");

            assertTrue(dialogue.getDialogue().contains("Been training slimes all morning..."));
            assertEquals("ArtificialPlayer/BotDialoguePack/SocialBotDialogue.yaml",
                    DialoguePackPaths.resolveRelative("SocialBotDialogue.yaml"));
        }
    }

    @Test
    void unsupportedLanguageAndUntranslatedFilesFallBackToEnglish() {
        SoloMaplingLanguageConfig.setLanguageTag("ja-JP");
        var dialogue = BotDialogueHandler.getDialogueCon(
                "SocialBotDialogue.yaml", "SocialBot", "SingleResponse");
        assertTrue(dialogue.getDialogue().contains("Been training slimes all morning..."));

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals("ArtificialPlayer/BotDialoguePack/DropGameLootPool.yaml",
                DialoguePackPaths.resolveRelative("DropGameLootPool.yaml"));
        assertNull(LocalizedResources.resolve(PACK_ROOT, "BotDialoguePack", "missing-dialogue.yaml"));
        assertNull(BotDialogueHandler.readDialogueYaml("missing-dialogue.yaml", "Missing", "Missing"));
    }

    @Test
    void missingLocalizedNodeUsesTheEnglishNodeWithoutReplacingOtherChineseNodes() throws Exception {
        String file = "LocalizedDialogueFallbackFixture.yaml";
        writeResource("BotDialoguePack", file, """
                Fixture:
                  NewUpstreamNode:
                    text: ["Hello {PLAYER_NAME}"]
                    emote: [2]
                    wait: 3
                EnglishOnlyType:
                  Greeting:
                    text: ["An English fallback"]
                    wait: 1
                """);
        writeResource("BotDialoguePack-zh-CN", file, """
                Fixture:
                  ExistingNode:
                    text: ["你好 {PLAYER_NAME}"]
                    emote: [3]
                    wait: 2
                """);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader resources = new URLClassLoader(
                new java.net.URL[]{directory.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(resources);
            SoloMaplingLanguageConfig.setLanguageTag("zh-CN");

            assertEquals(List.of("你好 {PLAYER_NAME}"), BotDialogueHandler.readDialogueYaml(
                    file, "Fixture", "ExistingNode").get("text"));
            var fallback = BotDialogueHandler.readDialogueYaml(file, "Fixture", "NewUpstreamNode");
            assertEquals(List.of("Hello {PLAYER_NAME}"), fallback.get("text"));
            assertEquals("3", String.valueOf(fallback.get("wait")));
            assertEquals(List.of("An English fallback"), BotDialogueHandler.readDialogueYaml(
                    file, "EnglishOnlyType", "Greeting").get("text"));
            assertNull(BotDialogueHandler.readDialogueYaml(file, "Fixture", "MissingEverywhere"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void everyChinesePackParsesAndPreservesControlFieldsAndContextTokens() throws Exception {
        List<String> names = SoloResources.listFileNames(PACK_ROOT + "BotDialoguePack-zh-CN", ".yaml");
        assertEquals(19, names.size());

        for (String name : names) {
            Map<?, ?> chinese = readPack("BotDialoguePack-zh-CN", name);
            Map<?, ?> english = readPack("BotDialoguePack", name);
            assertFalse(chinese.isEmpty(), name);
            for (var type : english.entrySet()) {
                if (!(type.getValue() instanceof Map<?, ?> englishNodes)
                        || !(chinese.get(type.getKey()) instanceof Map<?, ?> chineseNodes)) {
                    continue;
                }
                for (var node : englishNodes.entrySet()) {
                    if (!chineseNodes.containsKey(node.getKey())) {
                        continue; // Untranslated nodes follow the handler's English fallback.
                    }
                    String label = name + "/" + type.getKey() + "/" + node.getKey();
                    Object translation = chineseNodes.get(node.getKey());
                    assertEquals(controlStructure(node.getValue(), false),
                            controlStructure(translation, false), label);
                    assertEquals(tokens(node.getValue()), tokens(translation), label);
                }
            }
        }
    }

    @Test
    void trainingDialogueRetainsPerLineEmotesAfterTheSourceMappingRepair() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        var english = BotDialogueHandler.getDialogueCon("TrainingBotDialogue.yaml", "TrainingBot", "NearDeath");
        int line = english.getDialogue().indexOf("potting potting POTTING");
        assertTrue(line >= 0);
        assertEquals(6, english.getEmoteForIndex(line));

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        var chinese = BotDialogueHandler.getDialogueCon("TrainingBotDialogue.yaml", "TrainingBot", "NearDeath");
        assertFalse(chinese.getDialogue().get(line).isBlank());
        assertEquals(6, chinese.getEmoteForIndex(line));
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("dialogue-test", properties));
        return environment;
    }

    private void writeResource(String pack, String name, String content) throws Exception {
        Path file = directory.resolve("soloMapling/" + PACK_ROOT + pack + "/" + name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Map<?, ?> readPack(String pack, String name) throws Exception {
        try (Reader reader = SoloResources.openReader(PACK_ROOT + pack + "/" + name)) {
            return assertInstanceOf(Map.class, new YamlReader(reader).read());
        }
    }

    private static Object controlStructure(Object value, boolean text) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> fields = new LinkedHashMap<>();
            map.forEach((key, entry) -> fields.put(key,
                    controlStructure(entry, "text".equals(key) || "line".equals(key))));
            return fields;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(entry -> controlStructure(entry, text)).toList();
        }
        return text && value instanceof String ? "<translated text>" : value;
    }

    private static Set<String> tokens(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof String text) {
            TOKENS.matcher(text).results().forEach(match -> result.add(match.group()));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(entry -> result.addAll(tokens(entry)));
        } else if (value instanceof List<?> list) {
            list.forEach(entry -> result.addAll(tokens(entry)));
        }
        return result;
    }
}
