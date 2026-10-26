package soloMapling.ArtificialPlayer.LlmSystem;

import org.gms.client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Asynchronous LLM chat with a bounded timeout and a caller-owned YAML fallback. */
public final class SocialLlmService {

    private static final Logger log = LoggerFactory.getLogger(SocialLlmService.class);
    private static volatile LlmClient client;

    private SocialLlmService() {
    }

    public static void configure(Environment environment) {
        SocialLlmConfig.configure(environment);
        client = SocialLlmConfig.enabled() ? DeepSeekLlmClient.create(SocialLlmConfig.apiKey()) : null;
        SocialChatSessionStore.clearAll();
    }

    static void configure(LlmClient injectedClient) {
        client = injectedClient;
    }

    public static void shutdown() {
        client = null;
        SocialChatSessionStore.clearAll();
    }

    public static boolean isEnabled() {
        return client != null;
    }

    public static void completeAsync(Character bot, Character player, String userMessage,
                                     Consumer<String> onSuccess, Runnable onFailure) {
        if (!isEnabled()) {
            onFailure.run();
            return;
        }
        SocialChatSessionStore.addUser(bot.getId(), player.getId(), userMessage);
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(SocialLlmPromptBuilder.systemPrompt(bot, player)));
        messages.addAll(SocialChatSessionStore.toMessages(
                bot.getId(), player.getId(), SocialLlmConfig.historyTurns()));
        // A zero-length history disables retention, not the current user's input.
        if (SocialLlmConfig.historyTurns() == 0) {
            messages.add(LlmMessage.user(userMessage));
        }
        completeAsync(new LlmRequest(messages, SocialLlmConfig.model(), SocialLlmConfig.maxTokens(), 0.85),
                SocialLlmConfig.timeoutMs(), onSuccess, onFailure);
    }

    static void completeAsync(LlmRequest request, long timeoutMs,
                              Consumer<String> onSuccess, Runnable onFailure) {
        LlmClient selectedClient = client;
        if (selectedClient == null) {
            onFailure.run();
            return;
        }
        final CompletableFuture<String> completion;
        try {
            completion = selectedClient.complete(request);
            if (completion == null) {
                onFailure.run();
                return;
            }
        } catch (RuntimeException error) {
            logFailure(error);
            onFailure.run();
            return;
        }
        completion.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((raw, error) -> {
            if (error != null) {
                logFailure(error);
                onFailure.run();
                return;
            }
            String reply = truncateForMapChat(raw);
            if (reply == null || reply.isBlank()) {
                onFailure.run();
                return;
            }
            try {
                onSuccess.accept(reply);
            } catch (RuntimeException callbackError) {
                logFailure(callbackError);
                onFailure.run();
            }
        });
    }

    static String truncateForMapChat(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= SocialLlmConfig.MAX_REPLY_CHARS) {
            return normalized;
        }
        return normalized.substring(0, SocialLlmConfig.MAX_REPLY_CHARS - 1).trim() + "…";
    }

    private static void logFailure(Throwable error) {
        // Provider exceptions may contain request details; never print keys, prompts or response bodies.
        log.warn("SocialBot LLM call failed ({})", error.getClass().getSimpleName());
    }
}
