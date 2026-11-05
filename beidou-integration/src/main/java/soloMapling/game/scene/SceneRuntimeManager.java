package soloMapling.game.scene;

import org.gms.client.Character;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.gms.server.maps.MapleMap;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.runtime.CharacterRuntime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Exact native identities, not map IDs: channel instances and event maps can share the same numeric ID. */
public final class SceneRuntimeManager {
    private static final Map<WorldEpoch.Token, Map<MapleMap, SceneRuntime>> SCENES = new IdentityHashMap<>();
    private static final Map<Character, SceneActorBinding> ACTORS = new IdentityHashMap<>();

    private SceneRuntimeManager() { }

    static SceneActorBinding find(Character character) {
        synchronized (SCENES) { return ACTORS.get(character); }
    }

    public static SceneActorBinding current(Server server, GameContext.CharacterRef character) {
        NativeContextChecks.requireCharacterCurrent(server, character);
        SceneActorBinding actor;
        synchronized (SCENES) { actor = ACTORS.get(character.character()); }
        if (actor == null || actor.characterRef() != character) {
            throw new CharacterRuntime.Invalid("scene_membership_unavailable");
        }
        actor.requireCurrent();
        return actor;
    }

    public static void enter(Server server, CharacterRuntime runtime, CharacterRuntime.ModuleContext context,
                             SceneCharacterState characterState) {
        var nativeScene = NativeContextChecks.captureScene(server, context.characterRef());
        SceneActorBinding previous;
        SceneActorBinding actor;
        synchronized (SCENES) {
            previous = ACTORS.get(nativeScene.actor().character());
            if (previous != null && previous.nativeScene().map() == nativeScene.map() && previous.active()) return;
            var scenes = SCENES.computeIfAbsent(nativeScene.actor().epoch(), ignored -> new IdentityHashMap<>());
            var scene = scenes.get(nativeScene.map());
            if (scene == null || scene.closed()) {
                scene = new SceneRuntime(server, nativeScene);
            }
            actor = new SceneActorBinding(scene, runtime, context, nativeScene, characterState);
            if (!scene.reserve(actor)) {
                scene = new SceneRuntime(server, nativeScene);
                actor = new SceneActorBinding(scene, runtime, context, nativeScene, characterState);
                scene.reserve(actor);
            }
            scenes.put(nativeScene.map(), scene);
            ACTORS.put(nativeScene.actor().character(), actor);
        }
        try {
            if (previous != null) previous.scene().leave(previous);
            actor.scene().enter(actor);
        } catch (RuntimeException | Error failure) {
            synchronized (SCENES) { ACTORS.remove(nativeScene.actor().character(), actor); }
            try { actor.scene().leave(actor); }
            catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public static void leave(Character character) {
        SceneActorBinding actor;
        synchronized (SCENES) { actor = ACTORS.remove(character); }
        if (actor != null) actor.scene().leave(actor);
    }

    public static void packet(Character character, Packet packet) {
        SceneActorBinding actor;
        synchronized (SCENES) { actor = ACTORS.get(character); }
        if (actor != null) actor.scene().packet(actor, packet);
    }

    public static void attackInput(Character character, AttackInfo input) {
        int encoded = (input.direction & 0xff) | (input.stance & 0xff) << 8;
        attackAction(character, input.skill, encoded & 0x7fff, input.speed);
    }

    public static void attackAction(Character character, int skillId, int bodyAction, int speed) {
        var actor = find(character);
        if (actor == null || !actor.active()) return;
        int duration = SceneActionTiming.bodyDurationMs(skillId, bodyAction, speed);
        if (duration >= 0) actor.characterState().lockActionUntil(System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(duration));
    }

    public static void buffAction(Character character, int skillId) {
        var actor = find(character);
        if (actor == null || !actor.active()) return;
        int duration = SceneActionTiming.buffDurationMs(skillId);
        if (duration >= 0) actor.characterState().lockActionUntil(System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(duration));
    }

    static void forget(SceneRuntime scene) {
        synchronized (SCENES) {
            var scenes = SCENES.get(scene.epoch());
            if (scenes == null) return;
            scenes.remove(scene.map(), scene);
            if (scenes.isEmpty()) SCENES.remove(scene.epoch());
        }
    }

    public static List<Map<String, Object>> snapshots() {
        List<SceneRuntime> scenes;
        synchronized (SCENES) { scenes = SCENES.values().stream().flatMap(value -> value.values().stream()).toList(); }
        return scenes.stream().map(SceneRuntime::snapshot).toList();
    }

    /** Exact read-only map lookup; observation never creates or starts a simulation. */
    public static Map<String, Object> snapshot(MapleMap map) {
        SceneRuntime scene;
        synchronized (SCENES) {
            var current = SCENES.get(WorldEpoch.snapshot().token());
            scene = current == null ? null : current.get(map);
        }
        return scene == null ? Map.of("state", "UNAVAILABLE", "reason", "scene_runtime_not_running")
                : scene.snapshot();
    }

    public static Map<String, Object> snapshot(Character character) {
        SceneActorBinding actor;
        synchronized (SCENES) { actor = ACTORS.get(character); }
        return actor == null ? Map.of("state", "UNAVAILABLE") : actor.scene().snapshot();
    }
}
