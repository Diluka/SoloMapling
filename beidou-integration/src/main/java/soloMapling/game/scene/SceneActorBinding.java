package soloMapling.game.scene;

import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleTask;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** One exact entry into an actual native map. Holding this reference does not grant native control. */
public final class SceneActorBinding {
    private final SceneRuntime scene;
    private final CharacterRuntime runtime;
    private final CharacterRuntime.ModuleContext context;
    private final GameContext.SceneRef nativeScene;
    private final SceneCharacterState characterState;
    private record BackgroundTask(String name, ModuleTask task) { }
    private final List<BackgroundTask> tasks = new ArrayList<>();
    private final Set<ExecutionScope.WorkHandle> entryWork = new LinkedHashSet<>();
    private final List<CompletionStage<Void>> cleanupProofs = new ArrayList<>();
    private volatile boolean active = true;

    SceneActorBinding(SceneRuntime scene, CharacterRuntime runtime, CharacterRuntime.ModuleContext context,
                      GameContext.SceneRef nativeScene, SceneCharacterState characterState) {
        this.scene = scene;
        this.runtime = runtime;
        this.context = context;
        this.nativeScene = nativeScene;
        this.characterState = characterState;
    }

    public SceneRuntime scene() { return scene; }
    public CharacterRuntime runtime() { return runtime; }
    public CharacterRuntime.ModuleContext context() { return context; }
    public GameContext.CharacterRef characterRef() { return nativeScene.actor(); }
    public GameContext.SceneRef nativeScene() { return nativeScene; }
    public SceneCharacterState characterState() { return characterState; }
    public boolean active() { return active; }

    public void requireCurrent() {
        if (!active) throw new CharacterRuntime.Invalid("scene_membership_ended");
        NativeContextChecks.requireSceneCurrent(scene.server(), nativeScene);
        if (!active) throw new CharacterRuntime.Invalid("scene_membership_ended");
    }

    /** The existing membership module owns the carrier and work; there is no scene action queue. */
    public ModuleTask startTask(String name, Consumer<ExecutionScope.WorkHandle> body) {
        synchronized (tasks) {
            requireCurrent();
            var task = new ModuleTask(context, name, body);
            tasks.add(new BackgroundTask(name, task));
            task.start();
            return task;
        }
    }

    public List<Map<String, Object>> backgroundSnapshot() {
        synchronized (tasks) {
            return tasks.stream().map(background -> {
                var work = background.task().work().snapshot();
                return Map.<String, Object>of("name", background.name(), "work_id", work.id(),
                        "body_state", work.bodyState().name(), "settled", work.settled(),
                        "cancel_requested", work.cancelRequested());
            }).toList();
        }
    }

    /** Observe the existing provider's work as an exit dependency; never register or claim it again. */
    public void awaitWork(ExecutionScope.WorkHandle work) {
        synchronized (tasks) {
            requireCurrent();
            entryWork.removeIf(ExecutionScope.WorkHandle::isSettled);
            entryWork.add(work);
        }
    }

    /** A scene resource's original owner supplies its exact release proof, separately from carrier settlement. */
    void trackCleanup(CompletionStage<Void> proof) {
        synchronized (tasks) { cleanupProofs.add(proof); }
    }

    /** Native controller cleanup follows this entry's already registered action carriers. */
    CompletionStage<Void> whenActionsDrained() {
        synchronized (tasks) {
            return CompletableFuture.allOf(entryWork.stream().map(work -> work.completion().toCompletableFuture())
                    .toArray(CompletableFuture[]::new));
        }
    }

    CompletionStage<Void> leave() {
        synchronized (tasks) {
            active = false;
            tasks.forEach(background -> background.task().cancel());
            var waiting = new ArrayList<CompletableFuture<?>>();
            tasks.forEach(background -> waiting.add(background.task().completion().toCompletableFuture()));
            waiting.add(whenActionsDrained().toCompletableFuture());
            return CompletableFuture.allOf(waiting.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
                synchronized (tasks) {
                    return CompletableFuture.allOf(cleanupProofs.stream().map(CompletionStage::toCompletableFuture)
                            .toArray(CompletableFuture[]::new));
                }
            });
        }
    }
}
