package soloMapling.game.recovery;

import org.gms.client.Disease;
import org.gms.net.server.Server;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.potion.PotionActionModule;
import soloMapling.game.potion.PotionItem;
import soloMapling.game.potion.PotionRequest;
import soloMapling.game.potion.PotionResult;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ControlModule;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ModuleTask;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** Watches the bound role independently and delegates all potion effects and cooldowns to their provider. */
public final class RecoveryControlModule implements ControlModule {
    public record Config(int hpThresholdPct, int mpThresholdPct, boolean cleanseEnabled, int checkIntervalMs) { }
    public static final Config DEFAULT_CONFIG = new Config(50, 50, true, 500);
    public static final ModuleFunction<RecoveryControlModule, Void, RecoveryStartResult> START = new ModuleFunction<>(
            "recovery.start", "按本模块配置开始常驻恢复监控；模块激活时也会自动开始。", List.of(),
            "返回STARTED/ALREADY_RUNNING/BUSY/UNAVAILABLE及当前监控状态与有界诊断。",
            "本次启动已登记为恢复模块自身工作后返回；脚本结束后监控继续。",
            (module, arguments) -> null, (module, context, work, input) -> module.start(), RecoveryStartResult::toMap);
    public static final ModuleFunction<RecoveryControlModule, Void, RecoveryStopResult> STOP = new ModuleFunction<>(
            "recovery.stop", "停止本次监控及其药剂调用，保持模块与角色可继续使用。", List.of(),
            "返回STOPPED/ALREADY_IDLE及最近观察、请求和实际用药摘要。",
            "本次监控工作与下游药剂实际退出后返回；已发生药效和药剂冷却保留。",
            (module, arguments) -> null, (module, context, work, input) -> module.stop(), RecoveryStopResult::toMap);
    public static final ModuleDefinition<Config, RecoveryControlModule> DEFINITION = new ModuleDefinition<>(
            "recovery-control", Set.of("recovery-control"), new ExecutionScope.Limits(8, 1, 4, 1),
            config -> new RecoveryControlModule(config, Server.getInstance()), List.of(START, STOP));

    private final Config config;
    private final Server server;
    private CharacterRuntime.ModuleContext context;
    private Monitor active;
    private RecoveryStatus last = new RecoveryStatus(RecoveryStatus.State.IDLE, "not_started", 0, 0, 0, 0, 0,
            null, null, null);

