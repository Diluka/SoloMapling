package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Reusable module implementation, assembly declarations and configuration contract.
 * The factory constructs private state; runtime work and resources belong to lifecycle hooks.
 */
public record ModuleDefinition<C, M extends RuntimeModule>(
        String typeId,
        Set<String> controls,
        ExecutionScope.Limits limits,
        Function<C, M> factory,
        List<ModuleFunction<M>> exports) {

    public ModuleDefinition {
        requireIdentifier(typeId);
        controls = Set.copyOf(controls);
        controls.forEach(ModuleDefinition::requireIdentifier);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(factory);
        exports = List.copyOf(exports);
        Set<String> names = new HashSet<>();
        for (ModuleFunction<M> function : exports) {
            if (!names.add(function.name())) {
                throw new IllegalArgumentException("duplicate_module_function");
            }
        }
    }

    /** Bind validated declarations to one actual instance while retaining its concrete type. */
    public Map<String, ModuleFunction.Bound> bind(M instance) {
        Map<String, ModuleFunction.Bound> bound = new LinkedHashMap<>();
        for (ModuleFunction<M> function : exports) {
            bound.put(function.name(), function.bind(instance));
        }
        return Collections.unmodifiableMap(bound);
    }

    static void requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("invalid_module_identifier");
        }
    }
}
