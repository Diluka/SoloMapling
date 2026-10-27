package org.gms.soloacceptance.actor;

import org.gms.constants.string.CharsetConstants;
import org.gms.net.packet.Packet;
import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single bounded event history; the legacy metadata view is projected only when requested. */
final class PacketEventBuffer {
    static final int CAPACITY = 256;
    private final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();
    private long sequence;
    private long dropped;
    private long malformed;
    private volatile boolean closed;
    private volatile long latestNpcSequence;

    Map<String, Object> accept(Packet packet, int language) {
        if (closed) return null;
        String receivedAt = Instant.now().toString();
        byte[] bytes = null;
        String failure = null;
        if (packet == null) {
            failure = "packet_missing";
        } else {
            try {
                bytes = packet.getBytes();
                if (bytes == null) failure = "packet_bytes_missing";
                else if (bytes.length < 2) failure = "packet_header_missing";
            } catch (RuntimeException ignored) {
                failure = "packet_read_failed";
            }
        }
        Integer opcode = bytes == null || bytes.length < 2 ? null
                : (bytes[0] & 0xff) | (bytes[1] & 0xff) << 8;
        Map<String, Object> fields;
        if (failure != null) {
            fields = PacketEventDecoder.summary("malformed", failure);
        } else {
            try {
                fields = PacketEventDecoder.decode(bytes, CharsetConstants.getCharset(language));
            } catch (RuntimeException ignored) {
                fields = PacketEventDecoder.summary("malformed", "packet_decode_failed");
            }
        }
        synchronized (events) {
            if (closed) return null;
            if (events.size() == CAPACITY) {
                events.removeFirst();
                dropped++;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("sequence", ++sequence);
            event.put("received_at", receivedAt);
            event.put("opcode", opcode);
            event.put("length", bytes == null ? null : bytes.length);
            event.putAll(fields);
            if ("malformed".equals(fields.get("decode_status"))) malformed++;
            Map<String, Object> immutable = Collections.unmodifiableMap(event);
            events.addLast(immutable);
            if (opcode != null && opcode == PacketEventDecoder.NPC_TALK) latestNpcSequence = sequence;
            return immutable;
        }
    }

    Map<String, Object> window(long sinceCursor, int limit) {
        if (sinceCursor < 0 || limit < 1 || limit > 500) {
            throw new ObservationException(HttpStatus.BAD_REQUEST, "invalid_packet_window");
        }
        synchronized (events) {
            if (sinceCursor > sequence) {
                throw new ObservationException(HttpStatus.BAD_REQUEST, "packet_cursor_ahead");
            }
            long oldest = events.isEmpty() ? 0L : (long) events.getFirst().get("sequence");
            List<Map<String, Object>> items = new ArrayList<>();
            boolean truncated = false;
            for (Map<String, Object> event : events) {
                if ((long) event.get("sequence") <= sinceCursor) continue;
                if (items.size() == limit) {
                    truncated = true;
                    break;
                }
                items.add(event);
            }
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("items", List.copyOf(items));
            window.put("next_cursor", items.isEmpty() ? sinceCursor : items.getLast().get("sequence"));
            window.put("oldest_cursor", oldest);
            window.put("latest_cursor", sequence);
            window.put("cursor_expired", oldest > 0 && sinceCursor < oldest - 1);
            window.put("truncated", truncated);
            window.put("capacity", CAPACITY);
            window.put("dropped_events_total", dropped);
            window.put("malformed_events_total", malformed);
            return Collections.unmodifiableMap(window);
        }
    }

    List<Map<String, Object>> summaries() {
        synchronized (events) {
            return events.stream().map(event -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("sequence", event.get("sequence"));
                summary.put("opcode", event.get("opcode"));
                summary.put("length", event.get("length"));
                if ("malformed".equals(event.get("decode_status"))) {
                    summary.put("malformed", true);
                    summary.put("reason", event.get("reason"));
                }
                return Collections.unmodifiableMap(summary);
            }).toList();
        }
    }

    long dropped() { synchronized (events) { return dropped; } }
    long malformed() { synchronized (events) { return malformed; } }
    long latest() { synchronized (events) { return sequence; } }
    long latestNpcSequence() { return latestNpcSequence; }
    boolean closed() { return closed; }
    void close() { synchronized (events) { closed = true; } }
}
