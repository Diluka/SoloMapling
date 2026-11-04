package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.WeaponType;
import org.gms.net.PacketHandler;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.CloseRangeDamageHandler;
import org.gms.net.server.channel.handlers.MagicDamageHandler;
import org.gms.net.server.channel.handlers.SpecialMoveHandler;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.Route;
import soloMapling.beidou.NativeCombatEffects;
import soloMapling.game.context.GameContext;
import soloMapling.game.scene.AbilitySceneModule;
import soloMapling.game.scene.AbilitySceneModule.Plan;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.game.scene.AbilitySceneModule.weapon;

/** First adapted melee/magic skills. Native handlers own effects, costs and monster settlement. */
public final class NativeCombatActions {
    public static final String PHYSICAL_POLICY = "native_physical_skill_v1";
    public static final String MAGIC_POLICY = "native_magic_skill_v1";
    public enum Outcome { APPLIED, RESOURCE_UNAVAILABLE, COOLDOWN, REJECTED, CANCELLED, UNKNOWN }
    public record StrikeResult(Outcome outcome, String reason, boolean dispatched, boolean handlerError,
                               int skillId, int skillLevel, String policy, int hpBefore, int hpAfter,
                               int mpBefore, int mpAfter, List<BotAttackDriver.HitFact> hits) {
        public StrikeResult { hits = List.copyOf(hits); }
        public Map<String, Object> toMap() {
            var result = projection(outcome, reason, dispatched, handlerError, skillId, skillLevel, policy,
                    hpBefore, hpAfter, mpBefore, mpAfter);
            result.put("hits", hits.stream().map(hit -> Map.of("object_id", hit.objectId(),
                    "monster_id", hit.monsterId(), "rolled_damage", hit.rolledDamage(),
                    "hp_before", hit.hpBefore(), "hp_after", hit.hpAfter(), "killed", hit.killed())).toList());
            result.put("evidence_source", "NATIVE_MONSTER_DAMAGE");
            return result;
        }
    }
    public record BuffResult(Outcome outcome, String reason, boolean dispatched, boolean handlerError,
                             int skillId, int skillLevel, String policy, int hpBefore, int hpAfter,
                             int mpBefore, int mpAfter, int durationMs) {
        public Map<String, Object> toMap() {
            var result = projection(outcome, reason, dispatched, handlerError, skillId, skillLevel, policy,
                    hpBefore, hpAfter, mpBefore, mpAfter);
            result.put("duration_ms", durationMs);
            return result;
        }
    }

    private NativeCombatActions() { }

