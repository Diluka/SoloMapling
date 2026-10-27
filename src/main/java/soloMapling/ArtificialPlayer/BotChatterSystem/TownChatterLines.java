package soloMapling.ArtificialPlayer.BotChatterSystem;

import soloMapling.ArtificialPlayer.DialoguePackPaths;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

// Loads TownChatterDialogue.yaml: ordered bot-to-bot small-talk exchanges. Unlike the single-line dialogue
// nodes served by BotDialogueHandler (one random line from a node), an exchange is an ORDERED list of
// alternating turns (A,B,A,B...), which is exactly what two-party chatter needs. YAML-first with a
// defensive fail-soft parse (bad edit -> empty list, BotChatter then falls back to its hardcoded exchange),
// same pattern as TownPresenceConfig. Our own creation (not a GreenCat extraction).
public final class TownChatterLines {

    private TownChatterLines() {
    }

    private static final String YAML_PATH = "TownChatterDialogue.yaml";

    private static volatile List<List<String>> cached;
    private static String cachedLanguage;
    private static final Random RANDOM = new Random();

    // Parsed exchanges (cached after first load). Empty list on any parse/IO failure.
    public static synchronized List<List<String>> exchanges() {
        String language = SoloMaplingLanguageConfig.languageTag();
        List<List<String>> local = cached;
        if (local == null || !language.equals(cachedLanguage)) {
            local = load();
            cached = local;
            cachedLanguage = language;
        }
        return local;
    }

    // Force a re-read from disk (used by the live-tuning !env chatter command so edits apply without a restart).
    public static synchronized List<List<String>> reload() {
        String language = SoloMaplingLanguageConfig.languageTag();
        cached = load();
        cachedLanguage = language;
        return cached;
    }

    // A random ordered exchange (>= 2 turns), or null if none are loaded.
    public static List<String> randomExchange() {
        List<List<String>> all = exchanges();
        if (all.isEmpty()) {
            return null;
        }
        return all.get(RANDOM.nextInt(all.size()));
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> load() {
        List<List<String>> out = new ArrayList<>();
        try (java.io.Reader source = DialoguePackPaths.openDialogueReader(YAML_PATH)) {
            YamlReader reader = new YamlReader(source);
            Map<String, Object> root = (Map<String, Object>) reader.read();
            if (root == null) {
                return out;
            }
            Object node = root.get("exchanges");
            if (!(node instanceof List<?> list)) {
                return out;
            }
            for (Object ex : list) {
                if (!(ex instanceof List<?> turns)) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (Object t : turns) {
                    if (t != null) {
                        String s = t.toString().trim();
                        if (!s.isEmpty()) {
                            lines.add(s);
                        }
                    }
                }
                if (lines.size() >= 2) {
                    out.add(lines);
                }
            }
        } catch (Exception e) {
            System.out.println("[TownChatterLines] failed to load " + YAML_PATH + ": " + e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }
}
