package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** Fixed owner-scoped payloads for chat and the native center dialogue actions. */
@JsonDeserialize(using = ActorActRequest.Deserializer.class)
public record ActorActRequest(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("expected_generation") Long expectedGeneration,
        String action,
        Parameters parameters,
        @JsonProperty("expected_context") ExpectedContext expectedContext) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameters(String text, @JsonProperty("dialogue_version") Long dialogueVersion,
                             String kind, Integer selection,
                             @JsonProperty("inventory_type") String inventoryType,
                             @JsonProperty("source_slot") Integer sourceSlot,
                             @JsonProperty("target_slot") Integer targetSlot) {
        public Parameters(String text, Long dialogueVersion, String kind, Integer selection) {
            this(text, dialogueVersion, kind, selection, null, null, null);
        }

        public Parameters(String inventoryType, int sourceSlot, int targetSlot) {
            this(null, null, null, null, inventoryType, sourceSlot, targetSlot);
        }
        public Parameters(String text) {
            this(text, null, null, null);
        }

        public Parameters(String text, Long dialogueVersion) {
            this(text, dialogueVersion, null, null);
        }
    }

    public record ExpectedContext(@JsonProperty("scene_ref") String sceneRef,
                                  @JsonProperty("runtime_generation") String runtimeGeneration) {}

    /** Validate these three finite objects independently of the host's Jackson coercion settings. */
    public static final class Deserializer extends JsonDeserializer<ActorActRequest> {
        private static final Set<String> ROOT_FIELDS = Set.of(
                "session_id", "expected_generation", "action", "parameters", "expected_context");
        private static final Set<String> CHAT_PARAMETER_FIELDS = Set.of("text");
        private static final Set<String> NPC_CANCEL_PARAMETER_FIELDS = Set.of("dialogue_version");
        private static final Set<String> NPC_REPLY_PARAMETER_FIELDS = Set.of("dialogue_version", "kind");
        private static final Set<String> NPC_SELECT_PARAMETER_FIELDS = Set.of("dialogue_version", "kind", "selection");
        private static final Set<String> INVENTORY_MOVE_FIELDS = Set.of("inventory_type", "source_slot", "target_slot");
        private static final Set<String> KNOWN_PARAMETER_FIELDS = Set.of("text", "dialogue_version");
        private static final Set<String> CONTEXT_FIELDS = Set.of("scene_ref", "runtime_generation");

        @Override
        public ActorActRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode body = object(parser.getCodec().readTree(parser), ROOT_FIELDS, context);
            if (body == null) return null;
            String action = string(body.get("action"), context);
            JsonNode rawParameters = body.get("parameters");
            String kind = "npc_reply".equals(action) && rawParameters != null
                    ? string(rawParameters.get("kind"), context) : null;
            Set<String> parameterFields = switch (action == null ? "" : action) {
                case "chat" -> CHAT_PARAMETER_FIELDS;
                case "inventory_move" -> INVENTORY_MOVE_FIELDS;
                case "npc_center_open" -> Set.of();
                case "npc_cancel" -> NPC_CANCEL_PARAMETER_FIELDS;
                case "npc_reply" -> "select".equals(kind) ? NPC_SELECT_PARAMETER_FIELDS : NPC_REPLY_PARAMETER_FIELDS;
                default -> KNOWN_PARAMETER_FIELDS;
            };
            JsonNode parameters = object(rawParameters, parameterFields, context);
            JsonNode expectedContext = object(body.get("expected_context"), CONTEXT_FIELDS, context);
            return new ActorActRequest(
                    string(body.get("session_id"), context),
                    integer(body.get("expected_generation"), context),
                    action,
                    parameters == null ? null : new Parameters(
                            string(parameters.get("text"), context), integer(parameters.get("dialogue_version"), context),
                            kind, selection(parameters.get("selection"), context),
                            string(parameters.get("inventory_type"), context),
                            selection(parameters.get("source_slot"), context), selection(parameters.get("target_slot"), context)),
                    expectedContext == null ? null : new ExpectedContext(
                            string(expectedContext.get("scene_ref"), context),
                            string(expectedContext.get("runtime_generation"), context)));
        }

        private static JsonNode object(JsonNode value, Set<String> allowed,
                                       DeserializationContext context) throws IOException {
            if (value == null || value.isNull()) return null;
            if (!value.isObject()) {
                return context.reportInputMismatch(ActorActRequest.class, "action data must contain objects");
            }
            Iterator<String> fields = value.fieldNames();
            while (fields.hasNext()) {
                if (!allowed.contains(fields.next())) {
                    return context.reportInputMismatch(ActorActRequest.class, "unsupported action field");
                }
            }
            return value;
        }

        private static String string(JsonNode value, DeserializationContext context) throws IOException {
            if (value == null || value.isNull()) return null;
            if (!value.isTextual()) {
                return context.reportInputMismatch(String.class, "action strings must be strings");
            }
            return value.textValue();
        }

        private static Long integer(JsonNode value, DeserializationContext context) throws IOException {
            if (value == null || value.isNull()) return null;
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                return context.reportInputMismatch(Long.class, "action numbers must be long integers");
            }
            return value.longValue();
        }

        private static Integer selection(JsonNode value, DeserializationContext context) throws IOException {
            if (value == null || value.isNull()) return null;
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                return context.reportInputMismatch(Integer.class, "selection must be an integer");
            }
            return value.intValue();
        }
    }
}
