package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One export declaration supplies both its help and argument checks, then binds an exact instance. */
public record ModuleFunction<M extends RuntimeModule>(String name, String description, List<Parameter> parameters,
                                                      String resultDescription, Handler<M> handler) {
    public ModuleFunction {
        requireName(name);
        requireDescription(description);
        requireDescription(resultDescription);
        parameters = List.copyOf(parameters);
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!names.add(parameter.name())) throw new IllegalArgumentException("duplicate_module_function_parameter");
        }
        Objects.requireNonNull(handler);
    }

    public enum Type { STRING, NUMBER, INTEGER, BOOLEAN, OBJECT, ARRAY }

    public record Parameter(String name, Type type, boolean required, String description) {
        public Parameter {
            requireName(name);
            Objects.requireNonNull(type);
            requireDescription(description);
        }
    }

    @FunctionalInterface
    public interface Handler<M extends RuntimeModule> {
        Object invoke(M instance, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work,
                      Map<String, Object> arguments) throws Exception;
    }

    public interface Bound {
        String name();
        Map<String, Object> describe();
        void validate(Map<String, Object> arguments);
        /** The runtime has checked availability and validated the arguments before accepting this work. */
        Object invoke(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work,
                      Map<String, Object> arguments) throws Exception;
    }

    public Bound bind(M instance) {
        Objects.requireNonNull(instance);
        return new Bound() {
            @Override public String name() { return name; }
            @Override public Map<String, Object> describe() { return ModuleFunction.this.describe(); }
            @Override public void validate(Map<String, Object> arguments) { validateArguments(arguments); }
            @Override public Object invoke(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work,
                                           Map<String, Object> arguments) throws Exception {
                return handler.invoke(instance, context, work, arguments);
            }
        };
    }

    public Map<String, Object> describe() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("name", name);
        description.put("description", this.description);
        description.put("parameters", parameters.stream().map(parameter -> Map.<String, Object>of(
                "name", parameter.name(), "type", parameter.type().name().toLowerCase(Locale.ROOT),
                "required", parameter.required(), "description", parameter.description())).toList());
        description.put("result_description", resultDescription);
        return Map.copyOf(description);
    }

    private void validateArguments(Map<String, Object> arguments) {
        for (String argument : arguments.keySet()) {
            if (parameters.stream().noneMatch(parameter -> parameter.name().equals(argument))) {
                throw new IllegalArgumentException("unknown_module_function_argument:" + argument);
            }
        }
        for (Parameter parameter : parameters) {
            if (!arguments.containsKey(parameter.name())) {
                if (parameter.required()) throw new IllegalArgumentException("missing_module_function_argument:" + parameter.name());
                continue;
            }
            Object value = arguments.get(parameter.name());
            boolean matches = switch (parameter.type()) {
                case STRING -> value instanceof String;
                case NUMBER -> value instanceof Number number && Double.isFinite(number.doubleValue());
                case INTEGER -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() == Math.rint(number.doubleValue());
                case BOOLEAN -> value instanceof Boolean;
                case OBJECT -> value instanceof Map<?, ?>;
                case ARRAY -> value instanceof List<?>;
            };
            if (!matches) throw new IllegalArgumentException("invalid_module_function_argument:" + parameter.name());
        }
    }

    private static void requireName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("invalid_module_function_identifier");
        }
    }

    private static void requireDescription(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing_module_function_description");
    }
}
