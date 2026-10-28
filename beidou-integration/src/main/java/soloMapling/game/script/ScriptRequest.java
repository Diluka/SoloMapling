package soloMapling.game.script;

import java.util.Map;

/** One bounded JavaScript evaluation. Parameters are copied as JSON before execution. */
public record ScriptRequest(String source, Map<String, Object> parameters, long timeoutMs) {
    public ScriptRequest {
        if (source == null || source.isBlank() || source.length() > 65_536) {
            throw new IllegalArgumentException("invalid_script_source");
        }
        if (parameters == null) parameters = Map.of();
        if (timeoutMs < 1 || timeoutMs > 5_000) throw new IllegalArgumentException("invalid_script_timeout");
    }
}
