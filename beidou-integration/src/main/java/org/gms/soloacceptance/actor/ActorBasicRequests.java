package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeAttackAction;
import soloMapling.game.action.NativeItemAction;
import soloMapling.game.action.NativeMovementAction;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Strict finite protocol projection. Native rule validation remains in the shared action types. */
final class ActorBasicRequests {
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "move", Set.of("direction", "duration_ms"), "stop", Set.of(),
            "attack", Set.of("target_oid"), "pickup", Set.of("object_id"),
            "drop", Set.of("inventory_type", "source_slot", "quantity"),
            "use_item", Set.of("source_slot"),
            "equip", Set.of("source_slot", "target_slot"), "unequip", Set.of("source_slot", "target_slot"));

    private ActorBasicRequests() { }
    static boolean supports(String action) { return action != null && FIELDS.containsKey(action); }

    static void validate(String action, ActorActRequest.Parameters p) {
        if (!supports(action) || p == null) throw invalid();
        Set<String> supplied = new LinkedHashSet<>();
        if (p.text() != null) supplied.add("text");
        if (p.dialogueVersion() != null) supplied.add("dialogue_version");
        if (p.kind() != null) supplied.add("kind");
        if (p.selection() != null) supplied.add("selection");
        if (p.inventoryType() != null) supplied.add("inventory_type");
        if (p.sourceSlot() != null) supplied.add("source_slot");
        if (p.targetSlot() != null) supplied.add("target_slot");
        if (p.direction() != null) supplied.add("direction");
        if (p.durationMs() != null) supplied.add("duration_ms");
        if (p.targetOid() != null) supplied.add("target_oid");
        if (p.objectId() != null) supplied.add("object_id");
        if (p.quantity() != null) supplied.add("quantity");
        if (!FIELDS.get(action).equals(supplied)) throw invalid();
        try {
            switch (action) {
                case "move" -> movement(p);
                case "stop" -> { }
                case "attack" -> attack(p);
                default -> item(action, p);
            }
        } catch (NativeActionService.Invalid | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    static NativeMovementAction movement(ActorActRequest.Parameters p) {
        return new NativeMovementAction(p.direction(), p.durationMs());
    }
    static NativeAttackAction attack(ActorActRequest.Parameters p) { return new NativeAttackAction(p.targetOid()); }
    static NativeItemAction item(String action, ActorActRequest.Parameters p) {
        return switch (action) {
            case "pickup" -> new NativeItemAction.Pickup(p.objectId());
            case "drop" -> new NativeItemAction.Drop(p.inventoryType(), p.sourceSlot(), p.quantity());
            case "use_item" -> new NativeItemAction.Use(p.sourceSlot());
            case "equip" -> new NativeItemAction.Equip(p.sourceSlot(), p.targetSlot());
            case "unequip" -> new NativeItemAction.Unequip(p.sourceSlot(), p.targetSlot());
            default -> throw invalid();
        };
    }
    static String control(String action) { return "attack".equals(action) ? "combat" : "inventory"; }

    private static ObservationException invalid() {
        return new ObservationException(HttpStatus.BAD_REQUEST, "invalid_basic_action_parameters");
    }
}
