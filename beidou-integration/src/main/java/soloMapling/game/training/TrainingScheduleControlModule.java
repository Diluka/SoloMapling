package soloMapling.game.training;

import org.gms.client.BuffStat;
import org.gms.client.Disease;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.Server;
import soloMapling.game.action.PortalRequest;
import soloMapling.game.action.PortalResult;
import soloMapling.game.action.ReviveRequest;
import soloMapling.game.action.ReviveResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.hunting.HuntingControlModule;
import soloMapling.game.hunting.HuntingSnapshot;
import soloMapling.game.hunting.HuntingStartOptions;
import soloMapling.game.hunting.HuntingStartResult;
import soloMapling.game.inventory.CapacityRule;
import soloMapling.game.inventory.InventoryFacts;
import soloMapling.game.inventory.InventorySnapshot;
import soloMapling.game.inventory.SaleRules;
import soloMapling.game.nativeaction.NativeActionModule;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ControlModule;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleTask;
import soloMapling.game.action.NativeShopActions;
import soloMapling.game.shop.ShopActionModule;
import soloMapling.game.shop.ShopOpenRequest;
import soloMapling.game.shop.ShopResult;
import soloMapling.game.shop.ShopSellRequest;
import soloMapling.game.travel.TravelPlanModule;
import soloMapling.game.travel.TravelPlanRequest;
import soloMapling.game.travel.TravelPlanResult;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import soloMapling.game.training.TrainingScheduleStatus.Stage;
import soloMapling.game.training.TrainingScheduleStatus.State;

/** The fixed training/shop business cycle; execution remains with its action providers. */
public final class TrainingScheduleControlModule implements ControlModule {
    public record Config(int trainingMapId, int shopMapId, int shopNpcId,
                         HuntingStartOptions.Style huntingStyle, CapacityRule capacity, SaleRules sale,
                         int checkIntervalMs, boolean reviveOnDeath) {
        public Config {
            if (trainingMapId < 0 || shopMapId < 0 || trainingMapId == shopMapId || shopNpcId < 1
                    || checkIntervalMs < 1) throw new IllegalArgumentException("invalid_training_schedule_config");
        }
    }

    public static final Config DEFAULT_CONFIG = new Config(104040000, 100000102, 1011100,
            HuntingStartOptions.Style.PATROL, new CapacityRule(Set.of(InventoryType.ETC), 0),
            new SaleRules(Set.of(), Set.of(), Map.of()), 500, true);
    public static final ModuleDefinition<Config, TrainingScheduleControlModule> DEFINITION = new ModuleDefinition<>(
            "training-schedule", Set.of("training-observe"), new ExecutionScope.Limits(8, 1, 2, 1),
            config -> new TrainingScheduleControlModule(config, Server.getInstance()), List.of());

    private static final int MAX_RECENT_CYCLES = 2;
    private static final int MAX_CYCLE_PORTALS = 8;
    private static final int MAX_SALE_FACTS = 8;
    private static final int MAX_QUANTITY_FACTS = 16;
    private static final Set<String> COLLABORATORS = Set.of("hunting.start", "hunting.stop", "loot.collect",
            "travel.plan", "navigation.moveTo", "native.enterPortal", "native.revive", "shop.open", "shop.sell", "shop.close");

    private final Config config;
    private final Server server;
    private final List<Integer> watchedItemIds;
    private final ArrayDeque<TrainingScheduleStatus.Cycle> recent = new ArrayDeque<>();
    private CharacterRuntime.ModuleContext context;
    private Activity active;
    private long runSequence;
    private long cycleSequence;
    private long cyclesCompleted;
    private TrainingScheduleStatus last = new TrainingScheduleStatus(State.IDLE, Stage.STOPPED, "not_started",
            0, 0, null, null, null, List.of());

    public TrainingScheduleControlModule(Config config, Server server) {
        this.config = config;
        this.server = server;
        var watched = new HashSet<>(config.sale().allowedItemIds());
        watched.addAll(config.sale().retainedItemIds());
        watched.addAll(config.sale().retainedQuantities().keySet());
        watchedItemIds = watched.stream().sorted().toList();
    }

