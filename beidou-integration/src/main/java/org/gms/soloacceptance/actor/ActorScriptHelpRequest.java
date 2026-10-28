package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read-only discovery from the exact active role/module context. */
public record ActorScriptHelpRequest(
        @JsonProperty("session_id")
        @JsonDeserialize(using = ActorObserveRequest.StrictStringDeserializer.class) String sessionId,
        @JsonProperty("expected_generation")
        @JsonDeserialize(using = ActorObserveRequest.StrictLongDeserializer.class) Long expectedGeneration,
        @JsonDeserialize(using = NamesDeserializer.class) List<String> names) {
    public ActorScriptHelpRequest { names = names == null ? List.of() : List.copyOf(names); }

    public static final class NamesDeserializer extends JsonDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.START_ARRAY) {
                return context.reportInputMismatch(List.class, "names must be an array of strings");
            }
            List<String> result = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.VALUE_STRING || result.size() >= 64) {
                    return context.reportInputMismatch(List.class, "names must contain at most 64 strings");
                }
                result.add(parser.getText());
            }
            return result;
        }
    }
}
