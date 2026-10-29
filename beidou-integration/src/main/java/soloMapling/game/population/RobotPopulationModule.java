package soloMapling.game.population;

import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.RuntimeModule;

import java.util.List;
import java.util.Set;

/** A selected role can use the world's actual population service through the common function directory. */
public final class RobotPopulationModule implements RuntimeModule {
    private final RobotPopulationService population;

    private RobotPopulationModule(RobotPopulationService population) { this.population = population; }

    public static ModuleDefinition<Void, RobotPopulationModule> definition(RobotPopulationService population) {
        return new ModuleDefinition<>("robot-population", Set.of(), new ExecutionScope.Limits(8, 1, 4, 1),
                ignored -> new RobotPopulationModule(population), List.of(
                new ModuleFunction<>("robots.create", "Create one complete robot from a configured type",
                        List.of(new ModuleFunction.Parameter("type_id", ModuleFunction.Type.STRING, true,
                                "Configured robot type identifier")),
                        "Returns the initialized instance_id, type_id, runtime_id and character_id",
                        (instance, context, work, arguments) -> instance.population.create((String) arguments.get("type_id"))),
                new ModuleFunction<>("robots.remove", "Close one robot owned by the population service",
                        List.of(new ModuleFunction.Parameter("instance_id", ModuleFunction.Type.STRING, true,
                                "Actual robot instance identifier returned by create")),
                        "Returns removed=true only after real root and module cleanup",
                        (instance, context, work, arguments) -> instance.population.remove((String) arguments.get("instance_id")))));
    }
}
