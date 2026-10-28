package soloMapling.game.context;

import soloMapling.game.execution.ExecutionScope;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.coordinator.world.MonsterAggroCoordinator;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.scripting.event.EventManager;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import soloMapling.beidou.CharacterTestContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.game.context.GameContext.Failure.*;

/** Native objects with in-memory registration; no login flags, scripts, map loading or datasource. */
class NativeContextSceneTest {
    private Server server;
    private World world;
    private Channel channel;
    private BotClient client;
    private Character character;
    private MapleMap map;
    private GameContext.CharacterRef actor;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private final List<EventInstanceManager> events = new ArrayList<>();
    private final AtomicInteger disposedReads = new AtomicInteger();
    private MockedStatic<Server> singleton;

    @BeforeEach
    void nativeSceneWithFalseCharacterLoginFlag() throws Exception {
        CharacterTestContext.install();
        singleton = mockStatic(Server.class);
        singleton.when(Server::getInstance).thenThrow(new AssertionError("global server lookup is forbidden"));
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
        client = new BotClient(0, 1);
        character = spy(Character.getDefault(client));
        character.setId(71);
        character.setName("ContextScene");
        client.setPlayer(character);
        worldPlayers.addPlayer(character);
        channelPlayers.addPlayer(character);
        map = nativeMap(10000);
        moveTo(map);
        doAnswer(call -> {
            assertOutsideEventMonitors();
            return call.callRealMethod();
        }).when(character).getEventInstance();
        assertFalse(character.isLoggedIn());
        assertFalse(character.isLoggedInWorld());
        actor = NativeContextChecks.captureCharacter(server, character, client, world, channel);
    }

    @AfterEach
    void observationsNeverLoadTheWorldOrRunNativeEventMutations() {
        try {
            assertFalse(character.isLoggedIn());
            assertFalse(character.isLoggedInWorld());
            verify(server, never()).getWorlds();
            verify(world, never()).getChannels();
            verify(channel, never()).getMapFactory();
            verify(worldPlayers, never()).getAllCharacters();
            verify(channelPlayers, never()).getAllCharacters();
            for (EventInstanceManager event : events) {
                verify(event, never()).registerPlayer(any());
                verify(event, never()).registerPlayer(any(), anyBoolean());
                verify(event, never()).unregisterPlayer(any());
                verify(event, never()).dispose();
                verify(event, never()).dispose(anyBoolean());
                assertFalse(Thread.holdsLock(event));
            }
            singleton.verify(Server::getInstance, never());
        } finally {
            ExecutionScope.tryBeginWorldStop();
            singleton.close();
        }
    }

    @Test
    void capturesOnlyTheCurrentNativeMapAndKeepsCharacterLoginSemanticsUntouched() {
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        assertSame(actor, scene.actor());
        assertSame(map, scene.map());
        assertNull(scene.memberEim());
        assertNull(scene.mapEim());
        assertDoesNotThrow(() -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertTrue(client.isLoggedIn());
        assertFalse(character.isLoggedIn());
        assertFalse(character.isLoggedInWorld());
    }

    @Test
    void normalMapChangeKeepsCharacterRefValidButInvalidatesTheOldScene() throws Exception {
        GameContext.SceneRef old = NativeContextChecks.captureScene(server, actor);
        MapleMap destination = nativeMap(100000000);
        moveTo(destination);
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
        assertReason(SCENE_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, old));
        GameContext.SceneRef current = NativeContextChecks.captureScene(server, actor);
        assertSame(destination, current.map());
        assertDoesNotThrow(() -> NativeContextChecks.requireSceneCurrent(server, current));
    }

