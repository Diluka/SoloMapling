package soloMapling.game.script.nativeenv;

import java.util.List;
import java.util.Map;

/** One admitted invocation; the caller owns validation and its single input snapshot. */
public record NativeScriptRequest(String kind, String operation, String contextId, Map<String, Object> options,
                                  String entry, List<Object> args, String source, Map<String, Object> parameters,
                                  long timeoutMs) {
    public static final long DEFAULT_TIMEOUT_MS = 30_000;
    public static final long MAX_TIMEOUT_MS = 120_000;
}
