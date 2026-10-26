package soloMapling.beidou;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.Environment.EnvironmentPopulationConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PopulationConfigurationTest {
    @TempDir Path directory;

    @AfterEach
    void resetConfiguredPath() {
        EnvironmentPopulationConfig.setConfigPath(null);
    }

    @Test
    void explicitPopulationFileControlsCountsWithoutBeingRewritten() throws Exception {
        Path file = directory.resolve("population.yaml");
        String contents = """
                version: 1
                scale: 0.5
                waves:
                  essentials:
                    enabled: false
                  training:
                    enabled: true
                    cohorts:
                      - map: 100000000
                        count: 8
                        level_lo: 10
                        level_hi: 20
                """;
        Files.writeString(file, contents);
        EnvironmentPopulationConfig.setConfigPath(file.toString());

        var plan = EnvironmentPopulationConfig.plan();

        assertFalse(plan.essentials().enabled());
        assertEquals(4, plan.trainingCohortTotal());
        assertEquals(file.toAbsolutePath().toString(), plan.loadedFrom());
        assertEquals(contents, Files.readString(file));
    }

    @Test
    void missingExplicitFileCannotSilentlyChangeThePopulation() {
        EnvironmentPopulationConfig.setConfigPath(directory.resolve("missing.yaml").toString());
        assertThrows(IllegalStateException.class, EnvironmentPopulationConfig::plan);
    }

    @Test
    void emptyExplicitFileCannotSilentlyChangeThePopulation() throws Exception {
        Path file = Files.createFile(directory.resolve("empty.yaml"));
        EnvironmentPopulationConfig.setConfigPath(file.toString());
        assertThrows(IllegalStateException.class, EnvironmentPopulationConfig::plan);
    }

    @Test
    void malformedExplicitFileCannotSilentlyChangeThePopulation() throws Exception {
        Path file = directory.resolve("malformed.yaml");
        Files.writeString(file, "waves: [unterminated");
        EnvironmentPopulationConfig.setConfigPath(file.toString());
        assertThrows(IllegalStateException.class, EnvironmentPopulationConfig::plan);
    }
}
