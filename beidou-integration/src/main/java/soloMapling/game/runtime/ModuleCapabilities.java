package soloMapling.game.runtime;

import java.util.List;
import java.util.Map;

/** The current runtime's module help and managed calls, without exposing module objects to scripts. */
public interface ModuleCapabilities {
    List<Map<String, Object>> help();
    Map<String, Object> call(String name, Map<String, Object> arguments);
}
