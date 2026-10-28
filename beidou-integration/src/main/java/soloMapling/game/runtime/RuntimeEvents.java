package soloMapling.game.runtime;

import java.util.ArrayDeque;
import java.util.List;

/** The host's latest 64 safe facts; sequence numbers are independent of packet and operation cursors. */
public final class RuntimeEvents {
    private final String runtimeId;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;

    RuntimeEvents(String runtimeId) { this.runtimeId = runtimeId; }

    public enum Kind {
        CREATED, BOUND, MODULE_MOUNTED, MODULE_ACTIVE, MODULE_STOPPING, MODULE_STOPPED,
        MODULE_UNLOADED, COMMAND_ACCEPTED, COMMAND_SETTLED, ACTION_RESULT, CLOSING, CLOSED
    }

    public record Event(long sequence, Kind kind, String runtimeId, String moduleId,
                        long moduleGeneration, Long workId, String reason, String outcome) { }

    public record Window(List<Event> events, long nextCursor, boolean cursorExpired, boolean truncated) {
        public Window { events = List.copyOf(events); }
    }

    synchronized void append(Kind kind, String moduleId, long moduleGeneration, Long workId,
                             String fixedReason, String outcome) {
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("runtime_event_sequence_exhausted");
        events.addLast(new Event(++sequence, kind, runtimeId, moduleId, moduleGeneration, workId,
                fixedReason, outcome));
        if (events.size() > 64) events.removeFirst();
    }

    synchronized Window snapshot() {
        long oldest = events.isEmpty() ? 1 : events.getFirst().sequence();
        return new Window(List.copyOf(events), sequence, oldest > 1, false);
    }
}
