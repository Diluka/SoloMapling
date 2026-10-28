package org.gms.soloacceptance.command;

import org.gms.soloacceptance.ObservationException;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Independent administrator calls: native services own game data, not CharacterRuntime. */
@Service
@ConditionalOnProperty(prefix = "solomapling.acceptance",
        name = {"enabled", "actors-enabled", "debug-commands-enabled"}, havingValue = "true", matchIfMissing = false)
public final class DebugCommandService {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final int HISTORY_LIMIT = 256;
    private final ActorProfiles profiles;
    private final Backend backend;
    private final String generation;
    private final int historyLimit;
    private final Map<Key, Entry> history = new LinkedHashMap<>();

    public interface Backend {
        Outcome give(ActorProfiles.Resolved profile, int itemId, int quantity);
    }

    /** Only fixed summaries cross this boundary; history never retains native role objects. */
    public record Outcome(String status, Boolean nativeDispatched, String reason,
                          Long quantityBefore, Long quantityAfter, String inventoryType,
                          Integer characterId, Integer world) { }

    private record Key(String owner, String requestId) { }

    private static final class Entry {
        final String commandId = "command-" + UUID.randomUUID();
        final String requestId;
        final DebugCommandRequest request;
        volatile Outcome outcome;

        Entry(String requestId, DebugCommandRequest request) {
            this.requestId = requestId;
            this.request = request;
        }
    }

    @Autowired
    public DebugCommandService(ActorProfiles profiles, NativeDebugCommandBackend backend) {
        this(profiles, backend, "command-generation-" + UUID.randomUUID(), HISTORY_LIMIT);
    }

    DebugCommandService(ActorProfiles profiles, Backend backend, String generation, int historyLimit) {
        this.profiles = Objects.requireNonNull(profiles);
        this.backend = Objects.requireNonNull(backend);
        this.generation = Objects.requireNonNull(generation);
        if (historyLimit < 1) throw new IllegalArgumentException("history limit must be positive");
        this.historyLimit = historyLimit;
    }

    public Map<String, Object> publicCapabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("enabled_tools", List.of("game_command_help", "game_command"));
        result.put("commands", List.of("give"));
        result.put("command_generation", generation);
        result.put("administrator_required", true);
        result.put("runtime_required", false);
        result.put("target", "configured_online_ordinary_profile");
        result.put("history_limit", historyLimit);
        result.put("request_history", "owner_scoped_process_lifetime_no_eviction");
        result.put("recovery", "repeat_same_request_id_and_arguments_with_original_command_generation");
        result.put("cancellation_supported", false);
        result.put("give_inventory_types", List.of("USE", "ETC"));
        result.put("give_maximum_quantity", (int) Short.MAX_VALUE);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> help(String command) {
        if (command != null && !"give".equals(command)) throw invalid("unsupported_command");
        Map<String, Object> result = new LinkedHashMap<>(publicCapabilities());
        result.put("functions", List.of(Map.of(
                "command", "give",
                "description", "通过北斗原生管理服务给已登记在线调试角色发放普通可堆叠物品。",
                "parameters", Map.of("item_id", "positive integer; ordinary noncash USE/ETC stackable item",
                        "quantity", "integer in 1..32767"),
                "examples", List.of(Map.of("item_id", 2000000, "quantity", 1),
                        Map.of("item_id", 4000000, "quantity", 1)),
                "unsupported", List.of("equipment", "pet", "cash", "quest", "rechargeable", "all_players", "gm_promotion"),
                "completion", "SUCCEEDED requires exact observed native inventory increase; partial or unknown effects are not replayed")));
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> execute(String owner, String requestId, DebugCommandRequest request) {
        if (owner == null || owner.isBlank()) throw invalid("invalid_command_owner");
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw invalid("invalid_request_id");
        if (request == null || request.parameters() == null) throw invalid("malformed_command");
        if (!generation.equals(request.expectedCommandGeneration())) {
            throw new ObservationException(HttpStatus.CONFLICT, "command_generation_changed");
        }
        if (!"give".equals(request.command())) throw invalid("unsupported_command");
        Integer itemId = request.parameters().itemId();
        Integer quantity = request.parameters().quantity();
        if (itemId == null || itemId <= 0) throw invalid("invalid_item_id");
        if (quantity == null || quantity < 1 || quantity > Short.MAX_VALUE) throw invalid("invalid_quantity");

        Key key = new Key(owner, requestId);
        Entry entry;
        synchronized (history) {
            Entry previous = history.get(key);
            if (previous != null) {
                if (!previous.request.equals(request)) {
                    throw new ObservationException(HttpStatus.CONFLICT, "request_id_conflict");
                }
                return snapshot(previous);
            }
        }
        // Only configuration is resolved here: restoring an existing result never depends
        // on the profile still being online, present, or otherwise executable now.
        ActorProfiles.Resolved profile = profiles.resolve(request.profileId(), null, null);
        synchronized (history) {
            Entry previous = history.get(key);
            if (previous != null) {
                if (!previous.request.equals(request)) {
                    throw new ObservationException(HttpStatus.CONFLICT, "request_id_conflict");
                }
                return snapshot(previous);
            }
            if (history.size() >= historyLimit) {
                throw new ObservationException(HttpStatus.TOO_MANY_REQUESTS, "command_history_full");
            }
            entry = new Entry(requestId, request);
            history.put(key, entry);
        }
        try {
            Outcome outcome = backend.give(profile, itemId, quantity);
            entry.outcome = validOutcome(outcome) ? outcome : unknown();
        } catch (RuntimeException ignored) {
            // The backend normally retains its exact dispatch facts. If it fails outside
            // that contract, keep the request consumed rather than risk a second give.
            entry.outcome = unknown();
        }
        return snapshot(entry);
    }

    private static boolean validOutcome(Outcome outcome) {
        return outcome != null && List.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(outcome.status());
    }

    private static Outcome unknown() {
        return new Outcome("UNKNOWN", null, "command_execution_unknown", null, null, null, null, null);
    }

    private Map<String, Object> snapshot(Entry entry) {
        Outcome outcome = entry.outcome;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command_generation", generation);
        result.put("command_id", entry.commandId);
        result.put("request_id", entry.requestId);
        result.put("profile_id", entry.request.profileId());
        result.put("command", entry.request.command());
        result.put("status", outcome == null ? "RUNNING" : outcome.status());
        result.put("terminal", outcome != null);
        result.put("native_dispatched", outcome == null ? null : outcome.nativeDispatched());
        result.put("reason", outcome == null ? "command_running" : outcome.reason());
        result.put("item_id", entry.request.parameters().itemId());
        result.put("quantity", entry.request.parameters().quantity());
        result.put("quantity_before", outcome == null ? null : outcome.quantityBefore());
        result.put("quantity_after", outcome == null ? null : outcome.quantityAfter());
        result.put("inventory_type", outcome == null ? null : outcome.inventoryType());
        result.put("character_id", outcome == null ? null : outcome.characterId());
        result.put("world", outcome == null ? null : outcome.world());
        result.put("persisted_state_verified", false);
        return Collections.unmodifiableMap(result);
    }

    private static ObservationException invalid(String reason) {
        return new ObservationException(HttpStatus.BAD_REQUEST, reason);
    }
}
