package soloMapling.Environment;

/**
 * Resolves localized resource directories before their English defaults.
 * Adapted from zmzeng/solomapling-plugin, using this integration's read-only loader.
 */
public final class LocalizedResources {

    private LocalizedResources() {
    }

    public static String directoryName(String base) {
        return SoloMaplingLanguageConfig.isDefaultEnglish()
                ? base : base + "-" + SoloMaplingLanguageConfig.languageTag();
    }

    /** Returns null only when neither language contains the requested resource. */
    public static String resolve(String parent, String base, String fileName) {
        String directory = directoryName(base);
        String localized = parent + directory + "/" + fileName;
        if (SoloResources.exists(localized)) {
            return localized;
        }
        if (!base.equals(directory)) {
            String english = parent + base + "/" + fileName;
            if (SoloResources.exists(english)) {
                return english;
            }
        }
        return null;
    }
}
