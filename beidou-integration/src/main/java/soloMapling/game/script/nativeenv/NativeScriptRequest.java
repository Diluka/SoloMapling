package soloMapling.game.script.nativeenv;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import soloMapling.game.script.ScriptValues;

/** One invocation of a role-owned native script environment. */
public record NativeScriptRequest(String kind, String operation, String contextId, Map<String, Object> options,
                                  String entry, List<Object> args, String source, Map<String, Object> parameters,
                                  long timeoutMs) {
    public NativeScriptRequest {
        if (kind == null || operation == null || !List.of("npc", "quest", "portal", "reactor", "event").contains(kind)
                || !List.of("start", "invoke", "close").contains(operation)
                || timeoutMs < 1 || timeoutMs > 5_000) throw new IllegalArgumentException("invalid_native_script_request");
        options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        args = args == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(args));
        parameters = parameters == null ? Map.of() : parameters;
        try {
            ObjectMapper json = new ObjectMapper();
            String optionsJson = ScriptValues.json(options);
            String argsJson = ScriptValues.json(args);
            String parametersJson = ScriptValues.json(parameters);
            int bytes = optionsJson.getBytes(StandardCharsets.UTF_8).length
                    + argsJson.getBytes(StandardCharsets.UTF_8).length
                    + parametersJson.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 16_384) throw new IllegalArgumentException("native_script_json_limit");
            options = Collections.unmodifiableMap(json.readValue(optionsJson, Map.class));
            args = Collections.unmodifiableList(json.readValue(argsJson, List.class));
            parameters = Collections.unmodifiableMap(json.readValue(parametersJson, Map.class));
        } catch (Exception invalid) { throw new IllegalArgumentException("invalid_native_script_json"); }
        source = source == null ? "" : source;
        if (source.length() > 65_536 || ("start".equals(operation) ? contextId != null : contextId == null)) {
            throw new IllegalArgumentException("invalid_native_script_context");
        }
        if (entry != null && !entry.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}")) {
            throw new IllegalArgumentException("invalid_native_script_entry");
        }
        if ("invoke".equals(operation) && !source.isBlank() && entry != null
                || "close".equals(operation) && (!source.isBlank() || entry != null || !args.isEmpty() || !options.isEmpty())) {
            throw new IllegalArgumentException("invalid_native_script_invocation");
        }
    }
}
