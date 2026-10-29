package soloMapling.game.population;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runtime/root/registry are real; template loading and native map publication are explicit boundaries. */
class RobotPopulationOriginTest {
    private final ManualExecutor executor = new ManualExecutor();
    private final PlayerStorage worldPlayers = new PlayerStorage();
    private final PlayerStorage channelPlayers = new PlayerStorage();
    private CharacterRuntime runtime;
    private Character character;
    private Character other;
    private MapleMap map;
    private MockedStatic<Server> singleton;
    private MockedStatic<DatabaseConnection> database;
    private MockedStatic<BotGeneration> templates;

    @BeforeEach
    void nativeTemplateCopyAndDestinationChannel() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        Server server = mock(Server.class);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getWorld(0)).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(server.getChannel(0, 2)).thenReturn(channel);
        when(world.getId()).thenReturn(0);
        when(world.getExpRate()).thenReturn(4f);
        when(world.getDropRate()).thenReturn(3f);
        when(world.getMesoRate()).thenReturn(2f);
        when(world.getChannel(2)).thenReturn(channel);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getId()).thenReturn(2);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        doAnswer(call -> { channelPlayers.addPlayer(call.getArgument(0)); return null; })
                .when(channel).addPlayer(any());
        when(channel.removePlayer(any())).thenAnswer(call ->
                channelPlayers.removePlayer(((Character) call.getArgument(0)).getId()) != null);
        singleton = mockStatic(Server.class);
        singleton.when(Server::getInstance).thenReturn(server);

        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenReturn(connection);

        var client = new BotClient(0, 1);
        character = spy(Character.getDefault(client));
        character.setId(71);
        character.setName("SourceCopy");
        client.setPlayer(character);
        templates = mockStatic(BotGeneration.class);
        templates.when(BotGeneration::loadTemplate).thenReturn(character);

        map = spy(new MapleMap(10000, 0, 2, 10000, 1f));
        var portal = mock(Portal.class);
        when(portal.getPosition()).thenReturn(new Point(30, 40));
        doReturn(portal).when(map).getPortal(0);
        MapManager maps = mock(MapManager.class);
        when(channel.getMapFactory()).thenReturn(maps);
        when(maps.getMap(10000)).thenReturn(map);
        doAnswer(call -> {
            Character arriving = call.getArgument(0);
            arriving.setMapId(map.getId());
            members(map).add(arriving);
            return null;
        }).when(map).addPlayer(any());
        doAnswer(call -> { members(map).remove(call.getArgument(0)); return null; })
                .when(map).removePlayer(any());

        var otherClient = new BotClient(0, 2);
        other = Character.getDefault(otherClient);
        other.setId(72);
        other.setName("OtherRole");
        other.setMap(map);
        otherClient.setPlayer(other);
        members(map).add(other);
        channelPlayers.addPlayer(other);
        worldPlayers.addPlayer(other);
        runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(8, 1, 8, 1)), executor);
    }

    @AfterEach
    void closeOnlyTheRuntimeCreatedForThisTest() {
        try {
            if (runtime != null && runtime.snapshot().state() != CharacterRuntime.State.CLOSED) close();
        } finally {
            if (character != null) BotRegistry.unregister(character);
            executor.shutdown();
            if (templates != null) templates.close();
            if (database != null) database.close();
            if (singleton != null) singleton.close();
            ExecutionScope.tryBeginWorldStop();
        }
    }

    @Test
    void configuredChannelAndExactRootClosePreserveTheOtherRole() {
        var opening = runtime.acceptOpeningWork();
        runtime.submitOpening(opening, () -> {
            EnvironmentRobotOrigin.receive(runtime, opening, specification());
            runtime.bindAdoptedOrigin();
        }, () -> {});
        executor.drain();

        assertEquals(CharacterRuntime.State.BOUND, runtime.snapshot().state());
        assertNotEquals(71, character.getId());
        assertEquals(2, character.getClient().getChannel());
        assertTrue(BotRegistry.isRegistered(character));
        assertSame(character, map.getCharacterById(character.getId()));
        assertEquals(2, runtime.observeOrigin().get("channel_id"));
        assertFalse(character.isLoggedIn());
        assertEquals(4, character.getExpRate());
        assertEquals(3, character.getDropRate());
        assertEquals(2, character.getMesoRate());

        close();
        assertEquals(CharacterRuntime.State.CLOSED, runtime.snapshot().state());
        assertFalse(BotRegistry.isRegistered(character));
        assertNull(map.getCharacterById(character.getId()));
        assertNull(channelPlayers.getCharacterById(character.getId()));
        assertNull(worldPlayers.getCharacterById(character.getId()));
        assertSame(other, map.getCharacterById(72));
        assertSame(other, channelPlayers.getCharacterById(72));
        assertSame(other, worldPlayers.getCharacterById(72));
        verify(character, never()).saveCharToDB();
    }

    @Test
    void publicationFailureLeavesTheCreatedRoleOwnedUntilRootCleanup() {
        doThrow(new IllegalStateException("native_map_publication_failed")).when(map).addPlayer(character);
        var opening = runtime.acceptOpeningWork();
        runtime.submitOpening(opening, () -> EnvironmentRobotOrigin.receive(runtime, opening, specification()), () -> {});
        assertEquals("native_map_publication_failed",
                assertThrows(IllegalStateException.class, executor::drain).getMessage());

        assertFalse(runtime.originReleased());
        assertTrue(BotRegistry.isRegistered(character));
        assertSame(character, channelPlayers.getCharacterById(character.getId()));
        close();
        assertEquals(CharacterRuntime.State.CLOSED, runtime.snapshot().state());
        assertFalse(BotRegistry.isRegistered(character));
        assertNull(channelPlayers.getCharacterById(character.getId()));
        assertNull(worldPlayers.getCharacterById(character.getId()));
        assertSame(other, channelPlayers.getCharacterById(72));
    }

    private RobotPopulationConfig.CharacterSpec specification() {
        return new RobotPopulationConfig.CharacterSpec("fmbot", new RobotPopulationConfig.Spawn(0, 2, 10000));
    }

    private void close() {
        runtime.beginClose(Optional.empty());
        executor.drain();
        if (!runtime.originReleased()) {
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            executor.drain();
        }
        assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ZERO));
    }

    @SuppressWarnings("unchecked")
    private static Collection<Character> members(MapleMap map) throws Exception {
        var field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(map);
    }

    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean stopped;
        @Override public int commandQueueCapacity() { return 8; }
        void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }
        @Override public void execute(Runnable command) {
            if (stopped || queue.size() >= 8) throw new RejectedExecutionException();
            queue.addLast(command);
        }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { shutdown(); var pending = new ArrayList<>(queue); queue.clear(); return pending; }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
