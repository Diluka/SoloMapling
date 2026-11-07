package soloMapling.game.scene;

import soloMapling.game.context.GameContext;
import org.gms.net.server.Server;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.RuntimeModule;

import java.util.List;
import java.util.Set;

/** The role-owned scope for scene behavior; map changes replace only its scene-local binding. */
public final class SceneMembershipModule implements RuntimeModule {
    public static final String CONTROL = "scene-client";
    public record Config(CharacterRuntime runtime, Server server, GameContext.CharacterRef character) { }
    // Each admitted driver/motion work owns at most one resource, including bindings still draining.
    public static final ModuleDefinition<Config, SceneMembershipModule> DEFINITION = new ModuleDefinition<>(
            "scene-client", Set.of(CONTROL), new ExecutionScope.Limits(16, 16, 8, 2),
            SceneMembershipModule::new, List.of());

    private final CharacterRuntime runtime;
    private final Server server;
    private final SceneCharacterState characterState = new SceneCharacterState();
    private CharacterRuntime.ModuleContext context;
    private final GameContext.CharacterRef character;

    private SceneMembershipModule(Config config) {
        runtime = config.runtime();
        server = config.server();
        character = config.character();
    }

    @Override public synchronized void onActivated(CharacterRuntime.ModuleContext context) {
        this.context = context;
        SceneRuntimeManager.enter(server, runtime, context, characterState);
    }

    public synchronized void refresh() {
        if (context != null && context.scope().isActive()) {
            SceneRuntimeManager.enter(server, runtime, context, characterState);
        }
    }

    @Override public synchronized void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
        this.context = null;
        SceneRuntimeManager.leave(character.character());
    }
}
