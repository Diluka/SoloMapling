package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A native script context descriptor, separate from the submitted script's JSON parameters. */
@JsonDeserialize(using = ActorScriptEnvironment.Deserializer.class)
public record ActorScriptEnvironment(String kind, String operation,
                                     @JsonProperty("context_id") String contextId,
                                     Map<String, Object> options, String entry, List<Object> args) {
    public ActorScriptEnvironment {
        options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        args = args == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(args));
    }

    /** Native descriptors stay strict even when the application's mapper ignores unknown properties. */
    public static final class Deserializer extends JsonDeserializer<ActorScriptEnvironment> {
        private static final Set<String> FIELDS = Set.of("kind", "operation", "context_id", "options", "entry", "args");

        @Override
        public ActorScriptEnvironment deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (!node.isObject()) return context.reportInputMismatch(ActorScriptEnvironment.class, "environment must be an object");
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                if (!FIELDS.contains(fields.next())) {
                    return context.reportInputMismatch(ActorScriptEnvironment.class, "environment contains an unknown field");
                }
            }
            String kind = string(node, "kind", true, context);
            String operation = string(node, "operation", true, context);
            String contextId = string(node, "context_id", false, context);
            String entry = string(node, "entry", false, context);
            JsonNode options = node.get("options");
            JsonNode args = node.get("args");
            if (options != null && !options.isObject()) {
                return context.reportInputMismatch(ActorScriptEnvironment.class, "environment options must be an object");
            }
            if (args != null && !args.isArray()) {
                return context.reportInputMismatch(ActorScriptEnvironment.class, "environment args must be an array");
            }
            return new ActorScriptEnvironment(kind, operation, contextId, object(parser, options), entry, array(parser, args));
        }

        private static String string(JsonNode object, String name, boolean required, DeserializationContext context)
                throws IOException {
            JsonNode value = object.get(name);
            if (!required && (value == null || value.isNull())) return null;
            if (value == null || !value.isTextual()) {
                return context.reportInputMismatch(String.class, "environment string field has an invalid type");
            }
            return value.textValue();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> object(JsonParser parser, JsonNode value) throws IOException {
            return value == null ? Map.of() : parser.getCodec().treeToValue(value, Map.class);
        }

        @SuppressWarnings("unchecked")
        private static List<Object> array(JsonParser parser, JsonNode value) throws IOException {
            return value == null ? List.of() : parser.getCodec().treeToValue(value, List.class);
        }
    }
}