    /** Called on the provider's already admitted short lane work. No externally supplied damage. */
    public static StrikeResult strike(Server server, GameContext.SceneRef scene, ExecutionScope.WorkHandle work,
                                      Plan plan, List<Monster> targets, boolean facingLeft) {
        NativeContextChecks.requireSceneCurrent(server, scene);
        var actor = scene.actor().character();
        int hp = actor.getHp(), mp = actor.getMp();
        String policy = plan.route() == Route.MAGIC ? MAGIC_POLICY
                : plan.skillId() == 0 && weapon(actor) != WeaponType.NOT_A_WEAPON
                ? NativeAttackDamagePolicy.ID : PHYSICAL_POLICY;
        if (work.cancelRequested()) return strikeResult(Outcome.CANCELLED, "combat_cancelled", false, false,
                actor, plan, policy, hp, mp, List.of());
        String rejected = AbilitySceneModule.attackRejection(actor, plan.skillId());
        if (rejected != null) return strikeResult(Outcome.REJECTED, rejected, false, false,
                actor, plan, policy, hp, mp, List.of());
        var current = AbilitySceneModule.plan(actor, plan.skillId());
        if (current == null || current.skillLevel() != plan.skillLevel()) {
            return strikeResult(Outcome.REJECTED, "skill_or_weapon_changed", false, false,
                    actor, plan, policy, hp, mp, List.of());
        }
        if (targets.isEmpty() || targets.size() > plan.mobCount()) {
            return strikeResult(Outcome.REJECTED, "attack_targets_unavailable", false, false,
                    actor, plan, policy, hp, mp, List.of());
        }
        for (var target : targets) {
            if (scene.map().getMonsterByOid(target.getObjectId()) != target || target.getMap() != scene.map()
                    || !AbilitySceneModule.canHit(actor, target, plan, actor.getPosition())) {
                return strikeResult(Outcome.REJECTED, "attack_target_changed_or_out_of_range", false, false,
                        actor, plan, policy, hp, mp, List.of());
            }
        }
        var effect = plan.skillId() == 0 ? null : SkillFactory.getSkill(plan.skillId()).getEffect(plan.skillLevel());
        if (effect != null && !resources(actor, effect)) {
            return strikeResult(Outcome.RESOURCE_UNAVAILABLE, "skill_resources_unavailable", false, false,
                    actor, plan, policy, hp, mp, List.of());
        }
        if (plan.skillId() != 0 && actor.skillIsCooling(plan.skillId())) {
            return strikeResult(Outcome.COOLDOWN, "native_skill_cooldown", false, false,
                    actor, plan, policy, hp, mp, List.of());
        }

        var rolls = new LinkedHashMap<Monster, List<Integer>>();
        var before = new LinkedHashMap<Monster, Integer>();
        for (var target : targets) {
            before.put(target, target.getHp());
            var lines = new ArrayList<Integer>();
            for (int line = 0; line < plan.damageLines(); line++) lines.add(damage(actor, target, plan, effect));
            rolls.put(target, lines);
        }
        boolean handlerError;
        boolean effectObserved, effectApplied, damageObserved;
        boolean[] dispatched = {false};
        try (var cast = NativeCombatEvidence.begin(actor, plan.skillId(), targets)) {
            PacketHandler handler = plan.route() == Route.MAGIC ? new MagicDamageHandler() : new CloseRangeDamageHandler();
            try {
                handlerError = NativePacketDispatch.dispatch(scene.actor().client(), handler,
                        attackPacket(plan, rolls, weapon(actor), facingLeft), () -> {
                            NativeContextChecks.requireSceneCurrent(server, scene);
                            requireAdmission(actor, plan.skillId(), plan.skillLevel(), effect, work);
                            if (AbilitySceneModule.plan(actor, plan.skillId()) == null) {
                                throw new AdmissionStopped(Outcome.REJECTED, "skill_or_weapon_changed");
                            }
                            for (var target : targets) {
                                if (scene.map().getMonsterByOid(target.getObjectId()) != target
                                        || !AbilitySceneModule.canHit(actor, target, plan, actor.getPosition())) {
                                    throw new AdmissionStopped(Outcome.REJECTED, "attack_target_changed_or_out_of_range");
                                }
                            }
                        }, () -> dispatched[0] = true);
            } catch (AdmissionStopped stopped) {
                return strikeResult(stopped.outcome, stopped.getMessage(), false, false,
                        actor, plan, policy, hp, mp, List.of());
            }
            effectObserved = cast.effectObserved;
            effectApplied = cast.effectApplied;
            damageObserved = cast.damageObserved();
        }
        var hits = rolls.entrySet().stream().map(entry -> new BotAttackDriver.HitFact(entry.getKey().getObjectId(),
                entry.getKey().getId(), entry.getValue().stream().mapToInt(Integer::intValue).sum(),
                before.get(entry.getKey()), Math.max(0, entry.getKey().getHp()), !entry.getKey().isAlive())).toList();
        Outcome outcome = effectObserved && !effectApplied ? Outcome.RESOURCE_UNAVAILABLE
                : damageObserved || effectApplied ? Outcome.APPLIED : handlerError ? Outcome.UNKNOWN : Outcome.REJECTED;
        return strikeResult(outcome, outcome == Outcome.APPLIED ? null : "native_skill_not_applied",
                dispatched[0], handlerError, actor, plan, policy, hp, mp, hits);
    }

