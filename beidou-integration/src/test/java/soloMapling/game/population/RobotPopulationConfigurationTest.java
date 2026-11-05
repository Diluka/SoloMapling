package soloMapling.game.population;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import soloMapling.beidou.CharacterTestContext;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RobotPopulationConfigurationTest {
    @Test void defaultTypesCompileTheSharedControlsWithoutStartingMcpTrainingOrInitialRobots() {
        CharacterTestContext.install();
        var population = new RobotPopulationService(new StandardEnvironment());
        population.onWorldStarted();
        var configuration = population.configuration();
        var mcp = configuration.types().get("mcp_test");
        var training = configuration.types().get("training_basic");
        var mcpModules = mcp.modules().stream().map(RobotModuleRegistry.ModulePlan::instanceId).collect(Collectors.toSet());
        var trainingModules = training.modules().stream().map(RobotModuleRegistry.ModulePlan::instanceId).collect(Collectors.toSet());

        assertEquals(Map.of("mcp_test", 0, "training_basic", 0), configuration.initialPopulation());
        assertNull(mcp.character(), "the ordinary MCP role keeps its existing native source");
        assertTrue(mcpModules.containsAll(Set.of("tactics", "loot", "native")));
        assertFalse(mcpModules.contains("schedule"), "MCP assembly does not activate the training schedule");
        assertTrue(trainingModules.containsAll(Set.of("tactics", "loot", "native")));
        assertEquals("schedule", training.modules().getLast().instanceId(), "automatic training starts after its providers mount");
        assertEquals(new RobotPopulationConfig.CharacterSpec("fmbot", new RobotPopulationConfig.Spawn(0, 1, 100000000)),
                training.character());
    }
}
