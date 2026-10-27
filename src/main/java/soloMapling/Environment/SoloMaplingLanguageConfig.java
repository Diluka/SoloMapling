package soloMapling.Environment;

import org.springframework.core.env.Environment;

/**
 * Dialogue and resource language, adapted from zmzeng/solomapling-plugin.
 * Explicit Solo configuration takes precedence over the host language and English.
 */
public final class SoloMaplingLanguageConfig {

    public static final String KEY = "solomapling.language";
    public static final String HOST_KEY = "gms.service.language";
    public static final String DEFAULT = "en-US";

    private static volatile String languageTag = DEFAULT;

    private SoloMaplingLanguageConfig() {
    }

    public static void configure(Environment environment) {
        String language = environment == null ? DEFAULT : environment.getProperty(KEY, "");
        if (language == null || language.isBlank()) {
            language = environment == null ? DEFAULT : environment.getProperty(HOST_KEY, DEFAULT);
        }
        setLanguageTag(language);
    }

    public static void setLanguageTag(String tag) {
        languageTag = tag == null || tag.isBlank() ? DEFAULT : tag.trim();
    }

    public static String languageTag() {
        return languageTag;
    }

    public static String dialoguePackDirectoryName() {
        return LocalizedResources.directoryName("BotDialoguePack");
    }

    public static boolean isDefaultEnglish() {
        return "en".equalsIgnoreCase(languageTag) || "en-US".equalsIgnoreCase(languageTag);
    }
}
