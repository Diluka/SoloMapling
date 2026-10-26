package soloMapling.beidou;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotRegistryCurrentStorageTest {
    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void closedChannelWithNullPlayerStorageIsInactive() {
        Character character = mock(Character.class);
        BotClient client = mock(BotClient.class);
        Channel channel = mock(Channel.class);
        when(character.getId()).thenReturn(785);
        when(character.getClient()).thenReturn(client);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(null);
        BotRegistry.register(character);
        try {
            assertFalse(assertDoesNotThrow(() -> BotRegistry.isCurrent(character)));
        } finally {
            BotRegistry.unregister(character);
        }
    }

    @Test
    void nativeClientLookupReturnsNullWhenTheWorldHasBeenCleared() {
        Server server = mock(Server.class);
        when(server.getWorld(0)).thenReturn(null);
        when(server.getChannel(0, 1)).thenCallRealMethod();
        Character character = mock(Character.class);
        BotClient client = new BotClient(0, 1);
        when(character.getId()).thenReturn(786);
        when(character.getClient()).thenReturn(client);
        BotRegistry.register(character);
        try (MockedStatic<Server> singleton = mockStatic(Server.class)) {
            singleton.when(Server::getInstance).thenReturn(server);
            assertNull(client.getChannelServer());
            assertFalse(assertDoesNotThrow(() -> BotRegistry.isCurrent(character)));
        } finally {
            BotRegistry.unregister(character);
        }
    }

    @Test
    void matchingIdInAReplacementChannelDoesNotMakeAnOldObjectCurrent() {
        Character character = mock(Character.class);
        BotClient client = mock(BotClient.class);
        Channel channel = mock(Channel.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(character.getId()).thenReturn(783);
        when(character.getClient()).thenReturn(client);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(783)).thenReturn(character);
        BotRegistry.register(character);
        try {
            assertFalse(character.isLoggedIn());
            assertTrue(BotRegistry.isCurrent(character));
            when(storage.getCharacterById(783)).thenReturn(mock(Character.class));
            assertFalse(BotRegistry.isCurrent(character));
            when(storage.getCharacterById(783)).thenReturn(character);
            BotRegistry.unregister(character);
            assertFalse(BotRegistry.isCurrent(character));
        } finally {
            BotRegistry.unregister(character);
        }
    }
}
