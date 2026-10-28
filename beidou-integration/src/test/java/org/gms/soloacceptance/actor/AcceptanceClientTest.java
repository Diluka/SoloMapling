package org.gms.soloacceptance.actor;

import io.netty.handler.timeout.IdleStateEvent;
import org.gms.client.BotClient;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcceptanceClientTest {
    private World world;
    private Channel channel;

    @BeforeEach
    void nativeContextWithoutDatasource() {
        CharacterTestContext.install();
        world = mock(World.class);
        channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(3);
    }

    @Test
    void bindsNativeRoutingToCapturedObjectsAndDoesNotImpersonateAnEnvironmentBot() throws Exception {
        AcceptanceClient client = client();
        Channel replacement = mock(Channel.class);
        Channel other = mock(Channel.class);
        when(world.getChannel(3)).thenReturn(replacement);
        when(world.getChannel(2)).thenReturn(other);

        assertSame(world, client.getWorldServer());
        assertSame(channel, client.getChannelServer());
        assertSame(channel, client.getChannelServer((byte) 3));
        assertSame(other, client.getChannelServer((byte) 2));
        verify(world, never()).getChannel(3);
        assertFalse(BotClient.class.isInstance(client));
        assertFalse(client.isLoggedIn());
    }

    @Test
    void capturesOnlyImmutableMetadataAndNeverRetainsPayload() {
        AcceptanceClient client = client();
        byte[] payload = "private-login-payload".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[payload.length + 2];
        bytes[0] = 0x30;
        bytes[1] = 0x01;
        System.arraycopy(payload, 0, bytes, 2, payload.length);
        client.sendPacket(() -> bytes);
        List<Map<String, Object>> snapshot = client.packetSummaries();
        bytes[0] = 0;
        bytes[2] = 0;

        assertEquals(Map.of("sequence", 1L, "opcode", 0x0130, "length", bytes.length), snapshot.getFirst());
        assertFalse(snapshot.toString().contains("private-login-payload"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getFirst().put("payload", "not-allowed"));
        client.sendPacket(() -> new byte[]{1, 0});
        assertEquals(1, snapshot.size());
        assertEquals(2, client.packetSummaries().size());
    }

    @Test
    void boundsPacketHistoryAndReportsDiscardedMetadata() {
        AcceptanceClient client = client();
        for (int i = 0; i < 257; i++) client.sendPacket(() -> new byte[]{1, 0});
        List<Map<String, Object>> summaries = client.packetSummaries();
        assertEquals(256, summaries.size());
        assertEquals(2L, summaries.getFirst().get("sequence"));
        assertEquals(257L, summaries.getLast().get("sequence"));
        assertEquals(257L, client.latestPacketSequence());
        assertEquals(1L, client.droppedPacketCount());
    }

    @Test
    void malformedOutputCannotInterruptNativeLoginOrExposePayloadAndExceptionMessages() {
        AcceptanceClient client = client();
        assertDoesNotThrow(() -> client.sendPacket(null));
        assertDoesNotThrow(() -> client.sendPacket(() -> null));
        assertDoesNotThrow(() -> client.sendPacket(() -> {
            throw new IllegalStateException("private-packet-content");
        }));
        assertDoesNotThrow(() -> client.sendPacket(() -> new byte[1]));
        client.sendPacket(() -> new byte[]{1, 0});

        List<Map<String, Object>> summaries = client.packetSummaries();
        assertEquals(5, summaries.size());
        assertEquals(4L, client.malformedPacketCount());
        assertEquals(List.of("packet_missing", "packet_bytes_missing", "packet_read_failed",
                        "packet_header_missing"),
                summaries.subList(0, 4).stream().map(summary -> summary.get("reason")).toList());
        for (Map<String, Object> summary : summaries.subList(0, 4)) {
            assertEquals(true, summary.get("malformed"));
            assertNull(summary.get("opcode"));
            assertTrue(summary.containsKey("length"));
            assertThrows(UnsupportedOperationException.class, () -> summary.put("reason", "replacement"));
        }
        assertFalse(summaries.toString().contains("private-packet-content"));
        assertEquals(Map.of("sequence", 5L, "opcode", 1, "length", 2), summaries.getLast());
    }

    @Test
    void malformedMetadataIsBoundedAndClosedTransportDoesNotEvaluatePackets() {
        AcceptanceClient client = client();
        for (int i = 0; i < 257; i++) client.sendPacket(null);
        assertEquals(256, client.packetSummaries().size());
        assertEquals(2L, client.packetSummaries().getFirst().get("sequence"));
        assertEquals(257L, client.malformedPacketCount());
        assertEquals(1L, client.droppedPacketCount());
        client.closeSession();
        client.sendPacket(() -> {
            fail("closed transport must not read a packet");
            return null;
        });
        client.sendPacket(null);
        assertEquals(257L, client.malformedPacketCount());
        assertEquals(257L, client.latestPacketSequence());
    }

    @Test
    void closesOnlyTheTransportWithoutSocketOrNativeDatabaseCalls() {
        AcceptanceClient client = client();
        client.checkIfIdle(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        assertFalse(client.transportClosed());
        client.sendPacket(() -> new byte[1]);
        assertNull(client.packetSummaries().getFirst().get("opcode"));
        client.closeSession();
        client.disconnectSession();
        assertTrue(client.transportClosed());
        client.sendPacket(() -> new byte[]{1, 0});
        assertEquals(1L, client.latestPacketSequence());
        assertFalse(client.isLoggedIn());
    }

    private AcceptanceClient client() {
        return new AcceptanceClient(Client.Type.CHANNEL, -500L, "127.0.0.100", world, channel);
    }
}
