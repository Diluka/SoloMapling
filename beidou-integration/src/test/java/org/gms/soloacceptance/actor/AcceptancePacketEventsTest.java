package org.gms.soloacceptance.actor;

import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.util.PacketCreator;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native outbound packets exercise the public observation contract without a socket or datasource. */
class AcceptancePacketEventsTest {
    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "sequence", "received_at", "opcode", "length", "event_type", "decode_status", "reason");
    private static final Set<String> MALFORMED_REASONS = Set.of(
            "packet_missing", "packet_bytes_missing", "packet_header_missing", "packet_read_failed",
            "packet_payload_incomplete", "packet_string_length_invalid", "packet_text_encoding_invalid",
            "packet_boolean_invalid", "packet_trailing_bytes", "packet_yellow_tip_tail_invalid");
    private AcceptanceClient client;

    @BeforeEach
    void nativeContextAndChineseTransportWithoutDatasource() {
        CharacterTestContext.install();
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        client = new AcceptanceClient(Client.Type.CHANNEL, -501L, "127.0.0.101", world, channel);
        client.setLanguage(3);
    }

    @Test
    void decodesRealChineseChatWithSenderAndNativeFlags() {
        String text = "你好，欢迎一起冒险！";
        Packet packet = nativePacket(() -> PacketCreator.getChatText(1_234_567, text, true, 1));
        Map<String, Object> event = receive(packet);

        assertDecoded(event, "chat_text", text, false);
        assertEquals(0xA2, event.get("opcode"));
        assertEquals(packet.getBytes().length, event.get("length"));
        assertEquals(1_234_567, event.get("sender_character_id"));
        assertEquals(true, event.get("gm"));
        assertEquals(1, event.get("show"));

        Map<String, Object> ordinary = receive(nativePacket(() -> PacketCreator.getChatText(42, "普通聊天", false, 0)));
        assertEquals(false, ordinary.get("gm"));
        assertEquals(0, ordinary.get("show"));
    }

    @Test
    void decodesRealHintDimensionsVisibilityAndOnlyExactDotClearsPrevious() {
        Map<String, Object> hint = receive(nativePacket(() -> PacketCreator.sendHint("公会陪伴提示", 320, 90)));
        assertDecoded(hint, "player_hint", "公会陪伴提示", false);
        assertEquals(0xD6, hint.get("opcode"));
        assertEquals(320, hint.get("width"));
        assertEquals(90, hint.get("height"));
        assertEquals(true, hint.get("enabled"));
        assertEquals(false, hint.get("clears_previous"));

        assertEquals(true, receive(nativePacket(() -> PacketCreator.sendHint(".", 40, 5))).get("clears_previous"));
        assertEquals(false, receive(nativePacket(() -> PacketCreator.sendHint(". ", 40, 5))).get("clears_previous"));
        byte[] disabled = nativePacket(() -> PacketCreator.sendHint("隐藏提示", 80, 20)).getBytes().clone();
        disabled[disabled.length - 1] = 0;
        assertEquals(false, receive(() -> disabled).get("enabled"));
    }

    @Test
    void decodesAllSimpleNativeServerNoticeTypes() {
        for (int type : new int[]{0, 1, 2, 5}) {
            String text = "系统公告类型" + type;
            Map<String, Object> event = receive(nativePacket(() -> PacketCreator.serverNotice(type, text)));
            assertDecoded(event, "server_message", text, false);
            assertEquals(0x44, event.get("opcode"));
            assertEquals(type, event.get("message_type"));
            assertFalse(event.containsKey("ticker_enabled"));
            assertFalse(event.containsKey("channel_id"));
        }
    }

    @Test
    void decodesRealNativeYellowTipWithChineseAndEmptyText() {
        Packet packet = nativePacket(() -> PacketCreator.sendYellowTip("原生命令权限不足，请核对角色等级"));
        Map<String, Object> event = receive(packet);
        assertDecoded(event, "yellow_tip", "原生命令权限不足，请核对角色等级", false);
        assertEquals(0x4D, event.get("opcode"));
        assertEquals(packet.getBytes().length, event.get("length"));
        assertDecoded(receive(nativePacket(() -> PacketCreator.sendYellowTip(""))), "yellow_tip", "", false);
    }

    @Test
    void yellowTipReusesBoundedStrictNativeTextDecoding() {
        String boundary = "中".repeat(1024);
        assertDecoded(receive(nativePacket(() -> PacketCreator.sendYellowTip(boundary))),
                "yellow_tip", boundary, false);
        assertDecoded(receive(nativePacket(() -> PacketCreator.sendYellowTip(boundary + "文"))),
                "yellow_tip", boundary, true);

        byte[] invalidGbk = nativePacket(() -> PacketCreator.sendYellowTip(boundary + "A")).getBytes().clone();
        invalidGbk[invalidGbk.length - 3] = (byte) 0x81;
        assertMalformed(receive(() -> invalidGbk));
    }

    @Test
    void yellowTipCodePointTruncationNeverSplitsASurrogatePair() {
        // UTF-8 is a decoder seam; ordinary native client languages remain GBK/ASCII.
        String boundary = "a".repeat(1023) + "\uD83D\uDE42";
        byte[] text = (boundary + "中").getBytes(StandardCharsets.UTF_8);
        byte[] nativeHeader = nativePacket(() -> PacketCreator.sendYellowTip("")).getBytes();
        ByteBuffer packet = ByteBuffer.allocate(7 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(nativeHeader, 0, 3).putShort((short) text.length).put(text).putShort((short) 0);
        Map<String, Object> decoded = PacketEventDecoder.decode(packet.array(), StandardCharsets.UTF_8);
        assertDecoded(decoded, "yellow_tip", boundary, true);
        assertEquals(1024, ((String) decoded.get("text")).codePointCount(0, boundary.length()));
    }

    @Test
    void unknownYellowTipVariantsAndMalformedPayloadsKeepOnlyFixedSummaries() {
        byte[] nativeBytes = nativePacket(() -> PacketCreator.sendYellowTip("private-body")).getBytes();
        byte[] unknown = nativeBytes.clone();
        unknown[2] = 0;
        assertSummary(receive(() -> unknown), "unsupported", "unsupported_yellow_tip_type");

        byte[] length = nativeBytes.clone();
        length[3] = (byte) 0xFF;
        length[4] = (byte) 0xFF;
        assertMalformed(receive(() -> length));
        assertMalformed(receive(() -> Arrays.copyOf(nativeBytes, nativeBytes.length - 1)));

        byte[] tail = nativeBytes.clone();
        tail[tail.length - 2] = 1;
        assertSummary(receive(() -> tail), "malformed", "packet_yellow_tip_tail_invalid");
        assertMalformed(receive(() -> Arrays.copyOf(nativeBytes, nativeBytes.length + 1)));
        assertEquals(4L, client.packetEvents(0, 100).get("malformed_events_total"));
    }

    @Test
    void decodesNativeSuperMegaphoneChannelAndEarFlag() {
        Map<String, Object> loud = receive(nativePacket(() -> PacketCreator.serverNotice(3, 7, "七频道广播", true)));
        assertDecoded(loud, "server_message", "七频道广播", false);
        assertEquals(3, loud.get("message_type"));
        assertEquals(7, loud.get("channel_id"));
        assertEquals(true, loud.get("mega_ear"));

        Map<String, Object> quiet = receive(nativePacket(() -> PacketCreator.serverNotice(3, 1, "一频道广播", false)));
        assertEquals(1, quiet.get("channel_id"));
        assertEquals(false, quiet.get("mega_ear"));
    }

    @Test
    void decodesNativeTickerFlagBeforeStringLengthIncludingDisabledTicker() {
        Packet packet = nativePacket(() -> PacketCreator.serverMessage("欢迎来到北斗频道"));
        Map<String, Object> enabled = receive(packet);
        assertDecoded(enabled, "server_message", "欢迎来到北斗频道", false);
        assertEquals(4, enabled.get("message_type"));
        assertEquals(true, enabled.get("ticker_enabled"));

        byte[] disabled = packet.getBytes().clone();
        disabled[3] = 0;
        Map<String, Object> disabledEvent = receive(() -> disabled);
        assertDecoded(disabledEvent, "server_message", "欢迎来到北斗频道", false);
        assertEquals(false, disabledEvent.get("ticker_enabled"));
    }

    @Test
    void decodesNativeTypeSixAndNpcBroadcastLittleEndianTailFields() {
        Packet typeSix = nativePacket(() -> PacketCreator.serverNotice(6, "蓝色提示"));
        Map<String, Object> plain = receive(typeSix);
        assertDecoded(plain, "server_message", "蓝色提示", false);
        assertEquals(6, plain.get("message_type"));
        assertEquals(0, plain.get("trailing_value"));

        byte[] changedTail = typeSix.getBytes().clone();
        int value = 0x1234ABCD;
        for (int index = 0; index < 4; index++) changedTail[changedTail.length - 4 + index] = (byte) (value >>> (8 * index));
        assertEquals(value, receive(() -> changedTail).get("trailing_value"));

        Map<String, Object> npc = receive(nativePacket(() -> PacketCreator.serverNotice(7, "广播员提示", 1_012_000)));
        assertDecoded(npc, "server_message", "广播员提示", false);
        assertEquals(7, npc.get("message_type"));
        assertEquals(1_012_000, npc.get("npc_template_id"));
        assertFalse(npc.containsKey("trailing_value"));
    }

    @Test
    void supportsEmptyNativeTextAcrossAllDecodedPacketKinds() {
        assertDecoded(receive(nativePacket(() -> PacketCreator.getChatText(1, "", false, 0))), "chat_text", "", false);
        Map<String, Object> hint = receive(nativePacket(() -> PacketCreator.sendHint("", 0, 0)));
        assertDecoded(hint, "player_hint", "", false);
        assertEquals(false, hint.get("clears_previous"));
        assertEquals(40, hint.get("width"));
        assertEquals(5, hint.get("height"));
        assertDecoded(receive(nativePacket(() -> PacketCreator.serverMessage(""))), "server_message", "", false);
    }

    @Test
    void truncatesAt1024UnicodeCharactersRatherThanGbkBytes() {
        String boundary = "中".repeat(1024);
        String tooLong = boundary + "文";
        assertDecoded(receive(nativePacket(() -> PacketCreator.getChatText(1, boundary, false, 0))),
                "chat_text", boundary, false);
        assertDecoded(receive(nativePacket(() -> PacketCreator.getChatText(1, tooLong, false, 0))),
                "chat_text", boundary, true);
        assertDecoded(receive(nativePacket(() -> PacketCreator.sendHint(tooLong, 320, 90))),
                "player_hint", boundary, true);
        assertDecoded(receive(nativePacket(() -> PacketCreator.serverNotice(0, tooLong))),
                "server_message", boundary, true);

        String maximumNativeString = "a".repeat(65535);
        assertDecoded(receive(nativePacket(() -> PacketCreator.serverNotice(1, maximumNativeString))),
                "server_message", "a".repeat(1024), true);
    }

    @Test
    void rejectsNativeLengthClaimsBeyondRemainingBytesWithoutLeakingBody() {
        List<Packet> packets = List.of(
                nativePacket(() -> PacketCreator.getChatText(1, "private-body", false, 0)),
                nativePacket(() -> PacketCreator.sendHint("private-body", 100, 20)),
                nativePacket(() -> PacketCreator.serverNotice(0, "private-body")),
                nativePacket(() -> PacketCreator.serverMessage("private-body")));
        int[] stringLengthOffsets = {7, 2, 3, 4};
        for (int index = 0; index < packets.size(); index++) {
            byte[] malformed = packets.get(index).getBytes().clone();
            malformed[stringLengthOffsets[index]] = (byte) 0xFF;
            malformed[stringLengthOffsets[index] + 1] = (byte) 0xFF;
            assertMalformed(receive(() -> malformed));
        }
        assertEquals(4L, client.packetEvents(0, 100).get("malformed_events_total"));
    }

    @Test
    void strictlyRejectsInvalidGbkInsteadOfReplacingUndecodableBytes() {
        List<Packet> packets = List.of(
                nativePacket(() -> PacketCreator.getChatText(1, "A", false, 0)),
                nativePacket(() -> PacketCreator.sendHint("A", 100, 20)),
                nativePacket(() -> PacketCreator.serverNotice(0, "A")));
        int[] textOffsets = {9, 4, 5};
        for (int index = 0; index < packets.size(); index++) {
            byte[] malformed = packets.get(index).getBytes().clone();
            malformed[textOffsets[index]] = (byte) 0x81; // GBK lead byte with no second byte inside the string.
            assertMalformed(receive(() -> malformed));
        }
        assertEquals(3L, client.packetEvents(0, 100).get("malformed_events_total"));
    }

    @Test
    void decodingUsesActorLanguageAndValidatesBytesBeyondTextTruncationBoundary() {
        client.setLanguage(2);
        assertDecoded(receive(nativePacket(() -> PacketCreator.serverNotice(0, "ASCII message"))),
                "server_message", "ASCII message", false);
        byte[] invalidAscii = nativePacket(() -> PacketCreator.serverNotice(0, "A")).getBytes().clone();
        invalidAscii[5] = (byte) 0xE9;
        assertMalformed(receive(() -> invalidAscii));

        client.setLanguage(3);
        byte[] invalidBeyondLimit = nativePacket(() -> PacketCreator.serverNotice(0, "中".repeat(1024) + "A")).getBytes().clone();
        invalidBeyondLimit[invalidBeyondLimit.length - 1] = (byte) 0x81;
        assertMalformed(receive(() -> invalidBeyondLimit));
    }

    @Test
    void requiresNativeTailFieldsAndRejectsTrailingPayload() {
        List<Packet> packets = List.of(
                nativePacket(() -> PacketCreator.getChatText(1, "private-body", false, 0)),
                nativePacket(() -> PacketCreator.sendHint("private-body", 100, 20)),
                nativePacket(() -> PacketCreator.serverNotice(3, 1, "private-body", true)),
                nativePacket(() -> PacketCreator.serverNotice(6, "private-body")),
                nativePacket(() -> PacketCreator.serverNotice(7, "private-body", 1_012_000)));
        for (Packet packet : packets) {
            byte[] truncated = Arrays.copyOf(packet.getBytes(), packet.getBytes().length - 1);
            assertMalformed(receive(() -> truncated));
        }
        byte[] trailing = nativePacket(() -> PacketCreator.serverNotice(0, "private-body")).getBytes();
        byte[] extra = Arrays.copyOf(trailing, trailing.length + 1);
        assertMalformed(receive(() -> extra));
    }

    @Test
    void rejectsInvalidBooleanBytesForEachNativeBooleanField() {
        List<Packet> packets = List.of(
                nativePacket(() -> PacketCreator.getChatText(1, "private-body", true, 0)),
                nativePacket(() -> PacketCreator.sendHint("private-body", 100, 20)),
                nativePacket(() -> PacketCreator.serverMessage("private-body")),
                nativePacket(() -> PacketCreator.serverNotice(3, 1, "private-body", true)));
        for (int index = 0; index < packets.size(); index++) {
            byte[] malformed = packets.get(index).getBytes().clone();
            int flag = switch (index) { case 0 -> 6; case 2 -> 3; default -> malformed.length - 1; };
            malformed[flag] = 2;
            assertMalformed(receive(() -> malformed));
        }
    }

    @Test
    void unsupportedNoticeOpcodeAndNpcDialogueExposeOnlyFixedSummaries() {
        Map<String, Object> unsupportedType = receive(nativePacket(() -> PacketCreator.serverNotice(11, "private-body")));
        assertSummary(unsupportedType, "unsupported", "unsupported_server_message_type");

        byte[] unknown = nativePacket(() -> PacketCreator.serverNotice(0, "private-body")).getBytes().clone();
        unknown[0] = (byte) 0xFF;
        unknown[1] = (byte) 0xFF;
        assertSummary(receive(() -> unknown), "unsupported", "unsupported_opcode");

        Map<String, Object> npc = receive(nativePacket(() -> PacketCreator.getNPCTalk(
                1_012_000, (byte) 0, "private-body", "00 01", (byte) 0)));
        assertEquals(0x130, npc.get("opcode"));
        assertSummary(npc, "unsupported", "unsupported_npc_message_type");
        assertEquals(0L, client.packetEvents(0, 100).get("malformed_events_total"));
    }

    @Test
    void emptyOrUnreadablePacketsRemainBoundedSummariesAndNeverInterruptTransport() {
        assertDoesNotThrow(() -> client.sendPacket(null));
        assertDoesNotThrow(() -> client.sendPacket(() -> null));
        assertDoesNotThrow(() -> client.sendPacket(() -> new byte[0]));
        assertDoesNotThrow(() -> client.sendPacket(() -> new byte[1]));
        assertDoesNotThrow(() -> client.sendPacket(() -> { throw new IllegalStateException("private-body"); }));
        for (Map<String, Object> event : items(client.packetEvents(0, 100))) assertMalformed(event);
        assertEquals(5L, client.packetEvents(0, 100).get("malformed_events_total"));
    }

    @Test
    void legacyPacketSummariesNeverIncludeDecodedTextOrMutablePayload() {
        Packet packet = nativePacket(() -> PacketCreator.getChatText(42, "private-body", false, 0));
        byte[] mutable = packet.getBytes().clone();
        Map<String, Object> received = receive(() -> mutable);
        Arrays.fill(mutable, (byte) 0);
        assertDecoded(received, "chat_text", "private-body", false);
        assertEquals("private-body", items(client.packetEvents(0, 100)).getFirst().get("text"));
        Map<String, Object> summary = client.packetSummaries().getFirst();
        assertEquals(Set.of("sequence", "opcode", "length"), summary.keySet());
        assertFalse(summary.toString().contains("private-body"));
        assertThrows(UnsupportedOperationException.class, () -> received.put("text", "changed"));
    }

    @Test
    void advertisesOnlyTheImplementedNativePacketDecoders() {
        Map<String, Object> capabilities = AcceptanceClient.packetObservationCapabilities();
        assertEquals(256, capabilities.get("capacity"));
        assertEquals(1024, capabilities.get("maximum_text_chars"));
        assertEquals("session_packet_sequence", capabilities.get("cursor_scope"));
        assertEquals(List.of(
                Map.of("event_type", "chat_text", "opcode", 0xA2),
                Map.of("event_type", "player_hint", "opcode", 0xD6),
                Map.of("event_type", "server_message", "opcode", 0x44,
                        "message_types", List.of(0, 1, 2, 3, 4, 5, 6, 7)),
                Map.of("event_type", "yellow_tip", "opcode", 0x4D),
                Map.of("event_type", "npc_dialogue", "opcode", 0x130,
                        "message_types", List.of(4, 12))), capabilities.get("decoded_packets"));
    }

    private Packet nativePacket(Supplier<Packet> factory) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        try {
            return factory.get();
        } finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        }
    }

    private Map<String, Object> receive(Packet packet) {
        long before = client.latestPacketSequence();
        assertDoesNotThrow(() -> client.sendPacket(packet));
        List<Map<String, Object>> events = items(client.packetEvents(before, 100));
        assertEquals(1, events.size());
        Map<String, Object> event = events.getFirst();
        assertEquals(before + 1, event.get("sequence"));
        assertDoesNotThrow(() -> Instant.parse((String) event.get("received_at")));
        return event;
    }

    private void assertDecoded(Map<String, Object> event, String eventType, String text, boolean truncated) {
        assertEquals(eventType, event.get("event_type"));
        assertEquals("decoded", event.get("decode_status"));
        assertEquals("player_visible", event.get("information_level"));
        assertEquals(text, event.get("text"));
        assertEquals(truncated, event.get("text_truncated"));
        assertFalse(event.containsKey("payload"));
        assertFalse(event.containsKey("raw"));
        assertFalse(event.containsKey("hex"));
    }

    private void assertSummary(Map<String, Object> event, String status, String reason) {
        assertEquals("packet_summary", event.get("event_type"));
        assertEquals(status, event.get("decode_status"));
        assertEquals(reason, event.get("reason"));
        assertEquals(SUMMARY_FIELDS, event.keySet());
        assertFalse(event.toString().contains("private-body"));
        assertDoesNotThrow(() -> Instant.parse((String) event.get("received_at")));
    }

    private void assertMalformed(Map<String, Object> event) {
        assertTrue(MALFORMED_REASONS.contains(event.get("reason")), () -> "unexpected fixed reason: " + event.get("reason"));
        assertSummary(event, "malformed", (String) event.get("reason"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> window) {
        return (List<Map<String, Object>>) window.get("items");
    }
}
