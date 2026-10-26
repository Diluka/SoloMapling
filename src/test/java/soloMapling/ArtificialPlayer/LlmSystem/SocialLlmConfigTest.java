package soloMapling.ArtificialPlayer.LlmSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialLlmConfigTest {

    @AfterEach
    void resetConfig() {
        SocialLlmConfig.configure(null);
    }

    @Test
    void readsExistingApplicationKeysAndLanguageFallback() {
        StandardEnvironment environment = environment(Map.of(
                "solomapling.llm.enabled", "true",
                "solomapling.llm.api-key", "test-token",
                "solomapling.llm.model", "test-model",
                "solomapling.llm.max-tokens", "120",
                "solomapling.llm.timeout-ms", "5000",
                "solomapling.llm.history-turns", "4",
                "solomapling.llm.fallback-to-yaml", "false",
                "gms.service.language", "zh-CN"));

        SocialLlmConfig.configure(environment);

        assertTrue(SocialLlmConfig.enabled());
        assertEquals("test-token", SocialLlmConfig.apiKey());
        assertEquals("test-model", SocialLlmConfig.model());
        assertEquals(120, SocialLlmConfig.maxTokens());
        assertEquals(5000, SocialLlmConfig.timeoutMs());
        assertEquals(4, SocialLlmConfig.historyTurns());
        assertFalse(SocialLlmConfig.fallbackToYaml());
        assertEquals("zh-CN", SocialLlmConfig.languageTag());
    }

    @Test
    void requiresExplicitEnableAndNonBlankCredential() {
        SocialLlmConfig.configure(environment(Map.of("solomapling.llm.enabled", "true")));
        assertFalse(SocialLlmConfig.enabled());

        SocialLlmConfig.configure(environment(Map.of("solomapling.llm.api-key", "test-token")));
        assertFalse(SocialLlmConfig.enabled());
    }

    @Test
    void boundsResourceSettingsAndResetsDefaults() {
        SocialLlmConfig.configure(environment(Map.of(
                "solomapling.llm.max-tokens", "9999",
                "solomapling.llm.timeout-ms", "1",
                "solomapling.llm.history-turns", "-1")));
        assertEquals(512, SocialLlmConfig.maxTokens());
        assertEquals(2000, SocialLlmConfig.timeoutMs());
        assertEquals(0, SocialLlmConfig.historyTurns());

        SocialLlmConfig.configure(null);
        assertFalse(SocialLlmConfig.enabled());
        assertEquals(SocialLlmConfig.DEFAULT_MODEL, SocialLlmConfig.model());
        assertTrue(SocialLlmConfig.fallbackToYaml());
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
