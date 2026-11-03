package soloMapling.game.loot;

import org.gms.net.server.Server;
import org.gms.server.maps.MapItem;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeBasicActions;
import soloMapling.game.action.NativeBasicResult;
import soloMapling.game.action.NativeItemAction;
import soloMapling.game.action.NativeItemActions;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.inventory.InventoryFacts;
import soloMapling.game.inventory.ItemCapacity;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.NavigationResult;
import soloMapling.game.runtime.ActionModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;

import java.awt.Point;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Finite explicit-object pickup, using navigation and the existing native item action. */
public final class LootActionModule implements ActionModule {
    public record Config() { }
    public static final Config DEFAULT_CONFIG = new Config();
    private static final int APPROACH_DISTANCE_PX = 60;
    // A visible collection cadence, inherited from the old between-kills loot policy; not a native cooldown.
    private static final int PICKUP_GAP_MS = 250;
    public static final ModuleFunction<LootActionModule, LootRequest, LootResult> COLLECT = new ModuleFunction<>(
            "loot.collect", "接近并沿原生拾取入口收集当前场景的有限普通库存地物。",
            List.of(new ModuleFunction.Parameter("object_ids", ModuleFunction.Type.ARRAY, true,
                            "当前场景明确地物OID，最多32个，按给定顺序处理"),
                    new ModuleFunction.Parameter("timeout_ms", ModuleFunction.Type.INTEGER, false,
                            "整次接近与拾取期限1..120000ms，默认30000ms")),
            "返回逐对象的实际入包、拒绝或未知事实，容量不足结束本轮并保留已拾取效果。",
            "当前拾取与接近导航真实退出后返回；取消不撤回已发生的入包事实。",
            (module, arguments) -> new LootRequest(((List<?>) arguments.get("object_ids")).stream()
                    .map(value -> {
                        if (!(value instanceof Number number)) throw new IllegalArgumentException("invalid_loot_object_id");
                        return new BigDecimal(number.toString()).intValueExact();
                    }).toList(), new BigDecimal(arguments.getOrDefault("timeout_ms", 30_000).toString()).intValueExact()),
            (module, context, work, input) -> module.collect(input, context, work), LootResult::toMap);
    public static final ModuleDefinition<Config, LootActionModule> DEFINITION = new ModuleDefinition<>(
            "loot", Set.of("loot"), new ExecutionScope.Limits(8, 2, 2, 1),
            config -> new LootActionModule(Server.getInstance()), List.of(COLLECT));

    private final Server server;
    private Job active;

    public LootActionModule(Server server) { this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        if (!values.isEmpty()) throw new IllegalArgumentException("unknown_loot_config");
        return DEFAULT_CONFIG;
    }

