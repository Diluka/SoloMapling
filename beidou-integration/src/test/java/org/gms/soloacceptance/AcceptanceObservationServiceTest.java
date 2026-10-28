package org.gms.soloacceptance;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.soloacceptance.command.DebugCommandService;
import org.springframework.beans.factory.ObjectProvider;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;

import java.awt.Point;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcceptanceObservationServiceTest {
    private Server server;
    private World world;
    private Channel channel;
    private MapManager maps;
    private PlayerStorage players;
    private AcceptanceObservationService observations;

    @BeforeAll
    static void legacyContext() { CharacterTestContext.install(); }

    @BeforeEach
    void start() {
        AcceptanceObservationRuntime.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        maps = mock(MapManager.class);
        players = mock(PlayerStorage.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getWorld(0)).thenReturn(world);
        when(world.getId()).thenReturn(0);
        when(world.getChannels()).thenReturn(List.of(channel));
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        when(channel.getMapFactory()).thenReturn(maps);
        when(channel.getPlayerStorage()).thenReturn(players);
        when(world.getPlayerStorage()).thenReturn(players);
        when(maps.getMaps()).thenReturn(Map.of());
        when(players.getAllCharacters()).thenReturn(List.of());
        observations = service(8_192);
    }

    @AfterEach
    void stop() { AcceptanceObservationRuntime.onWorldStopped(); }

    private AcceptanceObservationService service(int bound) {
        return new AcceptanceObservationService(() -> server, () -> Map.of(
                "soloGitRevision", "test-revision", "beidouRevision", "engine-revision",
                "soloWorkingTreeDirty", false, "password", "must-not-escape"), bound);
    }

    @Test
    void capabilitiesExposeOnlyImplementedToolsAndThePublicArtifactIdentity() {
        Map<String, Object> result = observations.capabilities();
        assertEquals(List.of("game_capabilities", "game_observe"), result.get("enabled_tools"));
        assertEquals(List.of(), result.get("actions"));
        assertEquals(Map.of("enabled", false, "enabled_tools", List.of(), "commands", List.of()),
                result.get("debug_commands"));
        assertEquals(true, result.get("server_online"));
        Map<?, ?> source = assertInstanceOf(Map.class, result.get("source"));
        assertEquals("test-revision", source.get("soloGitRevision"));
        assertEquals("engine-revision", source.get("beidouRevision"));
        assertFalse(source.containsKey("password"));
        assertFalse(result.toString().contains("must-not-escape"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledCommandsAreAnnouncedIndependentlyOfActorActions() throws ReflectiveOperationException {
        DebugCommandService commands = mock(DebugCommandService.class);
        Map<String, Object> commandCapabilities = Map.of("enabled", true,
                "enabled_tools", List.of("game_command_help", "game_command"),
                "command_generation", "commands-test-generation", "commands", List.of("give"));
        when(commands.publicCapabilities()).thenReturn(commandCapabilities);
        ObjectProvider<DebugCommandService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(commands);
        var field = AcceptanceObservationService.class.getDeclaredField("debugCommands");
        field.setAccessible(true);
        field.set(observations, provider);

        Map<String, Object> result = observations.capabilities();
        assertEquals(List.of("game_capabilities", "game_observe", "game_command_help", "game_command"),
                result.get("enabled_tools"));
        assertEquals(commandCapabilities, result.get("debug_commands"));
        assertEquals(List.of(), result.get("actions"));
    }

    @Test
    void worldObservationDoesNotLoadAnEmptySceneOrCreateAnInstance() {
        Map<String, Object> result = observations.observe(new ObservationRequest("world", null, null));
        assertEquals(0, envelope(result, "scenes").get("total_count"));
        assertEquals(List.of(), envelope(result, "scenes").get("items"));
        verify(maps).getMaps();
        verify(maps, never()).getMap(anyInt());
        verify(maps, never()).getDisposableMap(anyInt());
        verify(maps, never()).resetMap(anyInt());
    }

    @Test
    void currentActorSceneIsResolvedByObjectEvenOutsideTheReturnedDirectorySlice() {
        MapleMap first = map(10000);
        MapleMap actual = map(100000000);
        Character actor = actor(actual, 4);
        when(players.getAllCharacters()).thenReturn(List.of(actor));
        when(maps.getMaps()).thenReturn(Map.of(10000, first, 100000000, actual));

        Map<String, Object> directory = observations.observe(new ObservationRequest("world", null, 1));
        assertEquals(true, envelope(directory, "scenes").get("truncated"));
        Map<String, Object> context = observations.contextForActor(actor);
        assertEquals(100000000, context.get("map_id"));
        assertNotNull(context.get("runtime_generation"));
        Map<String, Object> scene = observations.observe(new ObservationRequest(
                "scene", (String) context.get("scene_ref"), 10));
        assertEquals(context.get("map_instance_key"), envelope(scene, "context").get("map_instance_key"));
        verify(maps, never()).getMap(anyInt());

        when(players.getCharacterById(4)).thenReturn(mock(Character.class));
        assertEquals(HttpStatus.GONE, assertThrows(ObservationException.class,
                () -> observations.contextForActor(actor)).status());
    }

    @Test
    void boundActorObservationRegistersOneSceneWithoutEnumeratingTheWorldOrLoadingMaps() {
        MapleMap actual = map(100000000);
        Character actor = actor(actual, 4);
        GameContext.CharacterRef binding = binding(actor);
        clearInvocations(server, world, channel, maps, players);

        Map<String, Object> first = observations.contextForActor(binding);
        Map<String, Object> repeated = observations.contextForActor(binding);
        assertEquals(first.get("scene_ref"), repeated.get("scene_ref"));
        assertEquals(first.get("runtime_key"), repeated.get("runtime_key"));
        assertEquals(100000000, first.get("map_id"));
        assertEquals(binding.epoch().value(), first.get("runtime_generation"));
        verify(server, never()).getWorlds();
        verify(world, never()).getChannels();
        verify(players, never()).getAllCharacters();
        verify(channel, never()).getMapFactory();
        verifyNoInteractions(maps);
    }

    @Test
    void actorSceneDoesNotNeedWorldDirectoryDiscoveryEvenBeyondFiveHundredLoadedMaps() {
        Map<Integer, MapleMap> loaded = new LinkedHashMap<>();
        for (int i = 0; i < 501; i++) loaded.put(10000 + i, map(10000 + i));
        MapleMap actual = loaded.get(10500);
        Character actor = actor(actual, 4);
        GameContext.CharacterRef binding = binding(actor);
        when(maps.getMaps()).thenReturn(loaded);
        when(players.getAllCharacters()).thenReturn(List.of(actor));
        Map<String, Object> directory = observations.observe(new ObservationRequest("world", null, 500));
        assertEquals(501, envelope(directory, "scenes").get("total_count"));
        assertEquals(true, envelope(directory, "scenes").get("truncated"));
        assertTrue(sceneItems(directory).stream().noneMatch(row -> row.get("map_id").equals(10500)));
        clearInvocations(server, world, maps, players);
        when(server.getWorlds()).thenThrow(new AssertionError("A single actor cannot scan the directory"));
        when(maps.getMaps()).thenThrow(new AssertionError("A single actor cannot enumerate loaded maps"));

        Map<String, Object> context = observations.contextForActor(binding);
        assertEquals(10500, context.get("map_id"));
        assertNotNull(context.get("scene_ref"));
        verify(server, never()).getWorlds();
        verify(maps, never()).getMaps();
        verify(players, never()).getAllCharacters();
    }

    @Test
    void anActorsOldWorldBindingCannotAcquireANewEpochByBeingObserved() {
        Character actor = actor(map(10000), 4);
        GameContext.CharacterRef binding = binding(actor);
        String firstRef = (String) observations.contextForActor(binding).get("scene_ref");
        AcceptanceObservationRuntime.onWorldStopped();
        AcceptanceObservationRuntime.onWorldStarted();

        assertActorFailure(HttpStatus.GONE, "actor_not_in_current_world", binding);
        GameContext.CharacterRef reopened = binding(actor);
        assertNotEquals(firstRef, observations.contextForActor(reopened).get("scene_ref"));
    }

    @Test
    void directActorReferencesKeepTheRegistryBoundWithoutEvictingAnExistingScene() {
        observations = service(1);
        MapleMap firstMap = map(10000);
        MapleMap secondMap = map(10001);
        Character actor = actor(firstMap, 4);
        GameContext.CharacterRef binding = binding(actor);
        String ref = (String) observations.contextForActor(binding).get("scene_ref");
        when(actor.getMap()).thenReturn(secondMap);
        when(secondMap.getCharacterById(4)).thenReturn(actor);

        assertActorFailure(HttpStatus.SERVICE_UNAVAILABLE, "scene_capacity_exceeded", binding);
        when(actor.getMap()).thenReturn(firstMap);
        assertEquals(ref, observations.contextForActor(binding).get("scene_ref"));
    }

    @Test
    void movingToAnotherSameIdInstanceChangesTheActorSceneButKeepsItsCharacterBinding() {
        MapleMap firstMap = map(920010000);
        MapleMap secondMap = map(920010000);
        Character actor = actor(firstMap, 4);
        EventInstanceManager firstEvent = eventFor(actor);
        EventInstanceManager secondEvent = eventFor(actor);
        when(actor.getEventInstance()).thenReturn(firstEvent);
        when(firstMap.getEventInstance()).thenReturn(firstEvent);
        GameContext.CharacterRef binding = binding(actor);
        Map<String, Object> first = observations.contextForActor(binding);
        when(actor.getMap()).thenReturn(secondMap);
        when(secondMap.getCharacterById(4)).thenReturn(actor);
        when(actor.getEventInstance()).thenReturn(secondEvent);
        when(secondMap.getEventInstance()).thenReturn(secondEvent);

        Map<String, Object> current = observations.contextForActor(binding);
        assertNotEquals(first.get("scene_ref"), current.get("scene_ref"));
        assertNotEquals(first.get("runtime_key"), current.get("runtime_key"));
        assertEquals(binding.epoch().value(), current.get("runtime_generation"));
    }

    @Test
    void aLoadedGlobalSceneReferenceSurvivesTheActorsRemoval() {
        MapleMap actual = map(10000);
        Character actor = actor(actual, 4);
        when(maps.getMaps()).thenReturn(Map.of(10000, actual));
        String ref = (String) observations.contextForActor(binding(actor)).get("scene_ref");
        when(players.getCharacterById(4)).thenReturn(null);
        when(players.getAllCharacters()).thenReturn(List.of());
        when(actual.getCharacterById(4)).thenReturn(null);
        when(actual.getAllPlayers()).thenReturn(List.of());

        Map<String, Object> result = observations.observe(new ObservationRequest("scene", ref, 100));
        assertEquals(ref, envelope(result, "context").get("scene_ref"));
    }

    @Test
    void memberAndMapEventsAreValidatedIndependentlyWithoutUsingTheirNamesAsIdentity() {
        MapleMap actual = map(920010000);
        Character actor = actor(actual, 4);
        EventInstanceManager member = eventFor(actor);
        EventInstanceManager mapEvent = eventFor(actor);
        when(actor.getEventInstance()).thenReturn(member);
        when(actual.getEventInstance()).thenReturn(mapEvent);
        GameContext.CharacterRef binding = binding(actor);
        String firstRef = (String) observations.contextForActor(binding).get("scene_ref");
        when(member.getPlayerById(4)).thenReturn(null);
        assertActorFailure(HttpStatus.CONFLICT, "actor_context_mismatch", binding);
        when(member.getPlayerById(4)).thenReturn(actor);
        when(mapEvent.isEventDisposed()).thenReturn(true);
        assertActorFailure(HttpStatus.CONFLICT, "actor_context_mismatch", binding);
        when(mapEvent.isEventDisposed()).thenReturn(false);
        assertEquals(firstRef, observations.contextForActor(binding).get("scene_ref"));
        verify(member, never()).getMapFactory();
        verify(mapEvent, never()).getMapFactory();
    }

    @Test
    void changingTheMapsEventInvalidatesItsOldOpaqueReference() {
        MapleMap actual = map(920010000);
        Character actor = actor(actual, 4);
        EventInstanceManager firstEvent = eventFor(actor);
        EventInstanceManager secondEvent = eventFor(actor);
        when(actual.getEventInstance()).thenReturn(firstEvent);
        GameContext.CharacterRef binding = binding(actor);
        Map<String, Object> first = observations.contextForActor(binding);
        when(actual.getEventInstance()).thenReturn(secondEvent);
        Map<String, Object> second = observations.contextForActor(binding);
        assertNotEquals(first.get("scene_ref"), second.get("scene_ref"));
        assertNotEquals(first.get("runtime_key"), second.get("runtime_key"));
        when(maps.getMaps()).thenReturn(Map.of(920010000, actual));
        assertFailure(HttpStatus.GONE, "stale_scene_ref", new ObservationRequest(
                "scene", (String) first.get("scene_ref"), 100));
    }

    @Test
    void eventChangeWhileReadingTheProjectionIsASceneConflictWithoutInvalidatingTheCharacter() {
        MapleMap actual = map(920010000);
        Character actor = actor(actual, 4);
        EventInstanceManager member = eventFor(actor);
        when(actor.getEventInstance()).thenReturn(member);
        GameContext.CharacterRef binding = binding(actor);
        when(actual.getAllPlayers()).thenAnswer(ignored -> {
            when(actor.getEventInstance()).thenReturn(null);
            return List.of(actor);
        });
        assertActorFailure(HttpStatus.CONFLICT, "actor_context_mismatch", binding);
        NativeContextChecks.requireCharacterCurrent(server, binding);
    }

    @Test
    void actorReadFailureIsUnavailableAndDoesNotPublishNativeExceptionText() {
        MapleMap actual = map(10000);
        Character actor = actor(actual, 4);
        GameContext.CharacterRef binding = binding(actor);
        when(actual.getAllPlayers()).thenThrow(new IllegalStateException("private-native-detail"));
        ObservationException unavailable = assertActorFailure(
                HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable", binding);
        assertFalse(unavailable.toString().contains("private-native-detail"));
        doReturn(List.of(actor)).when(actual).getAllPlayers();
        assertNotNull(observations.contextForActor(binding).get("scene_ref"));
    }

    @Test
    void aSharedNativeReadFailureKeepsItsUnavailableBoundaryAndTheBindingCanRecover() {
        MapleMap actual = map(10000);
        Character actor = actor(actual, 4);
        GameContext.CharacterRef binding = binding(actor);
        when(actor.getEventInstance()).thenThrow(new IllegalStateException("private-event-detail"));

        ObservationException unavailable = assertActorFailure(
                HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable", binding);
        assertFalse(unavailable.toString().contains("private-event-detail"));
        doReturn(null).when(actor).getEventInstance();
        assertNotNull(observations.contextForActor(binding).get("scene_ref"));
    }

    @Test
    void worldChangeWhileReadingAnActorProjectionRejectsItsOldBinding() {
        MapleMap actual = map(10000);
        Character actor = actor(actual, 4);
        GameContext.CharacterRef binding = binding(actor);
        when(actual.getAllPlayers()).thenAnswer(ignored -> {
            AcceptanceObservationRuntime.onWorldStopped();
            return List.of(actor);
        });
        assertActorFailure(HttpStatus.CONFLICT, "runtime_changed", binding);
    }

    @Test
    void anEpochChangeDuringTheFinalActorOnlineReadCannotPublishThePreviousGeneration() {
        Character actor = actor(map(10000), 4);
        GameContext.CharacterRef binding = binding(actor);
        AtomicInteger nativeReads = new AtomicInteger();
        when(server.isOnline()).thenAnswer(ignored -> {
            // Initial readiness, two capture reads, two revalidation reads, then the final native read.
            if (nativeReads.incrementAndGet() == 6) AcceptanceObservationRuntime.onWorldStarted();
            return true;
        });

        assertActorFailure(HttpStatus.CONFLICT, "runtime_changed", binding);
        assertEquals(6, nativeReads.get());
    }

    @Test
    void aDirectoryObservationAlsoChecksItsEpochAfterTheLastNativeOnlineRead() {
        AtomicInteger nativeReads = new AtomicInteger();
        when(server.isOnline()).thenAnswer(ignored -> {
            if (nativeReads.incrementAndGet() == 2) AcceptanceObservationRuntime.onWorldStarted();
            return true;
        });

        assertFailure(HttpStatus.CONFLICT, "runtime_changed", new ObservationRequest("world", null, 100));
        assertEquals(2, nativeReads.get());
    }

    @Test
    void oldInternalCharacterEntryKeepsItsLegacyMissingActorAndSceneReasons() {
        ObservationException absent = assertThrows(ObservationException.class,
                () -> observations.contextForActor((Character) null));
        assertEquals("actor_not_in_world", absent.reason());
        MapleMap actual = map(10000);
        Character actor = actor(actual, 4);
        EventInstanceManager event = eventFor(actor);
        when(actor.getEventInstance()).thenReturn(event);
        when(event.isEventDisposed()).thenReturn(true);
        ObservationException scene = assertThrows(ObservationException.class,
                () -> observations.contextForActor(actor));
        assertEquals(HttpStatus.GONE, scene.status());
        assertEquals("actor_scene_unavailable", scene.reason());
    }

    @Test
    void sameMapIdInDifferentEventInstancesRemainsTwoDifferentScenes() {
        MapleMap publicMap = map(100000000);
        MapleMap firstInstance = map(920010000);
        MapleMap secondInstance = map(920010000);
        EventInstanceManager eventA = mock(EventInstanceManager.class);
        EventInstanceManager eventB = mock(EventInstanceManager.class);
        MapManager eventMapsA = mock(MapManager.class);
        MapManager eventMapsB = mock(MapManager.class);
        when(eventA.getMapFactory()).thenReturn(eventMapsA);
        when(eventB.getMapFactory()).thenReturn(eventMapsB);
        when(eventMapsA.getMaps()).thenReturn(Map.of(920010000, firstInstance));
        when(eventMapsB.getMaps()).thenReturn(Map.of(920010000, secondInstance));
        when(firstInstance.getEventInstance()).thenReturn(eventA);
        when(secondInstance.getEventInstance()).thenReturn(eventB);
        Character first = mock(Character.class);
        Character second = mock(Character.class);
        when(first.getMap()).thenReturn(firstInstance);
        when(second.getMap()).thenReturn(secondInstance);
        when(players.getAllCharacters()).thenReturn(List.of(first, second));
        when(maps.getMaps()).thenReturn(Map.of(100000000, publicMap));

        List<Map<String, Object>> scenes = sceneItems(observations.observe(new ObservationRequest("world", null, 100)));
        assertEquals(3, scenes.size());
        List<Map<String, Object>> copies = scenes.stream().filter(row -> row.get("map_id").equals(920010000)).toList();
        assertEquals(2, copies.size());
        assertNotEquals(copies.get(0).get("scene_ref"), copies.get(1).get("scene_ref"));
        assertNotEquals(copies.get(0).get("runtime_key"), copies.get(1).get("runtime_key"));
        String ref = (String) copies.get(0).get("scene_ref");
        Map<String, Object> snapshot = observations.observe(new ObservationRequest("scene", ref, 5));
        assertEquals(ref, assertInstanceOf(Map.class, snapshot.get("context")).get("map_instance_key"));
        verify(eventMapsA, never()).getMap(anyInt());
        verify(eventMapsB, never()).getMap(anyInt());
    }

    @Test
    void replacementMapAndWorldGenerationInvalidateIssuedReferences() {
        MapleMap original = map(100000000);
        when(maps.getMaps()).thenReturn(Map.of(100000000, original));
        String originalRef = (String) sceneItems(observations.observe(new ObservationRequest("world", null, 100))).getFirst().get("scene_ref");
        MapleMap replacement = map(100000000);
        when(maps.getMaps()).thenReturn(Map.of(100000000, replacement));
        assertFailure(HttpStatus.GONE, "stale_scene_ref", new ObservationRequest("scene", originalRef, 100));

        String ref = (String) sceneItems(observations.observe(new ObservationRequest("world", null, 100))).getFirst().get("scene_ref");
        AcceptanceObservationRuntime.onWorldStopped();
        assertEquals(false, observations.capabilities().get("server_online"));
        assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "server_offline", new ObservationRequest("world", null, 100));
        AcceptanceObservationRuntime.onWorldStarted();
        assertFailure(HttpStatus.GONE, "stale_scene_ref", new ObservationRequest("scene", ref, 100));
    }

    @Test
    void staleReferencesArePrunedAndBothResultAndRegistryBoundsAreExplicit() {
        MapleMap first = map(1);
        MapleMap second = map(2);
        when(maps.getMaps()).thenReturn(Map.of(1, first, 2, second));
        Map<String, Object> result = observations.observe(new ObservationRequest("world", null, 1));
        assertEquals(2, envelope(result, "scenes").get("total_count"));
        assertEquals(true, envelope(result, "scenes").get("truncated"));
        assertEquals(1, sceneItems(result).size());
        observations = service(1);
        assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "scene_capacity_exceeded", new ObservationRequest("world", null, 1));
    }

    @Test
    void generationChangeWhileReadingARealSceneRejectsItsSnapshot() {
        MapleMap map = map(100000000);
        when(maps.getMaps()).thenReturn(Map.of(100000000, map));
        String ref = (String) sceneItems(observations.observe(new ObservationRequest("world", null, 100))).getFirst().get("scene_ref");
        when(map.getMapObjects()).thenAnswer(ignored -> {
            AcceptanceObservationRuntime.onWorldStopped();
            return List.of();
        });
        assertFailure(HttpStatus.CONFLICT, "runtime_changed", new ObservationRequest("scene", ref, 100));
    }

    @Test
    void invalidInputsAndUnknownReferencesHaveStableReasons() {
        assertFailure(HttpStatus.BAD_REQUEST, "invalid_scope", null);
        assertFailure(HttpStatus.BAD_REQUEST, "invalid_scope", new ObservationRequest("inventory", null, 100));
        assertFailure(HttpStatus.BAD_REQUEST, "invalid_limit", new ObservationRequest("world", null, 0));
        assertFailure(HttpStatus.BAD_REQUEST, "invalid_limit", new ObservationRequest("world", null, 501));
        assertFailure(HttpStatus.BAD_REQUEST, "scene_ref_not_allowed", new ObservationRequest("world", "scene-x", 100));
        assertFailure(HttpStatus.BAD_REQUEST, "invalid_scene_ref", new ObservationRequest("scene", "100000000", 100));
        assertFailure(HttpStatus.GONE, "stale_scene_ref", new ObservationRequest("scene", "scene-00000000-0000-0000-0000-000000000000", 100));
    }

    private void assertFailure(HttpStatus status, String reason, ObservationRequest request) {
        ObservationException failure = assertThrows(ObservationException.class, () -> observations.observe(request));
        assertEquals(status, failure.status());
        assertEquals(reason, failure.reason());
    }

    private ObservationException assertActorFailure(HttpStatus status, String reason, GameContext.CharacterRef actor) {
        ObservationException failure = assertThrows(ObservationException.class,
                () -> observations.contextForActor(actor));
        assertEquals(status, failure.status());
        assertEquals(reason, failure.reason());
        return failure;
    }

    private Character actor(MapleMap map, int id) {
        Character actor = mock(Character.class);
        Client client = mock(Client.class);
        when(actor.getId()).thenReturn(id);
        when(actor.getWorld()).thenReturn(0);
        when(actor.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(actor);
        when(client.getWorld()).thenReturn(0);
        when(client.getChannel()).thenReturn(1);
        when(actor.isLoggedInWorld()).thenReturn(true);
        when(actor.getPosition()).thenReturn(new Point(0, 0));
        when(actor.getMap()).thenReturn(map);
        when(players.getCharacterById(id)).thenReturn(actor);
        when(map.getCharacterById(id)).thenReturn(actor);
        when(map.getAllPlayers()).thenReturn(List.of(actor));
        return actor;
    }

    private GameContext.CharacterRef binding(Character actor) {
        return NativeContextChecks.captureCharacter(server, actor, actor.getClient(), world, channel);
    }

    private static EventInstanceManager eventFor(Character actor) {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getPlayerById(actor.getId())).thenReturn(actor);
        return event;
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        when(map.getAllPlayers()).thenReturn(List.of());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(Map<String, Object> result, String key) {
        return (Map<String, Object>) result.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sceneItems(Map<String, Object> result) {
        return (List<Map<String, Object>>) envelope(result, "scenes").get("items");
    }
}
