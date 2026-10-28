package soloMapling.game.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Value;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded detached values and output for native script invocations; never owns an engine. */
public final class ScriptValues {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int JSON_LIMIT = 16_384;
    private static final int OUTPUT_LIMIT = 16_384;

    private ScriptValues() { }

    public static Object detachedJson(Value value) { return readJson(value, 0, new ArrayList<>(), new int[]{0}); }
    public static String json(Object value) { return encodeJson(value); }
    public static String errorText(String value) { return bounded(value, 1_024); }

    /** Converts guest values while its context is still cancellable; only detached JSON crosses out. */
    private static Object readJson(Value value, int depth, List<Value> ancestors, int[] size) {
        if (depth > 32 || ++size[0] > JSON_LIMIT) throw new InvalidJson("script_result_limit");
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) {
            String string = value.asString();
            requireUnicode(string, "script_result_not_json");
            size[0] += string.length();
            if (size[0] > JSON_LIMIT) throw new InvalidJson("script_result_limit");
            return string;
        }
        if (value.isNumber()) {
            if ("bigint".equals(value.getMetaObject().getMetaSimpleName())) throw new InvalidJson("script_result_not_json");
            if (value.fitsInLong()) return value.asLong();
            double number = value.asDouble();
            if (!Double.isFinite(number)) throw new InvalidJson("script_result_not_json");
            return number;
        }
        if (value.canExecute() || value.isHostObject() || value.isProxyObject()) throw new InvalidJson("script_result_not_json");
        for (Value ancestor : ancestors) if (ancestor.equals(value)) throw new InvalidJson("script_result_cycle");
        ancestors.add(value);
        try {
            if (value.hasArrayElements()) {
                long length = value.getArraySize();
                if (length > JSON_LIMIT) throw new InvalidJson("script_result_limit");
                List<Object> array = new ArrayList<>();
                for (long i = 0; i < length; i++) array.add(readJson(value.getArrayElement(i), depth + 1, ancestors, size));
                return Collections.unmodifiableList(array);
            }
            if (value.hasMembers()) {
                // Promise and other deferred values are not successful synchronous script results.
                if (value.hasMember("then") && value.getMember("then").canExecute()) throw new InvalidJson("script_result_not_json");
                Map<String, Object> object = new LinkedHashMap<>();
                for (String key : value.getMemberKeys()) {
                    requireUnicode(key, "script_result_not_json");
                    size[0] += key.length();
                    if (size[0] > JSON_LIMIT) throw new InvalidJson("script_result_limit");
                    object.put(key, readJson(value.getMember(key), depth + 1, ancestors, size));
                }
                return Collections.unmodifiableMap(object);
            }
            throw new InvalidJson("script_result_not_json");
        } finally { ancestors.remove(ancestors.size() - 1); }
    }

    private static String encodeJson(Object value) {
        checkJsonTree(value, 0, new IdentityHashMap<>(), new int[]{0});
        try {
            String json = JSON.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > JSON_LIMIT) throw new InvalidJson("script_json_limit");
            return json;
        } catch (JsonProcessingException failed) { throw new InvalidJson("script_parameters_not_json"); }
    }

    private static void checkJsonTree(Object value, int depth, IdentityHashMap<Object, Boolean> ancestors, int[] size) {
        if (depth > 32 || ++size[0] > JSON_LIMIT) throw new InvalidJson("script_json_limit");
        if (value == null || value instanceof Boolean) return;
        if (value instanceof String string) {
            requireUnicode(string, "script_value_not_json");
            size[0] += string.length();
            if (size[0] > JSON_LIMIT) throw new InvalidJson("script_json_limit");
            return;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal) {
            if (!Double.isFinite(((Number) value).doubleValue())) throw new InvalidJson("script_value_not_json");
            return;
        }
        if (ancestors.put(value, Boolean.TRUE) != null) throw new InvalidJson("script_json_cycle");
        try {
            if (value instanceof Map<?, ?> object) {
                for (Map.Entry<?, ?> entry : object.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new InvalidJson("script_value_not_json");
                    requireUnicode(key, "script_value_not_json");
                    size[0] += key.length();
                    checkJsonTree(entry.getValue(), depth + 1, ancestors, size);
                }
            } else if (value instanceof List<?> array) {
                for (Object entry : array) checkJsonTree(entry, depth + 1, ancestors, size);
            } else throw new InvalidJson("script_value_not_json");
        } finally { ancestors.remove(value); }
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "script_error";
        // Java strings can contain unpaired UTF-16 surrogates. A guest exception must never make
        // the JSON/MCP transport itself fail, so error text replaces only those invalid code units.
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    safe.append(character).append(value.charAt(++index));
                } else safe.append('\ufffd');
            } else if (Character.isLowSurrogate(character)) safe.append('\ufffd');
            else safe.append(character);
        }
        value = safe.toString();
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static void requireUnicode(String value, String reason) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new InvalidJson(reason);
                }
            } else if (Character.isLowSurrogate(character)) throw new InvalidJson(reason);
        }
    }

    private static final class InvalidJson extends IllegalArgumentException {
        private InvalidJson(String message) { super(message); }
    }

    public static final class CapturedOutput extends OutputStream {
        public CapturedOutput() { }
        public synchronized boolean isTruncated() { return truncated; }
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;
        @Override public synchronized void write(int value) {
            if (bytes.size() < OUTPUT_LIMIT) bytes.write(value); else truncated = true;
        }
        @Override public synchronized void write(byte[] data, int offset, int length) {
            int accepted = Math.min(length, OUTPUT_LIMIT - bytes.size());
            bytes.write(data, offset, accepted);
            truncated |= accepted < length;
        }
        public synchronized String text() {
            byte[] data = bytes.toByteArray();
            int end = data.length;
            // Output can be cut in the middle of one UTF-8 code point. Drop only that incomplete tail.
            if (truncated && end > 0) {
                int start = end - 1;
                while (start >= 0 && (data[start] & 0xc0) == 0x80) start--;
                if (start >= 0) {
                    int lead = data[start] & 0xff;
                    int needed = lead < 0x80 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
                    if (end - start < needed) end = start;
                }
            }
            return new String(data, 0, end, StandardCharsets.UTF_8);
        }
    }
}
