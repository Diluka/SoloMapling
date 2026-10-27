package org.gms.soloacceptance.actor;

import org.gms.scripting.npc.NPCConversationManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Current NPC metadata only. Native getters must be evaluated outside this monitor. */
final class NativeDialogueTracker {
    private long epoch;
    private State current = State.empty();

    synchronized long epoch() { return epoch; }

    synchronized State begin(long expectedEpoch, Map<String, Object> event) {
        long sequence = (long) event.get("sequence");
        if (expectedEpoch != epoch || current.sequence != null && sequence <= current.sequence) return null;
        boolean decoded = "npc_dialogue".equals(event.get("event_type"))
                && "decoded".equals(event.get("decode_status"));
        Integer npc = decoded ? (Integer) event.get("npc_template_id") : null;
        Integer type = decoded ? (Integer) event.get("message_type") : null;
        boolean supported = decoded && Integer.valueOf(9900001).equals(npc)
                && Integer.valueOf(4).equals(type) && Integer.valueOf(4).equals(event.get("speaker_type"))
                && Integer.valueOf(0).equals(event.get("speaker"));
        current = new State(sequence, npc, type, supported, null);
        return current;
    }

    synchronized void capture(State pending, NPCConversationManager cm) {
        if (current == pending) current = new State(pending.sequence, pending.npc, pending.type, pending.supported, cm);
    }

    synchronized State current() { return current; }
    synchronized boolean isCurrent(State state) { return current == state; }

    synchronized void invalidate() {
        epoch++;
        current = State.empty();
    }

    static final class State {
        final Long sequence;
        final Integer npc;
        final Integer type;
        final boolean supported;
        final NPCConversationManager cm;

        State(Long sequence, Integer npc, Integer type, boolean supported, NPCConversationManager cm) {
            this.sequence = sequence;
            this.npc = npc;
            this.type = type;
            this.supported = supported;
            this.cm = cm;
        }

        static State empty() { return new State(null, null, null, false, null); }
        static State unsupported(long sequence) { return new State(sequence, null, null, false, null); }

        Map<String, Object> view(boolean active) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("dialogue_version", sequence);
            view.put("active", active);
            view.put("supported", supported);
            view.put("entry_type", supported ? "native_ui_center" : "unsupported");
            view.put("npc_template_id", npc);
            view.put("message_type", type);
            view.put("packet_sequence", sequence);
            return Collections.unmodifiableMap(view);
        }
    }
}
