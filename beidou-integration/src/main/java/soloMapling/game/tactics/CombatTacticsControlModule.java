package soloMapling.game.tactics;

import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.GCMoveSystem.RuntimeNavigationEngine;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationPlan;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ControlModule;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ModuleTask;
import soloMapling.game.scene.AbilitySceneModule;
import soloMapling.game.scene.IncomingDamageEstimate;
import soloMapling.game.scene.SceneActionDispatch;
import soloMapling.game.scene.SceneActorBinding;
import soloMapling.game.scene.SceneRuntimeManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Independent persistent observation and finite tactical movements, on the role's original scope and lane. */
public final class CombatTacticsControlModule implements ControlModule {
    public static final String CONTROL = "combat-tactics-control";
    public record Config(int checkIntervalMs, int predictionWindowMs, int reserveHpPct, int localEscapeDistancePx) { }
    public static final Config DEFAULT_CONFIG = new Config(100, 1000, 30, 128);
    public enum State { IDLE, RUNNING, STOPPING }
    public record Status(State state, TacticalDirective.Action action, String reason, long observations,
                         int engagementTargetOid, IncomingDamageEstimate risk, String escapeOutcome) {
        public Map<String, Object> toMap() {
            var values = new LinkedHashMap<String, Object>();
            values.put("state", state.name()); values.put("action", action.name()); values.put("reason", reason);
            values.put("observations", observations); values.put("engagement_target_oid", engagementTargetOid);
            values.put("escape_outcome", escapeOutcome);
            if (risk != null) values.put("risk", risk.toMap());
            return values;
        }
    }
    public record StartResult(Outcome outcome, Status status) {
        public enum Outcome { STARTED, ALREADY_RUNNING, BUSY, UNAVAILABLE }
        public Map<String, Object> toMap() { var map = status.toMap(); map.put("outcome", outcome.name()); return map; }
    }
    public record StopResult(Outcome outcome, Status status) {
        public enum Outcome { STOPPED, ALREADY_IDLE }
        public Map<String, Object> toMap() { var map = status.toMap(); map.put("outcome", outcome.name()); return map; }
    }
    public static final ModuleFunction<CombatTacticsControlModule, Void, StartResult> START = new ModuleFunction<>(
            "combatTactics.start", "启动本模块常驻战术观察。", List.of(), "返回启动结果及当前状态。",
            "模块自己的受管观察载体已启动，脚本返回后保持运行。", (module, args) -> null,
            (module, context, work, input) -> module.start(), StartResult::toMap);
    public static final ModuleFunction<CombatTacticsControlModule, Void, StopResult> STOP = new ModuleFunction<>(
            "combatTactics.stop", "停止本模块意图并等待实际收尾。", List.of(), "返回停止结果及当前状态。",
            "本模块观察、有限运动及原工作真实退出。", (module, args) -> null,
            (module, context, work, input) -> module.stop(), StopResult::toMap);
    public static final ModuleFunction<CombatTacticsControlModule, EngagementCandidate, EngagementAdmission> ASSESS = new ModuleFunction<>(
            "combatTactics.assessEngagement", "按当前真实目标与同源生命预算只读评估交战准入。",
            List.of(new ModuleFunction.Parameter("target_oid", ModuleFunction.Type.INTEGER, true, "当前场景的目标OID")),
            "返回ALLOWED、WAIT或UNAVAILABLE与有界风险。", "只读评估完成，不启动交战或输入。",
            (module, args) -> module.candidate(((Number) args.get("target_oid")).intValue()),
            (module, context, work, input) -> module.assessEngagement(input), EngagementAdmission::toMap);
    public static final ModuleDefinition<Config, CombatTacticsControlModule> DEFINITION = new ModuleDefinition<>(
            CONTROL, Set.of(CONTROL), new ExecutionScope.Limits(16, 2, 12, 2),
            config -> new CombatTacticsControlModule(config, Server.getInstance()), List.of(START, STOP, ASSESS));

