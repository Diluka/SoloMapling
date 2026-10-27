package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.constants.game.NextLevelType;
import org.gms.model.pojo.NextLevelContext;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestScriptManager;
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
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real packet handlers with controlled script results; no native login, datasource or gameplay mutation. */
class NativeActorNpcReplyOutcomeTest {
    private static final String ROOT_TEXT = "结果不保留根正文#L0#新人福利#l#L71#万能传送#l";
    private static final String CHILD_TEXT = "结果不保留子正文";
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private AcceptanceClient client;
    private AccountService accounts;
    private CharacterService characters;
    private NoteService notes;
    private NPCScriptManager scripts;
    private NPCConversationManager root;
    private AtomicReference<NPCConversationManager> current;
    private MockedStatic<NPCScriptManager> scriptSingleton;
    private MockedStatic<QuestScriptManager> questSingleton;
    private MockedStatic<GameConfig> config;

    @BeforeEach
    void trustedNativeCenterWithoutDatasource() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        config = mockStatic(GameConfig.class);
        scripts = mock(NPCScriptManager.class);
        scriptSingleton = mockStatic(NPCScriptManager.class);
        scriptSingleton.when(NPCScriptManager::getInstance).thenReturn(scripts);
        QuestScriptManager quests = mock(QuestScriptManager.class);
        questSingleton = mockStatic(QuestScriptManager.class);
        questSingleton.when(QuestScriptManager::getInstance).thenReturn(quests);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        client = spy(new AcceptanceClient(Client.Type.CHANNEL, -507L, "127.0.0.107", world, channel));
        client.setLanguage(3);
        client.setAccID(17);
        Field loggedIn = Client.class.getDeclaredField("loggedIn");
        loggedIn.setAccessible(true);
        loggedIn.set(client, true);
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(41);
        when(character.gmLevel()).thenReturn(0);
        client.setPlayer(character);
        root = manager(null);
        current = new AtomicReference<>();
        when(scripts.getCM(client)).thenAnswer(call -> current.get());
        when(scripts.start(eq(client), eq(9900001), isNull(Character.class))).thenAnswer(call -> {
            current.set(root);
            publish(4, ROOT_TEXT);
            return true;
        });
        accounts = mock(AccountService.class);
        characters = mock(CharacterService.class);
        notes = mock(NoteService.class);
        backend = spy(new NativeActorBackend(mock(ActorProfiles.class), accounts, characters, notes));
        handle = new NativeActorHandle("primary", 17, client, character, world, channel,
                mock(ActorAccountOwnership.Lease.class), false, false);
        doReturn(true).when(backend).isCurrent(handle);
        assertEquals("SUCCEEDED", backend.npcCenterOpen(handle).get("status"));
        assertEquals("center", client.dialogueState().get("path"));
    }

    @AfterEach
    void noLogoutPersistenceOrHiddenRetry() {
        try {
            verify(client, never()).disconnect(anyBoolean(), anyBoolean());
            verify(backend, never()).close(any());
            verify(scripts, never()).dispose(any(Client.class));
            verify(scripts, never()).dispose(any(Client.class), anyBoolean());
            verifyNoInteractions(accounts, characters, notes);
        } finally {
            WorldEpoch.onWorldStopping();
            ThreadLocalUtil.removeCurrentClient();
            if (questSingleton != null) questSingleton.close();
            if (scriptSingleton != null) scriptSingleton.close();
            if (config != null) config.close();
        }
    }

    @Test
    void selectionNeedsANewTrustedNativePacketAndConsumesOnlyTheSubmittedVersion() {
        long version = version();
        NPCConversationManager welfare = manager("新人福利");
        onLegacySelect(0, () -> { current.set(welfare); publish(12, CHILD_TEXT); });
        Map<String, Object> outcome = backend.npcReply(handle, version, "select", 0);
        assertOutcome(outcome, "SUCCEEDED", "dialogue_replied", true, true);
        assertEquals("welfare", result(outcome).get("dialogue_path"));
        assertEquals(12, result(outcome).get("message_type"));
        assertTrue((long) result(outcome).get("dialogue_version") > version);
        assertSame(welfare, client.getCM());
        assertEquals(true, client.dialogueState().get("active"));
        assertStaleReply(version, "select", 0);
        verify(scripts, times(1)).action(client, (byte) 1, (byte) 4, 0);
    }

    @Test
    void selectWithoutANewPacketIsUnknownAndTheSameVersionCannotBeRetried() {
        long version = version();
        Map<String, Object> outcome = backend.npcReply(handle, version, "select", 0);
        assertUnknown(outcome, true);
        assertNull(result(outcome).get("dialogue_version"));
        assertEquals(true, result(outcome).get("active"));
        assertSame(root, client.getCM());
        assertStaleReply(version, "select", 0);
        verify(scripts, times(1)).action(client, (byte) 1, (byte) 4, 0);
    }

    @Test
    void missingPacketWindowDoesNotConvertARealNewMenuIntoSuccessOrLeakObserverText() {
        long version = version();
        onLegacySelect(0, () -> { current.set(manager("新人福利")); publish(12, CHILD_TEXT); });
        doThrow(new IllegalStateException("private-observer-failure")).when(client).packetEvents(anyLong(), anyInt());
        Map<String, Object> outcome = backend.npcReply(handle, version, "select", 0);
        assertUnknown(outcome, true);
        assertFalse(outcome.toString().contains("private-observer-failure"));
        assertStaleReply(version, "select", 0);
        verify(scripts, times(1)).action(client, (byte) 1, (byte) 4, 0);
    }

    @Test
    void anExpiredWindowCannotProveTheReplyOutcome() { assertIncompleteWindow("cursor_expired"); }

    @Test
    void aTruncatedWindowCannotProveTheReplyOutcome() { assertIncompleteWindow("truncated"); }

    @Test
    void matchingNamedCmReplacementWithoutAnotherPacketIsNotTheTrustedNewDialogue() {
        long version = version();
        onLegacySelect(0, () -> {
            current.set(manager("新人福利"));
            publish(12, CHILD_TEXT);
            current.set(manager("新人福利"));
        });
        Map<String, Object> outcome = backend.npcReply(handle, version, "select", 0);
        assertUnknown(outcome, true);
        assertEquals(true, result(outcome).get("active"));
        assertEquals(false, client.dialogueState().get("active"));
        assertStaleReply(version, "select", 0);
    }

    @Test
    void exceptionAfterANewPacketInvalidatesItsSourceAndDoesNotRestoreTheConsumedVersion() {
        long version = version();
        onLegacySelect(0, () -> {
            current.set(manager("新人福利"));
            publish(12, CHILD_TEXT);
            throw new IllegalStateException("private-native-failure");
        });
        Map<String, Object> outcome = backend.npcReply(handle, version, "select", 0);
        assertUnknown(outcome, true);
        assertEquals(true, result(outcome).get("native_handler_error"));
        assertEquals(false, client.dialogueState().get("active"));
        assertFalse(outcome.toString().contains("private-native-failure"));
        assertStaleReply(version, "select", 0);
        verify(scripts, times(1)).action(client, (byte) 1, (byte) 4, 0);
    }

    @Test
    void consumedReplyRejectedByNativeValidateStateIsUnknownEvenBeforeHandlerStarted() {
        long version = version();
        doReturn(false).when(client).isLoggedIn();
        Map<String, Object> outcome = backend.npcReply(handle, version, "select", 0);
        assertUnknown(outcome, false);
        assertFalse(result(outcome).containsKey("native_handler_error"));
        assertStaleReply(version, "select", 0);
        verify(scripts, never()).action(any(), anyByte(), anyByte(), anyInt());
    }

    @Test
    void consumedCancelRejectedByNativeValidateStateIsUnknownWithoutManualCleanup() {
        long version = version();
        doReturn(false).when(client).isLoggedIn();
        Map<String, Object> outcome = backend.npcCancel(handle, version);
        assertOutcome(outcome, "INDETERMINATE", "native_dialogue_outcome_unknown", false, true);
        assertSame(root, client.getCM());
        assertStaleCancel(version);
        verify(scripts, never()).action(any(), anyByte(), anyByte(), anyInt());
    }

    @Test
    void declinedWelfareNeedsItsActualNativeTypeZeroPacketAndBothMappingsEmpty() {
        long version = openWelfare();
        doAnswer(call -> { publish(0, CHILD_TEXT); current.set(null); return null; }).when(scripts)
                .action(client, (byte) 0, (byte) 12, -1);
        Map<String, Object> outcome = backend.npcReply(handle, version, "decline", null);
        assertOutcome(outcome, "SUCCEEDED", "dialogue_declined", true, true);
        assertEquals(true, result(outcome).get("native_dialogue_cleanup_verified"));
        assertEquals(false, result(outcome).get("active"));
        assertEquals("closed", result(outcome).get("dialogue_path"));
        assertNull(result(outcome).get("message_type")); // Type 0 remains an explicit unsupported summary.
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertStaleReply(version, "decline", null);
        verify(scripts, times(1)).action(client, (byte) 0, (byte) 12, -1);
    }

    @Test
    void declineWithoutANewPacketStaysUnknownEvenWhenMappingsAreEmpty() {
        long version = openWelfare();
        doAnswer(call -> { current.set(null); return null; }).when(scripts)
                .action(client, (byte) 0, (byte) 12, -1);
        Map<String, Object> outcome = backend.npcReply(handle, version, "decline", null);
        assertUnknown(outcome, true);
        assertEquals(true, result(outcome).get("native_dialogue_cleanup_verified"));
        assertStaleReply(version, "decline", null);
        verify(scripts, times(1)).action(client, (byte) 0, (byte) 12, -1);
    }

    @Test
    void malformedNpcOutputCannotProveDeclineEvenWhenMappingsWereCleared() {
        long version = openWelfare();
        doAnswer(call -> {
            client.sendPacket(() -> new byte[]{0x30, 0x01, 4});
            current.set(null);
            return null;
        }).when(scripts).action(client, (byte) 0, (byte) 12, -1);
        Map<String, Object> outcome = backend.npcReply(handle, version, "decline", null);
        assertUnknown(outcome, true);
        assertStaleReply(version, "decline", null);
    }

    @Test
    void anUnknownNpcMessageTypeCannotBeMistakenForTheReviewedTypeZeroDecline() {
        long version = openWelfare();
        doAnswer(call -> { publish(11, CHILD_TEXT); current.set(null); return null; }).when(scripts)
                .action(client, (byte) 0, (byte) 12, -1);
        Map<String, Object> outcome = backend.npcReply(handle, version, "decline", null);
        assertUnknown(outcome, true);
        assertStaleReply(version, "decline", null);
    }

    @Test
    void reviewedNextLevelSelectionAndCancelUseTheirCurrentNativeRoutes() {
        long rootVersion = version();
        NPCConversationManager transport = manager("万能传送");
        onLegacySelect(71, () -> {
            current.set(transport);
            publish(4, CHILD_TEXT + "#L2#城镇传送#l");
            transport.getNextLevelContext().setLevelType(NextLevelType.SEND_SELECT);
            transport.getNextLevelContext().setPrefix("");
        });
        assertEquals("SUCCEEDED", backend.npcReply(handle, rootVersion, "select", 71).get("status"));
        long categoryVersion = version();
        doAnswer(call -> {
            publish(4, CHILD_TEXT + "#L1#射手村#l");
            transport.getNextLevelContext().setLevelType(NextLevelType.SEND_NEXT_SELECT);
            transport.getNextLevelContext().setNextLevel("Town");
            return null;
        }).when(scripts).nextLevel(client, (byte) 1, (byte) 4, 2);
        Map<String, Object> selected = backend.npcReply(handle, categoryVersion, "select", 2);
        assertOutcome(selected, "SUCCEEDED", "dialogue_replied", true, true);
        assertEquals("transport_town", result(selected).get("dialogue_path"));
        long townVersion = version();
        doAnswer(call -> { current.set(null); return null; }).when(scripts)
                .nextLevel(client, (byte) 0, (byte) 4, -1);
        Map<String, Object> cancelled = backend.npcCancel(handle, townVersion);
        assertOutcome(cancelled, "SUCCEEDED", "dialogue_cancelled", true, true);
        assertEquals(true, result(cancelled).get("native_dialogue_cleanup_verified"));
        verify(scripts, times(1)).action(client, (byte) 1, (byte) 4, 71);
        verify(scripts, times(1)).nextLevel(client, (byte) 1, (byte) 4, 2);
        verify(scripts, times(1)).nextLevel(client, (byte) 0, (byte) 4, -1);
        assertStaleCancel(townVersion);
    }

    private void assertIncompleteWindow(String flag) {
        long version = version();
        onLegacySelect(0, () -> { current.set(manager("新人福利")); publish(12, CHILD_TEXT); });
        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> actual = (Map<String, Object>) call.callRealMethod();
            Map<String, Object> incomplete = new LinkedHashMap<>(actual);
            incomplete.put(flag, true);
            return incomplete;
        }).when(client).packetEvents(anyLong(), anyInt());
        Map<String, Object> outcome = backend.npcReply(handle, version, "select", 0);
        assertUnknown(outcome, true);
        assertStaleReply(version, "select", 0);
    }

    private long openWelfare() {
        long version = version();
        onLegacySelect(0, () -> { current.set(manager("新人福利")); publish(12, CHILD_TEXT); });
        assertEquals("SUCCEEDED", backend.npcReply(handle, version, "select", 0).get("status"));
        return version();
    }

    private void onLegacySelect(int selection, Runnable action) {
        doAnswer(call -> { action.run(); return null; }).when(scripts)
                .action(client, (byte) 1, (byte) 4, selection);
    }

    private NPCConversationManager manager(String script) {
        NPCConversationManager manager = mock(NPCConversationManager.class);
        when(manager.getNpc()).thenReturn(9900001);
        when(manager.getScriptName()).thenReturn(script);
        when(manager.getNextLevelContext()).thenReturn(new NextLevelContext());
        return manager;
    }

    private void publish(int type, String text) {
        client.sendPacket(PacketCreator.getNPCTalk(9900001, (byte) type, text, type == 0 ? "00 00" : "", (byte) 0));
    }

    private long version() { return (long) client.dialogueState().get("dialogue_version"); }

    private void assertStaleReply(long version, String kind, Integer selection) {
        ObservationException error = assertThrows(ObservationException.class,
                () -> backend.npcReply(handle, version, kind, selection));
        assertEquals(HttpStatus.GONE, error.status());
        assertEquals("stale_dialogue", error.reason());
    }

    private void assertStaleCancel(long version) {
        ObservationException error = assertThrows(ObservationException.class, () -> backend.npcCancel(handle, version));
        assertEquals(HttpStatus.GONE, error.status());
        assertEquals("stale_dialogue", error.reason());
    }

    private void assertUnknown(Map<String, Object> outcome, boolean started) {
        assertOutcome(outcome, "INDETERMINATE", "native_dialogue_reply_outcome_unknown", started, true);
        assertNull(result(outcome).get("dialogue_path"));
    }

    private void assertOutcome(Map<String, Object> outcome, String status, String observed, boolean started, boolean consumed) {
        assertEquals(status, outcome.get("status"));
        assertEquals("SUCCEEDED".equals(status) ? null : observed, outcome.get("error_reason"));
        assertEquals(observed, result(outcome).get("observed_outcome"));
        assertEquals(started, result(outcome).get("native_dispatch_started"));
        assertEquals(consumed, result(outcome).get("reply_version_consumed"));
        assertFalse(outcome.toString().contains(ROOT_TEXT));
        assertFalse(outcome.toString().contains(CHILD_TEXT));
        assertFalse(result(outcome).containsKey("text"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> outcome) {
        return (Map<String, Object>) outcome.get("result");
    }
}
