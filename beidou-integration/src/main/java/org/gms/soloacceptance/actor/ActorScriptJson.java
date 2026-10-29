package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import soloMapling.game.script.nativeenv.NativeScriptRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict shared transport parsing; native context rules stay in the service/runtime. */
final class ActorScriptJson {
    static final Set<String> RUN_FIELDS = Set.of("session_id", "expected_generation", "kind", "operation",
            "context_id", "options", "entry", "args", "source", "parameters", "timeout_ms");
    static final Set<String> CANCEL_FIELDS = Set.of("session_id", "expected_generation", "target_request_id",
            "kind", "operation", "context_id", "options", "entry", "args", "source", "parameters", "timeout_ms");

    private ActorScriptJson() { }

    static JsonNode request(JsonParser parser, DeserializationContext context, Set<String> fields, Class<?> type)
            throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (!node.isObject()) return context.reportInputMismatch(type, "script request must be an object");
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!fields.contains(names.next())) {
                return context.reportInputMismatch(type, "script request contains an unknown field");
            }
        }
        return node;
    }

    static String requiredString(JsonNode node, String field, DeserializationContext context) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return context.reportInputMismatch(String.class, "script string field is required");
        }
        return value.textValue();
    }

    static String optionalString(JsonNode node, String field, DeserializationContext context) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) return context.reportInputMismatch(String.class, "script string field has an invalid type");
        return value.textValue();
    }

    static String source(JsonNode node, DeserializationContext context) throws IOException {
        return node.has("source") ? requiredString(node, "source", context) : "";
    }

    static Long requiredLong(JsonNode node, String field, DeserializationContext context) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return context.reportInputMismatch(Long.class, "script integer field has an invalid type");
        }
        return value.longValue();
    }

    static Long timeout(JsonNode node, DeserializationContext context) throws IOException {
        return node.has("timeout_ms") ? requiredLong(node, "timeout_ms", context) : NativeScriptRequest.DEFAULT_TIMEOUT_MS;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(JsonParser parser, JsonNode node, String field, DeserializationContext context)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null) return Map.of();
        if (!value.isObject()) return context.reportInputMismatch(Map.class, "script object field has an invalid type");
        return parser.getCodec().treeToValue(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    static List<Object> args(JsonParser parser, JsonNode node, DeserializationContext context) throws IOException {
        JsonNode value = node.get("args");
        if (value == null) return List.of();
        if (!value.isArray()) return context.reportInputMismatch(List.class, "script args must be an array");
        return parser.getCodec().treeToValue(value, List.class);
    }
}
