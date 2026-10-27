package org.gms.soloacceptance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

public record ObservationRequest(String scope, @JsonProperty("scene_ref") String sceneRef,
                                 @JsonDeserialize(using = ObservationRequest.LimitDeserializer.class) Integer limit) {
    public static final class LimitDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "limit must be an integer");
            }
            return parser.getIntValue();
        }
    }
}
