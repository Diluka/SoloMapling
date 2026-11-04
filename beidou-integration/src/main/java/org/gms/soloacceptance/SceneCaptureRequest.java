package org.gms.soloacceptance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/** The HTTP edge owns capture parameter validation; internal sampling receives these fixed settings. */
public final class SceneCaptureRequest {
    private SceneCaptureRequest() { }

    public record Snapshot(@JsonProperty("scene_ref")
                           @JsonDeserialize(using = StrictString.class) String sceneRef) { }

    public record Record(@JsonProperty("scene_ref")
                         @JsonDeserialize(using = StrictString.class) String sceneRef,
                         @JsonProperty("duration_ms")
                         @JsonDeserialize(using = StrictInteger.class) Integer durationMs,
                         @JsonProperty("interval_ms")
                         @JsonDeserialize(using = StrictInteger.class) Integer intervalMs) {
        public Record {
            durationMs = durationMs == null ? 10_000 : durationMs;
            intervalMs = intervalMs == null ? 100 : intervalMs;
            if (durationMs < 100 || durationMs > 120_000) {
                throw new ObservationException(HttpStatus.BAD_REQUEST, "invalid_capture_duration");
            }
            if (intervalMs < 50 || intervalMs > 5_000 || intervalMs > durationMs) {
                throw new ObservationException(HttpStatus.BAD_REQUEST, "invalid_capture_interval");
            }
        }
    }

    public static final class StrictString extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(String.class, "scene_ref must be a string");
            }
            return parser.getText();
        }
    }

    public static final class StrictInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "capture timing must be an integer");
            }
            return parser.getIntValue();
        }
    }
}
