package soloMapling.game.shop;

import org.gms.client.inventory.InventoryType;
import org.gms.net.server.Server;
import org.gms.server.Shop;
import soloMapling.game.action.NativeShopActions;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.inventory.SaleItem;
import soloMapling.game.runtime.ActionModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ModuleTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Owns an ordinary native shop interaction and its finite, explicitly selected sale calls. */
public final class ShopActionModule implements ActionModule {
    public record Config() { }
    public static final Config DEFAULT_CONFIG = new Config();
    public static final ModuleFunction<ShopActionModule, ShopOpenRequest, ShopResult> OPEN = new ModuleFunction<>(
            "shop.open", "在当前实际地图中走近已登记普通商店NPC后打开商店；保留调用方脚本上下文。",
            List.of(new ModuleFunction.Parameter("npcId", ModuleFunction.Type.INTEGER, true, "当前地图中的NPC模板ID")),
            "返回实际NPC/商店、interaction_id及打开事实；本操作范围为距离NPC不超过100px。",
            "原生商店绑定及短命令实际退出后返回；后续交互由本模块持有至关闭或停止。",
            (module, arguments) -> {
                var value = (Number) arguments.get("npcId");
                if (value.doubleValue() < Integer.MIN_VALUE || value.doubleValue() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("shop_npc_id_out_of_range");
                }
                return new ShopOpenRequest(value.intValue());
            },
            (module, context, work, input) -> module.open(input, context, work), ShopResult::toMap);
    public static final ModuleFunction<ShopActionModule, ShopSellRequest, ShopResult> SELL = new ModuleFunction<>(
            "shop.sell", "在本模块打开的原生商店按明确清单卖出，保留已经发生的扣物及金币事实。",
            List.of(new ModuleFunction.Parameter("items", ModuleFunction.Type.ARRAY, true,
                    "1～32项，每项inventoryType/slot/expectedItemId/quantity；可充能弹药仅支持实际整栈")),
            "返回逐项实际物品/数量/金币前后态、派发状态及整体SOLD/PARTIAL/REJECTED/CANCELLED。",
            "所有已安排原生短命令真实退出；取消时本次交互也完成自己的关闭后返回。",
            (module, arguments) -> {
                var items = new ArrayList<SaleItem>();
                for (var value : (List<?>) arguments.get("items")) {
                    if (!(value instanceof Map<?, ?> item)
                            || !item.keySet().equals(Set.of("inventoryType", "slot", "expectedItemId", "quantity"))
                            || !(item.get("inventoryType") instanceof String type)) {
                        throw new IllegalArgumentException("invalid_shop_sale_item");
                    }
                    int slot = integer(item.get("slot"));
                    if (slot < Short.MIN_VALUE || slot > Short.MAX_VALUE) throw new IllegalArgumentException("invalid_shop_sale_slot");
                    items.add(new SaleItem(InventoryType.valueOf(type), (short) slot,
                            integer(item.get("expectedItemId")), integer(item.get("quantity"))));
                }
                return new ShopSellRequest(items);
            }, (module, context, work, input) -> module.sell(input, context, work), ShopResult::toMap);
    public static final ModuleFunction<ShopActionModule, ShopCloseRequest, ShopResult> CLOSE = new ModuleFunction<>(
            "shop.close", "关闭本模块自己打开的商店，保留角色和其他脚本上下文。", List.of(),
            "返回精确interaction_id及CLOSED/ALREADY_CLOSED结果；不会关闭另一个提供者的商店。",
            "对应原生交互清理、资源释放与本模块受管载体真正结束后返回。",
            (module, arguments) -> ShopCloseRequest.NORMAL,
            (module, context, work, input) -> module.close(input, context, work), ShopResult::toMap);
    public static final ModuleDefinition<Config, ShopActionModule> DEFINITION = new ModuleDefinition<>(
            "shop", Set.of("shop"), new ExecutionScope.Limits(8, 2, 2, 1),
            config -> new ShopActionModule(Server.getInstance()), List.of(OPEN, SELL, CLOSE));

    private final Server server;
    private Job current;
    private ShopResult lastClosed;
    private int characterId;
    private long nextInteractionId;

