package soloMapling.game.hunting;

import org.gms.net.server.Server;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindStyle;
import soloMapling.ArtificialPlayer.BotGrindSystem.HuntingPlanner;
import soloMapling.ArtificialPlayer.BotGrindSystem.SpotFinder;
import soloMapling.game.combat.CombatActionModule;
import soloMapling.game.combat.CombatRequest;
import soloMapling.game.combat.CombatResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.action.NativeItemActions;
import soloMapling.game.loot.LootActionModule;
import soloMapling.game.loot.LootRequest;
import soloMapling.game.loot.LootResult;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ControlModule;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ModuleTask;

import java.math.BigDecimal;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Starts and stops persistent, scene-bound hunting; concrete execution remains in the action providers. */
public final class HuntingControlModule implements ControlModule {
    public enum State { IDLE, HUNTING, STOPPING }
    public record Config(HuntingStartOptions.Style style) { }
    public static final Config DEFAULT_CONFIG = new Config(HuntingStartOptions.Style.AUTO);
    private static final int CHECK_INTERVAL_MS = 250;
    private static final int ACTION_TIMEOUT_MS = 30_000;
    private static final int LOOT_SETTLE_MS = 1_500;
    private static final int LOOT_SCAN_INTERVAL_MS = 1_000;
    private static final int LOOT_SEARCH_PX = 900;
    private static final int LOOT_SAME_LEDGE_Y = 120;
    public static final ModuleFunction<HuntingControlModule, HuntingStartOptions, HuntingStartResult> START =
            new ModuleFunction<>("hunting.start", "开始一次当前场景内的连续狩猎，返回后活动独立于调用脚本继续。",
                    List.of(new ModuleFunction.Parameter("style", ModuleFunction.Type.STRING, false,
                                    "AUTO/CAMP/PATROL/ROAM/STACK；省略使用模块配置"),
                            new ModuleFunction.Parameter("duration_ms", ModuleFunction.Type.INTEGER, false,
                                    "可选正整数毫秒，包含寻怪、等待和交战；省略持续至停止或其他结束条件")),
                    "返回STARTED/BUSY/UNAVAILABLE/DEAD、活动状态、条件与安全摘要。",
                    "本活动已建立并完成原生场景/能力准入后返回；后续工作继续归狩猎模块。",
                    (module, arguments) -> new HuntingStartOptions(arguments.containsKey("style")
                            ? HuntingStartOptions.Style.valueOf((String) arguments.get("style")) : null,
                            arguments.containsKey("duration_ms")
                                    ? new BigDecimal(arguments.get("duration_ms").toString()).longValueExact() : null),
                    (module, context, work, options) -> module.start(options), HuntingStartResult::toMap);
    public static final ModuleFunction<HuntingControlModule, Void, HuntingStopResult> STOP =
            new ModuleFunction<>("hunting.stop", "结束当前狩猎活动及其动作调用，保留模块与角色。",
                    List.of(), "返回STOPPED/ALREADY_IDLE与已结束活动的原因和摘要。",
                    "本次活动、下游工作及驻点登记实际收尾后返回。", (module, arguments) -> null,
                    (module, context, work, ignored) -> module.stop(), HuntingStopResult::toMap);
    public static final ModuleDefinition<Config, HuntingControlModule> DEFINITION = new ModuleDefinition<>(
            "hunting-control", Set.of("hunting-observe"), new ExecutionScope.Limits(8, 2, 2, 1),
            config -> new HuntingControlModule(config, Server.getInstance()), List.of(START, STOP));

    private final Config config;
    private final Server server;
    private CharacterRuntime.ModuleContext context;
    private Activity active;
    private long sequence;
    private HuntingSnapshot last = new HuntingSnapshot(State.IDLE, 0, null, null, null,
            0, 0, 0, 0, 0, 0, 0, false, null, null, "not_started", Map.of());

