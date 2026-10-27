package org.gms.soloacceptance.actor;

import org.gms.client.Client;
import org.gms.constants.game.NextLevelType;
import org.gms.model.pojo.NextLevelContext;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.quest.QuestActionManager;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeDialogueTrackerTest {
    private static final Set<String> STATE_KEYS = Set.of("dialogue_version", "active", "supported", "entry_type",
            "npc_template_id", "message_type", "packet_sequence");
    private AcceptanceClient client;
    private NPCConversationManager cm;

    @BeforeEach
    void nativeContextWithoutDatasource() {
        CharacterTestContext.install();
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        client = spy(new AcceptanceClient(Client.Type.CHANNEL, -503L, "127.0.0.103", world, channel));
        client.setLanguage(3);
        cm = mock(NPCConversationManager.class);
        when(cm.getNpc()).thenReturn(9900001);
        when(cm.getNextLevelContext()).thenReturn(new NextLevelContext());
        doReturn(cm).when(client).getCM();
        doReturn(null).when(client).getQM();
    }

    @Test
    void decodesRealNativeCenterMenuAndPublishesOnlyCurrentImmutableMetadata() {
        assertEmptyState();
        sendMenu("#e欢迎来到北斗脚本中心#n\r\n#L0#新人福利#l");
        Map<String, Object> event = latestEvent();
        assertEquals("npc_dialogue", event.get("event_type"));
        assertEquals("decoded", event.get("decode_status"));
        assertEquals("player_visible", event.get("information_level"));
        assertEquals(304, event.get("opcode"));
        assertEquals(4, event.get("speaker_type"));
        assertEquals(9900001, event.get("npc_template_id"));
        assertEquals(4, event.get("message_type"));
        assertEquals(0, event.get("speaker"));
        assertEquals("#e欢迎来到北斗脚本中心#n\r\n#L0#新人福利#l", event.get("text"));
        assertEquals(false, event.get("text_truncated"));
        Map<String, Object> state = client.dialogueState();
        assertEquals(STATE_KEYS, state.keySet());
        assertEquals(1L, state.get("dialogue_version"));
        assertEquals(1L, state.get("packet_sequence"));
        assertEquals(true, state.get("supported"));
        assertEquals(true, state.get("active"));
        assertEquals("native_ui_center", state.get("entry_type"));
        assertEquals(9900001, state.get("npc_template_id"));
        assertEquals(4, state.get("message_type"));
        assertDoesNotThrow(() -> client.requireNativeCenterDialogue(1));
        assertThrows(UnsupportedOperationException.class, () -> state.put("cm", cm));
        assertFalse(state.toString().contains("新人福利"));
        assertFalse(state.containsValue(cm));
    }

    @Test
    void currentCmIdentityAndQuestManagerDetermineActivityRatherThanPacketHistory() {
        sendMenu("首屏");
        doReturn(null).when(client).getCM(); // Native scripts can send a menu and immediately dispose.
        assertEquals(false, client.dialogueState().get("active"));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
        assertEquals("首屏", latestEvent().get("text"));
        doReturn(mock(NPCConversationManager.class)).when(client).getCM();
        assertEquals(false, client.dialogueState().get("active"));
        doReturn(cm).when(client).getCM();
        doReturn(mock(QuestActionManager.class)).when(client).getQM();
        assertEquals(false, client.dialogueState().get("active"));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
    }

    @Test
    void contextChangesAreRejectedAndNativeGetterFailuresDoNotEscapeDiagnosticsOrSendPacket() {
        sendMenu("首屏");
        cm.getNextLevelContext().setLevelType(NextLevelType.SEND_SELECT);
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
        when(cm.getNextLevelContext()).thenThrow(new IllegalStateException("private-native-error"));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
        doThrow(new IllegalStateException("private-native-error")).when(client).getCM();
        assertEquals(false, client.dialogueState().get("active"));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
        assertDoesNotThrow(() -> sendMenu("后续首屏"));
        assertEquals(2L, client.dialogueState().get("dialogue_version"));
        assertEquals(false, client.dialogueState().get("active"));
        doReturn(cm).when(client).getCM();
        assertEquals(false, client.dialogueState().get("active")); // A failed capture cannot attach a later CM.
        doThrow(new IllegalStateException("private-quest-error")).when(client).getQM();
        assertDoesNotThrow(() -> sendMenu("Quest getter失败"));
        assertEquals(false, client.dialogueState().get("active"));
        assertFalse(client.dialogueState().toString().contains("private"));
    }

    @Test
    void unsupportedOrMalformedNpcPacketsInvalidateOldVersionButOrdinaryPacketsDoNot() {
        sendMenu("首屏");
        client.sendPacket(nativePacket(() -> PacketCreator.serverNotice(0, "普通公告")));
        assertDoesNotThrow(() -> client.requireNativeCenterDialogue(1));
        client.sendPacket(nativePacket(() -> PacketCreator.getNPCTalk(9900001, (byte) 0,
                "private-body", "00 01", (byte) 0)));
        assertUnsupported(3L, "unsupported_npc_message_type");
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(1));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(3));
        sendMenu("新首屏");
        client.sendPacket(() -> new byte[]{0x30, 0x01, 4});
        assertEquals(5L, client.dialogueState().get("dialogue_version"));
        assertEquals(false, client.dialogueState().get("active"));
        assertEquals(false, client.dialogueState().get("supported"));
        assertNull(client.dialogueState().get("npc_template_id"));
        assertEquals("malformed", latestEvent().get("decode_status"));
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(4));
        assertFalse(latestEvent().containsKey("text"));
    }

    @Test
    void onlyFixedCenterSpeakerIsActionableEvenWhenOtherTypeFourPacketsDecode() {
        client.sendPacket(nativePacket(() -> PacketCreator.getNPCTalk(1012000, (byte) 4,
                "出租车菜单", "", (byte) 0)));
        assertEquals("npc_dialogue", latestEvent().get("event_type"));
        assertEquals(false, client.dialogueState().get("supported"));
        assertEquals("unsupported", client.dialogueState().get("entry_type"));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
        client.sendPacket(nativePacket(() -> PacketCreator.getNPCTalk(9900001, (byte) 4,
                "玩家发言", "", (byte) 2)));
        assertEquals(2, latestEvent().get("speaker"));
        assertEquals(false, client.dialogueState().get("active"));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(2));
    }

    @Test
    void capturedManagerMustBeTheDefaultCenterScriptRatherThanANamedOrItemScript() {
        sendMenu("中心模板的菜单");
        when(cm.getScriptName()).thenReturn("新人福利");
        assertEquals(false, client.dialogueState().get("active"));
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
        when(cm.getScriptName()).thenReturn(null);
        when(cm.getNpc()).thenReturn(1012000);
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
        when(cm.getNpc()).thenReturn(9900001);
        when(cm.isItemScript()).thenReturn(true);
        assertError(HttpStatus.CONFLICT, "dialogue_not_active", () -> client.requireNativeCenterDialogue(1));
    }

    @Test
    void npcLayoutRequiresNativeHeaderCompleteStrictEncodingAndNoTail() {
        Packet menu = menu("A");
        for (int length = 2; length < menu.getBytes().length; length++) {
            byte[] truncated = Arrays.copyOf(menu.getBytes(), length);
            client.sendPacket(() -> truncated);
            assertEquals("malformed", latestEvent().get("decode_status"));
            assertFalse(latestEvent().containsKey("text"));
        }
        byte[] trailing = Arrays.copyOf(menu.getBytes(), menu.getBytes().length + 1);
        client.sendPacket(() -> trailing);
        assertEquals("packet_trailing_bytes", latestEvent().get("reason"));
        byte[] invalidGbk = menu.getBytes().clone();
        invalidGbk[invalidGbk.length - 1] = (byte) 0x81;
        client.sendPacket(() -> invalidGbk);
        assertEquals("packet_text_encoding_invalid", latestEvent().get("reason"));
        byte[] invalidLength = menu.getBytes().clone();
        invalidLength[9] = (byte) 0xff;
        invalidLength[10] = (byte) 0xff;
        client.sendPacket(() -> invalidLength);
        assertEquals("packet_string_length_invalid", latestEvent().get("reason"));
        byte[] unsupportedSpeakerType = menu.getBytes().clone();
        unsupportedSpeakerType[2] = 1;
        client.sendPacket(() -> unsupportedSpeakerType);
        assertEquals("unsupported_npc_speaker_type", latestEvent().get("reason"));
        assertFalse(latestEvent().containsKey("text"));
        client.setLanguage(2);
        byte[] invalidAscii = menu.getBytes().clone();
        invalidAscii[invalidAscii.length - 1] = (byte) 0xe9;
        client.sendPacket(() -> invalidAscii);
        assertEquals("packet_text_encoding_invalid", latestEvent().get("reason"));
    }

    @Test
    void truncatedNpcTextIsExplicitAndUnicodeBoundaryNeverSplitsASurrogatePair() {
        sendMenu("中".repeat(1024) + "文");
        assertEquals("中".repeat(1024), latestEvent().get("text"));
        assertEquals(true, latestEvent().get("text_truncated"));
        assertEquals(true, client.dialogueState().get("active"));
        assertDoesNotThrow(() -> client.requireNativeCenterDialogue(1));
        String complete = "a".repeat(1023) + "\uD83D\uDE42";
        byte[] text = (complete + "中").getBytes(StandardCharsets.UTF_8);
        byte[] header = menu("").getBytes();
        ByteBuffer bytes = ByteBuffer.allocate(11 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(header, 0, 9).putShort((short) text.length).put(text);
        Map<String, Object> decoded = PacketEventDecoder.decode(bytes.array(), StandardCharsets.UTF_8);
        assertEquals(complete, decoded.get("text"));
        assertEquals(true, decoded.get("text_truncated"));
        byte[] invalidAfterLimit = menu("中".repeat(1024) + "A").getBytes().clone();
        invalidAfterLimit[invalidAfterLimit.length - 1] = (byte) 0x81;
        client.sendPacket(() -> invalidAfterLimit);
        assertEquals("packet_text_encoding_invalid", latestEvent().get("reason"));
        assertEquals(false, client.dialogueState().get("active"));
    }

    @Test
    void invalidationAndCloseKeepOnlyOneEventBufferAndCannotReuseOldVersions() {
        sendMenu("首屏");
        client.invalidateDialogue();
        assertEmptyState();
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(1));
        sendMenu("新首屏");
        assertDoesNotThrow(() -> client.requireNativeCenterDialogue(2));
        for (int i = 0; i < 256; i++) client.sendPacket(nativePacket(() -> PacketCreator.serverNotice(0, "公告")));
        assertEquals(256, items(client.packetEvents(0, 500)).size());
        assertEquals(2L, client.droppedPacketCount());
        assertEquals(2L, client.dialogueState().get("dialogue_version"));
        assertDoesNotThrow(() -> client.requireNativeCenterDialogue(2));
        client.closeSession();
        assertEmptyState();
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(2));
        client.sendPacket(() -> { fail("closed transport must not evaluate packet"); return null; });
        assertEquals(258L, client.latestPacketSequence());
    }

    @Test
    void slowOldCaptureCannotOverwriteNewUnknownNpcVersionAndDoesNotHoldPacketLock() throws Exception {
        CountDownLatch capturing = new CountDownLatch(1);
        CountDownLatch finishCapture = new CountDownLatch(1);
        doAnswer(invocation -> { capturing.countDown(); await(finishCapture); return cm; }).when(client).getCM();
        Packet old = menu("旧首屏");
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> client.sendPacket(old));
            assertTrue(capturing.await(5, TimeUnit.SECONDS));
            try {
                Future<?> newer = workers.submit(() -> {
                    assertEquals(1, items(client.packetEvents(0, 10)).size());
                    client.sendPacket(() -> new byte[]{0x30, 0x01, 4});
                    assertEquals(2L, client.dialogueState().get("dialogue_version"));
                });
                newer.get(5, TimeUnit.SECONDS);
            } finally { finishCapture.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2L, client.dialogueState().get("dialogue_version"));
        assertEquals(false, client.dialogueState().get("active"));
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(1));
    }

    @Test
    void slowOldCaptureCannotReplaceANewerMenuEvenWhenBothUseTheSameNativeManager() throws Exception {
        CountDownLatch capturing = new CountDownLatch(1);
        CountDownLatch finishCapture = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) { capturing.countDown(); await(finishCapture); }
            return cm;
        }).when(client).getCM();
        Packet old = menu("旧首屏");
        Packet newer = menu("新首屏");
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> client.sendPacket(old));
            assertTrue(capturing.await(5, TimeUnit.SECONDS));
            try { workers.submit(() -> client.sendPacket(newer)).get(5, TimeUnit.SECONDS); }
            finally { finishCapture.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2L, client.dialogueState().get("dialogue_version"));
        assertEquals(true, client.dialogueState().get("active"));
        assertDoesNotThrow(() -> client.requireNativeCenterDialogue(2));
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(1));
    }

    @Test
    void invalidationBetweenEntryEpochAndCaptureCannotReviveInflightPacket() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        Packet packet = menu("失效中的首屏");
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<?> send = worker.submit(() -> client.sendPacket(() -> {
                reading.countDown(); await(finishRead); return packet.getBytes();
            }));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            try { client.invalidateDialogue(); }
            finally { finishRead.countDown(); }
            send.get(5, TimeUnit.SECONDS);
        }
        assertEmptyState();
        assertEquals(1L, client.latestPacketSequence());
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(1));
    }

    @Test
    void closingDoesNotWaitForNativeGettersAndLateCaptureCannotReviveDialogue() throws Exception {
        CountDownLatch capturing = new CountDownLatch(1);
        CountDownLatch finishCapture = new CountDownLatch(1);
        doAnswer(invocation -> { capturing.countDown(); await(finishCapture); return cm; }).when(client).getCM();
        Packet packet = menu("关闭中的首屏");
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> send = workers.submit(() -> client.sendPacket(packet));
            assertTrue(capturing.await(5, TimeUnit.SECONDS));
            try {
                workers.submit(client::closeSession).get(5, TimeUnit.SECONDS);
                assertEmptyState();
            } finally { finishCapture.countDown(); }
            send.get(5, TimeUnit.SECONDS);
        }
        assertEmptyState();
        assertTrue(client.transportClosed());
        assertError(HttpStatus.GONE, "stale_dialogue", () -> client.requireNativeCenterDialogue(1));
    }

    @Test
    void observingNativeStateDoesNotHoldTrackerLockOrDiscardANewerVersion() throws Exception {
        sendMenu("旧首屏");
        CountDownLatch observing = new CountDownLatch(1);
        CountDownLatch finishObservation = new CountDownLatch(1);
        doAnswer(invocation -> { observing.countDown(); await(finishObservation); return cm; }).when(client).getCM();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Map<String, Object>> oldView = workers.submit(client::dialogueState);
            assertTrue(observing.await(5, TimeUnit.SECONDS));
            try {
                workers.submit(() -> client.sendPacket(() -> new byte[]{0x30, 0x01, 4})).get(5, TimeUnit.SECONDS);
            } finally { finishObservation.countDown(); }
            Map<String, Object> view = oldView.get(5, TimeUnit.SECONDS);
            assertEquals(2L, view.get("dialogue_version"));
            assertEquals(false, view.get("active"));
        }
        assertEquals(2L, client.dialogueState().get("dialogue_version"));
    }

    @Test
    void requireRechecksInvalidationAfterNativeGettersWithoutBlockingTheInvalidation() throws Exception {
        sendMenu("首屏");
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch finishCheck = new CountDownLatch(1);
        doAnswer(invocation -> { checking.countDown(); await(finishCheck); return cm; }).when(client).getCM();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> check = workers.submit(() -> assertError(HttpStatus.GONE, "stale_dialogue",
                    () -> client.requireNativeCenterDialogue(1)));
            assertTrue(checking.await(5, TimeUnit.SECONDS));
            try { workers.submit(client::invalidateDialogue).get(5, TimeUnit.SECONDS); }
            finally { finishCheck.countDown(); }
            check.get(5, TimeUnit.SECONDS);
        }
        assertEmptyState();
    }

    private void assertUnsupported(long version, String reason) {
        assertEquals(version, client.dialogueState().get("dialogue_version"));
        assertEquals(false, client.dialogueState().get("active"));
        assertEquals(false, client.dialogueState().get("supported"));
        assertEquals("unsupported", latestEvent().get("decode_status"));
        assertEquals(reason, latestEvent().get("reason"));
        assertFalse(latestEvent().containsKey("text"));
        assertFalse(latestEvent().toString().contains("private-body"));
    }

    private void assertEmptyState() {
        Map<String, Object> state = client.dialogueState();
        assertEquals(STATE_KEYS, state.keySet());
        assertNull(state.get("dialogue_version"));
        assertNull(state.get("packet_sequence"));
        assertNull(state.get("npc_template_id"));
        assertNull(state.get("message_type"));
        assertEquals(false, state.get("active"));
        assertEquals(false, state.get("supported"));
        assertEquals("unsupported", state.get("entry_type"));
    }

    private static void assertError(HttpStatus status, String reason, Runnable action) {
        ObservationException failure = assertThrows(ObservationException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(reason, failure.reason());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "native_getter_test_timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private void sendMenu(String text) { client.sendPacket(menu(text)); }
    private Packet menu(String text) {
        return nativePacket(() -> PacketCreator.getNPCTalk(9900001, (byte) 4, text, "", (byte) 0));
    }

    private Packet nativePacket(Supplier<Packet> factory) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        try { return factory.get(); }
        finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        }
    }

    private Map<String, Object> latestEvent() { return items(client.packetEvents(0, 500)).getLast(); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> window) {
        return (List<Map<String, Object>>) window.get("items");
    }
}
