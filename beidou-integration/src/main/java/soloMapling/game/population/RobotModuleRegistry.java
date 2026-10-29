package soloMapling.game.population;

import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RuntimeModule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Compiles each YAML module selection once into its typed, reusable assembly plan. */
public final class RobotModuleRegistry {
    private final Map<String, Compiler> definitions = new LinkedHashMap<>();

    public <C, M extends RuntimeModule> void register(ModuleDefinition<C, M> definition,
                                                     Function<Map<String, Object>, C> decode) {
        definitions.put(definition.typeId(), (id, config, enabled) -> {
            var selection = new ModuleSelection<>(id, decode.apply(config), enabled);
            return new ModulePlan(id, runtime -> runtime.mount(definition, selection));
        });
    }

    ModulePlan compile(String id, String type, Map<String, Object> configuration, boolean enabled) {
        var compiler = definitions.get(type);
        if (compiler == null) throw new IllegalArgumentException("unknown_robot_module_type:" + type);
        return compiler.compile(id, configuration, enabled);
    }

    public record ModulePlan(String instanceId,
                             Function<CharacterRuntime, CharacterRuntime.MountedModule<?>> mount) { }

    @FunctionalInterface
    private interface Compiler {
        ModulePlan compile(String id, Map<String, Object> configuration, boolean enabled);
    }
}
