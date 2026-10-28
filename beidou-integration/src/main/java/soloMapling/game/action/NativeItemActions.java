package soloMapling.game.action;

import io.netty.buffer.Unpooled;
import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.id.ItemId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.ItemMoveHandler;
import org.gms.net.server.channel.handlers.ItemPickupHandler;
import org.gms.net.server.channel.handlers.UseItemHandler;
import org.gms.server.ItemInformationProvider;
import org.gms.server.StatEffect;
import org.gms.server.SoloItemMetadata;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import soloMapling.game.context.GameContext;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static soloMapling.game.action.NativeActionResult.Outcome.*;

/** Ordinary native items, shared by runtime modules. Submission and authority stay in the common runner. */
public final class NativeItemActions {
    private NativeItemActions() { }

    public static Map<String, Object> capabilities() {
        return Map.ofEntries(
                Map.entry("inventory_types", List.of("EQUIP", "USE", "SETUP", "ETC", "CASH")),
                Map.entry("positive_slot_minimum", 1), Map.entry("positive_slot_maximum", 127),
                Map.entry("equipment_slots", NativeItemAction.equipmentSlots()),
                Map.entry("equipment_kind", "ordinary_non_cash_equipment"),
                Map.entry("pickup_kind", "ordinary_inventory_items_no_meso_nx_scripts_or_consume_on_pickup"),
                Map.entry("pickup_maximum_distance", Map.of("x", 800, "y", 600)),
                Map.entry("pickup_native_minimum_drop_age_ms", 400),
                Map.entry("use_item_kind", "ordinary_hp_mp_potions_no_buff_or_map_effect"),
                Map.entry("drop", Map.of("minimum_quantity", 1, "maximum_quantity", (int) Short.MAX_VALUE,
                        "rechargeable_partial_quantity", false, "retained_ground_object_guaranteed", false,
                        "pet_and_special_item_side_effects", false)),
                Map.entry("native_item_move_minimum_interval_ms", 300));
    }

    public static NativeBasicActions.CommandHandle prepare(Server server, CharacterCommandLane lane,
                                                           CharacterCommandLane.ControlPermit permit,
                                                           ExecutionScope.WorkHandle work, GameContext.SceneRef scene,
                                                           NativeItemAction action) {
        Objects.requireNonNull(action);
        return NativeBasicActions.prepare(server, lane, permit, work, scene, action.action(), context -> switch (action) {
            case NativeItemAction.Pickup pickup -> pickup(context, pickup);
            case NativeItemAction.Drop drop -> drop(context, drop);
            case NativeItemAction.Use use -> use(context, use);
            case NativeItemAction.Equip equip -> equipment(context, equip.sourceSlot(), equip.targetSlot(), true);
            case NativeItemAction.Unequip unequip -> equipment(context, unequip.sourceSlot(), unequip.targetSlot(), false);
        });
    }

    private static NativeBasicResult pickup(NativeBasicActions.Context context, NativeItemAction.Pickup request) {
        context.requireCurrent();
        Character actor = context.actor().character();
        MapleMap map = context.scene().map();
        MapObject object = map.getMapObject(request.objectId());
        if (!(object instanceof MapItem ground)) throw invalid("ground_item_not_found");
        ground.lockItem();
        try {
            if (map.getMapObject(request.objectId()) != ground || ground.isPickedUp()) throw invalid("ground_item_changed");
            Item item = ground.getItem();
            ItemInformationProvider information = ItemInformationProvider.getInstance();
            if (ground.getMeso() != 0 || item == null || item.getQuantity() < 1
                    || ItemId.isNxCard(item.getItemId()) || item.getItemId() / 10000 == 243
                    || information.isConsumeOnPickup(item.getItemId()) || item.getPetId() >= 0) {
                throw invalid("unsupported_pickup_item");
            }
            InventoryType type = ItemConstants.getInventoryType(item.getItemId());
            if (type == InventoryType.UNDEFINED || type == InventoryType.EQUIPPED) throw invalid("unsupported_pickup_item");
            Point actorPosition = actor.getPosition(), dropPosition = ground.getPosition();
            if (Math.abs((long) actorPosition.x - dropPosition.x) > 800
                    || Math.abs((long) actorPosition.y - dropPosition.y) > 600) throw invalid("ground_item_out_of_range");
            Inventory inventory = requireInventory(actor, type);
            int itemId = item.getItemId(), quantity = item.getQuantity();
            List<Map<String, Object>> before = inventory(inventory);
            int beforeQuantity = count(inventory, itemId);
            context.dispatch(new ItemPickupHandler(), Unpooled.buffer(13).writeIntLE(0).writeByte(0)
                    .writeShortLE(actorPosition.x).writeShortLE(actorPosition.y).writeIntLE(request.objectId()));
            List<Map<String, Object>> after = inventory(inventory);
            int gained = count(inventory, itemId) - beforeQuantity;
            boolean picked = ground.isPickedUp(), removed = map.getMapObject(request.objectId()) != ground;
            boolean identity = actor.getInventory(type) == inventory && ground.getItem() == item;
            boolean observed = identity && picked && removed && gained == quantity;
            Map<String, Object> facts = facts("NATIVE_ITEM_PICKUP", before, after, observed);
            facts.put("inventory_type", type.name()); facts.put("object_id", request.objectId());
            facts.put("item_id", itemId); facts.put("ground_quantity_before", quantity);
            facts.put("ground_quantity_after", (int) item.getQuantity());
            facts.put("inventory_quantity_delta", gained); facts.put("ground_picked_up", picked);
            facts.put("ground_removed", removed); facts.put("identity_verified", identity);
            if (observed) return context.result(SUCCEEDED, null, facts);
            return context.result(!context.handlerError() && identity && !picked && !removed && gained == 0
                    && quantity == item.getQuantity() ? FAILED : INDETERMINATE,
                    gained == 0 && !picked && !removed ? "native_pickup_not_observed" : "native_pickup_outcome_unknown", facts);
        } finally { ground.unlockItem(); }
    }

