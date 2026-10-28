package soloMapling.game.context;

import soloMapling.game.execution.ExecutionScope;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.coordinator.world.MonsterAggroCoordinator;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.game.context.GameContext.Failure.*;

/** Real native character, client, map and storage; only host routing is an in-memory seam. */
class NativeContextChecksTest {
    private Server server;
    private World world;
    private Channel channel;
    private BotClient client;
    private Character character;
    private MapleMap map;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private GameContext.CharacterRef ref;
    private MockedStatic<Server> singleton;

    @BeforeEach
    void structurallyRegisteredNativeBotWithoutFalsifyingItsLoginFlag() throws Exception {
        CharacterTestContext.install();
        singleton = mockStatic(Server.class);
        singleton.when(Server::getInstance).thenThrow(new AssertionError("global host lookup is forbidden"));
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        when(world.getChannel(1)).thenReturn(channel);
        worldPlayers = spy(new PlayerStorage());
        channelPlayers = spy(new PlayerStorage());
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        client = spy(new BotClient(0, 1));
        character = spy(Character.getDefault(client));
        character.setId(73);
        character.setName("SharedContext");
        client.setPlayer(character);
        try (MockedConstruction<MonsterAggroCoordinator> ignored = mockConstruction(MonsterAggroCoordinator.class)) {
            map = spy(new MapleMap(10000, 0, 1, 10000, 1f));
        }
        members().add(character);
        character.setMap(map);
        worldPlayers.addPlayer(character);
        channelPlayers.addPlayer(character);
        ref = NativeContextChecks.captureCharacter(server, character, client, world, channel);
    }

    @AfterEach
    void checksNeverDiscoverOrModifyTheHost() {
        try {
            singleton.verify(Server::getInstance, never());
            verify(server, never()).getWorlds();
            verify(world, never()).getChannels();
            verify(channel, never()).getMapFactory();
            verify(worldPlayers, never()).getAllCharacters();
            verify(channelPlayers, never()).getAllCharacters();
            verify(client, never()).getLoginState();
            verify(client, never()).updateLoginState(anyInt());
            verify(client, never()).sendPacket(any(Packet.class));
            verify(map, never()).addPlayer(any());
            verify(map, never()).removePlayer(any());
        } finally {
            ExecutionScope.tryBeginWorldStop();
            singleton.close();
        }
    }

    @Test
    void realFalseCharacterLoginFlagIsStructurallyValidAndNeverReadByTheCommonChecks() {
        assertFalse(character.isLoggedIn());
        assertFalse(character.isLoggedInWorld());
        assertTrue(client.isLoggedIn());
        clearInvocations(character, client);
        NativeContextChecks.requireCharacterCurrent(server, ref);
        NativeContextChecks.requireSceneCurrent(server, NativeContextChecks.captureScene(server, ref));
        verify(character, never()).isLoggedIn();
        verify(character, never()).isLoggedInWorld();
        verify(character, never()).isAwayFromWorld();
        verify(client, never()).isLoggedIn();
        assertSame(character, ref.character());
        assertSame(client, ref.client());
    }

