package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.ChatLogger;
import org.gms.server.maps.MapleMap;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.NoteService;
import org.gms.soloacceptance.ObservationException;
import org.gms.util.PacketCreator;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real native handler, map broadcast and player transport; no login, database or sockets. */
class NativeActorChatTest {
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private AcceptanceClient client;
    private Character character;
    private MapleMap map;
    private AutobanManager autoban;
    private AtomicLong clock;
    private MockedStatic<Server> serverSingleton;
    private MockedStatic<ChatLogger> chatLogger;
    private AccountService accounts;
    private CharacterService characters;
    private NoteService notes;

    @BeforeEach
    void nativeChatFixtureWithoutDatasource() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        Server server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        clock = new AtomicLong(1000);
        when(server.getCurrentTime()).thenAnswer(call -> clock.get());
        serverSingleton = mockStatic(Server.class);
        serverSingleton.when(Server::getInstance).thenReturn(server);
        chatLogger = mockStatic(ChatLogger.class);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        client = spy(new AcceptanceClient(Client.Type.CHANNEL, -503L, "127.0.0.103", world, channel));
        client.setAccID(17);
        client.setLanguage(3);
        nativeLoggedIn(true);
        character = mock(Character.class);
        doCallRealMethod().when(character).setClient(any());
        when(character.getClient()).thenCallRealMethod();
        doCallRealMethod().when(character).sendPacket(any(Packet.class));
        doCallRealMethod().when(character).dropMessage(anyInt(), anyString());
        character.setClient(client);
        when(character.getId()).thenReturn(41);
        when(character.getAccountId()).thenReturn(17);
        when(character.getName()).thenReturn("McpPrimary");
        client.setPlayer(character);
        map = new MapleMap(10000, 0, 1, 10000, 1f);
        when(character.getMap()).thenReturn(map);
        when(character.getMapId()).thenReturn(map.getId());
        nativeMapCharacters().add(character);
        autoban = new AutobanManager(character);
        when(character.getAutoBanManager()).thenReturn(autoban);
        accounts = mock(AccountService.class);
        characters = mock(CharacterService.class);
        notes = mock(NoteService.class);
        backend = spy(new NativeActorBackend(mock(ActorProfiles.class), accounts, characters, notes, () -> server));
        handle = new NativeActorHandle("primary", 17, client, character, world, channel,
                mock(ActorAccountOwnership.Lease.class), false, false);
        // Native session ownership is covered by its own tests; isolate chat from native login SQL here.
        doReturn(true).when(backend).isCurrent(handle);
    }

    @AfterEach
    void releaseStaticFixtures() {
        WorldEpoch.onWorldStopping();
        ThreadLocalUtil.removeCurrentClient();
        if (chatLogger != null) chatLogger.close();
        if (serverSingleton != null) serverSingleton.close();
    }

    @Test
    void realNativeHandlerBroadcastsOriginalGbkTextBackThroughTheMapAndCharacter() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        String text = "  中文聊天验收 native-echo-123  ";

        Map<String, Object> outcome = backend.chat(handle, text);

        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        Map<String, Object> result = result(outcome);
        assertEquals(0L, result.get("packet_cursor_before"));
        assertEquals(1L, result.get("packet_cursor_after"));
        assertEquals(1L, result.get("echo_sequence"));
        Map<String, Object> echo = packetItems().getFirst();
        assertEquals(0xA2, echo.get("opcode"));
        assertEquals("chat_text", echo.get("event_type"));
        assertEquals(41, echo.get("sender_character_id"));
        assertEquals(text, echo.get("text"));
        assertEquals(0, echo.get("show"));
        assertEquals(false, echo.get("text_truncated"));
        assertEquals(1000L, autoban.getLastSpam(7));
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertFalse(outcome.toString().contains(text));
        assertActorUntouched();
    }

    @Test
    void native127CharacterRuleUsesUtf16CharactersRatherThanGbkPayloadBytes() {
        String text = "中".repeat(127);
        Map<String, Object> outcome = backend.chat(handle, text);
        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(text, packetItems().getFirst().get("text"));
        assertEquals(127, ((String) packetItems().getFirst().get("text")).length());
        assertActorUntouched();
    }

    @Test
    void native200MillisecondSpamBoundaryIsPreservedWithoutSleepOrTimestampReset() {
        assertOutcome(backend.chat(handle, "第一条"), "SUCCEEDED", null, "native_chat_broadcast", true);
        clock.set(1199);
        Map<String, Object> rejected = backend.chat(handle, "太快的第二条");
        assertOutcome(rejected, "FAILED", "native_chat_not_broadcast", "native_chat_not_broadcast", false);
        assertEquals(1L, result(rejected).get("packet_cursor_before"));
        assertEquals(2L, result(rejected).get("packet_cursor_after"));
        assertEquals(1000L, autoban.getLastSpam(7));
        assertEquals(1L, packetItems().stream().filter(event -> "chat_text".equals(event.get("event_type"))).count());
        clock.set(1200);
        assertOutcome(backend.chat(handle, "正常间隔第二条"), "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(1200L, autoban.getLastSpam(7));
        assertEquals(2L, packetItems().stream().filter(event -> "chat_text".equals(event.get("event_type"))).count());
        assertActorUntouched();
    }

    @Test
    void nativeMutedMapProducesOnlyItsRealNoticeAndDoesNotPretendChatWasBroadcast() {
        map.setMuted(true);
        Map<String, Object> outcome = backend.chat(handle, "禁言图内的普通发言");
        assertOutcome(outcome, "FAILED", "native_chat_not_broadcast", "native_chat_not_broadcast", false);
        assertEquals(0L, autoban.getLastSpam(7));
        assertEquals(1, packetItems().size());
        assertEquals("server_message", packetItems().getFirst().get("event_type"));
        assertEquals(5, packetItems().getFirst().get("message_type"));
        assertActorUntouched();
    }

    @Test
    void exceptionAfterRealBroadcastCannotEraseTheObservedEcho() {
        String text = "日志异常之前已经广播";
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        chatLogger.when(() -> ChatLogger.log(client, "General", text))
                .thenThrow(new IllegalStateException("private-native-exception"));

        Map<String, Object> outcome = backend.chat(handle, text);

        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(true, result(outcome).get("native_handler_error"));
        assertEquals(1L, result(outcome).get("echo_sequence"));
        assertEquals(0L, autoban.getLastSpam(7));
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertFalse(outcome.toString().contains("private-native-exception"));
        assertFalse(outcome.toString().contains(text));
        assertActorUntouched();
    }

    @Test
    void exceptionWithoutEchoIsIndeterminateAndDoesNotDisconnectOrRetry() {
        when(character.getMap()).thenThrow(new IllegalStateException("private-native-exception"));
        Map<String, Object> outcome = backend.chat(handle, "结果未知");
        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals(true, result(outcome).get("native_handler_error"));
        assertEquals(0L, result(outcome).get("packet_cursor_after"));
        assertEquals(List.of(), packetItems());
        assertFalse(outcome.toString().contains("private-native-exception"));
        assertActorUntouched();
    }

    @Test
    void aMatchingHistoricalEchoCannotProveANewNativeBroadcast() {
        String text = "历史回声";
        withClient(() -> map.broadcastMessage(PacketCreator.getChatText(41, text, false, 0)));
        map.setMuted(true);
        Map<String, Object> outcome = backend.chat(handle, text);
        assertOutcome(outcome, "FAILED", "native_chat_not_broadcast", "native_chat_not_broadcast", false);
        assertEquals(1L, result(outcome).get("packet_cursor_before"));
        assertNull(result(outcome).get("echo_sequence"));
        assertActorUntouched();
    }

    @Test
    void otherSendersWrongTextWrongShowAndHintsCannotProveTheActorEcho() throws Exception {
        nativeMapCharacters().clear(); // The real handler broadcasts, but the actor has no map recipient entry.
        String text = "精确回声";
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            client.sendPacket(PacketCreator.getChatText(42, text, false, 0));
            client.sendPacket(PacketCreator.getChatText(41, text + "改写", false, 0));
            client.sendPacket(PacketCreator.getChatText(41, text, false, 1));
            client.sendPacket(PacketCreator.sendHint(text, 100, 20));
            return null;
        });
        Map<String, Object> outcome = backend.chat(handle, text);
        assertOutcome(outcome, "FAILED", "native_chat_not_broadcast", "native_chat_not_broadcast", false);
        assertEquals(4L, result(outcome).get("packet_cursor_after"));
        assertActorUntouched();
    }

    @Test
    void losingTheNecessaryWindowWithoutEchoLeavesAnUnknownOutcome() throws Exception {
        nativeMapCharacters().clear();
        String text = "窗口丢失";
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            for (int index = 0; index < 300; index++) client.sendPacket(PacketCreator.serverMessage("并行公告" + index));
            return null;
        });
        Map<String, Object> outcome = backend.chat(handle, text);
        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals(300L, result(outcome).get("packet_cursor_after"));
        assertEquals(true, client.packetEvents(0, 500).get("cursor_expired"));
        assertActorUntouched();
    }

    @Test
    void aRetainedRealNativeEchoProvesSuccessEvenIfEarlierUnrelatedPacketsWereEvicted() throws Exception {
        Character other = mock(Character.class);
        when(other.getClient()).thenReturn(mock(Client.class));
        doAnswer(call -> {
            for (int index = 0; index < 300; index++) client.sendPacket(PacketCreator.serverMessage("并行公告" + index));
            return null;
        }).when(other).sendPacket(any(Packet.class));
        nativeMapCharacters().clear();
        nativeMapCharacters().add(other);
        nativeMapCharacters().add(character);

        Map<String, Object> outcome = backend.chat(handle, "保留下来的原生回声");

        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(301L, result(outcome).get("echo_sequence"));
        assertEquals(true, client.packetEvents(0, 500).get("cursor_expired"));
        assertActorUntouched();
    }

    @Test
    void unreadablePacketWindowDoesNotInventABroadcastFailure() {
        doThrow(new IllegalStateException("private-observer-exception")).when(client).packetEvents(anyLong(), anyInt());
        Map<String, Object> outcome = backend.chat(handle, "观察失败但可能已广播");
        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals(1L, result(outcome).get("packet_cursor_after"));
        assertFalse(outcome.toString().contains("private-observer-exception"));
        assertActorUntouched();
    }

    @Test
    void unavailableLatestCursorRemainsNullInsteadOfBecomingAnInventedSequence() {
        doThrow(new IllegalStateException("private-observer-exception")).when(client).packetEvents(anyLong(), anyInt());
        doReturn(0L).doThrow(new IllegalStateException("private-cursor-exception")).when(client).latestPacketSequence();
        Map<String, Object> outcome = backend.chat(handle, "未知游标");
        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertNull(result(outcome).get("packet_cursor_after"));
        assertFalse(outcome.toString().contains("private-cursor-exception"));
        assertActorUntouched();
    }

    @Test
    void evidenceFailureAfterAlreadyMatchingARealEchoKeepsTheBroadcastFact() {
        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> original = (Map<String, Object>) call.callRealMethod();
            List<Map<String, Object>> items = new ArrayList<>(packetItems(original));
            items.add(Map.of("sequence", "unreadable"));
            Map<String, Object> changed = new LinkedHashMap<>(original);
            changed.put("items", items);
            return changed;
        }).when(client).packetEvents(anyLong(), anyInt());
        Map<String, Object> outcome = backend.chat(handle, "先证明回声再遇到观察异常");
        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(1L, result(outcome).get("echo_sequence"));
        assertActorUntouched();
    }

    @Test
    void malformedPotentialChatPacketCannotProveThatNoBroadcastHappened() throws Exception {
        nativeMapCharacters().clear();
        String text = "坏聊天包";
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            client.sendPacket(() -> new byte[]{(byte) 0xA2, 0});
            return null;
        });
        Map<String, Object> outcome = backend.chat(handle, text);
        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals("malformed", packetItems().getFirst().get("decode_status"));
        assertActorUntouched();
    }

    @Test
    void nativeValidationRejectionHappensBeforeTheHandlerStarts() throws Exception {
        nativeLoggedIn(false);
        ObservationException failure = assertThrows(ObservationException.class, () -> backend.chat(handle, "未登录"));
        assertEquals("actor_native_packet_state_rejected", failure.reason());
        assertEquals(0L, client.latestPacketSequence());
        assertEquals(0L, autoban.getLastSpam(7));
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        verifyNoInteractions(accounts, characters, notes);
    }

    @Test
    void anElevatedCharacterCannotTurnOrdinaryChatIntoAGmCommand() {
        when(character.gmLevel()).thenReturn(2);
        ObservationException failure = assertThrows(ObservationException.class, () -> backend.chat(handle, "!warp 100000000"));
        assertEquals("actor_session_stale", failure.reason());
        assertEquals(0L, client.latestPacketSequence());
        assertActorUntouched();
    }

    private void assertActorUntouched() {
        assertSame(character, client.getPlayer());
        assertEquals(17, client.getAccID());
        assertTrue(client.isLoggedIn());
        assertFalse(client.transportClosed());
        assertNull(handle.closeEvidence());
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        verify(backend, never()).close(any());
        verifyNoInteractions(accounts, characters, notes);
    }

    private static void assertOutcome(Map<String, Object> outcome, String status, String reason,
                                      String observedOutcome, boolean matched) {
        assertEquals(status, outcome.get("status"));
        assertEquals(reason, outcome.get("error_reason"));
        Map<String, Object> result = result(outcome);
        assertEquals(observedOutcome, result.get("observed_outcome"));
        assertEquals(true, result.get("native_dispatch_started"));
        assertEquals(matched, result.get("echo_matched"));
        assertEquals(41, result.get("sender_character_id"));
        if (!matched) assertNull(result.get("echo_sequence"));
        assertFalse(result.containsKey("text"));
        assertFalse(result.containsKey("message"));
        assertFalse(result.containsKey("payload"));
    }

    private void nativeLoggedIn(boolean loggedIn) throws Exception {
        Field field = Client.class.getDeclaredField("loggedIn");
        field.setAccessible(true);
        field.set(client, loggedIn);
    }

    @SuppressWarnings("unchecked")
    private Collection<Character> nativeMapCharacters() throws Exception {
        Field field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(map);
    }

    private void withClient(Runnable action) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        try { action.run(); }
        finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        }
    }

    private List<Map<String, Object>> packetItems() { return packetItems(client.packetEvents(0, 500)); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> packetItems(Map<String, Object> window) {
        return (List<Map<String, Object>>) window.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> outcome) {
        return (Map<String, Object>) outcome.get("result");
    }
}
