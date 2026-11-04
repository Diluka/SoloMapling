package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.gms.util.ThreadLocalUtil;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A trusted controller frame reaches the actual native movement update and map broadcast. */
class NativeMonsterMovementActionsTest {
    @Test void nativeFrameUpdatesMonsterPositionAndRejectsAnExpiredController() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        var server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        var map = mock(MapleMap.class);
        var client = mock(Client.class);
        var actor = mock(Character.class);
        var world = mock(World.class);
        var channel = mock(Channel.class);
        var storage = mock(PlayerStorage.class);
        when(actor.getId()).thenReturn(41);
        when(actor.getClient()).thenReturn(client);
        when(actor.getMap()).thenReturn(map);
        when(actor.isLoggedInWorld()).thenReturn(true);
        when(client.getPlayer()).thenReturn(actor);
        when(client.isLoggedIn()).thenReturn(true);
        when(client.getChannel()).thenReturn(1);
        when(map.getCharacterById(41)).thenReturn(actor);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(41)).thenReturn(actor);
        var stats = new MonsterStats();
        stats.hp = 100;
        var monster = spy(new Monster(100101, stats));
        monster.setObjectId(501);
        monster.setMap(map);
        monster.setPosition(new Point(0, 0));
        doReturn(actor).when(monster).getController();
        when(map.getMapObject(501)).thenReturn(monster);
        when(map.getMonsterByOid(501)).thenReturn(monster);
        var ref = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), actor, client, world, channel);
        var scene = new GameContext.SceneRef(ref, map, null, null);
        var scope = new ExecutionScope(ref.epoch(), new ExecutionScope.Limits(2, 1, 2, 1));
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        try (var database = mockStatic(DatabaseConnection.class);
             var packets = mockStatic(PacketCreator.class)) {
            // The native singleton loads global drops once; keep that JDBC collaborator in memory.
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            MonsterInformationProvider.getInstance();
            var work = scope.acceptWork();
            var result = NativeMonsterMovementActions.move(server, scene, work, monster,
                    (short) 1, new Point(5, 0), 50, 2, 1, 100);

            assertTrue(result.dispatched());
            assertFalse(result.handlerError());
            assertTrue(result.positionConfirmed());
            assertEquals(new Point(5, 0), monster.getPosition());
            verify(map).moveMonster(monster, new Point(5, 0));
            packets.verify(() -> PacketCreator.moveMonster(eq(501), anyBoolean(), anyInt(), anyInt(), anyInt(),
                    anyInt(), eq(new Point(0, 0)), any(), anyLong()));

            doReturn(null).when(monster).getController();
            assertThrows(NativeActionService.Invalid.class, () -> NativeMonsterMovementActions.move(server, scene,
                    scope.acceptWork(), monster, (short) 2, new Point(10, 0), 50, 2, 1, 100));
            assertEquals(new Point(5, 0), monster.getPosition());
        } finally {
            ThreadLocalUtil.removeCurrentClient();
            ExecutionScope.tryBeginWorldStop();
        }
    }
}