    @Test
    void sameNumericWorldOrChannelIdCannotReplaceTheCapturedNativeObjects() {
        World replacementWorld = mock(World.class);
        when(replacementWorld.getId()).thenReturn(0);
        when(server.getWorld(0)).thenReturn(replacementWorld);
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        when(server.getWorld(0)).thenReturn(world);
        Channel replacementChannel = mock(Channel.class);
        when(replacementChannel.getId()).thenReturn(1);
        when(world.getChannel(1)).thenReturn(replacementChannel);
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void clientAndCharacterMustRemainTheExactBidirectionalPair() {
        Character replacement = mock(Character.class);
        when(replacement.getId()).thenReturn(73);
        client.setPlayer(replacement);
        assertReason(CHARACTER_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        client.setPlayer(character);
        character.setClient(new BotClient(0, 1));
        assertReason(CHARACTER_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void matchingNativeRouteIdsAreRequiredWithoutCallingClientRoutingGetters() {
        client.setWorld(1);
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        client.setWorld(0);
        client.setChannel(2);
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        verify(client, never()).getWorldServer();
        verify(client, never()).getChannelServer();
    }

    @Test
    void characterWorldCannotRouteAwayFromTheWorldWhichStillHoldsItsStorageEntry() {
        character.setWorld(1);
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void sameCidReplacementsInEitherNativeStorageInvalidateTheOldReference() {
        Character replacement = mock(Character.class);
        when(replacement.getId()).thenReturn(73);
        when(replacement.getName()).thenReturn("Replacement");
        worldPlayers.addPlayer(replacement);
        assertReason(CHARACTER_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        worldPlayers.addPlayer(character);
        channelPlayers.addPlayer(replacement);
        assertReason(CHARACTER_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void nullOrMissingNativeStorageDoesNotBecomeAnUncaughtNullPointer() {
        when(world.getPlayerStorage()).thenReturn(null);
        assertReason(NOT_IN_WORLD, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(null);
        assertReason(NOT_IN_WORLD, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        worldPlayers.removePlayer(73);
        assertReason(CHARACTER_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void aGhostOrMissingMapIsNotRecoveredFromItsIdOrFromAWorldDirectory() throws Exception {
        members().clear();
        assertReason(NOT_IN_WORLD, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        members().add(character);
        character.setMap((MapleMap) null);
        assertReason(NOT_IN_WORLD, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void anOfflineWorldAndAnOldEpochRemainDistinctEvenIfAllObjectsAreReused() {
        ExecutionScope.tryBeginWorldStop();
        assertReason(WORLD_OFFLINE, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        WorldEpoch.onWorldStarted();
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        GameContext.CharacterRef fresh = NativeContextChecks.captureCharacter(server, character, client, world, channel);
        assertNotSame(ref.epoch(), fresh.epoch());
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, fresh));
        when(server.isOnline()).thenReturn(false);
        assertReason(WORLD_OFFLINE, () -> NativeContextChecks.requireCharacterCurrent(server, fresh));
    }

    @Test
    void anEpochChangeDuringNativeReadsIsReportedAsRuntimeChanged() {
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true)) ExecutionScope.tryBeginWorldStop();
            return call.callRealMethod();
        }).when(map).getCharacterById(73);
        assertReason(RUNTIME_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void aKnownEpochChangeStillWinsWhenTheNativeGetterThenThrows() {
        when(world.getChannel(1)).thenAnswer(call -> {
            ExecutionScope.tryBeginWorldStop();
            throw new IllegalStateException("private-native-context-detail");
        });
        assertReason(RUNTIME_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void aWorldReplacementDuringTheMapReadIsCaughtByTheFinalStructureRecheck() {
        World replacement = mock(World.class);
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true)) when(server.getWorld(0)).thenReturn(replacement);
            return call.callRealMethod();
        }).when(map).getCharacterById(73);
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void nativeReadExceptionsAreUnavailableAndNeverLeakedOrMisclassifiedAsLost() {
        when(world.getChannel(1)).thenThrow(new IllegalStateException("private-native-context-detail"));
        GameContext.Invalid error = assertThrows(GameContext.Invalid.class,
                () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        assertEquals(READ_UNAVAILABLE, error.reason());
        assertEquals("READ_UNAVAILABLE", error.getMessage());
        assertNull(error.getCause());
        doReturn(channel).when(world).getChannel(1);
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @Test
    void absentRequiredReferencesHaveFixedFailuresRatherThanNativeNullPointers() {
        assertReason(WORLD_OFFLINE, () -> NativeContextChecks.requireCharacterCurrent(null, ref));
        assertReason(CHARACTER_CHANGED, () -> NativeContextChecks.requireCharacterCurrent(server, null));
        assertReason(SCENE_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, null));
        assertReason(CHARACTER_CHANGED, () -> NativeContextChecks.captureCharacter(server, null, client, world, channel));
        assertReason(WORLD_CHANGED, () -> NativeContextChecks.captureCharacter(server, character, client, null, channel));
    }

    private void assertReason(GameContext.Failure reason, Runnable operation) {
        GameContext.Invalid error = assertThrows(GameContext.Invalid.class, operation::run);
        assertEquals(reason, error.reason());
        assertEquals(reason.name(), error.getMessage());
        assertNull(error.getCause());
    }

    @SuppressWarnings("unchecked")
    private Collection<Character> members() throws Exception {
        Field field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(map);
    }
}
