package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.Set;

/** Only the fixed MCP module belonging to this owned session can be controlled. */
@JsonDeserialize(using = ActorModuleControlRequest.Deserializer.class)
public record ActorModuleControlRequest(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("expected_generation") Long expectedGeneration,
        @JsonProperty("expected_module_generation") Long expectedModuleGeneration,
        @JsonProperty("operation") String operation) {

    /** Keep the finite boundary strict even when host Jackson coercion/unknown-field settings are lenient. */
    public static final class Deserializer extends JsonDeserializer<ActorModuleControlRequest> {
        private static final Set<String> FIELDS = Set.of(
                "session_id", "expected_generation", "expected_module_generation", "operation");

        @Override
        public ActorModuleControlRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode body = parser.getCodec().readTree(parser);
            if (body == null || body.isNull()) return null;
            if (!body.isObject()) return context.reportInputMismatch(ActorModuleControlRequest.class, "module control data must be an object");
            var fields = body.fieldNames();
            while (fields.hasNext()) {
                if (!FIELDS.contains(fields.next())) {
                    return context.reportInputMismatch(ActorModuleControlRequest.class, "unsupported module control field");
                }
            }
            return new ActorModuleControlRequest(string(body.get("session_id"), context),
                    integer(body.get("expected_generation"), context),
                    integer(body.get("expected_module_generation"), context),
                    string(body.get("operation"), context));
        }

        private static String string(JsonNode value, DeserializationContext context) throws IOException {
            if (value == null || value.isNull()) return null;
            if (!value.isTextual()) return context.reportInputMismatch(String.class, "module control string must be textual");
            return value.textValue();
        }

        private static Long integer(JsonNode value, DeserializationContext context) throws IOException {
            if (value == null || value.isNull()) return null;
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                return context.reportInputMismatch(Long.class, "module control generation must be an integer");
            }
            return value.longValue();
        }
    }
}
