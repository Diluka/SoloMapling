package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.model.pojo.NextLevelContext;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestActionManager;
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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native handlers and transport with a controlled script boundary for failure and evidence cases. */
class NativeActorNpcOutcomeTest {
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private AcceptanceClient client;
    private Character character;
    private AccountService accounts;
    private CharacterService characters;
    private NoteService notes;
    private NPCScriptManager scripts;
    private QuestScriptManager quests;
    private NPCConversationManager cm;
    private AtomicReference<NPCConversationManager> current;
    private MockedStatic<NPCScriptManager> scriptSingleton;
    private MockedStatic<QuestScriptManager> questSingleton;
    private MockedStatic<GameConfig> config;

    @BeforeEach
    void controlledScriptBoundaryWithoutDatasource() throws Exception {
        CharacterTestContext.install();
        config = mockStatic(GameConfig.class);
        scripts = mock(NPCScriptManager.class);
        quests = mock(QuestScriptManager.class);
        scriptSingleton = mockStatic(NPCScriptManager.class);
        scriptSingleton.when(NPCScriptManager::getInstance).thenReturn(scripts);
        questSingleton = mockStatic(QuestScriptManager.class);
        questSingleton.when(QuestScriptManager::getInstance).thenReturn(quests);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        client = spy(new AcceptanceClient(Client.Type.CHANNEL, -504L, "127.0.0.104", world, channel));
        client.setLanguage(3);
        client.setAccID(17);
        Field loggedIn = Client.class.getDeclaredField("loggedIn");
        loggedIn.setAccessible(true);
        loggedIn.set(client, true);
        character = mock(Character.class);
        when(character.getId()).thenReturn(41);
        client.setPlayer(character);
        current = new AtomicReference<>();
        when(scripts.getCM(client)).thenAnswer(call -> current.get());
        cm = mock(NPCConversationManager.class);
        when(cm.getNpc()).thenReturn(9900001);
        when(cm.getNextLevelContext()).thenReturn(new NextLevelContext());
        accounts = mock(AccountService.class);
        characters = mock(CharacterService.class);
        notes = mock(NoteService.class);
        backend = spy(new NativeActorBackend(mock(ActorProfiles.class), accounts, characters, notes));
        handle = new NativeActorHandle("primary", 17, client, character, world, channel,
                mock(ActorAccountOwnership.Lease.class), false, false);
        doReturn(true).when(backend).isCurrent(handle);
    }

    @AfterEach
    void releaseMocks() {
        ThreadLocalUtil.removeCurrentClient();
        if (questSingleton != null) questSingleton.close();
        if (scriptSingleton != null) scriptSingleton.close();
        if (config != null) config.close();
    }

    @Test
    void mtsConfigurationAndExistingNpcOrQuestDialogueRejectBeforeNativeDispatch() {
        config.when(() -> GameConfig.getServerBoolean("use_mts")).thenReturn(true);
        assertRejected("native_center_mts_enabled", () -> backend.npcCenterOpen(handle));
        config.when(() -> GameConfig.getServerBoolean("use_mts")).thenReturn(false);
        current.set(cm);
        assertRejected("native_dialogue_already_active", () -> backend.npcCenterOpen(handle));
        current.set(null);
        when(quests.getQM(client)).thenReturn(mock(QuestActionManager.class));
        assertRejected("native_dialogue_already_active", () -> backend.npcCenterOpen(handle));
        verify(scripts, never()).start(any(), anyInt(), any(Character.class));
        assertEquals(0L, client.latestPacketSequence());
        assertActorUntouched();
    }

    @Test
    void anActiveCmWithoutANewPacketIsAnExplicitFailure() {
        onOpen(() -> current.set(cm));
        Map<String, Object> outcome = backend.npcCenterOpen(handle);
        assertOutcome(outcome, "FAILED", "native_center_not_opened", true, false);
        assertNull(result(outcome).get("dialogue_version"));
        assertEquals(true, result(outcome).get("active"));
        assertActorUntouched();
    }

    @Test
    void anActualPacketWhoseScriptAlreadyDisposedCannotProveAnActiveDialogue() {
        onOpen(() -> {
            publishCenter();
            current.set(null);
        });
        Map<String, Object> outcome = backend.npcCenterOpen(handle);
        assertOutcome(outcome, "FAILED", "native_center_not_opened", true, false);
        assertEquals(false, result(outcome).get("active"));
        assertEquals(1L, result(outcome).get("packet_sequence"));
        assertActorUntouched();
    }

