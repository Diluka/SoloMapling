package soloMapling.game.script.nativeenv;

import java.util.Map;

/** Native source-specific binding. Native business rules remain in the original managers and objects. */
public interface NativeScriptAdapter {
    default AutoCloseable enter(NativeScriptSession session) { return () -> { }; }
    default void validate(NativeScriptSession session) { }
    Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception;
    Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception;
    void close(NativeScriptSession session) throws Exception;
    Map<String, Object> describe(NativeScriptSession session);
}