    public static Config parseConfig(Map<String, Object> values) {
        var names = Set.of("training_map_id", "shop_map_id", "shop_npc_id", "hunting_style", "capacity", "sale",
                "check_interval_ms", "revive_on_death");
        if (!names.containsAll(values.keySet())) throw new IllegalArgumentException("unknown_training_schedule_config");
        var capacity = object(values.getOrDefault("capacity", Map.of()), "capacity");
        if (!Set.of("inventory_types", "minimum_free_slots").containsAll(capacity.keySet())) {
            throw new IllegalArgumentException("unknown_training_capacity_config");
        }
        var types = list(capacity.getOrDefault("inventory_types", List.of("ETC")), "inventory_types").stream()
                .map(value -> InventoryType.valueOf(value.toString())).collect(Collectors.toSet());
        var sale = object(values.getOrDefault("sale", Map.of()), "sale");
        if (!Set.of("allowed_item_ids", "retained_item_ids", "retained_quantities").containsAll(sale.keySet())) {
            throw new IllegalArgumentException("unknown_training_sale_config");
        }
        var retained = object(sale.getOrDefault("retained_quantities", Map.of()), "retained_quantities");
        var quantities = new LinkedHashMap<Integer, Integer>();
        retained.forEach((id, quantity) -> quantities.put(integer(id), integer(quantity)));
        if (!(values.getOrDefault("revive_on_death", DEFAULT_CONFIG.reviveOnDeath()) instanceof Boolean reviveOnDeath)) {
            throw new IllegalArgumentException("invalid_training_revive_on_death");
        }
        return new Config(integer(values.getOrDefault("training_map_id", DEFAULT_CONFIG.trainingMapId())),
                integer(values.getOrDefault("shop_map_id", DEFAULT_CONFIG.shopMapId())),
                integer(values.getOrDefault("shop_npc_id", DEFAULT_CONFIG.shopNpcId())),
                HuntingStartOptions.Style.valueOf(values.getOrDefault("hunting_style", "PATROL").toString()),
                new CapacityRule(types, integer(capacity.getOrDefault("minimum_free_slots", 0))),
                new SaleRules(itemIds(sale.getOrDefault("allowed_item_ids", List.of())),
                        itemIds(sale.getOrDefault("retained_item_ids", List.of())), quantities),
                integer(values.getOrDefault("check_interval_ms", DEFAULT_CONFIG.checkIntervalMs())),
                reviveOnDeath);
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("invalid_training_" + name);
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, entry) -> result.put(key.toString(), entry));
        return result;
    }

    private static List<?> list(Object value, String name) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("invalid_training_" + name);
        return list;
    }

    private static Set<Integer> itemIds(Object value) {
        return list(value, "item_ids").stream().map(TrainingScheduleControlModule::integer).collect(Collectors.toSet());
    }

    private static int integer(Object value) { return new BigDecimal(value.toString()).intValueExact(); }

    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext current) {
        if (active != null && active.task.work().isSettled()) finished(active);
        context = current;
        start();
    }

    @Override public void onStopping(CharacterRuntime.ModuleContext current, CharacterRuntime.StopMode mode) {
        Activity stopping;
        synchronized (this) {
            context = null;
            stopping = active;
            if (stopping != null) { stopping.state = State.STOPPING; stopping.reason = "module_stopping"; }
        }
        if (stopping != null) stopping.task.cancel();
    }

    public synchronized TrainingScheduleStartResult start() {
        if (active != null && active.task.work().isSettled()) finished(active);
        if (active != null) return new TrainingScheduleStartResult(active.state == State.STOPPING
                ? TrainingScheduleStartResult.Outcome.BUSY : TrainingScheduleStartResult.Outcome.ALREADY_RUNNING,
                snapshot(active));
        if (context == null || !context.scope().isActive()) {
            return new TrainingScheduleStartResult(TrainingScheduleStartResult.Outcome.UNAVAILABLE, last);
        }
        var activity = new Activity(++runSequence, context);
        activity.task = new ModuleTask(context, "training-schedule", work -> run(activity, work));
        active = activity;
        activity.task.completion().whenComplete((ignored, failure) -> finished(activity));
        activity.task.start();
        return new TrainingScheduleStartResult(TrainingScheduleStartResult.Outcome.STARTED, snapshot(activity));
    }

    public TrainingScheduleStopResult stop() {
        Activity stopping;
        synchronized (this) {
            stopping = active;
            if (stopping == null) return new TrainingScheduleStopResult(TrainingScheduleStopResult.Outcome.ALREADY_IDLE, last);
            stopping.state = State.STOPPING;
            stopping.reason = "stop_requested";
        }
        stopping.task.cancel();
        stopping.task.await();
        finished(stopping);
        synchronized (this) {
            return new TrainingScheduleStopResult(TrainingScheduleStopResult.Outcome.STOPPED, snapshot(stopping));
        }
    }

    public synchronized TrainingScheduleStatus status() { return active == null ? last : snapshot(active); }

    private synchronized void finished(Activity activity) {
        if (active != activity || !activity.task.work().isSettled()) return;
        if (activity.stage != Stage.STOPPED && activity.state != State.FAILED) {
            activity.state = State.IDLE;
            activity.stage = Stage.STOPPED;
            activity.destinationMapId = null;
            if (activity.reason == null) activity.reason = "not_started";
        }
        last = snapshot(activity);
        active = null;
    }

    private TrainingScheduleStatus snapshot(Activity activity) {
        return new TrainingScheduleStatus(activity.state, activity.stage, activity.reason, activity.id,
                cyclesCompleted, activity.currentMapId, activity.destinationMapId,
                activity.cycle == null ? null : activity.cycle.snapshot(false), List.copyOf(recent));
    }

    private void run(Activity activity, ExecutionScope.WorkHandle work) {
        boolean normal = false;
        try {
            while (!work.cancelRequested()) {
                var facts = observe(activity);
                synchronized (this) {
                    activity.currentMapId = facts.mapId();
                    if (facts.alive() && activity.deathObserved && activity.state != State.STOPPING
                            && !work.cancelRequested()) {
                        activity.deathObserved = false;
                        activity.reviveAttempted = false;
                        activity.stage = Stage.STARTING;
                        activity.blockedOn = null;
                        if (!activity.lootIndeterminate && !activity.saleIndeterminate && !activity.portalIndeterminate) {
                            activity.state = State.RUNNING;
                            activity.reason = null;
                        }
                        activity.context.recordActivity(work, "revived", "STARTING");
                    }
                }
                var condition = condition(activity, facts);
                synchronized (this) {
                    if (activity.state == State.BLOCKED && (activity.lootIndeterminate || activity.saleIndeterminate || activity.portalIndeterminate || condition.equals(activity.blockedOn))) {
                        // Wait below without repeating routes or native shop calls against unchanged facts.
                    } else if (activity.state == State.BLOCKED) {
                        activity.state = State.RUNNING; activity.reason = null; activity.blockedOn = null;
                    }
                }
                if (activity.state == State.STOPPING) break;
                if (activity.state != State.BLOCKED) {
                    String blocked;
                    if (!facts.alive()) blocked = recoverDeath(activity, work);
                    else if (facts.changingMaps()) blocked = "map_transition_pending";
                    else {
                        if (activity.stage == Stage.STARTING) {
                            stage(activity, config.capacity().evaluate(facts.inventory()).triggered()
                                    ? facts.mapId() == config.shopMapId() ? Stage.SELLING : Stage.TO_SHOP
                                    : facts.mapId() == config.trainingMapId() ? Stage.HUNTING : Stage.TO_TRAINING);
                        }
                        try {
                            blocked = switch (activity.stage) {
                                case TO_TRAINING, RETURNING -> {
                                    var journey = travel(activity, work, config.trainingMapId());
                                    if (journey.arrived()) stage(activity, Stage.HUNTING);
                                    yield journey.reason();
                                }
                                case TO_SHOP -> {
                                    if (!config.capacity().evaluate(facts.inventory()).triggered()) {
                                        synchronized (this) {
                                            if (activity.cycle != null) activity.cycle.afterSale = summary(facts);
                                        }
                                        stage(activity, Stage.RETURNING);
                                        yield null;
                                    }
                                    var journey = travel(activity, work, config.shopMapId());
                                    if (journey.arrived()) stage(activity, Stage.SELLING);
                                    yield journey.reason();
                                }
                                case HUNTING -> hunt(activity, work, facts);
                                case SELLING -> sell(activity, work);
                                default -> null;
                            };
                        } catch (CharacterRuntime.Invalid unavailable) {
                            if (work.cancelRequested() || !activity.context.scope().isActive()) break;
                            if (!Set.of("module_not_active", "stale_module_reference").contains(unavailable.reason())) throw unavailable;
                            blocked = "collaborator_changed";
                        } catch (ExecutionScope.Invalid unavailable) {
                            if (work.cancelRequested() || !activity.context.scope().isActive()) break;
                            if (!"scope_not_active".equals(unavailable.reason())) throw unavailable;
                            blocked = "collaborator_changed";
                        } catch (CancellationException unavailable) {
                            if (work.cancelRequested() || !activity.context.scope().isActive()) break;
                            blocked = "collaborator_cancelled";
                        }
                    }
                    if (blocked != null && !work.cancelRequested()) {
                        synchronized (this) {
                            if (activity.state != State.STOPPING && !work.cancelRequested()) {
                                activity.state = State.BLOCKED; activity.reason = blocked; activity.blockedOn = condition;
                            }
                        }
                        if (activity.state == State.BLOCKED) activity.context.recordActivity(work, blocked, "BLOCKED");
                    }
                }
                try { Thread.sleep(config.checkIntervalMs()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            normal = true;
        } catch (CancellationException stopped) {
            if (!work.cancelRequested() && activity.context.scope().isActive()) throw stopped;
            normal = true;
        } catch (CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (!work.cancelRequested() && activity.context.scope().isActive()) throw stopped;
            normal = true;
        } finally {
            boolean closed = false;
            try {
                try { stopHunting(activity); }
                finally { closeShop(activity); }
                closed = true;
            } finally {
                synchronized (this) {
                    activity.state = normal && closed ? State.IDLE : State.FAILED;
                    activity.stage = Stage.STOPPED;
                    if (!closed) activity.reason = "cleanup_failed";
                    else if (!normal) activity.reason = "execution_failed";
                    else if (activity.reason == null) activity.reason = "stopped";
                    activity.destinationMapId = null;
                }
                activity.context.recordActivity(work, activity.reason, "ENDED");
            }
        }
    }

    private String recoverDeath(Activity activity, ExecutionScope.WorkHandle work) {
        stopHunting(activity);
        closeShop(activity);
        activity.deathObserved = true;
        if (!config.reviveOnDeath()) return "dead_waiting_external_revive";
        // An accepted native event may finish later. Observe it rather than replaying its side effects.
        if (activity.reviveAttempted) return activity.reviveWaitReason;
        var revived = activity.context.call(NativeActionModule.REVIVE, ReviveRequest.NORMAL, work);
        if (revived.status() == ModuleCall.Status.UNAVAILABLE) return "revive_" + revived.reason();
        var result = revived.result();
        activity.reviveAttempted = result.dispatch().started();
        activity.context.recordActivity(work, "revive_" + result.outcome().name().toLowerCase(java.util.Locale.ROOT), "RECOVERING");
        if (result.outcome() == ReviveResult.Outcome.REVIVED || result.outcome() == ReviveResult.Outcome.ALREADY_ALIVE) {
            stage(activity, Stage.STARTING);
            return null;
        }
        activity.reviveWaitReason = result.event().route() == ReviveResult.EventRoute.TAKEN_OVER
                ? "revive_waiting_event" : "revive_" + result.outcome().name().toLowerCase(java.util.Locale.ROOT);
        return activity.reviveWaitReason;
    }

    private String hunt(Activity activity, ExecutionScope.WorkHandle work, Observation facts) {
        if (activity.huntingActivityId != 0) {
            var current = activity.hunting.snapshot();
            if (current.activityId() != activity.huntingActivityId) {
                activity.huntingActivityId = 0;
                return "hunting_activity_changed";
            }
            updateHunting(activity, current, current.state() == HuntingControlModule.State.IDLE);
            if (current.state() == HuntingControlModule.State.IDLE) {
                activity.huntingActivityId = 0;
                if (current.lootOutcome() == soloMapling.game.loot.LootResult.Outcome.INDETERMINATE) {
                    activity.lootIndeterminate = true;
                    return "loot_indeterminate";
                }
            }
        }
        if (facts.mapId() != config.trainingMapId()) {
            stopHunting(activity);
            stage(activity, config.capacity().evaluate(facts.inventory()).triggered() ? Stage.TO_SHOP : Stage.TO_TRAINING);
            return null;
        }
        if (config.capacity().evaluate(facts.inventory()).triggered()) {
            stopHunting(activity);
            if (activity.lootIndeterminate) return "loot_indeterminate";
            var triggered = observe(activity);
            synchronized (this) {
                if (activity.cycle != null) activity.cycle.capacityTriggered = summary(triggered);
            }
            stage(activity, Stage.TO_SHOP);
            return null;
        }
        if (!functionAvailable(activity.context, "loot.collect")) {
            stopHunting(activity);
            return "loot_unavailable";
        }
        if (activity.huntingActivityId != 0) return null;
        var provider = activity.context.provider(HuntingControlModule.START);
        if (provider.isEmpty()) return "hunting_unavailable";
        activity.hunting = provider.get();
        var call = activity.context.call(HuntingControlModule.START, new HuntingStartOptions(config.huntingStyle(), null), work);
        if (call.status() == ModuleCall.Status.UNAVAILABLE) return "hunting_" + call.reason();
        if (call.result().outcome() != HuntingStartResult.Outcome.STARTED) {
            return "hunting_" + call.result().outcome().name().toLowerCase(java.util.Locale.ROOT);
        }
        activity.huntingActivityId = call.result().activity().activityId();
        synchronized (this) {
            if (activity.cycle != null && activity.cycle.afterSale != null) {
                activity.cycle.returned = summary(facts);
                if (activity.cycle.soldEntries > 0) {
                    cyclesCompleted++;
                    remember(activity.cycle.snapshot(true));
                }
                activity.cycle = null;
            }
            if (activity.cycle == null) activity.cycle = new Cycle(++cycleSequence, summary(facts));
        }
        updateHunting(activity, call.result().activity(), false);
        return null;
    }

    private void stopHunting(Activity activity) {
        if (activity.huntingActivityId == 0) return;
        var stopped = activity.hunting.stop(activity.huntingActivityId);
        if (stopped.activity().activityId() == activity.huntingActivityId) {
            updateHunting(activity, stopped.activity(), true);
            if (stopped.activity().lootOutcome() == soloMapling.game.loot.LootResult.Outcome.INDETERMINATE) {
                activity.lootIndeterminate = true;
            }
        }
        activity.huntingActivityId = 0;
    }

    private synchronized void updateHunting(Activity activity, HuntingSnapshot current, boolean finished) {
        if (activity.cycle == null) return;
        var cycle = activity.cycle;
        cycle.hunting = new TrainingScheduleStatus.HuntingSummary(current.activityId(), cycle.kills + current.kills(),
                cycle.lootCalls + current.lootCalls(), cycle.collectedObjects + current.collectedObjects(),
                cycle.collectedQuantity + current.collectedQuantity(), current.endReason());
        if (finished) {
            cycle.kills += current.kills(); cycle.lootCalls += current.lootCalls();
            cycle.collectedObjects += current.collectedObjects(); cycle.collectedQuantity += current.collectedQuantity();
        }
    }

    private Journey travel(Activity activity, ExecutionScope.WorkHandle work, int destination) {
        stopHunting(activity);
        var current = observe(activity);
        synchronized (this) { activity.destinationMapId = destination; }
        if (current.mapId() == destination) return new Journey(true, null);
        ModuleCall<TravelPlanResult> plan;
        try { plan = activity.context.call(TravelPlanModule.PLAN, new TravelPlanRequest(current.mapId(), destination), work); }
        catch (CancellationException stopped) {
            if (work.cancelRequested()) throw stopped;
            return new Journey(false, "travel_plan_cancelled");
        }
        if (plan.status() == ModuleCall.Status.UNAVAILABLE) return new Journey(false, "travel_" + plan.reason());
        if (plan.result().outcome() != TravelPlanResult.Outcome.FOUND) {
            return new Journey(false, "travel_" + plan.result().outcome().name().toLowerCase(java.util.Locale.ROOT));
        }
        for (var leg : plan.result().route()) {
            if (work.cancelRequested()) throw new CancellationException("training_stopped");
            var portal = observe(activity, scene -> {
                if (scene.map().getId() != leg.fromMapId()) return null;
                var value = scene.map().getPortal(leg.exitPortalId());
                if (value == null || !value.getName().equals(leg.exitPortalName()) || value.getTargetMapId() != leg.toMapId()) {
                    return null;
                }
                return new Position(value.getPosition().x, value.getPosition().y);
            });
            if (portal == null) {
                return new Journey(false, observe(activity, scene -> scene.map().getId()) != leg.fromMapId()
                        ? null : "route_portal_changed");
            }
            var move = activity.context.call(NavigationActionModule.MOVE_TO, new MoveToRequest(portal.x(), portal.y()), work);
            if (move.status() == ModuleCall.Status.UNAVAILABLE) return new Journey(false, "navigation_" + move.reason());
            if (move.result().outcome() != NavigationResult.Outcome.ARRIVED) {
                return new Journey(false, "navigation_" + move.result().outcome().name().toLowerCase(java.util.Locale.ROOT));
            }
            var entered = activity.context.call(NativeActionModule.ENTER_PORTAL, new PortalRequest(leg.exitPortalId()), work);
            if (entered.status() == ModuleCall.Status.UNAVAILABLE) return new Journey(false, "portal_" + entered.reason());
            var result = entered.result();
            if (result.outcome() != PortalResult.Outcome.ENTERED) {
                if (result.outcome() == PortalResult.Outcome.UNKNOWN) activity.portalIndeterminate = true;
                return new Journey(false, "portal_" + result.outcome().name().toLowerCase(java.util.Locale.ROOT));
            }
            synchronized (this) {
                activity.currentMapId = result.after().mapId();
                if (activity.cycle != null) {
                    if (activity.cycle.portals.size() == MAX_CYCLE_PORTALS) {
                        activity.cycle.portals.removeFirst(); activity.cycle.portalsTruncated = true;
                    }
                    activity.cycle.portals.addLast(new TrainingScheduleStatus.PortalStep(result.before().mapId(),
                            result.exit().portalId(), result.exit().portalName(), result.after().mapId(),
                            result.entry().portalId(), result.entry().portalName(), result.before().x(), result.before().y(),
                            result.after().x(), result.after().y()));
                }
            }
            activity.context.recordActivity(work, "portal_entered", Integer.toString(result.after().mapId()));
            if (result.after().mapId() != leg.toMapId()) return new Journey(false, "portal_destination_changed");
        }
        return new Journey(observe(activity, scene -> scene.map().getId()) == destination, null);
    }

    private String sell(Activity activity, ExecutionScope.WorkHandle work) {
        var before = observe(activity);
        if (!config.capacity().evaluate(before.inventory()).triggered()) {
            synchronized (this) { if (activity.cycle != null) activity.cycle.afterSale = summary(before); }
            stage(activity, Stage.RETURNING);
            return null;
        }
        if (before.mapId() != config.shopMapId()) { stage(activity, Stage.TO_SHOP); return null; }
        var candidates = config.sale().candidates(before.inventory());
        if (candidates.isEmpty()) return "no_saleable_items";
        if (before.npc() == null) return "shop_npc_unavailable";
        var move = activity.context.call(NavigationActionModule.MOVE_TO,
                new MoveToRequest(before.npc().x(), before.npc().y()), work);
        if (move.status() == ModuleCall.Status.UNAVAILABLE) return "navigation_" + move.reason();
        if (move.result().outcome() != NavigationResult.Outcome.ARRIVED) {
            return "navigation_" + move.result().outcome().name().toLowerCase(java.util.Locale.ROOT);
        }
        var provider = activity.context.provider(ShopActionModule.OPEN);
        if (provider.isEmpty()) return "shop_unavailable";
        activity.shop = provider.get();
        var opened = activity.context.call(ShopActionModule.OPEN, new ShopOpenRequest(config.shopNpcId()), work);
        if (opened.status() == ModuleCall.Status.UNAVAILABLE) return "shop_" + opened.reason();
        if (opened.result().outcome() != ShopResult.Outcome.OPENED) {
            return "shop_" + opened.result().outcome().name().toLowerCase(java.util.Locale.ROOT);
        }
        activity.shopInteractionId = opened.result().interactionId();
        synchronized (this) {
            if (activity.cycle != null && activity.cycle.beforeSale == null) activity.cycle.beforeSale = summary(before);
        }
        String rejection = null;
        try {
            while (!work.cancelRequested()) {
                var current = observe(activity);
                candidates = config.sale().candidates(current.inventory());
                if (candidates.isEmpty()) break;
                var batch = candidates.subList(0, Math.min(32, candidates.size()));
                var sold = activity.context.call(ShopActionModule.SELL, new ShopSellRequest(batch), work);
                if (sold.status() == ModuleCall.Status.UNAVAILABLE) { rejection = "shop_" + sold.reason(); break; }
                synchronized (this) {
                    if (activity.cycle != null) {
                        var cycle = activity.cycle;
                        cycle.saleOutcome = sold.result().outcome().name(); cycle.saleReason = sold.result().reason();
                        if (cycle.saleMesoBefore == null) cycle.saleMesoBefore = sold.result().mesoBefore();
                        cycle.saleMesoAfter = sold.result().mesoAfter();
                        for (var row : sold.result().sales()) {
                            if (row.outcome() != ShopResult.SaleOutcome.SOLD) continue;
                            cycle.soldEntries++; cycle.soldQuantity += row.quantityRemoved();
                            if (cycle.sales.size() == MAX_SALE_FACTS) {
                                cycle.sales.removeFirst(); cycle.salesTruncated = true;
                            }
                            cycle.sales.addLast(new TrainingScheduleStatus.SaleFact(row.request(), row.quantityRemoved(), row.mesoDelta()));
                        }
                    }
                }
                if (sold.result().sales().stream().anyMatch(row -> row.handlerError()
                        || row.dispatchStarted() && row.outcome() != ShopResult.SaleOutcome.SOLD
                        && (row.quantityRemoved() > 0 || row.mesoDelta() != 0))) {
                    activity.saleIndeterminate = true;
                    rejection = "shop_sale_indeterminate";
                    break;
                }
                if (sold.result().outcome() != ShopResult.Outcome.SOLD) {
                    rejection = "shop_" + sold.result().outcome().name().toLowerCase(java.util.Locale.ROOT);
                    break;
                }
            }
        } finally { closeShop(activity); }
        if (work.cancelRequested()) throw new CancellationException("training_stopped");
        var after = observe(activity);
        synchronized (this) { if (activity.cycle != null) activity.cycle.afterSale = summary(after); }
        if (activity.saleIndeterminate) return "shop_sale_indeterminate";
        if (config.capacity().evaluate(after.inventory()).triggered()) {
            return rejection == null ? "space_not_freed" : rejection;
        }
        stage(activity, Stage.RETURNING);
        return null;
    }

    private void closeShop(Activity activity) {
        if (activity.shopInteractionId == 0) return;
        var closed = activity.shop.close(activity.shopInteractionId);
        if (closed.outcome() != ShopResult.Outcome.CLOSED && closed.outcome() != ShopResult.Outcome.ALREADY_CLOSED
                && !"shop_interaction_changed".equals(closed.reason())) {
            throw new IllegalStateException("training_shop_cleanup_failed");
        }
        activity.shopInteractionId = 0;
    }

    private synchronized void stage(Activity activity, Stage stage) {
        if (activity.state == State.STOPPING || activity.task.work().cancelRequested()) return;
        activity.stage = stage; activity.state = State.RUNNING; activity.reason = null; activity.blockedOn = null;
        activity.context.recordActivity(activity.task.work(), "stage_changed", stage.name());
    }

    private synchronized void remember(TrainingScheduleStatus.Cycle cycle) {
        if (recent.size() == MAX_RECENT_CYCLES) recent.removeFirst();
        recent.addLast(cycle);
    }

    private TrainingScheduleStatus.InventorySummary summary(Observation facts) {
        var quantities = new LinkedHashMap<Integer, Integer>();
        for (var id : watchedItemIds.stream().limit(MAX_QUANTITY_FACTS).toList()) {
            quantities.put(id, facts.inventory().bags().stream().flatMap(bag -> bag.items().stream())
                    .filter(item -> item.itemId() == id).mapToInt(InventorySnapshot.ItemSlot::quantity).sum());
        }
        return new TrainingScheduleStatus.InventorySummary(facts.inventory().bags().stream()
                .filter(bag -> config.capacity().inventoryTypes().contains(bag.inventoryType()))
                .map(bag -> new TrainingScheduleStatus.BagCapacity(bag.inventoryType(), bag.slotLimit(), bag.freeSlots())).toList(),
                facts.meso(), quantities, watchedItemIds.size() > MAX_QUANTITY_FACTS);
    }

    private BlockedCondition condition(Activity activity, Observation facts) {
        var provider = activity.context.provider(HuntingControlModule.START);
        var hunting = provider.map(HuntingControlModule::snapshot).orElse(null);
        return new BlockedCondition(facts, providers(activity.context), hunting == null ? 0 : hunting.activityId(),
                hunting == null ? null : hunting.state());
    }

    private static List<Provider> providers(CharacterRuntime.ModuleContext context) {
        return context.help().stream()
                .filter(function -> COLLABORATORS.contains(function.get("name")))
                .map(function -> new Provider((String) function.get("name"), (Boolean) function.get("available"),
                        ((Number) function.get("module_generation")).longValue()))
                .sorted(Comparator.comparing(Provider::name)).toList();
    }

    private static boolean functionAvailable(CharacterRuntime.ModuleContext context, String name) {
        return providers(context).stream().anyMatch(provider -> provider.name().equals(name) && provider.available());
    }

    private <T> T observe(Activity activity, Function<GameContext.SceneRef, T> read) {
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var entered = new AtomicBoolean();
        var step = activity.context.acceptWork(activity.task.work());
        try {
            activity.context.submit("training-observe", step, () -> {}, () -> {
                entered.set(true);
                try {
                    if (activity.task.work().cancelRequested()) throw new CancellationException("training_stopped");
                    var scene = NativeContextChecks.captureScene(server, activity.context.characterRef());
                    value.set(read.apply(scene));
                    NativeContextChecks.requireSceneCurrent(server, scene);
                } catch (RuntimeException | Error problem) { failure.set(problem); }
            }, () -> {});
        } catch (RuntimeException rejected) { step.reject(); throw rejected; }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof Error fatal) throw fatal;
        if (failure.get() instanceof RuntimeException problem) throw problem;
        if (!entered.get()) throw new CancellationException("training_work_not_entered");
        return value.get();
    }

    private Observation observe(Activity activity) {
        return observe(activity, scene -> {
            var actor = scene.actor().character();
            var npc = scene.map().getNPCById(config.shopNpcId());
            var shoes = actor.getInventory(InventoryType.EQUIPPED).getItem((short) -7);
            return new Observation(System.identityHashCode(scene.map()), scene.map().getId(), actor.getPosition().x,
                    actor.getPosition().y, actor.isAlive(), actor.isChangingMaps(), InventoryFacts.read(actor), actor.getMeso(),
                    npc == null ? null : new Position(npc.getPosition().x, npc.getPosition().y),
                    NativeShopActions.cooldownReady(server, actor), actor.getShop() == null ? 0 : actor.getShop().getId(),
                    Arrays.stream(Disease.values()).filter(actor::hasDisease).toList(), actor.getChair(),
                    actor.getBuffedValue(BuffStat.MONSTER_RIDING), actor.getTotalMoveSpeedStat(), actor.getTotalJumpStat(),
                    shoes == null ? 0 : shoes.getItemId(), shoes == null ? 0 : shoes.getFlag(),
                    scene.map().getPortals().stream().map(portal -> new PortalState(portal.getId(),
                            portal.getPortalStatus(), portal.getPortalState())).sorted(Comparator.comparingInt(PortalState::id)).toList());
        });
    }

    private record Provider(String name, boolean available, long generation) { }
    private record Position(int x, int y) { }
    private record PortalState(int id, boolean status, boolean state) { }
    private record Observation(int sceneIdentity, int mapId, int x, int y, boolean alive, boolean changingMaps,
                               InventorySnapshot inventory, int meso, Position npc, boolean npcReady, int shopId,
                               List<Disease> diseases, int chair, Integer riding, int speed, int jump, int shoesId,
                               int shoesFlag, List<PortalState> portals) { }
    private record BlockedCondition(Observation facts, List<Provider> providers, long huntingActivityId,
                                    HuntingControlModule.State huntingState) { }
    private record Journey(boolean arrived, String reason) { }

    private static final class Activity {
        final long id;
        final CharacterRuntime.ModuleContext context;
        ModuleTask task;
        volatile State state = State.RUNNING;
        Stage stage = Stage.STARTING;
        String reason;
        Integer currentMapId;
        Integer destinationMapId;
        BlockedCondition blockedOn;
        HuntingControlModule hunting;
        long huntingActivityId;
        ShopActionModule shop;
        long shopInteractionId;
        boolean lootIndeterminate;
        boolean saleIndeterminate;
        boolean portalIndeterminate;
        boolean deathObserved;
        boolean reviveAttempted;
        String reviveWaitReason;
        Cycle cycle;
        Activity(long id, CharacterRuntime.ModuleContext context) { this.id = id; this.context = context; }
    }

    private static final class Cycle {
        final long index;
        final TrainingScheduleStatus.InventorySummary huntingStarted;
        TrainingScheduleStatus.InventorySummary capacityTriggered;
        TrainingScheduleStatus.HuntingSummary hunting;
        TrainingScheduleStatus.InventorySummary beforeSale;
        TrainingScheduleStatus.InventorySummary afterSale;
        TrainingScheduleStatus.InventorySummary returned;
        final ArrayDeque<TrainingScheduleStatus.PortalStep> portals = new ArrayDeque<>();
        boolean portalsTruncated;
        int kills, lootCalls, collectedObjects, collectedQuantity;
        int soldEntries, soldQuantity;
        Integer saleMesoBefore;
        int saleMesoAfter;
        String saleOutcome;
        String saleReason;
        final ArrayDeque<TrainingScheduleStatus.SaleFact> sales = new ArrayDeque<>();
        boolean salesTruncated;
        Cycle(long index, TrainingScheduleStatus.InventorySummary started) { this.index = index; huntingStarted = started; }
        TrainingScheduleStatus.Cycle snapshot(boolean complete) {
            return new TrainingScheduleStatus.Cycle(index, huntingStarted, capacityTriggered, hunting, beforeSale, afterSale,
                    returned, saleMesoBefore == null ? null : new TrainingScheduleStatus.SaleSummary(saleOutcome,
                    saleReason, soldEntries, soldQuantity, saleMesoBefore, saleMesoAfter, List.copyOf(sales), salesTruncated),
                    List.copyOf(portals), portalsTruncated, complete);
        }
    }
}
