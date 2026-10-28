package soloMapling.game.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runtime-owned descriptors are the sole source for both help and executable bindings. */
public final class ScriptRegistry {
    private final Map<Object, Map<String, Entry>> owners = new java.util.IdentityHashMap<>();

    public synchronized void register(Object owner, Runnable requireCurrent, ScriptFunction definition, ScriptFunction.Body body) {
        Objects.requireNonNull(owner); Objects.requireNonNull(requireCurrent);
        Objects.requireNonNull(definition); Objects.requireNonNull(body);
        requireCurrent.run();
        Map<String, Entry> functions = owners.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
        if (functions.containsKey(definition.name())) throw new IllegalArgumentException("script_function_exists");
        if (functions.size() >= 64) throw new IllegalArgumentException("script_function_capacity");
        functions.put(definition.name(), new Entry(requireCurrent, definition, body));
    }

    public synchronized void remove(Object owner) { owners.remove(owner); }

    public synchronized List<Entry> bindings(Object owner) { return List.copyOf(owners.getOrDefault(owner, Map.of()).values()); }

    public synchronized Map<String, Object> help(Object owner, List<String> names) {
        Map<String, Entry> functions = owners.getOrDefault(owner, Map.of());
        if (names == null) names = List.of();
        if (names.size() > 64 || names.stream().anyMatch(name -> name == null || name.length() > 64)) {
            throw new IllegalArgumentException("invalid_script_help_names");
        }
        List<Map<String, Object>> found = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (names.isEmpty()) functions.values().forEach(entry -> found.add(entry.definition.describe()));
        else for (String name : names) {
            Entry entry = functions.get(name);
            if (entry == null) unavailable.add(name);
            else found.add(entry.definition.describe());
        }
        return Map.of("language", "javascript", "functions", List.copyOf(found), "unavailable", List.copyOf(unavailable));
    }

    public static final class Entry {
        private final Runnable requireCurrent;
        private final ScriptFunction definition;
        private final ScriptFunction.Body body;
        private Entry(Runnable requireCurrent, ScriptFunction definition, ScriptFunction.Body body) {
            this.requireCurrent = requireCurrent; this.definition = definition; this.body = body;
        }
        public ScriptFunction definition() { return definition; }
        public Object invoke(List<Object> arguments) {
            requireCurrent.run();
            if (arguments.size() != definition.parameters().size()) throw new IllegalArgumentException("script_function_arity");
            Object result = body.invoke(arguments);
            requireCurrent.run();
            return result;
        }
    }
}
