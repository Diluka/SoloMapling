package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import soloMapling.server.MethodScheduler;

import java.awt.Point;

/** Creates delayed callbacks in a normal Solo class rather than an AspectJ inter-type method. */
public final class DelayedMapDrops {
    private DelayedMapDrops() { }

    public static void fromReactor(MapleMap map, Character character, Reactor reactor, Item item,
                                   Point point, short questId, short delay) {
        MethodScheduler.runAfterDelay(() -> map.dropFromReactor(character, reactor, item, point, questId), delay);
    }

    public static void meso(MapleMap map, int meso, Point point, MapObject dropper, Character owner,
                            boolean playerDrop, byte dropType, short delay) {
        MethodScheduler.runAfterDelay(() -> map.spawnMesoDrop(meso, point, dropper, owner, playerDrop, dropType), delay);
    }
}
