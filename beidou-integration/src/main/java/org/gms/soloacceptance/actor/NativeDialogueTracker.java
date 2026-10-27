package org.gms.soloacceptance.actor;

import org.gms.scripting.npc.NPCConversationManager;
import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Current metadata only. This monitor never calls a native getter or takes the packet buffer lock. */
final class NativeDialogueTracker {
    static final int MAXIMUM_OFFERS = 128;
    private final PacketEventBuffer packets;
    private long epoch;
    private State current = State.empty();
    private DispatchScope dispatch;

    NativeDialogueTracker(PacketEventBuffer packets) { this.packets = packets; }
    synchronized long epoch() { return epoch; }
    synchronized State current() { return current; }
    synchronized boolean isCurrent(State state) { return current == state; }

    synchronized Pending begin(long expectedEpoch, PacketEventBuffer.Receipt receipt) {
        Map<String, Object> event = receipt.event();
        long sequence = (long) event.get("sequence");
        if (expectedEpoch != epoch || current.sequence != null && sequence <= current.sequence) return null;
        boolean decoded = "npc_dialogue".equals(event.get("event_type"))
                && "decoded".equals(event.get("decode_status"));
        Integer npc = decoded ? (Integer) event.get("npc_template_id") : null;
        Integer type = decoded ? (Integer) event.get("message_type") : null;
        boolean candidate = decoded && Integer.valueOf(9900001).equals(npc)
                && (Integer.valueOf(4).equals(type) || Integer.valueOf(12).equals(type))
                && Integer.valueOf(4).equals(event.get("speaker_type"))
                && Integer.valueOf(0).equals(event.get("speaker"));
        Offers offers = candidate ? Offers.parse(event) : Offers.incomplete();
        current = new State(sequence, npc, type, "unsupported", null, offers, false);
        DispatchScope scope = dispatch;
        if (scope != null) scope.closedPacketSequence = null;
        boolean scopeValid = scope != null && scope.active && !scope.poisoned && scope.epoch == epoch
                && scope.thread == Thread.currentThread();
        boolean terminal = scopeValid && scope.decline && receipt.nativeNpcClose();
        if ((!candidate && !terminal) || !scopeValid) {
            if (scope != null) scope.poisoned = true;
            return new Pending(current, null, false);
        }
        return new Pending(current, scope, terminal);
    }

    synchronized void capture(Pending pending, NPCConversationManager cm, int npc, String script,
                              boolean itemScript, boolean noQuest) {
        DispatchScope scope = pending.scope;
        if (current != pending.state || scope == null || scope != dispatch || !scope.active || scope.poisoned
                || scope.epoch != epoch || !Long.valueOf(packets.latestNpcSequence()).equals(current.sequence)) return;
        boolean identity = cm != null && noQuest && npc == 9900001 && !itemScript;
        if (pending.terminal) {
            if (identity && cm == scope.previous && "新人福利".equals(script)) scope.closedPacketSequence = current.sequence;
            scope.poisoned = true;
            return;
        }
        boolean matches = identity && switch (scope.expectedPath) {
            case "center" -> current.type == 4 && script == null;
            case "welfare" -> current.type == 12 && "新人福利".equals(script) && cm != scope.previous;
            case "transport_category" -> current.type == 4 && "万能传送".equals(script) && cm != scope.previous;
            case "transport_town" -> current.type == 4 && "万能传送".equals(script) && cm == scope.previous;
            default -> false;
        };
        if (scope.target != null && cm != scope.target) matches = false;
        if (!matches) {
            scope.poisoned = true;
            return;
        }
        scope.target = cm;
        current = new State(current.sequence, current.npc, current.type, scope.expectedPath, cm,
                current.offers, false);
    }

    synchronized DispatchScope openCenter() {
        requireNoDispatch();
        epoch++;
        current = State.empty();
        dispatch = new DispatchScope(epoch, "center", null, false);
        return dispatch;
    }

    synchronized DispatchScope consume(State state, String expectedPath, boolean decline) {
        if (current != state || state.consumed || state.sequence == null
                || state.sequence != packets.latestNpcSequence()) throw stale();
        requireNoDispatch();
        current = new State(state.sequence, state.npc, state.type, state.path, state.cm, state.offers, true);
        dispatch = new DispatchScope(epoch, expectedPath, state.cm, decline);
        return dispatch;
    }

    synchronized void finish(DispatchScope scope, boolean failed) {
        scope.active = false;
        if (dispatch != scope) return;
        dispatch = null;
        if (failed) {
            epoch++;
            current = State.empty();
        }
    }

