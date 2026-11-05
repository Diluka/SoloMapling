package org.gms.soloacceptance;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.AcceptanceClient;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneCaptureOutputTest {
    @BeforeAll static void nativeContext() { CharacterTestContext.install(); }

    @Test
    void directNativeSendsKeepTheActualRecipientAndItsSubmissionOrder() throws Exception {
        var map = mock(MapleMap.class);
        var firstChannel = mock(io.netty.channel.Channel.class);
        var secondChannel = mock(io.netty.channel.Channel.class);
        var first = nativeClient(map, 7, firstChannel);
        var second = nativeClient(map, 8, secondChannel);
        Packet attack = PacketCreator.closeRangeAttack(first.getPlayer(), 0, 0, 1, 0x11,
                Map.of(400, List.of(9)), 6, 0, 0);
        Packet removed = PacketCreator.removePlayerFromMap(7);
        try (var subscription = SceneCaptureEvents.open(map)) {
            first.sendPacket(attack);
            second.sendPacket(attack);
            first.sendPacket(removed);
            var events = assertInstanceOf(List.class, subscription.drain().get("items"));
            assertEquals(3, events.size());
            var firstOutput = assertInstanceOf(Map.class, events.get(0));
            var secondOutput = assertInstanceOf(Map.class, events.get(1));
            var lastOutput = assertInstanceOf(Map.class, events.get(2));
            assertEquals("output", firstOutput.get("category"));
            var firstValues = assertInstanceOf(Map.class, firstOutput.get("values"));
            var secondValues = assertInstanceOf(Map.class, secondOutput.get("values"));
            var lastValues = assertInstanceOf(Map.class, lastOutput.get("values"));
            assertEquals(7, assertInstanceOf(Map.class, firstValues.get("recipient")).get("character_id"));
            assertEquals(8, assertInstanceOf(Map.class, secondValues.get("recipient")).get("character_id"));
            assertEquals(1L, firstValues.get("recipient_seq"));
            assertEquals(1L, secondValues.get("recipient_seq"));
            assertEquals(2L, lastValues.get("recipient_seq"));
            assertEquals("native_announcer_lock_submission_order", firstValues.get("recipient_ordering"));
            assertEquals("unobserved", firstValues.get("network_completion"));
            assertEquals(false, firstValues.get("client_receipt_or_playback_verified"));
            assertArrayEquals(attack.getBytes(), Base64.getDecoder().decode((String) firstValues.get("payload_base64")));
            verify(firstChannel).writeAndFlush(attack);
            verify(firstChannel).writeAndFlush(removed);
            verify(secondChannel).writeAndFlush(attack);
            verifyNoMoreInteractions(firstChannel, secondChannel);
            verify(map, never()).addPlayer(any());
        }
        assertFalse(SceneCaptureEvents.interested(map));
    }

    @Test
    void headlessDispatchesAreOutputEvidenceWithoutClaimingSocketDeliveryOrBufferOrder() {
        var map = mock(MapleMap.class);
        var bot = new BotClient(0, 1);
        var world = mock(org.gms.net.server.world.World.class);
        var channel = mock(org.gms.net.server.channel.Channel.class);
        var acceptance = new AcceptanceClient(Client.Type.CHANNEL, 73, "test", world, channel);
        var clients = List.of(bot, acceptance);
        for (var client : clients) {
            var actor = mock(Character.class);
            when(actor.getMap()).thenReturn(map);
            client.setPlayer(actor);
        }
        try (var subscription = SceneCaptureEvents.open(map)) {
            bot.sendPacket(PacketCreator.removePlayerFromMap(11));
            acceptance.sendPacket(PacketCreator.removePlayerFromMap(11));
            var items = assertInstanceOf(List.class, subscription.drain().get("items"));
            assertEquals(2, items.size());
            for (var item : items) {
                var event = assertInstanceOf(Map.class, item);
                var values = assertInstanceOf(Map.class, event.get("values"));
                assertEquals("output", event.get("category"));
                assertEquals("headless_scene_dispatch_returned", values.get("send_boundary"));
                assertEquals("no_socket", values.get("network_completion"));
                assertEquals("callback_observation_order_not_transport_or_packet_buffer_order",
                        values.get("recipient_ordering"));
            }
        }
        assertEquals(1, acceptance.latestPacketSequence());
        for (var client : clients) verify(client.getPlayer(), never()).getClient();
    }

    @Test
    void captureReadFailureDoesNotChangeTheExistingNativeSend() throws Exception {
        var map = mock(MapleMap.class);
        var transport = mock(io.netty.channel.Channel.class);
        var client = nativeClient(map, 9, transport);
        Packet unreadable = () -> { throw new IllegalStateException("private-packet-body"); };
        assertDoesNotThrow(() -> client.sendPacket(unreadable));
        try (var subscription = SceneCaptureEvents.open(map)) {
            assertDoesNotThrow(() -> client.sendPacket(unreadable));
            var batch = subscription.drain();
            assertEquals(1L, batch.get("probe_read_failures"));
            var items = assertInstanceOf(List.class, batch.get("items"));
            var event = assertInstanceOf(Map.class, items.getFirst());
            assertEquals("diagnostic", event.get("category"));
            assertEquals("probe_read_failed", event.get("kind"));
            assertFalse(event.toString().contains("private-packet-body"));
        }
        verify(transport, times(2)).writeAndFlush(unreadable);
    }

    @Test
    void anOutputBurstReportsItsWindowGapAndRetainsAnImmutableDrainedBatch() throws Exception {
        var map = mock(MapleMap.class);
        var transport = mock(io.netty.channel.Channel.class);
        var client = nativeClient(map, 10, transport);
        Packet packet = PacketCreator.removePlayerFromMap(7);
        try (var subscription = SceneCaptureEvents.open(map)) {
            for (int remaining = 4097; remaining > 0; remaining--) client.sendPacket(packet);
            var batch = subscription.drain();
            var items = assertInstanceOf(List.class, batch.get("items"));
            assertEquals(4096, items.size());
            assertEquals(4097L, batch.get("total_since_last"));
            assertEquals(1L, batch.get("dropped"));
            assertEquals(true, batch.get("gap"));
            assertEquals(2L, assertInstanceOf(Map.class, items.getFirst()).get("source_seq"));
            assertEquals(4097L, assertInstanceOf(Map.class, items.getLast()).get("source_seq"));
            var next = subscription.drain();
            assertTrue(assertInstanceOf(List.class, next.get("items")).isEmpty());
            assertEquals(false, next.get("gap"));
            assertEquals(4096, items.size());
            assertThrows(UnsupportedOperationException.class, items::clear);
        }
        assertFalse(SceneCaptureEvents.interested(map));
        verify(transport, times(4097)).writeAndFlush(packet);
    }

    private static Client nativeClient(MapleMap map, int id, io.netty.channel.Channel transport)
            throws ReflectiveOperationException {
        var client = new Client(Client.Type.CHANNEL, id, "test", null, 0, 1);
        var actor = mock(Character.class);
        when(actor.getId()).thenReturn(id);
        when(actor.getObjectId()).thenReturn(id);
        when(actor.getMap()).thenReturn(map);
        when(actor.getClient()).thenReturn(client);
        client.setPlayer(actor);
        var ioChannel = Client.class.getDeclaredField("ioChannel");
        ioChannel.setAccessible(true);
        ioChannel.set(client, transport);
        return client;
    }
}
