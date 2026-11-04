package org.gms.soloacceptance;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Pet;
import org.gms.server.life.Monster;
import org.gms.server.life.NPC;
import org.gms.server.maps.Dragon;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Mist;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.server.maps.Summon;
import org.gms.soloacceptance.actor.AcceptanceClient;
import soloMapling.game.scene.MonsterControlCoordinator;
import soloMapling.game.scene.SceneRuntimeManager;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only value projection of one already-loaded map. Capture service owns identity and timing. */
final class SceneCaptureFrames {
    private static final List<String> CATEGORIES = List.of("players", "monsters", "npcs", "reactors", "items",
            "summons", "mists", "dragons", "other_objects", "pets", "portals");

    private SceneCaptureFrames() { }

    static Map<String, Object> staticScene(MapleMap map) {
        var result = new LinkedHashMap<String, Object>();
        result.put("map_id", map.getId());
        result.put("bounds", rectangle(map.getMapArea()));
        result.put("footholds", map.getFootholds().getAllFootholds().stream()
                .sorted(Comparator.comparingInt(foothold -> foothold.getId())).map(foothold -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", foothold.getId());
                    row.put("start", Map.of("x", foothold.getX1(), "y", foothold.getY1()));
                    row.put("end", Map.of("x", foothold.getX2(), "y", foothold.getY2()));
                    row.put("previous_id", foothold.getPrev());
                    row.put("next_id", foothold.getNext());
                    row.put("wall", foothold.isWall());
                    row.put("forbid_fall_down", foothold.isForbidFallDown());
                    return row;
                }).toList());
        result.put("ropes", map.getRopes().stream().map(rope -> Map.of(
                "x", rope.x(), "y1", rope.y1(), "y2", rope.y2(), "ladder", rope.isLadder())).toList());
        result.put("portals", map.getPortals().stream().sorted(Comparator.comparingInt(Portal::getId))
                .map(SceneCaptureFrames::portal).toList());
        result.put("physics", Map.of("foothold_speed", map.getFootholdSpeed(), "swim", map.isSwim(),
                "hp_decrease", map.getHPDec(), "hp_decrease_protection_item", map.getHPDecProtect(),
                "recovery", map.getRecovery(), "field_limit", map.getFieldLimit()));
        result.put("coverage", Map.of("status", "partial", "read_status", "available",
                "platform_representation", "native_footholds", "unsupported_fields", List.of("native_gravity")));
        return result;
    }

    static Map<String, Object> frame(MapleMap map) {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        var failed = new LinkedHashMap<String, Integer>();
        CATEGORIES.forEach(category -> { rows.put(category, new ArrayList<>()); failed.put(category, 0); });
        var objects = new ArrayList<>(map.getMapObjects());
        objects.sort(Comparator.comparingInt(MapObject::getObjectId));
        int unavailablePetOwners = 0;
        for (var object : objects) {
            String category = category(object);
            try {
                rows.get(category).add(object(object));
            } catch (RuntimeException unavailable) {
                // Objects can disappear or change while this best-effort projection reads them.
                rows.get(category).add(Map.of("oid", object.getObjectId(), "read_status", "failed",
                        "reason", "native_object_read_failed", "lifecycle_identity", "unknown"));
                failed.merge(category, 1, Integer::sum);
            }
            if (object instanceof Character player) {
                Pet[] pets;
                try { pets = player.getPets(); }
                catch (RuntimeException unavailable) { unavailablePetOwners++; continue; }
                for (var pet : pets) {
                    if (pet == null) continue;
                    try { if (pet.isSummoned()) rows.get("pets").add(pet(player.getId(), pet)); }
                    catch (RuntimeException unavailable) {
                        rows.get("pets").add(Map.of("owner_character_id", player.getId(), "read_status", "failed",
                                "reason", "native_pet_read_failed", "lifecycle_identity", "unknown"));
                        failed.merge("pets", 1, Integer::sum);
                    }
                }
            }
        }
        for (var portal : map.getPortals().stream().sorted(Comparator.comparingInt(Portal::getId)).toList()) {
            try { rows.get("portals").add(portal(portal)); }
            catch (RuntimeException unavailable) {
                rows.get("portals").add(Map.of("id", portal.getId(), "read_status", "failed",
                        "reason", "native_portal_read_failed"));
                failed.merge("portals", 1, Integer::sum);
            }
        }
        var nativeState = new LinkedHashMap<String, Object>();
        for (var category : CATEGORIES) {
            var items = rows.get(category);
            boolean countComplete = !category.equals("pets") || unavailablePetOwners == 0;
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("total_count", countComplete ? items.size() : null);
            envelope.put("recorded_count", items.size());
            envelope.put("count_complete", countComplete);
            envelope.put("failed_count", failed.get(category));
            envelope.put("complete", countComplete && failed.get(category) == 0);
            envelope.put("truncated", false);
            envelope.put("items", items);
            if (category.equals("pets")) envelope.put("unavailable_owner_count", unavailablePetOwners);
            nativeState.put(category, envelope);
        }
        int failures = failed.values().stream().mapToInt(Integer::intValue).sum();
        var coverage = new LinkedHashMap<String, Object>();
        coverage.put("status", "partial");
        coverage.put("object_enumeration", "complete_native_map_object_copy_and_player_pet_arrays");
        coverage.put("object_limit", "none");
        coverage.put("failed_object_count", failures);
        coverage.put("unavailable_pet_owner_count", unavailablePetOwners);
        coverage.put("object_lifecycle_identity", "unknown");
        coverage.put("unsupported_fields", List.of("native_velocity", "native_object_lifecycle_generation",
                "monster_mist_skill", "other_object_specific_fields"));
        coverage.put("consistency", "best_effort");
        coverage.put("field_sources", Map.of(
                "stance", "native_last_movement_stance",
                "facing_left", "native_stance_direction",
                "max_hp_and_max_mp", "native_current_effective_character_maxima",
                "attack_animation", "separate_attack_output_events_when_covered_not_inferred_from_stance"));
        coverage.put("client_playback_verified", false);
        return Map.of("native", nativeState, "simulation", SceneRuntimeManager.snapshot(map), "coverage", coverage);
    }

    private static String category(MapObject object) {
        if (object instanceof Character) return "players";
        if (object instanceof Monster) return "monsters";
        if (object instanceof NPC) return "npcs";
        if (object instanceof Reactor) return "reactors";
        if (object instanceof MapItem) return "items";
        if (object instanceof Summon) return "summons";
        if (object instanceof Mist) return "mists";
        if (object instanceof Dragon) return "dragons";
        return "other_objects";
    }

    private static Map<String, Object> object(MapObject object) {
        var row = new LinkedHashMap<String, Object>();
        row.put("oid", object.getObjectId());
        row.put("type", object.getType().name());
        row.put("position", position(object.getPosition()));
        row.put("lifecycle_identity", "unknown");
        row.put("read_status", "available");
        if (object instanceof Character player) {
            row.put("character_id", player.getId());
            row.put("name", player.getName());
            row.put("level", player.getLevel());
            row.put("job_id", player.getJob().getId());
            row.put("source", source(player.getClient()));
            row.put("stance", player.getStance());
            row.put("facing_left", player.isFacingLeft());
            row.put("alive", player.isAlive());
            row.put("hp", player.getHp());
            row.put("mp", player.getMp());
            row.put("max_hp", player.getCurrentMaxHp());
            row.put("max_mp", player.getCurrentMaxMp());
            row.put("base_max_hp", player.getMaxHp());
            row.put("base_max_mp", player.getMaxMp());
            var weapon = player.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
            row.put("weapon_item_id", weapon == null ? null : weapon.getItemId());
            row.put("buffs", player.getAllBuffs().stream().map(buff -> {
                var value = new LinkedHashMap<String, Object>();
                value.put("source_id", buff.effect.getSourceId());
                value.put("buff_source_id", buff.effect.getBuffSourceId());
                value.put("elapsed_ms", buff.usedTime);
                value.put("duration_ms", buff.effect.getDuration());
                value.put("stats", buff.effect.getStatups().stream().map(stat -> Map.of(
                        "name", stat.getLeft().name(), "value", stat.getRight())).toList());
                return value;
            }).toList());
            row.put("diseases", player.getAllDiseases().entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().name())).map(entry -> Map.of(
                            "name", entry.getKey().name(), "remaining_ms", entry.getValue().getLeft(),
                            "skill_id", entry.getValue().getRight().getId().type().getId(),
                            "skill_level", entry.getValue().getRight().getId().level())).toList());
        } else if (object instanceof Monster monster) {
            row.put("template_id", monster.getId());
            row.put("name", monster.getName());
            row.put("level", monster.getLevel());
            row.put("hp", monster.getHp());
            row.put("mp", monster.getMp());
            row.put("max_hp", monster.getMaxHp());
            row.put("max_mp", monster.getMaxMp());
            row.put("alive", monster.isAlive());
            row.put("stance", monster.getStance());
            row.put("facing_left", monster.isFacingLeft());
            var controller = monster.getController();
            row.put("controller", controller == null ? null : Map.of("character_id", controller.getId(),
                    "oid", controller.getObjectId(), "source", source(controller.getClient()),
                    "driver_eligible", MonsterControlCoordinator.canControl(monster, controller),
                    "qualification_source", "MonsterControlCoordinator"));
            row.put("controller_has_aggro", monster.isControllerHasAggro());
            row.put("controller_knows_about_aggro", monster.isControllerKnowsAboutAggro());
            row.put("statuses", monster.getStati().entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().name())).map(entry -> {
                        var value = new LinkedHashMap<String, Object>();
                        value.put("name", entry.getKey().name());
                        value.put("value", entry.getValue().getStati().get(entry.getKey()));
                        return value;
                    }).toList());
        } else if (object instanceof NPC npc) {
            row.put("template_id", npc.getId());
            row.put("name", npc.getName());
            row.put("stance", npc.getStance());
            row.put("facing_left", npc.isFacingLeft());
            row.put("foothold_id", npc.getFh());
            row.put("hidden", npc.isHidden());
        } else if (object instanceof Reactor reactor) {
            row.put("template_id", reactor.getId());
            row.put("state", reactor.getState());
            row.put("event_state", reactor.getEventState());
            row.put("alive", reactor.isAlive());
            row.put("facing_direction", reactor.getFacingDirection());
            row.put("area", rectangle(reactor.getArea()));
        } else if (object instanceof MapItem dropped) {
            var item = dropped.getItem();
            row.put("item_id", item == null ? null : item.getItemId());
            row.put("meso", dropped.getMeso());
            row.put("quantity", item == null ? 0 : (int) item.getQuantity());
            row.put("owner_id", dropped.getOwnerId());
            row.put("party_owner_id", dropped.getPartyOwnerId());
            row.put("drop_type", dropped.getDropType());
            row.put("player_drop", dropped.isPlayerDrop());
            row.put("picked_up", dropped.isPickedUp());
            row.put("drop_time", dropped.getDropTime());
        } else if (object instanceof Summon summon) {
            row.put("owner_character_id", summon.getOwner().getId());
            row.put("skill_id", summon.getSkill());
            row.put("skill_level", summon.getSkillLevel());
            row.put("hp", summon.getHP());
            row.put("movement_type", summon.getMovementType().name());
            row.put("stance", summon.getStance());
            row.put("facing_left", summon.isFacingLeft());
        } else if (object instanceof Mist mist) {
            row.put("area", rectangle(mist.getBox()));
            row.put("owner_character_id", mist.getOwner() == null ? null : mist.getOwner().getId());
            row.put("owner_monster_oid", mist.getMobOwner() == null ? null : mist.getMobOwner().getObjectId());
            row.put("monster_mist", mist.isMobMist());
            row.put("poison", mist.isPoisonMist());
            row.put("recovery", mist.isRecoveryMist());
            row.put("skill_id", mist.isMobMist() ? null : mist.getSourceSkill().getId());
            row.put("skill_read_status", mist.isMobMist() ? "unavailable" : "available");
        } else if (object instanceof Dragon dragon) {
            row.put("owner_character_id", dragon.getOwner().getId());
            row.put("stance", dragon.getStance());
            row.put("facing_left", dragon.isFacingLeft());
        } else row.put("specific_fields", "unavailable");
        return row;
    }

    private static Map<String, Object> pet(int ownerId, Pet pet) {
        var row = new LinkedHashMap<String, Object>();
        row.put("owner_character_id", ownerId);
        row.put("pet_id", pet.getUniqueId());
        row.put("item_id", pet.getItemId());
        row.put("name", pet.getName());
        row.put("position", position(pet.getPos()));
        row.put("stance", pet.getStance());
        row.put("foothold_id", pet.getFh());
        row.put("summoned", pet.isSummoned());
        row.put("lifecycle_identity", "unknown");
        return row;
    }

    private static Map<String, Object> portal(Portal portal) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", portal.getId());
        row.put("portal_type", portal.getType());
        row.put("name", portal.getName());
        row.put("position", position(portal.getPosition()));
        row.put("target_map_id", portal.getTargetMapId());
        row.put("target_name", portal.getTarget());
        row.put("script_name", portal.getScriptName());
        row.put("status", portal.getPortalStatus());
        row.put("state", portal.getPortalState());
        return row;
    }

    private static String source(Client client) {
        if (client == null) return "unavailable";
        if (client instanceof AcceptanceClient) return "acceptance_actor";
        if (client instanceof BotClient) return "environment_bot";
        return "native_client";
    }

    private static Map<String, Integer> position(Point position) {
        return position == null ? null : Map.of("x", position.x, "y", position.y);
    }

    private static Map<String, Integer> rectangle(Rectangle area) {
        return area == null ? null : Map.of("x", area.x, "y", area.y, "width", area.width, "height", area.height);
    }
}
