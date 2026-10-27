package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/** A read-only packet cursor is scoped to the exact actor session and generation. */
public record ActorObserveRequest(
        @JsonProperty("session_id")
        @JsonDeserialize(using = StrictStringDeserializer.class) String sessionId,
        @JsonProperty("expected_generation")
        @JsonDeserialize(using = StrictLongDeserializer.class) Long expectedGeneration,
        @JsonProperty("since_cursor")
        @JsonDeserialize(using = StrictLongDeserializer.class) Long sinceCursor,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer limit) {

    public ActorObserveRequest {
        sinceCursor = sinceCursor == null ? 0L : sinceCursor;
        limit = limit == null ? 100 : limit;
    }

    public static final class StrictStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(String.class, "session_id must be a string");
            }
            return parser.getText();
        }
    }

    public static final class StrictLongDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "generation and cursor must be integers");
            }
            return parser.getLongValue();
        }
    }

    public static final class StrictIntegerDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "limit must be an integer");
            }
            return parser.getIntValue();
        }
    }
}
