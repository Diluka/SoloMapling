package soloMapling.ArtificialPlayer.LlmSystem;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous, provider-neutral interface for text completions.
 */
@FunctionalInterface
public interface LlmClient {

    CompletableFuture<String> complete(LlmRequest request);
}
