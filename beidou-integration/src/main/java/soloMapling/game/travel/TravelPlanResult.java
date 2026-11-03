package soloMapling.game.travel;

import java.util.List;
import java.util.Map;

/** A static portal route; FOUND does not assert that the character can walk or enter it. */
public record TravelPlanResult(Outcome outcome, int fromMapId, int toMapId, List<Leg> route) {
    public enum Outcome { FOUND, NO_ROUTE, UNSUPPORTED }

    public TravelPlanResult { route = List.copyOf(route); }

    public record Leg(int fromMapId, int exitPortalId, String exitPortalName, int toMapId, String entryPortalName) {
        public Map<String, Object> toMap() {
            return Map.of("fromMapId", fromMapId, "exitPortalId", exitPortalId,
                    "exitPortalName", exitPortalName, "toMapId", toMapId, "entryPortalName", entryPortalName);
        }
    }

    public Map<String, Object> toMap() {
        return Map.of("outcome", outcome.name(), "fromMapId", fromMapId, "toMapId", toMapId,
                "route", route.stream().map(Leg::toMap).toList());
    }
}
