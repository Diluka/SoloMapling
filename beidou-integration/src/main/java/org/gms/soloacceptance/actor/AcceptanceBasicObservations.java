package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fresh bounded native values. No map is loaded and no native object escapes the snapshot. */
final class AcceptanceBasicObservations {
    private static final int EQUIPPED_LIMIT = 127;
    private static final int SCENE_LIMIT = 100;

    private AcceptanceBasicObservations() { }

    /** Native getters own their locks; do not hold an inventory lock while reading derived stats. */
    static Map<String, Object> stats(Character character) {
        return Map.of("str", character.getTotalStr(), "dex", character.getTotalDex(),
                "int", character.getTotalInt(), "luk", character.getTotalLuk(),
                "watk", character.getTotalWatk(), "magic", character.getTotalMagic(),
                "max_hp", character.getCurrentMaxHp(), "max_mp", character.getCurrentMaxMp(),
                "hp", character.getHp(), "mp", character.getMp());
    }

    static Map<String, Object> equipped(Character character) {
        Inventory inventory = character.getInventory(InventoryType.EQUIPPED);
        inventory.lockInventory();
        try {
            List<Item> all = new ArrayList<>(inventory.list());
            List<Item> negative = all.stream().filter(item -> item.getPosition() < 0)
                    .sorted(Comparator.comparingInt(Item::getPosition)).toList();
            List<Map<String, Object>> items = new ArrayList<>();
            boolean nativeTypesComplete = true;
            for (Item item : negative.stream().limit(EQUIPPED_LIMIT).toList()) {
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("slot", (int) item.getPosition());
                fact.put("item_id", item.getItemId());
                fact.put("quantity", (int) item.getQuantity());
                fact.put("flags", (int) item.getFlag());
                if (item instanceof Equip equip) {
                    fact.put("attributes", Map.of("str", (int) equip.getStr(), "dex", (int) equip.getDex(),
                            "int", (int) equip.getInt(), "luk", (int) equip.getLuk(),
                            "watk", (int) equip.getWatk(), "matk", (int) equip.getMatk(),
                            "hp", (int) equip.getHp(), "mp", (int) equip.getMp()));
                } else {
                    nativeTypesComplete = false;
                    fact.put("attributes", null);
                }
                items.add(Collections.unmodifiableMap(fact));
            }
            boolean truncated = negative.size() > EQUIPPED_LIMIT;
            return Map.of("items", List.copyOf(items), "count", negative.size(), "truncated", truncated,
                    "complete", !truncated && negative.size() == all.size() && nativeTypesComplete);
        } finally {
            inventory.unlockInventory();
        }
    }

    static Map<String, Object> scene(MapleMap map) {
        // getMapObjects returns a copy after releasing the native map lock. Never nest that lock
        // around a MapItem lock or a monster read: native pickup takes item -> map in the other path.
        List<MapObject> objects = map.getMapObjects();
        List<MapItem> drops = objects.stream().filter(MapItem.class::isInstance).map(MapItem.class::cast)
                .sorted(Comparator.comparingInt(MapObject::getObjectId)).toList();
        List<Monster> monsters = objects.stream().filter(Monster.class::isInstance).map(Monster.class::cast)
                .sorted(Comparator.comparingInt(MapObject::getObjectId)).toList();
        List<Map<String, Object>> dropFacts = new ArrayList<>();
        for (MapItem item : drops.stream().limit(SCENE_LIMIT).toList()) {
            item.lockItem();
            try {
                Item nativeItem = item.getItem();
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("object_id", item.getObjectId());
                fact.put("position", position(item.getPosition()));
                fact.put("item_id", nativeItem == null ? null : nativeItem.getItemId());
                fact.put("quantity", nativeItem == null ? null : (int) nativeItem.getQuantity());
                fact.put("meso", item.getMeso());
                fact.put("picked_up", item.isPickedUp());
                dropFacts.add(Collections.unmodifiableMap(fact));
            } finally {
                item.unlockItem();
            }
        }
        List<Map<String, Object>> monsterFacts = new ArrayList<>();
        for (Monster monster : monsters.stream().limit(SCENE_LIMIT).toList()) {
            monsterFacts.add(Map.of("object_id", monster.getObjectId(), "template_id", monster.getId(),
                    "position", position(monster.getPosition()), "hp", monster.getHp(), "max_hp", monster.getMaxHp()));
        }
        return Map.of("items", page(dropFacts, drops.size()), "monsters", page(monsterFacts, monsters.size()),
                "snapshot_consistency", "best_effort");
    }

    private static Map<String, Object> page(List<Map<String, Object>> items, int total) {
        boolean truncated = total > SCENE_LIMIT;
        return Map.of("items", List.copyOf(items), "total_count", total, "truncated", truncated, "complete", !truncated);
    }

    private static Map<String, Object> position(Point point) { return Map.of("x", point.x, "y", point.y); }
}