    @Test
    void equalMapIdsDoNotAuthorizeAnotherMapObject() throws Exception {
        GameContext.SceneRef old = NativeContextChecks.captureScene(server, actor);
        MapleMap replacement = nativeMap(map.getId());
        assertNotSame(map, replacement);
        moveTo(replacement);
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
        assertReason(SCENE_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, old));
        assertSame(replacement, NativeContextChecks.captureScene(server, actor).map());
    }

    @Test
    void memberAndMapEventReferencesAreIndependentAndNeverComparedByName() throws Exception {
        EventInstanceManager member = nativeEvent("same-name");
        EventInstanceManager mapEvent = nativeEvent("same-name");
        registerMemberInMemory(member);
        character.setEventInstance(member);
        map.setEventInstance(mapEvent);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        assertSame(member, scene.memberEim());
        assertSame(mapEvent, scene.mapEim());
        assertNotSame(scene.memberEim(), scene.mapEim());
        assertNull(mapEvent.getPlayerById(character.getId()));
        assertDoesNotThrow(() -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertTrue(disposedReads.get() >= 4);
        verify(member, never()).getName();
        verify(mapEvent, never()).getName();
    }

    @Test
    void memberEventMayExistBeforeTheCharacterEntersAnEventMap() throws Exception {
        EventInstanceManager member = nativeEvent("member-only");
        registerMemberInMemory(member);
        character.setEventInstance(member);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        assertSame(member, scene.memberEim());
        assertNull(scene.mapEim());
        assertDoesNotThrow(() -> NativeContextChecks.requireSceneCurrent(server, scene));
    }

    @Test
    void eventMapDoesNotRequireTheCharacterToBeRegisteredAsItsEventMember() throws Exception {
        EventInstanceManager mapEvent = nativeEvent("map-only");
        map.setEventInstance(mapEvent);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        assertNull(scene.memberEim());
        assertSame(mapEvent, scene.mapEim());
        assertDoesNotThrow(() -> NativeContextChecks.requireSceneCurrent(server, scene));
        verify(mapEvent, never()).getPlayerById(anyInt());
    }

    @Test
    void removalFromMemberEventInvalidatesSceneWithoutInvalidatingTheCharacter() throws Exception {
        EventInstanceManager member = nativeEvent("member");
        registerMemberInMemory(member);
        character.setEventInstance(member);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        eventMembers(member).remove(character.getId());
        assertReason(EVENT_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void sameIdMemberReplacementCannotStandInForTheCapturedCharacter() throws Exception {
        EventInstanceManager member = nativeEvent("member");
        registerMemberInMemory(member);
        character.setEventInstance(member);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        Character replacement = Character.getDefault(new BotClient(0, 1));
        replacement.setId(character.getId());
        eventMembers(member).put(character.getId(), replacement);
        assertReason(EVENT_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void changingOnlyTheMemberEventReferenceInvalidatesTheScene() throws Exception {
        EventInstanceManager original = nativeEvent("same-name");
        EventInstanceManager replacement = nativeEvent("same-name");
        registerMemberInMemory(original);
        registerMemberInMemory(replacement);
        character.setEventInstance(original);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        character.setEventInstance(replacement);
        assertReason(EVENT_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void changingOnlyTheMapEventReferenceInvalidatesTheScene() throws Exception {
        EventInstanceManager original = nativeEvent("same-name");
        map.setEventInstance(original);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        map.setEventInstance(nativeEvent("same-name"));
        assertReason(EVENT_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void disposedMemberEventIsReadUnderItsOwnShortMonitorAndDoesNotBlockCharacterChecks() throws Exception {
        EventInstanceManager member = nativeEvent("member");
        registerMemberInMemory(member);
        character.setEventInstance(member);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        setDisposedInMemory(member);
        assertReason(EVENT_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void disposedMapEventIsReadUnderItsOwnShortMonitorAndDoesNotBlockCharacterChecks() throws Exception {
        EventInstanceManager mapEvent = nativeEvent("map");
        map.setEventInstance(mapEvent);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        setDisposedInMemory(mapEvent);
        assertReason(EVENT_CHANGED, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void eventMembershipReadFailureIsUnavailableWithoutLeakingTheNativeMessage() throws Exception {
        EventInstanceManager member = nativeEvent("member");
        registerMemberInMemory(member);
        character.setEventInstance(member);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        int characterId = character.getId();
        doThrow(new IllegalStateException("private-event-reader-detail")).when(member).getPlayerById(characterId);
        assertReason(READ_UNAVAILABLE, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void eventDisposedReadFailureIsUnavailableAndReleasesItsMonitor() throws Exception {
        EventInstanceManager member = nativeEvent("member");
        registerMemberInMemory(member);
        character.setEventInstance(member);
        GameContext.SceneRef scene = NativeContextChecks.captureScene(server, actor);
        doThrow(new IllegalStateException("private-event-reader-detail")).when(member).isEventDisposed();
        assertReason(READ_UNAVAILABLE, () -> NativeContextChecks.requireSceneCurrent(server, scene));
        assertFalse(Thread.holdsLock(member));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void characterEventGetterFailureDoesNotTurnAValidCharacterBindingIntoALostActor() {
        doThrow(new IllegalStateException("private-event-reader-detail")).when(character).getEventInstance();
        assertReason(READ_UNAVAILABLE, () -> NativeContextChecks.captureScene(server, actor));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void mapEventGetterFailureIsUnavailableWithoutLoadingAnotherMap() {
        doThrow(new IllegalStateException("private-event-reader-detail")).when(map).getEventInstance();
        assertReason(READ_UNAVAILABLE, () -> NativeContextChecks.captureScene(server, actor));
        assertDoesNotThrow(() -> NativeContextChecks.requireCharacterCurrent(server, actor));
    }

    @Test
    void aMissingCurrentMapIsNotResolvedOrCreatedFromTheCharactersOldMapId() {
        int previousMapId = character.getMapId();
        character.setMap((MapleMap) null);
        assertEquals(previousMapId, character.getMapId());
        assertReason(NOT_IN_WORLD, () -> NativeContextChecks.captureScene(server, actor));
    }

    private MapleMap nativeMap(int id) {
        MapleMap result;
        // Aggro construction reaches the global singleton; scene membership itself does not use it.
        try (MockedConstruction<MonsterAggroCoordinator> ignored = mockConstruction(MonsterAggroCoordinator.class)) {
            result = spy(new MapleMap(id, 0, 1, id, 1f));
        }
        doAnswer(call -> {
            assertOutsideEventMonitors();
            return call.callRealMethod();
        }).when(result).getCharacterById(anyInt());
        doAnswer(call -> {
            assertOutsideEventMonitors();
            return call.callRealMethod();
        }).when(result).getEventInstance();
        return result;
    }

    private void moveTo(MapleMap destination) throws Exception {
        MapleMap previous = character.getMap();
        if (previous != null) mapMembers(previous).remove(character);
        // Native addPlayer invokes gameplay scripts. This fixture only installs its in-memory membership.
        mapMembers(destination).add(character);
        character.setMap(destination);
        character.setMapId(destination.getId());
    }

    private EventInstanceManager nativeEvent(String name) {
        EventManager manager = mock(EventManager.class);
        when(manager.getWorldServer()).thenReturn(world);
        when(manager.getChannelServer()).thenReturn(channel);
        EventInstanceManager event = spy(new EventInstanceManager(manager, name));
        events.add(event);
        doAnswer(call -> {
            assertTrue(Thread.holdsLock(event), "native disposed flag must be read under its own monitor");
            for (EventInstanceManager other : events) if (other != event) assertFalse(Thread.holdsLock(other));
            disposedReads.incrementAndGet();
            return call.callRealMethod();
        }).when(event).isEventDisposed();
        doAnswer(call -> {
            assertOutsideEventMonitors();
            return call.callRealMethod();
        }).when(event).getPlayerById(anyInt());
        return event;
    }

    private void registerMemberInMemory(EventInstanceManager event) throws Exception {
        // registerPlayer deliberately requires isLoggedInWorld; context checks must not falsify that flag.
        eventMembers(event).put(character.getId(), character);
    }

    private void setDisposedInMemory(EventInstanceManager event) throws Exception {
        Field disposed = EventInstanceManager.class.getDeclaredField("disposed");
        disposed.setAccessible(true);
        synchronized (event) { disposed.setBoolean(event, true); }
    }

    private void assertOutsideEventMonitors() {
        for (EventInstanceManager event : events) assertFalse(Thread.holdsLock(event), "native getter called while holding an EIM monitor");
    }

    private void assertReason(GameContext.Failure reason, Runnable operation) {
        GameContext.Invalid error = assertThrows(GameContext.Invalid.class, operation::run);
        assertEquals(reason, error.reason());
        assertFalse(String.valueOf(error.getMessage()).contains("private-event-reader-detail"));
    }

    @SuppressWarnings("unchecked")
    private Collection<Character> mapMembers(MapleMap target) throws Exception {
        Field field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(target);
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Character> eventMembers(EventInstanceManager event) throws Exception {
        Field field = EventInstanceManager.class.getDeclaredField("chars");
        field.setAccessible(true);
        return (Map<Integer, Character>) field.get(event);
    }
}
