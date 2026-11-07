package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.Packet;
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
import org.mockito.ArgumentCaptor;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
             var packets = mockStatic(PacketCreator.class, CALLS_REAL_METHODS)) {
            // The native singleton loads global drops once; keep that JDBC collaborator in memory.
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            MonsterInformationProvider.getInstance();
            var work = scope.acceptWork();
            when(actor.isChangingMaps()).thenReturn(true);
            var pending = NativeMonsterMovementActions.move(server, scene, work, monster,
                    (short) 1, new Point(14, -77), 50, -15, new Point(50, -555), 7, 0, 270);
            assertFalse(pending.mapReady());
            assertFalse(pending.dispatched());
            assertFalse(pending.handlerError());
            assertEquals(new Point(0, 0), monster.getPosition());
            verify(map, never()).moveMonster(any(), any());
            packets.verifyNoInteractions();

            // The scene owner completes entry; the movement edge only reads the native readiness fact.
            when(actor.isChangingMaps()).thenReturn(false);
            var result = NativeMonsterMovementActions.move(server, scene, work, monster,
                    (short) 1, new Point(14, -77), 50, -15, new Point(50, -555), 7, 0, 270);

            assertTrue(result.mapReady());
            assertTrue(result.dispatched());
            assertFalse(result.handlerError());
            assertTrue(result.positionConfirmed());
            assertEquals(new Point(14, -77), monster.getPosition());
            assertEquals(7, monster.getStance());
            verify(map).moveMonster(monster, new Point(14, -77));
            packets.verify(() -> PacketCreator.moveMonster(eq(501), anyBoolean(), anyInt(), anyInt(), anyInt(),
                    anyInt(), eq(new Point(0, 0)), any(), anyLong()));
            var output = ArgumentCaptor.forClass(Packet.class);
            verify(map).broadcastMessage(eq(actor), output.capture(), eq(new Point(0, 0)));
            var movement = ByteBuffer.wrap(output.getValue().getBytes()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(SendOpcode.MOVE_MONSTER.getValue(), Short.toUnsignedInt(movement.getShort()));
            assertEquals(501, movement.getInt());
            movement.position(movement.position() + 7); // movement marker and native activity/skill header
            assertEquals(0, movement.getShort());
            assertEquals(0, movement.getShort()); // handler removes the start-position wire offset
            assertEquals(2, movement.get());
            assertEquals(1, movement.get());
            assertEquals(50, movement.getShort());
            assertEquals(-555, movement.getShort());
            assertEquals(7, movement.get());
            assertEquals(0, movement.getShort());
            assertEquals(0, movement.get());
            assertEquals(14, movement.getShort());
            assertEquals(-75, movement.getShort()); // absolute fragment retains destination.y + 2
            assertEquals(50, movement.getShort());
            assertEquals(-15, movement.getShort());
            assertEquals(0, movement.getShort());
            assertEquals(7, movement.get());
            assertEquals(270, movement.getShort());

            // A transition between initial admission and final dispatch remains a known, unstarted frame.
            when(actor.isChangingMaps()).thenReturn(false, true);
            var notDispatched = NativeMonsterMovementActions.move(server, scene, work, monster,
                    (short) 2, new Point(28, -9), 50, 525, null, 7, 0, 270);
            assertFalse(notDispatched.mapReady());
            assertFalse(notDispatched.dispatched());
            assertFalse(notDispatched.handlerError());
            assertEquals(new Point(14, -77), monster.getPosition());

            when(actor.isChangingMaps()).thenReturn(false);
            doThrow(new IllegalStateException("native_handler_failure")).when(map).getMapObject(501);
            var failedHandler = NativeMonsterMovementActions.move(server, scene, work, monster,
                    (short) 3, new Point(28, -9), 50, 525, null, 7, 0, 270);
            assertTrue(failedHandler.mapReady());
            assertTrue(failedHandler.dispatched());
            assertTrue(failedHandler.handlerError());
            assertFalse(failedHandler.positionConfirmed());
            doReturn(monster).when(map).getMapObject(501);

            doReturn(null).when(monster).getController();
            assertThrows(NativeActionService.Invalid.class, () -> NativeMonsterMovementActions.move(server, scene,
                    scope.acceptWork(), monster, (short) 2, new Point(28, -9), 50, 525, null, 7, 0, 270));
            assertEquals(new Point(14, -77), monster.getPosition());
        } finally {
            ThreadLocalUtil.removeCurrentClient();
            ExecutionScope.tryBeginWorldStop();
        }
    }
}
