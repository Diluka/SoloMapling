package soloMapling.game.action;

import java.util.LinkedHashMap;
import java.util.Map;

/** Native role facts and the actual event/standard route of one normal revive request. */
public record ReviveResult(Outcome outcome, int characterId, String epoch, State before, State after,
                           Dispatch dispatch, Event event, Transition transition, boolean sceneChanged, boolean cancelRequested,
                           String reason) {
    public enum Outcome { REVIVED, ALREADY_ALIVE, NOT_REVIVED, UNKNOWN, CANCELLED }
    public enum EventRoute { NOT_PRESENT, STANDARD_ALLOWED, TAKEN_OVER, UNCONFIRMED }
    public record State(boolean alive, int hp, int mp, int mapId, int x, int y, boolean changingMaps) {
        public Map<String, Object> toMap() {
            return Map.of("alive", alive, "hp", hp, "mp", mp, "map_id", mapId, "x", x, "y", y,
                    "changing_maps", changingMaps);
        }
    }
    public record Dispatch(boolean started, boolean returned, boolean handlerError) { }
    public record Transition(boolean observed, boolean acknowledgementStarted,
                             boolean acknowledgementReturned, boolean acknowledgementError, boolean complete) { }
    public record Event(EventRoute route, boolean consulted, boolean decisionReturned, boolean allowsStandardPath,
                        boolean standardRespawnStarted, boolean standardRespawnReturned,
                        boolean respawnStarted, boolean respawnReturned, Integer requestedReturnMap) { }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        result.put("character_id", characterId);
        result.put("epoch", epoch);
        result.put("before", before == null ? null : before.toMap());
        result.put("after", after == null ? null : after.toMap());
        result.put("dispatch_started", dispatch.started());
        result.put("handler_returned", dispatch.returned());
        result.put("handler_error", dispatch.handlerError());
        result.put("event_route", event.route().name());
        result.put("event_consulted", event.consulted());
        result.put("event_decision", event.decisionReturned() ? event.allowsStandardPath() : null);
        result.put("standard_respawn_started", event.standardRespawnStarted());
        result.put("standard_respawn_returned", event.standardRespawnReturned());
        result.put("respawn_started", event.respawnStarted());
        result.put("respawn_returned", event.respawnReturned());
        result.put("requested_return_map", event.requestedReturnMap());
        result.put("event_work_completion", event.consulted() ? "OWNER_MANAGED_UNOBSERVED" : "NOT_APPLICABLE");
        result.put("transition_observed", transition.observed());
        result.put("acknowledgement_started", transition.acknowledgementStarted());
        result.put("acknowledgement_returned", transition.acknowledgementReturned());
        result.put("acknowledgement_error", transition.acknowledgementError());
        result.put("transition_complete", transition.complete());
        result.put("scene_changed", sceneChanged);
        result.put("cancel_requested", cancelRequested);
        if (reason != null) result.put("reason", reason);
        return result;
    }
}