    private static NativeBasicResult drop(NativeBasicActions.Context context, NativeItemAction.Drop request) {
        context.requireCurrent();
        Character actor = context.actor().character();
        InventoryType type = InventoryType.valueOf(request.inventoryType());
        Inventory inventory = requireInventory(actor, type);
        Item source = requireItem(inventory, request.sourceSlot());
        int itemId = source.getItemId(), sourceQuantity = source.getQuantity();
        short sourceFlag = source.getFlag(); String sourceOwner = source.getOwner(); long sourceExpiration = source.getExpiration();
        if (request.quantity() > sourceQuantity || ItemConstants.isRechargeable(itemId) && request.quantity() != sourceQuantity) {
            throw invalid("invalid_item_quantity");
        }
        if (source.getPetId() >= 0 || ItemConstants.isNewYearCardEtc(itemId)
                || itemId == ItemId.ARPQ_SPIRIT_JEWEL || itemId == ItemId.CHALKBOARD_1
                || itemId == ItemId.CHALKBOARD_2 || itemId == actor.getItemEffect()) throw invalid("unsupported_drop_item");
        MapleMap map = context.scene().map();
        List<Map<String, Object>> before = inventory(inventory);
        int beforeQuantity = count(inventory, itemId);
        Set<Integer> oldObjects = map.getMapObjects().stream().filter(MapItem.class::isInstance)
                .map(MapObject::getObjectId).collect(Collectors.toSet());
        dispatchMove(context, type, request.sourceSlot(), 0, request.quantity());
        List<Map<String, Object>> after = inventory(inventory);
        int lost = beforeQuantity - count(inventory, itemId);
        Item remaining = inventory.getItem((short) request.sourceSlot());
        boolean identity = actor.getInventory(type) == inventory && (remaining == null || remaining == source);
        boolean observed = identity && lost == request.quantity()
                && (remaining == null ? sourceQuantity == lost : remaining.getQuantity() == sourceQuantity - lost);
        List<Map<String, Object>> ground = new ArrayList<>();
        for (MapObject candidate : map.getMapObjects()) {
            if (candidate instanceof MapItem item && !oldObjects.contains(item.getObjectId())) {
                item.lockItem();
                try {
                    Item dropped = item.getItem();
                    if (item.getDropper() == actor && item.getOwnerIdLocked() == actor.getId() && dropped != null
                            && dropped.getItemId() == itemId && dropped.getQuantity() == lost
                            && dropped.getFlag() == sourceFlag && Objects.equals(dropped.getOwner(), sourceOwner)
                            && dropped.getExpiration() == sourceExpiration
                            && (sourceQuantity != lost || dropped == source)) {
                        ground.add(Map.of("object_id", item.getObjectId(), "item_id", itemId,
                                "quantity", (int) dropped.getQuantity(), "x", item.getPosition().x, "y", item.getPosition().y));
                    }
                } finally { item.unlockItem(); }
            }
        }
        Map<String, Object> facts = facts("NATIVE_ITEM_DROP", before, after, observed);
        facts.put("inventory_type", type.name()); facts.put("source_slot", request.sourceSlot());
        facts.put("item_id", itemId); facts.put("requested_quantity", request.quantity());
        facts.put("removed_quantity", lost); facts.put("identity_verified", identity);
        facts.put("ground_objects", ground);
        facts.put("ground_outcome", ground.size() == 1 ? "RETAINED_OBJECT_OBSERVED"
                : ground.isEmpty() ? "NO_RETAINED_OBJECT_OBSERVED" : "AMBIGUOUS");
        if (observed) return context.result(context.handlerError() && ground.size() != 1 ? INDETERMINATE : SUCCEEDED,
                context.handlerError() && ground.size() != 1 ? "native_drop_outcome_unknown" : null, facts);
        return context.result(!context.handlerError() && identity && lost == 0 && before.equals(after) ? FAILED : INDETERMINATE,
                lost == 0 ? "native_drop_not_observed" : "native_drop_outcome_unknown", facts);
    }

