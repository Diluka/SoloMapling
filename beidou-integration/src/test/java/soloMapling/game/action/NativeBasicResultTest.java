package soloMapling.game.action;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.game.action.NativeActionResult.Dispatch.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;

class NativeBasicResultTest {
    @Test void nestedFactsAreCopiedAndEveryReturnedCollectionIsReadOnly() {
        Map<String, Object> item = new LinkedHashMap<>(Map.of("slot", 1, "quantity", 2));
        List<Object> items = new ArrayList<>(List.of(item));
        Map<String, Object> facts = positive();
        facts.put("items", items);
        NativeBasicResult result = result(SUCCEEDED, STARTED, facts);
        item.put("quantity", 99);
        items.clear();
        facts.clear();
        List<?> observed = (List<?>) result.facts().get("items");
        Map<?, ?> observedItem = (Map<?, ?>) observed.getFirst();
        assertEquals(2, observedItem.get("quantity"));
        assertThrows(UnsupportedOperationException.class, () -> result.facts().put("extra", 1));
        assertThrows(UnsupportedOperationException.class, observed::clear);
        assertThrows(UnsupportedOperationException.class, observedItem::clear);
    }

    @Test void scalarFactsMayContainNullAndFiniteNumbersWithoutRetainingTheirInputs() {
        Map<String, Object> facts = positive();
        facts.put("absent", null);
        facts.put("floats", List.of(1.25f, 2.5d, 3L, (short) 4, (byte) 5));
        NativeBasicResult result = result(SUCCEEDED, STARTED, facts);
        assertTrue(result.facts().containsKey("absent"));
        assertNull(result.facts().get("absent"));
        assertEquals(List.of(1.25f, 2.5d, 3L, (short) 4, (byte) 5), result.facts().get("floats"));
    }

    @Test void nativeObjectsNonFiniteValuesAndUnsafeKeysCannotEnterTerminalFacts() {
        for (Object value : new Object[]{new Object(), new int[]{1}, Double.NaN, Float.POSITIVE_INFINITY}) {
            Map<String, Object> facts = positive();
            facts.put("native", value);
            assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED, facts));
        }
        Map<String, Object> badKey = positive();
        badKey.put("private key with spaces", 1);
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED, badKey));
    }

    @Test void successNeedsBothNativeEntryAndAnExplicitPositiveNativeEvidenceSource() {
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, NOT_STARTED, positive()));
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED,
                Map.of("effect_observed", false, "evidence_source", "NATIVE_TEST_EFFECT")));
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED,
                Map.of("effect_observed", true, "evidence_source", "LOG_MESSAGE")));
        assertDoesNotThrow(() -> result(SUCCEEDED, STARTED, positive()));
        assertDoesNotThrow(() -> result(INDETERMINATE, STARTED, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> result(CANCELLED, STARTED, Map.of()));
    }

    @Test void aDispatchedMoveMayBeCancelledOnlyAfterNativeMovementHasActuallyStopped() {
        Map<String, Object> stopped = new LinkedHashMap<>(Map.of("movement_stopped", true,
                "completed_duration", false, "evidence_source", "NATIVE_MOVEMENT_PACKETS"));
        NativeBasicResult cancelled = assertDoesNotThrow(() -> new NativeBasicResult("move", CANCELLED,
                STARTED, 41, "source-epoch", true, false, "movement_cancelled", stopped));
        assertEquals(CANCELLED, cancelled.outcome());
        assertEquals(STARTED, cancelled.dispatch());
        assertEquals(Boolean.TRUE, cancelled.facts().get("movement_stopped"));
        assertEquals(Boolean.FALSE, cancelled.facts().get("completed_duration"));

        assertThrows(IllegalArgumentException.class, () -> new NativeBasicResult("pickup", CANCELLED,
                STARTED, 41, "source-epoch", true, false, null, stopped));
        for (Map<String, Object> unconfirmed : List.of(
                Map.<String, Object>of("movement_stopped", false, "completed_duration", false,
                        "evidence_source", "NATIVE_MOVEMENT_PACKETS"),
                Map.<String, Object>of("movement_stopped", true, "completed_duration", true,
                        "evidence_source", "NATIVE_MOVEMENT_PACKETS"),
                Map.<String, Object>of("movement_stopped", true, "completed_duration", false,
                        "evidence_source", "TIMER_CANCELLED"),
                Map.<String, Object>of("completed_duration", false, "evidence_source", "NATIVE_MOVEMENT_PACKETS"),
                Map.<String, Object>of("movement_stopped", true, "evidence_source", "NATIVE_MOVEMENT_PACKETS"))) {
            assertThrows(IllegalArgumentException.class, () -> new NativeBasicResult("move", CANCELLED,
                    STARTED, 41, "source-epoch", true, false, null, unconfirmed));
        }
    }

    @Test void textCollectionDepthAndTotalFactBudgetsAreExplicitlyBounded() {
        Map<String, Object> text = positive();
        text.put("summary", "x".repeat(1024));
        assertDoesNotThrow(() -> result(SUCCEEDED, STARTED, text));
        text.put("summary", "x".repeat(1025));
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED, text));

        Map<String, Object> collection = positive();
        collection.put("items", java.util.Collections.nCopies(512, 1));
        assertDoesNotThrow(() -> result(SUCCEEDED, STARTED, collection));
        collection.put("items", java.util.Collections.nCopies(513, 1));
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED, collection));

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < 11; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("child", child);
            cursor = child;
        }
        cursor.put("value", 1);
        assertThrows(IllegalArgumentException.class, () -> result(FAILED, NOT_STARTED, deep));

        Map<String, Object> many = positive();
        for (int i = 0; i < 17; i++) many.put("batch_" + i, java.util.Collections.nCopies(512, 1));
        assertThrows(IllegalArgumentException.class, () -> result(SUCCEEDED, STARTED, many));
    }

    private static NativeBasicResult result(NativeActionResult.Outcome outcome, NativeActionResult.Dispatch dispatch,
                                            Map<String, Object> facts) {
        return new NativeBasicResult("pickup", outcome, dispatch, 41, "source-epoch", false, false, null, facts);
    }

    private static Map<String, Object> positive() {
        return new LinkedHashMap<>(Map.of("effect_observed", true, "evidence_source", "NATIVE_TEST_EFFECT"));
    }
}
