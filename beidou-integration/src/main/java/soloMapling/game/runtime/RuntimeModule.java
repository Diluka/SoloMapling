package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

import java.util.Objects;
import java.util.Set;

/** A compiled control/behavior component. No autonomous policy is supplied by the host. */
public interface RuntimeModule {
    record Spec(String typeId, String instanceId, Set<String> controls, ExecutionScope.Limits limits) {
        public Spec {
            requireId(typeId); requireId(instanceId);
            controls = Set.copyOf(controls);
            controls.forEach(Spec::requireId);
            Objects.requireNonNull(limits);
        }

        private static void requireId(String value) {
            if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
                throw new IllegalArgumentException("invalid_module_identifier");
            }
        }
    }

    /** Keep this hook short. All native work and owned resources must use the supplied context. */
    default void onActivated(CharacterRuntime.ModuleContext context) { }

    /** Admission has already stopped. Cleanup may use only this context's registered resources. */
    default void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) { }

    /** Called only after owned work and resources really drained; it cannot start native work. */
    default void onUnloaded() { }
}
