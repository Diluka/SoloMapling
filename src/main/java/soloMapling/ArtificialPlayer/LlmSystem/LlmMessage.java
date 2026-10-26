package soloMapling.ArtificialPlayer.LlmSystem;

import java.util.Objects;

/**
 * Provider-neutral message passed to an {@link LlmClient}.
 */
public record LlmMessage(Role role, String content) {

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