    public static BuffResult buff(Server server, GameContext.SceneRef scene, ExecutionScope.WorkHandle work, int skillId) {
        NativeContextChecks.requireSceneCurrent(server, scene);
        var actor = scene.actor().character();
        int hp = actor.getHp(), mp = actor.getMp(), level = actor.getSkillLevel(skillId);
        boolean supported = skillId == 1001003 && actor.getJob().isA(Job.WARRIOR)
                || (skillId == 2001002 || skillId == 2001003) && actor.getJob().isA(Job.MAGICIAN);
        if (!supported || level == 0) return buffResult(Outcome.REJECTED, "buff_not_learned_or_adapted", false,
                false, actor, skillId, level, hp, mp, 0);
        var effect = SkillFactory.getSkill(skillId).getEffect(level);
        if (work.cancelRequested()) return buffResult(Outcome.CANCELLED, "combat_cancelled", false, false,
                actor, skillId, level, hp, mp, effect.getDuration());
        String rejected = AbilitySceneModule.attackRejection(actor, skillId);
        if (rejected != null) return buffResult(Outcome.REJECTED, rejected, false, false,
                actor, skillId, level, hp, mp, effect.getDuration());
        if (!resources(actor, effect)) return buffResult(Outcome.RESOURCE_UNAVAILABLE, "skill_resources_unavailable",
                false, false, actor, skillId, level, hp, mp, effect.getDuration());
        if (actor.skillIsCooling(skillId)) return buffResult(Outcome.COOLDOWN, "native_skill_cooldown", false,
                false, actor, skillId, level, hp, mp, effect.getDuration());
        boolean[] dispatched = {false};
        boolean handlerError, applied;
        try (var cast = NativeCombatEvidence.begin(actor, skillId, List.of())) {
            try {
                handlerError = NativePacketDispatch.dispatch(scene.actor().client(), new SpecialMoveHandler(),
                        Unpooled.buffer(9).writeIntLE(0).writeIntLE(skillId).writeByte(level), () -> {
                            NativeContextChecks.requireSceneCurrent(server, scene);
                            requireAdmission(actor, skillId, level, effect, work);
                        }, () -> dispatched[0] = true);
            } catch (AdmissionStopped stopped) {
                return buffResult(stopped.outcome, stopped.getMessage(), false, false,
                        actor, skillId, level, hp, mp, effect.getDuration());
            }
            applied = cast.effectObserved && cast.effectApplied;
        }
        return buffResult(applied ? Outcome.APPLIED : handlerError ? Outcome.UNKNOWN : Outcome.REJECTED,
                applied ? null : "native_buff_not_applied", dispatched[0], handlerError,
                actor, skillId, level, hp, mp, effect.getDuration());
    }

    private static boolean resources(Character actor, StatEffect effect) {
        int hpChange = NativeCombatEffects.hpChange(effect, actor);
        int mpChange = NativeCombatEffects.mpChange(effect, actor);
        return (hpChange == 0 || actor.getHp() + hpChange > 0) && actor.getMp() + mpChange >= 0;
    }

    private static void requireAdmission(Character actor, int skillId, int level, StatEffect effect,
                                          ExecutionScope.WorkHandle work) {
        if (work.cancelRequested()) throw new AdmissionStopped(Outcome.CANCELLED, "combat_cancelled");
        String rejected = AbilitySceneModule.attackRejection(actor, skillId);
        if (rejected != null) throw new AdmissionStopped(Outcome.REJECTED, rejected);
        if (skillId != 0 && actor.getSkillLevel(skillId) != level) {
            throw new AdmissionStopped(Outcome.REJECTED, "skill_level_changed");
        }
        if (effect != null && !resources(actor, effect)) {
            throw new AdmissionStopped(Outcome.RESOURCE_UNAVAILABLE, "skill_resources_unavailable");
        }
        if (skillId != 0 && actor.skillIsCooling(skillId)) {
            throw new AdmissionStopped(Outcome.COOLDOWN, "native_skill_cooldown");
        }
    }

