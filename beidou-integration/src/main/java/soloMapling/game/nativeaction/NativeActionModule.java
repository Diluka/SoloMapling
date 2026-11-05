package soloMapling.game.nativeaction;

import org.gms.net.server.Server;
import org.gms.server.maps.MapItem;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeBasicActions;
import soloMapling.game.action.NativeItemActions;
import soloMapling.game.action.PickupRequest;
import soloMapling.game.action.PickupResult;
import soloMapling.game.action.NativePortalActions;
import soloMapling.game.action.NativeReviveActions;
import soloMapling.game.action.PortalRequest;
import soloMapling.game.action.PortalResult;
import soloMapling.game.action.ReviveRequest;
import soloMapling.game.action.ReviveResult;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.ActionModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.scene.SceneActionDispatch;
import soloMapling.game.scene.SceneRuntimeManager;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** A loadable container of semantic native operations, each with its own typed call contract. */
public final class NativeActionModule implements ActionModule {
    public record Config() { }
    public static final Config DEFAULT_CONFIG = new Config();
    public static final ModuleFunction<NativeActionModule, ReviveRequest, ReviveResult> REVIVE = new ModuleFunction<>(
            "native.revive", "通过原生普通复活点击流程请求复活，由原事件裁决及原生回城、HP/MP和Buff规则执行。",
            List.of(), "返回实际复活结果、HP/MP/地图位置及换图状态前后事实、地图进入确认和事件/标准路径证据。",
            "本次原生处理、已发生换图的进入确认及提供者短命令真实退出后返回；事件后续任务由原生事件所有者管理。",
            (module, arguments) -> ReviveRequest.NORMAL,
            (module, context, work, input) -> module.revive(input, context, work), ReviveResult::toMap);
    public static final ModuleFunction<NativeActionModule, PortalRequest, PortalResult> ENTER_PORTAL = new ModuleFunction<>(
            "native.enterPortal", "进入当前实际场景的普通门户，沿原生准入、跨图和地图进入完成流程执行。",
            List.of(new ModuleFunction.Parameter("portalId", ModuleFunction.Type.INTEGER, true, "当前实际场景的门户ID")),
            "返回实际来源与目标场景/位置、出口与入口、原生派发和地图进入完成事实。",
            "本次原生跨图、对应地图进入完成处理和提供者短work真实退出后返回。",
            (module, arguments) -> new PortalRequest(new BigDecimal(arguments.get("portalId").toString()).intValueExact()),
            (module, context, work, input) -> module.enterPortal(input, context, work), PortalResult::toMap);
    public static final ModuleFunction<NativeActionModule, PickupRequest, PickupResult> PICKUP = new ModuleFunction<>(
            "native.pickup", "在当前位置沿原生规则拾取一个实际地物，不扫描、不接近目标。",
            List.of(new ModuleFunction.Parameter("object_id", ModuleFunction.Type.INTEGER, true, "当前实际场景的地物OID")),
            "返回实际入包数量、地物移除及身份确认，区分繁忙、容量不足、拒绝和未知结果。",
            "本次原生命令及原提供者work真实退出后返回；已入包事实不因停止或被替换而撤回。",
            (module, arguments) -> new PickupRequest(new BigDecimal(arguments.get("object_id").toString()).intValueExact()),
            (module, context, work, input) -> module.pickup(input, context, work), PickupResult::toMap);
    public static final ModuleDefinition<Config, NativeActionModule> DEFINITION = new ModuleDefinition<>(
            "native-actions", Set.of("revive", "portal", "pickup"), new ExecutionScope.Limits(8, 2, 2, 1),
            config -> new NativeActionModule(Server.getInstance()), List.of(REVIVE, ENTER_PORTAL, PICKUP));

    private final Server server;

    public NativeActionModule(Server server) { this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        if (!values.isEmpty()) throw new IllegalArgumentException("unknown_native_actions_config");
        return DEFAULT_CONFIG;
    }

