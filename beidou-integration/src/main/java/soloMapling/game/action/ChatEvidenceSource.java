package soloMapling.game.action;

import soloMapling.game.context.GameContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A finite, read-only evidence adapter. Identity policy belongs to the consumer. */
public interface ChatEvidenceSource {
    Probe begin(GameContext.CharacterRef actor, NativeChatAction action);

    interface Probe extends AutoCloseable {
        ChatEvidence observe();
        @Override void close();
    }

    /** Existing decoded packet windows, never raw packets or a handler execution hook. */
    interface PacketFeed {
        void requireCurrent();
        long latestCursor();
        Map<String, Object> window(long sinceCursor, int limit);
        /** Explicit guarantee that this receiver retained reception throughout the checkpoint interval. */
        default boolean completeReception() { return false; }
    }

    static ChatEvidenceSource none() {
        return (actor, action) -> {
            String epoch = actor.epoch().value();
            return new Probe() {
                public ChatEvidence observe() { return ChatEvidence.none(epoch); }
                public void close() { }
            };
        };
    }

    static ChatEvidenceSource packets(ChatEvidence.Source source, String sourceId, PacketFeed feed) {
        Objects.requireNonNull(feed);
        if (source == null || source == ChatEvidence.Source.NONE) throw new IllegalArgumentException("invalid_packet_source");
        // Validate stamps without retaining an artificial native context.
        new ChatEvidence(source, sourceId, "validation", null, null, null, ChatEvidence.Completeness.UNAVAILABLE, false);
        return (actor, action) -> {
            long before;
            try {
                feed.requireCurrent();
                before = feed.latestCursor();
                if (before < 0) throw new IllegalStateException("invalid_packet_cursor");
            } catch (RuntimeException | Error unavailable) {
                // An optional observer is not the actor's admission authority or transport.
                if (source == ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET) throw unavailable;
                String epoch = actor.epoch().value();
                return new Probe() {
                    public ChatEvidence observe() { return new ChatEvidence(source, sourceId, epoch, null, null, null,
                            ChatEvidence.Completeness.UNAVAILABLE, false); }
                    public void close() { }
                };
            }
            return new PacketProbe(source, sourceId, actor.epoch().value(), actor.character().getId(),
                    action.text(), feed, before);
        };
    }

    /** Short-lived matcher; close clears the feed and text even if the public handle is retained. */
    final class PacketProbe implements Probe {
        private final ChatEvidence.Source source;
        private final String sourceId;
        private final String epoch;
        private final int sender;
        private final long before;
        private String text;
        private PacketFeed feed;

        private PacketProbe(ChatEvidence.Source source, String sourceId, String epoch, int sender,
                            String text, PacketFeed feed, long before) {
            this.source = source; this.sourceId = sourceId; this.epoch = epoch;
            this.sender = sender; this.text = text; this.feed = feed; this.before = before;
        }

        @Override public ChatEvidence observe() {
            PacketFeed current = feed;
            if (current == null) throw new IllegalStateException("chat_probe_closed");
            Long after = null;
            Long match = null;
            boolean complete = false;
            boolean uncertain = false;
            boolean readFailed = false;
            try {
                current.requireCurrent();
                Map<String, Object> window = current.window(before, 500);
                after = number(window.get("latest_cursor"));
                Object value = window.get("items");
                if (!(value instanceof List<?> events)) throw new IllegalStateException("invalid_packet_window");
                for (Object item : events) {
                    if (!(item instanceof Map<?, ?> event)) throw new IllegalStateException("invalid_packet_event");
                    Long sequence = number(event.get("sequence"));
                    if (sequence == null) throw new IllegalStateException("invalid_packet_sequence");
                    if (sequence <= before || after == null || sequence > after) continue;
                    if ("chat_text".equals(event.get("event_type")) && "decoded".equals(event.get("decode_status"))
                            && Objects.equals(number(event.get("sender_character_id")), (long) sender)
                            && Objects.equals(event.get("text"), text)
                            && Objects.equals(number(event.get("show")), 0L)
                            && Boolean.FALSE.equals(event.get("text_truncated")) && match == null) match = sequence;
                    if ("malformed".equals(event.get("decode_status"))
                            && (event.get("opcode") == null || Objects.equals(number(event.get("opcode")), 0xA2L))) uncertain = true;
                }
                complete = after != null && after >= before
                        && Boolean.FALSE.equals(window.get("cursor_expired"))
                        && Boolean.FALSE.equals(window.get("truncated"));
                // A readable historical window does not prove the receiver was still accepting packets.
                // Inspect its exact positive facts first; lost reception only removes negative assurance.
                if (complete) {
                    complete = false;
                    complete = current.completeReception();
                }
            } catch (Throwable ignored) {
                readFailed = true; // An already matched native packet remains a positive fact.
            }
            if (after == null) {
                try { long value = current.latestCursor(); if (value >= 0) after = value; }
                catch (Throwable ignored) { /* Absence of evidence is explicit. */ }
            }
            ChatEvidence.Completeness completeness = complete ? ChatEvidence.Completeness.COMPLETE
                    : readFailed && match == null ? ChatEvidence.Completeness.UNAVAILABLE : ChatEvidence.Completeness.PARTIAL;
            return new ChatEvidence(source, sourceId, epoch, before, after, match, completeness, uncertain);
        }

        @Override public void close() { feed = null; text = null; }

        private static Long number(Object value) {
            return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    ? ((Number) value).longValue() : null;
        }
    }
}
