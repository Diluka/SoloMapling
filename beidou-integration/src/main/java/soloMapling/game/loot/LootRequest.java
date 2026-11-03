package soloMapling.game.loot;

import java.util.LinkedHashSet;
import java.util.List;

/** One bounded collection pass over explicit objects in the caller's current scene. */
public record LootRequest(List<Integer> objectIds, int timeoutMs) {
    public static final int MAX_OBJECTS = 32;
    public static final int MAX_TIMEOUT_MS = 120_000;

    public LootRequest {
        objectIds = List.copyOf(new LinkedHashSet<>(objectIds));
        if (objectIds.isEmpty() || objectIds.size() > MAX_OBJECTS || objectIds.stream().anyMatch(id -> id < 1)
                || timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("invalid_loot_request");
        }
    }
}
