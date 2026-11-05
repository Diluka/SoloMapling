package soloMapling.game.scene;

import org.gms.net.server.Server;
import org.gms.server.life.Monster;
import org.gms.client.inventory.WeaponType;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.game.action.NativeCombatActions;
import soloMapling.game.action.NativeAttackAction;
import soloMapling.game.action.NativeBasicActions;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.util.List;

/** One entered character's attack input; the caller retains its original scope, grant and lane work. */
public final class CharacterAttack {
    private final SceneActorBinding binding;

    public CharacterAttack(SceneActorBinding binding) { this.binding = binding; }

    public NativeCombatActions.StrikeResult strike(Server server, ExecutionScope.WorkHandle work,
            AbilitySceneModule.Plan plan, List<Monster> targets, boolean facingLeft) {
        binding.awaitWork(work);
        return binding.scene().abilities().strike(server, binding, work, plan, targets, facingLeft);
    }

    public NativeCombatActions.BuffResult buff(Server server, ExecutionScope.WorkHandle work, int skillId) {
        binding.awaitWork(work);
        return binding.scene().abilities().buff(server, binding, work, skillId);
    }

    public NativeBasicActions.CommandHandle prepareBasic(Server server, CharacterCommandLane lane,
            CharacterCommandLane.ControlPermit permit, ExecutionScope.WorkHandle work, NativeAttackAction action) {
        binding.awaitWork(work);
        var admitted = binding.dispatch().requestNative(permit, work,
                binding.nativeScene().map().getMonsterByOid(action.targetOid()), "basic_attack");
        var arrangement = admitted.arrangement();
        if (arrangement != null) work.completion().whenComplete((ignored, failure) ->
                binding.dispatch().release(arrangement, "basic_attack_settled"));
        return binding.scene().abilities().prepareBasic(server, binding, lane, permit, work, action, arrangement);
    }

    public BotAttackDriver.StrikeResult strikeLegacy(ExecutionScope.WorkHandle work, BotAttackProfile profile,
            List<Monster> targets, WeaponType weapon, boolean facingLeft, boolean shadowPartner) {
        binding.awaitWork(work);
        return binding.scene().abilities().strikeLegacy(binding, work, profile, targets, weapon, facingLeft, shadowPartner);
    }
}
