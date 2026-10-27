package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.PlayerStorage;
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
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.ChatEvidence;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
    private MockedStatic<ActorAccountOwnership> ownership;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private CharacterCommandLane.ControlPermit permit;
    private ManualExecutor executor;
    private NativeActionResult lastNativeResult;
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
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        PlayerStorage worldPlayers = mock(PlayerStorage.class);
        PlayerStorage channelPlayers = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
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
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        map = spy(new MapleMap(10000, 0, 1, 10000, 1f));
        when(character.getMap()).thenReturn(map);
        int mapId = map.getId();
        when(character.getMapId()).thenReturn(mapId);
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
        ownership = mockStatic(ActorAccountOwnership.class);
        ownership.when(() -> ActorAccountOwnership.owns(handle.ownership(), client)).thenReturn(true);
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(client);
        scope = new ExecutionScope(WorldEpoch.snapshot().token(),
                new ExecutionScope.OwnerStamp(new Object(), new Object()), new ExecutionScope.Limits(16, 0, 16, 0));
        executor = new ManualExecutor();
        lane = new CharacterCommandLane(8, 1, executor);
        permit = lane.bindGrantedPermit(scope, handle.characterRef(), lane.declareGrant(scope), () -> { });
    }

    @AfterEach
    void releaseStaticFixtures() {
        try {
            if (scope != null) scope.beginDrain();
            if (executor != null) executor.drain();
            if (scope != null) assertEquals(ExecutionScope.DrainResult.DRAINED, scope.awaitDrained(Duration.ZERO));
        } finally {
            assertAll("release native chat fixtures",
                    () -> { if (lane != null) lane.close(); },
                    () -> { if (executor != null) executor.shutdown(); },
                    () -> { WorldEpoch.onWorldStopping(); ThreadLocalUtil.removeCurrentClient(); },
                    () -> { if (ownership != null) ownership.close(); },
                    () -> { if (chatLogger != null) chatLogger.close(); },
                    () -> { if (serverSingleton != null) serverSingleton.close(); });
        }
    }

    @Test
    void realNativeHandlerBroadcastsOriginalGbkTextBackThroughTheMapAndCharacter() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        String text = "  中文聊天验收 native-echo-123  ";

        Map<String, Object> outcome = chat(text);

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
        Map<String, Object> outcome = chat(text);
        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(text, packetItems().getFirst().get("text"));
        assertEquals(127, ((String) packetItems().getFirst().get("text")).length());
        assertActorUntouched();
    }

    @Test
    void native200MillisecondSpamBoundaryIsPreservedWithoutSleepOrTimestampReset() {
        assertOutcome(chat("第一条"), "SUCCEEDED", null, "native_chat_broadcast", true);
        clock.set(1199);
        Map<String, Object> rejected = chat("太快的第二条");
        assertOutcome(rejected, "FAILED", "native_chat_not_broadcast", "native_chat_not_broadcast", false);
        assertEquals(1L, result(rejected).get("packet_cursor_before"));
        assertEquals(2L, result(rejected).get("packet_cursor_after"));
        assertEquals(1000L, autoban.getLastSpam(7));
        assertEquals(1L, packetItems().stream().filter(event -> "chat_text".equals(event.get("event_type"))).count());
        clock.set(1200);
        assertOutcome(chat("正常间隔第二条"), "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(1200L, autoban.getLastSpam(7));
        assertEquals(2L, packetItems().stream().filter(event -> "chat_text".equals(event.get("event_type"))).count());
        assertActorUntouched();
    }

    @Test
    void nativeMutedMapProducesOnlyItsRealNoticeAndDoesNotPretendChatWasBroadcast() {
        map.setMuted(true);
        Map<String, Object> outcome = chat("禁言图内的普通发言");
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

        Map<String, Object> outcome = chat(text);

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
        doThrow(new IllegalStateException("private-native-exception")).when(map).isMuted();
        Map<String, Object> outcome = chat("结果未知");
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
        Map<String, Object> outcome = chat(text);
        assertOutcome(outcome, "FAILED", "native_chat_not_broadcast", "native_chat_not_broadcast", false);
        assertEquals(1L, result(outcome).get("packet_cursor_before"));
        assertNull(result(outcome).get("echo_sequence"));
        assertActorUntouched();
    }

    @Test
    void otherSendersWrongTextWrongShowAndHintsCannotProveTheActorEcho() throws Exception {
        // Keep the actor's real scene registration while this controlled recipient omits its echo.
        doNothing().when(character).sendPacket(any(Packet.class));
        String text = "精确回声";
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            client.sendPacket(PacketCreator.getChatText(42, text, false, 0));
            client.sendPacket(PacketCreator.getChatText(41, text + "改写", false, 0));
            client.sendPacket(PacketCreator.getChatText(41, text, false, 1));
            client.sendPacket(PacketCreator.sendHint(text, 100, 20));
            return null;
        });
        Map<String, Object> outcome = chat(text);
        assertOutcome(outcome, "FAILED", "native_chat_not_broadcast", "native_chat_not_broadcast", false);
        assertEquals(4L, result(outcome).get("packet_cursor_after"));
        assertActorUntouched();
    }

    @Test
    void losingTheNecessaryWindowWithoutEchoLeavesAnUnknownOutcome() throws Exception {
        doNothing().when(character).sendPacket(any(Packet.class));
        String text = "窗口丢失";
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            for (int index = 0; index < 300; index++) client.sendPacket(PacketCreator.serverMessage("并行公告" + index));
            return null;
        });
        Map<String, Object> outcome = chat(text);
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

        Map<String, Object> outcome = chat("保留下来的原生回声");

        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(301L, result(outcome).get("echo_sequence"));
        assertEquals(true, client.packetEvents(0, 500).get("cursor_expired"));
        assertActorUntouched();
    }

    @Test
    void unreadablePacketWindowDoesNotInventABroadcastFailure() {
        doThrow(new IllegalStateException("private-observer-exception")).when(client).packetEvents(anyLong(), anyInt());
        Map<String, Object> outcome = chat("观察失败但可能已广播");
        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals(1L, result(outcome).get("packet_cursor_after"));
        assertFalse(outcome.toString().contains("private-observer-exception"));
        assertActorUntouched();
    }

    @Test
    void unavailableLatestCursorRemainsNullInsteadOfBecomingAnInventedSequence() {
        doThrow(new IllegalStateException("private-observer-exception")).when(client).packetEvents(anyLong(), anyInt());
        doReturn(0L).doThrow(new IllegalStateException("private-cursor-exception")).when(client).latestPacketSequence();
        Map<String, Object> outcome = chat("未知游标");
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
        Map<String, Object> outcome = chat("先证明回声再遇到观察异常");
        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(1L, result(outcome).get("echo_sequence"));
        assertActorUntouched();
    }

    @Test
    void malformedPotentialChatPacketCannotProveThatNoBroadcastHappened() throws Exception {
        doNothing().when(character).sendPacket(any(Packet.class));
        String text = "坏聊天包";
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            client.sendPacket(() -> new byte[]{(byte) 0xA2, 0});
            return null;
        });
        Map<String, Object> outcome = chat(text);
        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals("malformed", packetItems().getFirst().get("decode_status"));
        assertActorUntouched();
    }

    @Test
    void nativeValidationRejectionHappensBeforeTheHandlerStarts() throws Exception {
        nativeLoggedIn(false);
        Map<String, Object> rejected = chat("未登录");
        assertEquals("FAILED", rejected.get("status"));
        assertEquals("actor_native_packet_state_rejected", rejected.get("error_reason"));
        assertEquals(false, result(rejected).get("native_dispatch_started"));
        assertEquals(0L, client.latestPacketSequence());
        assertEquals(0L, autoban.getLastSpam(7));
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        verifyNoInteractions(accounts, characters, notes);
    }

    @Test
    void closedReceptionBeforeNativeBroadcastDoesNotTurnAMissingEchoIntoNegativeProof() {
        doAnswer(call -> {
            client.closeSession();
            return false;
        }).when(map).isMuted();

        Map<String, Object> outcome = chat("接收关闭后原生广播仍可能发生");

        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals(ChatEvidence.Completeness.PARTIAL, lastNativeResult.evidence().completeness());
        Map<String, Object> rawWindow = client.packetEvents(0, 500);
        assertEquals(false, rawWindow.get("cursor_expired"));
        assertEquals(false, rawWindow.get("truncated"));
        assertEquals(List.of(), rawWindow.get("items"));
        assertEquals(1000L, autoban.getLastSpam(7));
        assertActorUntouched(true);
    }

    @Test
    void closingReceptionAfterTheRealEchoKeepsItsPositiveFactWithoutFakingWindowLoss() {
        String text = "先收到回声再关闭接收";
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            client.closeSession();
            return null;
        });

        Map<String, Object> outcome = chat(text);

        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(ChatEvidence.Completeness.PARTIAL, lastNativeResult.evidence().completeness());
        assertEquals(1L, result(outcome).get("echo_sequence"));
        Map<String, Object> rawWindow = client.packetEvents(0, 500);
        assertEquals(false, rawWindow.get("cursor_expired"));
        assertEquals(false, rawWindow.get("truncated"));
        assertEquals(1, packetItems(rawWindow).size());
        assertActorUntouched(true);
    }

    @Test
    void aMapChangeAfterBroadcastDoesNotEraseTheExactClientsReceivedPacket() {
        String text = "广播后正常换图的事实";
        MapleMap nextMap = new MapleMap(10001, 0, 1, 10001, 1f);
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            when(character.getMap()).thenReturn(nextMap);
            return null;
        });

        Map<String, Object> outcome = chat(text);

        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertSame(nextMap, character.getMap());
        assertEquals(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, lastNativeResult.evidence().source());
        assertEquals("acceptance:-503", lastNativeResult.evidence().sourceId());
        assertActorUntouched();
    }

    @Test
    void aWorldStopAfterBroadcastRecordsTheOldExactClientFactWithoutAuthorizingAnotherAction() {
        String text = "停服前已广播的事实";
        String acceptedEpoch = handle.characterRef().epoch().value();
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            WorldEpoch.onWorldStopping();
            return null;
        });

        Map<String, Object> outcome = chat(text);

        assertOutcome(outcome, "SUCCEEDED", null, "native_chat_broadcast", true);
        assertEquals(acceptedEpoch, lastNativeResult.evidence().epoch());
        assertFalse(scope.isActive());
        assertThrows(ExecutionScope.Invalid.class, scope::acceptWork);
        assertActorUntouched();
    }

    @Test
    void losingTheExactPublishedOwnerAfterBroadcastDoesNotReadAnotherClientsEvidence() {
        String text = "旧接收者不可借新归属取证";
        Client replacement = mock(Client.class);
        chatLogger.when(() -> ChatLogger.log(client, "General", text)).thenAnswer(call -> {
            ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(replacement);
            return null;
        });

        Map<String, Object> outcome = chat(text);

        assertOutcome(outcome, "INDETERMINATE", "native_chat_outcome_unknown", "native_chat_outcome_unknown", false);
        assertEquals(ChatEvidence.Completeness.UNAVAILABLE, lastNativeResult.evidence().completeness());
        assertEquals(1L, client.latestPacketSequence());
        verifyNoInteractions(replacement);
        assertActorUntouched();
    }

    @Test
    void anElevatedCharacterCannotTurnOrdinaryChatIntoAGmCommand() {
        when(character.gmLevel()).thenReturn(2);
        ObservationException failure = assertThrows(ObservationException.class, () -> chat("!warp 100000000"));
        assertEquals("actor_session_stale", failure.reason());
        assertEquals(0L, client.latestPacketSequence());
        assertActorUntouched();
    }

    private Map<String, Object> chat(String text) {
        ExecutionScope.WorkHandle work = scope.acceptWork();
        NativeActionService.ActionHandle action;
        try {
            action = backend.prepareChat(handle, lane, permit, work, new NativeChatAction(text));
        } catch (RuntimeException failure) {
            work.reject();
            throw failure;
        }
        NativeActionService.SubmissionReceipt receipt = backend.submitChat(action);
        assertEquals("ENQUEUED", receipt.status().name());
        executor.drain();
        NativeActionResult nativeResult = action.result().toCompletableFuture().join();
        lastNativeResult = nativeResult;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", nativeResult.outcome().name());
        response.put("error_reason", AcceptanceChatResults.errorReason(nativeResult));
        response.put("result", AcceptanceChatResults.resultFields(nativeResult));
        return response;
    }

    /** Queue acceptance stays asynchronous; the test drives native statics on their owning thread. */
    private static final class ManualExecutor extends AbstractExecutorService
            implements CharacterCommandLane.SerialBoundedExecutor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        private boolean shutdown;
        @Override public int commandQueueCapacity() { return 8; }
        @Override public void execute(Runnable command) {
            if (shutdown || queued.size() >= commandQueueCapacity()) throw new RejectedExecutionException();
            queued.addLast(command);
        }
        void drain() { while (!queued.isEmpty()) queued.removeFirst().run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = List.copyOf(queued);
            queued.clear();
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queued.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private void assertActorUntouched() { assertActorUntouched(false); }

    private void assertActorUntouched(boolean transportClosed) {
        assertSame(character, client.getPlayer());
        assertEquals(17, client.getAccID());
        assertTrue(client.isLoggedIn());
        assertEquals(transportClosed, client.transportClosed());
        assertNull(handle.closeEvidence());
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        verify(backend, never()).close(any(), any());
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
