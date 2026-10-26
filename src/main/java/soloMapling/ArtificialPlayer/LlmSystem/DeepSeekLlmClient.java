package soloMapling.ArtificialPlayer.LlmSystem;

import io.github.sashirestela.openai.SimpleOpenAIDeepseek;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek adapter backed by simple-openai.
 */
public final class DeepSeekLlmClient implements LlmClient {

    private final SimpleOpenAIDeepseek client;

    private DeepSeekLlmClient(SimpleOpenAIDeepseek client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static DeepSeekLlmClient create(String apiKey) {
        return new DeepSeekLlmClient(SimpleOpenAIDeepseek.builder()
                .apiKey(apiKey)
                .build());
    }

    @Override
    public CompletableFuture<String> complete(LlmRequest request) {
        List<ChatMessage> messages = request.messages().stream()
                .map(DeepSeekLlmClient::toDeepSeekMessage)
                .toList();
        ChatRequest chatRequest = ChatRequest.builder()
                .model(request.model())
                .messages(messages)
                .maxCompletionTokens(request.maxTokens())
                .temperature(request.temperature())
                .build();

        return client.chatCompletions()
                .create(chatRequest)
                .thenApply(chat -> chat == null ? null : chat.firstContent());
    }

    private static ChatMessage toDeepSeekMessage(LlmMessage message) {
        return switch (message.role()) {
            case SYSTEM -> ChatMessage.SystemMessage.of(message.content());
            case USER -> ChatMessage.UserMessage.of(message.content());
            case ASSISTANT -> ChatMessage.AssistantMessage.of(message.content());
        };
    }
}
