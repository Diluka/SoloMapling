package soloMapling.game.scene;

import org.gms.client.inventory.WeaponType;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import soloMapling.game.combat.CombatJobTree;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;

/** Matched V17 PrepareActionLayer: integer scaling of each WZ frame, then sum; uncovered actions stay unknown. */
public final class SceneActionTiming {
    private SceneActionTiming() { }

    public static int bodyDurationMs(int skillId, int bodyAction, int speed) {
        String action = BotAttackData.actionName(bodyAction);
        if (action == null) return -1;
        return durationForName(action, speed);
    }

    public static int buffDurationMs(int skillId) {
        var skill = DataProviderFactory.getDataProvider(WZFiles.SKILL).getData(skillId / 10000 + ".img");
        if (skill == null) return -1;
        var actions = skill.getChildByPath("skill/" + skillId + "/action");
        if (actions == null || actions.getChildren().isEmpty()) return 0;
        return durationForName(DataTool.getString(actions.getChildren().getFirst()), 6);
    }

    private static int durationForName(String action, int speed) {
        var body = DataProviderFactory.getDataProvider(WZFiles.CHARACTER).getData("00002000.img");
        if (body == null) return -1;
        var frames = body.getChildByPath(action);
        if (frames == null || frames.getChildren().isEmpty()) return -1;
        int total = 0;
        int scale = Math.max(2, Math.min(10, speed)) + 10;
        for (var frame : frames.getChildren()) {
            if (frame.getChildByPath("delay") == null) return -1;
            int delay = Math.abs(DataTool.getIntConvert("delay", frame));
            total += delay * scale / 16;
        }
        return total;
    }

    public static int proposalDurationMs(CombatJobTree.AttackPlan plan, WeaponType weapon) {
        int skillId = plan.nativeRules() ? plan.nativePlan().skillId() : plan.profile().skillFor(weapon);
        int speed = plan.nativeRules() ? 4 : plan.profile().speed;
        int upper = 0;
        for (int action : BotAttackData.possibleActionsFor(skillId, weapon)) {
            int duration = bodyDurationMs(skillId, action, speed);
            if (duration < 0) return -1;
            upper = Math.max(upper, duration);
        }
        return upper;
    }
}
