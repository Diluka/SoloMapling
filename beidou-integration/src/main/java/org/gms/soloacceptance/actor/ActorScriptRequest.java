package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/** One role-scoped invocation; JSON parameters never expose native role objects. */
public record ActorScriptRequest(
        @JsonProperty("session_id")
        @JsonDeserialize(using = ActorObserveRequest.StrictStringDeserializer.class) String sessionId,
        @JsonProperty("expected_generation")
        @JsonDeserialize(using = ActorObserveRequest.StrictLongDeserializer.class) Long expectedGeneration,
        @JsonDeserialize(using = ActorObserveRequest.StrictStringDeserializer.class) String source,
        Map<String, Object> parameters,
        @JsonProperty("timeout_ms")
        @JsonDeserialize(using = ActorObserveRequest.StrictLongDeserializer.class) Long timeoutMs,
        ActorScriptEnvironment environment) {
    public ActorScriptRequest {
        if (source == null && environment != null) source = "";
        parameters = parameters == null ? Map.of() : parameters;
        timeoutMs = timeoutMs == null ? 1000L : timeoutMs;
    }

    public ActorScriptRequest(String sessionId, Long expectedGeneration, String source,
                              Map<String, Object> parameters, Long timeoutMs) {
        this(sessionId, expectedGeneration, source, parameters, timeoutMs, null);
    }
}
