package soloMapling.game.combat;

import org.gms.client.Job;
import org.gms.client.inventory.WeaponType;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One combat parent's private advancement tree, bound once to the role's initial job. */
public final class CombatJobTree {
    public enum AttackKind { SINGLE, AOE, ULTIMATE }

    public record AttackPlan(Job providerJob, AttackKind kind, BotAttackProfile profile, boolean undeadOnly) {}

    private record JobNode(Job job, JobNode parent, BotAttackConfig.JobAttacks attacks, List<Integer> buffs) {}

    private final Map<Job, JobNode> nodes = new EnumMap<>(Job.class);
    private final JobNode current;
    private final List<Job> activePath;
    private final List<Integer> buffs;
    private final BotAttackProfile basicFallback = BotAttackProfile.basicSwing();

    public CombatJobTree(Job initialJob) {
        add(Job.WARRIOR, null);
        add(Job.FIGHTER, Job.WARRIOR);
        add(Job.CRUSADER, Job.FIGHTER);
        add(Job.HERO, Job.CRUSADER);
        add(Job.MAGICIAN, null);
        add(Job.IL_WIZARD, Job.MAGICIAN);
        add(Job.IL_MAGE, Job.IL_WIZARD);
        add(Job.IL_ARCHMAGE, Job.IL_MAGE);

        current = nodes.get(initialJob);
        var path = new ArrayList<Job>();
        var ancestry = new ArrayList<JobNode>();
        for (var node = current; node != null; node = node.parent()) {
            path.add(node.job());
            ancestry.add(node);
        }
        activePath = current == null ? List.of(initialJob) : List.copyOf(path);
        Collections.reverse(ancestry);
        buffs = ancestry.stream().flatMap(node -> node.buffs().stream()).toList();
    }

    private void add(Job job, Job parent) {
        nodes.put(job, new JobNode(job, parent == null ? null : nodes.get(parent),
                BotAttackConfig.localAttacks(job, WeaponType.NOT_A_WEAPON), BotBuffConfig.localBuffsForJob(job)));
    }

    /** Each category stops at its first declaring advancement; AUTO selection belongs to the parent. */
    public Optional<AttackPlan> resolve(AttackKind kind, WeaponType weapon, boolean undeadTarget) {
        for (var node = current; node != null; node = node.parent()) {
            var profile = switch (kind) {
                case SINGLE -> node.attacks().single();
                case AOE -> node.attacks().aoe();
                case ULTIMATE -> node.attacks().ultimate();
            };
            if (profile != null) return Optional.of(new AttackPlan(node.job(), kind, profile, false));
        }
        return kind == AttackKind.SINGLE
                ? Optional.of(new AttackPlan(Job.BEGINNER, kind, basicFallback, false)) : Optional.empty();
    }

    /** Current job first; unrelated branches never participate in attack selection. */
    public List<Job> activePath() { return activePath; }

    /** Local declarations accumulated from the earliest ancestor to the current job. */
    public List<Integer> buffs() { return buffs; }
}
