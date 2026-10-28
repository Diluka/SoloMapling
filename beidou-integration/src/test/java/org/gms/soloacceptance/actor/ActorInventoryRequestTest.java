package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The inventory action must not inherit Jackson's coercion or another action's parameters. */
class ActorInventoryRequestTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void inventoryMoveRoundTripsAllOrdinaryInventoriesAndBoundarySlots() throws Exception {
        for (String inventory : List.of("EQUIP", "USE", "SETUP", "ETC", "CASH")) {
            ActorActRequest request = new ActorActRequest("session-id", 7L, "inventory_move",
                    new ActorActRequest.Parameters(inventory, 1, 127),
                    new ActorActRequest.ExpectedContext("scene-id", "runtime-id"));
            String encoded = mapper.writeValueAsString(request);
            assertEquals(request, mapper.readValue(encoded, ActorActRequest.class));
            ObjectNode body = (ObjectNode) mapper.readTree(encoded);
            assertEquals(3, body.get("parameters").size());
            assertEquals(inventory, body.at("/parameters/inventory_type").textValue());
            assertFalse(body.get("parameters").has("quantity"));
        }
    }

    @Test
    void slotNumbersRejectCoercionAndOverflowBeforeServiceValidation() throws Exception {
        for (String field : List.of("source_slot", "target_slot")) {
            for (String value : List.of("true", "false", "1.0", "\"1\"", "{}", "[]",
                    "2147483648", "-2147483649")) {
                ObjectNode body = action();
                ((ObjectNode) body.get("parameters")).set(field, mapper.readTree(value));
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), field + "=" + value);
            }
        }
        for (String value : List.of("1", "true", "{}", "[]")) {
            ObjectNode body = action();
            ((ObjectNode) body.get("parameters")).set("inventory_type", mapper.readTree(value));
            assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), "inventory_type=" + value);
        }
    }

    @Test
    void inventoryMoveCannotSmuggleChatNpcDiscardOrPacketParameters() throws Exception {
        for (String field : List.of("text", "dialogue_version", "kind", "selection", "quantity", "action",
                "character_id", "packet", "handler", "script")) {
            ObjectNode body = action();
            ((ObjectNode) body.get("parameters")).putNull(field);
            assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), field);
        }
        for (String owner : List.of("root", "expected_context")) {
            ObjectNode body = action();
            ("root".equals(owner) ? body : (ObjectNode) body.get(owner)).put("character_id", 41);
            assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), owner);
        }
    }

    @Test
    void existingActionsCannotAcceptInventoryFieldsEvenWhenNull() throws Exception {
        for (String action : List.of("chat", "npc_center_open", "npc_cancel", "npc_reply")) {
            for (String field : List.of("inventory_type", "source_slot", "target_slot")) {
                ObjectNode body = action();
                body.put("action", action);
                ObjectNode parameters = mapper.createObjectNode();
                if ("chat".equals(action)) parameters.put("text", "hello");
                if ("npc_cancel".equals(action) || "npc_reply".equals(action)) parameters.put("dialogue_version", 1);
                if ("npc_reply".equals(action)) parameters.put("kind", "decline");
                parameters.putNull(field);
                body.set("parameters", parameters);
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), action + "." + field);
            }
        }
    }

    private ObjectNode action() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"session_id":"session-id","expected_generation":7,"action":"inventory_move",
                 "parameters":{"inventory_type":"USE","source_slot":1,"target_slot":127},
                 "expected_context":{"scene_ref":"scene-id","runtime_generation":"runtime-id"}}
                """);
    }
}
