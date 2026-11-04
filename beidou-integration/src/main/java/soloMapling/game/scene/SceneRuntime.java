package soloMapling.game.scene;

import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One shared behavior host per actual map and world epoch; never a second native world model. */
public final class SceneRuntime {
    private final String id = "scene-" + UUID.randomUUID();
    private final Server server;
    private final WorldEpoch.Token epoch;
    private final World world;
    private final Channel channel;
    private final MapleMap map;
    private final EnvironmentSceneModule environment;
    private final CharacterSceneModule characters;
    private final MonsterSceneModule monsters;
    private final AbilitySceneModule abilities;
    private final PresentationSceneModule presentation;
    private final List<SceneRuntimeModule> modules;
    private final Set<SceneActorBinding> members = new LinkedHashSet<>();
    private final Set<SceneActorBinding> departing = new LinkedHashSet<>();
    private boolean closed;
    private String failure;

    SceneRuntime(Server server, GameContext.SceneRef origin) {
        this.server = server;
        epoch = origin.actor().epoch();
        world = origin.actor().world();
        channel = origin.actor().channel();
        map = origin.map();
        environment = new EnvironmentSceneModule(this);
        characters = new CharacterSceneModule(this);
        monsters = new MonsterSceneModule(this);
        abilities = new AbilitySceneModule(this);
        presentation = new PresentationSceneModule();
        modules = List.of(environment, characters, monsters, abilities, presentation);
    }

    public String id() { return id; }
    public Server server() { return server; }
    public MapleMap map() { return map; }
    public WorldEpoch.Token epoch() { return epoch; }
    public EnvironmentSceneModule environment() { return environment; }
    public CharacterSceneModule characters() { return characters; }
    public MonsterSceneModule monsters() { return monsters; }
    public AbilitySceneModule abilities() { return abilities; }
    public PresentationSceneModule presentation() { return presentation; }
    public synchronized List<SceneActorBinding> members() { return List.copyOf(members); }
    public synchronized boolean closed() { return closed; }

    synchronized boolean reserve(SceneActorBinding actor) {
        if (closed) return false;
        members.add(actor);
        return true;
    }

    void enter(SceneActorBinding actor) {
        synchronized (actor) {
            if (!actor.active()) return;
            modules.forEach(module -> module.onEnter(actor));
        }
    }

    void leave(SceneActorBinding actor) {
        synchronized (actor) {
            synchronized (this) {
                if (!members.remove(actor)) return;
                departing.add(actor);
            }
            var drained = actor.leave();
            try { modules.forEach(module -> module.onLeave(actor)); }
            finally {
                drained.whenComplete((ignored, failure) -> {
                    boolean finish;
                    synchronized (this) {
                        if (failure != null) {
                            this.failure = "scene_cleanup_unverified";
                            return;
                        }
                        departing.remove(actor);
                        finish = members.isEmpty() && departing.isEmpty() && !closed;
                        if (finish) closed = true;
                    }
                    if (finish) {
                        modules.reversed().forEach(SceneRuntimeModule::close);
                        SceneRuntimeManager.forget(this);
                    }
                });
            }
        }
    }

    void packet(SceneActorBinding actor, Packet packet) {
        if (actor.active()) modules.forEach(module -> module.onPacket(actor, packet));
    }

    public synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("scene_id", id);
        result.put("epoch", epoch().value());
        result.put("world_id", world.getId());
        result.put("channel_id", channel.getId());
        result.put("map_id", map().getId());
        result.put("state", closed ? "CLOSED" : failure != null ? "FAILED" : members.isEmpty() ? "CLOSING" : "OPEN");
        if (failure != null) result.put("failure", failure);
        result.put("member_count", members.size());
        result.put("departing_member_count", departing.size());
        result.put("members", members.stream().map(actor -> {
            var member = new LinkedHashMap<String, Object>();
            member.put("character_id", actor.characterRef().character().getId());
            member.put("runtime_id", actor.runtime().id());
            var contacts = actor.characterState().contactSnapshot();
            member.put("contact_count", contacts.count());
            member.put("background_tasks", actor.backgroundSnapshot());
            if (contacts.latest() != null) member.put("latest_contact", contacts.latest().toMap());
            var scope = actor.context().scope().snapshot();
            member.put("scope", Map.of("state", scope.state().name(), "accepted_work", scope.acceptedWork(),
                    "running_bodies", scope.runningBodies(), "pending_carriers", scope.pendingCarriers(),
                    "outstanding_resources", scope.outstandingResources()));
            return Map.copyOf(member);
        }).toList());
        result.put("modules", modules.stream().map(module -> module.getClass().getSimpleName()).toList());
        result.put("monster_driver", monsters.snapshot());
        return Map.copyOf(result);
    }
}
