package soloMapling.game.nativeaction;

import org.gms.net.server.Server;
import soloMapling.game.action.NativeReviveActions;
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
    public static final ModuleDefinition<Config, NativeActionModule> DEFINITION = new ModuleDefinition<>(
            "native-actions", Set.of("revive"), new ExecutionScope.Limits(8, 2, 2, 1),
            config -> new NativeActionModule(Server.getInstance()), List.of(REVIVE));

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
}
