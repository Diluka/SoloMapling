package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.server.MethodScheduler;

import java.awt.Point;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes the woven delayed overload and its native drop body; only the timer and host routing are controlled. */
class DelayedMapDropsTest {
    private MapleMap map;
    private Character owner;
    private Reactor dropper;
    private final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
    private MockedStatic<MethodScheduler> scheduler;
    private MockedStatic<GameConfig> configuration;
    private MockedStatic<Server> servers;

    @BeforeEach void nativeMapWithoutServer() {
        CharacterTestContext.install();
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(41);
        when(owner.getPartyId()).thenReturn(-1);
        var client = mock(Client.class);
        when(client.isLoggedIn()).thenReturn(true);
        when(client.getPlayer()).thenReturn(owner);
        when(owner.getClient()).thenReturn(client);
        dropper = mock(Reactor.class);
        when(dropper.getPosition()).thenReturn(new Point(30, 10));
        configuration = mockStatic(GameConfig.class);
        configuration.when(() -> GameConfig.getServerInt("item_limit_on_map")).thenReturn(1000);
        configuration.when(() -> GameConfig.getServerLong("item_expire_time")).thenReturn(60_000L);
        var server = mock(Server.class);
        when(server.getCurrentTime()).thenReturn(5000L);
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(server);
        map = spy(new MapleMap(10000, 0, 1, 10000, 1));
        doReturn(new Point(30, 10)).when(map).calcDropPos(any(Point.class), any(Point.class));
        scheduler = mockStatic(MethodScheduler.class);
        scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), eq(250L)))
                .thenAnswer(invocation -> { scheduled.add(invocation.getArgument(0, Runnable.class)); return null; });
    }

    @AfterEach void releaseHostSeams() {
        scheduler.close();
        servers.close();
        configuration.close();
    }

    @Test void wovenDelayedMesoDropPreservesNativeOwnershipAndDropType() {
        map.spawnMesoDrop(777, new Point(30, 10), dropper, owner, true, (byte) 2, (short) 250);
        assertEquals(1, scheduled.size());
        assertTrue(map.getMapObjects().isEmpty());

        scheduled.remove().run();

        assertEquals(1, map.getMapObjects().size());
        var drop = assertInstanceOf(MapItem.class, map.getMapObjects().get(0));
        assertEquals(777, drop.getMeso());
        assertEquals(41, drop.getOwnerId());
        assertSame(owner.getClient(), drop.getOwnerClient());
        assertSame(dropper, drop.getDropper());
        assertEquals(2, drop.getDropType());
        assertTrue(drop.isPlayerDrop());
        assertEquals(new Point(30, 10), drop.getPosition());
        assertEquals(1, map.getDroppedItemCount());
    }

    @Test void wovenDelayedReactorDropPreservesNativeItemAndQuest() {
        var item = new Item(4000000, (short) 0, (short) 1);
        map.dropFromReactor(owner, dropper, item, new Point(30, 10), (short) 123, (short) 250);
        assertEquals(1, scheduled.size());
        assertTrue(map.getMapObjects().isEmpty());

        scheduled.remove().run();

        assertEquals(1, map.getMapObjects().size());
        var drop = assertInstanceOf(MapItem.class, map.getMapObjects().get(0));
        assertSame(item, drop.getItem());
        assertEquals(123, drop.getQuest());
        assertEquals(41, drop.getOwnerId());
        assertSame(dropper, drop.getDropper());
        assertEquals(0, drop.getDropType());
        assertFalse(drop.isPlayerDrop());
        assertEquals(1, map.getDroppedItemCount());
    }
}
