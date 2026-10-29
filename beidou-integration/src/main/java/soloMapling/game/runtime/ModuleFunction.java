package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** One typed operation supplies its JSON boundary, business invocation, help and completion contract. */
public record ModuleFunction<M extends RuntimeModule, I, R>(String name, String description, List<Parameter> parameters,
        String resultDescription, String completionCondition, Input<M, I> input,
        Handler<M, I, R> handler, Function<R, ?> output) {
    public ModuleFunction {
        requireName(name);
        requireDescription(description);
        requireDescription(resultDescription);
        requireDescription(completionCondition);
        parameters = List.copyOf(parameters);
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!names.add(parameter.name())) throw new IllegalArgumentException("duplicate_module_function_parameter");
        }
        Objects.requireNonNull(input);
        Objects.requireNonNull(handler);
        Objects.requireNonNull(output);
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
    public interface Input<M extends RuntimeModule, I> {
        I decode(M instance, Map<String, Object> arguments);
    }

    @FunctionalInterface
    public interface Handler<M extends RuntimeModule, I, R> {
        R invoke(M instance, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work, I input) throws Exception;
    }

    @FunctionalInterface
    public interface Invocation<R> {
        R invoke(CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) throws Exception;
    }

    public interface Bound<I, R> {
        ModuleFunction<?, I, R> declaration();
        String name();
        Map<String, Object> describe();
        /** Schema validation and JSON decoding happen once, before the runtime accepts invocation work. */
        Invocation<Object> prepare(Map<String, Object> arguments);
        /** Uses an already-typed input and the provider's already-running invocation work. */
        R invoke(I input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) throws Exception;
    }

    public Bound<I, R> bind(M instance) {
        Objects.requireNonNull(instance);
        return new Bound<>() {
            @Override public ModuleFunction<M, I, R> declaration() { return ModuleFunction.this; }
            @Override public String name() { return name; }
            @Override public Map<String, Object> describe() { return ModuleFunction.this.describe(); }
            @Override public Invocation<Object> prepare(Map<String, Object> arguments) {
                validateArguments(arguments);
                I decoded = input.decode(instance, arguments);
                return (context, work) -> output.apply(invoke(decoded, context, work));
            }
            @Override public R invoke(I value, CharacterRuntime.ModuleContext context,
                                       ExecutionScope.WorkHandle work) throws Exception {
                return handler.invoke(instance, context, work, value);
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
        description.put("completion_condition", completionCondition);
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
