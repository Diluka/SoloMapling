package soloMapling.game.loot;

import org.gms.client.inventory.InventoryType;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.server.Server;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import soloMapling.game.action.NativeItemActions;
import soloMapling.game.action.PickupRequest;
import soloMapling.game.action.PickupResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.inventory.InventoryFacts;
import soloMapling.game.inventory.ItemCapacity;
import soloMapling.game.nativeaction.NativeActionModule;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ControlModule;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ModuleTask;
import soloMapling.game.scene.SceneActionDispatch;
import soloMapling.game.scene.SceneRuntimeManager;

import java.awt.Point;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Independent observation of original own drops; only equipment acquires a movement arrangement. */
public final class LootControlModule implements ControlModule {
    public record Config(int checkIntervalMs, int equipmentTimeoutMs) { }
    public static final Config DEFAULT_CONFIG = new Config(100, 30_000);
    public static final ModuleFunction<LootControlModule, Void, LootControlResult> START = new ModuleFunction<>(
            "loot.start", "开始独立观察自己的地物；普通物品只在当前位置尝试，装备按优先级主动拾取。", List.of(),
            "返回STARTED/ALREADY_RUNNING/BUSY/UNAVAILABLE与单调序号、入包及当前装备安排摘要。",
            "监控登记为本模块受管工作后返回，脚本结束不停止监控。", (module, arguments) -> null,
            (module, context, work, input) -> module.start(), LootControlResult::toMap);
    public static final ModuleFunction<LootControlModule, Void, LootControlResult> STOP = new ModuleFunction<>(
            "loot.stop", "停止自身观察和当前装备安排，等待实际收尾，保留已入包事实。", List.of(),
            "返回STOPPED/ALREADY_IDLE及本模块最近摘要；其他控制保持各自生命周期。",
            "监控、装备安排、运动及单次拾取的原工作真实退出后返回。", (module, arguments) -> null,
            (module, context, work, input) -> module.stop(), LootControlResult::toMap);
    public static final ModuleDefinition<Config, LootControlModule> DEFINITION = new ModuleDefinition<>(
            "loot-control", Set.of("loot-control"), new ExecutionScope.Limits(8, 2, 4, 1),
            config -> new LootControlModule(config, Server.getInstance()), List.of(START, STOP));

    private final Config config;
    private final Server server;
    private CharacterRuntime.ModuleContext context;
    private Monitor active;
    private final Set<MapItem> processed = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    // MapItem retains Object identity equality. Uncertain live objects remain ineligible across control pauses and map revisits.
    private final Set<MapItem> uncertain = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private MapleMap processedMap;
    private long sequence, collectedObjects, collectedQuantity, indeterminateSequence, equipmentSequence;
    private PickupResult latest;
    private LootStatus last = new LootStatus(LootStatus.State.IDLE, "not_started", 0, 0, 0, 0, 0, null, null);

