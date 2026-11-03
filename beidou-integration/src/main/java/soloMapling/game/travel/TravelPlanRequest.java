package soloMapling.game.travel;

/** A topology query, independent of the bound character's current scene. */
public record TravelPlanRequest(int fromMapId, int toMapId) {
    public TravelPlanRequest {
        if (fromMapId < 0 || fromMapId >= 999_999_999 || toMapId < 0 || toMapId >= 999_999_999) {
            throw new IllegalArgumentException("invalid_travel_map_id");
        }
    }
}
