package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import soloMapling.game.script.nativeenv.NativeScriptRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One native BeiDou script invocation, bound to the existing role session. */
@JsonDeserialize(using = ActorScriptRequest.Deserializer.class)
public record ActorScriptRequest(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("expected_generation") Long expectedGeneration,
        String kind, String operation,
        @JsonProperty("context_id") String contextId,
        Map<String, Object> options, String entry, List<Object> args, String source,
        Map<String, Object> parameters,
        @JsonProperty("timeout_ms") Long timeoutMs) {
    public ActorScriptRequest {
        source = source == null ? "" : source;
        options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        args = args == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(args));
        parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        timeoutMs = timeoutMs == null ? NativeScriptRequest.DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    public static final class Deserializer extends JsonDeserializer<ActorScriptRequest> {
        @Override public ActorScriptRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = ActorScriptJson.request(parser, context, ActorScriptJson.RUN_FIELDS, ActorScriptRequest.class);
            return new ActorScriptRequest(ActorScriptJson.requiredString(node, "session_id", context),
                    ActorScriptJson.requiredLong(node, "expected_generation", context),
                    ActorScriptJson.requiredString(node, "kind", context), ActorScriptJson.requiredString(node, "operation", context),
                    ActorScriptJson.optionalString(node, "context_id", context), ActorScriptJson.object(parser, node, "options", context),
                    ActorScriptJson.optionalString(node, "entry", context), ActorScriptJson.args(parser, node, context),
                    ActorScriptJson.source(node, context), ActorScriptJson.object(parser, node, "parameters", context),
                    ActorScriptJson.timeout(node, context));
        }
    }
}
