package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/** Only a configured profile may select the native account and character. */
public record ActorOpenRequest(
        @JsonProperty("profile_id") String profileId,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer world,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer channel) {

    public static final class StrictIntegerDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "world and channel must be integers");
            }
            return parser.getIntValue();
        }
    }
}
