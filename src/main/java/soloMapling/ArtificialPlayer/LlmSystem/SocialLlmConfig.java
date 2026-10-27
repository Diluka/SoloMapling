package soloMapling.ArtificialPlayer.LlmSystem;

import org.springframework.core.env.Environment;
import soloMapling.Environment.SoloMaplingLanguageConfig;

/** Reads SocialBot LLM settings from the host's existing application configuration. */
public final class SocialLlmConfig {

    private SocialLlmConfig() {
    }

    public static final String PREFIX = "solomapling.llm.";
    public static final String KEY_ENABLED = PREFIX + "enabled";
    public static final String KEY_API_KEY = PREFIX + "api-key";
    public static final String KEY_MODEL = PREFIX + "model";
    public static final String KEY_MAX_TOKENS = PREFIX + "max-tokens";
    public static final String KEY_TIMEOUT_MS = PREFIX + "timeout-ms";
    public static final String KEY_HISTORY_TURNS = PREFIX + "history-turns";
    public static final String KEY_FALLBACK_YAML = PREFIX + "fallback-to-yaml";

    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    public static final int DEFAULT_MAX_TOKENS = 80;
    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_HISTORY_TURNS = 8;
    public static final int MAX_REPLY_CHARS = 120;

    private static volatile boolean enabled;
    private static volatile String apiKey = "";
    private static volatile String model = DEFAULT_MODEL;
    private static volatile int maxTokens = DEFAULT_MAX_TOKENS;
    private static volatile int timeoutMs = DEFAULT_TIMEOUT_MS;
    private static volatile int historyTurns = DEFAULT_HISTORY_TURNS;
    private static volatile boolean fallbackToYaml = true;

    public static void configure(Environment environment) {
        enabled = bool(environment, KEY_ENABLED, false);
        apiKey = string(environment, KEY_API_KEY, "").trim();
        model = string(environment, KEY_MODEL, DEFAULT_MODEL).trim();
        if (model.isEmpty()) {
            model = DEFAULT_MODEL;
        }
        maxTokens = clamp(integer(environment, KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS), 16, 512);
        timeoutMs = clamp(integer(environment, KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS), 2_000, 60_000);
        historyTurns = clamp(integer(environment, KEY_HISTORY_TURNS, DEFAULT_HISTORY_TURNS), 0, 20);
        fallbackToYaml = bool(environment, KEY_FALLBACK_YAML, true);
        SoloMaplingLanguageConfig.configure(environment);
    }

    public static boolean enabled() {
        return enabled && !apiKey.isBlank();
    }

    public static String apiKey() {
        return apiKey;
    }

    public static String model() {
        return model;
    }

    public static int maxTokens() {
        return maxTokens;
    }

    public static int timeoutMs() {
        return timeoutMs;
    }

    public static int historyTurns() {
        return historyTurns;
    }

    public static boolean fallbackToYaml() {
        return fallbackToYaml;
    }

    public static String languageTag() {
        return SoloMaplingLanguageConfig.languageTag();
    }

    private static String string(Environment environment, String key, String fallback) {
        return environment == null ? fallback : environment.getProperty(key, fallback);
    }

    private static boolean bool(Environment environment, String key, boolean fallback) {
        return environment == null ? fallback : environment.getProperty(key, Boolean.class, fallback);
    }

    private static int integer(Environment environment, String key, int fallback) {
        return environment == null ? fallback : environment.getProperty(key, Integer.class, fallback);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
