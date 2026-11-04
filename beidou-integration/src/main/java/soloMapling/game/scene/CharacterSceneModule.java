package soloMapling.game.scene;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
