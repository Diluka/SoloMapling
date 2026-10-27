package org.gms.soloacceptance.actor;

import soloMapling.game.action.NativeActionResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A scalar-only projection of the shared result onto MCP's existing chat JSON fields. */
final class AcceptanceChatResults {
    private AcceptanceChatResults() { }

    static String errorReason(NativeActionResult outcome) {
        String reason = outcome.fixedReason();
        if (reason == null) return null;
        return switch (reason) {
            case "native_action_queue_rejected" -> "actor_queue_full";
            case "native_packet_state_rejected" -> "actor_native_packet_state_rejected";
            case "native_world_offline" -> "actor_world_offline";
            case "native_world_changed" -> "stale_actor_session";
            case "native_scene_changed" -> "actor_context_mismatch";
            case "native_context_unavailable" -> "actor_observation_unavailable";
            case "native_character_changed" -> "actor_session_stale";
            default -> reason;
        };
    }

    static Map<String, Object> resultFields(NativeActionResult outcome) {
        boolean matched = outcome.evidence().matched();
        String observed = matched ? "native_chat_broadcast"
                : outcome.outcome() == NativeActionResult.Outcome.INDETERMINATE
                    ? "native_chat_outcome_unknown" : "native_chat_not_broadcast";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observed_outcome", observed);
        result.put("native_dispatch_started", outcome.dispatch() == NativeActionResult.Dispatch.STARTED);
        result.put("echo_matched", matched);
        result.put("sender_character_id", outcome.senderCharacterId());
        result.put("echo_sequence", outcome.evidence().matchedSequence());
        result.put("packet_cursor_before", outcome.evidence().cursorBefore());
        result.put("packet_cursor_after", outcome.evidence().cursorAfter());
        if (outcome.handlerError()) result.put("native_handler_error", true);
        return Collections.unmodifiableMap(result);
    }
}