    private final Config config;
    private final Server server;
    private final CombatTacticsPlanner planner = new CombatTacticsPlanner();
    private volatile CharacterRuntime.ModuleContext context;
    private Monitor active;
    private EngagementBinding engagement;
    private Status last = new Status(State.IDLE, TacticalDirective.Action.WAIT, "not_started", 0, 0, null, "NONE");

    public CombatTacticsControlModule(Config config, Server server) { this.config = config; this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        var names = Set.of("check_interval_ms", "prediction_window_ms", "reserve_hp_pct", "local_escape_distance_px");
        if (values.keySet().stream().anyMatch(key -> !names.contains(key))) throw new IllegalArgumentException("unknown_tactics_config");
        int interval = Integer.parseInt(values.getOrDefault("check_interval_ms", DEFAULT_CONFIG.checkIntervalMs()).toString());
        int window = Integer.parseInt(values.getOrDefault("prediction_window_ms", DEFAULT_CONFIG.predictionWindowMs()).toString());
        int reserve = Integer.parseInt(values.getOrDefault("reserve_hp_pct", DEFAULT_CONFIG.reserveHpPct()).toString());
        int distance = Integer.parseInt(values.getOrDefault("local_escape_distance_px", DEFAULT_CONFIG.localEscapeDistancePx()).toString());
        if (interval < 1 || window < 1 || reserve < 0 || reserve > 100 || distance < 1) throw new IllegalArgumentException("invalid_tactics_config");
        return new Config(interval, window, reserve, distance);
    }

    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext current) { context = current; start(); }
    @Override public synchronized void onStopping(CharacterRuntime.ModuleContext current, CharacterRuntime.StopMode mode) {
        revokeEngagement();
        if (active != null) { active.state = State.STOPPING; active.task.cancel(); if (active.move != null) active.move.task.cancel(); }
    }
    @Override public synchronized void onUnloaded() { context = null; }

    public synchronized StartResult start() {
        if (active != null && active.task.work().isSettled()) finished(active);
        if (active != null) return new StartResult(active.state == State.STOPPING ? StartResult.Outcome.BUSY
                : StartResult.Outcome.ALREADY_RUNNING, status());
        if (context == null || !context.scope().isActive()) return new StartResult(StartResult.Outcome.UNAVAILABLE, last);
        var monitor = new Monitor(context);
        monitor.task = new ModuleTask(context, "combat-tactics-control", work -> run(monitor, work));
        active = monitor;
        monitor.task.completion().whenComplete((ignored, failure) -> finished(monitor));
        monitor.task.start();
        return new StartResult(StartResult.Outcome.STARTED, status());
    }

    public StopResult stop() {
        Monitor monitor;
        synchronized (this) {
            monitor = active;
            if (monitor == null) return new StopResult(StopResult.Outcome.ALREADY_IDLE, last);
            revokeEngagement();
            monitor.state = State.STOPPING; monitor.task.cancel(); if (monitor.move != null) monitor.move.task.cancel();
        }
        monitor.task.await();
        finished(monitor);
        return new StopResult(StopResult.Outcome.STOPPED, status());
    }

    public synchronized Status status() {
        if (active == null) return last;
        var current = active.status;
        return new Status(active.state, current.action(), current.reason(), current.observations(),
                current.engagementTargetOid(), current.risk(), current.escapeOutcome());
    }
    private void revokeEngagement() {
        if (engagement == null) return;
        engagement.attached = false;
        engagement.directive = new TacticalDirective(TacticalDirective.Action.WAIT, "tactics_unavailable", null, System.nanoTime(), null);
        engagement.context.work().scope().requestCancel(engagement.context.work());
        engagement = null;
    }
    public synchronized EngagementBinding attachEngagement(EngagementContext input) {
        if (active == null || active.state != State.RUNNING || !context.scope().isActive()) return null;
        if (engagement != null && engagement.attached) throw new IllegalStateException("tactics_engagement_busy");
        engagement = new EngagementBinding(input);
        return engagement;
    }
    public synchronized void updateAttackProposal(EngagementBinding binding, AttackProposal proposal) {
        if (binding == engagement && binding.attached) binding.proposal = proposal;
    }
    public synchronized TacticalDirective directive(EngagementBinding binding) {
        if (binding != engagement || !binding.attached || active == null || active.state != State.RUNNING) {
            return new TacticalDirective(TacticalDirective.Action.WAIT, "tactics_unavailable", null, System.nanoTime(), null);
        }
        var directive = binding.directive;
        return System.nanoTime() - directive.observedNanos() > TimeUnit.MILLISECONDS.toNanos(2L * config.checkIntervalMs())
                ? new TacticalDirective(TacticalDirective.Action.WAIT, "tactical_observation_stale", null, System.nanoTime(), directive.risk()) : directive;
    }
    public synchronized void detachEngagement(EngagementBinding binding) {
        binding.attached = false;
        if (engagement == binding) engagement = null;
    }

    private EngagementCandidate candidate(int oid) {
        var scene = NativeContextChecks.captureScene(server, context.characterRef());
        return new EngagementCandidate(scene, scene.map().getMonsterByOid(oid), Set.of(oid), null);
    }

    public synchronized EngagementAdmission assessEngagement(EngagementCandidate candidate) {
        if (active == null || active.state != State.RUNNING || context == null || !context.scope().isActive()) {
            return new EngagementAdmission(EngagementAdmission.Outcome.UNAVAILABLE, "tactics_unavailable", null);
        }
        var binding = SceneRuntimeManager.current(server, context.characterRef());
        if (candidate.scene().map() != binding.nativeScene().map() || candidate.target() == null
                || binding.nativeScene().map().getMonsterByOid(candidate.target().getObjectId()) != candidate.target()) {
            return new EngagementAdmission(EngagementAdmission.Outcome.WAIT, "engagement_target_changed", null);
        }
        var actor = binding.characterRef().character();
        var risk = binding.scene().characters().damage(binding).estimateIncoming(config.predictionWindowMs(), actor.getPosition());
        if (active.move != null && !active.move.task.work().isSettled()
                && active.move.priority != SceneActionDispatch.Priority.NORMAL) {
            return new EngagementAdmission(EngagementAdmission.Outcome.WAIT, "escape_in_progress", risk);
        }
        int reserve = (int) ((long) actor.getCurrentMaxHp() * config.reserveHpPct() / 100);
        if (risk.coverage() != IncomingDamageEstimate.Coverage.SUPPORTED) return new EngagementAdmission(EngagementAdmission.Outcome.WAIT, "risk_unknown", risk);
        return new EngagementAdmission(actor.isAlive() && actor.getHp() - risk.hpLossUpper() >= reserve
                ? EngagementAdmission.Outcome.ALLOWED : EngagementAdmission.Outcome.WAIT, "current_life_budget", risk);
    }

    private void run(Monitor monitor, ExecutionScope.WorkHandle work) {
        try {
            while (!work.cancelRequested()) {
                var observed = new AtomicReference<Observation>();
                var failure = new AtomicReference<Throwable>();
                var step = monitor.context.acceptWork(work);
                try {
                    monitor.context.submit(CONTROL, step, () -> {}, () -> {
                        try {
                            var binding = SceneRuntimeManager.current(server, monitor.context.characterRef());
                            var actor = binding.characterRef().character();
                            if (actor.isChangingMaps()) return;
                            EngagementBinding current;
                            synchronized (this) { current = engagement; }
                            observed.set(new Observation(binding, actor.getHp(), actor.getCurrentMaxHp(), new Point(actor.getPosition()), current, System.nanoTime()));
                        } catch (RuntimeException | Error problem) { failure.set(problem); }
                    }, () -> {});
                } catch (RuntimeException rejected) { step.reject(); throw rejected; }
                step.completion().toCompletableFuture().join();
                if (failure.get() instanceof Error fatal) throw fatal;
                if (failure.get() instanceof RuntimeException problem && !sceneTransition(monitor, problem)) throw problem;
                if (observed.get() != null) {
                    try { evaluate(monitor, work, observed.get()); }
                    catch (RuntimeException problem) { if (!sceneTransition(monitor, problem)) throw problem; }
                }
                try { Thread.sleep(config.checkIntervalMs()); }
                catch (InterruptedException cancelled) { break; }
            }
        } catch (CancellationException | GameContext.Invalid | CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (!work.cancelRequested() && monitor.context.scope().isActive()) monitor.reason = "tactical_context_unavailable";
        } finally {
            if (monitor.move != null) { monitor.move.task.cancel(); monitor.move.task.await(); }
            synchronized (this) {
                if (engagement != null) engagement.directive = new TacticalDirective(TacticalDirective.Action.WAIT,
                        "tactics_unavailable", null, System.nanoTime(), null);
            }
        }
    }

    private boolean sceneTransition(Monitor monitor, RuntimeException problem) {
        boolean changed = problem instanceof GameContext.Invalid contextInvalid
                && (contextInvalid.reason() == GameContext.Failure.SCENE_CHANGED || contextInvalid.reason() == GameContext.Failure.EVENT_CHANGED)
                || problem instanceof CharacterRuntime.Invalid runtimeInvalid
                && (runtimeInvalid.reason().equals("scene_membership_unavailable") || runtimeInvalid.reason().equals("scene_membership_ended"));
        if (!changed) return false;
        if (monitor.move != null) monitor.move.task.cancel();
        monitor.status = new Status(State.RUNNING, TacticalDirective.Action.WAIT, "scene_transition", monitor.observations,
                0, null, monitor.escapeOutcome);
        synchronized (this) {
            if (engagement != null) engagement.directive = new TacticalDirective(TacticalDirective.Action.WAIT,
                    "scene_transition", null, System.nanoTime(), null);
        }
        return true;
    }

    private void evaluate(Monitor monitor, ExecutionScope.WorkHandle work, Observation observation) {
        var binding = observation.binding();
        var actor = binding.characterRef().character();
        if (!actor.isAlive() || actor.isChangingMaps()) {
            monitor.status = new Status(State.RUNNING, TacticalDirective.Action.WAIT, "actor_not_actionable", ++monitor.observations,
                    0, null, monitor.escapeOutcome); return;
        }
        if (monitor.move != null && monitor.move.task.work().isSettled()) {
            var completed = monitor.move;
            if (completed.priority != SceneActionDispatch.Priority.NORMAL) {
                var risk = binding.scene().characters().damage(binding).estimateIncoming(config.predictionWindowMs(), actor.getPosition());
                boolean safe = risk.coverage() == IncomingDamageEstimate.Coverage.SUPPORTED
                        && actor.getHp() - risk.hpLossUpper() >= (long) actor.getCurrentMaxHp() * config.reserveHpPct() / 100;
                monitor.escapeOutcome = completed.result != null && completed.result.outcome() == NavigationResult.Outcome.ARRIVED
                        && safe ? "DISENGAGED" : "NO_SAFE_ROUTE";
            }
            monitor.move = null;
        }
        var engagement = observation.engagement();
        var proposal = engagement == null ? null : engagement.proposal;
        boolean engaged = engagement != null && engagement.attached && !engagement.context.work().cancelRequested()
                && engagement.context.candidate().scene().map() == binding.nativeScene().map()
                && engagement.context.candidate().target().isAlive();
        var points = new ArrayList<Point>();
        Point approachPoint = null;
        if (monitor.move != null) points.add(monitor.move.plan.target());
        if (engaged && proposal != null) {
            var target = engagement.context.candidate().target().getPosition();
            int offset = Math.max(10, Math.min(160, proposal.plan().reachX() / 2));
            approachPoint = new Point(target.x + (observation.position().x > target.x ? offset : -offset), target.y);
            points.add(approachPoint);
        }
        points.add(new Point(observation.position().x - config.localEscapeDistancePx(), observation.position().y));
        points.add(new Point(observation.position().x + config.localEscapeDistancePx(), observation.position().y));
        var plans = new ArrayList<NavigationPlan>();
        boolean planningTimedOut = false;
        for (var point : points) {
            if (work.cancelRequested()) return;
            var ground = RuntimeNavigationEngine.groundPoint(binding.nativeScene().map(), point);
            if (ground == null || engaged && engagement.context.candidate().activityBounds() != null
                    && !engagement.context.candidate().activityBounds().contains(ground)) continue;
            boolean chasing = point.equals(approachPoint) || engaged && monitor.move != null
                    && monitor.move.priority == SceneActionDispatch.Priority.NORMAL && point.equals(monitor.move.plan.target());
            long budget = chasing ? TimeUnit.NANOSECONDS.toMillis(engagement.context.deadlineNanos() - System.nanoTime())
                    : Math.max(1000L, config.predictionWindowMs() * 4L);
            if (budget < 1) continue;
            var planned = monitor.context.call(NavigationActionModule.PLAN,
                    new MoveToRequest(ground.x, ground.y, (int) Math.min(120_000L, budget)), work);
            if (planned.status() == ModuleCall.Status.SUCCEEDED && planned.result().outcome() == NavigationPlan.Outcome.PLANNED
                    && planned.result().prediction().arrived()) plans.add(planned.result());
            else if (planned.status() == ModuleCall.Status.SUCCEEDED
                    && planned.result().outcome() == NavigationPlan.Outcome.TIMED_OUT) planningTimedOut = true;
        }
        int window = Math.max(config.predictionWindowMs(), (int) binding.dispatch().actionLockRemainingMs());
        if (proposal != null) window = Math.max(window, proposal.actionDurationMs());
        for (var plan : plans) window = Math.max(window, plan.prediction().elapsedMs());
        var damage = binding.scene().characters().damage(binding);
        var standingRisk = damage.estimateIncoming(window, observation.position());
        var candidates = new ArrayList<CombatTacticsPlanner.Candidate>();
        boolean canAttack = engaged && proposal != null && canHit(actor, engagement.context.candidate().target(), proposal, observation.position());
        candidates.add(new CombatTacticsPlanner.Candidate(observation.position(), null, standingRisk, canAttack, false));
        for (var plan : plans) {
            int elapsed = 0;
            var path = new ArrayList<IncomingDamageEstimate.PathSample>();
            path.add(new IncomingDamageEstimate.PathSample(plan.start(), 0));
            for (var frame : plan.prediction().frames()) {
                elapsed += frame.durationMs(); path.add(new IncomingDamageEstimate.PathSample(frame.position(), elapsed));
            }
            var risk = damage.estimateIncoming(window, path);
            if (plan.prediction().frames().stream().anyMatch(frame -> frame.mode() != RuntimeNavigationEngine.Mode.IDLE
                    && frame.mode() != RuntimeNavigationEngine.Mode.WALK)) {
                var missing = new ArrayList<>(risk.missing()); missing.add("tactical_route_mode_unsupported");
                risk = new IncomingDamageEstimate(IncomingDamageEstimate.Coverage.PARTIAL, risk.observedNanos(), window,
                        risk.hpLossLower(), risk.hpLossUpper(), risk.threats(), missing);
            }
            boolean approach = engaged && proposal != null && canHit(actor, engagement.context.candidate().target(), proposal, plan.target());
            candidates.add(new CombatTacticsPlanner.Candidate(plan.target(), plan, risk, approach, approach));
        }
        var decision = planner.decide(observation.hp(), observation.maxHp(), config.reserveHpPct(), engaged,
                proposal != null && System.nanoTime() >= proposal.availableAtNanos() && binding.dispatch().actionLockRemainingMs() == 0, candidates);
        if (planningTimedOut && decision.action() == TacticalDirective.Action.WAIT) {
            decision = new CombatTacticsPlanner.Decision(TacticalDirective.Action.WAIT, "navigation_plan_timeout", null,
                    decision.priority(), decision.risk());
        }
        if (binding.dispatch().currentPriority() == SceneActionDispatch.Priority.EQUIPMENT_PICKUP
                && decision.priority() != SceneActionDispatch.Priority.LETHAL_ESCAPE) {
            decision = new CombatTacticsPlanner.Decision(TacticalDirective.Action.DISENGAGE, "equipment_pickup_preempted", null,
                    SceneActionDispatch.Priority.NORMAL, standingRisk);
        }
        if (decision.movement() != null) {
            var selected = decision.movement().route();
            if (monitor.move != null && !monitor.move.task.work().isSettled()
                    && (monitor.move.priority != decision.priority() || monitor.move.plan.scene().map() != binding.nativeScene().map()
                    || monitor.move.plan.target().distance(selected.target()) > 8)) monitor.move.task.cancel();
            synchronized (this) {
                if (active == monitor && monitor.state == State.RUNNING && !work.cancelRequested() && monitor.move == null) {
                    var move = new Movement(binding, selected, decision.priority(), engaged ? engagement.context.candidate().target() : null);
                    move.task = new ModuleTask(monitor.context, "tactical-movement", movementWork -> move(monitor, move, movementWork));
                    monitor.move = move; move.task.start();
                }
            }
        } else if (monitor.move != null && !monitor.move.task.work().isSettled()) {
            if (decision.action() == TacticalDirective.Action.ATTACK || decision.action() == TacticalDirective.Action.DISENGAGE
                    || !engaged && monitor.move.priority == SceneActionDispatch.Priority.NORMAL) monitor.move.task.cancel();
        }
        if (engaged) engagement.directive = new TacticalDirective(decision.action(), decision.reason(), null, observation.observedNanos(), decision.risk());
        monitor.status = new Status(State.RUNNING, decision.action(), decision.reason(), ++monitor.observations,
                engaged ? engagement.context.candidate().target().getObjectId() : 0, decision.risk(), monitor.escapeOutcome);
    }

    private static boolean canHit(Character actor, Monster target, AttackProposal proposal, Point from) {
        return proposal.plan().nativeRules() ? AbilitySceneModule.canHit(actor, target, proposal.plan().nativePlan(), from)
                : BotAttackDriver.canHitFrom(actor, target, proposal.plan().profile(), BotAttack.resolveEquippedWeaponType(actor), from);
    }

    private void move(Monitor monitor, Movement move, ExecutionScope.WorkHandle work) {
        try {
            var request = move.binding.dispatch().request(move.priority, monitor.context, work, move.target, "tactical_movement");
            move.arrangement = request.arrangement();
            if (move.arrangement == null) { move.result = new NavigationResult(NavigationResult.Outcome.BUSY, request.reason(), null); return; }
            while (!move.arrangement.current()) {
                if (!move.arrangement.valid() || work.cancelRequested()) return;
                try { Thread.sleep(config.checkIntervalMs()); }
                catch (InterruptedException cancelled) { return; }
            }
            move.result = move.binding.scene().characters().motion(move.binding)
                    .executePlan(move.plan, monitor.context, work, move.arrangement, CONTROL);
        } finally { move.binding.dispatch().release(move.arrangement, "tactical_movement_finished"); }
    }

    private synchronized void finished(Monitor monitor) {
        if (active != monitor) return;
        last = new Status(State.IDLE, TacticalDirective.Action.WAIT, monitor.reason, monitor.observations, 0,
                monitor.status.risk(), monitor.escapeOutcome);
        active = null;
    }
    private record Observation(SceneActorBinding binding, int hp, int maxHp, Point position,
                               EngagementBinding engagement, long observedNanos) { }
    private static final class Monitor {
        final CharacterRuntime.ModuleContext context;
        ModuleTask task;
        volatile State state = State.RUNNING;
        volatile Movement move;
        volatile long observations;
        volatile String reason = "stopped";
        volatile String escapeOutcome = "NONE";
        volatile Status status = new Status(State.RUNNING, TacticalDirective.Action.WAIT, "initializing", 0, 0, null, "NONE");
        Monitor(CharacterRuntime.ModuleContext context) { this.context = context; }
    }
    private static final class Movement {
        final SceneActorBinding binding;
        final NavigationPlan plan;
        final SceneActionDispatch.Priority priority;
        final Monster target;
        ModuleTask task;
        volatile SceneActionDispatch.Arrangement arrangement;
        volatile NavigationResult result;
        Movement(SceneActorBinding binding, NavigationPlan plan, SceneActionDispatch.Priority priority, Monster target) {
            this.binding = binding; this.plan = plan; this.priority = priority; this.target = target;
        }
    }
}
