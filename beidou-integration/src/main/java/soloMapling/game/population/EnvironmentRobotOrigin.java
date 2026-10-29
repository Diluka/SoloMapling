package soloMapling.game.population;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.beidou.BotGameContexts;
import soloMapling.beidou.BotRegistry;
import soloMapling.game.context.GameContext;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RoleBinding;

import java.util.Map;

import static soloMapling.FreeMarket.FMShopDescGen.getRandomCharacterIGN;

/** Headless environment origin owned by one runtime; it never saves back to the template. */
public final class EnvironmentRobotOrigin implements RoleBinding {
    private final Server server;
    private final GameContext.CharacterRef ref;
    private BotGameContexts.Binding current;

    private EnvironmentRobotOrigin(Server server, GameContext.CharacterRef ref) {
        this.server = server;
        this.ref = ref;
    }

    /** Called only by the already admitted OPEN body on this runtime's character lane. */
    public static EnvironmentRobotOrigin receive(CharacterRuntime runtime, ExecutionScope.WorkHandle openingWork,
                                                  RobotPopulationConfig.CharacterSpec specification) {
        var resource = runtime.reserveOrigin(openingWork);
        EnvironmentRobotOrigin received = null;
        try {
            Server server = Server.getInstance();
            var spawn = specification.spawn();
            World world = server.getWorld(spawn.world());
            if (world == null) throw new IllegalStateException("robot_world_unavailable");
            Channel channel = world.getChannel(spawn.channel());
            if (channel == null) throw new IllegalStateException("robot_channel_unavailable");
            MapleMap map = channel.getMapFactory().getMap(spawn.map());
            if (map == null || map.getId() != spawn.map()) throw new IllegalStateException("robot_map_unavailable");
            var portal = map.getPortal(0);
            if (portal == null) throw new IllegalStateException("robot_spawn_unavailable");

            Character character = BotGeneration.loadTemplate();
            BotClient client = (BotClient) character.getClient();
            client.setWorld(world.getId());
            client.setChannel(channel.getId());
            character.setWorld(world.getId());
            character.setId(BotRegistry.allocateId());
            character.setName(getRandomCharacterIGN());
            character.setMap(map);
            character.setPosition(portal.getPosition());
            character.setStance(5);
            received = new EnvironmentRobotOrigin(server, new GameContext.CharacterRef(
                    runtime.acceptedEpoch(), character, client, world, channel));
            resource.adopt(received);

            BotRegistry.register(character);
            channel.addPlayer(character);
            world.getPlayerStorage().addPlayer(character);
            map.addPlayer(character);
            received.current = BotGameContexts.capture(server, character, client, world, channel);
            return received;
        } catch (RuntimeException | Error failure) {
            if (received == null) {
                resource.creationFailed("environment_robot_creation_failed");
                resource.confirmAbsent();
            }
            throw failure;
        }
    }

    @Override public String kind() { return "environment_robot"; }
    @Override public Object identity() { return this; }
    @Override public GameContext.CharacterRef characterRef() { return ref; }
    @Override public void requireCurrent() { BotGameContexts.requireCurrent(server, current); }

    @Override public Map<String, Object> observe() {
        requireCurrent();
        Character character = ref.character();
        var position = character.getPosition();
        return Map.of("character_id", character.getId(), "name", character.getName(),
                "world_id", ref.world().getId(), "channel_id", ref.channel().getId(),
                "map_id", character.getMapId(), "x", position.x, "y", position.y);
    }

    @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
        permit.requireExecuting(this);
        Character character = ref.character();
        MapleMap map = character.getMap();
        if (map != null && map.getCharacterById(character.getId()) == character) map.removePlayer(character);
        if (ref.channel().getPlayerStorage().getCharacterById(character.getId()) == character) {
            ref.channel().removePlayer(character);
        }
        if (ref.world().getPlayerStorage().getCharacterById(character.getId()) == character) {
            ref.world().getPlayerStorage().removePlayer(character.getId());
        }
        BotRegistry.unregister(character);
        MapleMap remainingMap = character.getMap();
        if ((remainingMap != null && remainingMap.getCharacterById(character.getId()) == character)
                || ref.channel().getPlayerStorage().getCharacterById(character.getId()) == character
                || ref.world().getPlayerStorage().getCharacterById(character.getId()) == character
                || BotRegistry.isRegistered(character)) {
            throw new IllegalStateException("environment_robot_cleanup_unverified");
        }
        return new ReleaseProof(true);
    }
}
