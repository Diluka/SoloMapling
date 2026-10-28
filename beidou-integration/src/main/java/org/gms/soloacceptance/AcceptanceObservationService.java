package org.gms.soloacceptance;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.Server;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.ActorControlService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = "enabled", havingValue = "true", matchIfMissing = false)
public final class AcceptanceObservationService {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_SCENES = 8_192;
    private final Supplier<Server> serverSource;
    private final Supplier<Map<String, Object>> identitySource;
    private final int registryLimit;
    private Map<String, SceneReference> references = new LinkedHashMap<>();
    private String referenceGeneration;
    @Autowired(required = false)
    private ObjectProvider<ActorControlService> actorControls;

    public AcceptanceObservationService() {
        this(Server::getInstance, SourceIdentity::read, MAX_SCENES);
    }

    AcceptanceObservationService(Supplier<Server> serverSource, Supplier<Map<String, Object>> identitySource,
                                 int registryLimit) {
        this.serverSource = serverSource;
        this.identitySource = identitySource;
        this.registryLimit = registryLimit;
    }

    public Map<String, Object> capabilities() {
        AcceptanceObservationRuntime.State runtime = AcceptanceObservationRuntime.snapshot();
        Map<String, Object> result = metadata(runtime.generation());
        result.put("service", "solomapling-acceptance");
        ActorControlService controls = actorControls == null ? null : actorControls.getIfAvailable();
        Map<String, Object> actorCapabilities = controls == null ? null : controls.publicCapabilities();
        List<String> enabledTools = new ArrayList<>(List.of("game_capabilities", "game_observe"));
        if (actorCapabilities != null) {
            for (Object tool : (List<?>) actorCapabilities.get("enabled_tools")) {
                enabledTools.add((String) tool);
            }
            result.put("actor_control", actorCapabilities);
        }
        result.put("implementation_round", controls == null ? "R1" : "R2a");
        result.put("enabled_tools", enabledTools);
        result.put("scopes", controls == null ? List.of("world", "scene") : List.of("world", "scene", "actor"));
        result.put("actions", actorCapabilities == null ? List.of() : actorCapabilities.get("actions"));
        result.put("server_online", runtime.active() && serverSource.get().isOnline());
        result.put("source", SourceIdentity.publicFields(identitySource.get()));
        result.put("default_limit", DEFAULT_LIMIT);
        result.put("maximum_limit", 500);
        result.put("scene_discovery", "loaded_channel_maps_and_instances_referenced_by_online_characters");
        result.put("event_stream", false);
        return result;
    }

