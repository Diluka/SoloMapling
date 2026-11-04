package soloMapling.game.scene;

import org.gms.server.life.Monster;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;

import java.util.List;

/** Live geometry and object views; the native map owns all positions, life and item state. */
public final class EnvironmentSceneModule implements SceneRuntimeModule {
    private final SceneRuntime scene;

    public EnvironmentSceneModule(SceneRuntime scene) { this.scene = scene; }

    public MapleMap map() { return scene.map(); }
    public FootholdTree geometry() { return scene.map().getFootholds(); }
    public List<MapObject> objects() { return objects(scene.map()); }
    public List<Monster> monsters() { return scene.map().getAllMonsters(); }

    /** Also serves observers of loaded maps which do not have a simulated member. */
    public static List<MapObject> objects(MapleMap map) { return map.getMapObjects(); }
}