    @Test
    void nativeFailureAfterTheCenterPacketInvalidatesItsSourceAndLeavesTheOutcomeUnknown() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        onOpen(() -> {
            publishCenter();
            throw new IllegalStateException("private-native-exception");
        });
        Map<String, Object> outcome = backend.npcCenterOpen(handle);
        // #110 requires an exception-free controlled source, beyond #75's packet/CM observation.
        assertOutcome(outcome, "INDETERMINATE", "native_center_outcome_unknown", true, false);
        assertEquals(true, result(outcome).get("native_handler_error"));
        assertEquals(1L, result(outcome).get("dialogue_version"));
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertFalse(outcome.toString().contains("private-native-exception"));
        assertEquals(false, client.dialogueState().get("active"));
        assertActorUntouched();
    }

    @Test
    void unsupportedNewNpcPacketInvalidatesTheEarlierCenterVersion() {
        onOpen(() -> {
            publishCenter();
            client.sendPacket(PacketCreator.getNPCTalk(9900001, (byte) 0, "未知新页", "00 01", (byte) 0));
        });
        Map<String, Object> outcome = backend.npcCenterOpen(handle);
        assertOutcome(outcome, "INDETERMINATE", "native_center_outcome_unknown", true, false);
        assertEquals(2L, client.dialogueState().get("dialogue_version"));
        assertEquals(false, client.dialogueState().get("supported"));
        assertActorUntouched();
    }

    @Test
    void missingPacketWindowCannotPretendNoCenterWasOpened() {
        onOpen(this::publishCenter);
        doThrow(new IllegalStateException("private-observer-exception")).when(client).packetEvents(anyLong(), anyInt());
        Map<String, Object> outcome = backend.npcCenterOpen(handle);
        assertOutcome(outcome, "INDETERMINATE", "native_center_outcome_unknown", true, false);
        assertEquals(true, result(outcome).get("active"));
        assertEquals(1L, result(outcome).get("packet_cursor_after"));
        assertFalse(outcome.toString().contains("private-observer-exception"));
        assertActorUntouched();
    }

    @Test
    void cancelUsesOnlyTheNativeFixedMoreTalkPayloadAndVerifiesBothMappingsAreEmpty() {
        publishCenterWithThreadLocal();
        doAnswer(call -> { current.set(null); return null; }).when(scripts)
                .action(client, (byte) -1, (byte) 4, -1);
        Map<String, Object> outcome = backend.npcCancel(handle, 1L);
        assertOutcome(outcome, "SUCCEEDED", "dialogue_cancelled", true, true);
        assertEquals(false, result(outcome).get("active"));
        assertEquals(1L, result(outcome).get("dialogue_version"));
        verify(scripts, times(1)).action(client, (byte) -1, (byte) 4, -1);
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertNull(client.dialogueState().get("dialogue_version"));
        assertActorUntouched();
    }

    @Test
    void cancelWhichLeavesTheOriginalCmActiveIsAnExplicitFailure() {
        publishCenterWithThreadLocal();
        Map<String, Object> outcome = backend.npcCancel(handle, 1L);
        assertOutcome(outcome, "FAILED", "native_dialogue_not_cancelled", true, false);
        assertEquals(true, result(outcome).get("active"));
        assertSame(cm, client.getCM());
        assertEquals(1L, client.dialogueState().get("dialogue_version"));
        assertActorUntouched();
    }

    @Test
    void cancelExceptionAfterEmptyMappingsCannotEraseTheCleanupFact() {
        publishCenterWithThreadLocal();
        doAnswer(call -> {
            current.set(null);
            throw new IllegalStateException("private-native-exception");
        }).when(scripts).action(client, (byte) -1, (byte) 4, -1);
        Map<String, Object> outcome = backend.npcCancel(handle, 1L);
        assertOutcome(outcome, "SUCCEEDED", "dialogue_cancelled", true, true);
        assertEquals(true, result(outcome).get("native_handler_error"));
        assertFalse(outcome.toString().contains("private-native-exception"));
        assertActorUntouched();
    }

    @Test
    void cancelExceptionBeforeCleanupRemainsIndeterminateAndKeepsTheCm() {
        publishCenterWithThreadLocal();
        doThrow(new IllegalStateException("private-native-exception")).when(scripts)
                .action(client, (byte) -1, (byte) 4, -1);
        Map<String, Object> outcome = backend.npcCancel(handle, 1L);
        assertOutcome(outcome, "INDETERMINATE", "native_dialogue_outcome_unknown", true, false);
        assertSame(cm, client.getCM());
        assertActorUntouched();
    }

    @Test
    void aStaleVersionNeverDispatchesAnAnswerToTheNewCurrentDialogue() {
        publishCenterWithThreadLocal();
        publishCenterWithThreadLocal();
        ObservationException error = assertThrows(ObservationException.class, () -> backend.npcCancel(handle, 1));
        assertEquals(HttpStatus.GONE, error.status());
        assertEquals("stale_dialogue", error.reason());
        verify(scripts, never()).action(any(), anyByte(), anyByte(), anyInt());
        assertSame(cm, client.getCM());
        assertEquals(2L, client.dialogueState().get("dialogue_version"));
        assertActorUntouched();
    }

    private void onOpen(Runnable action) {
        when(scripts.start(eq(client), eq(9900001), isNull(Character.class))).thenAnswer(call -> {
            action.run();
            return true;
        });
    }

    private void publishCenterWithThreadLocal() {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        try { client.dispatchNativeCenter(this::publishCenter); }
        finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        }
    }

    private void publishCenter() {
        current.set(cm);
        client.sendPacket(PacketCreator.getNPCTalk(9900001, (byte) 4, "不进入结果的中心正文", "", (byte) 0));
    }

    private static void assertRejected(String reason, Runnable action) {
        ObservationException error = assertThrows(ObservationException.class, action::run);
        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals(reason, error.reason());
    }

    private static void assertOutcome(Map<String, Object> outcome, String status, String name, boolean started, boolean cleaned) {
        assertEquals(status, outcome.get("status"));
        assertEquals("SUCCEEDED".equals(status) ? null : name, outcome.get("error_reason"));
        Map<String, Object> result = result(outcome);
        assertEquals(name, result.get("observed_outcome"));
        assertEquals(started, result.get("native_dispatch_started"));
        assertEquals(cleaned, result.get("native_dialogue_cleanup_verified"));
        assertEquals(9900001, result.get("npc_template_id"));
        assertEquals(4, result.get("message_type"));
        assertFalse(outcome.toString().contains("不进入结果的中心正文"));
    }

    private void assertActorUntouched() {
        assertSame(character, client.getPlayer());
        assertTrue(client.isLoggedIn());
        assertFalse(client.transportClosed());
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        verify(backend, never()).close(any());
        verifyNoInteractions(accounts, characters, notes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> outcome) {
        return (Map<String, Object>) outcome.get("result");
    }
}
