package soloMapling.game.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.game.runtime.RuntimeEvents.Kind.*;

class RuntimeEventsTest {
    @Test
    void pagesCanBeRereadWithoutConsumingFactsOrSharingAnotherRuntimesCursor() {
        RuntimeEvents events = new RuntimeEvents("runtime-1", 8);
        var created = events.append(CREATED, null, 0, null, null, null);
        var mounted = events.append(MODULE_MOUNTED, "mcp", 1, null, null, null);
        var accepted = events.append(COMMAND_ACCEPTED, "mcp", 1, 1L, null, null);
        var result = events.append(ACTION_RESULT, "mcp", 1, 1L, null, "SUCCEEDED");

        var first = events.read(0, 2);
        assertEquals(List.of(created, mounted), first.events());
        assertEquals(2, first.nextCursor());
        assertFalse(first.cursorExpired());
        assertTrue(first.truncated());
        assertEquals(first, events.read(0, 2));
        assertThrows(UnsupportedOperationException.class, () -> first.events().clear());

        var second = events.read(first.nextCursor(), 2);
        assertEquals(List.of(accepted, result), second.events());
        assertEquals(4, second.nextCursor());
        assertFalse(second.truncated());
        assertEquals(new RuntimeEvents.Window(List.of(), 4, false, false), events.read(4, 2));
        assertEquals(1, new RuntimeEvents("runtime-2", 8)
                .append(CREATED, null, 0, null, null, null).sequence());
    }

    @Test
    void evictionKeepsTheWindowBoundedAndDistinguishesExpiryFromPagination() {
        RuntimeEvents events = new RuntimeEvents("runtime-1", 3);
        for (int i = 0; i < 5; i++) events.append(MODULE_ACTIVE, "mcp", 1, null, null, null);

        var expired = events.read(0, 2);
        assertEquals(List.of(3L, 4L), sequences(expired));
        assertTrue(expired.cursorExpired());
        assertTrue(expired.truncated());
        assertEquals(4, expired.nextCursor());

        var resumed = events.read(expired.nextCursor(), 10);
        assertEquals(List.of(5L), sequences(resumed));
        assertFalse(resumed.cursorExpired());
        assertFalse(resumed.truncated());
        var boundary = events.read(2, Integer.MAX_VALUE);
        assertEquals(List.of(3L, 4L, 5L), sequences(boundary));
        assertFalse(boundary.cursorExpired());
        assertFalse(boundary.truncated());
    }

    @Test
    void repeatedModuleFactsRemainDistinctAndAnEarlierWindowIsAnImmutableSnapshot() {
        RuntimeEvents events = new RuntimeEvents("runtime-1", 2);
        var first = events.append(MODULE_ACTIVE, "mcp", 1, null, "ready", "ACTIVE");
        var snapshot = events.read(0, 10);
        var second = events.append(MODULE_ACTIVE, "mcp", 1, null, "ready", "ACTIVE");
        events.append(MODULE_ACTIVE, "mcp", 2, null, "ready", "ACTIVE");

        assertEquals(List.of(first), snapshot.events());
        assertEquals(2, second.sequence());
        assertEquals(List.of(2L, 3L), sequences(events.read(0, 10)));
    }

    @Test
    void invalidInputCannotInsertPayloadTextOrAdvanceTheCursor() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeEvents("", 2));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeEvents("runtime\nsecret", 2));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeEvents("x".repeat(129), 2));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeEvents("runtime-1", 0));
        RuntimeEvents events = new RuntimeEvents("runtime-1", 2);
        assertEquals(new RuntimeEvents.Window(List.of(), 0, false, false), events.read(0, 1));
        assertThrows(IllegalArgumentException.class, () -> events.read(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> events.read(1, 1));
        assertThrows(IllegalArgumentException.class, () -> events.read(0, 0));
        assertThrows(NullPointerException.class, () -> events.append(null, null, 0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> events.append(MODULE_ACTIVE, "mcp text", 1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> events.append(CREATED, null, 1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> events.append(MODULE_ACTIVE, "mcp", -1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> events.append(COMMAND_ACCEPTED, "mcp", 1, 0L, null, null));
        assertThrows(IllegalArgumentException.class, () -> events.append(FAILED, "mcp", 1, null, "Exception: secret\n", null));
        assertThrows(IllegalArgumentException.class, () -> events.append(ACTION_RESULT, "mcp", 1, null, null, "x".repeat(97)));
        assertEquals(1, events.append(CREATED, null, 0, null, null, null).sequence());
        assertThrows(IllegalArgumentException.class, () -> events.read(2, 1));
    }

    @Test
    void concurrentWritersProduceOneContiguousSequenceAndRespectRetention() throws Exception {
        int writers = 8;
        int perWriter = 100;
        RuntimeEvents events = new RuntimeEvents("runtime-concurrent", 64);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(writers)) {
            for (int writer = 0; writer < writers; writer++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    List<Long> sequences = new ArrayList<>();
                    for (int i = 0; i < perWriter; i++) {
                        sequences.add(events.append(MODULE_ACTIVE, "mcp", 1, null, null, null).sequence());
                    }
                    return sequences;
                }));
            }
            start.countDown();
            List<Long> all = new ArrayList<>();
            for (var result : results) all.addAll(result.get(10, TimeUnit.SECONDS));
            assertEquals(LongStream.rangeClosed(1, writers * perWriter).boxed().toList(), all.stream().sorted().toList());
        }
        var retained = events.read(0, Integer.MAX_VALUE);
        assertEquals(LongStream.rangeClosed(737, 800).boxed().toList(), sequences(retained));
        assertTrue(retained.cursorExpired());
        assertFalse(retained.truncated());
        assertEquals(800, retained.nextCursor());
    }

    private static List<Long> sequences(RuntimeEvents.Window window) {
        return window.events().stream().map(RuntimeEvents.Event::sequence).toList();
    }
}
