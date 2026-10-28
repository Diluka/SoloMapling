package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestActionManager;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.server.quest.Quest;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Original native manager maps and Graal callbacks; only game identity, WZ and output transport are substitutes. */
class NativeDialogueScriptsTest {
    private Client client;
    private Character character;
    private NativeScriptSession session;
    private final Map<String, ScriptEngine> cachedEngines = new HashMap<>();
    private final List<GraalJSScriptEngine> engines = new ArrayList<>();
    private final AtomicReference<Object> attachment = new AtomicReference<>();
    private final AtomicReference<GraalJSScriptEngine> capturedEngine = new AtomicReference<>();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final StringWriter guestOutput = new StringWriter();
    private MockedStatic<NativeScriptSession> nativeSession;
    private Client previousClient;

    @BeforeEach void originalManagersWithOneOrdinaryClient() {
        CharacterTestContext.install();
        client = mock(Client.class);
        character = mock(Character.class);
        session = mock(NativeScriptSession.class);
        when(client.getPlayer()).thenReturn(character);
        when(character.getClient()).thenReturn(client);
        when(character.getId()).thenReturn(4);
        when(client.getLanguage()).thenReturn(3);
        when(client.canClickNPC()).thenReturn(true);
        when(client.tryacquireClient()).thenReturn(true);
        when(client.getCM()).thenAnswer(call -> NPCScriptManager.getInstance().getCM(client));
        when(client.getQM()).thenAnswer(call -> QuestScriptManager.getInstance().getQM(client));
        when(client.getScriptEngine(anyString())).thenAnswer(call -> cachedEngines.get(call.getArgument(0)));
        doAnswer(call -> { cachedEngines.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(client).setScriptEngine(anyString(), any());
        doAnswer(call -> {
            ScriptEngine removed = cachedEngines.remove(call.getArgument(0));
            if (removed != null) NativeDialogueScripts.removedEngine(removed);
            return null;
        }).when(client).removeScriptEngine(anyString());
        when(session.attachment()).thenAnswer(call -> attachment.get());
        doAnswer(call -> { attachment.set(call.getArgument(0)); return null; }).when(session).attachment(any());
        when(session.engine()).thenAnswer(call -> capturedEngine.get());
        doAnswer(call -> {
            GraalJSScriptEngine engine = call.getArgument(0);
            engine.getContext().setWriter(guestOutput);
            capturedEngine.set(engine);
            if (!engines.contains(engine)) engines.add(engine);
            return null;
        }).when(session).captureEngine(any(), anyString());
        doAnswer(call -> { disposed.set(true); return null; }).when(session).markNativeDisposed();
        nativeSession = mockStatic(NativeScriptSession.class);
        nativeSession.when(NativeScriptSession::current).thenReturn(session);
        nativeSession.when(() -> NativeScriptSession.forEngine(any(Invocable.class)))
                .thenAnswer(call -> call.getArgument(0) == capturedEngine.get() ? session : null);
        previousClient = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
    }

    @AfterEach void disposeOnlyTheseManagersAndEngines() {
        NPCScriptManager.getInstance().dispose(client);
        QuestScriptManager.getInstance().dispose(client);
        nativeSession.close();
        for (GraalJSScriptEngine engine : engines) engine.getPolyglotContext().close(true);
        if (previousClient == null) ThreadLocalUtil.removeCurrentClient();
        else ThreadLocalUtil.setCurrentClient(previousClient);
    }

    @Test void originalNpcKeepsItsRealCmAndStatusAcrossManagerCallbacks() throws Exception {
        GraalJSScriptEngine engine = original("npc/1032110.js");
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        Map<?, ?> started = assertInstanceOf(Map.class, adapter.start(session,
                request("npc", "start", Map.of("npc_id", 1032110), null, List.of())));

        NPCConversationManager cm = client.getCM();
        assertNotNull(cm);
        assertSame(cm, engine.get("cm"));
        assertSame(client, cm.getClient());
        assertSame(character, cm.getPlayer());
        assertSame(engine, capturedEngine.get());
        assertEquals(0, ((Number) engine.eval("status")).intValue());
        assertEquals(Boolean.TRUE, started.get("native_dialogue_alive"));
        assertEquals("npc/1032110.js", started.get("script_path"));
        ArgumentCaptor<Packet> output = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(output.capture());
        assertTrue(new String(output.getValue().getBytes(), Charset.forName("GBK")).contains("这里没有什么特别的。"));

        adapter.invoke(session, request("npc", "invoke", Map.of(), "reply", List.of(1, 0, 0)));
        assertEquals(1, ((Number) engine.eval("status")).intValue());
        assertNull(client.getCM());
        assertNull(client.getQM());
        assertTrue(disposed.get());
        assertNull(cachedEngines.get("scripts/npc/1032110.js"));
        verify(character).flushDelayedUpdateQuests();
    }

    @Test void originalQuestDeclineKeepsRealQmAndNeverStartsTheQuest() throws Exception {
        GraalJSScriptEngine engine = original("quest/20600.js");
        DataProvider wz = mock(DataProvider.class);
        when(wz.getData(anyString())).thenReturn(mock(Data.class));
        try (MockedStatic<DataProviderFactory> providers = mockStatic(DataProviderFactory.class)) {
            providers.when(() -> DataProviderFactory.getDataProvider(any())).thenReturn(wz);
            Quest quest = mock(Quest.class);
            try (MockedStatic<Quest> quests = mockStatic(Quest.class)) {
                quests.when(() -> Quest.getInstance(20600)).thenReturn(quest);
                NativeDialogueScripts adapter = new NativeDialogueScripts(client, "quest");
                adapter.start(session, request("quest", "start", Map.of("npc_id", 1101002, "quest_id", 20600), null, List.of()));
                QuestActionManager qm = client.getQM();
                assertNotNull(qm);
                assertSame(qm, engine.get("qm"));
                assertSame(client, qm.getClient());
                assertTrue(qm.isStart());
                assertEquals(20600, qm.getQuest());
                assertEquals(0, ((Number) engine.eval("status")).intValue());

                adapter.invoke(session, request("quest", "invoke", Map.of(), "reply", List.of(0, 0, 0)));
                assertEquals(1, ((Number) engine.eval("status")).intValue());
                assertNull(client.getQM());
                assertNull(client.getCM());
                assertTrue(disposed.get());
                verify(quest, never()).forceStart(any(), anyInt());
            }
        }
    }

    @Test void foreignConversationIsRefusedAndRejectedStartCleanupLeavesItUntouched() {
        NPCConversationManager foreign = mock(NPCConversationManager.class);
        when(client.getCM()).thenReturn(foreign);
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        assertEquals("native_dialogue_already_active", assertThrows(IllegalStateException.class,
                () -> adapter.start(session, request("npc", "start", Map.of("npc_id", 1032110), null, List.of()))).getMessage());
        adapter.close(session);
        verify(foreign, never()).dispose();
        assertTrue(disposed.get());
    }

    @Test void replacementDialogueRejectsSourceValidationAndLateCloseDoesNotDisposeIt() throws Exception {
        original("npc/1032110.js");
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        adapter.start(session, request("npc", "start", Map.of("npc_id", 1032110), null, List.of()));
        NPCConversationManager original = client.getCM();
        NPCConversationManager replacement = mock(NPCConversationManager.class);
        when(client.getCM()).thenReturn(replacement);

        assertEquals("native_dialogue_replaced", assertThrows(IllegalStateException.class,
                () -> adapter.validate(session)).getMessage());
        adapter.close(session);
        verify(replacement, never()).dispose();
        assertSame(original, NPCScriptManager.getInstance().getCM(client));
        assertNull(cachedEngines.get("scripts/npc/1032110.js"));
        assertTrue(disposed.get());
    }

    @Test void entryNamesNeverGuessTheCurrentNativeDialogueRoute() throws Exception {
        original("npc/1032110.js");
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        adapter.start(session, request("npc", "start", Map.of("npc_id", 1032110), null, List.of()));
        assertEquals("invalid_native_dialogue_reply", assertThrows(IllegalArgumentException.class,
                () -> adapter.invoke(session, request("npc", "invoke", Map.of(), "action", List.of(1, 0, 0)))).getMessage());
        assertNotNull(client.getCM());
        adapter.close(session);
        assertNull(NPCScriptManager.getInstance().getCM(client));
    }

    @Test void originalManagerSwallowedCallbackFailureIsCapturedBeforeItsGenericReturn() throws Exception {
        cached("npc/1032110.js", "function start(){cm.sendOk('before'); throw new Error('native_callback_failure');}");
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        assertThrows(IllegalStateException.class,
                () -> adapter.start(session, request("npc", "start", Map.of("npc_id", 1032110), null, List.of())));
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(session).recordFailure(failure.capture());
        assertTrue(failure.getValue().getMessage().contains("native_callback_failure"));
        assertNull(client.getCM());
        assertTrue(disposed.get());
    }

    @Test void nativeManagersMayRecoverMissingOptionalEntryBeforeItBecomesAnInvocationFailure() throws Exception {
        // Graal JS function arity itself does not raise NoSuchMethodException. This explicit Invocable
        // boundary verifies the native Java fallback contract without inventing JS overload behavior.
        GraalJSScriptEngine javascript = cached("npc/__solo_216_arity.js", "function start(chr){return typeof chr;}");
        assertEquals("undefined", javascript.invokeFunction("start"));
        assertEquals("object", javascript.invokeFunction("start", character));
        ScriptEngine engine = mock(ScriptEngine.class, withSettings().extraInterfaces(Invocable.class));
        Invocable invocable = (Invocable) engine;
        cachedEngines.put("scripts/npc/1032110.js", engine);
        doThrow(new NoSuchMethodException("start_without_arguments_missing")).when(invocable).invokeFunction("start");
        doReturn(null).when(invocable).invokeFunction("start", character);
        nativeSession.when(() -> NativeScriptSession.forEngine(invocable)).thenReturn(session);

        assertTrue(NPCScriptManager.getInstance().start(client, 1032110, character));
        assertNotNull(client.getCM());
        verify(invocable).invokeFunction("start");
        verify(invocable).invokeFunction("start", character);
        verify(session, never()).recordFailure(any(NoSuchMethodException.class));
    }

    @Test void invocationThreadLocalIsRestoredForBothPresentAndAbsentPreviousClients() throws Exception {
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        try (AutoCloseable ignored = adapter.enter(session)) { assertSame(client, ThreadLocalUtil.getCurrentClient()); }
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        ThreadLocalUtil.removeCurrentClient();
        try (AutoCloseable ignored = adapter.enter(session)) { assertSame(client, ThreadLocalUtil.getCurrentClient()); }
        assertNull(ThreadLocalUtil.getCurrentClient());
    }

    @Test void nativeClickCooldownBeforeLoadingHasNoNullConversationCleanupDebt() {
        when(client.canClickNPC()).thenReturn(false);
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        assertThrows(IllegalStateException.class,
                () -> adapter.start(session, request("npc", "start", Map.of("npc_id", 1032110), null, List.of())));
        assertNull(client.getCM());
        assertNull(capturedEngine.get());
        assertDoesNotThrow(() -> adapter.close(session));
        assertTrue(disposed.get());
    }

    @Test void missingNativeScriptReleasesOnlyItsConstructedConversation() {
        NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
        assertThrows(IllegalStateException.class,
                () -> adapter.start(session, request("npc", "start", Map.of("npc_id", 2147483001,
                        "script_name", "__solo_216_missing"), null, List.of())));
        assertNull(client.getCM());
        assertNull(capturedEngine.get());
        assertDoesNotThrow(() -> adapter.close(session));
        assertTrue(disposed.get());
    }

    @Test void nativeTopLevelParseAndRuntimeFailuresAreCapturedBeforeManagerBinding() throws Exception {
        for (String source : List.of("const = ;", "throw new Error('native_top_level_failure');")) {
            try (TemporaryNativeScript file = temporaryScript(source)) {
                NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
                assertThrows(IllegalStateException.class, () -> adapter.start(session,
                        request("npc", "start", Map.of("npc_id", 2147483001, "script_name", file.scriptName), null, List.of())));
                assertNotNull(capturedEngine.get(), "the exact engine must be captured before its failing eval");
                assertNull(client.getCM());
                assertDoesNotThrow(() -> adapter.close(session));
                assertTrue(disposed.get());
                capturedEngine.set(null);
                disposed.set(false);
            }
        }
        verify(session, times(2)).recordFailure(any());
    }

    @Test void anEnteredNativeTopLevelLoopCanBeStoppedBeforeCmIsBoundToItsEngine() throws Exception {
        try (TemporaryNativeScript file = temporaryScript("print('native-top-loop-entered'); while (true) {}")) {
            NativeDialogueScripts adapter = new NativeDialogueScripts(client, "npc");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread guest = Thread.ofVirtual().start(() -> {
                try (MockedStatic<NativeScriptSession> active = mockStatic(NativeScriptSession.class);
                     AutoCloseable nativeClient = adapter.enter(session)) {
                    active.when(NativeScriptSession::current).thenReturn(session);
                    active.when(() -> NativeScriptSession.forEngine(any(Invocable.class)))
                            .thenAnswer(call -> call.getArgument(0) == capturedEngine.get() ? session : null);
                    adapter.start(session, request("npc", "start",
                            Map.of("npc_id", 2147483001, "script_name", file.scriptName), null, List.of()));
                } catch (Throwable rejected) { failure.set(rejected); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!guestOutput.toString().contains("native-top-loop-entered") && System.nanoTime() < deadline) Thread.sleep(1);
            try {
                assertTrue(guestOutput.toString().contains("native-top-loop-entered"));
                assertNotNull(capturedEngine.get());
                capturedEngine.get().getPolyglotContext().close(true);
                guest.join(5000);
                assertFalse(guest.isAlive(), "native manager must really leave the cancelled top-level eval");
                assertNotNull(failure.get());
                assertNull(client.getCM());
                assertDoesNotThrow(() -> adapter.close(session));
                assertTrue(disposed.get());
            } finally {
                if (capturedEngine.get() != null) capturedEngine.get().getPolyglotContext().close(true);
                guest.join(5000);
            }
        }
    }

    private GraalJSScriptEngine original(String path) throws Exception {
        return cached(path, Files.readString(Path.of("upstream/BeiDou-Server/gms-server/scripts-zh-CN").resolve(path)));
    }

    private GraalJSScriptEngine cached(String path, String source) throws Exception {
        GraalJSScriptEngine engine = (GraalJSScriptEngine) new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        engine.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostClassLookup", true);
        engine.eval(source);
        engines.add(engine);
        cachedEngines.put("scripts/" + path, engine);
        return engine;
    }

    private static NativeScriptRequest request(String kind, String operation, Map<String, Object> options,
                                                String entry, List<Object> args) {
        return new NativeScriptRequest(kind, operation, "start".equals(operation) ? null : "native-test-context",
                options, entry, args, "", Map.of(), 5000);
    }

    /** The native loader keeps its real relative-path behavior; only this unique, temporary script is supplied. */
    private static TemporaryNativeScript temporaryScript(String source) throws Exception {
        Path scripts = Path.of("scripts");
        Path npc = scripts.resolve("npc");
        boolean madeScripts = !Files.exists(scripts);
        boolean madeNpc = !Files.exists(npc);
        Files.createDirectories(npc);
        Path file = Files.createTempFile(npc, "__solo_216_", ".js");
        Files.writeString(file, source);
        String name = file.getFileName().toString();
        return new TemporaryNativeScript(file, name.substring(0, name.length() - 3), madeNpc, madeScripts);
    }

    private record TemporaryNativeScript(Path file, String scriptName, boolean madeNpc, boolean madeScripts) implements AutoCloseable {
        @Override public void close() throws Exception {
            Files.delete(file);
            if (madeNpc) Files.delete(file.getParent());
            if (madeScripts) Files.delete(file.getParent().getParent());
        }
    }
}
