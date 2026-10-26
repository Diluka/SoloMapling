package soloMapling.beidou;

import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapFactory;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;

import java.util.HashMap;
import java.util.Map;

/** Extend the official map loader's resolved WZ data with navigation-only metadata. */
public privileged aspect MapGeometryHooks {
    after(int mapId, int world, int channel, EventInstanceManager event) returning(MapleMap map):
            execution(MapleMap MapFactory.loadMapFromWz(int, int, int, EventInstanceManager))
            && args(mapId, world, channel, event) {
        if (map == null) return;
        Data data = MapFactory.mapSource.getData(MapFactory.getMapName(mapId));
        Data info = data.getChildByPath("info");
        map.setSwim(DataTool.getInt("swim", info, 0) != 0);
        Data speed = info.getChildByPath("fs");
        if (speed != null) map.setFootholdSpeed(DataTool.getFloat(speed));
        String link = DataTool.getString(info.getChildByPath("link"), "");
        if (!link.isEmpty()) data = MapFactory.mapSource.getData(MapFactory.getMapName(Integer.parseInt(link)));
        Data ropes = data.getChildByPath("ladderRope");
        if (ropes != null) {
            for (Data rope : ropes) {
                map.addRope(new Rope(DataTool.getInt("x", rope), DataTool.getInt("y1", rope),
                        DataTool.getInt("y2", rope), DataTool.getInt("l", rope, 0) == 1));
            }
        }
        if (map.getFootholds() == null) return;
        Map<Integer, Foothold> byId = new HashMap<Integer, Foothold>();
        for (Foothold foothold : map.getFootholds().getAllFootholds()) byId.put(foothold.getId(), foothold);
        Data footholds = data.getChildByPath("foothold");
        if (footholds != null) {
            for (Data layer : footholds) for (Data group : layer) for (Data entry : group) {
                Foothold foothold = byId.get(Integer.parseInt(entry.getName()));
                if (foothold != null) foothold.setForbidFallDown(DataTool.getInt("forbidFallDown", entry, 0) != 0);
            }
        }
    }
}