    @Override public synchronized void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        if (active != null) active.stopRequested = true;
    }

    public LootResult collect(LootRequest input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        var job = new Job(context, work, input.timeoutMs());
        synchronized (this) {
            if (active != null) return new LootResult(LootResult.Outcome.BUSY, "loot_busy", List.of(), 0);
            active = job;
        }
        var results = new ArrayList<LootResult.ItemResult>();
        var outcome = LootResult.Outcome.COMPLETED;
        String reason = null;
        try {
            job.scene = context.prepareNative("loot", work, () -> {},
                    (lane, permit, original, character) -> NativeContextChecks.captureScene(server, character));
            for (int objectId : input.objectIds()) {
                checkpoint(job);
                var target = read(job, () -> target(job, objectId));
                if (target.result() != null) {
                    results.add(target.result());
                    if (target.result().outcome() == LootResult.ItemOutcome.CAPACITY_FULL) {
                        outcome = LootResult.Outcome.CAPACITY_FULL;
                        reason = "inventory_full";
                        break;
                    }
                    continue;
                }
                if (read(job, () -> job.scene.actor().character().getPosition().distanceSq(target.position()))
                        > APPROACH_DISTANCE_PX * APPROACH_DISTANCE_PX) {
                    var call = context.call(NavigationActionModule.MOVE_TO,
                            new MoveToRequest(target.position().x, target.position().y, Math.max(1, remaining(job))), work);
                    if (call.status() == ModuleCall.Status.UNAVAILABLE) {
                        outcome = LootResult.Outcome.UNAVAILABLE; reason = call.reason(); break;
                    }
                    var navigation = call.result();
                    if (navigation.outcome() != NavigationResult.Outcome.ARRIVED) {
                        checkpoint(job);
                        if (navigation.outcome() == NavigationResult.Outcome.CONTEXT_CHANGED) {
                            outcome = LootResult.Outcome.CONTEXT_CHANGED; reason = "context_changed"; break;
                        }
                        results.add(new LootResult.ItemResult(objectId, target.itemId(), target.quantity(), 0,
                                LootResult.ItemOutcome.UNREACHABLE, navigation.reason(), null, false));
                        continue;
                    }
                }
                checkpoint(job);
                // Navigation yielded to other role work; inventory and ownership must be current at pickup.
                var current = read(job, () -> target(job, objectId));
                if (current.result() != null) {
                    results.add(current.result());
                    if (current.result().outcome() == LootResult.ItemOutcome.CAPACITY_FULL) {
                        outcome = LootResult.Outcome.CAPACITY_FULL; reason = "inventory_full"; break;
                    }
                    continue;
                }
                var nativeResult = pickUp(job, objectId);
                var facts = nativeResult.facts();
                int gained = ((Number) facts.getOrDefault("inventory_quantity_delta", 0)).intValue();
                var itemOutcome = switch (nativeResult.outcome()) {
                    case SUCCEEDED -> LootResult.ItemOutcome.COLLECTED;
                    case INDETERMINATE -> LootResult.ItemOutcome.INDETERMINATE;
                    default -> LootResult.ItemOutcome.NATIVE_REJECTED;
                };
                Integer itemId = facts.containsKey("item_id") ? ((Number) facts.get("item_id")).intValue() : current.itemId();
                Integer groundQuantity = facts.containsKey("ground_quantity_before")
                        ? ((Number) facts.get("ground_quantity_before")).intValue() : current.quantity();
                results.add(new LootResult.ItemResult(objectId, itemId, groundQuantity, gained,
                        itemOutcome, nativeResult.fixedReason(), nativeResult.outcome(),
                        nativeResult.dispatch() == NativeActionResult.Dispatch.STARTED));
                // An unknown dispatched effect is retained and is never automatically replayed in this pass.
                if (nativeResult.outcome() == NativeActionResult.Outcome.INDETERMINATE) {
                    outcome = LootResult.Outcome.INDETERMINATE; reason = "native_pickup_outcome_unknown"; break;
                }
                checkpoint(job);
                if (results.size() < input.objectIds().size()) {
                    try { Thread.sleep(Math.min(PICKUP_GAP_MS, remaining(job))); }
                    catch (InterruptedException stopped) { job.interrupted = true; throw new Halt(LootResult.Outcome.CANCELLED, "loot_cancelled"); }
                }
            }
        } catch (Halt stopped) {
            outcome = stopped.outcome; reason = stopped.reason;
        } catch (CancellationException stopped) {
            outcome = LootResult.Outcome.CANCELLED; reason = "loot_cancelled";
        } catch (GameContext.Invalid changed) {
            outcome = LootResult.Outcome.CONTEXT_CHANGED; reason = changed.reason().name();
        } catch (IllegalStateException stopped) {
            if (!work.cancelRequested() && context.scope().isActive()) throw stopped;
            outcome = LootResult.Outcome.CANCELLED; reason = "loot_cancelled";
        } finally {
            synchronized (this) { active = null; }
            if (job.interrupted) Thread.currentThread().interrupt();
        }
        return new LootResult(outcome, reason, results, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.started));
    }

    private NativeBasicResult pickUp(Job job, int objectId) {
        var step = job.context.acceptWork(job.work);
        NativeBasicActions.CommandHandle handle;
        try {
            handle = job.context.prepareNative("loot", step, () -> {}, (lane, permit, original, character) ->
                    NativeItemActions.prepare(server, lane, permit, original, job.scene, new NativeItemAction.Pickup(objectId)));
            NativeBasicActions.submit(handle);
        } catch (RuntimeException failure) {
            step.reject();
            throw failure;
        }
        NativeBasicResult result;
        try {
            try { result = handle.result().toCompletableFuture().get(Math.max(1, remaining(job)), TimeUnit.MILLISECONDS); }
            catch (TimeoutException elapsed) {
                job.context.cancel(step);
                result = handle.result().toCompletableFuture().join();
            } catch (InterruptedException stopped) {
                job.interrupted = true;
                job.stopRequested = true;
                job.context.cancel(step);
                result = handle.result().toCompletableFuture().join();
            } catch (ExecutionException failed) { throw new CompletionException(failed.getCause()); }
        } finally {
            // Completion and cancellation of the public result cannot stand in for physical exit.
            step.completion().toCompletableFuture().join();
        }
        job.context.recordResult(step, result);
        return result;
    }

    private Target target(Job job, int objectId) {
        var actor = job.scene.actor().character();
        if (!(job.scene.map().getMapObject(objectId) instanceof MapItem ground)) {
            return new Target(null, null, null, new LootResult.ItemResult(objectId, null, null, 0,
                    LootResult.ItemOutcome.NOT_FOUND, "ground_item_not_found", null, false));
        }
        ground.lockItem();
        try {
            var item = ground.getItem();
            Integer id = item == null ? null : item.getItemId();
            Integer quantity = item == null ? null : (int) item.getQuantity();
            LootResult.ItemOutcome outcome = null;
            String reason = null;
            if (ground.isPickedUp() || job.scene.map().getMapObject(objectId) != ground) {
                outcome = LootResult.ItemOutcome.NOT_FOUND; reason = "ground_item_changed";
            } else if (ground.getMeso() != 0 || !NativeItemActions.supportsPickup(item)) {
                outcome = LootResult.ItemOutcome.UNSUPPORTED; reason = "unsupported_pickup_item";
            } else if (System.currentTimeMillis() - ground.getDropTime() < 400) {
                outcome = LootResult.ItemOutcome.TOO_FRESH; reason = "ground_item_not_settled";
            } else if (!ground.canBePickedBy(actor)) {
                outcome = LootResult.ItemOutcome.OWNER_REJECTED; reason = "ground_item_owner_rejected";
            } else {
                var capacity = InventoryFacts.capacity(actor, item);
                if (!capacity.fits()) {
                    boolean full = capacity.outcome() == ItemCapacity.Outcome.NO_SPACE;
                    outcome = full ? LootResult.ItemOutcome.CAPACITY_FULL : LootResult.ItemOutcome.PICKUP_RESTRICTED;
                    reason = full ? "inventory_full" : "pickup_restricted";
                }
            }
            return new Target(new Point(ground.getPosition()), id, quantity,
                    outcome == null ? null : new LootResult.ItemResult(objectId, id, quantity, 0, outcome, reason, null, false));
        } finally { ground.unlockItem(); }
    }

    private <T> T read(Job job, Supplier<T> observation) {
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var step = job.context.acceptWork(job.work);
        try {
            job.context.submit("loot", step, () -> {}, () -> {
                try { checkpoint(job); value.set(observation.get()); }
                catch (RuntimeException | Error stopped) { failure.set(stopped); }
            }, () -> {});
        } catch (RuntimeException rejected) { step.reject(); throw rejected; }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof Error fatal) throw fatal;
        if (failure.get() instanceof RuntimeException stopped) throw stopped;
        if (step.snapshot().skipped()) throw new Halt(LootResult.Outcome.CANCELLED, "loot_work_not_entered");
        return value.get();
    }

    private void checkpoint(Job job) {
        if (Thread.interrupted()) { job.interrupted = true; job.stopRequested = true; }
        if (job.stopRequested || job.work.cancelRequested()) throw new Halt(LootResult.Outcome.CANCELLED, "loot_cancelled");
        if (remaining(job) <= 0) throw new Halt(LootResult.Outcome.TIMED_OUT, "loot_timeout");
        NativeContextChecks.requireSceneCurrent(server, job.scene);
        if (!job.scene.actor().character().isAlive()) throw new Halt(LootResult.Outcome.DEAD, "dead");
    }

    private static int remaining(Job job) {
        return Math.max(0, job.timeoutMs - (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.started));
    }

    private record Target(Point position, Integer itemId, Integer quantity, LootResult.ItemResult result) { }
    private static final class Halt extends RuntimeException {
        final LootResult.Outcome outcome;
        final String reason;
        Halt(LootResult.Outcome outcome, String reason) { super(reason, null, false, false); this.outcome = outcome; this.reason = reason; }
    }
    private static final class Job {
        final CharacterRuntime.ModuleContext context;
        final ExecutionScope.WorkHandle work;
        final int timeoutMs;
        final long started = System.nanoTime();
        GameContext.SceneRef scene;
        volatile boolean stopRequested;
        boolean interrupted;
        Job(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work, int timeoutMs) {
            this.context = context; this.work = work; this.timeoutMs = timeoutMs;
        }
    }
}
