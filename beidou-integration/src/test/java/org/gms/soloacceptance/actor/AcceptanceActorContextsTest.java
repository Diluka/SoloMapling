package org.gms.soloacceptance.actor;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;

import java.lang.reflect.Field;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real native players, maps and storage with in-memory ownership; no datasource or world discovery. */
class AcceptanceActorContextsTest {
    private Server server;
    private World world;
    private Channel channel;
    private AcceptanceClient client;
    private Character character;
    private MapleMap map;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private ActorAccountOwnership.Lease lease;
    private NativeActorHandle handle;
    private MockedStatic<ActorAccountOwnership> ownership;
    private NativeActorExecutionFixture execution;

    @BeforeEach
    void nativePlayerRegistration() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        when(world.getChannel(1)).thenReturn(channel);
        worldPlayers = new PlayerStorage();
        channelPlayers = new PlayerStorage();
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        client = new AcceptanceClient(Client.Type.CHANNEL, -117L, "127.0.0.117", world, channel);
        client.setAccID(117);
        client.setLanguage(3);
        client.setAccountName("ContextPlayer");
        setBoolean(Client.class, client, "loggedIn", true);
        character = spy(Character.getDefault(client));
        character.setId(117);
        character.setName("ContextPlayer");
        character.setAccountId(117);
        character.setWorld(0);
        setBoolean(Character.class, character, "loggedIn", true);
        Field away = Character.class.getDeclaredField("awayFromWorld");
        away.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) away.get(character)).set(false);
        client.setPlayer(character);
        worldPlayers.addPlayer(character);
        channelPlayers.addPlayer(character);
        map = new MapleMap(10000, 0, 1, 10000, 1f);
        moveTo(map);
        lease = mock(ActorAccountOwnership.Lease.class);
        ownership = mockStatic(ActorAccountOwnership.class);
        ownership.when(() -> ActorAccountOwnership.owns(lease, client)).thenReturn(true);
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(117)).thenReturn(client);
        handle = new NativeActorHandle("primary", 117, client, character, world, channel, lease, false, false);
        execution = NativeActorExecutionFixture.owned(handle);
    }

    @AfterEach
    void releaseFixtureOnly() {
        try {
            verify(server, never()).getWorlds();
            verify(world, never()).getChannels();
            verify(channel, never()).getMapFactory();
            assertFalse(BotRegistry.isRegistered(character));
        } finally {
            ownership.close();
            client.closeSession();
            WorldEpoch.onWorldStopping();
        }
    }

    @Test
    void anOwnedOrdinaryNativePlayerUsesOneCapturedEpochAndTheSharedContext() {
        GameContext.CharacterRef captured = handle.characterRef();
        assertSame(WorldEpoch.snapshot().token(), captured.epoch());
        assertSame(character, captured.character());
        assertSame(client, captured.client());
        assertSame(captured, AcceptanceActorContexts.requireCharacter(server, handle));
        GameContext.SceneRef scene = AcceptanceActorContexts.captureScene(server, handle);
        assertSame(captured, scene.actor());
        assertSame(map, scene.map());
        assertDoesNotThrow(() -> AcceptanceActorContexts.requireSceneCurrent(server, scene));
        assertFalse(BotRegistry.isRegistered(character));
    }

    @Test
    void aNormalMapChangeKeepsTheOwnedCharacterButInvalidatesItsOldScene() throws Exception {
        GameContext.SceneRef old = AcceptanceActorContexts.captureScene(server, handle);
        MapleMap destination = new MapleMap(map.getId(), 0, 1, map.getId(), 1f);
        moveTo(destination);
        assertSame(handle.characterRef(), AcceptanceActorContexts.requireCharacter(server, handle));
        assertEquals("actor_context_mismatch", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireSceneCurrent(server, old)).reason());
        assertSame(destination, AcceptanceActorContexts.captureScene(server, handle).map());
    }

    @Test
    void stopStartWithTheSameNativeObjectsCannotRenewTheCapturedHandleEpoch() {
        WorldEpoch.Token epoch = handle.characterRef().epoch();
        WorldEpoch.onWorldStopping();
        assertEquals("actor_world_offline", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, handle)).reason());
        WorldEpoch.onWorldStarted();
        assertNotSame(epoch, WorldEpoch.snapshot().token());
        assertSame(epoch, handle.characterRef().epoch());
        ObservationException stale = assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, handle));
        assertEquals(HttpStatus.GONE, stale.status());
        assertEquals("stale_actor_session", stale.reason());
    }

    @Test
    void neutralContextDoesNotGrantMcpPermissionWhenNativePlayerLoginFlagsAreFalse() throws Exception {
        setBoolean(Character.class, character, "loggedIn", false);
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, handle.characterRef()));
        assertEquals("actor_session_stale", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, handle)).reason());
        setBoolean(Character.class, character, "loggedIn", true);
        setBoolean(Client.class, client, "loggedIn", false);
        assertEquals("actor_session_stale", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, handle)).reason());
    }

    @Test
    void foreignOwnershipAccountAndGmIdentityCannotBorrowTheSharedCharacterRef() {
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(117)).thenReturn(mock(Client.class));
        assertStale();
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(117)).thenReturn(client);
        ownership.when(() -> ActorAccountOwnership.owns(lease, client)).thenReturn(false);
        assertStale();
        ownership.when(() -> ActorAccountOwnership.owns(lease, client)).thenReturn(true);
        character.setAccountId(118);
        assertStale();
        character.setAccountId(117);
        character.setGMLevel(1);
        assertStale();
    }

    @Test
    void aBotClientOrClosedTransportCannotBeTreatedAsAnAcceptancePlayer() {
        BotClient bot = new BotClient(0, 1);
        character.setClient(bot);
        assertStale();
        character.setClient(client);
        client.closeSession();
        assertStale();
    }

    @Test
    void sameIdReplacementMissingMapAndMissingStorageAreDefiniteIdentityFailures() throws Exception {
        Character replacement = Character.getDefault(new BotClient(0, 1));
        replacement.setId(character.getId());
        replacement.setName("Replacement");
        worldPlayers.addPlayer(replacement);
        assertStale();
        worldPlayers.addPlayer(character);
        mapMembers(map).remove(character);
        mapMembers(map).add(replacement);
        assertStale();
        mapMembers(map).remove(replacement);
        mapMembers(map).add(character);
        character.setMap((MapleMap) null);
        assertStale();
        character.setMap(map);
        when(channel.getPlayerStorage()).thenReturn(null);
        assertStale();
    }

    @Test
    void disposedMapEventsInvalidateOnlyScenesAndDoNotBlockOwnedNativeLogout() {
        EventInstanceManager disposed = mock(EventInstanceManager.class);
        when(disposed.isEventDisposed()).thenReturn(true);
        map.setEventInstance(disposed);
        assertDoesNotThrow(() -> AcceptanceActorContexts.requireCharacter(server, handle));
        ObservationException scene = assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.captureScene(server, handle));
        assertEquals("actor_context_mismatch", scene.reason());
        assertDoesNotThrow(() -> requireClose());
        execution.stopWorld();
        assertDoesNotThrow(() -> requireClose());
        verify(disposed, never()).dispose();
    }

    @Test
    void nativeReadFailureIsUnavailableAndNeverMasqueradesAsADeadActor() {
        doThrow(new IllegalStateException("private-native-detail")).when(character).getMap();
        ObservationException unavailable = assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, handle));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.status());
        assertEquals("actor_observation_unavailable", unavailable.reason());
        assertFalse(String.valueOf(unavailable.getMessage()).contains("private-native-detail"));
        assertSame(character, worldPlayers.getCharacterById(117));
        assertTrue(client.isLoggedIn());
    }

    @Test
    void epochChangeDuringNativeReadHasAnExplicitRuntimeChangedBoundary() {
        doAnswer(invocation -> {
            WorldEpoch.onWorldStopping();
            return map;
        }).when(character).getMap();
        ObservationException changed = assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, handle));
        assertEquals(HttpStatus.CONFLICT, changed.status());
        assertEquals("runtime_changed", changed.reason());
    }

    @Test
    void drainingDoesNotAuthorizeCleanupOfAReplacedWorldOrClient() {
        execution.stopWorld();
        when(server.getWorld(0)).thenReturn(mock(World.class));
        assertEquals("actor_close_cleanup_unverified_stale_session", assertThrows(ObservationException.class,
                () -> requireClose()).reason());
        when(server.getWorld(0)).thenReturn(world);
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(117)).thenReturn(mock(Client.class));
        assertEquals("actor_close_cleanup_unverified_stale_session", assertThrows(ObservationException.class,
                () -> requireClose()).reason());
    }

    @Test
    void cleanupRequiresTheExecutingWorkAndItsExactRegisteredHandle() {
        assertEquals("actor_close_cleanup_unverified_scope", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCloseCharacter(server, handle, null)).reason());
        java.util.concurrent.atomic.AtomicReference<soloMapling.game.execution.ScopeResources.DrainPermit> retained =
                new java.util.concurrent.atomic.AtomicReference<>();
        execution.cleanup(permit -> {
            retained.set(permit);
            NativeActorHandle other = new NativeActorHandle("primary", 117, client, character,
                    world, channel, lease, false, false);
            assertEquals("actor_close_cleanup_unverified_scope", assertThrows(ObservationException.class,
                    () -> AcceptanceActorContexts.requireCloseCharacter(server, other, permit)).reason());
            AcceptanceActorContexts.requireCloseCharacter(server, handle, permit);
            return null;
        });
        assertEquals("actor_close_cleanup_unverified_scope", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCloseCharacter(server, handle, retained.get())).reason());
    }

    @Test
    void anUncontrolledInactiveEpochDoesNotGrantOwnedCleanup() {
        WorldEpoch.onWorldStopping();
        assertThrows(RuntimeException.class, this::requireClose);
    }

    @Test
    void aNewWorldEpochRejectsOldCleanupEvenWhenNativeObjectsAreReused() {
        execution.stopWorld();
        WorldEpoch.onWorldStarted();
        assertThrows(RuntimeException.class, this::requireClose);
        assertSame(character, worldPlayers.getCharacterById(117));
        assertTrue(client.isLoggedIn());
    }

    @Test
    void aLateOpenResultUsesItsAcceptedWorkOriginRatherThanItsStoppedHandleEpoch() {
        WorldEpoch.Token acceptedEpoch = WorldEpoch.snapshot().token();
        NativeActorExecutionFixture late = new NativeActorExecutionFixture();
        NativeActorHandle lateHandle = late.open(() -> {
            late.bindOrigin(world, channel);
            late.stopWorld();
            NativeActorHandle result = new NativeActorHandle("primary", 117, client, character,
                    world, channel, lease, false, false);
            late.adopt(result);
            return result;
        });
        assertNotSame(acceptedEpoch, lateHandle.characterRef().epoch());
        late.cleanup(permit -> {
            AcceptanceActorContexts.requireCloseCharacter(server, lateHandle, permit);
            return null;
        });
        assertEquals("actor_world_offline", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, lateHandle)).reason());
    }

    private void requireClose() {
        execution.cleanup(permit -> {
            AcceptanceActorContexts.requireCloseCharacter(server, handle, permit);
            return null;
        });
    }

    private void assertStale() {
        assertEquals("actor_session_stale", assertThrows(ObservationException.class,
                () -> AcceptanceActorContexts.requireCharacter(server, handle)).reason());
    }

    private void moveTo(MapleMap destination) throws Exception {
        if (character.getMap() != null) mapMembers(character.getMap()).remove(character);
        mapMembers(destination).add(character);
        character.setMap(destination);
        character.setMapId(destination.getId());
    }

    @SuppressWarnings("unchecked")
    private static Collection<Character> mapMembers(MapleMap target) throws Exception {
        Field field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(target);
    }

    private static void setBoolean(Class<?> owner, Object target, String name, boolean value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
