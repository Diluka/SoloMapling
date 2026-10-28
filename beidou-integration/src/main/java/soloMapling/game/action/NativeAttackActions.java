package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.client.inventory.WeaponType;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.CloseRangeDamageHandler;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import soloMapling.game.context.GameContext;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static soloMapling.game.action.NativeActionResult.Outcome.*;

/** One trusted ordinary melee command, with the runtime's existing scope, grant and lane. */
public final class NativeAttackActions {
    public static final int MAXIMUM_HORIZONTAL_DISTANCE = 120;
    public static final int MAXIMUM_VERTICAL_DISTANCE = 60;
    private NativeAttackActions() { }

    public static Map<String, Object> capabilities() {
        return Map.of("damage_policy", NativeAttackDamagePolicy.ID, "skill", 0, "targets", 1, "damage_lines", 1,
                "maximum_horizontal_distance", MAXIMUM_HORIZONTAL_DISTANCE,
                "maximum_vertical_distance", MAXIMUM_VERTICAL_DISTANCE,
                "supported_weapon_types", NativeAttackDamagePolicy.WEAPONS,
                "native_damage_attribution", true, "client_calculation_equivalent", false);
    }

    public static NativeBasicActions.CommandHandle prepareAttack(Server server, CharacterCommandLane lane,
            CharacterCommandLane.ControlPermit permit, ExecutionScope.WorkHandle work, GameContext.SceneRef scene,
            NativeAttackAction action) {
        Objects.requireNonNull(action);
        return NativeBasicActions.prepare(server, lane, permit, work, scene, "attack", context -> attack(context, action));
    }

    private static NativeBasicResult attack(NativeBasicActions.Context context, NativeAttackAction action) {
        Character actor = context.actor().character();
        MapleMap map = context.scene().map();
        Monster target = map.getMonsterByOid(action.targetOid());
        requireTarget(actor, map, target, action.targetOid());
        NativeAttackDamagePolicy.Roll roll = NativeAttackDamagePolicy.roll(NativeAttackDamagePolicy.capture(actor, target));
        int before = target.getHp();
        int template = target.getId();
        Point actorPosition = new Point(actor.getPosition());
        Point targetPosition = new Point(target.getPosition());
        NativeAttackEvidence.Fact evidence;
        try (NativeAttackEvidence.Probe probe = NativeAttackEvidence.begin(actor, target)) {
            // Re-resolve the exact object and legal distance immediately before native entry.
            requireTarget(actor, map, map.getMonsterByOid(action.targetOid()), action.targetOid());
            if (map.getMonsterByOid(action.targetOid()) != target) {
                throw new NativeActionService.Invalid("attack_target_changed");
            }
            context.dispatch(new CloseRangeDamageHandler(), attackPacket(action.targetOid(), roll.damage(),
                    roll.attributes().weaponType(), targetPosition.x < actorPosition.x));
            evidence = probe.fact();
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("evidence_source", "NATIVE_MONSTER_DAMAGE");
        facts.put("effect_observed", evidence.observed());
        facts.put("target_oid", action.targetOid()); facts.put("target_template_id", template);
        facts.put("target_hp_before", before);
        try {
            int after = Math.max(0, target.getHp());
            facts.put("target_hp_after", after); facts.put("target_dead_after", after == 0);
        } catch (RuntimeException unavailable) {
            facts.put("target_hp_after", null); facts.put("target_dead_after", null);
        }
        facts.put("damage_applied", evidence.appliedDamage());
        facts.put("hit", evidence.appliedDamage() > 0);
        facts.put("native_last_hit", evidence.returned() == 1 ? evidence.lastHit() : null);
        facts.put("native_damage_entries", evidence.entered()); facts.put("native_damage_returns", evidence.returned());
        facts.put("native_damage_applications", evidence.applications());
        facts.put("damage_policy", NativeAttackDamagePolicy.ID); facts.put("computed_damage", roll.damage());
        facts.put("hit_roll_passed", roll.hitRollPassed()); facts.put("hit_chance", roll.hitChance());
        facts.put("weapon_type", roll.attributes().weaponType().name());
        facts.put("weapon_item_id", roll.attributes().weaponItemId());
        if (evidence.observed()) return context.result(SUCCEEDED, null, facts);
        return context.result(context.handlerError() ? INDETERMINATE : FAILED,
                context.handlerError() ? "native_attack_outcome_unknown" : "native_attack_not_applied", facts);
    }

    private static void requireTarget(Character actor, MapleMap map, Monster target, int oid) {
        if (!actor.isAlive() || actor.isBanned() || actor.hasDisease(Disease.STUN) || actor.hasDisease(Disease.SEDUCE)
                || actor.getBuffedValue(BuffStat.MONSTER_RIDING) != null
                || actor.getBuffEffect(BuffStat.MORPH) != null && actor.getBuffEffect(BuffStat.MORPH).isMorphWithoutAttack()) {
            throw new NativeActionService.Invalid("attack_actor_state_rejected");
        }
        if (target == null || target.getObjectId() != oid || target.getMap() != map || !target.isAlive()
                || target.getStats().isFriendly()) throw new NativeActionService.Invalid("attack_target_unavailable");
        Point from = actor.getPosition(), to = target.getPosition();
        if (from == null || to == null || Math.abs((long) to.x - from.x) > MAXIMUM_HORIZONTAL_DISTANCE
                || Math.abs((long) to.y - from.y) > MAXIMUM_VERTICAL_DISTANCE) {
            throw new NativeActionService.Invalid("attack_target_out_of_range");
        }
    }

    /** Fixed native layout, skill=0, one target, one line; never an external packet/damage API. */
    private static ByteBuf attackPacket(int targetOid, int damage, WeaponType weapon, boolean left) {
        int bodyAction = switch (weapon) {
            case GENERAL2H_SWING, GENERAL2H_STAB, SWORD2H -> 9;
            case POLE_ARM_SWING, POLE_ARM_STAB -> 13;
            case SPEAR_STAB, SPEAR_SWING -> 19;
            case DAGGER_OTHER, DAGGER_THIEVES -> 16;
            default -> 5;
        };
        return Unpooled.buffer(54).writeByte(0).writeByte(0x11).writeIntLE(0).writeZero(8)
                .writeByte(0).writeByte(bodyAction).writeByte(left ? 0x80 : 0).writeByte(0).writeByte(4).writeZero(4)
                .writeIntLE(targetOid).writeZero(14).writeIntLE(damage).writeZero(4);
    }
}