    public LootControlModule(Config config, Server server) { this.config = config; this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        if (values.keySet().stream().anyMatch(key -> !Set.of("check_interval_ms", "equipment_timeout_ms").contains(key))) {
            throw new IllegalArgumentException("unknown_loot_control_config");
        }
        int interval = Integer.parseInt(values.getOrDefault("check_interval_ms", DEFAULT_CONFIG.checkIntervalMs()).toString());
        int timeout = Integer.parseInt(values.getOrDefault("equipment_timeout_ms", DEFAULT_CONFIG.equipmentTimeoutMs()).toString());
        if (interval < 1 || timeout < 1 || timeout > 120_000) throw new IllegalArgumentException("invalid_loot_control_config");
        return new Config(interval, timeout);
    }

    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext context) { this.context = context; start(); }

    @Override public synchronized void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        if (active != null) requestStop(active, "module_stopping");
    }

    @Override public synchronized void onUnloaded() {
        context = null;
        processed.clear();
        processedMap = null;
    }

    public synchronized LootControlResult start() {
        if (active != null && active.task.work().isSettled()) finished(active);
        if (active != null) return new LootControlResult(active.state == LootStatus.State.STOPPING
                ? LootControlResult.Outcome.BUSY : LootControlResult.Outcome.ALREADY_RUNNING, snapshot());
        if (context == null || !context.scope().isActive()) return new LootControlResult(LootControlResult.Outcome.UNAVAILABLE, last);
        var monitor = new Monitor(context);
        monitor.task = new ModuleTask(context, "loot-control", work -> monitor(monitor, work));
        active = monitor;
        monitor.task.completion().whenComplete((ignored, failure) -> finished(monitor));
        monitor.task.start();
        return new LootControlResult(LootControlResult.Outcome.STARTED, snapshot());
    }

    public LootControlResult stop() {
        Monitor monitor;
        synchronized (this) {
            monitor = active;
            if (monitor == null) return new LootControlResult(LootControlResult.Outcome.ALREADY_IDLE, last);
            requestStop(monitor, "stopped");
        }
        monitor.task.await();
        finished(monitor);
        return new LootControlResult(LootControlResult.Outcome.STOPPED, snapshot());
    }

    public synchronized LootStatus snapshot() {
        if (active == null) return last;
        var equipment = active.equipment;
        return new LootStatus(active.state, active.reason, sequence, collectedObjects, collectedQuantity,
                indeterminateSequence, equipment == null ? 0 : equipment.id,
                equipment == null ? null : equipment.target.getObjectId(), latest);
    }

    /** A schedule waits only for the exact equipment arrangement it observed; it does not stop this sibling. */
    public void awaitEquipment(long expectedActivityId, ExecutionScope.WorkHandle callerWork) {
        Equipment equipment;
        synchronized (this) { equipment = active == null ? null : active.equipment; }
        if (equipment == null || equipment.id != expectedActivityId) return;
        while (!equipment.task.work().isSettled()) {
            if (callerWork.cancelRequested()) throw new CancellationException("equipment_wait_cancelled");
            try { Thread.sleep(25); }
            catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new CancellationException("equipment_wait_cancelled");
            }
        }
    }

    private void requestStop(Monitor monitor, String reason) {
        monitor.state = LootStatus.State.STOPPING;
        monitor.reason = reason;
        if (monitor.equipment != null) monitor.equipment.task.cancel();
        monitor.task.cancel();
    }

    private synchronized void finished(Monitor monitor) {
        if (active != monitor) return;
        if (monitor.state == LootStatus.State.RUNNING && !monitor.reason.equals("monitor_failed")
                && (monitor.task.work().cancelRequested() || !monitor.context.scope().isActive())) monitor.reason = "module_stopping";
        monitor.state = LootStatus.State.IDLE;
        var equipment = monitor.equipment;
        last = new LootStatus(LootStatus.State.IDLE, monitor.reason, sequence, collectedObjects, collectedQuantity,
                indeterminateSequence, equipment == null ? 0 : equipment.id,
                equipment == null ? null : equipment.target.getObjectId(), latest);
        active = null;
    }

    private void monitor(Monitor monitor, ExecutionScope.WorkHandle work) {
        boolean exited = false;
        try {
            while (!work.cancelRequested() && monitor.context.scope().isActive()) {
                try {
                    var observation = observe(monitor, work);
                    if (monitor.scene != null && monitor.scene.map() != observation.scene().map()) {
                        Equipment previous;
                        synchronized (this) { previous = monitor.equipment; }
                        if (previous != null) { previous.task.cancel(); previous.task.await(); }
                    }
                    monitor.scene = observation.scene();
                    if (processedMap != observation.scene().map()) {
                        processed.clear();
                        processedMap = observation.scene().map();
                    }
                    processed.removeIf(item -> observation.scene().map().getMapObject(item.getObjectId()) != item);
                    Equipment current;
                    synchronized (this) { current = monitor.equipment; }
                    if (current != null && (!observation.alive()
                            || observation.scene().map().getMapObject(current.target.getObjectId()) != current.target
                            || current.target.isPickedUp())) current.task.cancel();
                    if (observation.alive()) {
                        var equipment = observation.items().stream().filter(Candidate::equipment)
                                .filter(item -> !processed.contains(item.target()) && !uncertain.contains(item.target())).findFirst().orElse(null);
                        synchronized (this) {
                            if (monitor.equipment == null && equipment != null) {
                                if (equipment.rejection() == null) startEquipment(monitor, equipment.target());
                                else {
                                    processed.add(equipment.target());
                                    record(monitor, work, PickupResult.skipped(equipment.target().getObjectId(),
                                            equipment.rejection(), equipment.reason()), equipment.target());
                                }
                            }
                        }
                        var ordinary = observation.items().stream().filter(item -> !item.equipment())
                                .filter(item -> !processed.contains(item.target()) && !uncertain.contains(item.target()) && item.withinRange()).findFirst().orElse(null);
                        if (ordinary != null) {
                            processed.add(ordinary.target());
                            if (ordinary.rejection() != null) {
                                record(monitor, work, PickupResult.skipped(ordinary.target().getObjectId(),
                                        ordinary.rejection(), ordinary.reason()), ordinary.target());
                            } else {
                                var call = monitor.context.call(NativeActionModule.PICKUP,
                                        new PickupRequest(ordinary.target().getObjectId(),
                                                new PickupRequest.Binding(ordinary.target(), true, null)), work);
                                record(monitor, work, call.status() == ModuleCall.Status.UNAVAILABLE
                                        ? PickupResult.skipped(ordinary.target().getObjectId(), PickupResult.Outcome.UNAVAILABLE, call.reason())
                                        : call.result(), ordinary.target());
                            }
                        }
                    }
                    synchronized (this) {
                        if (monitor.state == LootStatus.State.RUNNING) monitor.reason = observation.alive() ? "monitoring" : "dead";
                    }
                } catch (GameContext.Invalid changed) {
                    if (work.cancelRequested() || !monitor.context.scope().isActive()) break;
                    if (changed.reason() != GameContext.Failure.SCENE_CHANGED && changed.reason() != GameContext.Failure.EVENT_CHANGED
                            && changed.reason() != GameContext.Failure.NOT_IN_WORLD) throw changed;
                    synchronized (this) { monitor.reason = "scene_changed"; }
                } catch (CancellationException stopped) {
                    if (work.cancelRequested() || !monitor.context.scope().isActive()) break;
                } catch (CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
                    if (!lifecycleRejection(stopped)) throw stopped;
                    if (ownerStopping(monitor.context, work)) break;
                    synchronized (this) { monitor.reason = "pickup_provider_unavailable"; }
                }
                try { Thread.sleep(config.checkIntervalMs()); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            }
            exited = true;
        } finally {
            Equipment equipment;
            synchronized (this) { equipment = monitor.equipment; if (!exited) monitor.reason = "monitor_failed"; }
            if (equipment != null) { equipment.task.cancel(); equipment.task.await(); }
            monitor.scene = null;
        }
    }

    private Observation observe(Monitor monitor, ExecutionScope.WorkHandle parent) {
        var result = new AtomicReference<Observation>();
        var failure = new AtomicReference<Throwable>();
        var step = monitor.context.acceptWork(parent);
        try {
            monitor.context.submit("loot-control", step, () -> {}, () -> {
                try {
                    if (parent.cancelRequested()) throw new CancellationException("loot_stopped");
                    var scene = NativeContextChecks.captureScene(server, monitor.context.characterRef());
                    var actor = scene.actor().character();
                    Point position = actor.getPosition();
                    var items = scene.map().getMapObjects().stream().filter(MapItem.class::isInstance).map(MapItem.class::cast)
                            .filter(item -> item.getOwnerId() == actor.getId()).map(item -> {
                                item.lockItem();
                                try {
                                    if (item.isPickedUp() || scene.map().getMapObject(item.getObjectId()) != item
                                            || System.currentTimeMillis() - item.getDropTime() < 400) return null;
                                    var content = item.getItem();
                                    boolean equipment = content != null && ItemConstants.getInventoryType(content.getItemId()) == InventoryType.EQUIP;
                                    PickupResult.Outcome rejection = null;
                                    String reason = null;
                                    if (item.getMeso() != 0 || !NativeItemActions.supportsPickup(content)) {
                                        rejection = PickupResult.Outcome.REJECTED;
                                        reason = "unsupported_pickup_item";
                                    } else if (equipment) {
                                        var capacity = InventoryFacts.capacity(actor, content);
                                        if (!capacity.fits()) {
                                            rejection = capacity.outcome() == ItemCapacity.Outcome.NO_SPACE
                                                    ? PickupResult.Outcome.CAPACITY_FULL : PickupResult.Outcome.REJECTED;
                                            reason = capacity.outcome() == ItemCapacity.Outcome.NO_SPACE ? "inventory_full" : "pickup_restricted";
                                        }
                                    }
                                    return new Candidate(item, equipment,
                                            NativeItemActions.withinPickupRange(position, item.getPosition()), rejection, reason);
                                } finally { item.unlockItem(); }
                            }).filter(java.util.Objects::nonNull)
                            .sorted(Comparator.comparingDouble(item -> item.target().getPosition().distanceSq(position))).toList();
                    NativeContextChecks.requireSceneCurrent(server, scene);
                    result.set(new Observation(scene, actor.isAlive() && !actor.isChangingMaps(), items));
                } catch (RuntimeException | Error unavailable) { failure.set(unavailable); }
            }, () -> {});
        } catch (RuntimeException rejected) { step.reject(); throw rejected; }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof RuntimeException unavailable) throw unavailable;
        if (failure.get() instanceof Error fatal) throw fatal;
        if (result.get() == null) throw new CancellationException("loot_observation_not_entered");
        return result.get();
    }

    private void startEquipment(Monitor monitor, MapItem target) {
        var equipment = new Equipment(++equipmentSequence, monitor.scene, target);
        equipment.task = new ModuleTask(monitor.context, "equipment-pickup-" + equipment.id,
                work -> collectEquipment(monitor, equipment, work));
        monitor.equipment = equipment;
        equipment.task.completion().whenComplete((ignored, failure) -> {
            synchronized (this) { if (monitor.equipment == equipment) monitor.equipment = null; }
        });
        equipment.task.start();
    }

    private void collectEquipment(Monitor monitor, Equipment equipment, ExecutionScope.WorkHandle work) {
        SceneActionDispatch dispatch = null;
        SceneActionDispatch.Arrangement arrangement = null;
        PickupResult result = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.equipmentTimeoutMs());
        try {
            var binding = SceneRuntimeManager.current(server, equipment.scene.actor());
            dispatch = binding.dispatch();
            while (arrangement == null || !arrangement.current()) {
                if (work.cancelRequested()) throw new CancellationException("equipment_pickup_cancelled");
                if (System.nanoTime() >= deadline) {
                    result = PickupResult.skipped(equipment.target.getObjectId(), PickupResult.Outcome.REJECTED, "equipment_pickup_timeout");
                    return;
                }
                NativeContextChecks.requireSceneCurrent(server, equipment.scene);
                var decision = dispatch.request(SceneActionDispatch.Priority.EQUIPMENT_PICKUP, monitor.context,
                        work, equipment.target, "equipment_pickup");
                if (decision.arrangement() != null) arrangement = decision.arrangement();
                if (decision.status() == SceneActionDispatch.Status.UNAVAILABLE) throw new CancellationException("equipment_owner_unavailable");
                if (decision.status() != SceneActionDispatch.Status.GRANTED) {
                    try { Thread.sleep(config.checkIntervalMs()); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new CancellationException("equipment_pickup_cancelled"); }
                }
            }
            var targetPosition = equipment.target.getPosition();
            var plan = monitor.context.call(NavigationActionModule.PLAN,
                    new MoveToRequest(targetPosition.x, targetPosition.y,
                            Math.max(1, (int) TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))), work);
            if (plan.status() == ModuleCall.Status.UNAVAILABLE) {
                result = PickupResult.skipped(equipment.target.getObjectId(), PickupResult.Outcome.UNAVAILABLE, plan.reason());
                return;
            }
            var navigation = binding.scene().characters().motion(binding).executePlan(plan.result(), monitor.context,
                    work, arrangement, "loot-control");
            if (navigation.outcome() != NavigationResult.Outcome.ARRIVED) {
                result = PickupResult.skipped(equipment.target.getObjectId(), arrangement.valid()
                        ? PickupResult.Outcome.REJECTED : PickupResult.Outcome.PREEMPTED,
                        navigation.reason() == null ? "equipment_not_reached" : navigation.reason());
                return;
            }
            var call = monitor.context.call(NativeActionModule.PICKUP, new PickupRequest(equipment.target.getObjectId(),
                    new PickupRequest.Binding(equipment.target, false, arrangement)), work);
            result = call.status() == ModuleCall.Status.UNAVAILABLE
                    ? PickupResult.skipped(equipment.target.getObjectId(), arrangement.valid()
                            ? PickupResult.Outcome.UNAVAILABLE : PickupResult.Outcome.PREEMPTED, call.reason()) : call.result();
        } catch (CancellationException stopped) {
            result = PickupResult.skipped(equipment.target.getObjectId(), arrangement != null && !arrangement.valid()
                    ? PickupResult.Outcome.PREEMPTED : PickupResult.Outcome.CANCELLED, "equipment_pickup_cancelled");
        } catch (GameContext.Invalid changed) {
            result = PickupResult.skipped(equipment.target.getObjectId(), PickupResult.Outcome.CONTEXT_CHANGED, "scene_changed");
        } catch (CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (!lifecycleRejection(stopped)) throw stopped;
            result = PickupResult.skipped(equipment.target.getObjectId(), arrangement != null && !arrangement.valid()
                    ? PickupResult.Outcome.PREEMPTED : ownerStopping(monitor.context, work)
                    ? PickupResult.Outcome.CANCELLED : PickupResult.Outcome.UNAVAILABLE, "pickup_provider_unavailable");
        } finally {
            if (dispatch != null) dispatch.release(arrangement, "equipment_pickup_finished");
            if (result != null) {
                if (result.outcome() != PickupResult.Outcome.PREEMPTED) processed.add(equipment.target);
                record(monitor, work, result, equipment.target);
            }
        }
    }

    private synchronized void record(Monitor monitor, ExecutionScope.WorkHandle work, PickupResult result, MapItem target) {
        latest = result;
        sequence++;
        if (result.inventoryQuantityDelta() > 0) { collectedObjects++; collectedQuantity += result.inventoryQuantityDelta(); }
        if (result.outcome() == PickupResult.Outcome.INDETERMINATE) {
            indeterminateSequence = sequence;
            uncertain.add(target);
        }
        monitor.context.recordActivity(work, result.reason() == null ? "pickup_collected" : result.reason(), result.outcome().name());
    }

    private static boolean lifecycleRejection(RuntimeException failure) {
        return failure instanceof CharacterRuntime.Invalid runtime
                && Set.of("module_not_active", "stale_module_reference").contains(runtime.reason())
                || failure instanceof ExecutionScope.Invalid scope && "scope_not_active".equals(scope.reason());
    }

    private static boolean ownerStopping(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        if (work.cancelRequested() || !context.scope().isActive()) return true;
        try { context.help(); return false; }
        catch (CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (!lifecycleRejection(stopped)) throw stopped;
            return true;
        }
    }

    private record Candidate(MapItem target, boolean equipment, boolean withinRange,
                             PickupResult.Outcome rejection, String reason) { }
    private record Observation(GameContext.SceneRef scene, boolean alive, List<Candidate> items) { }
    private static final class Equipment {
        final long id;
        final GameContext.SceneRef scene;
        final MapItem target;
        ModuleTask task;
        Equipment(long id, GameContext.SceneRef scene, MapItem target) { this.id = id; this.scene = scene; this.target = target; }
    }
    private static final class Monitor {
        final CharacterRuntime.ModuleContext context;
        ModuleTask task;
        LootStatus.State state = LootStatus.State.RUNNING;
        String reason = "monitoring";
        GameContext.SceneRef scene;
        Equipment equipment;
        Monitor(CharacterRuntime.ModuleContext context) { this.context = context; }
    }
}