    public HuntingControlModule(Config config, Server server) { this.config = config; this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        if (values.keySet().stream().anyMatch(key -> !key.equals("style"))) {
            throw new IllegalArgumentException("unknown_hunting_config");
        }
        return new Config(HuntingStartOptions.Style.valueOf(values.getOrDefault("style", "AUTO").toString()));
    }

    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext context) {
        if (active != null) finish(active);
        this.context = context;
    }

    @Override public void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        Activity activity;
        synchronized (this) {
            this.context = null;
            activity = active;
        }
        if (activity != null) requestStop(activity, "module_" + mode.name().toLowerCase(java.util.Locale.ROOT));
    }

    public HuntingStartResult start(HuntingStartOptions options) {
        Activity activity;
        synchronized (this) {
            if (active != null) finish(active);
            if (active != null) return new HuntingStartResult(HuntingStartResult.Outcome.BUSY,
                    "hunting_busy", snapshot(active, active.state));
            if (context == null || !context.scope().isActive()) return new HuntingStartResult(
                    HuntingStartResult.Outcome.UNAVAILABLE, "module_not_active", last);
            activity = new Activity(++sequence, context, options.style() == null ? config.style() : options.style(),
                    options.durationMs());
            activity.task = new ModuleTask(context, "hunting-activity", work -> run(activity, work));
            activity.task.completion().whenComplete((ignored, failure) -> finish(activity));
            active = activity;
            activity.task.start();
        }
        var admission = activity.admission.join();
        if (admission != HuntingStartResult.Outcome.STARTED) activity.task.await();
        if (activity.task.work().isSettled()) finish(activity);
        synchronized (this) {
            return new HuntingStartResult(admission, admission == HuntingStartResult.Outcome.STARTED
                    ? "activity_started" : activity.endReason, snapshot(activity, activity.state));
        }
    }

    public HuntingStopResult stop() {
        return stopActivity(null);
    }

    /** A composed controller may stop only the activity it started, including while that caller is stopping. */
    public HuntingStopResult stop(long expectedActivityId) {
        return stopActivity(expectedActivityId);
    }

    private HuntingStopResult stopActivity(Long expectedActivityId) {
        Activity activity;
        synchronized (this) {
            activity = active;
            if (expectedActivityId != null && (activity == null || activity.id != expectedActivityId)) {
                return new HuntingStopResult(HuntingStopResult.Outcome.ACTIVITY_CHANGED,
                        "activity_changed", activity == null ? last : snapshot(activity, activity.state));
            }
            if (activity == null) return new HuntingStopResult(HuntingStopResult.Outcome.ALREADY_IDLE,
                    last.endReason(), last);
        }
        requestStop(activity, "stop_requested");
        activity.task.await();
        finish(activity);
        return new HuntingStopResult(HuntingStopResult.Outcome.STOPPED, activity.endReason,
                snapshot(activity, State.IDLE));
    }

    /** Safe current/recent activity facts for controllers and source-backed diagnostics. */
    public synchronized HuntingSnapshot snapshot() {
        if (active != null) finish(active);
        return active == null ? last : snapshot(active, active.state);
    }

    private void requestStop(Activity activity, String reason) {
        synchronized (activity) {
            if (activity.endReason == null) activity.endReason = reason;
            activity.state = State.STOPPING;
            if (activity.action != null) activity.action.task.cancel();
            if (activity.loot != null) activity.loot.task.cancel();
            activity.task.cancel();
        }
    }

    private void run(Activity activity, ExecutionScope.WorkHandle work) {
        try {
            if (!capabilitiesAvailable(activity.context)) {
                activity.endReason = "required_capability_unavailable";
                activity.admission.complete(HuntingStartResult.Outcome.UNAVAILABLE);
                return;
            }
            activity.scene = observe(activity, () -> NativeContextChecks.captureScene(server,
                    activity.context.characterRef()));
            if (!observe(activity, () -> activity.scene.actor().character().isAlive())) {
                activity.endReason = "dead";
                activity.admission.complete(HuntingStartResult.Outcome.DEAD);
                return;
            }
            // The cached static spawn/geometry profile may be expensive; do that planning outside the short lane.
            SpotFinder.profile(activity.scene.map());
            activity.planner = observe(activity, () -> new HuntingPlanner(activity.scene.actor().character(),
                    activity.requestedStyle == HuntingStartOptions.Style.AUTO ? null
                            : GrindStyle.valueOf(activity.requestedStyle.name()), System.currentTimeMillis()));
            activity.style = activity.planner.style().name();
            activity.placement = activity.planner.snapshot();
            activity.context.recordActivity(work, "started", activity.style);
            activity.admission.complete(HuntingStartResult.Outcome.STARTED);
            while (!work.cancelRequested()) {
                if (activity.durationMs != null && elapsed(activity) >= activity.durationMs) {
                    activity.endReason = "duration_elapsed";
                    break;
                }
                if (!capabilitiesAvailable(activity.context)) {
                    activity.endReason = "required_capability_unavailable";
                    break;
                }
                var action = activity.action;
                if (!observe(activity, () -> activity.scene.actor().character().isAlive())) {
                    activity.endReason = "dead";
                    break;
                }
                if (action != null) {
                    if (!action.task.work().isSettled()) {
                        var reason = observe(activity, () -> activity.planner.cancellationReason(
                                action.decision, System.currentTimeMillis()));
                        if (reason != null) {
                            action.cancelReason = reason;
                            activity.context.recordActivity(work, reason, "REPLAN");
                            action.task.cancel();
                            action.task.await();
                        }
                    }
                    if (action.task.work().isSettled()) {
                        var outcome = action.result.join();
                        activity.action = null;
                        activity.lastTarget = -1;
                        activity.kills += outcome.kills();
                        activity.lootAfterEngagement = action.decision.kind() == HuntingPlanner.Kind.FIGHT;
                        if (outcome.unavailable()) { activity.endReason = "required_capability_unavailable"; break; }
                        if (outcome.endReason() != null) { activity.endReason = outcome.endReason(); break; }
                        observe(activity, () -> {
                            if (action.decision.kind() == HuntingPlanner.Kind.FIGHT
                                    && (outcome.unreachable() || "no_progress".equals(action.cancelReason))) {
                                activity.planner.deferTarget(action.decision.targetOid(), System.currentTimeMillis());
                            }
                            activity.planner.completed(action.decision, outcome.targetKilled(), outcome.succeeded(),
                                    System.currentTimeMillis());
                            activity.placement = activity.planner.snapshot();
                            return true;
                        });
                    }
                } else if (activity.loot != null) {
                    var loot = activity.loot;
                    if (loot.task.work().isSettled()) {
                        var result = loot.result.join();
                        activity.loot = null;
                        recordLoot(activity, result);
                        activity.lootAfterEngagement = false;
                        if (result.outcome() == LootResult.Outcome.INDETERMINATE) {
                            activity.endReason = "loot_indeterminate"; break;
                        }
                        if (result.outcome() == LootResult.Outcome.CONTEXT_CHANGED) {
                            activity.endReason = "context_changed"; break;
                        }
                        if (result.outcome() == LootResult.Outcome.DEAD) {
                            activity.endReason = "dead"; break;
                        }
                    }
                } else if (activity.lootAfterEngagement && launchLoot(activity)) {
                    // A completed engagement gets one collection pass before another combat decision.
                } else {
                    activity.lootAfterEngagement = false;
                    var decision = observe(activity, () -> {
                        var selected = activity.planner.decide(System.currentTimeMillis());
                        activity.placement = activity.planner.snapshot();
                        return selected;
                    });
                    if (decision.kind() == HuntingPlanner.Kind.WAIT) launchLoot(activity);
                    else launch(activity, decision);
                }
                try { Thread.sleep(CHECK_INTERVAL_MS); }
                catch (InterruptedException stopped) { break; }
            }
            if (activity.endReason == null) activity.endReason = "cancelled";
        } catch (GameContext.Invalid changed) {
            if (activity.endReason == null) activity.endReason = "context_changed";
        } catch (CancellationException stopped) {
            if (activity.endReason == null) activity.endReason = "cancelled";
        } catch (CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (!lifecycleRejection(stopped) || !providerStopping(activity.context, work)) throw stopped;
            if (activity.endReason == null) activity.endReason = "cancelled";
        } finally {
            activity.state = State.STOPPING;
            var action = activity.action;
            if (action != null) {
                action.task.cancel();
                action.task.await();
                if (action.result.isDone() && !action.result.isCompletedExceptionally()) {
                    activity.kills += action.result.join().kills();
                }
                activity.action = null;
            }
            var loot = activity.loot;
            if (loot != null) {
                loot.task.cancel();
                loot.task.await();
                if (loot.result.isDone() && !loot.result.isCompletedExceptionally()) recordLoot(activity, loot.result.join());
                activity.loot = null;
            }
            if (activity.planner != null) {
                activity.planner.release();
                activity.placement = activity.planner.snapshot();
                activity.planner = null;
            }
            activity.scene = null;
            if (activity.endReason == null) activity.endReason = "execution_failed";
            activity.elapsedMs = elapsed(activity);
            activity.context.recordActivity(work, activity.endReason, "ENDED");
            activity.admission.complete(HuntingStartResult.Outcome.UNAVAILABLE);
        }
    }

    private static boolean lifecycleRejection(RuntimeException failure) {
        return failure instanceof CharacterRuntime.Invalid runtime
                && Set.of("module_not_active", "stale_module_reference").contains(runtime.reason())
                || failure instanceof ExecutionScope.Invalid scope && "scope_not_active".equals(scope.reason());
    }

    private static boolean providerStopping(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        if (work.cancelRequested() || !context.scope().isActive()) return true;
        // Entry STOPPING is authoritative even while onStopping has not yet cancelled the scope's work.
        try { context.help(); return false; }
        catch (CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (!lifecycleRejection(stopped)) throw stopped;
            return true;
        }
    }

    private boolean launchLoot(Activity activity) {
        if (System.nanoTime() < activity.nextLootScan) return false;
        activity.nextLootScan = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOOT_SCAN_INTERVAL_MS);
        boolean available = activity.context.help().stream().anyMatch(function -> LootActionModule.COLLECT.name().equals(function.get("name"))
                && Boolean.TRUE.equals(function.get("available")));
        if (!available) return false;
        var objects = observe(activity, () -> {
            var actor = activity.scene.actor().character();
            var position = actor.getPosition();
            return activity.scene.map().getMapObjectsInRange(position, (double) LOOT_SEARCH_PX * LOOT_SEARCH_PX,
                            List.of(MapObjectType.ITEM)).stream().map(MapItem.class::cast)
                    .filter(ground -> {
                        ground.lockItem();
                        try {
                            return !ground.isPickedUp() && ground.getMeso() == 0
                                    && NativeItemActions.supportsPickup(ground.getItem())
                                    && System.currentTimeMillis() - ground.getDropTime() >= LOOT_SETTLE_MS
                                    && Math.abs((long) ground.getPosition().y - position.y) <= LOOT_SAME_LEDGE_Y
                                    && ground.canBePickedBy(actor);
                        } finally { ground.unlockItem(); }
                    }).sorted(Comparator.comparingDouble((MapItem ground) -> position.distanceSq(ground.getPosition()))
                            .thenComparingInt(MapItem::getObjectId))
                    .limit(LootRequest.MAX_OBJECTS).map(MapItem::getObjectId).toList();
        });
        if (objects.isEmpty()) return false;
        synchronized (activity) {
            if (activity.task.work().cancelRequested() || activity.state == State.STOPPING) return false;
            var loot = new LootAttempt();
            int timeout = activity.durationMs == null ? ACTION_TIMEOUT_MS
                    : (int) Math.max(1, Math.min(ACTION_TIMEOUT_MS, activity.durationMs - elapsed(activity)));
            loot.task = new ModuleTask(activity.context, "hunting-loot", work -> {
                try {
                    var call = activity.context.call(LootActionModule.COLLECT, new LootRequest(objects, timeout), work);
                    loot.result.complete(call.status() == ModuleCall.Status.UNAVAILABLE
                            ? new LootResult(LootResult.Outcome.UNAVAILABLE, call.reason(), List.of(), 0) : call.result());
                } catch (RuntimeException | Error failure) { loot.result.completeExceptionally(failure); }
            });
            loot.task.completion().whenComplete((ignored, failure) -> {
                if (!loot.result.isDone()) loot.result.complete(new LootResult(LootResult.Outcome.CANCELLED,
                        "loot_not_entered", List.of(), 0));
            });
            activity.loot = loot;
            activity.lootCalls++;
            activity.context.recordActivity(activity.task.work(), "collecting", Integer.toString(objects.size()));
            loot.task.start();
        }
        return true;
    }

    private void recordLoot(Activity activity, LootResult result) {
        activity.collectedObjects += result.collectedObjects();
        activity.collectedQuantity += result.collectedQuantity();
        activity.lootOutcome = result.outcome();
        activity.lootReason = result.reason();
        activity.nextLootScan = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOOT_SCAN_INTERVAL_MS);
        activity.context.recordActivity(activity.task.work(), "loot_finished", result.outcome().name());
    }

    private void launch(Activity activity, HuntingPlanner.Decision decision) {
        var action = new Action(decision);
        synchronized (activity) {
            if (activity.task.work().cancelRequested() || activity.state == State.STOPPING) return;
            int timeout = activity.durationMs == null ? ACTION_TIMEOUT_MS
                    : (int) Math.max(1, Math.min(ACTION_TIMEOUT_MS, activity.durationMs - elapsed(activity)));
            action.task = new ModuleTask(activity.context, "hunting-action", work -> {
                try {
                    if (decision.kind() == HuntingPlanner.Kind.FIGHT) {
                        var call = activity.context.call(CombatActionModule.ENGAGE, new CombatRequest(decision.targetOid(),
                                decision.allowedTargetOids(), CombatRequest.Choice.AUTO, timeout), work);
                        action.result.complete(combatOutcome(call));
                    } else {
                        var point = decision.destination();
                        var call = activity.context.call(NavigationActionModule.MOVE_TO,
                                new MoveToRequest(point.x, point.y, timeout), work);
                        action.result.complete(navigationOutcome(call));
                    }
                } catch (RuntimeException | Error failure) { action.result.completeExceptionally(failure); }
            });
            action.task.completion().whenComplete((ignored, failure) -> {
                if (!action.result.isDone()) action.result.complete(new ActionOutcome(false, false, false, 0, null, false));
            });
            activity.action = action;
            if (decision.kind() == HuntingPlanner.Kind.FIGHT) {
                activity.engagements++;
                if (activity.lastTarget != decision.targetOid()) {
                    activity.lastTarget = decision.targetOid();
                    activity.context.recordActivity(activity.task.work(), "target_selected", Integer.toString(decision.targetOid()));
                }
            } else {
                activity.navigationCalls++;
                activity.context.recordActivity(activity.task.work(), "moving", decision.reason());
            }
            action.task.start();
        }
    }

    private static ActionOutcome combatOutcome(ModuleCall<CombatResult> call) {
        if (call.status() == ModuleCall.Status.UNAVAILABLE) return new ActionOutcome(true, false, false, 0, null, false);
        var result = call.result();
        int killed = (int) result.hits().stream().filter(CombatResult.Hit::killed).count();
        return switch (result.outcome()) {
            case TARGET_DEAD -> new ActionOutcome(false, true, true, killed, null, false);
            case TARGET_INVALID, CANCELLED, TIMED_OUT, BUSY ->
                    new ActionOutcome(false, false, false, killed, null, false);
            case UNREACHABLE -> new ActionOutcome(false, false, false, killed, null, true);
            case CONTEXT_CHANGED -> new ActionOutcome(false, false, false, killed, "context_changed", false);
            case UNAVAILABLE -> new ActionOutcome(true, false, false, killed, null, false);
            case DEAD -> new ActionOutcome(false, false, false, killed, "dead", false);
            case NO_ATTACK -> new ActionOutcome(false, false, false, killed, "no_attack", false);
            case INDETERMINATE -> new ActionOutcome(false, false, false, killed, "combat_indeterminate", false);
        };
    }

    private static ActionOutcome navigationOutcome(ModuleCall<NavigationResult> call) {
        if (call.status() == ModuleCall.Status.UNAVAILABLE) return new ActionOutcome(true, false, false, 0, null, false);
        var result = call.result();
        return new ActionOutcome(false, result.outcome() == NavigationResult.Outcome.ARRIVED, false, 0,
                result.outcome() == NavigationResult.Outcome.CONTEXT_CHANGED ? "context_changed" : null, false);
    }

    private <T> T observe(Activity activity, Supplier<T> read) {
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var entered = new AtomicBoolean();
        var step = activity.context.acceptWork(activity.task.work());
        try {
            activity.context.submit("hunting-observe", step, () -> {}, () -> {
                entered.set(true);
                try {
                    if (activity.task.work().cancelRequested()) throw new CancellationException("hunting_cancelled");
                    if (activity.scene != null) NativeContextChecks.requireSceneCurrent(server, activity.scene);
                    value.set(read.get());
                } catch (RuntimeException | Error stopped) { failure.set(stopped); }
            }, () -> {});
        } catch (RuntimeException rejected) { step.reject(); throw rejected; }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof Error fatal) throw fatal;
        if (failure.get() instanceof RuntimeException unavailable) throw unavailable;
        if (!entered.get()) throw new CancellationException("hunting_work_not_entered");
        return value.get();
    }

    private static boolean capabilitiesAvailable(CharacterRuntime.ModuleContext context) {
        var names = context.help().stream().filter(function -> Boolean.TRUE.equals(function.get("available")))
                .map(function -> (String) function.get("name")).toList();
        return names.contains(CombatActionModule.ENGAGE.name()) && names.contains(NavigationActionModule.MOVE_TO.name());
    }

    private synchronized void finish(Activity activity) {
        if (!activity.task.work().isSettled()) return;
        activity.state = State.IDLE;
        activity.elapsedMs = elapsed(activity);
        if (activity.endReason == null) activity.endReason = "activity_not_entered";
        activity.admission.complete(HuntingStartResult.Outcome.UNAVAILABLE);
        if (active == activity) {
            last = snapshot(activity, State.IDLE);
            active = null;
        }
    }

    private static long elapsed(Activity activity) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - activity.startedAt);
    }

    private static HuntingSnapshot snapshot(Activity activity, State state) {
        return new HuntingSnapshot(state, activity.id, activity.requestedStyle.name(), activity.style,
                activity.durationMs, state == State.IDLE ? activity.elapsedMs : elapsed(activity),
                activity.engagements, activity.kills, activity.navigationCalls,
                activity.lootCalls, activity.collectedObjects, activity.collectedQuantity,
                activity.loot != null,
                activity.lootOutcome, activity.lootReason, activity.endReason, activity.placement);
    }

    private static final class Activity {
        final long id;
        final CharacterRuntime.ModuleContext context;
        final HuntingStartOptions.Style requestedStyle;
        final Long durationMs;
        final long startedAt = System.nanoTime();
        final CompletableFuture<HuntingStartResult.Outcome> admission = new CompletableFuture<>();
        volatile ModuleTask task;
        volatile State state = State.HUNTING;
        volatile GameContext.SceneRef scene;
        volatile HuntingPlanner planner;
        volatile Action action;
        volatile LootAttempt loot;
        volatile String style;
        volatile String endReason;
        volatile Map<String, Object> placement = Map.of();
        volatile long elapsedMs;
        volatile int engagements;
        volatile int kills;
        volatile int navigationCalls;
        volatile int lootCalls;
        volatile int collectedObjects;
        volatile int collectedQuantity;
        volatile LootResult.Outcome lootOutcome;
        volatile String lootReason;
        long nextLootScan;
        boolean lootAfterEngagement;
        int lastTarget = -1;

        Activity(long id, CharacterRuntime.ModuleContext context, HuntingStartOptions.Style requestedStyle, Long durationMs) {
            this.id = id; this.context = context; this.requestedStyle = requestedStyle; this.durationMs = durationMs;
        }
    }

    private record ActionOutcome(boolean unavailable, boolean succeeded, boolean targetKilled, int kills,
                                 String endReason, boolean unreachable) { }
    private static final class Action {
        final HuntingPlanner.Decision decision;
        final CompletableFuture<ActionOutcome> result = new CompletableFuture<>();
        ModuleTask task;
        String cancelReason;
        Action(HuntingPlanner.Decision decision) { this.decision = decision; }
    }
    private static final class LootAttempt {
        final CompletableFuture<LootResult> result = new CompletableFuture<>();
        ModuleTask task;
    }
}
