package soloMapling.game.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Value/output boundaries used by the native environment, without another script execution mode. */
class ScriptValuesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void nativeResultsRemainDetachedJsonAfterTheirGuestContextCloses() throws Exception {
        Object detached;
        try (Context guest = guest()) {
            detached = ScriptValues.detachedJson(guest.eval("js",
                    "({nullable: null, enabled: true, total: 42, fraction: 1.25, items: ['中文', '😀', null]})"));
        }
        Map<?, ?> object = assertInstanceOf(Map.class, detached);
        assertNull(object.get("nullable"));
        assertEquals(true, object.get("enabled"));
        assertEquals(42, ((Number) object.get("total")).intValue());
        assertEquals(1.25, ((Number) object.get("fraction")).doubleValue());
        assertEquals(Arrays.asList("中文", "😀", null), object.get("items"));
        assertEquals(JSON.readTree("""
                {"nullable":null,"enabled":true,"total":42,"fraction":1.25,"items":["中文","😀",null]}
                """), JSON.readTree(ScriptValues.json(detached)));
    }

    @Test void jsonAcceptsDetachedTreesAndRejectsCyclesHostObjectsAndNonFiniteNumbers() throws Exception {
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("nullable", null);
        accepted.put("values", Arrays.asList(true, 42, 0.25, "中文"));
        assertEquals(JSON.readTree("{\"nullable\":null,\"values\":[true,42,0.25,\"中文\"]}"),
                JSON.readTree(ScriptValues.json(accepted)));
        assertEquals("null", ScriptValues.json(null));

        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        for (Object rejected : List.of(cyclic, new Object(), Map.of(1, "invalid key"),
                Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> ScriptValues.json(rejected));
        }
    }

    @Test void executableDeferredCyclicAndNonFiniteGuestValuesCannotCrossTheBoundary() {
        try (Context guest = guest()) {
            for (String source : List.of("(() => 1)", "Promise.resolve(1)", "NaN", "Infinity", "1n",
                    "123456789012345678901234567890n", "(() => { const value = {}; value.self = value; return value; })()")) {
                assertThrows(IllegalArgumentException.class,
                        () -> ScriptValues.detachedJson(guest.eval("js", source)), source);
            }
        }
    }

    @Test void jsonBoundariesAccountForEncodedUtf8SizeAndNesting() {
        assertEquals(16_384, ScriptValues.json("x".repeat(16_382)).getBytes(StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class, () -> ScriptValues.json("x".repeat(16_383)));
        assertTrue(ScriptValues.json("中".repeat(5_460)).getBytes(StandardCharsets.UTF_8).length <= 16_384);
        assertThrows(IllegalArgumentException.class, () -> ScriptValues.json("中".repeat(5_461)));

        Object nested = 0;
        for (int index = 0; index < 40; index++) nested = Map.of("nested", nested);
        Object tooDeep = nested;
        assertThrows(IllegalArgumentException.class, () -> ScriptValues.json(tooDeep));
        try (Context guest = guest()) {
            assertThrows(IllegalArgumentException.class, () -> ScriptValues.detachedJson(guest.eval("js",
                    "(() => { let value = 0; for (let i = 0; i < 40; i++) value = {nested: value}; return value; })()")));
        }
    }

    @Test void unicodeResultsAndErrorTextPreserveCompleteCharacters() {
        try (Context guest = guest()) {
            for (String source : List.of("'\\uD800'", "'\\uDC00'", "({'\\uD800': 1})", "({value: '\\uDC00'})")) {
                assertThrows(IllegalArgumentException.class,
                        () -> ScriptValues.detachedJson(guest.eval("js", source)), source);
            }
            assertEquals(Map.of("😀", "😀"), ScriptValues.detachedJson(guest.eval("js", "({'😀': '😀'})")));
        }
        assertThrows(IllegalArgumentException.class, () -> ScriptValues.json("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> ScriptValues.json(Map.of("\udc00", 1)));
        assertEquals("bad\ufffdtext\ufffd", ScriptValues.errorText("bad\ud800text\udc00"));
        String bounded = ScriptValues.errorText("😀".repeat(2_000));
        assertEquals(1_024, bounded.codePointCount(0, bounded.length()));
        assertEquals("😀".repeat(1_024), bounded);
    }

    @Test void capturedOutputIsBoundedWithoutReturningAPartialUtf8Character() {
        ScriptValues.CapturedOutput output = new ScriptValues.CapturedOutput();
        byte[] exact = "x".repeat(16_384).getBytes(StandardCharsets.UTF_8);
        output.write(exact, 0, exact.length);
        assertFalse(output.isTruncated());
        output.write('!');
        assertTrue(output.isTruncated());
        assertEquals("x".repeat(16_384), output.text());

        ScriptValues.CapturedOutput unicode = new ScriptValues.CapturedOutput();
        byte[] crossing = ("x".repeat(16_383) + "😀").getBytes(StandardCharsets.UTF_8);
        unicode.write(crossing, 0, crossing.length);
        assertTrue(unicode.isTruncated());
        assertEquals("x".repeat(16_383), unicode.text());
        assertFalse(unicode.text().contains("\ufffd"));
    }

    private static Context guest() {
        return Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
    }
}
