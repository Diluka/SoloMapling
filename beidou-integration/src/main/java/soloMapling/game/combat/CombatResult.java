package soloMapling.game.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual facts of one settled engagement using the legacy simulated combat rules. */
public record CombatResult(Outcome outcome, String reason, int initialJob, List<Integer> activePath,
                           List<Integer> buffSkillIds, List<Integer> buffsCast, List<AttackUse> attacks, List<Hit> hits,
                           int navigationCalls, int levelBefore, int levelAfter, int expBefore, int expAfter,
                           long elapsedMs) {
    public enum Outcome { TARGET_DEAD, TARGET_INVALID, UNREACHABLE, UNAVAILABLE, CANCELLED, TIMED_OUT,
        CONTEXT_CHANGED, BUSY, NO_ATTACK }
    public record AttackUse(int providerJob, String kind, int skillId, String route, int count) { }
    public record Hit(int objectId, int monsterId, int hpBefore, int hpAfter, long rolledDamage, boolean killed) { }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        if (reason != null) result.put("reason", reason);
        result.put("initial_job", initialJob);
        result.put("active_path", activePath);
        result.put("buff_skill_ids", buffSkillIds);
        result.put("buffs_cast", buffsCast);
        result.put("attacks", attacks.stream().map(attack -> Map.of("provider_job", attack.providerJob(),
                "kind", attack.kind(), "skill_id", attack.skillId(), "route", attack.route(), "count", attack.count())).toList());
        result.put("hits", hits.stream().map(hit -> Map.of("object_id", hit.objectId(), "monster_id", hit.monsterId(),
                "hp_before", hit.hpBefore(), "hp_after", hit.hpAfter(), "rolled_damage", hit.rolledDamage(),
                "killed", hit.killed())).toList());
        result.put("navigation_calls", navigationCalls);
        result.put("level_before", levelBefore);
        result.put("level_after", levelAfter);
        result.put("exp_before", expBefore);
        result.put("exp_after", expAfter);
        result.put("elapsed_ms", elapsedMs);
        result.put("execution_stopped", true);
        result.put("rules", "legacy_simulated_combat_v1");
        return result;
    }
}