    public ReviveResult revive(ReviveRequest input, CharacterRuntime.ModuleContext context,
                               ExecutionScope.WorkHandle work) {
        var scene = context.prepareNative("revive", work, () -> {},
                (lane, permit, original, character) -> NativeContextChecks.captureScene(server, character));
        var result = new AtomicReference<ReviveResult>();
        var failure = new AtomicReference<Throwable>();
        var step = context.acceptWork();
        try {
            context.submit("revive", step, () -> {}, () -> {
                try { result.set(NativeReviveActions.revive(server, scene, work)); }
                catch (RuntimeException | Error stopped) { failure.set(stopped); }
            }, () -> {});
        } catch (RuntimeException rejected) {
            step.reject();
            throw rejected;
        }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof RuntimeException unavailable) throw unavailable;
        if (failure.get() instanceof Error fatal) throw fatal;
        if (result.get() == null) throw new CancellationException("revive_work_not_entered");
        return result.get();
    }

    public PortalResult enterPortal(PortalRequest input, CharacterRuntime.ModuleContext context,
                                     ExecutionScope.WorkHandle work) {
        var scene = context.prepareNative("portal", work, () -> {},
                (lane, permit, original, character) -> NativeContextChecks.captureScene(server, character));
        var dispatch = SceneRuntimeManager.current(server, scene.actor()).dispatch();
        var decision = dispatch.request(SceneActionDispatch.Priority.NORMAL, context, work, null, "portal_entry");
        var arrangement = decision.arrangement();
        try {
            if (decision.status() != SceneActionDispatch.Status.GRANTED) {
                return new PortalResult(PortalResult.Outcome.BUSY, scene.actor().character().getId(),
                        scene.actor().epoch().value(), input.portalId(), null, null, null, null,
                        new PortalResult.Dispatch(false, false, false),
                        new PortalResult.Transition(false, false, false, false, false), null, work.cancelRequested(), decision.reason());
            }
            var result = new AtomicReference<PortalResult>();
            var failure = new AtomicReference<Throwable>();
            var step = context.acceptWork(work);
            try {
                context.submit("portal", step, arrangement::requireCurrent, () -> {
                    try { result.set(NativePortalActions.enterPortal(server, scene, work, input, arrangement::requireCurrent)); }
                    catch (RuntimeException | Error stopped) { failure.set(stopped); }
                }, () -> {});
            } catch (RuntimeException rejected) {
                step.reject();
                throw rejected;
            }
            step.completion().toCompletableFuture().join();
            if (failure.get() instanceof RuntimeException unavailable) throw unavailable;
            if (failure.get() instanceof Error fatal) throw fatal;
            var fact = result.get();
            if (fact == null) {
                if (!arrangement.valid() || work.cancelRequested()) {
                    return new PortalResult(!arrangement.valid() ? PortalResult.Outcome.PREEMPTED : PortalResult.Outcome.CANCELLED,
                            scene.actor().character().getId(), scene.actor().epoch().value(), input.portalId(), null, null, null, null,
                            new PortalResult.Dispatch(false, false, false),
                            new PortalResult.Transition(false, false, false, false, false), null, work.cancelRequested(),
                            !arrangement.valid() ? "portal_arrangement_preempted" : "portal_cancelled_before_dispatch");
                }
                throw new CancellationException("portal_work_not_entered");
            }
            if (!fact.dispatch().started() && !arrangement.valid()) {
                return new PortalResult(PortalResult.Outcome.PREEMPTED, fact.characterId(), fact.epoch(), fact.requestedPortalId(),
                        fact.exit(), fact.entry(), fact.before(), fact.after(), fact.dispatch(), fact.transition(), fact.sceneChanged(),
                        fact.cancelRequested(), "portal_arrangement_preempted");
            }
            return fact;
        } finally { dispatch.release(arrangement, "portal_entry_finished"); }
    }

    public PickupResult pickup(PickupRequest input, CharacterRuntime.ModuleContext context,
                                 ExecutionScope.WorkHandle work) {
        var scene = context.prepareNative("pickup", work, () -> {},
                (lane, permit, original, character) -> NativeContextChecks.captureScene(server, character));
        MapItem target;
        if (input.binding() != null) target = input.binding().target();
        else if (scene.map().getMapObject(input.objectId()) instanceof MapItem ground) target = ground;
        else return PickupResult.skipped(input.objectId(), PickupResult.Outcome.REJECTED, "ground_item_not_found");
        var dispatch = SceneRuntimeManager.current(server, scene.actor()).dispatch();
        boolean immediate = input.binding() != null && input.binding().immediate();
        var arrangement = input.binding() == null ? null : input.binding().arrangement();
        boolean ownsArrangement = input.binding() == null;
        if (ownsArrangement) {
            var decision = dispatch.request(SceneActionDispatch.Priority.NORMAL, context, work, target, "native_pickup");
            arrangement = decision.arrangement();
            if (decision.status() != SceneActionDispatch.Status.GRANTED) {
                dispatch.release(arrangement, "native_pickup_not_admitted");
                return PickupResult.skipped(input.objectId(), PickupResult.Outcome.BUSY, decision.reason());
            }
        }
        var currentArrangement = arrangement;
        Runnable authorize = immediate ? dispatch::requireImmediateOpportunity : currentArrangement::requireCurrent;
        try {
            var step = context.acceptWork(work);
            NativeBasicActions.CommandHandle handle;
            try {
                handle = context.prepareNative("pickup", step, authorize,
                        (lane, permit, original, character) -> NativeItemActions.preparePickup(server, lane, permit,
                                original, scene, target));
            } catch (RuntimeException rejected) { step.reject(); throw rejected; }
            var receipt = immediate ? NativeBasicActions.submitIfIdle(handle) : NativeBasicActions.submit(handle);
            var fact = handle.result().toCompletableFuture().join();
            step.completion().toCompletableFuture().join();
            context.recordResult(step, fact);
            var outcome = switch (fact.outcome()) {
                case SUCCEEDED -> PickupResult.Outcome.COLLECTED;
                case INDETERMINATE -> PickupResult.Outcome.INDETERMINATE;
                case CANCELLED -> currentArrangement != null && !currentArrangement.valid()
                        ? PickupResult.Outcome.PREEMPTED : PickupResult.Outcome.CANCELLED;
                default -> "inventory_full".equals(fact.fixedReason()) ? PickupResult.Outcome.CAPACITY_FULL
                        : currentArrangement != null && !currentArrangement.valid() ? PickupResult.Outcome.PREEMPTED
                        : immediate && (receipt.status() == NativeActionService.SubmissionStatus.REJECTED
                            || "scene_immediate_opportunity_busy".equals(fact.fixedReason()))
                                ? PickupResult.Outcome.BUSY : PickupResult.Outcome.REJECTED;
            };
            var facts = fact.facts();
            return new PickupResult(outcome, fact.fixedReason(), input.objectId(), (Integer) facts.get("item_id"),
                    (Integer) facts.getOrDefault("ground_quantity_before", 0),
                    (Integer) facts.getOrDefault("inventory_quantity_delta", 0),
                    fact.dispatch() == NativeActionResult.Dispatch.STARTED, Boolean.TRUE.equals(facts.get("ground_removed")),
                    Boolean.TRUE.equals(facts.get("identity_verified")), fact.handlerError(), fact.cancelRequested());
        } finally {
            if (ownsArrangement) dispatch.release(currentArrangement, "native_pickup_finished");
        }
    }
}
