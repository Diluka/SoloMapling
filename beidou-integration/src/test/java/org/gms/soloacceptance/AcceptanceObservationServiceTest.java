package org.gms.soloacceptance;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.util.List;
import java.util.Map;

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
        assertEquals(List.of(), result.get("recipes"));
        assertEquals(true, result.get("server_online"));
        Map<?, ?> source = assertInstanceOf(Map.class, result.get("source"));
        assertEquals("test-revision", source.get("soloGitRevision"));
        assertEquals("engine-revision", source.get("beidouRevision"));
        assertFalse(source.containsKey("password"));
        assertFalse(result.toString().contains("must-not-escape"));
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
        Character actor = mock(Character.class);
        Client client = mock(Client.class);
        when(actor.getId()).thenReturn(4);
        when(actor.getWorld()).thenReturn(0);
        when(actor.getClient()).thenReturn(client);
        when(client.getChannel()).thenReturn(1);
        when(actor.isLoggedInWorld()).thenReturn(true);
        when(actor.getMap()).thenReturn(actual);
        when(players.getCharacterById(4)).thenReturn(actor);
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
