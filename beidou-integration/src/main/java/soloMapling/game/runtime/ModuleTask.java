package soloMapling.game.runtime;

import soloMapling.game.execution.ExecutionScope;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** A module-owned carrier for persistent control work; native commands still use the role's lane. */
public final class ModuleTask {
    private final ExecutionScope.WorkHandle work;
    private final Thread thread;

    public ModuleTask(CharacterRuntime.ModuleContext context, String name,
                      Consumer<ExecutionScope.WorkHandle> body) {
        work = context.acceptWork();
        work.claimCarrier();
        thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try { context.scope().runTracked(work, () -> body.accept(work)); }
            finally { work.carrierSettled(); }
        });
    }

    public ExecutionScope.WorkHandle work() { return work; }
    public CompletionStage<ExecutionScope.WorkSnapshot> completion() { return work.completion(); }
    public void start() { thread.start(); }

    public void cancel() {
        work.scope().requestCancel(work);
        thread.interrupt();
    }

    public void await() { work.completion().toCompletableFuture().join(); }
}
