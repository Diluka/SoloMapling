package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/** The original fingerprint also closes the cancellation-before-admission race. */
public record ActorScriptCancelRequest(
        @JsonProperty("session_id")
        @JsonDeserialize(using = ActorObserveRequest.StrictStringDeserializer.class) String sessionId,
        @JsonProperty("expected_generation")
        @JsonDeserialize(using = ActorObserveRequest.StrictLongDeserializer.class) Long expectedGeneration,
        @JsonProperty("target_request_id")
        @JsonDeserialize(using = ActorObserveRequest.StrictStringDeserializer.class) String targetRequestId,
        @JsonDeserialize(using = ActorObserveRequest.StrictStringDeserializer.class) String source,
        Map<String, Object> parameters,
        @JsonProperty("timeout_ms")
        @JsonDeserialize(using = ActorObserveRequest.StrictLongDeserializer.class) Long timeoutMs) {
    ActorScriptRequest invocation() {
        return new ActorScriptRequest(sessionId, expectedGeneration, source, parameters, timeoutMs);
    }
}
