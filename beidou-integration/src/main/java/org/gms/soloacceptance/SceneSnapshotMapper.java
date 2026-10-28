package org.gms.soloacceptance;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.life.Monster;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import soloMapling.beidou.BotRegistry;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded diagnostic projection of an already loaded scene; never performs game actions. */
final class SceneSnapshotMapper {
    private static final List<String> CATEGORIES = List.of(
            "players", "monsters", "npcs", "reactors", "items", "portals");

    private SceneSnapshotMapper() { }

    static Map<String, Object> snapshot(MapleMap map, int limit) {
        Map<String, List<Map<String, Object>>> projected = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            projected.put(category, new ArrayList<>());
            totals.put(category, 0);
        }

        // getMapObjects already acquires the native read lock and returns a copy.
        List<MapObject> objects = new ArrayList<>(map.getMapObjects());
        objects.removeIf(object -> object == null);
        objects.sort(Comparator.comparingInt(MapObject::getObjectId));
        Map<String, Integer> unsupported = new LinkedHashMap<>();
        for (MapObject object : objects) {
            String category = category(object);
            if (category == null) {
                String type = object.getType() == null ? "UNKNOWN" : object.getType().name();
                unsupported.merge(type, 1, Integer::sum);
                continue;
            }
            totals.merge(category, 1, Integer::sum);
            if (projected.get(category).size() < limit) {
                projected.get(category).add(object(object));
            }
        }

        List<Portal> portals = new ArrayList<>(map.getPortals());
        portals.removeIf(portal -> portal == null);
        portals.sort(Comparator.comparingInt(Portal::getId));
        totals.put("portals", portals.size());
        for (Portal portal : portals.subList(0, Math.min(limit, portals.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", portal.getId());
            row.put("type", "PORTAL");
            row.put("portal_type", portal.getType());
            row.put("name", portal.getName());
            row.put("position", position(portal.getPosition()));
            row.put("target_map_id", portal.getTargetMapId());
            projected.get("portals").add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            int total = totals.get(category);
            result.put(category, Map.of("total_count", total,
                    "truncated", total > limit, "items", projected.get(category)));
        }
        result.put("unsupported_object_count", unsupported.values().stream().mapToInt(Integer::intValue).sum());
        result.put("unsupported_object_types", unsupported);
        return result;
    }

    private static String category(MapObject object) {
        if (object instanceof Character) return "players";
        if (object instanceof Monster) return "monsters";
        if (object instanceof NPC) return "npcs";
        if (object instanceof Reactor) return "reactors";
        if (object instanceof MapItem) return "items";
        return null;
    }

    private static Map<String, Object> object(MapObject object) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("oid", object.getObjectId());
        row.put("type", object.getType() == null ? "UNKNOWN" : object.getType().name());
        row.put("position", position(object.getPosition()));
        if (object instanceof Character player) {
            row.put("id", player.getId());
            row.put("name", player.getName());
            row.put("level", player.getLevel());
            row.put("job_id", player.getJob() == null ? null : player.getJob().getId());
            row.put("guild_id", player.getGuildId());
            row.put("party_id", player.getPartyId());
            row.put("is_bot", BotRegistry.isBot(player));
        } else if (object instanceof Monster monster) {
            row.put("template_id", monster.getId());
            row.put("name", monster.getName());
            row.put("level", monster.getLevel());
            row.put("hp", monster.getHp());
            row.put("max_hp", monster.getMaxHp());
        } else if (object instanceof NPC npc) {
            row.put("template_id", npc.getId());
            row.put("name", npc.getName());
        } else if (object instanceof Reactor reactor) {
            row.put("template_id", reactor.getId());
            row.put("state", reactor.getState());
            row.put("alive", reactor.isAlive());
        } else if (object instanceof MapItem dropped) {
            Item item = dropped.getItem();
            // Native getItemId() returns the meso amount for money, so do not call it here.
            row.put("item_id", item == null ? null : item.getItemId());
            row.put("meso", dropped.getMeso());
            row.put("quantity", item == null ? 0 : (int) item.getQuantity());
            row.put("owner_id", dropped.getOwnerId());
            row.put("picked_up", dropped.isPickedUp());
        }
        return row;
    }

    private static Map<String, Integer> position(Point point) {
        return point == null ? null : Map.of("x", point.x, "y", point.y);
    }
}