    private static NativeBasicResult use(NativeBasicActions.Context context, NativeItemAction.Use request) {
        context.requireCurrent();
        Character actor = context.actor().character();
        Inventory inventory = requireInventory(actor, InventoryType.USE);
        Item source = requireItem(inventory, request.sourceSlot());
        int itemId = source.getItemId(), beforeQuantity = source.getQuantity();
        if (!ItemConstants.isPotion(itemId)) throw invalid("unsupported_use_item");
        StatEffect effect = ItemInformationProvider.getInstance().getItemEffect(itemId);
        if (effect == null || effect.getHpCon() != 0 || effect.getMpCon() != 0
                || effect.getStatups() == null || !effect.getStatups().isEmpty()
                || effect.getHp() < 0 || effect.getMp() < 0 || effect.getHpRate() < 0 || effect.getMpRate() < 0
                || !(effect.getHp() > 0 || effect.getMp() > 0 || effect.getHpRate() > 0 || effect.getMpRate() > 0)) {
            throw invalid("unsupported_use_item");
        }
        List<Map<String, Object>> before = inventory(inventory);
        Map<String, Object> beforeStats = stats(actor);
        context.dispatch(new UseItemHandler(), Unpooled.buffer(10).writeIntLE(0).writeShortLE(request.sourceSlot()).writeIntLE(itemId));
        List<Map<String, Object>> after = inventory(inventory);
        Map<String, Object> afterStats = stats(actor);
        Item remaining = inventory.getItem((short) request.sourceSlot());
        boolean identity = actor.getInventory(InventoryType.USE) == inventory && (remaining == null || remaining == source);
        int consumed = beforeQuantity - (remaining == null ? 0 : remaining.getQuantity());
        boolean observed = identity && consumed == 1;
        int hpDelta = ((Number) afterStats.get("hp")).intValue() - ((Number) beforeStats.get("hp")).intValue();
        int mpDelta = ((Number) afterStats.get("mp")).intValue() - ((Number) beforeStats.get("mp")).intValue();
        Map<String, Object> facts = facts("NATIVE_ITEM_USE", before, after, observed);
        facts.put("inventory_type", "USE"); facts.put("source_slot", request.sourceSlot()); facts.put("item_id", itemId);
        facts.put("consumed_quantity", consumed); facts.put("before_stats", beforeStats); facts.put("after_stats", afterStats);
        facts.put("hp_delta", hpDelta); facts.put("mp_delta", mpDelta);
        facts.put("recovery_observed", hpDelta > 0 || mpDelta > 0); facts.put("identity_verified", identity);
        if (observed) return context.result(context.handlerError() ? INDETERMINATE : SUCCEEDED,
                context.handlerError() ? "native_item_effect_unknown" : null, facts);
        return context.result(!context.handlerError() && identity && before.equals(after) && beforeStats.equals(afterStats)
                ? FAILED : INDETERMINATE, consumed == 0 ? "native_item_use_not_observed" : "native_item_use_outcome_unknown", facts);
    }

