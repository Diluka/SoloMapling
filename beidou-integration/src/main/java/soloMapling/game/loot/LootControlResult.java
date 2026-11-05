package soloMapling.game.loot;

import java.util.Map;

public record LootControlResult(Outcome outcome, LootStatus status) {
    public enum Outcome { STARTED, ALREADY_RUNNING, BUSY, UNAVAILABLE, STOPPED, ALREADY_IDLE }

    public Map<String, Object> toMap() { return Map.of("outcome", outcome.name(), "status", status.toMap()); }
}