    synchronized void invalidate() {
        epoch++;
        current = State.empty();
        if (dispatch != null) {
            dispatch.poisoned = true;
            dispatch.closedPacketSequence = null;
        }
    }

    private void requireNoDispatch() {
        if (dispatch != null && dispatch.active) {
            throw new ObservationException(HttpStatus.CONFLICT, "dialogue_dispatch_active");
        }
    }

    private static ObservationException stale() { return new ObservationException(HttpStatus.GONE, "stale_dialogue"); }
    record Pending(State state, DispatchScope scope, boolean terminal) { }

    static final class DispatchScope {
        final long epoch;
        final Thread thread = Thread.currentThread();
        final String expectedPath;
        final NPCConversationManager previous;
        final boolean decline;
        NPCConversationManager target;
        Long closedPacketSequence;
        boolean active = true;
        boolean poisoned;
        DispatchScope(long epoch, String expectedPath, NPCConversationManager previous, boolean decline) {
            this.epoch = epoch; this.expectedPath = expectedPath; this.previous = previous; this.decline = decline;
        }
    }

    static final class State {
        final Long sequence;
        final Integer npc;
        final Integer type;
        final String path;
        final NPCConversationManager cm;
        final Offers offers;
        final boolean consumed;
        State(Long sequence, Integer npc, Integer type, String path, NPCConversationManager cm,
              Offers offers, boolean consumed) {
            this.sequence = sequence; this.npc = npc; this.type = type; this.path = path; this.cm = cm;
            this.offers = offers; this.consumed = consumed;
        }
        boolean supported() { return !"unsupported".equals(path); }
        static State empty() { return new State(null, null, null, "unsupported", null, Offers.incomplete(), false); }
        static State unsupported(long sequence) {
            return new State(sequence, null, null, "unsupported", null, Offers.incomplete(), false);
        }

        Map<String, Object> view(boolean active, boolean routeValid) {
            List<Integer> allowed = List.of();
            List<String> kinds = List.of();
            if (active && routeValid && !consumed) {
                if (offers.complete && type == 4) {
                    List<Integer> reviewed = "center".equals(path) ? List.of(0, 71)
                            : "transport_category".equals(path) ? List.of(2) : List.of();
                    allowed = reviewed.stream().filter(offers.ids::contains).toList();
                    if (!allowed.isEmpty()) kinds = List.of("select");
                } else if (type == 12 && "welfare".equals(path)) kinds = List.of("decline");
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("dialogue_version", sequence);
            view.put("active", active);
            view.put("supported", supported());
            view.put("entry_type", supported() ? "native_ui_center" : "unsupported");
            view.put("npc_template_id", npc);
            view.put("message_type", type);
            view.put("packet_sequence", sequence);
            view.put("path", path);
            view.put("reply_options", Map.of("kinds", kinds,
                    "offered_selections", supported() ? offers.ids : List.of(),
                    "allowed_selections", allowed, "complete", supported() && offers.complete,
                    "consumed", consumed));
            return Collections.unmodifiableMap(view);
        }
    }

    record Offers(List<Integer> ids, boolean complete) {
        static Offers incomplete() { return new Offers(List.of(), false); }
        static Offers parse(Map<String, Object> event) {
            if (!Boolean.FALSE.equals(event.get("text_truncated"))) return incomplete();
            if (Integer.valueOf(12).equals(event.get("message_type"))) return new Offers(List.of(), true);
            if (!(event.get("text") instanceof String text)) return incomplete();
            List<Integer> ids = new ArrayList<>();
            int position = 0;
            while (position < text.length()) {
                int open = text.indexOf("#L", position);
                int strayClose = text.indexOf("#l", position);
                if (strayClose >= 0 && (open < 0 || strayClose < open)) return incomplete();
                if (open < 0) break;
                int separator = text.indexOf('#', open + 2);
                if (separator < 0 || separator == open + 2) return incomplete();
                long id = 0;
                for (int index = open + 2; index < separator; index++) {
                    char digit = text.charAt(index);
                    if (digit < '0' || digit > '9') return incomplete();
                    id = id * 10 + digit - '0';
                    if (id > Integer.MAX_VALUE) return incomplete();
                }
                int close = text.indexOf("#l", separator + 1);
                int nested = text.indexOf("#L", separator + 1);
                if (close < 0 || nested >= 0 && nested < close || ids.contains((int) id)
                        || ids.size() == MAXIMUM_OFFERS) return incomplete();
                ids.add((int) id);
                position = close + 2;
            }
            return new Offers(List.copyOf(ids), true);
        }
    }
}
