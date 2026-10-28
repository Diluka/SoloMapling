package soloMapling.game.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded immutable native facts; these fields are evidence, never another character model. */
public record NativeBasicResult(String action, NativeActionResult.Outcome outcome, NativeActionResult.Dispatch dispatch,
                                int senderCharacterId, String epoch, boolean cancelRequested, boolean handlerError,
                                String fixedReason, Map<String, Object> facts) implements NativeActionFact {
    public NativeBasicResult {
        if (action == null || !List.of("move", "stop", "attack", "pickup", "drop", "use_item", "equip", "unequip").contains(action)) {
            throw new IllegalArgumentException("invalid_basic_action");
        }
        Objects.requireNonNull(outcome); Objects.requireNonNull(dispatch); Objects.requireNonNull(epoch);
        if (fixedReason != null && !fixedReason.matches("[a-z_]{1,96}")) throw new IllegalArgumentException("invalid_action_reason");
        facts = freezeMap(Objects.requireNonNull(facts), 0, new int[]{8192});
        if (outcome == NativeActionResult.Outcome.SUCCEEDED && (dispatch != NativeActionResult.Dispatch.STARTED
                || !Boolean.TRUE.equals(facts.get("effect_observed"))
                || !(facts.get("evidence_source") instanceof String source) || !source.startsWith("NATIVE_"))) {
            throw new IllegalArgumentException("action_success_without_evidence");
        }
        if (outcome == NativeActionResult.Outcome.CANCELLED && dispatch != NativeActionResult.Dispatch.NOT_STARTED
                && !("move".equals(action) && Boolean.TRUE.equals(facts.get("movement_stopped"))
                && Boolean.FALSE.equals(facts.get("completed_duration"))
                && facts.get("evidence_source") instanceof String source && source.startsWith("NATIVE_"))) {
            throw new IllegalArgumentException("action_result_contradicts_native_fact");
        }
    }

    private static Map<String, Object> freezeMap(Map<?, ?> input, int depth, int[] remaining) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("[A-Za-z0-9_.-]{1,96}")) {
                throw new IllegalArgumentException("invalid_native_fact_key");
            }
            output.put(key, freeze(entry.getValue(), depth + 1, remaining));
        }
        return Collections.unmodifiableMap(output);
    }

    private static Object freeze(Object value, int depth, int[] remaining) {
        if (depth > 10 || --remaining[0] < 0) throw new IllegalArgumentException("native_facts_capacity_exceeded");
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) return value;
        if (value instanceof Float single && Float.isFinite(single) || value instanceof Double decimal && Double.isFinite(decimal)) return value;
        if (value instanceof String text && text.length() <= 1024) return text;
        if (value instanceof Map<?, ?> map) return freezeMap(map, depth, remaining);
        if (value instanceof List<?> list && list.size() <= 512) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(freeze(item, depth + 1, remaining));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("non_scalar_native_fact");
    }
}
