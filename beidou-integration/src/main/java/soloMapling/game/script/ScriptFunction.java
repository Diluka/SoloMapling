package soloMapling.game.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trusted, synchronous pure-function seam. Game capabilities are deliberately not registered yet. */
public record ScriptFunction(String name, String description, List<String> parameters, String returns) {
    public ScriptFunction {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                || description == null || description.length() > 1_024
                || returns == null || returns.length() > 1_024 || parameters == null || parameters.size() > 32
                || parameters.stream().anyMatch(p -> p == null || p.length() > 128)) {
            throw new IllegalArgumentException("invalid_script_function");
        }
        parameters = List.copyOf(parameters);
    }

    @FunctionalInterface public interface Body { Object invoke(List<Object> arguments); }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("binding", "functions." + name);
        result.put("description", description);
        result.put("parameters", parameters);
        result.put("returns", returns);
        return Map.copyOf(result);
    }
}