    public synchronized Map<String, Object> observe(ObservationRequest request) {
        if (request == null || !("world".equals(request.scope()) || "scene".equals(request.scope()))) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_scope");
        }
        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        if (limit < 1 || limit > 500) throw error(HttpStatus.BAD_REQUEST, "invalid_limit");
        if ("world".equals(request.scope()) && request.sceneRef() != null) {
            throw error(HttpStatus.BAD_REQUEST, "scene_ref_not_allowed");
        }
        if ("scene".equals(request.scope()) && (request.sceneRef() == null
                || !request.sceneRef().matches("scene-[0-9a-f-]{36}"))) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_scene_ref");
        }
        AcceptanceObservationRuntime.State runtime = AcceptanceObservationRuntime.snapshot();
        Server server = serverSource.get();
        if (!runtime.active() || !server.isOnline()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "server_offline");
        Discovery discovery = refreshReferences(server, runtime);
        Map<String, Object> result = metadata(runtime.generation());
        result.put("scope", request.scope());
        if ("world".equals(request.scope())) {
            List<Map<String, Object>> scenes = new ArrayList<>();
            for (SceneReference ref : references.values()) scenes.add(ref.description());
            result.put("worlds", discovery.worlds());
            result.put("scenes", Map.of("total_count", scenes.size(), "truncated", scenes.size() > limit,
                    "items", scenes.subList(0, Math.min(scenes.size(), limit))));
        } else {
            SceneReference ref = references.get(request.sceneRef());
            MapleMap actual = ref == null ? null : ref.map().get();
            if (actual == null) throw error(HttpStatus.GONE, "stale_scene_ref");
            result.put("context", ref.description(actual));
            result.putAll(SceneSnapshotMapper.snapshot(actual, limit));
        }
        if (!server.isOnline() || !runtime.equals(AcceptanceObservationRuntime.snapshot())) {
            references.clear();
            throw error(HttpStatus.CONFLICT, "runtime_changed");
        }
        return result;
    }

    /** Legacy internal read-only entry; control callers pass their already-bound CharacterRef instead. */
    public synchronized Map<String, Object> contextForActor(Character actor) {
        AcceptanceObservationRuntime.State runtime = AcceptanceObservationRuntime.snapshot();
        try {
            Server server = serverSource.get();
            requireActorWorld(server, runtime);
            Client client = actor == null ? null : actor.getClient();
            if (client == null || !actor.isLoggedInWorld()) {
                throw error(HttpStatus.GONE, "actor_not_in_world");
            }
            World world = server.getWorld(actor.getWorld());
            Channel channel = world == null ? null : world.getChannel(client.getChannel());
            if (channel == null) throw error(HttpStatus.GONE, "actor_not_in_current_world");
            GameContext.CharacterRef ref = NativeContextChecks.captureCharacter(server, actor, client, world, channel);
            return contextForActor(server, runtime, ref);
        } catch (GameContext.Invalid invalid) {
            if (runtime.equals(AcceptanceObservationRuntime.snapshot())
                    && (invalid.reason() == GameContext.Failure.SCENE_CHANGED
                    || invalid.reason() == GameContext.Failure.EVENT_CHANGED)) {
                throw error(HttpStatus.GONE, "actor_scene_unavailable");
            }
            throw actorContextFailure(runtime, invalid);
        } catch (ObservationException failure) {
            requireActorGeneration(runtime);
            throw failure;
        } catch (RuntimeException ignored) {
            throw actorReadFailure(runtime);
        }
    }

    /** Resolve one authorized binding without discovering or enumerating the world's other scenes. */
    public synchronized Map<String, Object> contextForActor(GameContext.CharacterRef actor) {
        AcceptanceObservationRuntime.State runtime = AcceptanceObservationRuntime.snapshot();
        try {
            Server server = serverSource.get();
            requireActorWorld(server, runtime);
            if (actor == null || actor.character() == null || actor.client() == null) {
                throw error(HttpStatus.GONE, "actor_not_in_world");
            }
            return contextForActor(server, runtime, actor);
        } catch (GameContext.Invalid invalid) {
            throw actorContextFailure(runtime, invalid);
        } catch (ObservationException failure) {
            requireActorGeneration(runtime);
            throw failure;
        } catch (RuntimeException ignored) {
            throw actorReadFailure(runtime);
        }
    }

    private Map<String, Object> contextForActor(Server server, AcceptanceObservationRuntime.State runtime,
                                               GameContext.CharacterRef actor) {
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        useGeneration(runtime.generation());
        SceneReference reference = registerActorScene(scene, runtime.generation());
        Map<String, Object> context = reference.description(scene.map());
        NativeContextChecks.requireSceneCurrent(server, scene);
        if (!server.isOnline() || !runtime.equals(AcceptanceObservationRuntime.snapshot())) {
            references.clear();
            throw error(HttpStatus.CONFLICT, "runtime_changed");
        }
        context.put("runtime_generation", runtime.generation());
        return context;
    }

    private void requireActorWorld(Server server, AcceptanceObservationRuntime.State runtime) {
        if (!runtime.active() || server == null || !server.isOnline()) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "server_offline");
        }
    }

    private ObservationException actorContextFailure(AcceptanceObservationRuntime.State runtime,
                                                     GameContext.Invalid invalid) {
        requireActorGeneration(runtime);
        if (invalid.reason() == GameContext.Failure.RUNTIME_CHANGED) {
            references.clear();
            return error(HttpStatus.CONFLICT, "runtime_changed");
        }
        return switch (invalid.reason()) {
            case WORLD_OFFLINE -> error(HttpStatus.SERVICE_UNAVAILABLE, "server_offline");
            case READ_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
            case SCENE_CHANGED, EVENT_CHANGED -> error(HttpStatus.CONFLICT, "actor_context_mismatch");
            default -> error(HttpStatus.GONE, "actor_not_in_current_world");
        };
    }

    private ObservationException actorReadFailure(AcceptanceObservationRuntime.State runtime) {
        requireActorGeneration(runtime);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
    }

    private void requireActorGeneration(AcceptanceObservationRuntime.State runtime) {
        if (!runtime.equals(AcceptanceObservationRuntime.snapshot())) {
            references.clear();
            throw error(HttpStatus.CONFLICT, "runtime_changed");
        }
    }

    private SceneReference registerActorScene(GameContext.SceneRef scene, String generation) {
        GameContext.CharacterRef actor = scene.actor();
        Object runtime = scene.mapEim() == null ? actor.channel() : scene.mapEim();
        String runtimeKey = null;
        SceneReference existing = null;
        var iterator = references.values().iterator();
        while (iterator.hasNext()) {
            SceneReference ref = iterator.next();
            Object owner = ref.runtime().get();
            Channel channel = ref.channel().get();
            MapleMap map = ref.map().get();
            if (owner == null || channel == null || map == null) {
                iterator.remove();
                continue;
            }
            if (channel == actor.channel() && map == scene.map()) {
                if (owner == runtime) existing = ref;
                else {
                    iterator.remove();
                    continue;
                }
            }
            if (owner == runtime) runtimeKey = ref.runtimeKey();
        }
        if (existing != null) return existing;
        if (references.size() >= registryLimit) throw error(HttpStatus.SERVICE_UNAVAILABLE, "scene_capacity_exceeded");
        SceneReference added = new SceneReference("scene-" + UUID.randomUUID(), generation, actor.world().getId(),
                actor.channel().getId(), new WeakReference<>(actor.channel()), new WeakReference<>(scene.map()),
                new WeakReference<>(runtime), runtimeKey == null ? "runtime-" + UUID.randomUUID() : runtimeKey);
        references.put(added.key(), added);
        return added;
    }

    private Discovery refreshReferences(Server server, AcceptanceObservationRuntime.State runtime) {
        useGeneration(runtime.generation());
        Discovery discovery = discover(server);
        reconcile(discovery.scenes(), runtime.generation());
        return discovery;
    }

    private void useGeneration(String generation) {
        if (!generation.equals(referenceGeneration)) {
            references.clear();
            referenceGeneration = generation;
        }
    }

    private Discovery discover(Server server) {
        List<ObservedScene> scenes = new ArrayList<>();
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (World world : new ArrayList<>(server.getWorlds())) {
            if (world == null) continue;
            List<Map<String, Object>> channels = new ArrayList<>();
            for (Channel channel : new ArrayList<>(world.getChannels())) {
                if (channel == null) continue;
                IdentityHashMap<MapleMap, Boolean> maps = new IdentityHashMap<>();
                addLoadedMaps(maps, channel.getMapFactory());
                PlayerStorage storage = channel.getPlayerStorage();
                List<Character> players = storage == null ? List.of() : new ArrayList<>(storage.getAllCharacters());
                IdentityHashMap<EventInstanceManager, Boolean> events = new IdentityHashMap<>();
                for (Character player : players) {
                    if (player == null) continue;
                    MapleMap map = player.getMap();
                    if (map != null) {
                        maps.put(map, true);
                        if (map.getEventInstance() != null) events.put(map.getEventInstance(), true);
                    }
                    if (player.getEventInstance() != null) events.put(player.getEventInstance(), true);
                }
                for (EventInstanceManager event : events.keySet()) addLoadedMaps(maps, event.getMapFactory());
                for (MapleMap map : maps.keySet()) {
                    scenes.add(new ObservedScene(world.getId(), channel.getId(), channel, map));
                    if (scenes.size() > registryLimit) throw error(HttpStatus.SERVICE_UNAVAILABLE, "scene_capacity_exceeded");
                }
                channels.add(Map.of("channel_id", channel.getId(), "online_player_count", players.size(),
                        "loaded_scene_count", maps.size()));
            }
            channels.sort(Comparator.comparingInt(row -> (int) row.get("channel_id")));
            worlds.add(Map.of("world_id", world.getId(), "channels", channels));
        }
        worlds.sort(Comparator.comparingInt(row -> (int) row.get("world_id")));
        scenes.sort(Comparator.comparingInt(ObservedScene::worldId).thenComparingInt(ObservedScene::channelId)
                .thenComparingInt(scene -> scene.map().getId()));
        return new Discovery(worlds, scenes);
    }

    private static void addLoadedMaps(IdentityHashMap<MapleMap, Boolean> result, MapManager manager) {
        if (manager == null) return;
        for (MapleMap map : manager.getMaps().values()) if (map != null) result.put(map, true);
    }

    private void reconcile(List<ObservedScene> scenes, String generation) {
        IdentityHashMap<Object, String> runtimeKeys = new IdentityHashMap<>();
        IdentityHashMap<Channel, IdentityHashMap<MapleMap, IdentityHashMap<Object, SceneReference>>> previous = new IdentityHashMap<>();
        for (SceneReference ref : references.values()) {
            Object owner = ref.runtime().get();
            Channel channel = ref.channel().get();
            MapleMap map = ref.map().get();
            if (owner != null && channel != null && map != null) {
                runtimeKeys.put(owner, ref.runtimeKey());
                previous.computeIfAbsent(channel, ignored -> new IdentityHashMap<>())
                        .computeIfAbsent(map, ignored -> new IdentityHashMap<>()).put(owner, ref);
            }
        }
        Map<String, SceneReference> current = new LinkedHashMap<>();
        for (ObservedScene scene : scenes) {
            Object runtime = scene.map().getEventInstance() == null ? scene.channel() : scene.map().getEventInstance();
            var channelRefs = previous.get(scene.channel());
            var mapRefs = channelRefs == null ? null : channelRefs.get(scene.map());
            SceneReference existing = mapRefs == null ? null : mapRefs.get(runtime);
            if (existing == null) {
                String runtimeKey = runtimeKeys.computeIfAbsent(runtime, ignored -> "runtime-" + UUID.randomUUID());
                existing = new SceneReference("scene-" + UUID.randomUUID(), generation, scene.worldId(),
                        scene.channelId(), new WeakReference<>(scene.channel()), new WeakReference<>(scene.map()),
                        new WeakReference<>(runtime), runtimeKey);
            }
            current.put(existing.key(), existing);
        }
        references = current;
    }

    private static Map<String, Object> metadata(String generation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("captured_at", Instant.now().toString());
        result.put("runtime_generation", generation);
        result.put("information_level", "server_diagnostic");
        result.put("snapshot_consistency", "best_effort");
        return result;
    }

    private static ObservationException error(HttpStatus status, String reason) {
        return new ObservationException(status, reason);
    }

    private record Discovery(List<Map<String, Object>> worlds, List<ObservedScene> scenes) { }
    private record ObservedScene(int worldId, int channelId, Channel channel, MapleMap map) { }
    private record SceneReference(String key, String generation, int worldId, int channelId,
                                  WeakReference<Channel> channel, WeakReference<MapleMap> map,
                                  WeakReference<Object> runtime, String runtimeKey) {
        Map<String, Object> description() {
            MapleMap actual = map.get();
            if (actual == null) throw error(HttpStatus.GONE, "stale_scene_ref");
            return description(actual);
        }

        Map<String, Object> description(MapleMap actual) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scene_ref", key);
            result.put("world_id", worldId);
            result.put("channel_id", channelId);
            result.put("map_id", actual.getId());
            result.put("map_instance_key", key);
            result.put("runtime_key", runtimeKey);
            result.put("player_count", actual.getAllPlayers().size());
            result.put("event_instance", actual.getEventInstance() != null);
            return result;
        }
    }
}
