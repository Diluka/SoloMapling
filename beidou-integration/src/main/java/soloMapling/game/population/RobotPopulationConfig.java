package soloMapling.game.population;

import com.esotericsoftware.yamlbeans.YamlReader;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The world-start configuration boundary; constructed plans are trusted during instance creation. */
public record RobotPopulationConfig(Map<String, RobotType> types, Map<String, Integer> initialPopulation,
                                    String loadedFrom) {
    public static final String RESOURCE = "soloMapling/game/population/RobotPopulation.yaml";

    public record Spawn(int world, int channel, int map) { }
    public record CharacterSpec(String template, Spawn spawn) { }
    public record RobotType(CharacterSpec character, List<RobotModuleRegistry.ModulePlan> modules) { }

    public static RobotPopulationConfig load(List<String> paths, RobotModuleRegistry registry) {
        Map<String, TypeDocument> definitions = new LinkedHashMap<>();
        Map<String, Integer> initial = new LinkedHashMap<>();
        var sources = new ArrayList<String>();
        var mapper = new ObjectMapper();
        for (var path : paths.isEmpty() ? List.of("") : paths) {
            try (Reader reader = path.isBlank()
                    ? new InputStreamReader(RobotPopulationConfig.class.getClassLoader().getResourceAsStream(RESOURCE),
                            StandardCharsets.UTF_8)
                    : Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
                var document = mapper.convertValue(new YamlReader(reader).read(), Document.class);
                if (document.types() != null) definitions.putAll(document.types());
                if (document.initialPopulation() != null) initial.putAll(document.initialPopulation());
                sources.add(path.isBlank() ? "classpath:" + RESOURCE : Path.of(path).toAbsolutePath().toString());
            } catch (IOException failure) {
                throw new IllegalArgumentException("robot_population_configuration_unreadable:" + path, failure);
            }
        }
        Map<String, RobotType> types = new LinkedHashMap<>();
        definitions.forEach((name, type) -> {
            if (type.character() != null && !"fmbot".equals(type.character().template())) {
                throw new IllegalArgumentException("unsupported_robot_template:" + type.character().template());
            }
            var modules = type.modules().entrySet().stream().map(entry -> {
                var module = entry.getValue();
                return registry.compile(entry.getKey(), module.type(),
                        module.config() == null ? Map.of() : module.config(),
                        module.enabled() == null || module.enabled());
            }).toList();
            types.put(name, new RobotType(type.character(), modules));
        });
        initial.forEach((type, count) -> {
            if (!types.containsKey(type)) throw new IllegalArgumentException("unknown_robot_type:" + type);
            if (count < 0) throw new IllegalArgumentException("negative_robot_initial_population:" + type);
        });
        return new RobotPopulationConfig(Collections.unmodifiableMap(types), Map.copyOf(initial),
                String.join(", ", sources));
    }

    private record Document(Map<String, TypeDocument> types,
                            @JsonProperty("initial_population") Map<String, Integer> initialPopulation) { }
    private record TypeDocument(CharacterSpec character, Map<String, ModuleDocument> modules) { }
    private record ModuleDocument(String type, Map<String, Object> config, Boolean enabled) { }
}
