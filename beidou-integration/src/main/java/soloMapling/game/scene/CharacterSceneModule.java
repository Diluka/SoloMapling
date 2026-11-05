package soloMapling.game.scene;

import soloMapling.game.action.NativeMapEntryActions;
import soloMapling.game.context.GameContext;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.runtime.CharacterRuntime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Per-entry client behavior, sharing the native character instead of storing another role state. */
public final class CharacterSceneModule implements SceneRuntimeModule {
    private record Actor(CharacterMotion motion, CharacterAttack attack, CharacterDamage damage) { }

    private final SceneRuntime scene;
    private final Map<SceneActorBinding, Actor> actors = new IdentityHashMap<>();

    public CharacterSceneModule(SceneRuntime scene) { this.scene = scene; }

    @Override public void onEnter(SceneActorBinding binding) {
        var actor = new Actor(new CharacterMotion(binding, scene.server()),
                new CharacterAttack(binding), new CharacterDamage(binding));
        synchronized (this) { actors.put(binding, actor); }
        var context = binding.context();
        var entry = context.acceptWork();
        try {
            binding.awaitWork(entry);
            var submission = context.submit(SceneMembershipModule.CONTROL, entry, binding::requireCurrent, () -> {
                if (entry.cancelRequested()) return;
                var character = binding.characterRef().character();
                // Portal/revive may have completed this entry synchronously in the preceding lane body.
                if (!character.isChangingMaps()) {
                    actor.motion.startEntryLanding();
                    return;
                }
                boolean handlerError;
                try {
                    handlerError = NativeMapEntryActions.acknowledge(scene.server(), binding.nativeScene(),
                            () -> {
                                binding.requireCurrent();
                                if (entry.cancelRequested()) throw new CancellationException();
                            },
                            () -> context.recordActivity(entry, "map_entry_ack_started", "SCENE_ENTRY"));
                } catch (GameContext.Invalid | CharacterRuntime.Invalid | CancellationException ended) {
                    if (!binding.active() || entry.cancelRequested()) return;
                    throw ended;
                }
                if (handlerError || character.isChangingMaps()) {
                    context.recordActivity(entry, "map_entry_ack_failed", "SCENE_ENTRY");
                    throw new IllegalStateException("scene_map_entry_ack_failed");
                }
                context.recordActivity(entry, "map_entry_ready", "SCENE_ENTRY");
                actor.motion.startEntryLanding();
            }, () -> {});
            if (submission.status() == CharacterCommandLane.Status.REJECTED) {
                throw new CharacterRuntime.Invalid("scene_map_entry_not_accepted");
            }
        } catch (RuntimeException | Error failure) {
            entry.reject();
            throw failure;
        }
        actor.damage.start();
    }

    @Override public void onLeave(SceneActorBinding binding) {
        Actor actor;
        synchronized (this) { actor = actors.remove(binding); }
        if (actor != null) {
            actor.motion.leave();
            actor.damage.stop();
        }
    }

    public CharacterMotion motion(SceneActorBinding binding) { return actor(binding).motion; }
    public CharacterAttack attack(SceneActorBinding binding) { return actor(binding).attack; }
    public CharacterDamage damage(SceneActorBinding binding) { return actor(binding).damage; }

    private Actor actor(SceneActorBinding binding) {
        binding.requireCurrent();
        synchronized (this) {
            var actor = actors.get(binding);
            if (actor == null) throw new IllegalStateException("scene_character_unavailable");
            return actor;
        }
    }

    @Override public void close() {
        List<Actor> remaining;
        synchronized (this) { remaining = List.copyOf(actors.values()); actors.clear(); }
        remaining.forEach(actor -> {
            actor.motion.leave();
            actor.damage.stop();
        });
    }
}
