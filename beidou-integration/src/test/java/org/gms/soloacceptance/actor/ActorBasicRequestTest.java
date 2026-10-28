package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real Jackson requests retain strict action-specific input boundaries despite host coercion settings. */
class ActorBasicRequestTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void basicActionsRoundTripOnlyTheirOwnParameters() throws Exception {
        for (var example : examples().entrySet()) {
            ObjectNode input = action(example.getKey(), example.getValue());
            ActorActRequest request = mapper.treeToValue(input, ActorActRequest.class);
            ObjectNode output = (ObjectNode) mapper.readTree(mapper.writeValueAsString(request));
            assertEquals(input, output, example.getKey());
            assertEquals(request, mapper.treeToValue(output, ActorActRequest.class));
        }
    }

    @Test
    void allIntegerFieldsRejectBooleanFloatingPointStringsAndOverflow() throws Exception {
        Map<String, List<String>> numbers = Map.of(
                "move", List.of("duration_ms"), "attack", List.of("target_oid"),
                "pickup", List.of("object_id"), "drop", List.of("source_slot", "quantity"),
                "use_item", List.of("source_slot"), "equip", List.of("source_slot", "target_slot"),
                "unequip", List.of("source_slot", "target_slot"));
        for (var example : numbers.entrySet()) {
            for (String field : example.getValue()) {
                for (String invalid : List.of("true", "false", "1.0", "\"1\"", "{}", "[]", "2147483648", "-2147483649")) {
                    ObjectNode input = action(example.getKey(), examples().get(example.getKey()));
                    ((ObjectNode) input.get("parameters")).set(field, mapper.readTree(invalid));
                    assertThrows(Exception.class, () -> mapper.treeToValue(input, ActorActRequest.class),
                            example.getKey() + "." + field + "=" + invalid);
                }
            }
        }
    }

    @Test
    void stringInputsRejectCoercionButSignedSlotIntegersRemainAvailableForEquipmentValidation() throws Exception {
        for (var pair : Map.of("move", "direction", "drop", "inventory_type").entrySet()) {
            for (String invalid : List.of("1", "true", "{}", "[]")) {
                ObjectNode input = action(pair.getKey(), examples().get(pair.getKey()));
                ((ObjectNode) input.get("parameters")).set(pair.getValue(), mapper.readTree(invalid));
                assertThrows(Exception.class, () -> mapper.treeToValue(input, ActorActRequest.class));
            }
        }
        assertEquals(-11, mapper.treeToValue(action("equip", examples().get("equip")), ActorActRequest.class)
                .parameters().targetSlot());
        assertEquals(-11, mapper.treeToValue(action("unequip", examples().get("unequip")), ActorActRequest.class)
                .parameters().sourceSlot());
    }

    @Test
    void actionParametersCannotSmuggleAnotherActionOrRawNativeControlsEvenWhenNull() throws Exception {
        List<String> fields = List.of("text", "dialogue_version", "kind", "selection", "inventory_type", "source_slot",
                "target_slot", "direction", "duration_ms", "target_oid", "object_id", "quantity",
                "damage", "skill", "packet", "handler", "script", "character_id");
        for (var example : examples().entrySet()) {
            for (String field : fields) {
                if (example.getValue().containsKey(field)) continue;
                ObjectNode input = action(example.getKey(), example.getValue());
                ((ObjectNode) input.get("parameters")).putNull(field);
                assertThrows(Exception.class, () -> mapper.treeToValue(input, ActorActRequest.class),
                        example.getKey() + "." + field);
            }
        }
        for (String existing : List.of("chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move")) {
            for (String field : List.of("direction", "duration_ms", "target_oid", "object_id", "quantity")) {
                ObjectNode input = action(existing, existingParameters(existing));
                ((ObjectNode) input.get("parameters")).putNull(field);
                assertThrows(Exception.class, () -> mapper.treeToValue(input, ActorActRequest.class), existing + "." + field);
            }
        }
    }

    @Test
    void existingJavaConstructorsAndTheirSerializedWireFieldsRemainCompatible() throws Exception {
        List<ActorActRequest.Parameters> originals = List.of(new ActorActRequest.Parameters("hello"),
                new ActorActRequest.Parameters(null, 5L), new ActorActRequest.Parameters(null, 5L, "select", 7),
                new ActorActRequest.Parameters("USE", 1, 127),
                new ActorActRequest.Parameters(null, null, null, null, "ETC", 1, 2));
        for (ActorActRequest.Parameters parameters : originals) {
            assertNull(parameters.direction()); assertNull(parameters.durationMs()); assertNull(parameters.targetOid());
            assertNull(parameters.objectId()); assertNull(parameters.quantity());
            ObjectNode output = (ObjectNode) mapper.valueToTree(parameters);
            for (String field : List.of("direction", "duration_ms", "target_oid", "object_id", "quantity")) {
                assertFalse(output.has(field));
            }
        }
        for (String action : List.of("chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move")) {
            ObjectNode input = action(action, existingParameters(action));
            assertEquals(input, mapper.readTree(mapper.writeValueAsString(mapper.treeToValue(input, ActorActRequest.class))));
        }
    }

    private ObjectNode action(String name, Map<String, Object> parameters) {
        ObjectNode input = mapper.createObjectNode();
        input.put("session_id", "session-200"); input.put("expected_generation", 7); input.put("action", name);
        input.set("parameters", mapper.valueToTree(parameters));
        input.set("expected_context", mapper.createObjectNode().put("scene_ref", "scene-200").put("runtime_generation", "world-200"));
        return input;
    }

    private static Map<String, Map<String, Object>> examples() {
        Map<String, Map<String, Object>> examples = new LinkedHashMap<>();
        examples.put("move", Map.of("direction", "LEFT", "duration_ms", 3000));
        examples.put("stop", Map.of()); examples.put("attack", Map.of("target_oid", 42));
        examples.put("pickup", Map.of("object_id", 43));
        examples.put("drop", Map.of("inventory_type", "USE", "source_slot", 127, "quantity", 32767));
        examples.put("use_item", Map.of("source_slot", 1));
        examples.put("equip", Map.of("source_slot", 1, "target_slot", -11));
        examples.put("unequip", Map.of("source_slot", -11, "target_slot", 127));
        return examples;
    }

    private static Map<String, Object> existingParameters(String action) {
        return switch (action) {
            case "chat" -> Map.of("text", "hello");
            case "npc_center_open" -> Map.of();
            case "npc_cancel" -> Map.of("dialogue_version", 5);
            case "npc_reply" -> Map.of("dialogue_version", 5, "kind", "select", "selection", 7);
            case "inventory_move" -> Map.of("inventory_type", "ETC", "source_slot", 1, "target_slot", 2);
            default -> throw new IllegalArgumentException(action);
        };
    }
}