    public RecoveryControlModule(Config config, Server server) { this.config = config; this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        var names = Set.of("hp_threshold_pct", "mp_threshold_pct", "cleanse_enabled", "check_interval_ms");
        if (values.keySet().stream().anyMatch(key -> !names.contains(key))) {
            throw new IllegalArgumentException("unknown_recovery_config");
        }
        int hp = Integer.parseInt(values.getOrDefault("hp_threshold_pct", DEFAULT_CONFIG.hpThresholdPct()).toString());
        int mp = Integer.parseInt(values.getOrDefault("mp_threshold_pct", DEFAULT_CONFIG.mpThresholdPct()).toString());
        int interval = Integer.parseInt(values.getOrDefault("check_interval_ms", DEFAULT_CONFIG.checkIntervalMs()).toString());
        var cleanse = String.valueOf(values.getOrDefault("cleanse_enabled", DEFAULT_CONFIG.cleanseEnabled()));
        if (!cleanse.equalsIgnoreCase("true") && !cleanse.equalsIgnoreCase("false")
                || hp < 0 || hp > 100 || mp < 0 || mp > 100 || interval < 1) {
            throw new IllegalArgumentException("invalid_recovery_config");
        }
        return new Config(hp, mp, Boolean.parseBoolean(cleanse), interval);
    }

    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext current) {
        context = current;
        start();
    }

    @Override public void onStopping(CharacterRuntime.ModuleContext current, CharacterRuntime.StopMode mode) {
        synchronized (this) {
            if (active != null) requestStop(active, "module_stopping");
        }
    }

    @Override public synchronized void onUnloaded() { context = null; }

    public synchronized RecoveryStartResult start() {
        // Scope drain may be observed before the old carrier's completion callbacks run.
        if (active != null && active.task.work().isSettled()) finished(active);
        if (active != null) {
            return new RecoveryStartResult(active.state == RecoveryStatus.State.STOPPING
                    ? RecoveryStartResult.Outcome.BUSY : RecoveryStartResult.Outcome.ALREADY_RUNNING, snapshot(active));
        }
        if (context == null || !context.scope().isActive()) {
            return new RecoveryStartResult(RecoveryStartResult.Outcome.UNAVAILABLE, last);
        }
        var monitor = new Monitor(context);
        monitor.task = new ModuleTask(context, "recovery-control", work -> monitor(monitor, work));
        active = monitor;
        monitor.task.completion().whenComplete((settled, failure) -> finished(monitor));
        context.recordActivity(monitor.task.work(), "recovery_started", "RUNNING");
        monitor.task.start();
        return new RecoveryStartResult(RecoveryStartResult.Outcome.STARTED, snapshot(monitor));
    }

    public RecoveryStopResult stop() {
        Monitor monitor;
        synchronized (this) {
            monitor = active;
            if (monitor == null) return new RecoveryStopResult(RecoveryStopResult.Outcome.ALREADY_IDLE, last);
            requestStop(monitor, "stopped");
        }
        monitor.task.await();
        finished(monitor);
        synchronized (this) { return new RecoveryStopResult(RecoveryStopResult.Outcome.STOPPED, snapshot(monitor)); }
    }

    public synchronized RecoveryStatus status() { return active == null ? last : snapshot(active); }

    /** Called with the module monitor held; cancellation never blocks the lifecycle hook. */
    private void requestStop(Monitor monitor, String reason) {
        if (monitor.state == RecoveryStatus.State.RUNNING) {
            monitor.state = RecoveryStatus.State.STOPPING;
            monitor.reason = reason;
            monitor.context.recordActivity(monitor.task.work(), reason, "STOPPING");
        }
        monitor.task.cancel();
    }

    private synchronized void finished(Monitor monitor) {
        if (active != monitor) return;
        if (monitor.state == RecoveryStatus.State.RUNNING && !monitor.reason.equals("monitor_failed")
                && (monitor.task.work().cancelRequested() || !monitor.context.scope().isActive())) {
            monitor.reason = "module_stopping";
        }
        monitor.state = RecoveryStatus.State.IDLE;
        last = snapshot(monitor);
        active = null;
        monitor.context.recordActivity(monitor.task.work(), monitor.reason, "IDLE");
    }

    private static RecoveryStatus snapshot(Monitor monitor) {
        return new RecoveryStatus(monitor.state, monitor.reason, monitor.checks, monitor.restoreRequests,
                monitor.cleanseRequests, monitor.restorations, monitor.cleanses, monitor.observation,
                monitor.lastRestore, monitor.lastCleanse);
    }

    private void monitor(Monitor monitor, ExecutionScope.WorkHandle work) {
        boolean exited = false;
        try {
            while (!work.cancelRequested() && monitor.context.scope().isActive()) {
                try {
                    var observation = observe(monitor.context, work);
                    synchronized (this) {
                        monitor.checks++;
                        monitor.observation = observation;
                        if (monitor.state == RecoveryStatus.State.RUNNING) {
                            monitor.reason = observation.alive()
                                    ? monitor.potionsUnavailable ? "potions_unavailable" : "monitoring" : "dead";
                        }
                    }
                    if (observation.alive()) {
                        if (config.hpThresholdPct() > 0 && observation.hp() < observation.maxHp()
                                && 100L * observation.hp() <= (long) observation.maxHp() * config.hpThresholdPct()
                                || config.mpThresholdPct() > 0 && observation.mp() < observation.maxMp()
                                && 100L * observation.mp() <= (long) observation.maxMp() * config.mpThresholdPct()) {
                            observation = use(monitor, work, PotionItem.ELIXIR, observation);
                        }
                        if (!work.cancelRequested() && observation.alive() && config.cleanseEnabled()
                                && observation.diseases().stream().anyMatch(PotionItem.ALL_CURE.curableDiseases()::contains)) {
                            use(monitor, work, PotionItem.ALL_CURE, observation);
                        }
                    }
                } catch (GameContext.Invalid changed) {
                    if (work.cancelRequested() || !monitor.context.scope().isActive()) break;
                    if (changed.reason() != GameContext.Failure.SCENE_CHANGED
                            && changed.reason() != GameContext.Failure.EVENT_CHANGED
                            && changed.reason() != GameContext.Failure.NOT_IN_WORLD) throw changed;
                    synchronized (this) {
                        if (monitor.state == RecoveryStatus.State.RUNNING) monitor.reason = "scene_changed";
                    }
                } catch (CancellationException skipped) {
                    if (work.cancelRequested() || !monitor.context.scope().isActive()) break;
                    synchronized (this) {
                        if (monitor.state == RecoveryStatus.State.RUNNING) monitor.reason = "work_not_entered";
                    }
                } catch (CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
                    if (work.cancelRequested() || !monitor.context.scope().isActive()) break;
                    throw stopped;
                }
                try { Thread.sleep(config.checkIntervalMs()); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    synchronized (this) {
                        if (monitor.state == RecoveryStatus.State.RUNNING
                                && !work.cancelRequested() && monitor.context.scope().isActive()) monitor.reason = "interrupted";
                    }
                    break;
                }
            }
            exited = true;
        } finally {
            synchronized (this) { if (!exited) monitor.reason = "monitor_failed"; }
        }
    }

    private PotionResult.State use(Monitor monitor, ExecutionScope.WorkHandle work, PotionItem item,
                                   PotionResult.State observation) {
        if (work.cancelRequested()) return observation;
        synchronized (this) {
            if (item == PotionItem.ELIXIR) monitor.restoreRequests++;
            else monitor.cleanseRequests++;
        }
        var call = monitor.context.call(PotionActionModule.USE, new PotionRequest(item.itemId()), work);
        synchronized (this) {
            if (call.status() == ModuleCall.Status.UNAVAILABLE) {
                if (!monitor.potionsUnavailable) {
                    monitor.potionsUnavailable = true;
                    monitor.context.recordActivity(work, "potions_unavailable", "UNAVAILABLE");
                }
                if (monitor.state == RecoveryStatus.State.RUNNING) monitor.reason = "potions_unavailable";
                return observation;
            }
            if (monitor.potionsUnavailable) {
                monitor.potionsUnavailable = false;
                monitor.context.recordActivity(work, "potions_available", "AVAILABLE");
                if (monitor.state == RecoveryStatus.State.RUNNING) monitor.reason = "monitoring";
            }
            var result = call.result();
            if (item == PotionItem.ELIXIR) {
                monitor.lastRestore = result;
                if (result.outcome() == PotionResult.Outcome.APPLIED) monitor.restorations++;
            } else {
                monitor.lastCleanse = result;
                if (result.outcome() == PotionResult.Outcome.APPLIED) monitor.cleanses++;
            }
            if (result.outcome() == PotionResult.Outcome.APPLIED) {
                monitor.context.recordActivity(work, item == PotionItem.ELIXIR ? "restore_applied" : "cleanse_applied", "APPLIED");
            }
            monitor.observation = result.after();
            return result.after();
        }
    }

    private PotionResult.State observe(CharacterRuntime.ModuleContext current, ExecutionScope.WorkHandle work) {
        var actor = current.characterRef();
        var result = new AtomicReference<PotionResult.State>();
        var failure = new AtomicReference<Throwable>();
        var step = current.acceptWork();
        try {
            current.submit("recovery-control", step, () -> {}, () -> {
                try {
                    if (work.cancelRequested()) throw new CancellationException("recovery_stopped");
                    var scene = NativeContextChecks.captureScene(server, actor);
                    var character = actor.character();
                    var observed = new PotionResult.State(character.isAlive(), character.getHp(), character.getMp(),
                            character.getCurrentMaxHp(), character.getCurrentMaxMp(),
                            character.getAllDiseases().keySet().stream()
                                    .filter(disease -> disease != Disease.NULL && disease != Disease.FISHABLE).sorted().toList());
                    NativeContextChecks.requireSceneCurrent(server, scene);
                    result.set(observed);
                } catch (RuntimeException | Error unavailable) { failure.set(unavailable); }
            }, () -> {});
        } catch (RuntimeException rejected) {
            step.reject();
            throw rejected;
        }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof RuntimeException unavailable) throw unavailable;
        if (failure.get() instanceof Error fatal) throw fatal;
        if (result.get() == null) throw new CancellationException("recovery_observation_not_entered");
        return result.get();
    }

    private static final class Monitor {
        final CharacterRuntime.ModuleContext context;
        ModuleTask task;
        RecoveryStatus.State state = RecoveryStatus.State.RUNNING;
        String reason = "monitoring";
        long checks, restoreRequests, cleanseRequests, restorations, cleanses;
        boolean potionsUnavailable;
        PotionResult.State observation;
        PotionResult lastRestore, lastCleanse;

        Monitor(CharacterRuntime.ModuleContext context) { this.context = context; }
    }
}
