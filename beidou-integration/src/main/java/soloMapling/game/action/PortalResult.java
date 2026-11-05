package soloMapling.game.action;

import java.util.LinkedHashMap;
import java.util.Map;

/** Value-only facts of one native portal click and its map-entry completion. */
public record PortalResult(Outcome outcome, int characterId, String epoch, int requestedPortalId,
                           Endpoint exit, Endpoint entry, State before, State after,
                           Dispatch dispatch, Transition transition, Boolean sceneChanged,
                           boolean cancelRequested, String reason) {
    public enum Outcome { ENTERED, NOT_ENTERED, UNSUPPORTED, UNKNOWN, CANCELLED, BUSY, PREEMPTED }
    public record Endpoint(int mapId, int portalId, String portalName, int x, int y) {
        public Map<String, Object> toMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("map_id", mapId);
            result.put("portal_id", portalId);
            result.put("portal_name", portalName);
            result.put("x", x);
            result.put("y", y);
            return result;
        }
    }
    public record State(int worldId, int channelId, int mapId, int sceneIdentity, int x, int y,
                        boolean alive, boolean changingMaps) {
        public Map<String, Object> toMap() {
            return Map.of("world_id", worldId, "channel_id", channelId, "map_id", mapId,
                    "scene_identity", sceneIdentity, "x", x, "y", y,
                    "alive", alive, "changing_maps", changingMaps);
        }
    }
    public record Dispatch(boolean started, boolean returned, boolean handlerError) { }
    public record Transition(boolean observed, boolean acknowledgementStarted,
                             boolean acknowledgementReturned, boolean acknowledgementError,
                             boolean complete) { }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        result.put("character_id", characterId);
        result.put("epoch", epoch);
        result.put("requested_portal_id", requestedPortalId);
        result.put("exit", exit == null ? null : exit.toMap());
        result.put("entry", entry == null ? null : entry.toMap());
        result.put("before", before == null ? null : before.toMap());
        result.put("after", after == null ? null : after.toMap());
        result.put("dispatch_started", dispatch.started());
        result.put("handler_returned", dispatch.returned());
        result.put("handler_error", dispatch.handlerError());
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
