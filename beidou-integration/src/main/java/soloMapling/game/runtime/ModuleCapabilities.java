package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

import java.util.List;
import java.util.Map;

/** The current runtime's module help and managed calls, without exposing module objects to scripts. */
public interface ModuleCapabilities {
    List<Map<String, Object>> help();
    default Map<String, Object> call(String name, Map<String, Object> arguments) {
        return call(name, arguments, null);
    }
    Map<String, Object> call(String name, Map<String, Object> arguments, ExecutionScope.WorkHandle callerWork);
}
