package soloMapling.game.navigation;

import org.gms.net.server.Server;
import soloMapling.game.action.NativeMovementActions;
import soloMapling.game.scene.SceneRuntimeManager;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.ActionModule;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One target-directed navigation task, using the role's original native lane and module scope. */
public final class NavigationActionModule implements ActionModule {
    public record Config(int arrivalTolerancePx, int timeoutMs, int stalledTimeoutMs) { }
    public static final Config DEFAULT_CONFIG = new Config(8, 30_000, 3_000);
    public static final ModuleFunction<NavigationActionModule, MoveToRequest, NavigationResult> MOVE_TO = new ModuleFunction<>(
            "navigation.moveTo", "在当前实际场景内规划并移动到指定坐标，等待抵达或失败及收尾。",
            List.of(new ModuleFunction.Parameter("x", ModuleFunction.Type.INTEGER, true, "目标横坐标"),
                    new ModuleFunction.Parameter("y", ModuleFunction.Type.INTEGER, true, "目标纵坐标"),
                    new ModuleFunction.Parameter("timeout_ms", ModuleFunction.Type.INTEGER, false,
                            "本次规划及移动预算1..120000ms，实际取与模块期限的较小值")),
            "返回outcome、实际末位置、稳定状态、执行步数和经过的基础边类型。",
            "抵达、失败或请求停止后，等待本次移动、原生短work及导航资源实际结束。",
            (module, args) -> new MoveToRequest((int) ((Number) args.get("x")).doubleValue(),
                    (int) ((Number) args.get("y")).doubleValue(),
                    (int) ((Number) args.getOrDefault("timeout_ms", MoveToRequest.MAX_TIMEOUT_MS)).doubleValue()),
            (module, context, work, input) -> module.execute(input, context, work), NavigationResult::toMap);
    public static final ModuleDefinition<Config, NavigationActionModule> DEFINITION = new ModuleDefinition<>(
            "navigation", Set.of("movement"), new ExecutionScope.Limits(16, 2, 4, 2),
            config -> new NavigationActionModule(config, Server.getInstance()), List.of(MOVE_TO));

    private final Config config;
    private final Server server;
    private volatile CharacterRuntime.ModuleContext context;

    public NavigationActionModule(Config config, Server server) {
        this.config = config;
        this.server = server;
    }

    public static Config parseConfig(Map<String, Object> values) {
        return new Config(integer(values, "arrival_tolerance_px", DEFAULT_CONFIG.arrivalTolerancePx(), 4, 32_767),
                integer(values, "timeout_ms", DEFAULT_CONFIG.timeoutMs(), 1, 120_000),
                integer(values, "stalled_timeout_ms", DEFAULT_CONFIG.stalledTimeoutMs(), 1, 120_000));
    }

    private static int integer(Map<String, Object> values, String key, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(values.getOrDefault(key, fallback).toString());
        if (value < minimum || value > maximum) throw new IllegalArgumentException("invalid_navigation_config:" + key);
        return value;
    }

    @Override public void onActivated(CharacterRuntime.ModuleContext context) { this.context = context; }

    public CharacterRuntime.ModuleContext context() { return context; }
    public NativeMovementActions movement() {
        var binding = SceneRuntimeManager.current(server, context.characterRef());
        return binding.scene().characters().motion(binding).movement();
    }

    /** The navigation provider supplies the goal; the current scene member owns physical execution. */
    public NavigationResult execute(MoveToRequest input, CharacterRuntime.ModuleContext context,
                                    ExecutionScope.WorkHandle work) {
        var actor = context.prepareNative("movement", work, () -> {},
                (lane, permit, original, character) -> character);
        var binding = SceneRuntimeManager.current(server, actor);
        return binding.scene().characters().motion(binding).moveTo(input, config, context, work);
    }
}