    public ShopActionModule(Server server) { this.server = server; }
    public static Config parseConfig(Map<String, Object> values) {
        if (!values.isEmpty()) throw new IllegalArgumentException("unknown_shop_config");
        return DEFAULT_CONFIG;
    }
    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext context) {
        if (current != null && current.task.work().isSettled()
                && (current.lease == null || current.lease.registration.released())) current = null;
        characterId = context.characterRef().character().getId();
    }
    @Override public synchronized void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        if (current != null) current.task.cancel();
    }

    public ShopResult open(ShopOpenRequest input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        Job job;
        boolean starting;
        synchronized (this) {
            if (current != null) {
                job = current;
                starting = false;
            } else {
                job = new Job(context);
                starting = true;
                try {
                    job.task = new ModuleTask(context, "shop-interaction-" + characterId, owned -> runInteraction(job));
                } catch (CharacterRuntime.Invalid | ExecutionScope.Invalid rejected) {
                    if (providerUnavailable(rejected)) return idle(ShopResult.Operation.OPEN,
                            ShopResult.Outcome.CANCELLED, providerUnavailable(rejected)
                                    ? "shop_provider_unavailable" : "shop_open_cancelled", work.cancelRequested());
                    throw rejected;
                }
                current = job;
                job.task.completion().whenComplete((ignored, failure) -> {
                    job.started.complete(false);
                    synchronized (ShopActionModule.this) {
                        boolean released = job.lease == null || job.lease.registration.released();
                        if (current == job && released) current = null;
                        if (job.closed != null && (lastClosed == null
                                || job.closed.interactionId() > lastClosed.interactionId())) lastClosed = job.closed;
                    }
                    if (job.failure != null) job.finished.completeExceptionally(job.failure);
                    else job.finished.complete(job.closed == null
                            ? idle(ShopResult.Operation.CLOSE, ShopResult.Outcome.ALREADY_CLOSED, null, true) : job.closed);
                });
                job.task.start();
            }
        }
        if (!starting) try { return nativeStep(context, work, step -> {
            var scene = NativeContextChecks.captureScene(server, context.characterRef());
            var lease = job.lease;
            if (lease == null || job.closing || lease.npcId != input.npcId()
                    || scene.actor().character().getShop() != lease.shop) {
                return idle(ShopResult.Operation.OPEN, ShopResult.Outcome.REJECTED, "shop_interaction_busy", work.cancelRequested());
            }
            NativeContextChecks.requireSceneCurrent(server, lease.scene);
            int meso = scene.actor().character().getMeso();
            return new ShopResult(ShopResult.Operation.OPEN, ShopResult.Outcome.ALREADY_OPEN, null,
                    characterId, lease.npcId, lease.npcOid, lease.shop.getId(), lease.id,
                    meso, meso, List.of(), false, work.cancelRequested());
        }); } catch (StepNotEntered stopped) {
            if (!work.cancelRequested() && !stopped.providerUnavailable) throw stopped;
            // This invocation only inspected an existing view; cancelling it does not acquire that view.
            return idle(ShopResult.Operation.OPEN, ShopResult.Outcome.CANCELLED,
                    stopped.providerUnavailable ? "shop_provider_unavailable" : "shop_open_cancelled", work.cancelRequested());
        }
        if (!job.started.join()) return idle(ShopResult.Operation.OPEN, ShopResult.Outcome.CANCELLED, "shop_open_cancelled", true);
        ShopResult result;
        try {
            result = nativeStep(context, work, step -> {
                var scene = NativeContextChecks.captureScene(server, context.characterRef());
                var opened = NativeShopActions.open(server, scene, input.npcId(), work);
                long id = 0;
                if (opened.shop() != null) {
                    var registration = context.scope().resources().beginOwnedResult(step, "shop_interaction");
                    synchronized (ShopActionModule.this) { id = ++nextInteractionId; }
                    var lease = new Lease(id, scene, opened.npc().getId(), opened.npc().getObjectId(),
                            opened.shop(), registration, opened.mesoAfter());
                    job.lease = lease;
                    registration.attach(lease, job.closeRequested::release);
                    job.created.complete(lease);
                }
                context.recordActivity(step, "shop_open", opened.outcome().name());
                return new ShopResult(ShopResult.Operation.OPEN, opened.outcome(), opened.reason(), characterId,
                        opened.npc() == null ? input.npcId() : opened.npc().getId(),
                        opened.npc() == null ? 0 : opened.npc().getObjectId(),
                        opened.shop() == null ? 0 : opened.shop().getId(), id, opened.mesoBefore(), opened.mesoAfter(),
                        List.of(), opened.dispatched(), work.cancelRequested());
            });
        } catch (StepNotEntered stopped) {
            job.closing = true; job.closeRequested.release(); job.created.complete(job.lease);
            job.finished.join();
            if (!work.cancelRequested() && !stopped.providerUnavailable) throw stopped;
            return idle(ShopResult.Operation.OPEN, ShopResult.Outcome.CANCELLED,
                    stopped.providerUnavailable ? "shop_provider_unavailable" : "shop_open_cancelled", work.cancelRequested());
        } catch (RuntimeException | Error failure) {
            job.closing = true; job.closeRequested.release(); job.created.complete(job.lease);
            job.finished.join();
            throw failure;
        } finally { job.created.complete(job.lease); }
        if (result.outcome() != ShopResult.Outcome.OPENED || work.cancelRequested()) {
            job.closing = true; job.closeRequested.release();
            job.finished.join();
        }
        if (work.cancelRequested() && result.outcome() == ShopResult.Outcome.OPENED) {
            return new ShopResult(result.operation(), ShopResult.Outcome.CANCELLED, "shop_open_cancelled_after_effect",
                    result.characterId(), result.npcId(), result.npcOid(), result.shopId(), result.interactionId(),
                    result.mesoBefore(), result.mesoAfter(), result.sales(), result.nativeDispatched(), true);
        }
        return result;
    }

    public ShopResult sell(ShopSellRequest input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        Job job;
        synchronized (this) { job = current; }
        if (job == null || job.lease == null || job.closing) {
            return idle(ShopResult.Operation.SELL, ShopResult.Outcome.REJECTED, "shop_not_open", work.cancelRequested());
        }
        Lease lease = job.lease;
        var sales = new ArrayList<ShopResult.Sale>();
        String stoppedReason = null;
        try {
            for (var item : input.items()) {
                if (work.cancelRequested()) break;
                var sale = nativeStep(context, work, step -> {
                    var fact = NativeShopActions.sell(server,
                            lease.scene, lease.shop, item, work,
                            () -> ownsInteraction(job, lease));
                    context.recordActivity(step, "shop_sale", fact.outcome().name());
                    return fact;
                });
                sales.add(sale);
                if (sale.outcome() == ShopResult.SaleOutcome.CANCELLED || sale.handlerError()
                        || "shop_interaction_changed".equals(sale.reason())) break;
            }
        } catch (StepNotEntered stopped) {
            if (!work.cancelRequested() && !stopped.providerUnavailable) throw stopped;
            stoppedReason = stopped.providerUnavailable ? "shop_provider_unavailable" : "shop_sale_cancelled";
        } finally {
            if (work.cancelRequested() || stoppedReason != null) {
                job.closing = true; job.closeRequested.release(); job.finished.join();
            }
        }
        int before = sales.isEmpty() ? lease.openedMeso : sales.getFirst().mesoBefore();
        int after = sales.isEmpty() ? before : sales.getLast().mesoAfter();
        boolean all = sales.size() == input.items().size() && sales.stream().allMatch(sale ->
                sale.outcome() == ShopResult.SaleOutcome.SOLD && !sale.handlerError());
        boolean changed = sales.stream().anyMatch(sale -> sale.quantityRemoved() > 0);
        var outcome = all ? ShopResult.Outcome.SOLD : changed ? ShopResult.Outcome.PARTIAL
                : work.cancelRequested() || stoppedReason != null ? ShopResult.Outcome.CANCELLED : ShopResult.Outcome.REJECTED;
        String reason = all ? null : stoppedReason != null ? stoppedReason : work.cancelRequested() ? "shop_sale_cancelled"
                : sales.isEmpty() ? "shop_sale_not_attempted" : sales.getLast().reason();
        return new ShopResult(ShopResult.Operation.SELL, outcome, reason, characterId, lease.npcId, lease.npcOid,
                lease.shop.getId(), lease.id, before, after, sales,
                sales.stream().anyMatch(ShopResult.Sale::dispatchStarted), work.cancelRequested());
    }

    public ShopResult close(ShopCloseRequest input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        Job job;
        synchronized (this) { job = current; }
        if (job == null) return idle(ShopResult.Operation.CLOSE, ShopResult.Outcome.ALREADY_CLOSED, null, work.cancelRequested());
        job.closing = true;
        job.closeRequested.release();
        return job.finished.join();
    }

    /** Exact old interaction cleanup remains available when its caller or this provider is stopping. */
    public ShopResult close(long expectedInteractionId) {
        Job job;
        synchronized (this) {
            job = current;
            if (job == null && lastClosed != null && lastClosed.interactionId() == expectedInteractionId) return lastClosed;
            if (job == null || job.lease == null || job.lease.id != expectedInteractionId) {
                return idle(ShopResult.Operation.CLOSE, ShopResult.Outcome.REJECTED, "shop_interaction_changed", false);
            }
        }
        job.closing = true;
        job.closeRequested.release();
        return job.finished.join();
    }

    private void runInteraction(Job job) {
        job.started.complete(true);
        boolean interrupted = false;
        try {
            Lease lease = job.created.join();
            if (lease == null) return;
            try { job.closeRequested.acquire(); }
            catch (InterruptedException stopped) { interrupted = true; }
            job.closing = true;
            while (true) {
                var cleanup = lease.registration.acquireCleanup();
                var observed = new AtomicReference<ShopResult>();
                var failure = new AtomicReference<Throwable>();
                var submitted = job.context.submitCleanup(cleanup, () -> {
                    try {
                        cleanup.permit().requireExecuting(lease);
                        int before = lease.scene.actor().character().getMeso();
                        boolean dispatched = NativeShopActions.close(lease.scene.actor().character(), lease.shop,
                                () -> cleanup.permit().requireExecuting(lease));
                        lease.registration.confirmReleased(cleanup.permit(), lease);
                        observed.set(new ShopResult(ShopResult.Operation.CLOSE, ShopResult.Outcome.CLOSED,
                                dispatched ? null : "shop_interaction_already_replaced", characterId, lease.npcId,
                                lease.npcOid, lease.shop.getId(), lease.id, before, lease.scene.actor().character().getMeso(),
                                List.of(), dispatched, job.task.work().cancelRequested()));
                    } catch (RuntimeException | Error stopped) { failure.set(stopped); }
                }, () -> { });
                cleanup.work().completion().toCompletableFuture().join();
                if (failure.get() instanceof RuntimeException failed) throw failed;
                if (failure.get() instanceof Error fatal) throw fatal;
                if (observed.get() != null) { job.closed = observed.get(); break; }
                if (submitted.status() != CharacterCommandLane.Status.CLEANUP_PENDING) {
                    throw new IllegalStateException("shop_cleanup_not_entered");
                }
                try { Thread.sleep(5); }
                catch (InterruptedException stopped) { interrupted = true; }
            }
        } catch (RuntimeException | Error failed) {
            // The waiting close receives the failure; an unverified resource remains in its owning scope.
            job.failure = failed;
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private <T> T nativeStep(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle parent,
                              Function<ExecutionScope.WorkHandle, T> body) {
        ExecutionScope.WorkHandle step;
        try { step = context.acceptWork(parent); }
        catch (CharacterRuntime.Invalid | ExecutionScope.Invalid rejected) {
            if (providerUnavailable(rejected)) throw new StepNotEntered(true);
            throw rejected;
        }
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        try {
            context.submit("shop", step, () -> { }, () -> {
                try { value.set(body.apply(step)); }
                catch (RuntimeException | Error stopped) { failure.set(stopped); }
            }, () -> { });
        } catch (RuntimeException rejected) {
            step.reject();
            step.completion().toCompletableFuture().join();
            if ((providerUnavailable(rejected) || rejected instanceof CancellationException && parent.cancelRequested())
                    && step.snapshot().bodyState() == ExecutionScope.BodyState.NOT_STARTED) {
                throw new StepNotEntered(providerUnavailable(rejected));
            }
            throw rejected;
        }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof RuntimeException stopped) throw stopped;
        if (failure.get() instanceof Error fatal) throw fatal;
        if (value.get() == null) throw new StepNotEntered();
        return value.get();
    }
    private synchronized ShopResult idle(ShopResult.Operation operation, ShopResult.Outcome outcome, String reason, boolean cancelled) {
        return new ShopResult(operation, outcome, reason, characterId, 0, 0, 0, 0, 0, 0, List.of(), false, cancelled);
    }
    private synchronized boolean ownsInteraction(Job job, Lease lease) {
        return current == job && job.lease == lease && !job.closing;
    }
    private static int integer(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < Integer.MIN_VALUE || number.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid_shop_sale_integer");
        }
        return number.intValue();
    }
    private static final class StepNotEntered extends CancellationException {
        final boolean providerUnavailable;
        StepNotEntered() { this(false); }
        StepNotEntered(boolean providerUnavailable) { super("shop_work_not_entered"); this.providerUnavailable = providerUnavailable; }
    }
    private static boolean providerUnavailable(RuntimeException failure) {
        return failure instanceof CharacterRuntime.Invalid runtimeInvalid && "module_not_active".equals(runtimeInvalid.reason())
                || failure instanceof ExecutionScope.Invalid scopeInvalid && "scope_not_active".equals(scopeInvalid.reason());
    }
    private static final class Job {
        final CharacterRuntime.ModuleContext context;
        final CompletableFuture<Boolean> started = new CompletableFuture<>();
        final CompletableFuture<Lease> created = new CompletableFuture<>();
        final CompletableFuture<ShopResult> finished = new CompletableFuture<>();
        final Semaphore closeRequested = new Semaphore(0);
        ModuleTask task;
        volatile Lease lease;
        volatile boolean closing;
        ShopResult closed;
        Throwable failure;
        Job(CharacterRuntime.ModuleContext context) { this.context = context; }
    }
    private record Lease(long id, GameContext.SceneRef scene, int npcId, int npcOid, Shop shop,
                          ScopeResources.Registration registration, int openedMeso) { }
}
