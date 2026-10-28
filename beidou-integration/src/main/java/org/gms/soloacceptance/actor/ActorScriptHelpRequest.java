package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/** Read-only discovery of the exact role's native script kinds and owned context. */
@JsonDeserialize(using = ActorScriptHelpRequest.Deserializer.class)
public record ActorScriptHelpRequest(@JsonProperty("session_id") String sessionId,
                                     @JsonProperty("expected_generation") Long expectedGeneration) {
    public static final class Deserializer extends JsonDeserializer<ActorScriptHelpRequest> {
        @Override public ActorScriptHelpRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = ActorScriptJson.request(parser, context, ActorScriptJson.HELP_FIELDS, ActorScriptHelpRequest.class);
            return new ActorScriptHelpRequest(ActorScriptJson.requiredString(node, "session_id", context),
                    ActorScriptJson.requiredLong(node, "expected_generation", context));
        }
    }
}
