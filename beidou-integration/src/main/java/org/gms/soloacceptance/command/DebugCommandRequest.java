package org.gms.soloacceptance.command;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** A complete native command line; role selection remains a configured profile. */
@JsonDeserialize(using = DebugCommandRequest.Deserializer.class)
public record DebugCommandRequest(
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("expected_command_generation") String expectedCommandGeneration,
        String command) {

    /** Keeps the native SubmitBody wire format while rejecting unknown outer fields as well. */
    @JsonDeserialize(using = SubmissionDeserializer.class)
    public record Submission(String requestId, DebugCommandRequest data) { }

    public static final class SubmissionDeserializer extends JsonDeserializer<Submission> {
        @Override
        public Submission deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode body = object(parser.getCodec().readTree(parser), Set.of("requestId", "data"), context);
            return body == null ? null : new Submission(string(body.get("requestId"), context),
                    parse(body.get("data"), context));
        }
    }

    public static final class Deserializer extends JsonDeserializer<DebugCommandRequest> {
        @Override
        public DebugCommandRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return parse(parser.getCodec().readTree(parser), context);
        }
    }

    private static DebugCommandRequest parse(JsonNode value, DeserializationContext context) throws IOException {
        JsonNode body = object(value,
                Set.of("profile_id", "expected_command_generation", "command"), context);
        if (body == null) return null;
        return new DebugCommandRequest(string(body.get("profile_id"), context),
                string(body.get("expected_command_generation"), context), string(body.get("command"), context));
    }

    private static JsonNode object(JsonNode value, Set<String> allowed, DeserializationContext context)
            throws IOException {
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) return context.reportInputMismatch(DebugCommandRequest.class, "command object required");
        Iterator<String> fields = value.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                return context.reportInputMismatch(DebugCommandRequest.class, "unsupported command field");
            }
        }
        return value;
    }

    private static String string(JsonNode value, DeserializationContext context) throws IOException {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) return context.reportInputMismatch(String.class, "command string required");
        return value.textValue();
    }
}
