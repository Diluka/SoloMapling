package soloMapling.game.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Detached JSON values only: never retains a guest Value, Context, role or Throwable. */
public record ScriptResult(Status status, Object result, String stdout, String stderr, boolean outputTruncated,
                           ScriptError error, boolean engineExited, boolean contextClosed, boolean workSettled) {
    public enum Status { SUCCEEDED, PARSE_ERROR, RUNTIME_ERROR, RESULT_ERROR, TIMED_OUT, CANCELLED, CLEANUP_ERROR }
    public record ScriptError(String message, Integer line, Integer column) { }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status.name());
        value.put("result", result);
        value.put("stdout", stdout);
        value.put("stderr", stderr);
        value.put("output_truncated", outputTruncated);
        value.put("engine_exited", engineExited);
        value.put("context_closed", contextClosed);
        value.put("work_settled", workSettled);
        if (error != null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("message", error.message());
            detail.put("line", error.line());
            detail.put("column", error.column());
            value.put("error", detail);
        }
        return Collections.unmodifiableMap(value);
    }
}
