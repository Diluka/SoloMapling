package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeAttackAction;
import soloMapling.game.action.NativeItemAction;
import soloMapling.game.action.NativeMovementAction;

import java.util.Set;

/** Strict finite protocol projection. Native rule validation remains in the shared action types. */
final class ActorBasicRequests {
    private static final Set<String> ACTIONS = Set.of("move", "stop", "attack", "pickup", "drop", "use_item", "equip", "unequip");

    private ActorBasicRequests() { }
    static boolean supports(String action) { return action != null && ACTIONS.contains(action); }

    static void validate(String action, ActorActRequest.Parameters p) {
        if (p == null) throw invalid();
        try {
            switch (action) {
                case "move" -> {
                    if (p.direction() == null || p.durationMs() == null) throw invalid();
                    movement(p);
                }
                case "stop" -> { }
                case "attack" -> {
                    if (p.targetOid() == null) throw invalid();
                    attack(p);
                }
                case "pickup" -> {
                    if (p.objectId() == null) throw invalid();
                    item(action, p);
                }
                case "drop" -> {
                    if (p.inventoryType() == null || p.sourceSlot() == null || p.quantity() == null) throw invalid();
                    item(action, p);
                }
                case "use_item" -> {
                    if (p.sourceSlot() == null) throw invalid();
                    item(action, p);
                }
                case "equip", "unequip" -> {
                    if (p.sourceSlot() == null || p.targetSlot() == null) throw invalid();
                    item(action, p);
                }
                default -> throw invalid();
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
