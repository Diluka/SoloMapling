package soloMapling.ArtificialPlayer.LlmSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.DialogueContextResolver;

/** Builds a short player-style prompt from the existing dialogue context resolvers. */
public final class SocialLlmPromptBuilder {

    private SocialLlmPromptBuilder() {
    }

    public static String systemPrompt(Character bot, Character player) {
        String language = SocialLlmConfig.languageTag();
        String languageHint = "en".equalsIgnoreCase(language) || "en-US".equalsIgnoreCase(language)
                ? "Reply in English."
                : "Prefer " + language + " when natural; mirror the player's language if obvious.";
        return """
                You are %s, a MapleStory v83 player character hanging out in town — not an NPC, not an AI assistant.
                %s
                Game context:
                %s
                Rules:
                - Reply in 1-2 short in-game chat lines (under %d characters total).
                - Stay in character as a casual Maple player; no markdown, no meta, no URLs.
                - Do not invite to party or promise buffs — the player uses menu options for that.
                - Be friendly and slightly playful; reference the game world when it fits.
                """.formatted(safeName(bot), languageHint, context(bot, player), SocialLlmConfig.MAX_REPLY_CHARS);
    }

    private static String context(Character bot, Character player) {
        if (bot == null) {
            return "(no bot context)";
        }
        StringBuilder result = new StringBuilder();
        String[] tokens = {"MAP", "REGION", "JOB", "LEVEL", "WEAPON", "MOB", "DROP",
                "PLAYER_NAME", "PLAYER_LEVEL", "PLAYER_JOB", "PLAYER_FAME", "PLAYER_WEAPON",
                "PLAYER_GEAR", "PLAYER_PET", "PLAYER_GUILD", "PLAYER_NX"};
        for (String token : tokens) {
            DialogueContextResolver.fill("{" + token + "}", bot, player)
                    .filter(value -> !value.isBlank())
                    .ifPresent(value -> result.append("- ").append(token).append(": ").append(value).append('\n'));
        }
        return result.isEmpty() ? "(minimal context)" : result.toString();
    }

    private static String safeName(Character character) {
        return character == null || character.getName() == null ? "a town bot" : character.getName();
    }
}
