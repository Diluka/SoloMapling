package soloMapling.game.action;

/** An actual portal id in the role's current scene; the native definition owns its destination. */
public record PortalRequest(int portalId) {
    public PortalRequest {
        if (portalId < 0) throw new IllegalArgumentException("invalid_portal_id");
    }
}
