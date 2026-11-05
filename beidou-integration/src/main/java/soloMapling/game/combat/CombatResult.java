package soloMapling.game.combat;

import soloMapling.game.action.NativeCombatActions;
import soloMapling.game.action.NativeContactDamageActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual facts of one settled engagement, with the execution policy recorded per attack. */
public record CombatResult(Outcome outcome, String reason, int initialJob, List<Integer> activePath,
                           List<Integer> buffSkillIds, List<Integer> buffsCast, List<AttackUse> attacks, List<Hit> hits,
                           int navigationCalls, int levelBefore, int levelAfter, int expBefore, int expAfter,
                           long elapsedMs, String rules, int currentJob, List<Integer> jobChanges,
                           int hpBefore, int hpAfter, int mpBefore, int mpAfter,
                           List<NativeCombatActions.StrikeResult> nativeAttacks,
                           List<NativeCombatActions.BuffResult> nativeBuffs,
                           List<NativeContactDamageActions.ContactResult> contactHits,
                           int nativeAttackAttempts, int nativeBuffAttempts, int contactHitCount) {
    public enum Outcome { TARGET_DEAD, TARGET_INVALID, UNREACHABLE, UNAVAILABLE, CANCELLED, TIMED_OUT,
        CONTEXT_CHANGED, PREEMPTED, BUSY, NO_ATTACK, DEAD, INDETERMINATE }
    public record AttackUse(int providerJob, String kind, int skillId, String route, int count,
                            int skillLevel, String rules) { }
    public record Hit(int objectId, int monsterId, int hpBefore, int hpAfter, long rolledDamage, boolean killed) { }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome.name());
        if (reason != null) result.put("reason", reason);
        result.put("initial_job", initialJob);
        result.put("current_job", currentJob);
        result.put("job_changes", jobChanges);
        result.put("active_path", activePath);
        result.put("buff_skill_ids", buffSkillIds);
        result.put("buffs_cast", buffsCast);
        result.put("attacks", attacks.stream().map(attack -> Map.of("provider_job", attack.providerJob(),
                "kind", attack.kind(), "skill_id", attack.skillId(), "route", attack.route(), "count", attack.count(),
                "skill_level", attack.skillLevel(), "rules", attack.rules())).toList());
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
        result.put("rules", rules);
        result.put("resources_before", Map.of("hp", hpBefore, "mp", mpBefore));
        result.put("resources_after", Map.of("hp", hpAfter, "mp", mpAfter));
        result.put("native_attacks", nativeAttacks.stream().map(NativeCombatActions.StrikeResult::toMap).toList());
        result.put("native_attack_fact_scope", "LATEST_PER_SKILL");
        result.put("native_attack_attempts", nativeAttackAttempts);
        result.put("native_buffs", nativeBuffs.stream().map(NativeCombatActions.BuffResult::toMap).toList());
        result.put("native_buff_fact_scope", "LATEST_PER_SKILL");
        result.put("native_buff_attempts", nativeBuffAttempts);
        result.put("contact_hits", contactHits.stream().map(NativeContactDamageActions.ContactResult::toMap).toList());
        result.put("contact_hit_fact_scope", "LAST_SCENE_BACKGROUND_DURING_ENGAGEMENT");
        result.put("contact_hit_count", contactHitCount);
        return result;
    }
}
