package soloMapling.beidou;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WovenHeadlessMembershipTest {
    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void lateNativeMapEntryCannotRegisterOrPublishADetachedHeadlessObject() {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(784);
        when(character.getClient()).thenReturn(mock(BotClient.class));
        // Execute the real woven entry. Its guard must reject before touching the
        // uninitialized map internals or mutating the detached character.
        MapleMap map = mock(MapleMap.class, CALLS_REAL_METHODS);
        assertFalse(BotRegistry.isRegistered(character));
        assertThrows(IllegalStateException.class, () -> map.addPlayer(character));
        assertFalse(BotRegistry.isRegistered(character));
        verify(character, never()).setEnteredChannelWorld();
        verify(character, never()).setMapId(anyInt());
        verify(character, never()).setLoggedIn(anyBoolean());
    }
}
