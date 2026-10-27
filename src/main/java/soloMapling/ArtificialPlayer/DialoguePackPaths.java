package soloMapling.ArtificialPlayer;

import soloMapling.Environment.LocalizedResources;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.Environment.SoloResources;

import java.io.IOException;
import java.io.Reader;

/** Localized dialogue lookup adapted from zmzeng/solomapling-plugin. */
public final class DialoguePackPaths {

    public static final String PACK_ROOT = "ArtificialPlayer/";
    public static final String DEFAULT_PACK = "BotDialoguePack";

    private DialoguePackPaths() {
    }

    public static String resolveRelative(String fileName) {
        return LocalizedResources.resolve(PACK_ROOT, DEFAULT_PACK, fileName);
    }

    public static Reader openDialogueReader(String fileName) throws IOException {
        String path = resolveRelative(fileName);
        if (path == null) {
            throw new IOException("Dialogue file not found: " + fileName
                    + " (language=" + SoloMaplingLanguageConfig.languageTag() + ")");
        }
        return SoloResources.openReader(path);
    }
}
