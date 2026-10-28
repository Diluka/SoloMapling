package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Cancellation carries the complete original native invocation fingerprint. */
@JsonDeserialize(using = ActorScriptCancelRequest.Deserializer.class)
public record ActorScriptCancelRequest(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("expected_generation") Long expectedGeneration,
        @JsonProperty("target_request_id") String targetRequestId,
        String kind, String operation,
        @JsonProperty("context_id") String contextId,
        Map<String, Object> options, String entry, List<Object> args, String source,
        Map<String, Object> parameters,
        @JsonProperty("timeout_ms") Long timeoutMs) {
    public ActorScriptCancelRequest {
        ActorScriptRequest invocation = new ActorScriptRequest(sessionId, expectedGeneration, kind, operation,
                contextId, options, entry, args, source, parameters, timeoutMs);
        options = invocation.options(); args = invocation.args(); source = invocation.source();
        parameters = invocation.parameters(); timeoutMs = invocation.timeoutMs();
    }

    ActorScriptRequest invocation() {
        return new ActorScriptRequest(sessionId, expectedGeneration, kind, operation, contextId,
                options, entry, args, source, parameters, timeoutMs);
    }

    public static final class Deserializer extends JsonDeserializer<ActorScriptCancelRequest> {
        @Override public ActorScriptCancelRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = ActorScriptJson.request(parser, context, ActorScriptJson.CANCEL_FIELDS, ActorScriptCancelRequest.class);
            return new ActorScriptCancelRequest(ActorScriptJson.requiredString(node, "session_id", context),
                    ActorScriptJson.requiredLong(node, "expected_generation", context),
                    ActorScriptJson.requiredString(node, "target_request_id", context),
                    ActorScriptJson.requiredString(node, "kind", context), ActorScriptJson.requiredString(node, "operation", context),
                    ActorScriptJson.optionalString(node, "context_id", context), ActorScriptJson.object(parser, node, "options", context),
                    ActorScriptJson.optionalString(node, "entry", context), ActorScriptJson.args(parser, node, context),
                    ActorScriptJson.source(node, context), ActorScriptJson.object(parser, node, "parameters", context),
                    ActorScriptJson.timeout(node, context));
        }
    }
}
