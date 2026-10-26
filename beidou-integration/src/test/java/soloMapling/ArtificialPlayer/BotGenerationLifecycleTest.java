package soloMapling.ArtificialPlayer;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.Point;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BotGenerationLifecycleTest {
    @BeforeAll
    static void installNativeContext() {
        CharacterTestContext.install();
    }

    @Test
    void crossMapMoveRemovesTheOldMembershipBeforePublishingTheNewOne() {
        Character bot = mock(Character.class);
        MapleMap oldMap = mock(MapleMap.class);
        MapleMap newMap = mock(MapleMap.class);
        Set<Character> oldPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Character> newPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
        oldPlayers.add(bot);
        AtomicReference<MapleMap> current = new AtomicReference<>(oldMap);
        when(bot.getMap()).thenAnswer(ignored -> current.get());
        doAnswer(invocation -> {
            assertSame(oldMap, current.get());
            oldPlayers.remove(bot);
            return null;
        }).when(oldMap).removePlayer(bot);
        doAnswer(invocation -> {
            assertFalse(oldPlayers.contains(bot));
            current.set(invocation.getArgument(0));
            return null;
        }).when(bot).setMap(any(MapleMap.class));
        doAnswer(invocation -> {
            assertSame(newMap, current.get());
            assertFalse(oldPlayers.contains(bot));
            assertTrue(newPlayers.add(bot));
            return null;
        }).when(newMap).addPlayer(bot);

        BotGeneration.placeBotOnMap(bot, new Point(30, 40), newMap);

        assertTrue(oldPlayers.isEmpty());
        assertEquals(Set.of(bot), newPlayers);
        var order = inOrder(oldMap, bot, newMap);
        order.verify(oldMap).removePlayer(bot);
        order.verify(bot).setMap(newMap);
        order.verify(bot).setPosition(new Point(30, 40));
        order.verify(bot).setStance(5);
        order.verify(newMap).addPlayer(bot);
    }

    @Test
    void sameMapPlacementRemovesBeforeReaddingExactlyOnce() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);

        BotGeneration.placeBotOnMap(bot, new Point(1, 2), map);

        var order = inOrder(map, bot);
        order.verify(map).removePlayer(bot);
        order.verify(bot).setMap(map);
        order.verify(map).addPlayer(bot);
        verify(map, times(1)).removePlayer(bot);
        verify(map, times(1)).addPlayer(bot);
    }

    @Test
    void firstPlacementNeedsNoOldMap() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotGeneration.placeBotOnMap(bot, new Point(), map);
        verify(map, never()).removePlayer(any());
        verify(map).addPlayer(bot);
    }

    @Test
    void sameJvmRestartUsesTheReplacementChannelAndWorldForRegistrationAndLookup() {
        Server server = mock(Server.class);
        Channel firstChannel = mock(Channel.class, RETURNS_DEEP_STUBS);
        Channel nextChannel = mock(Channel.class, RETURNS_DEEP_STUBS);
        World firstWorld = mock(World.class, RETURNS_DEEP_STUBS);
        World nextWorld = mock(World.class, RETURNS_DEEP_STUBS);
        AtomicReference<Channel> channel = new AtomicReference<>(firstChannel);
        AtomicReference<World> world = new AtomicReference<>(firstWorld);
        when(server.getChannel(0, 1)).thenAnswer(ignored -> channel.get());
        when(server.getWorld(0)).thenAnswer(ignored -> world.get());
        Character first = bot(1_700_000_001);
        Character next = bot(1_700_000_002);

        try (MockedStatic<Server> singleton = mockStatic(Server.class)) {
            singleton.when(Server::getInstance).thenReturn(server);
            BotGeneration.addBotToServer(first);
            verify(firstChannel).addPlayer(first);
            verify(firstWorld.getPlayerStorage()).addPlayer(first);

            // Simulate doShutdownInternal(true) disposing the old stores and creating new ones.
            when(firstWorld.getPlayerStorage()).thenThrow(new IllegalStateException("old world closed"));
            doThrow(new IllegalStateException("old channel closed")).when(firstChannel).addPlayer(any());
            channel.set(nextChannel);
            world.set(nextWorld);
            when(nextChannel.getPlayerStorage().getCharacterById(next.getId())).thenReturn(next);
            when(nextChannel.getPlayerStorage().getCharacterByName("next")).thenReturn(next);

            assertDoesNotThrow(() -> BotGeneration.addBotToServer(next));
            verify(nextChannel).addPlayer(next);
            verify(nextWorld.getPlayerStorage()).addPlayer(next);
            assertSame(nextChannel, SoloMaplingUtilities.channel());
            assertSame(nextWorld, SoloMaplingUtilities.world());
            assertSame(next, SoloMaplingUtilities.getChr(next.getId()));
            assertSame(next, SoloMaplingUtilities.getChr("next"));
            verify(firstChannel, never()).addPlayer(next);
        } finally {
            BotRegistry.unregister(first);
            BotRegistry.unregister(next);
        }
    }

    private static Character bot(int id) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        return bot;
    }
}
