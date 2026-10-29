package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

/** Selects task goals and constraints, then decides when to start, continue, switch, or interrupt them. */
public interface StrategyModule<I, R> extends RuntimeModule {
    /** Uses the provider's managed invocation work; action calls retain their own provider scopes. */
    R run(I input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) throws Exception;
}