    private static final class AdmissionStopped extends RuntimeException {
        private final Outcome outcome;
        AdmissionStopped(Outcome outcome, String reason) { super(reason, null, false, false); this.outcome = outcome; }
    }

    /** Explicit finite policy: native base bound/WZ multiplier and native target defence, without critical/mastery emulation. */
    private static int damage(Character actor, Monster target, Plan plan, StatEffect effect) {
        if (plan.route() == Route.CLOSE) {
            var roll = NativeAttackDamagePolicy.roll(NativeAttackDamagePolicy.capture(actor, target));
            return plan.skillId() == 0 ? roll.damage() : (int) Math.min(Integer.MAX_VALUE,
                    (long) roll.damage() * effect.getDamage() / 100);
        }
        // This is the native normal-spell upper bound, with a bounded roll and actual monster MDDamage.
        double magic = actor.getTotalMagic();
        double base = Math.ceil((magic * Math.ceil(magic / 1000.0) + magic) / 30.0)
                + Math.ceil(actor.getTotalInt() / 200.0);
        double bound = Math.max(1, base * effect.getMatk());
        double rolled = bound * ThreadLocalRandom.current().nextDouble(0.6, 1.0)
                - Math.max(0, target.getStats().MDDamage) * 0.5;
        return (int) Math.max(1, Math.min(Math.min(Integer.MAX_VALUE, bound), Math.floor(rolled)));
    }

    private static ByteBuf attackPacket(Plan plan, Map<Monster, List<Integer>> rolls, WeaponType weapon, boolean left) {
        var packet = Unpooled.buffer(24 + rolls.size() * (22 + 4 * plan.damageLines()));
        packet.writeByte(0).writeByte((rolls.size() << 4) | plan.damageLines()).writeIntLE(plan.skillId()).writeZero(8)
                .writeByte(0).writeByte(BotAttackData.actionFor(plan.skillId(), weapon)).writeByte(left ? 0x80 : 0)
                .writeByte(0).writeByte(4).writeZero(4);
        rolls.forEach((target, lines) -> {
            packet.writeIntLE(target.getObjectId()).writeZero(14);
            lines.forEach(packet::writeIntLE);
            packet.writeZero(4);
        });
        return packet;
    }

    private static StrikeResult strikeResult(Outcome outcome, String reason, boolean dispatched, boolean error,
            Character actor, Plan plan, String policy, int hp, int mp, List<BotAttackDriver.HitFact> hits) {
        return new StrikeResult(outcome, reason, dispatched, error, plan.skillId(), plan.skillLevel(), policy,
                hp, actor.getHp(), mp, actor.getMp(), hits);
    }

    private static BuffResult buffResult(Outcome outcome, String reason, boolean dispatched, boolean error,
            Character actor, int skillId, int level, int hp, int mp, int duration) {
        return new BuffResult(outcome, reason, dispatched, error, skillId, level, "native_self_buff_v1",
                hp, actor.getHp(), mp, actor.getMp(), duration);
    }

    private static Map<String, Object> projection(Outcome outcome, String reason, boolean dispatched, boolean error,
            int skillId, int level, String policy, int hpBefore, int hpAfter, int mpBefore, int mpAfter) {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        if (reason != null) result.put("reason", reason);
        result.put("dispatched", dispatched); result.put("handler_error", error);
        result.put("skill_id", skillId); result.put("skill_level", level); result.put("damage_policy", policy);
        result.put("hp_before", hpBefore); result.put("hp_after", hpAfter);
        result.put("mp_before", mpBefore); result.put("mp_after", mpAfter);
        return result;
    }
}
