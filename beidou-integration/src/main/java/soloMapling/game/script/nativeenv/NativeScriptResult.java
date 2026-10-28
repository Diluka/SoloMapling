package soloMapling.game.script.nativeenv;

import soloMapling.game.script.ScriptResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Detached result of an invocation. An OPEN native context intentionally outlives a successful invocation. */
public record NativeScriptResult(ScriptResult.Status status, Object result, String stdout, String stderr,
                                 boolean outputTruncated, ScriptResult.ScriptError error, String contextId,
                                 String contextState, boolean engineExited, boolean contextClosed,
                                 boolean invocationSettled, Map<String, Object> environment) {
    public Map<String, Object> toMap() {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("status", status.name()); resultMap.put("result", result);
        resultMap.put("stdout", stdout); resultMap.put("stderr", stderr); resultMap.put("output_truncated", outputTruncated);
        resultMap.put("context_id", contextId); resultMap.put("context_state", contextState);
        resultMap.put("engine_exited", engineExited); resultMap.put("context_closed", contextClosed);
        resultMap.put("invocation_settled", invocationSettled); resultMap.put("work_settled", invocationSettled);
        resultMap.put("environment", environment);
        if (error != null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("message", error.message()); detail.put("line", error.line()); detail.put("column", error.column());
            resultMap.put("error", Collections.unmodifiableMap(detail));
        }
        return Collections.unmodifiableMap(resultMap);
    }
}
