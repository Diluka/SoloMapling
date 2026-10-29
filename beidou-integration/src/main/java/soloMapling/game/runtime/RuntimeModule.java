package soloMapling.game.runtime;

/** Common loadable-module lifecycle; task execution and strategy contracts extend this protocol. */
public interface RuntimeModule {
    /** Keep this hook short. All native work and owned resources must use the supplied context. */
    default void onActivated(CharacterRuntime.ModuleContext context) { }

    /** Admission has already stopped. Cleanup may use only this context's registered resources. */
    default void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) { }

    /** Called only after owned work and resources really drained; it cannot start native work. */
    default void onUnloaded() { }
}
