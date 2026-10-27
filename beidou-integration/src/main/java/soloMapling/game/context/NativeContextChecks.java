package soloMapling.game.context;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;

import java.util.function.Function;

import static soloMapling.game.context.GameContext.Failure;

/** Read-only native structure checks. Identity policy and execution ownership belong to callers. */
public final class NativeContextChecks {
    private NativeContextChecks() { }

    public static GameContext.CharacterRef captureCharacter(Server server, Character character,
                                                            Client client, World world, Channel channel) {
        return read(server, state -> {
            GameContext.CharacterRef ref = new GameContext.CharacterRef(state.token(), character, client, world, channel);
            requireCharacter(server, state, ref);
            return ref;
        });
    }

    public static void requireCharacterCurrent(Server server, GameContext.CharacterRef ref) {
        read(server, state -> {
            requireCharacter(server, state, ref);
            return null;
        });
    }

    public static GameContext.SceneRef captureScene(Server server, GameContext.CharacterRef ref) {
        return read(server, state -> {
            requireCharacter(server, state, ref);
            Character character = ref.character();
            MapleMap map = character.getMap();
            require(map != null && map.getCharacterById(character.getId()) == character, Failure.NOT_IN_WORLD);
            GameContext.SceneRef scene = new GameContext.SceneRef(ref, map, character.getEventInstance(), map.getEventInstance());
            requireScene(scene);
            requireCharacter(server, state, ref);
            requireScene(scene);
            return scene;
        });
    }

    public static void requireSceneCurrent(Server server, GameContext.SceneRef ref) {
        read(server, state -> {
            require(ref != null, Failure.SCENE_CHANGED);
            requireCharacter(server, state, ref.actor());
            requireScene(ref);
            requireCharacter(server, state, ref.actor());
            requireScene(ref);
            return null;
        });
    }

    private static void requireCharacter(Server server, WorldEpoch.State state, GameContext.CharacterRef ref) {
        requireCharacterSnapshot(server, state, ref);
        // Native changes span several independently locked objects. Recheck after reading them all.
        requireCharacterSnapshot(server, state, ref);
    }

    private static void requireCharacterSnapshot(Server server, WorldEpoch.State state, GameContext.CharacterRef ref) {
        require(ref != null, Failure.CHARACTER_CHANGED);
        require(ref.epoch() == state.token(), Failure.WORLD_CHANGED);
        World world = ref.world();
        Channel channel = ref.channel();
        require(world != null && channel != null, Failure.WORLD_CHANGED);
        require(server.getWorld(world.getId()) == world && world.getChannel(channel.getId()) == channel,
                Failure.WORLD_CHANGED);
        Character character = ref.character();
        Client client = ref.client();
        require(character != null && client != null, Failure.CHARACTER_CHANGED);
        require(character.getClient() == client && client.getPlayer() == character, Failure.CHARACTER_CHANGED);
        require(character.getWorld() == world.getId() && client.getWorld() == world.getId()
                && client.getChannel() == channel.getId(), Failure.WORLD_CHANGED);
        PlayerStorage worldPlayers = world.getPlayerStorage();
        PlayerStorage channelPlayers = channel.getPlayerStorage();
        require(worldPlayers != null && channelPlayers != null, Failure.NOT_IN_WORLD);
        int characterId = character.getId();
        require(worldPlayers.getCharacterById(characterId) == character
                && channelPlayers.getCharacterById(characterId) == character, Failure.CHARACTER_CHANGED);
        MapleMap currentMap = character.getMap();
        require(currentMap != null && currentMap.getCharacterById(characterId) == character, Failure.NOT_IN_WORLD);
        // Login flags differ between ordinary native characters and registered V1 bots.
    }

    private static void requireScene(GameContext.SceneRef ref) {
        Character character = ref.actor().character();
        MapleMap map = character.getMap();
        require(map == ref.map(), Failure.SCENE_CHANGED);
        require(map != null && map.getCharacterById(character.getId()) == character, Failure.SCENE_CHANGED);
        require(character.getEventInstance() == ref.memberEim() && map.getEventInstance() == ref.mapEim(),
                Failure.EVENT_CHANGED);
        if (ref.memberEim() != null) {
            require(ref.memberEim().getPlayerById(character.getId()) == character, Failure.EVENT_CHANGED);
            requireEventLive(ref.memberEim());
        }
        if (ref.mapEim() != null && ref.mapEim() != ref.memberEim()) requireEventLive(ref.mapEim());
        require(character.getMap() == map, Failure.SCENE_CHANGED);
        require(character.getEventInstance() == ref.memberEim() && map.getEventInstance() == ref.mapEim(),
                Failure.EVENT_CHANGED);
    }

    private static void requireEventLive(EventInstanceManager event) {
        boolean disposed;
        synchronized (event) {
            // Native dispose writes this non-volatile flag under this same monitor.
            disposed = event.isEventDisposed();
        }
        require(!disposed, Failure.EVENT_CHANGED);
    }

    private static <T> T read(Server server, Function<WorldEpoch.State, T> query) {
        WorldEpoch.State before = WorldEpoch.snapshot();
        try {
            require(before.active() && server != null && server.isOnline(), Failure.WORLD_OFFLINE);
            T result = query.apply(before);
            require(server.isOnline(), Failure.WORLD_OFFLINE);
            require(WorldEpoch.snapshot() == before, Failure.RUNTIME_CHANGED);
            return result;
        } catch (GameContext.Invalid invalid) {
            if (WorldEpoch.snapshot() != before) throw new GameContext.Invalid(Failure.RUNTIME_CHANGED);
            throw invalid;
        } catch (RuntimeException ignored) {
            if (WorldEpoch.snapshot() != before) throw new GameContext.Invalid(Failure.RUNTIME_CHANGED);
            throw new GameContext.Invalid(Failure.READ_UNAVAILABLE);
        }
    }

    private static void require(boolean condition, Failure reason) {
        if (!condition) throw new GameContext.Invalid(reason);
    }
}
