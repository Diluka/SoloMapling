package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

public record ActorCloseRequest(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("expected_generation")
        @JsonDeserialize(using = StrictGenerationDeserializer.class) Long expectedGeneration) {

    public static final class StrictGenerationDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "expected_generation must be an integer");
            }
            return parser.getLongValue();
        }
    }
}
