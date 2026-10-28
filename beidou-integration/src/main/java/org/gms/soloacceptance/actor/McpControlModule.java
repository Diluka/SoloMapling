package org.gms.soloacceptance.actor;

import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeMovementActions;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSlots;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptExecution;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.runtime.RuntimeModule;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP controls consume a host-owned context; role lifetime and execution accounting stay with the host. */
public final class McpControlModule implements RuntimeModule {
    public static final ModuleDefinition<Map<String, Object>, McpControlModule> DEFINITION =
            new ModuleDefinition<>("mcp-control", Set.of(ModuleSlots.CONTROLLER),
                    Set.of("chat", "interaction", "inventory", "movement", "combat"),
                    new ExecutionScope.Limits(16, 2, 16, 2), config -> {
                        if (!config.isEmpty()) throw new IllegalArgumentException("invalid_mcp_module_config");
                    }, config -> new McpControlModule(), List.of());

    private volatile CharacterRuntime.ModuleContext context;
    private final NativeMovementActions movement = new NativeMovementActions();

    private McpControlModule() { }

    @Override
    public void onActivated(CharacterRuntime.ModuleContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @Override
    public void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        // The host revokes and drains this module. Existing CM and the native role belong to the root.
    }

    /** Retained after stopping for factual results; each ordinary context operation checks host authority. */
    public CharacterRuntime.ModuleContext context() {
        CharacterRuntime.ModuleContext captured = context;
        if (captured == null) throw new CharacterRuntime.Invalid("module_not_active");
        return captured;
    }

    public ExecutionScope.WorkHandle acceptWork() { return context().acceptWork(); }
    NativeMovementActions movement() { return movement; }

    public NativeScriptExecution runNativeScript(ExecutionScope.WorkHandle work, NativeScriptRequest request,
                                                  NativeScriptAdapter adapter) {
        return context().runNativeScript(work, request, adapter);
    }

    public List<Map<String, Object>> nativeScriptContexts() { return context().nativeScriptContexts(); }

    public CharacterCommandLane.Submission submitInteraction(ExecutionScope.WorkHandle work, Runnable preflight,
                                                              Runnable body, Runnable settled) {
        return context().submit("interaction", work, preflight, body, settled);
    }

    public <T> T prepareChat(ExecutionScope.WorkHandle work, Runnable preflight,
                             CharacterRuntime.NativePreparation<T> preparation) {
        return context().prepareNative("chat", work, preflight, preparation);
    }

    public <T> T prepareInventoryMove(ExecutionScope.WorkHandle work, Runnable preflight,
                                      CharacterRuntime.NativePreparation<T> preparation) {
        return context().prepareNative("inventory", work, preflight, preparation);
    }

    public <T> T prepareBasic(String control, ExecutionScope.WorkHandle work, Runnable preflight,
                             CharacterRuntime.NativePreparation<T> preparation) {
        return context().prepareNative(control, work, preflight, preparation);
    }

    public void recordResult(ExecutionScope.WorkHandle work, NativeActionResult result) {
        context().recordResult(work, result);
    }

    public CharacterRuntime.ModuleRef reference() { return context().reference(); }
}
