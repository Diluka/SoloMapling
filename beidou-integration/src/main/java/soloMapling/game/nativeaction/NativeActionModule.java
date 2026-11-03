package soloMapling.game.nativeaction;

import org.gms.net.server.Server;
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
            List.of(), "返回实际复活结果、HP/MP/地图位置前后事实、原生派发及事件/标准路径证据。",
            "本次原生处理及提供者短命令真实退出后返回；事件接管后的后续任务继续由原生事件所有者管理。",
            (module, arguments) -> ReviveRequest.NORMAL,
            (module, context, work, input) -> module.revive(input, context, work), ReviveResult::toMap);
    public static final ModuleFunction<NativeActionModule, PortalRequest, PortalResult> ENTER_PORTAL = new ModuleFunction<>(
            "native.enterPortal", "进入当前实际场景的普通门户，沿原生准入、跨图和地图进入完成流程执行。",
            List.of(new ModuleFunction.Parameter("portalId", ModuleFunction.Type.INTEGER, true, "当前实际场景的门户ID")),
            "返回实际来源与目标场景/位置、出口与入口、原生派发和地图进入完成事实。",
            "本次原生跨图、对应地图进入完成处理和提供者短work真实退出后返回。",
            (module, arguments) -> new PortalRequest(new BigDecimal(arguments.get("portalId").toString()).intValueExact()),
            (module, context, work, input) -> module.enterPortal(input, context, work), PortalResult::toMap);
    public static final ModuleDefinition<Config, NativeActionModule> DEFINITION = new ModuleDefinition<>(
            "native-actions", Set.of("revive", "portal"), new ExecutionScope.Limits(8, 2, 2, 1),
            config -> new NativeActionModule(Server.getInstance()), List.of(REVIVE, ENTER_PORTAL));

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
        var result = new AtomicReference<PortalResult>();
        var failure = new AtomicReference<Throwable>();
        var step = context.acceptWork(work);
        try {
            context.submit("portal", step, () -> {}, () -> {
                try { result.set(NativePortalActions.enterPortal(server, scene, work, input)); }
                catch (RuntimeException | Error stopped) { failure.set(stopped); }
            }, () -> {});
        } catch (RuntimeException rejected) {
            step.reject();
            throw rejected;
        }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof RuntimeException unavailable) throw unavailable;
        if (failure.get() instanceof Error fatal) throw fatal;
        if (result.get() == null && work.cancelRequested()) {
            return new PortalResult(PortalResult.Outcome.CANCELLED, scene.actor().character().getId(),
                    scene.actor().epoch().value(), input.portalId(), null, null, null, null,
                    new PortalResult.Dispatch(false, false, false),
                    new PortalResult.Transition(false, false, false, false, false), null, true,
                    "portal_cancelled_before_dispatch");
        }
        if (result.get() == null) throw new CancellationException("portal_work_not_entered");
        return result.get();
    }
}
