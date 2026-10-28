package org.gms.soloacceptance.actor;

import soloMapling.game.execution.ExecutionScope;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.constants.game.NextLevelType;
import org.gms.constants.string.ExtendType;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.server.CashShop;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.NoteService;
import org.gms.soloacceptance.ObservationException;
import org.gms.util.ThreadLocalUtil;
import org.gms.util.ExtendUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real handlers, manager and upstream Chinese script; only filesystem lookup and persistence are isolated. */
class NativeActorNpcScriptTest {
    private static final String SCRIPT_KEY = "scripts/npc/9900001.js";
    private static final Set<String> DIALOGUE_FIELDS = Set.of("dialogue_version", "active", "supported",
            "entry_type", "npc_template_id", "message_type", "packet_sequence");
    private final List<GraalJSScriptEngine> engines = new ArrayList<>();
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private AcceptanceClient client;
    private Character character;
    private CashShop cashShop;
    private AccountService accounts;
    private CharacterService characters;
    private NoteService notes;
    private AtomicLong clock;
    private MockedStatic<Server> serverSingleton;
    private MockedStatic<GameConfig> config;
    private MockedStatic<ExtendUtil> extensions;

    @BeforeEach
    void nativeManagersAndUnmodifiedChineseScriptWithoutDatasource() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        Server server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        clock = new AtomicLong(1000);
        when(server.getCurrentTime()).thenAnswer(call -> clock.get());
        serverSingleton = mockStatic(Server.class);
        serverSingleton.when(Server::getInstance).thenReturn(server);
        config = mockStatic(GameConfig.class);
        config.when(() -> GameConfig.getServerBoolean("use_mts")).thenReturn(false);
        extensions = mockStatic(ExtendUtil.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if (invocation.getMethod().getName().equals("getExtendValue") && arguments.length == 3
                    && "41".equals(arguments[0]) && ExtendType.CHARACTER_EXTEND.getType().equals(arguments[1])
                    && "新人福利礼包".equals(arguments[2])) return null;
            throw new AssertionError("NPC no-reward tests must not call another extension read or write");
        });
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        client = spy(new AcceptanceClient(Client.Type.CHANNEL, -505L, "127.0.0.105", world, channel));
        client.setAccID(17);
        client.setLanguage(3);
        Field loggedIn = Client.class.getDeclaredField("loggedIn");
        loggedIn.setAccessible(true);
        loggedIn.set(client, true);
        character = mock(Character.class);
        when(character.getId()).thenReturn(41);
        when(character.getAccountId()).thenReturn(17);
        when(character.getName()).thenReturn("McpPrimary");
        when(character.getClient()).thenReturn(client);
        when(character.getLevel()).thenReturn(1);
        when(character.getMapId()).thenReturn(10000);
        when(character.getMeso()).thenReturn(606);
        cashShop = mock(CashShop.class);
        when(cashShop.getCash(1)).thenReturn(101);
        when(cashShop.getCash(2)).thenReturn(202);
        when(cashShop.getCash(4)).thenReturn(404);
        when(character.getCashShop()).thenReturn(cashShop);
        client.setPlayer(character);
        accounts = mock(AccountService.class);
        characters = mock(CharacterService.class);
        notes = mock(NoteService.class);
        backend = spy(new NativeActorBackend(mock(ActorProfiles.class), accounts, characters, notes, () -> server));
        handle = new NativeActorHandle("primary", 17, client, character, world, channel,
                mock(ActorAccountOwnership.Lease.class), false, false);
        doReturn(true).when(backend).isCurrent(handle);
        assertNull(client.getCM());
        assertNull(client.getQM());
        cacheOriginalChineseScript();
    }

    @AfterEach
    void disposeOnlyThisClientsNativeMappingsAndRealEngines() {
        try {
            if (client != null) {
                NPCScriptManager.getInstance().dispose(client);
                client.removeScriptEngine(SCRIPT_KEY);
                for (String name : List.of("新人福利", "万能传送")) {
                    client.removeScriptEngine("scripts/npc/" + name + ".js");
                    client.removeScriptEngine("scripts/BeiDouSpecial/" + name + ".js");
                }
                assertNull(client.getCM());
                assertNull(client.getQM());
            }
        } finally {
            ExecutionScope.tryBeginWorldStop();
            ThreadLocalUtil.removeCurrentClient();
            engines.forEach(GraalJSScriptEngine::close);
            if (extensions != null) extensions.close();
            if (config != null) config.close();
            if (serverSingleton != null) serverSingleton.close();
        }
    }

    @Test
    void originalScriptOpensItsRealChineseMenuAndSevenFieldNativeDialogueState() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        Map<String, Object> outcome = backend.npcCenterOpen(handle);
        assertOutcome(outcome, "SUCCEEDED", "center_dialogue_opened");
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        NPCConversationManager cm = client.getCM();
        assertNotNull(cm);
        assertEquals(9900001, cm.getNpc());
        assertNull(cm.getNextLevelContext().getLevelType());
        assertNull(client.getQM());
        Map<String, Object> state = client.dialogueState();
        assertTrue(state.keySet().containsAll(DIALOGUE_FIELDS));
        assertEquals(9, state.size());
        assertEquals("center", state.get("path"));
        assertReplyOptions(true, List.of(3, 69, 70, 71, 4, 2, 0, 1, 72), List.of(0, 71), List.of("select"));
        assertEquals(true, state.get("active"));
        assertEquals(true, state.get("supported"));
        assertEquals("native_ui_center", state.get("entry_type"));
        assertEquals(9900001, state.get("npc_template_id"));
        assertEquals(4, state.get("message_type"));
        assertEquals(state.get("dialogue_version"), state.get("packet_sequence"));
        assertEquals(state.get("dialogue_version"), result(outcome).get("dialogue_version"));
        Map<String, Object> event = packetItems().getFirst();
        assertEquals(0x130, event.get("opcode"));
        assertEquals("npc_dialogue", event.get("event_type"));
        assertEquals("decoded", event.get("decode_status"));
        assertEquals(4, event.get("speaker_type"));
        assertEquals(0, event.get("speaker"));
        assertEquals(false, event.get("text_truncated"));
        String text = (String) event.get("text");
        for (String expected : List.of("脚本中心", "当前点券：101", "当前抵用券：202",
                "当前信用券：404", "当前金币：606", "#L3#", "#L69#", "#L70#", "#L71#",
                "#L4#", "#L2#", "#L0#", "#L1#", "#L72#")) assertTrue(text.contains(expected), expected);
        assertFalse(text.contains("以下内容仅GM可见"));
        assertFalse(outcome.toString().contains(text));
        assertActorRetained();
    }

    @Test
    void normalMoreTalkCancelRemovesNativeCmScriptAndEngineMappings() {
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        long version = dialogueVersion();
        ScriptEngine originalEngine = client.getScriptEngine(SCRIPT_KEY);
        assertNotNull(originalEngine);
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        Map<String, Object> outcome = backend.npcCancel(handle, version);
        assertOutcome(outcome, "SUCCEEDED", "dialogue_cancelled");
        assertEquals(true, result(outcome).get("native_dialogue_cleanup_verified"));
        assertEquals(false, result(outcome).get("active"));
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertNull(client.getScriptEngine(SCRIPT_KEY));
        assertFalse(nativeScriptMappings().containsKey(client));
        assertEquals(false, client.dialogueState().get("active"));
        verify(character).setCS(false);
        verify(character).setNpcCooldown(anyLong());
        verify(character).flushDelayedUpdateQuests();
        assertActorRetained();
    }

    @Test
    void cancelledVersionCannotBeReusedAndDoesNotEmitAnotherPacket() {
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        long version = dialogueVersion();
        assertOutcome(backend.npcCancel(handle, version), "SUCCEEDED", "dialogue_cancelled");
        long after = client.latestPacketSequence();
        assertStaleVersion(version);
        assertEquals(after, client.latestPacketSequence());
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertActorRetained();
    }

    @Test
    void nativeFiveHundredMillisecondClickBoundaryRejects1500AndAllows1501() throws Exception {
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        assertOutcome(backend.npcCancel(handle, dialogueVersion()), "SUCCEEDED", "dialogue_cancelled");
        cacheOriginalChineseScript();
        clock.set(1500);
        assertFalse(client.canClickNPC());
        Map<String, Object> throttled = backend.npcCenterOpen(handle);
        assertOutcome(throttled, "FAILED", "native_center_not_opened");
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertEquals(1L, packetItems().stream().filter(event -> "npc_dialogue".equals(event.get("event_type"))).count());
        clock.set(1501);
        assertTrue(client.canClickNPC());
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        assertEquals(2L, packetItems().stream().filter(event -> "npc_dialogue".equals(event.get("event_type"))).count());
        assertActorRetained();
    }

    @Test
    void reopenedNativeCmRejectsThePreviousVersionWithoutClosingTheNewMenu() throws Exception {
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        long first = dialogueVersion();
        assertOutcome(backend.npcCancel(handle, first), "SUCCEEDED", "dialogue_cancelled");
        clock.set(1501);
        cacheOriginalChineseScript();
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        NPCConversationManager current = client.getCM();
        long second = dialogueVersion();
        assertTrue(second > first);
        long beforeRejected = client.latestPacketSequence();
        assertStaleVersion(first);
        assertSame(current, client.getCM());
        assertEquals(true, client.dialogueState().get("active"));
        assertEquals(beforeRejected, client.latestPacketSequence());
        assertOutcome(backend.npcCancel(handle, second), "SUCCEEDED", "dialogue_cancelled");
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertActorRetained();
    }

    @Test
    void nativeGiftDeclineUsesTypeTwelveThenARealUnsupportedFarewellWithoutReward() throws Exception {
        cacheNamedChineseScript("新人福利");
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        NPCConversationManager root = client.getCM();
        long rootVersion = dialogueVersion();
        assertReplySucceeded(backend.npcReply(handle, rootVersion, "select", 0), "welfare");
        NPCConversationManager gift = client.getCM();
        assertNotNull(gift);
        assertNotSame(root, gift);
        assertEquals("新人福利", gift.getScriptName());
        assertNull(gift.getNextLevelContext().getLevelType());
        long giftVersion = dialogueVersion();
        assertTrue(giftVersion > rootVersion);
        Map<String, Object> prompt = packetItems().getLast();
        assertEquals("npc_dialogue", prompt.get("event_type"));
        assertEquals(12, prompt.get("message_type"));
        assertEquals(false, prompt.get("text_truncated"));
        assertTrue(((String) prompt.get("text")).contains("您确定要领取新手礼包吗"));
        assertReplyOptions(true, List.of(), List.of(), List.of("decline"));
        assertRejectedReplyDoesNotAdvance(rootVersion, "select", 0, "stale_dialogue");
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        assertReplySucceeded(backend.npcReply(handle, giftVersion, "decline", null), "closed");
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertFalse(nativeScriptMappings().containsKey(client));
        List<Map<String, Object>> farewell = packetItems().stream()
                .filter(event -> ((long) event.get("sequence")) > giftVersion
                        && Integer.valueOf(0x130).equals(event.get("opcode"))).toList();
        assertEquals(1, farewell.size());
        assertEquals("unsupported", farewell.getFirst().get("decode_status"));
        assertFalse(farewell.getFirst().containsKey("text"));
        assertStaleVersion(giftVersion);
        verifyGiftWasOnlyRead(1);
        assertActorRetained();
    }

    @Test
    void reopenedNativeGiftCanBeCancelledThroughItsTypeTwelveLegacyRoute() throws Exception {
        cacheNamedChineseScript("新人福利");
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        assertReplySucceeded(backend.npcReply(handle, dialogueVersion(), "select", 0), "welfare");
        long firstVersion = dialogueVersion();
        assertReplySucceeded(backend.npcReply(handle, firstVersion, "decline", null), "closed");
        clock.addAndGet(501);
        cacheOriginalChineseScript();
        cacheNamedChineseScript("新人福利");
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        assertReplySucceeded(backend.npcReply(handle, dialogueVersion(), "select", 0), "welfare");
        NPCConversationManager current = client.getCM();
        assertEquals("新人福利", current.getScriptName());
        assertNull(current.getNextLevelContext().getLevelType());
        long secondVersion = dialogueVersion();
        assertTrue(secondVersion > firstVersion);
        assertStaleVersion(firstVersion);
        assertSame(current, client.getCM());
        long before = client.latestPacketSequence();
        assertOutcome(backend.npcCancel(handle, secondVersion), "SUCCEEDED", "dialogue_cancelled");
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertTrue(packetItems().stream().noneMatch(event -> ((long) event.get("sequence")) > before
                && Integer.valueOf(0x130).equals(event.get("opcode"))));
        verifyGiftWasOnlyRead(2);
        assertActorRetained();
    }

    @Test
    void realNextLevelTownMenuRejectsAllSelectionsWhenTruncatedThenCancelsNormally() throws Exception {
        cacheNamedChineseScript("万能传送");
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        NPCConversationManager root = client.getCM();
        long rootVersion = dialogueVersion();
        assertReplySucceeded(backend.npcReply(handle, rootVersion, "select", 71), "transport_category");
        NPCConversationManager transport = client.getCM();
        assertNotNull(transport);
        assertNotSame(root, transport);
        assertEquals("万能传送", transport.getScriptName());
        // Native sendSelectLevel sets this only after sending its packet.
        assertEquals(NextLevelType.SEND_SELECT, transport.getNextLevelContext().getLevelType());
        long categoryVersion = dialogueVersion();
        assertTrue(categoryVersion > rootVersion);
        assertEquals(false, packetItems().getLast().get("text_truncated"));
        assertTrue(((String) packetItems().getLast().get("text")).contains("#L2#城镇地图#l"));
        assertReplyOptions(true, List.of(0, 1, 2, 3), List.of(2), List.of("select"));
        assertReplySucceeded(backend.npcReply(handle, categoryVersion, "select", 2), "transport_town");
        assertSame(transport, client.getCM());
        assertEquals(NextLevelType.SEND_NEXT_SELECT, transport.getNextLevelContext().getLevelType());
        assertEquals("Town", transport.getNextLevelContext().getNextLevel());
        long townVersion = dialogueVersion();
        assertTrue(townVersion > categoryVersion);
        Map<String, Object> town = packetItems().getLast();
        assertEquals(true, town.get("text_truncated"));
        assertTrue(((String) town.get("text")).contains("#L1#射手村"));
        assertReplyOptions(false, List.of(), List.of(), List.of());
        assertRejectedReplyDoesNotAdvance(townVersion, "select", 1, "npc_menu_incomplete");
        assertSame(transport, client.getCM());
        assertEquals(NextLevelType.SEND_NEXT_SELECT, transport.getNextLevelContext().getLevelType());
        assertOutcome(backend.npcCancel(handle, townVersion), "SUCCEEDED", "dialogue_cancelled");
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertFalse(nativeScriptMappings().containsKey(client));
        extensions.verifyNoInteractions();
        assertActorRetained();
    }

    @Test
    void realOrdinaryCenterRejectsHiddenAndUnreviewedSelectionsBeforeNativeDispatch() {
        assertOutcome(backend.npcCenterOpen(handle), "SUCCEEDED", "center_dialogue_opened");
        long version = dialogueVersion();
        for (int selection : new int[]{61, 62, 63, 64, 65, 66, 67, 68, 999, 123456, 1, 2, 3, 4, 69, 70, 72}) {
            String reason = List.of(1, 2, 3, 4, 69, 70, 72).contains(selection)
                    ? "npc_reply_path_not_supported" : "npc_selection_not_offered";
            assertRejectedReplyDoesNotAdvance(version, "select", selection, reason);
        }
        assertEquals(true, client.dialogueState().get("active"));
        extensions.verifyNoInteractions();
        assertActorRetained();
    }

    private void cacheOriginalChineseScript() throws Exception {
        cacheChineseScript("npc/9900001.js", SCRIPT_KEY);
    }

    private void cacheNamedChineseScript(String name) throws Exception {
        cacheChineseScript("BeiDouSpecial/" + name + ".js", "scripts/BeiDouSpecial/" + name + ".js");
    }

    private void cacheChineseScript(String relativeSource, String cacheKey) throws Exception {
        ScriptEngine nativeEngine = new ScriptEngineManager().getEngineByName("graal.js");
        assertInstanceOf(GraalJSScriptEngine.class, nativeEngine, "native GraalJS engine must be available");
        GraalJSScriptEngine engine = (GraalJSScriptEngine) nativeEngine;
        engines.add(engine);
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        try (Reader source = Files.newBufferedReader(chineseScript(relativeSource), StandardCharsets.UTF_8)) {
            engine.eval(source);
        }
        // Native script caching isolates cwd lookup, not manager/start/action/dispose or JS execution.
        client.setScriptEngine(cacheKey, engine);
    }

    private static Path chineseScript(String script) {
        Path relative = Path.of("upstream/BeiDou-Server/gms-server/scripts-zh-CN").resolve(script);
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            for (Path candidate : List.of(root.resolve(relative), root.resolve("sources/SoloMapling").resolve(relative))) {
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new AssertionError("upstream Chinese script must exist; do not replace the native script with a stub: " + script);
    }

    @SuppressWarnings("unchecked")
    private Map<Client, ?> nativeScriptMappings() {
        try {
            Field field = NPCScriptManager.class.getDeclaredField("scripts");
            field.setAccessible(true);
            return (Map<Client, ?>) field.get(NPCScriptManager.getInstance());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void assertStaleVersion(long version) {
        ObservationException failure = assertThrows(ObservationException.class, () -> backend.npcCancel(handle, version));
        assertEquals(HttpStatus.GONE, failure.status());
        assertEquals("stale_dialogue", failure.reason());
    }

    private void assertActorRetained() {
        assertSame(character, client.getPlayer());
        assertEquals(17, client.getAccID());
        assertTrue(client.isLoggedIn());
        assertFalse(client.transportClosed());
        assertNull(handle.closeEvidence());
        assertEquals(1, character.getLevel());
        assertEquals(10000, character.getMapId());
        assertEquals(606, character.getMeso());
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        verify(backend, never()).close(any(), any());
        verify(character, never()).saveCharToDB(anyBoolean());
        verify(character, never()).gainMeso(anyInt());
        verify(character, never()).saveLocationOnWarp();
        verify(character, never()).changeMap(anyInt());
        verify(cashShop, never()).gainCash(anyInt(), anyInt());
        verifyNoInteractions(accounts, characters, notes);
    }

    private void verifyGiftWasOnlyRead(int times) {
        extensions.verify(() -> ExtendUtil.getExtendValue("41", ExtendType.CHARACTER_EXTEND.getType(), "新人福利礼包"), times(times));
        extensions.verifyNoMoreInteractions();
    }

    private void assertRejectedReplyDoesNotAdvance(long version, String kind, Integer selection, String reason) {
        NPCConversationManager current = client.getCM();
        long before = client.latestPacketSequence();
        ObservationException failure = assertThrows(ObservationException.class,
                () -> backend.npcReply(handle, version, kind, selection));
        assertEquals("stale_dialogue".equals(reason) ? HttpStatus.GONE : HttpStatus.CONFLICT, failure.status());
        assertEquals(reason, failure.reason());
        assertSame(current, client.getCM());
        assertEquals(before, client.latestPacketSequence());
        assertEquals(true, client.dialogueState().get("active"));
    }

    private static void assertReplySucceeded(Map<String, Object> outcome, String path) {
        assertEquals("SUCCEEDED", outcome.get("status"));
        assertNull(outcome.get("error_reason"));
        assertEquals(true, result(outcome).get("native_dispatch_started"));
        assertEquals("closed".equals(path) ? "dialogue_declined" : "dialogue_replied", result(outcome).get("observed_outcome"));
        assertEquals(true, result(outcome).get("reply_version_consumed"));
        assertEquals(path, result(outcome).get("dialogue_path"));
        assertFalse(result(outcome).containsKey("text"));
    }

    @SuppressWarnings("unchecked")
    private void assertReplyOptions(boolean complete, List<Integer> offered, List<Integer> allowed, List<String> kinds) {
        Map<String, Object> options = (Map<String, Object>) client.dialogueState().get("reply_options");
        assertEquals(complete, options.get("complete"));
        assertEquals(offered, options.get("offered_selections"));
        assertEquals(allowed, options.get("allowed_selections"));
        assertEquals(kinds, options.get("kinds"));
        assertEquals(false, options.get("consumed"));
    }

    private long dialogueVersion() { return (long) client.dialogueState().get("dialogue_version"); }

    private static void assertOutcome(Map<String, Object> outcome, String status, String observed) {
        assertEquals(status, outcome.get("status"));
        assertEquals("SUCCEEDED".equals(status) ? null : observed, outcome.get("error_reason"));
        assertEquals(observed, result(outcome).get("observed_outcome"));
        assertEquals(true, result(outcome).get("native_dispatch_started"));
        assertFalse(result(outcome).containsKey("text"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> packetItems() {
        return (List<Map<String, Object>>) client.packetEvents(0, 500).get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> outcome) {
        return (Map<String, Object>) outcome.get("result");
    }
}
