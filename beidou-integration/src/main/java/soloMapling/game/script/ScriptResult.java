package soloMapling.game.script;

/** Common outcome vocabulary; invocation data is projected by the native script result. */
public final class ScriptResult {
    private ScriptResult() { }

    public enum Status { SUCCEEDED, PARSE_ERROR, RUNTIME_ERROR, RESULT_ERROR, TIMED_OUT, CANCELLED, CLEANUP_ERROR }
    public record ScriptError(String message, Integer line, Integer column) { }
}
