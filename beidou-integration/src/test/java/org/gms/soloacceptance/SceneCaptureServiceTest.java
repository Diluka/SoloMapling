package org.gms.soloacceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneCaptureServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll static void nativeContext() { CharacterTestContext.install(); }
    @AfterEach void stop() { AcceptanceObservationRuntime.onWorldStopped(); }

    @Test
    void oneBackendCallStreamsImmediateFramesAndEndsOnlyAfterItsProbeIsReleased() throws Exception {
        var fixture = new Fixture();
        var output = new EvidenceStream(row -> {
            if (row.path("type").asText().equals("baseline")) {
                assertTrue(SceneCaptureEvents.interested(fixture.map));
            }
        });
        fixture.captures.record("admin", new SceneCaptureRequest.Record(fixture.sceneRef, 100, 50), output);
        var records = output.rows();
        assertEquals("header", records.getFirst().path("type").asText());
        assertEquals(fixture.sceneRef, records.getFirst().path("identity").path("scene_ref").asText());
        var end = records.getLast();
        assertTrue(end.path("completed_normally").asBoolean());
        assertTrue(end.path("file_complete").asBoolean());
        assertEquals(records.stream().filter(row -> List.of("baseline", "checkpoint").contains(row.path("type").asText())).count(),
                end.path("frame_count").asLong());
        assertEquals(1, records.get(1).path("seq").asInt());
        assertEquals("baseline", records.get(1).path("type").asText());
        assertEquals("events", records.get(2).path("type").asText());
        assertEquals(records.stream().filter(row -> row.path("type").asText().equals("events")).count(),
                end.path("event_batch_count").asLong());
        assertTrue(end.path("sampling_stopped").asBoolean());
        assertTrue(end.path("subscription_released").asBoolean());
        assertFalse(end.path("coverage").path("complete").asBoolean());
        assertFalse(SceneCaptureEvents.interested(fixture.map));
        assertEquals(true, fixture.captures.status("admin", output.captureId).get("file_complete"));
        verify(fixture.maps, never()).getMap(anyInt());
        verify(fixture.map, never()).addPlayer(any());
    }

    @Test
    void cancellationIsOwnerBoundAndWaitsForTheActualSamplingCarrierAndSubscription() throws Exception {
        var fixture = new Fixture();
        var firstFrame = new CountDownLatch(1);
        var output = new EvidenceStream(row -> {
            if (row.path("type").asText().equals("baseline")) firstFrame.countDown();
        });
        var failure = new AtomicReference<Throwable>();
        var carrier = Thread.ofVirtual().start(() -> {
            try { fixture.captures.record("admin", new SceneCaptureRequest.Record(fixture.sceneRef, 120_000, 5_000), output); }
            catch (Throwable problem) { failure.set(problem); }
        });
        assertTrue(firstFrame.await(5, TimeUnit.SECONDS));
        var denied = assertThrows(ObservationException.class, () -> fixture.captures.cancel("other", output.captureId));
        assertEquals(HttpStatus.NOT_FOUND, denied.status());
        fixture.captures.cancel("admin", output.captureId);
        carrier.join(Duration.ofSeconds(5));
        assertFalse(carrier.isAlive());
        assertNull(failure.get());
        var status = fixture.captures.status("admin", output.captureId);
        assertEquals("cancelled", status.get("reason"));
        assertEquals(true, status.get("sampling_stopped"));
        assertEquals(true, status.get("subscription_released"));
        assertEquals(false, status.get("completed_normally"));
        assertEquals(true, status.get("file_complete"));
        assertFalse(SceneCaptureEvents.interested(fixture.map));
    }

    @Test
    void replacingTheSameMapIdEndsTheOriginalCaptureWithoutFollowingTheReplacement() throws Exception {
        var fixture = new Fixture();
        var replacement = mock(MapleMap.class);
        var output = new EvidenceStream(row -> {
            if (row.path("type").asText().equals("baseline")) fixture.loaded.set(Map.of(104040000, replacement));
        });
        fixture.captures.record("admin", new SceneCaptureRequest.Record(fixture.sceneRef, 1_000, 50), output);
        var end = output.rows().getLast();
        assertEquals("stale_scene_ref", end.path("reason").asText());
        assertEquals(1, end.path("frame_count").asInt());
        assertFalse(end.path("completed_normally").asBoolean());
        assertTrue(end.path("file_complete").asBoolean());
        assertFalse(SceneCaptureEvents.interested(fixture.map));
        verifyNoInteractions(replacement);
    }

    @Test
    void failedOutputKeepsItsPrefixAndReleasesItsSamplerAndSubscription() throws Exception {
        var fixture = new Fixture();
        var failed = new EvidenceStream(row -> { });
        failed.failAfterFirstFrame = true;
        assertThrows(IOException.class,
                () -> fixture.captures.record("admin", new SceneCaptureRequest.Record(fixture.sceneRef, 1_000, 50), failed));
        assertEquals(2, failed.rows().size());
        assertEquals("baseline", failed.rows().getLast().path("type").asText());
        var status = fixture.captures.status("admin", failed.captureId);
        assertEquals("output_failed", status.get("reason"));
        assertEquals(false, status.get("file_complete"));
        assertEquals(true, status.get("sampling_stopped"));
        assertEquals(true, status.get("subscription_released"));
        assertFalse(SceneCaptureEvents.interested(fixture.map));
    }

    private static final class EvidenceStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Consumer<JsonNode> onRecord;
        private int flushed;
        private volatile String captureId;
        private boolean failAfterFirstFrame;
        private boolean fail;

        EvidenceStream(Consumer<JsonNode> onRecord) { this.onRecord = onRecord; }

        @Override public void write(int value) throws IOException {
            if (fail) throw new IOException("capture consumer disconnected");
            bytes.write(value);
        }

        @Override public void flush() throws IOException {
            String text = bytes.toString(StandardCharsets.UTF_8);
            var row = JSON.readTree(text.substring(flushed));
            flushed = text.length();
            if (row.path("type").asText().equals("header")) captureId = row.path("capture_id").asText();
            onRecord.accept(row);
            if (failAfterFirstFrame && row.path("type").asText().equals("baseline")) fail = true;
        }

        List<JsonNode> rows() {
            return bytes.toString(StandardCharsets.UTF_8).lines().map(line -> {
                try { return JSON.readTree(line); }
                catch (IOException malformed) { throw new AssertionError(malformed); }
            }).toList();
        }
    }

    private static final class Fixture {
        final MapleMap map = mock(MapleMap.class);
        final MapManager maps = mock(MapManager.class);
        final AtomicReference<Map<Integer, MapleMap>> loaded = new AtomicReference<>(Map.of(104040000, map));
        final SceneCaptureService captures;
        final String sceneRef;

        Fixture() {
            AcceptanceObservationRuntime.onWorldStarted();
            var server = mock(Server.class);
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var storage = mock(PlayerStorage.class);
            when(server.isOnline()).thenReturn(true);
            when(server.getWorlds()).thenReturn(List.of(world));
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannels()).thenReturn(List.of(channel));
            when(world.getChannel(1)).thenReturn(channel);
            when(world.getPlayerStorage()).thenReturn(storage);
            when(channel.getId()).thenReturn(1);
            when(channel.getPlayerStorage()).thenReturn(storage);
            when(channel.getMapFactory()).thenReturn(maps);
            when(maps.getMaps()).thenAnswer(ignored -> loaded.get());
            when(map.getId()).thenReturn(104040000);
            when(map.getMapArea()).thenReturn(new Rectangle(-100, -100, 200, 200));
            when(map.getFootholds()).thenReturn(new FootholdTree(new Point(-100, -100), new Point(100, 100)));
            var observations = new AcceptanceObservationService(() -> server, () -> Map.of(), 8_192);
            var result = observations.observe(new ObservationRequest("world", null, 100));
            var scenes = (Map<?, ?>) result.get("scenes");
            var items = (List<?>) scenes.get("items");
            sceneRef = (String) ((Map<?, ?>) items.getFirst()).get("scene_ref");
            captures = new SceneCaptureService(observations);
        }
    }
}
