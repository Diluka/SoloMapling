package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.scene.SceneRuntimeManager;

/** Scene membership follows native map lifecycle; the official source tree remains unchanged. */
public aspect SceneRuntimeHooks {
    after(Character character) returning:
            execution(void MapleMap.addPlayer(Character)) && args(character) {
        CharacterRuntime runtime = CharacterRuntime.nativeOwner(character);
        if (runtime != null) runtime.refreshSceneMembership();
    }

    before(Character character): execution(void MapleMap.removePlayer(Character)) && args(character) {
        SceneRuntimeManager.leave(character);
    }

    before(MapleMap map): execution(void MapleMap.dispose()) && this(map) {
        for (Character character : map.getCharacters()) SceneRuntimeManager.leave(character);
    }

}
