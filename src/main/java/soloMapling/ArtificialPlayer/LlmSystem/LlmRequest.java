package soloMapling.ArtificialPlayer.LlmSystem;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral text completion request.
 */
public record LlmRequest(List<LlmMessage> messages,
                         String model,
                         int maxTokens,
                         double temperature) {

    public LlmRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }
}
