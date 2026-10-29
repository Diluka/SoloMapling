package soloMapling.game.population;

import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.RuntimeModule;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** A selected role uses two independently typed operations from the world's population service. */
public final class RobotPopulationModule implements RuntimeModule {
    public record CreateRequest(String typeId) { }
    public record RemoveRequest(String instanceId) { }

    public static final ModuleFunction<RobotPopulationModule, CreateRequest, RobotPopulationService.CreatedRobot> CREATE =
            new ModuleFunction<>("robots.create", "Create one complete robot from a configured type",
                    List.of(new ModuleFunction.Parameter("type_id", ModuleFunction.Type.STRING, true,
                            "Configured robot type identifier")),
                    "Returns the initialized instance_id, type_id, runtime_id and character_id",
                    "Returns after native role publication and all selected modules have initialized",
                    (module, arguments) -> new CreateRequest((String) arguments.get("type_id")),
                    (module, context, work, input) -> module.population.create(input.typeId()),
                    result -> Map.of("instance_id", result.instanceId(), "type_id", result.typeId(),
                            "runtime_id", result.runtimeId(), "character_id", result.characterId()));
    public static final ModuleFunction<RobotPopulationModule, RemoveRequest, RobotPopulationService.RemovedRobot> REMOVE =
            new ModuleFunction<>("robots.remove", "Close one robot owned by the population service",
                    List.of(new ModuleFunction.Parameter("instance_id", ModuleFunction.Type.STRING, true,
                            "Actual robot instance identifier returned by create")),
                    "Returns removed=true only after real root and module cleanup",
                    "Returns after owned modules, native root and role publication have actually closed",
                    (module, arguments) -> new RemoveRequest((String) arguments.get("instance_id")),
                    (module, context, work, input) -> module.population.remove(input.instanceId()),
                    result -> Map.of("instance_id", result.instanceId(), "type_id", result.typeId(), "removed", true));

    private final RobotPopulationService population;

    private RobotPopulationModule(RobotPopulationService population) { this.population = population; }

    public static ModuleDefinition<Void, RobotPopulationModule> definition(RobotPopulationService population) {
        return new ModuleDefinition<>("robot-population", Set.of(), new ExecutionScope.Limits(8, 1, 4, 1),
                ignored -> new RobotPopulationModule(population), List.of(CREATE, REMOVE));
    }
}
