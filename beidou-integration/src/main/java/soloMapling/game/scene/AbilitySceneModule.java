package soloMapling.game.scene;

import org.gms.client.Character;
import org.gms.client.BuffStat;
import org.gms.client.Disease;
import org.gms.client.Job;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.WeaponType;
import org.gms.net.server.Server;
import org.gms.server.ItemInformationProvider;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.Route;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotMobHitboxProvider;
import soloMapling.game.action.NativeCombatActions;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeAttackAction;
import soloMapling.game.action.NativeAttackActions;
import soloMapling.game.action.NativeBasicActions;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Scene-owned supported skill definitions and execution; native handlers settle effects exactly once. */
public final class AbilitySceneModule implements SceneRuntimeModule {
    public record Plan(int skillId, int skillLevel, Route route, int mobCount, int damageLines,
                       int intervalMs, int reachX, int reachY) { }
    public static final List<String> SUPPORTED_MELEE_WEAPONS = List.of("GENERAL1H_SWING", "GENERAL1H_STAB", "GENERAL2H_SWING",
            "GENERAL2H_STAB", "DAGGER_THIEVES", "DAGGER_OTHER", "KNUCKLE", "POLE_ARM_SWING",
            "POLE_ARM_STAB", "SPEAR_STAB", "SPEAR_SWING", "STAFF", "SWORD1H", "SWORD2H", "WAND");

    private final SceneRuntime scene;

    public AbilitySceneModule(SceneRuntime scene) { this.scene = scene; }

    NativeCombatActions.StrikeResult strike(Server server, SceneActorBinding binding, ExecutionScope.WorkHandle work,
                                            Plan plan, List<Monster> targets, boolean facingLeft) {
        binding.requireCurrent();
        var actor = binding.characterRef().character();
        if (!work.cancelRequested() && attackRejection(actor, plan.skillId()) == null
                && plan.equals(plan(actor, plan.skillId()))) {
            scene.presentation().faceAttack(binding, work, facingLeft);
        }
        return NativeCombatActions.strike(server, binding.nativeScene(), work, plan, targets, facingLeft);
    }

    NativeCombatActions.BuffResult buff(Server server, SceneActorBinding binding, ExecutionScope.WorkHandle work, int skillId) {
        binding.requireCurrent();
        return NativeCombatActions.buff(server, binding.nativeScene(), work, skillId);
    }

    NativeBasicActions.CommandHandle prepareBasic(Server server, SceneActorBinding binding,
            CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
            ExecutionScope.WorkHandle work, NativeAttackAction action, SceneActionDispatch.Arrangement arrangement) {
        binding.requireCurrent();
        return NativeAttackActions.prepareAttack(server, lane, permit, work, binding.nativeScene(), action,
                facingLeft -> {
                    if (arrangement == null) throw new NativeActionService.Invalid("scene_arrangement_busy");
                    arrangement.requireCurrent();
                    scene.presentation().faceAttack(binding, work, facingLeft);
                });
    }

    BotAttackDriver.StrikeResult strikeLegacy(SceneActorBinding binding, ExecutionScope.WorkHandle work,
            BotAttackProfile profile, List<Monster> targets, WeaponType expectedWeapon, boolean facingLeft,
            boolean shadowPartner) {
        binding.requireCurrent();
        if (work.cancelRequested()) throw new CancellationException("combat_cancelled");
        var actor = binding.characterRef().character();
        var currentWeapon = weapon(actor);
        if (currentWeapon == WeaponType.NOT_A_WEAPON || currentWeapon != expectedWeapon) {
            throw new NativeActionService.Invalid("attack_weapon_changed");
        }
        var rejected = attackRejection(actor, profile.skillFor(currentWeapon));
        if (rejected != null) throw new NativeActionService.Invalid(rejected);
        scene.presentation().faceAttack(binding, work, facingLeft);
        return BotAttackDriver.strikeNative(actor, targets, profile, currentWeapon, facingLeft, shadowPartner);
    }

    /** Resolve only adapted, actually learned skills; target selection stays with the combat module. */
    public static Plan plan(Character actor, int skillId) {
        Route route;
        if (skillId == 0) route = Route.CLOSE;
        else if ((skillId == 1001004 || skillId == 1001005) && actor.getJob().isA(Job.WARRIOR)) route = Route.CLOSE;
        else if ((skillId == 2001004 || skillId == 2001005) && actor.getJob().isA(Job.MAGICIAN)) route = Route.MAGIC;
        else return null;
        var weapon = weapon(actor);
        if (route == Route.CLOSE && weapon == WeaponType.NOT_A_WEAPON) return null;
        if (route == Route.CLOSE && !SUPPORTED_MELEE_WEAPONS.contains(weapon.name())) return null;
        if (route == Route.MAGIC && weapon != WeaponType.WAND && weapon != WeaponType.STAFF) return null;
        if (skillId == 0) return new Plan(0, 0, route, 1, 1, 720, 120, 60);
        int level = actor.getSkillLevel(skillId);
        if (level == 0) return null;
        var skill = SkillFactory.getSkill(skillId);
        var effect = skill.getEffect(level);
        int reachX = route == Route.MAGIC ? 450 : skillId == 1001005 ? 130 : 120;
        int reachY = route == Route.MAGIC ? 150 : 80;
        if (effect.hasBoundingBox()) {
            var box = effect.calculateBoundingBox(new Point(), true);
            reachX = Math.max(Math.abs(box.x), Math.abs(box.x + box.width));
            reachY = Math.max(Math.abs(box.y), Math.abs(box.y + box.height));
        }
        return new Plan(skillId, level, route, effect.getMobCount(), effect.getAttackCount(),
                Math.max(route == Route.MAGIC ? 840 : 720, skill.getAnimationTime()), reachX, reachY);
    }

    /** Uses the actual learned-level WZ box, or this first slice's explicit finite fallback range. */
    public static boolean canHit(Character actor, Monster target, Plan plan, Point from) {
        if (target.getStats().isFriendly() || !target.isAlive()) return false;
        var position = target.getPosition();
        boolean left = position.x < from.x;
        if (plan.skillId() != 0) {
            var effect = SkillFactory.getSkill(plan.skillId()).getEffect(plan.skillLevel());
            if (effect.hasBoundingBox()) {
                var box = effect.calculateBoundingBox(from, left);
                var body = BotMobHitboxProvider.getMobBounds(target);
                return body == null ? box.contains(position) : box.intersects(body);
            }
        }
        return Math.abs((long) position.x - from.x) <= plan.reachX()
                && Math.abs((long) position.y - from.y) <= plan.reachY();
    }

    public static String attackRejection(Character actor, int skillId) {
        if (!actor.isAlive() || actor.isBanned() || actor.hasDisease(Disease.STUN) || actor.hasDisease(Disease.SEDUCE)
                || actor.getBuffedValue(BuffStat.MONSTER_RIDING) != null
                || actor.getBuffEffect(BuffStat.MORPH) != null && actor.getBuffEffect(BuffStat.MORPH).isMorphWithoutAttack()) {
            return "actor_state_rejected";
        }
        return skillId != 0 && actor.hasDisease(Disease.SEAL) ? "skill_sealed" : null;
    }

    public static WeaponType weapon(Character actor) {
        var inventory = actor.getInventory(InventoryType.EQUIPPED);
        inventory.lockInventory();
        try {
            var item = inventory.getItem((short) -11);
            return item == null ? WeaponType.NOT_A_WEAPON : ItemInformationProvider.getInstance().getWeaponType(item.getItemId());
        } finally { inventory.unlockInventory(); }
    }

}
