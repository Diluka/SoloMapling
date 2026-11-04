package soloMapling.game.scene;

import soloMapling.game.action.NativeContactDamageActions;
import soloMapling.game.combat.CombatJobTree;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleTask;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** One scene membership's passive client input; independent of hunting and engagement lifetime. */
public final class CharacterDamage {
    private final SceneActorBinding binding;
    private ModuleTask task;

    public CharacterDamage(SceneActorBinding binding) { this.binding = binding; }

    public void start() {
        task = binding.startTask("scene-contact-" + binding.characterRef().character().getId(), this::run);
    }

    public void stop() { task.cancel(); }

    private void run(ExecutionScope.WorkHandle work) {
        var context = binding.context();
        try {
            while (!work.cancelRequested()) {
                var failure = new AtomicReference<Throwable>();
                var step = context.acceptWork(work);
                try {
                    context.submit("scene-client", step, () -> {}, () -> {
                        try {
                            if (work.cancelRequested()) return;
                            binding.requireCurrent();
                            var server = binding.scene().server();
                            var scene = NativeContextChecks.captureScene(server, binding.characterRef());
                            var actor = scene.actor().character();
                            var state = binding.characterState();
                            if (state.observeLife(actor.isAlive())) {
                                context.recordActivity(work, actor.isAlive() ? "alive" : "dead", "SCENE_LIFE");
                            }
                            binding.scene().presentation().syncLifeState(server, scene, work);
                            if (CombatJobTree.nativeMode(actor) && System.nanoTime() >= state.nextContactNanos()) {
                                var hit = NativeContactDamageActions.contact(server, scene, work);
                                if (hit != null) {
                                    state.recordContact(hit);
                                    if (state.observeLife(actor.isAlive())) {
                                        context.recordActivity(work, actor.isAlive() ? "alive" : "dead", "SCENE_LIFE");
                                    }
                                    binding.scene().presentation().syncLifeState(server, scene, work);
                                    if (hit.handlerError()) throw new IllegalStateException("native_contact_handler_failed");
                                }
                            }
                        } catch (RuntimeException | Error problem) { failure.set(problem); }
                    }, () -> {});
                } catch (RuntimeException rejected) { step.reject(); throw rejected; }
                step.completion().toCompletableFuture().join();
                if (failure.get() instanceof Error fatal) throw fatal;
                if (failure.get() instanceof RuntimeException problem) throw problem;
                try { Thread.sleep(50); }
                catch (InterruptedException cancelled) { return; }
            }
        } catch (CancellationException | GameContext.Invalid | CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (binding.active() && !work.cancelRequested() && context.scope().isActive()) throw stopped;
        }
    }
}
