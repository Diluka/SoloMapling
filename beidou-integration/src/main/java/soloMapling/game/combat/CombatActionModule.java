package soloMapling.game.combat;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.skills.Hermit;
import org.gms.net.server.Server;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.GCMoveSystem.RuntimeNavigationEngine;
import soloMapling.game.combat.CombatJobTree.AttackKind;
import soloMapling.game.combat.CombatJobTree.AttackPlan;
import soloMapling.game.combat.CombatResult.Outcome;
import soloMapling.game.action.NativeCombatActions;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.ActionModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.scene.AbilitySceneModule;
import soloMapling.game.scene.SceneCharacterState;
import soloMapling.game.scene.SceneRuntimeManager;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.navigation.MoveToRequest;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One target-constrained engagement; supported native skills and legacy routes share the role's lane. */
public final class CombatActionModule implements ActionModule {
    public record Config(int timeoutMs) { }
    public static final Config DEFAULT_CONFIG = new Config(30_000);
    public static final ModuleFunction<CombatActionModule, CombatRequest, CombatResult> ENGAGE = new ModuleFunction<>(
            "combat.engage", "持续攻击指定目标；首批近战/魔法沿真实技能与资源，其余职业保留明确旧规则，等待交战及实际收尾。",
            List.of(new ModuleFunction.Parameter("target_oid", ModuleFunction.Type.INTEGER, true, "指定怪物对象ID"),
                    new ModuleFunction.Parameter("allowed_target_oids", ModuleFunction.Type.ARRAY, false,
                            "允许命中的怪物对象ID列表，最多32个且包含指定目标，默认仅指定目标"),
                    new ModuleFunction.Parameter("choice", ModuleFunction.Type.STRING, false,
                            "AUTO/SINGLE/AOE/ULTIMATE，默认AUTO；原生首批按真实已学技能，旧职业沿原选择"),
                    new ModuleFunction.Parameter("timeout_ms", ModuleFunction.Type.INTEGER, false,
                            "本次期限1～120000ms，默认模块期限")),
            "返回outcome、开始/当前职业与入口变化、各技能及规则、原生资源/入伤事实、怪物HP/经验和收尾。",
            "指定目标死亡、失效或执行终止后，已受理的原生短命令全部结算，导航和交战停止。",
            (module, args) -> {
                int target = argumentInteger((Number) args.get("target_oid"));
                Set<Integer> allowed = args.containsKey("allowed_target_oids")
                        ? ((List<?>) args.get("allowed_target_oids")).stream().map(CombatActionModule::integer)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of(target);
                var choice = CombatRequest.Choice.valueOf((String) args.getOrDefault("choice", "AUTO"));
                int timeout = args.containsKey("timeout_ms") ? argumentInteger((Number) args.get("timeout_ms")) : module.config.timeoutMs();
                return new CombatRequest(target, allowed, choice, timeout);
            }, (module, context, work, input) -> module.execute(input, context, work), CombatResult::toMap);
    public static final ModuleDefinition<Config, CombatActionModule> DEFINITION = new ModuleDefinition<>(
            "combat", Set.of("combat-action"), new ExecutionScope.Limits(16, 2, 4, 2),
            config -> new CombatActionModule(config, Server.getInstance()), List.of(ENGAGE));

    private final Config config;
    private final Server server;
    private CombatJobTree tree;
    private Job treeJob;
    private final Map<Integer, Long> nextBuffAt = new LinkedHashMap<>();
    private long nextBuff, nextAttack, nextUltimate;
    private Task active;

    public CombatActionModule(Config config, Server server) { this.config = config; this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        if (values.keySet().stream().anyMatch(key -> !key.equals("timeout_ms"))) {
            throw new IllegalArgumentException("unknown_combat_config");
        }
        int timeout = Integer.parseInt(values.getOrDefault("timeout_ms", DEFAULT_CONFIG.timeoutMs()).toString());
        if (timeout < 1 || timeout > 120_000) throw new IllegalArgumentException("invalid_combat_timeout");
        return new Config(timeout);
    }

