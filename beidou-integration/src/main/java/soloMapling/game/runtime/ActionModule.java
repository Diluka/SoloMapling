package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

/** Executes a caller-selected task within its supplied goal and constraints. */
public interface ActionModule<I, R> extends RuntimeModule {
    /**
     * Uses the provider's already-running invocation work and finishes this task's child work and resources.
     * The managed carrier settles the supplied invocation work after this method returns.
     * Native short commands use new work on the same provider scope and the role's original lane.
     * A task may plan multiple steps and call other actions through the managed module directory.
     */
    R execute(I input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) throws Exception;
}
