package soloMapling.game.runtime;

/** Instance identity and module-owned configuration selected for one runtime. */
public record ModuleSelection<C>(String instanceId, C config, boolean enabled) {
    public ModuleSelection {
        ModuleDefinition.requireIdentifier(instanceId);
    }
}
