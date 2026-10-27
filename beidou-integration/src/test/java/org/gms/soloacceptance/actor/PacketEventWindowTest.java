package org.gms.soloacceptance.actor;

import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.soloacceptance.ObservationException;
import org.gms.util.PacketCreator;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketEventWindowTest {
    private AcceptanceClient client;

    @BeforeEach
    void prepareNativeContextWithoutDatasource() {
        CharacterTestContext.install();
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        client = new AcceptanceClient(Client.Type.CHANNEL, -502L, "127.0.0.102", world, channel);
        client.setLanguage(3);
    }

    @Test
    void emptyWindowAndInvalidInputsDoNotChangeAnyPacketState() {
        Map<String, Object> window = client.packetEvents(0, 500);
        assertEquals(List.of(), window.get("items"));
        assertEquals(0L, window.get("next_cursor"));
        assertEquals(0L, window.get("oldest_cursor"));
        assertEquals(0L, window.get("latest_cursor"));
        assertEquals(false, window.get("cursor_expired"));
        assertEquals(false, window.get("truncated"));
        assertEquals(0L, window.get("dropped_events_total"));
        assertEquals(0L, window.get("malformed_events_total"));
        assertBadRequest("packet_cursor_ahead", () -> client.packetEvents(1, 100));
        assertBadRequest("packet_cursor_ahead", () -> client.packetEvents(Long.MAX_VALUE, 100));
        assertBadRequest("invalid_packet_window", () -> client.packetEvents(-1, 100));
        assertBadRequest("invalid_packet_window", () -> client.packetEvents(0, 0));
        assertBadRequest("invalid_packet_window", () -> client.packetEvents(0, 501));
        assertEquals(window, client.packetEvents(0, 500));
    }

    @Test
    void pagesAdvanceOnlyToTheirOwnLastEventAndRepeatedReadsDoNotConsumeAnything() {
        for (int index = 0; index < 10; index++) sendNotice("分页公告" + index);
        Map<String, Object> first = client.packetEvents(0, 3);
        assertEquals(List.of(1L, 2L, 3L), sequences(first));
        assertEquals(3L, first.get("next_cursor"));
        assertEquals(10L, first.get("latest_cursor"));
        assertEquals(true, first.get("truncated"));
        assertEquals(first, client.packetEvents(0, 3));
        List<Long> visited = new ArrayList<>();
        long cursor = 0;
        boolean more;
        do {
            Map<String, Object> page = client.packetEvents(cursor, 3);
            visited.addAll(sequences(page));
            cursor = (long) page.get("next_cursor");
            more = (boolean) page.get("truncated");
        } while (more);
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 10).boxed().toList(), visited);
        assertEquals(10L, cursor);
        Map<String, Object> tail = client.packetEvents(cursor, 3);
        assertEquals(List.of(), tail.get("items"));
        assertEquals(10L, tail.get("next_cursor"));
        assertEquals(false, tail.get("truncated"));
        assertEquals(10, client.packetSummaries().size());
        assertEquals(10L, client.latestPacketSequence());
    }

    @Test
    void evictionAndPaginationReportLostHistorySeparatelyFromRemainingRetainedEvents() {
        long malformed = 0;
        for (int index = 1; index <= 300; index++) {
            if (index % 17 == 0) {
                client.sendPacket(null);
                malformed++;
            } else sendNotice("淘汰公告" + index);
        }
        Map<String, Object> page = client.packetEvents(0, 7);
        assertEquals(45L, page.get("oldest_cursor"));
        assertEquals(300L, page.get("latest_cursor"));
        assertEquals(51L, page.get("next_cursor"));
        assertEquals(true, page.get("cursor_expired"));
        assertEquals(true, page.get("truncated"));
        assertEquals(44L, page.get("dropped_events_total"));
        assertEquals(malformed, page.get("malformed_events_total"));
        assertEquals(java.util.stream.LongStream.rangeClosed(45, 51).boxed().toList(), sequences(page));
        assertEquals(false, client.packetEvents(44, 7).get("cursor_expired"));
        assertEquals(true, client.packetEvents(43, 7).get("cursor_expired"));
        Map<String, Object> allRetained = client.packetEvents(0, 500);
        assertEquals(256, items(allRetained).size());
        assertEquals(false, allRetained.get("truncated"));
        assertEquals(true, allRetained.get("cursor_expired"));
        assertEquals(300L, allRetained.get("next_cursor"));
        assertEquals(256, client.packetSummaries().size());
        assertEquals(44L, client.droppedPacketCount());
        assertEquals(malformed, client.malformedPacketCount());
        assertEquals(sequences(allRetained), client.packetSummaries().stream().map(event -> (long) event.get("sequence")).toList());
        assertBadRequest("packet_cursor_ahead", () -> client.packetEvents(301, 7));
        assertEquals(allRetained, client.packetEvents(0, 500));
    }

    @Test
    void windowAndNestedItemsAreImmutableSnapshotsIndependentOfLaterReceives() {
        sendNotice("第一次公告");
        Map<String, Object> window = client.packetEvents(0, 10);
        List<Map<String, Object>> items = items(window);
        assertThrows(UnsupportedOperationException.class, () -> window.put("latest_cursor", 100L));
        assertThrows(UnsupportedOperationException.class, () -> items.add(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> items.getFirst().put("text", "变更"));
        sendNotice("第二次公告");
        assertEquals(1L, window.get("latest_cursor"));
        assertEquals(1, items.size());
        assertEquals("第一次公告", items.getFirst().get("text"));
        assertEquals(2L, client.packetEvents(0, 10).get("latest_cursor"));
        Map<String, Object> capabilities = AcceptanceClient.packetObservationCapabilities();
        assertThrows(UnsupportedOperationException.class, () -> capabilities.put("capacity", 100));
        List<?> decoded = (List<?>) capabilities.get("decoded_packets");
        assertThrows(UnsupportedOperationException.class, () -> decoded.clear());
        Map<?, ?> notice = (Map<?, ?>) decoded.getLast();
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) notice.get("message_types")).clear());
    }

    @Test
    void concurrentWritersAndReadersAlwaysPublishOneConsistentWindow() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(3)) {
            Future<?> first = workers.submit(() -> writeNotices(start, "甲"));
            Future<?> second = workers.submit(() -> writeNotices(start, "乙"));
            Future<?> reader = workers.submit(() -> {
                await(start);
                for (int index = 0; index < 750; index++) assertCoherentWindow(client.packetEvents(0, 13), 13);
            });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            reader.get(15, TimeUnit.SECONDS);
        }
        Map<String, Object> finalWindow = client.packetEvents(0, 500);
        assertCoherentWindow(finalWindow, 500);
        assertEquals(600L, finalWindow.get("latest_cursor"));
        assertEquals(345L, finalWindow.get("oldest_cursor"));
        assertEquals(344L, finalWindow.get("dropped_events_total"));
        assertEquals(256, items(finalWindow).size());
        assertEquals(600L, finalWindow.get("next_cursor"));
        assertEquals(0L, finalWindow.get("malformed_events_total"));
    }

    @Test
    void closingTransportDoesNotBlockOnPacketAccessOrPublishAnInflightPacketAfterClose() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        Packet nativePacket = nativePacket(() -> PacketCreator.serverMessage("关闭中的公告"));
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<?> send = worker.submit(() -> client.sendPacket(() -> {
                reading.countDown();
                await(finishRead);
                return nativePacket.getBytes();
            }));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            try {
                client.closeSession();
                assertTrue(client.transportClosed());
                assertEquals(0L, client.latestPacketSequence());
            } finally { finishRead.countDown(); }
            send.get(5, TimeUnit.SECONDS);
        }
        assertEquals(List.of(), client.packetEvents(0, 500).get("items"));
        assertEquals(0L, client.latestPacketSequence());
    }

    @Test
    void internalDecoderTruncatesUnicodeByCodePointWithoutSplittingASurrogatePair() {
        // UTF-8 is a decoder seam here; native client languages remain the original GBK/ASCII set.
        String complete = "a".repeat(1023) + "\uD83D\uDE42";
        byte[] text = (complete + "中").getBytes(StandardCharsets.UTF_8);
        byte[] nativeHeader = nativePacket(() -> PacketCreator.serverNotice(1, "")).getBytes();
        ByteBuffer bytes = ByteBuffer.allocate(5 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(nativeHeader, 0, 3).putShort((short) text.length).put(text);
        Map<String, Object> fields = PacketEventDecoder.decode(bytes.array(), StandardCharsets.UTF_8);
        String decoded = (String) fields.get("text");
        assertEquals(complete, decoded);
        assertEquals(1024, decoded.codePointCount(0, decoded.length()));
        assertEquals(true, fields.get("text_truncated"));
        assertEquals("decoded", fields.get("decode_status"));
    }

    private void writeNotices(CountDownLatch start, String prefix) {
        await(start);
        for (int index = 0; index < 300; index++) sendNotice(prefix + index);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("packet_test_latch_timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private void sendNotice(String text) { client.sendPacket(nativePacket(() -> PacketCreator.serverMessage(text))); }

    private Packet nativePacket(Supplier<Packet> factory) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        try { return factory.get(); }
        finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        }
    }

    private static void assertBadRequest(String reason, Runnable action) {
        ObservationException failure = assertThrows(ObservationException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, failure.status());
        assertEquals(reason, failure.reason());
    }

    private static void assertCoherentWindow(Map<String, Object> window, int limit) {
        long latest = (long) window.get("latest_cursor");
        long expectedOldest = latest == 0 ? 0 : Math.max(1, latest - 255);
        assertEquals(expectedOldest, window.get("oldest_cursor"));
        assertEquals(Math.max(0, latest - 256), window.get("dropped_events_total"));
        assertEquals(expectedOldest > 1, window.get("cursor_expired"));
        int retained = (int) Math.min(256, latest);
        assertEquals(retained > limit, window.get("truncated"));
        List<Map<String, Object>> page = items(window);
        assertEquals(Math.min(retained, limit), page.size());
        long prior = expectedOldest - 1;
        for (Map<String, Object> event : page) {
            long sequence = (long) event.get("sequence");
            assertEquals(prior + 1, sequence);
            assertTrue(sequence <= latest);
            assertDoesNotThrow(() -> Instant.parse((String) event.get("received_at")));
            prior = sequence;
        }
        assertEquals(page.isEmpty() ? 0L : prior, window.get("next_cursor"));
    }

    private static List<Long> sequences(Map<String, Object> window) {
        return items(window).stream().map(event -> (long) event.get("sequence")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> window) {
        return (List<Map<String, Object>>) window.get("items");
    }
}