    /** Pure single-target geometry; resource/cooldown readiness remains the action's responsibility. */
    public static boolean inAttackRange(Character actor, Monster target) {
        var current = new CombatJobTree(actor.getJob());
        var weapon = BotAttack.resolveEquippedWeaponType(actor);
        var selected = current.resolve(AttackKind.SINGLE, weapon, target.getStats().isUndead(), actor);
        if (selected.isEmpty()) return false;
        var plan = selected.get();
        return plan.nativeRules() ? AbilitySceneModule.canHit(actor, target, plan.nativePlan(), actor.getPosition())
                : BotAttackDriver.canHitFrom(actor, target, plan.profile(), weapon, actor.getPosition());
    }

    /** The shared directory has already validated INTEGER type, finiteness and integrality. */
    private static int argumentInteger(Number value) {
        if (value.doubleValue() < Integer.MIN_VALUE || value.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("combat_integer_out_of_range");
        }
        return value.intValue();
    }

    private static int integer(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue()) || number.doubleValue() < 1
                || number.doubleValue() > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid_combat_integer");
        return number.intValue();
    }

    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext context) {
        treeJob = context.characterRef().character().getJob();
        tree = new CombatJobTree(treeJob);
    }

    @Override public synchronized void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        if (active != null) active.stopRequested = true;
    }

    public CombatResult execute(CombatRequest input, CharacterRuntime.ModuleContext context,
                                           ExecutionScope.WorkHandle work) {
        var actor = context.characterRef();
        var task = new Task(input, context, work, actor.character());
        synchronized (this) {
            if (active != null) return result(task, Outcome.BUSY, "combat_busy");
            active = task;
        }
        Outcome outcome;
        String reason = null;
        try {
            task.scene = NativeContextChecks.captureScene(server, actor);
            task.target = task.scene.map().getMonsterByOid(input.targetOid());
            if (task.target == null || !task.target.isAlive()) {
                outcome = Outcome.TARGET_INVALID;
            } else {
                task.sceneState = SceneRuntimeManager.current(server, actor).characterState();
                task.contactsBefore = task.sceneState.contactSnapshot().count();
                while (true) {
                    checkpoint(task);
                    var tick = tick(task);
                    if (tick.outcome() != null) { outcome = tick.outcome(); reason = tick.reason(); break; }
                    if (tick.approach() != null) {
                        task.navigationCalls++;
                        var navigation = context.call(NavigationActionModule.MOVE_TO,
                                new MoveToRequest(tick.approach().x, tick.approach().y,
                                        Math.max(1, task.input.timeoutMs() - (int) TimeUnit.NANOSECONDS.toMillis(
                                                System.nanoTime() - task.started))), work);
                        checkpoint(task);
                        if (navigation.status() == ModuleCall.Status.UNAVAILABLE) {
                            outcome = Outcome.UNAVAILABLE; reason = "navigation_unavailable"; break;
                        }
                        var navigationResult = navigation.result();
                        if (navigationResult.outcome() != NavigationResult.Outcome.ARRIVED) {
                            outcome = switch (navigationResult.outcome()) {
                                case CANCELLED -> Outcome.CANCELLED;
                                case TIMED_OUT -> Outcome.TIMED_OUT;
                                default -> Outcome.UNREACHABLE;
                            };
                            reason = "navigation_" + (navigationResult.reason() == null ? navigationResult.outcome().name()
                                    : navigationResult.reason()).toLowerCase(java.util.Locale.ROOT);
                            break;
                        }
                    } else {
                        try { Thread.sleep(50); }
                        catch (InterruptedException interrupted) {
                            task.interrupted = true;
                            throw new Halt(Outcome.CANCELLED, "combat_cancelled");
                        }
                    }
                }
            }
        } catch (Halt stopped) {
            outcome = stopped.outcome; reason = stopped.getMessage();
        } catch (CancellationException cancelled) {
            outcome = Outcome.CANCELLED; reason = "combat_cancelled";
            task.interrupted |= Thread.interrupted();
        } catch (GameContext.Invalid changed) {
            outcome = Outcome.CONTEXT_CHANGED; reason = changed.reason().name();
        } finally {
            synchronized (this) { active = null; }
            if (task.interrupted) Thread.currentThread().interrupt();
        }
        return result(task, outcome, reason);
    }

    private CombatResult result(Task task, Outcome outcome, String reason) {
        var contacts = task.sceneState == null ? null : task.sceneState.contactSnapshot();
        int contactCount = contacts == null ? 0 : (int) (contacts.count() - task.contactsBefore);
        return new CombatResult(outcome, reason, task.initialJob.getId(), tree.activePath().stream().map(Job::getId).toList(),
                tree.buffs(task.actor), List.copyOf(task.buffsCast), task.attacks.entrySet().stream().map(entry -> new CombatResult.AttackUse(
                        entry.getKey().providerJob(), entry.getKey().kind(), entry.getKey().skillId(),
                        entry.getKey().route(), entry.getValue(), entry.getKey().skillLevel(), entry.getKey().rules())).toList(),
                task.hits.values().stream().map(hit -> new CombatResult.Hit(hit.objectId, hit.monsterId,
                        hit.hpBefore, hit.hpAfter, hit.rolledDamage, hit.killed)).toList(), task.navigationCalls,
                task.levelBefore, task.actor.getLevel(), task.expBefore, task.actor.getExp(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - task.started),
                task.usedNative && task.usedLegacy ? "mixed_native_and_legacy_combat_v1"
                        : task.usedNative || CombatJobTree.nativeMode(task.actor)
                        ? "native_skill_combat_first_v1" : "legacy_simulated_combat_v1",
                task.actor.getJob().getId(), List.copyOf(task.jobChanges),
                task.hpBefore, task.actor.getHp(), task.mpBefore, task.actor.getMp(),
                List.copyOf(task.nativeAttacks.values()), List.copyOf(task.nativeBuffs.values()),
                contactCount == 0 ? List.of() : List.of(contacts.latest()),
                task.nativeAttackAttempts, task.nativeBuffAttempts, contactCount);
    }

    /** Every native observation, buff or strike executes as a new short work on this role's lane. */
    private Tick tick(Task task) {
        var value = new AtomicReference<Tick>();
        var failure = new AtomicReference<Throwable>();
        var step = task.context.acceptWork();
        Runnable body = () -> {
            try {
                checkpoint(task);
                if (!task.target.isAlive()) { value.set(new Tick(Outcome.TARGET_DEAD, null, null)); return; }
                if (task.scene.map().getMonsterByOid(task.input.targetOid()) != task.target) {
                    value.set(new Tick(Outcome.TARGET_INVALID, "target_replaced_or_removed", null)); return;
                }
                var actor = task.actor;
                if (!actor.isAlive()) { value.set(new Tick(Outcome.DEAD, "actor_dead", null)); return; }
                if (actor.getJob() != treeJob) {
                    treeJob = actor.getJob();
                    tree = new CombatJobTree(treeJob);
                    nextBuffAt.clear();
                    nextBuff = nextAttack = nextUltimate = 0;
                    task.jobChanges.add(treeJob.getId());
                }
                long now = System.nanoTime();
                boolean nativeRules = CombatJobTree.nativeMode(actor);
                task.usedNative |= nativeRules;
                task.usedLegacy |= !nativeRules;
                var availableBuffs = tree.buffs(actor);
                if (now >= nextBuff) {
                    var due = availableBuffs.stream().filter(skill -> now >= nextBuffAt.getOrDefault(skill, 0L)).findFirst();
                    if (due.isPresent()) {
                        int skill = due.get();
                        int duration;
                        boolean applied;
                        if (nativeRules) {
                            checkpoint(task);
                            var binding = SceneRuntimeManager.current(server, task.scene.actor());
                            var buff = binding.scene().characters().attack(binding).buff(server, task.work, skill);
                            task.nativeBuffAttempts++;
                            task.nativeBuffs.put(skill, buff);
                            if (buff.handlerError()) throw new Halt(Outcome.INDETERMINATE, "native_buff_handler_failed");
                            applied = buff.outcome() == NativeCombatActions.Outcome.APPLIED;
                            duration = buff.durationMs();
                        } else {
                            duration = BotBuffEffects.castBuff(actor, skill);
                            applied = true;
                        }
                        if (applied) {
                            task.buffsCast.add(skill);
                            nextBuffAt.put(skill, now + TimeUnit.MILLISECONDS.toNanos(duration > 0 ? duration * 9L / 10 : 60_000));
                        }
                        nextBuff = now + TimeUnit.MILLISECONDS.toNanos(750);
                    }
                }
                WeaponType weapon = BotAttack.resolveEquippedWeaponType(actor);
                boolean facingLeft = task.target.getPosition().x < actor.getPosition().x;
                var single = tree.resolve(AttackKind.SINGLE, weapon, false, actor);
                var aoe = tree.resolve(AttackKind.AOE, weapon, false, actor);
                var ultimate = tree.resolve(AttackKind.ULTIMATE, weapon, false, actor);
                Optional<AttackPlan> selected;
                if (task.input.choice() != CombatRequest.Choice.AUTO) {
                    selected = tree.resolve(AttackKind.valueOf(task.input.choice().name()), weapon, false, actor);
                } else {
                    var heal = task.target.getStats().isUndead() ? tree.resolve(AttackKind.AOE, weapon, true, actor) : Optional.<AttackPlan>empty();
                    if (heal.isPresent() && heal.get().undeadOnly()) selected = heal;
                    else {
                        var pack = ultimate.isPresent() && now >= nextUltimate ? ultimate : aoe;
                        if (pack.isPresent() && targets(task, pack.get(), weapon, facingLeft).size() >= 2) selected = pack;
                        else selected = single.isPresent() ? single : (aoe.isPresent() ? aoe : ultimate);
                    }
                }
                if (selected.isEmpty()) { value.set(new Tick(Outcome.NO_ATTACK, "attack_category_unavailable", null)); return; }
                var plan = selected.get();
                var targets = targets(task, plan, weapon, facingLeft);
                if (!targets.contains(task.target)) {
                    int offset = Math.max(10, Math.min(plan.reachX() / 2, 160));
                    var targetPosition = task.target.getPosition();
                    for (int distance : List.of(offset, 0)) {
                        int x = targetPosition.x + (actor.getPosition().x > targetPosition.x ? distance : -distance);
                        Point ground = RuntimeNavigationEngine.groundPoint(task.scene.map(), new Point(x, targetPosition.y));
                        if (ground == null) ground = RuntimeNavigationEngine.groundPoint(task.scene.map(),
                                new Point(x, targetPosition.y - plan.reachY()));
                        if (ground != null && Math.abs(ground.y - targetPosition.y) <= plan.reachY()
                                && (plan.nativeRules() ? AbilitySceneModule.canHit(actor, task.target, plan.nativePlan(), ground)
                                : BotAttackDriver.canHitFrom(actor, task.target, plan.profile(), weapon, ground))) {
                            value.set(new Tick(null, null, ground)); return;
                        }
                    }
                    value.set(new Tick(Outcome.UNREACHABLE, "target_outside_attack_geometry", null));
                    return;
                }
                if (now < nextAttack || plan.kind() == AttackKind.ULTIMATE && now < nextUltimate) {
                    value.set(new Tick(null, null, null)); return;
                }
                List<BotAttackDriver.HitFact> hitFacts;
                int skillId, skillLevel;
                String route, rule;
                if (plan.nativeRules()) {
                    checkpoint(task);
                    var binding = SceneRuntimeManager.current(server, task.scene.actor());
                    var strike = binding.scene().characters().attack(binding)
                            .strike(server, task.work, plan.nativePlan(), targets, facingLeft);
                    task.nativeAttackAttempts++;
                    task.nativeAttacks.put(strike.skillId(), strike);
                    if (strike.handlerError()) throw new Halt(Outcome.INDETERMINATE, "native_attack_handler_failed");
                    nextAttack = now + TimeUnit.MILLISECONDS.toNanos(plan.nativePlan().intervalMs());
                    if (!strike.dispatched()) { value.set(new Tick(null, strike.reason(), null)); return; }
                    hitFacts = strike.hits();
                    skillId = strike.skillId(); skillLevel = strike.skillLevel();
                    route = plan.nativePlan().route().name(); rule = strike.policy();
                } else {
                    var binding = SceneRuntimeManager.current(server, task.scene.actor());
                    var strike = binding.scene().characters().attack(binding).strikeLegacy(task.work, plan.profile(),
                            targets, weapon, facingLeft, tree.buffs().contains(Hermit.SHADOW_PARTNER));
                    nextAttack = now + TimeUnit.MILLISECONDS.toNanos(plan.profile().cooldownMs);
                    hitFacts = strike.hits(); skillId = strike.skillId(); skillLevel = plan.profile().skillLevel;
                    route = strike.route().name(); rule = "legacy_simulated_combat_v1";
                }
                if (plan.kind() == AttackKind.ULTIMATE) nextUltimate = now + TimeUnit.SECONDS.toNanos(25);
                var key = new AttackKey(plan.providerJob().getId(), plan.kind().name(), skillId, route, skillLevel, rule);
                task.attacks.merge(key, 1, Integer::sum);
                for (var hit : hitFacts) {
                    var old = task.hits.get(hit.objectId());
                    if (old == null) task.hits.put(hit.objectId(), new HitTotal(hit));
                    else { old.hpAfter = hit.hpAfter(); old.rolledDamage += hit.rolledDamage(); old.killed |= hit.killed(); }
                }
                value.set(new Tick(task.target.isAlive() ? null : Outcome.TARGET_DEAD, null, null));
            } catch (RuntimeException | Error stopped) { failure.set(stopped); }
        };
        try { task.context.submit("combat-action", step, () -> {}, body, () -> {}); }
        catch (RuntimeException rejected) { step.reject(); throw rejected; }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw (RuntimeException) failure.get();
        if (value.get() == null) throw new Halt(Outcome.CANCELLED, "combat_step_cancelled");
        return value.get();
    }

    private static List<Monster> targets(Task task, AttackPlan plan, WeaponType weapon, boolean facingLeft) {
        var candidates = plan.nativeRules() ? task.scene.map().getAllMonsters().stream()
                .filter(monster -> AbilitySceneModule.canHit(task.actor, monster, plan.nativePlan(), task.actor.getPosition()))
                : BotAttackDriver.mobsInReach(task.actor, plan.profile(), weapon, facingLeft).stream();
        return candidates
                .filter(monster -> task.input.allowedTargetOids().contains(monster.getObjectId()))
                .filter(monster -> !plan.undeadOnly() || monster.getStats().isUndead())
                .sorted(Comparator.comparingInt(monster -> monster == task.target ? 0 : 1))
                .limit(plan.maximumTargets()).toList();
    }

    private void checkpoint(Task task) {
        if (Thread.interrupted()) { task.interrupted = true; task.stopRequested = true; }
        if (task.stopRequested || task.work.cancelRequested()) throw new Halt(Outcome.CANCELLED, "combat_cancelled");
        if (System.nanoTime() >= task.deadline) {
            throw new Halt(Outcome.TIMED_OUT, "combat_timeout");
        }
        NativeContextChecks.requireSceneCurrent(server, task.scene);
    }

    private record Tick(Outcome outcome, String reason, Point approach) { }
    private record AttackKey(int providerJob, String kind, int skillId, String route, int skillLevel, String rules) { }
    private static final class Halt extends RuntimeException {
        final Outcome outcome;
        Halt(Outcome outcome, String reason) { super(reason, null, false, false); this.outcome = outcome; }
    }
    private static final class HitTotal {
        final int objectId, monsterId, hpBefore;
        int hpAfter;
        long rolledDamage;
        boolean killed;
        HitTotal(BotAttackDriver.HitFact hit) {
            objectId = hit.objectId(); monsterId = hit.monsterId(); hpBefore = hit.hpBefore();
            hpAfter = hit.hpAfter(); rolledDamage = hit.rolledDamage(); killed = hit.killed();
        }
    }
    private static final class Task {
        final CombatRequest input;
        final CharacterRuntime.ModuleContext context;
        final ExecutionScope.WorkHandle work;
        final Character actor;
        final long started = System.nanoTime();
        final long deadline;
        final int levelBefore, expBefore;
        final int hpBefore, mpBefore;
        final Job initialJob;
        final List<Integer> buffsCast = new ArrayList<>();
        final List<Integer> jobChanges = new ArrayList<>();
        final Map<AttackKey, Integer> attacks = new LinkedHashMap<>();
        final Map<Integer, HitTotal> hits = new LinkedHashMap<>();
        final Map<Integer, NativeCombatActions.StrikeResult> nativeAttacks = new LinkedHashMap<>();
        final Map<Integer, NativeCombatActions.BuffResult> nativeBuffs = new LinkedHashMap<>();
        SceneCharacterState sceneState;
        long contactsBefore;
        GameContext.SceneRef scene;
        Monster target;
        int navigationCalls, nativeAttackAttempts, nativeBuffAttempts;
        volatile boolean stopRequested;
        boolean interrupted;
        boolean usedNative, usedLegacy;
        Task(CombatRequest input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work, Character actor) {
            this.input = input; this.context = context; this.work = work; this.actor = actor;
            levelBefore = actor.getLevel(); expBefore = actor.getExp();
            hpBefore = actor.getHp(); mpBefore = actor.getMp(); initialJob = actor.getJob();
            deadline = started + TimeUnit.MILLISECONDS.toNanos(input.timeoutMs());
        }
    }
}
