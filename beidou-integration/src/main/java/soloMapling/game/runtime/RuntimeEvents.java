package soloMapling.game.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded, non-consuming runtime facts; its cursor is unrelated to packet or operation cursors. */
public final class RuntimeEvents {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}");
    private final String runtimeId;
    private final int capacity;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;

    public RuntimeEvents(String runtimeId, int capacity) {
        requireId(runtimeId);
        if (capacity < 1) throw new IllegalArgumentException("invalid_runtime_event_capacity");
        this.runtimeId = runtimeId;
        this.capacity = capacity;
    }

    public enum Kind {
        CREATED, BOUND, MODULE_MOUNTED, MODULE_ACTIVE, MODULE_STOPPING, MODULE_STOPPED,
        MODULE_UNLOADED, COMMAND_ACCEPTED, COMMAND_SETTLED, ACTION_RESULT, CLOSING, CLOSED, FAILED
    }

    public record Event(long sequence, Kind kind, String runtimeId, String moduleId,
                        long moduleGeneration, Long workId, String reason, String outcome) {
        public Event {
            if (sequence < 1) throw new IllegalArgumentException("invalid_runtime_event_sequence");
            Objects.requireNonNull(kind, "kind");
            requireId(runtimeId);
            if (moduleId != null) requireId(moduleId);
            if (moduleGeneration < 0 || moduleId == null && moduleGeneration != 0) {
                throw new IllegalArgumentException("invalid_runtime_event_module_generation");
            }
            if (workId != null && workId < 1) throw new IllegalArgumentException("invalid_runtime_event_work_id");
            requireLabel(reason);
            requireLabel(outcome);
        }
    }

    public record Window(List<Event> events, long nextCursor, boolean cursorExpired, boolean truncated) {
        public Window { events = List.copyOf(events); }
    }

    /** Records each fact, including repeated facts from the same module; this is not a state machine. */
    public synchronized Event append(Kind kind, String moduleId, long moduleGeneration, Long workId,
                                     String fixedReason, String outcome) {
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("runtime_event_sequence_exhausted");
        Event event = new Event(sequence + 1, kind, runtimeId, moduleId, moduleGeneration, workId,
                fixedReason, outcome);
        events.addLast(event);
        sequence = event.sequence();
        if (events.size() > capacity) events.removeFirst();
        return event;
    }

    /** Returns retained events after since; an expired cursor resumes at the oldest retained fact. */
    public synchronized Window read(long since, int limit) {
        if (since < 0 || since > sequence) throw new IllegalArgumentException("invalid_runtime_event_cursor");
        if (limit < 1) throw new IllegalArgumentException("invalid_runtime_event_limit");
        long oldest = events.isEmpty() ? 1 : events.getFirst().sequence();
        List<Event> page = new ArrayList<>(Math.min(limit, events.size()));
        for (Event event : events) {
            if (event.sequence() > since) {
                page.add(event);
                if (page.size() == limit) break;
            }
        }
        long next = page.isEmpty() ? since : page.getLast().sequence();
        return new Window(page, next, since < oldest - 1, next < sequence);
    }

    private static void requireId(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid_runtime_event_id");
        }
    }

    private static void requireLabel(String value) {
        if (value != null && !LABEL.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid_runtime_event_label");
        }
    }
}
