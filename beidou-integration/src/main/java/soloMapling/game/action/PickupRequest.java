package soloMapling.game.action;

import org.gms.server.maps.MapItem;
import soloMapling.game.scene.SceneActionDispatch;

/** Scripts identify one OID; trusted controls may bind the already-selected exact native object. */
public record PickupRequest(int objectId, Binding binding) {
    public PickupRequest {
        if (objectId < 1) throw new IllegalArgumentException("invalid_ground_object");
    }

    public PickupRequest(int objectId) { this(objectId, null); }

    public record Binding(MapItem target, boolean immediate, SceneActionDispatch.Arrangement arrangement) { }
}