    private static NativeBasicResult equipment(NativeBasicActions.Context context, int sourceSlot, int targetSlot, boolean equip) {
        context.requireCurrent();
        Character actor = context.actor().character();
        Inventory sourceInventory = requireInventory(actor, equip ? InventoryType.EQUIP : InventoryType.EQUIPPED);
        Inventory targetInventory = requireInventory(actor, equip ? InventoryType.EQUIPPED : InventoryType.EQUIP);
        Item source = equip ? requireItem(sourceInventory, sourceSlot) : sourceInventory.getItem((short) sourceSlot);
        if (!(source instanceof Equip) || source.getQuantity() != 1) throw invalid("equipment_source_empty");
        if (ItemInformationProvider.getInstance().isCash(source.getItemId())) throw invalid("unsupported_equipment_item");
        if (equip && !SoloItemMetadata.ordinaryEquipmentFits(source.getItemId(), targetSlot)) throw invalid("equipment_slot_mismatch");
        if (!equip && (targetSlot > Byte.toUnsignedInt(targetInventory.getSlotLimit())
                || targetInventory.getItem((short) targetSlot) != null)) throw invalid("equipment_target_unavailable");
        Map<String, Object> before = equipmentState(actor);
        dispatchMove(context, InventoryType.EQUIP, sourceSlot, targetSlot, 0);
        Map<String, Object> after = equipmentState(actor);
        boolean identity = actor.getInventory(equip ? InventoryType.EQUIP : InventoryType.EQUIPPED) == sourceInventory
                && actor.getInventory(equip ? InventoryType.EQUIPPED : InventoryType.EQUIP) == targetInventory;
        boolean observed = identity && targetInventory.getItem((short) targetSlot) == source && source.getPosition() == targetSlot
                && sourceInventory.getItem((short) sourceSlot) != source;
        Map<String, Object> facts = facts("NATIVE_EQUIPMENT", before, after, observed);
        facts.put("source_slot", sourceSlot); facts.put("target_slot", targetSlot);
        facts.put("item_id", source.getItemId()); facts.put("identity_verified", identity);
        if (observed) return context.result(context.handlerError() ? INDETERMINATE : SUCCEEDED,
                context.handlerError() ? "native_equipment_outcome_unknown" : null, facts);
        return context.result(!context.handlerError() && identity && before.equals(after) ? FAILED : INDETERMINATE,
                before.equals(after) ? "native_equipment_not_observed" : "native_equipment_outcome_unknown", facts);
    }

    private static void dispatchMove(NativeBasicActions.Context context, InventoryType type, int source, int target, int quantity) {
        context.dispatch(new ItemMoveHandler(), Unpooled.buffer(11).writeIntLE(0).writeByte(type.getType())
                .writeShortLE(source).writeShortLE(target).writeShortLE(quantity));
    }

    private static Inventory requireInventory(Character actor, InventoryType type) {
        Inventory inventory = actor.getInventory(type);
        if (inventory == null) throw invalid("native_inventory_unavailable");
        return inventory;
    }
    private static Item requireItem(Inventory inventory, int slot) {
        if (slot > Byte.toUnsignedInt(inventory.getSlotLimit())) throw invalid("inventory_slot_out_of_range");
        Item item = inventory.getItem((short) slot);
        if (item == null || item.getQuantity() < 1 || item.getPosition() != slot) throw invalid("inventory_source_empty");
        return item;
    }
    private static int count(Inventory inventory, int itemId) {
        inventory.lockInventory();
        try { return inventory.countById(itemId); }
        finally { inventory.unlockInventory(); }
    }
    private static List<Map<String, Object>> inventory(Inventory inventory) {
        inventory.lockInventory();
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Item item : inventory.list()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slot", (int) item.getPosition()); row.put("item_id", item.getItemId());
                row.put("quantity", (int) item.getQuantity()); row.put("flag", (int) item.getFlag());
                if (item instanceof Equip equip) row.put("attributes", Map.of("str", (int) equip.getStr(), "dex", (int) equip.getDex(),
                        "int", (int) equip.getInt(), "luk", (int) equip.getLuk(), "watk", (int) equip.getWatk(), "matk", (int) equip.getMatk(),
                        "max_hp", (int) equip.getHp(), "max_mp", (int) equip.getMp()));
                result.add(Map.copyOf(row));
                if (result.size() > 127) throw invalid("native_inventory_snapshot_unavailable");
            }
            result.sort(java.util.Comparator.comparingInt(row -> ((Number) row.get("slot")).intValue()));
            return List.copyOf(result);
        } finally { inventory.unlockInventory(); }
    }
    private static Map<String, Object> equipmentState(Character actor) {
        return Map.of("inventory", inventory(requireInventory(actor, InventoryType.EQUIP)),
                "equipped", inventory(requireInventory(actor, InventoryType.EQUIPPED)), "stats", stats(actor));
    }
    private static Map<String, Object> stats(Character actor) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("hp", actor.getHp()); stats.put("mp", actor.getMp());
        stats.put("max_hp", actor.getCurrentMaxHp()); stats.put("max_mp", actor.getCurrentMaxMp());
        stats.put("str", actor.getTotalStr()); stats.put("dex", actor.getTotalDex()); stats.put("int", actor.getTotalInt());
        stats.put("luk", actor.getTotalLuk()); stats.put("watk", actor.getTotalWatk()); stats.put("magic", actor.getTotalMagic());
        return Map.copyOf(stats);
    }
    private static Map<String, Object> facts(String source, Object before, Object after, boolean observed) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("evidence_source", source); facts.put("effect_observed", observed);
        facts.put("snapshot_consistency", "per_inventory_locked_native_state");
        facts.put("before", before); facts.put("after", after);
        return facts;
    }
    private static NativeActionService.Invalid invalid(String reason) { return new NativeActionService.Invalid(reason); }
}
