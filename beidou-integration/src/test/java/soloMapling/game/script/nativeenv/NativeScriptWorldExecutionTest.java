package soloMapling.game.script.nativeenv;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.manager.ServerManager;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.property.ServiceProperty;
import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.server.ThreadManager;
import org.gms.server.TimerManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.server.quest.Quest;
import org.gms.soloscripting.NativeDialogueScripts;
import org.gms.soloscripting.NativeEventScripts;
import org.gms.soloscripting.NativePortalScripts;
import org.gms.soloscripting.NativeReactorScripts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;
import soloMapling.game.script.ScriptResult;

import javax.script.ScriptEngine;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Real runtime script entry, native file loader and managers; only game-world/storage/clock boundaries are substitutes. */
class NativeScriptWorldExecutionTest {
    private Path scriptDirectory;
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach void startWorld() throws Exception {
        CharacterTestContext.install();
        // 通过原生语言目录选择隔离本轮资料，不覆盖完整 checkout 中已有的 scripts 文件。
        scriptDirectory = Files.createTempDirectory(Path.of("."), "scripts-__solo_240_");
        WorldEpoch.onWorldStarted();
    }

    @AfterEach void closeOwnedContextsAndRemoveOnlyStagedFiles() throws Exception {
        try {
            for (Fixture fixture : fixtures) fixture.close();
        } finally {
            ExecutionScope.tryBeginWorldStop();
            if (scriptDirectory != null) {
                try (var paths = Files.walk(scriptDirectory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test void npcAndQuestRunOriginalManagersThroughTheRuntimeAndRetainTheirOwnCallbacks() throws Exception {
        stage("npc/1032110.js");
        stage("quest/20600.js");
        Fixture npc = fixture("npc");
        NativeScriptResult opened = npc.start(Map.of("npc_id", 1032110), null, "({status:status, cid:cm.getPlayer().getId()})");
        assertOpen(opened);
        assertEquals(Map.of("status", 0, "cid", 4), opened.result());
        assertNotNull(npc.client.getCM());
        assertTrue(npc.packets().contains("这里没有什么特别的。"));
        assertClosed(npc.invoke(opened.contextId(), "reply", List.of(1, 0, 0), ""));
        assertNull(npc.client.getCM());

        Fixture quest = fixture("quest");
        NativeScriptResult started = quest.start(Map.of("npc_id", 1101002, "quest_id", 20600), null,
                "({status:status, quest:qm.getQuest()})");
        assertOpen(started);
        assertEquals(Map.of("status", 0, "quest", 20600), started.result());
        assertNotNull(quest.client.getQM());
        assertClosed(quest.invoke(started.contextId(), "reply", List.of(0, 0, 0), ""));
        assertNull(quest.client.getQM());
        verify(quest.quest, never()).forceStart(any(), anyInt());
        quest.assertDrained();
    }

    @Test void portalUsesTheActualMapPortalAndStopsWhenThatObjectOrItsScriptChanges() throws Exception {
        stage("portal/Pachinko_port_in.js");
        Fixture fixture = fixture("portal");
        Portal portal = fixture.portal("Pachinko_port_in");
        NativeScriptResult opened = fixture.start(Map.of("portal_id", 6), null, "pi.getPlayer().getId()");
        assertOpen(opened);
        assertEquals(4, opened.result());
        assertTrue(fixture.packets().contains("打豆豆传送阵正在维护"));
        assertOpen(fixture.invoke(opened.contextId(), "enter", List.of(), ""));
        when(portal.getScriptName()).thenReturn("replaced");
        NativeScriptResult stale = fixture.invoke(opened.contextId(), null, List.of(), "pi.getPlayer().getId()");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, stale.status());
        assertEquals("CLOSED", stale.contextState());
        fixture.assertDrained();

        when(portal.getScriptName()).thenReturn("Pachinko_port_in");
        NativeScriptResult reopened = fixture.start(Map.of("portal_id", 6), null, "");
        assertOpen(reopened);
        when(fixture.map.getPortal(6)).thenReturn(mock(Portal.class));
        NativeScriptResult replaced = fixture.invoke(reopened.contextId(), "enter", List.of(), "");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, replaced.status());
        assertEquals("CLOSED", replaced.contextState());
        assertTrue(fixture.online);
    }

    @Test void reactorRetainsOptionalHitButExecutesActAgainstItsExactMapWithoutAwardingTheFalseBranch() throws Exception {
        stage("reactor/1029000.js");
        Fixture fixture = fixture("reactor");
        fixture.reactor();
        NativeScriptResult hit = fixture.start(Map.of("reactor_oid", 9001), "hit", "");
        assertOpen(hit);
        NativeScriptResult act = fixture.invoke(hit.contextId(), "act", List.of(), "");
        assertOpen(act);
        verify(fixture.map).isAllReactorState(1029000, 4);
        verify(fixture.map, never()).killMonster(anyInt());
        NativeScriptResult absentTouch = fixture.invoke(hit.contextId(), "touch", List.of(), "");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, absentTouch.status());
        assertEquals("CLOSED", absentTouch.contextState());
        fixture.assertDrained();

        NativeScriptResult restarted = fixture.start(Map.of("reactor_oid", 9001), "hit", "");
        assertOpen(restarted);
        when(fixture.map.getReactorByOid(9001)).thenReturn(mock(Reactor.class));
        NativeScriptResult replaced = fixture.invoke(restarted.contextId(), "act", List.of(), "");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, replaced.status());
        assertEquals("CLOSED", replaced.contextState());
    }

    @Test void nativeEventLoadsItsOwnEngineAndSchedulesInThatSameContextThenReallyCloses() throws Exception {
        stage("event/KerningTrain.js");
        Fixture fixture = fixture("event");
        NativeScriptResult opened = fixture.start(Map.of("script", "KerningTrain"), null,
                "var calls=0; function checkedCallback(){calls++;} em.schedule('checkedCallback',25); rideTime");
        assertOpen(opened);
        assertEquals(2500, opened.result());
        assertNotNull(fixture.tick.get());
        fixture.time.set(1026);
        try (AutoCloseable ignored = fixture.nativeBoundary()) { fixture.tick.get().run(); }
        NativeScriptResult read = fixture.invoke(opened.contextId(), null, List.of(), "calls");
        assertOpen(read);
        assertEquals(1, read.result());
        assertClosed(fixture.closeContext(opened.contextId()));
        fixture.assertDrained();
        verify(fixture.task).cancel(false);
        verify(fixture.channel, never()).getEventSM();
    }

    @Test void aMissingNativeFileFailsAndReleasesItsContextBeforeTheNextStart() throws Exception {
        stage("portal/Pachinko_port_in.js");
        Fixture fixture = fixture("portal");
        Portal portal = fixture.portal("__solo_missing_240");
        NativeScriptResult missing = fixture.start(Map.of("portal_id", 6), null, "");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, missing.status());
        assertEquals("CLOSED", missing.contextState());
        assertTrue(missing.contextClosed());
        fixture.assertDrained();
        when(portal.getScriptName()).thenReturn("Pachinko_port_in");
        NativeScriptResult opened = fixture.start(Map.of("portal_id", 6), null, "");
        assertOpen(opened);
        assertClosed(fixture.closeContext(opened.contextId()));
    }

    @Test void aForeignOrReplacementConversationIsNeverDisposedByThisRuntime() throws Exception {
        stage("npc/1032110.js");
        Fixture fixture = fixture("npc");
        NPCConversationManager foreign = mock(NPCConversationManager.class);
        when(fixture.client.getCM()).thenReturn(foreign);
        NativeScriptResult rejected = fixture.start(Map.of("npc_id", 1032110), null, "");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, rejected.status());
        assertEquals("CLOSED", rejected.contextState());
        verify(foreign, never()).dispose();
        fixture.assertDrained();

        when(fixture.client.getCM()).thenAnswer(call -> NPCScriptManager.getInstance().getCM(fixture.client));
        NativeScriptResult opened = fixture.start(Map.of("npc_id", 1032110), null, "");
        assertOpen(opened);
        NPCConversationManager replacement = mock(NPCConversationManager.class);
        when(fixture.client.getCM()).thenReturn(replacement);
        NativeScriptResult stale = fixture.invoke(opened.contextId(), null, List.of(), "42");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, stale.status());
        assertEquals("CLOSED", stale.contextState());
        verify(replacement, never()).dispose();
        assertSame(replacement, fixture.client.getCM());
        assertTrue(fixture.online);
        fixture.assertDrained();
    }

    @Test void nativeLoadingAndCallbackFailuresReleaseOnlyTheirOwnedConversationAndAllowAnotherStart() throws Exception {
        stage("npc/1032110.js");
        Fixture fixture = fixture("npc");
        when(fixture.client.canClickNPC()).thenReturn(false);
        NativeScriptResult cooldown = fixture.start(Map.of("npc_id", 1032110), null, "");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, cooldown.status());
        assertEquals("CLOSED", cooldown.contextState());
        assertNull(fixture.client.getCM());
        fixture.assertDrained();
        when(fixture.client.canClickNPC()).thenReturn(true);

        List<String> scripts = List.of("__solo_missing_240", temporaryNpc("const = ;"),
                temporaryNpc("throw new Error('native_top_level_failure');"),
                temporaryNpc("function start(){cm.sendOk('before callback failure'); throw new Error('native_callback_failure');}"));
        for (String script : scripts) {
            NativeScriptResult failed = fixture.start(Map.of("npc_id", 2147483001, "script_name", script), null, "");
            assertNotEquals(ScriptResult.Status.SUCCEEDED, failed.status());
            assertEquals("CLOSED", failed.contextState(), () -> String.valueOf(failed.toMap()));
            assertTrue(failed.contextClosed());
            assertNull(fixture.client.getCM());
            if (script.equals(scripts.get(2))) assertTrue(failed.error().message().contains("native_top_level_failure"));
            if (script.equals(scripts.get(3))) assertTrue(failed.error().message().contains("native_callback_failure"));
            fixture.assertDrained();
        }
        assertTrue(fixture.packets().contains("before callback failure"));
        NativeScriptResult opened = fixture.start(Map.of("npc_id", 1032110), null, "");
        assertOpen(opened);
        NativeScriptResult invalidReply = fixture.invoke(opened.contextId(), "action", List.of(1, 0, 0), "");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, invalidReply.status());
        assertEquals("CLOSED", invalidReply.contextState());
        assertNull(fixture.client.getCM());
        fixture.assertDrained();
    }

    @Test void cancellationStopsARealNativeFileBeforeItsConversationIsBoundAndSettlesTheContext() throws Exception {
        Fixture fixture = fixture("npc");
        CountDownLatch entered = new CountDownLatch(1);
        when(fixture.player.getName()).thenAnswer(call -> { entered.countDown(); return "script fixture"; });
        String script = temporaryNpc("Java.type('org.gms.util.ThreadLocalUtil').getCurrentClient().getPlayer().getName(); while(true){}");
        NativeScriptExecution execution = fixture.execution(new NativeScriptRequest("npc", "start", null,
                Map.of("npc_id", 2147483001, "script_name", script), null, List.of(), "", Map.of(), 5000));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        execution.cancel();
        NativeScriptResult cancelled = execution.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ScriptResult.Status.CANCELLED, cancelled.status(), () -> String.valueOf(cancelled.toMap()));
        assertEquals("CLOSED", cancelled.contextState());
        assertTrue(cancelled.contextClosed());
        assertTrue(cancelled.invocationSettled());
        assertNull(fixture.client.getCM());
        fixture.assertDrained();
        assertTrue(fixture.online);
    }

    private String temporaryNpc(String source) throws Exception {
        Path directory = scriptDirectory.resolve("npc");
        Files.createDirectories(directory);
        Path script = Files.createTempFile(directory, "__solo_240_", ".js");
        Files.writeString(script, source);
        String name = script.getFileName().toString();
        return name.substring(0, name.length() - 3);
    }

    private void stage(String relative) throws Exception {
        Path target = scriptDirectory.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(Path.of("upstream/BeiDou-Server/gms-server/scripts-zh-CN", relative), target);
    }

    private Fixture fixture(String kind) throws Exception {
        Fixture fixture = new Fixture(kind, scriptDirectory.getFileName().toString().substring("scripts-".length()));
        fixtures.add(fixture);
        fixture.open();
        return fixture;
    }

    private static void assertOpen(NativeScriptResult result) {
        assertEquals(ScriptResult.Status.SUCCEEDED, result.status(), () -> String.valueOf(result.toMap()));
        assertEquals("OPEN", result.contextState());
        assertTrue(result.invocationSettled());
    }

    private static void assertClosed(NativeScriptResult result) {
        assertEquals(ScriptResult.Status.SUCCEEDED, result.status(), () -> String.valueOf(result.toMap()));
        assertEquals("CLOSED", result.contextState());
        assertTrue(result.contextClosed());
        assertTrue(result.invocationSettled());
    }

    private static final class Fixture implements RoleBinding {
        final String kind;
        final Client client = mock(Client.class);
        final Character player = mock(Character.class);
        final MapleMap map = mock(MapleMap.class);
        final Channel channel = mock(Channel.class);
        final World world = mock(World.class);
        final Server server = mock(Server.class);
        final ThreadManager workers = mock(ThreadManager.class);
        final TimerManager timers = mock(TimerManager.class);
        final ScheduledFuture<?> task = mock(ScheduledFuture.class);
        final AtomicReference<Runnable> tick = new AtomicReference<>();
        final AtomicLong time = new AtomicLong(1000);
        final Map<String, ScriptEngine> engines = new HashMap<>();
        final List<Packet> output = new ArrayList<>();
        final Quest quest;
        final ApplicationContext nativeApplication;
        final ExecutorService executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        final CharacterRuntime runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 4, 16, 4), 8), executor);
        final GameContext.CharacterRef character;
        final NativeScriptAdapter adapter;
        CharacterRuntime.ModuleContext context;
        volatile boolean online = true;

        Fixture(String kind, String scriptLanguage) {
            this.kind = kind;
            ServiceProperty service = new ServiceProperty();
            service.setLanguage(scriptLanguage);
            nativeApplication = mock(ApplicationContext.class, delegatesTo(ServerManager.getApplicationContext()));
            when(nativeApplication.getBean(ServiceProperty.class)).thenReturn(service);
            when(client.getPlayer()).thenReturn(player);
            when(client.getChannelServer()).thenReturn(channel);
            when(client.getLanguage()).thenReturn(3);
            when(player.getClient()).thenReturn(client);
            when(player.getMap()).thenReturn(map);
            when(player.getId()).thenReturn(4);
            when(map.getId()).thenReturn(101040001);
            when(channel.getId()).thenReturn(1);
            when(client.canClickNPC()).thenReturn(true);
            when(client.tryacquireClient()).thenReturn(true);
            when(client.getCM()).thenAnswer(call -> NPCScriptManager.getInstance().getCM(client));
            when(client.getQM()).thenAnswer(call -> QuestScriptManager.getInstance().getQM(client));
            when(client.getScriptEngine(anyString())).thenAnswer(call -> engines.get(call.getArgument(0)));
            doAnswer(call -> { engines.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(client).setScriptEngine(anyString(), any());
            doAnswer(call -> {
                ScriptEngine removed = engines.remove(call.getArgument(0));
                if (removed != null) NativeDialogueScripts.removedEngine(removed);
                return null;
            }).when(client).removeScriptEngine(anyString());
            doAnswer(call -> { output.add(call.getArgument(0)); return null; }).when(client).sendPacket(any());
            when(server.getWorld(0)).thenReturn(world);
            when(server.getCurrentTime()).thenAnswer(call -> time.get());
            when(world.getTransportationTime(10000)).thenReturn(2500);
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(workers).newTask(any());
            when(timers.register(any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
                tick.set(call.getArgument(0)); return task;
            });
            DataProvider wz = mock(DataProvider.class);
            when(wz.getData(anyString())).thenReturn(mock(Data.class));
            try (MockedStatic<DataProviderFactory> providers = mockStatic(DataProviderFactory.class)) {
                providers.when(() -> DataProviderFactory.getDataProvider(any())).thenReturn(wz);
                quest = mock(Quest.class);
            }
            character = new GameContext.CharacterRef(runtime.acceptedEpoch(), player, client, world, channel);
            NativeScriptAdapter nativeAdapter = switch (kind) {
                case "npc", "quest" -> new NativeDialogueScripts(client, kind);
                case "portal" -> new NativePortalScripts(client);
                case "reactor" -> new NativeReactorScripts(client);
                case "event" -> new NativeEventScripts(client);
                default -> throw new AssertionError(kind);
            };
            adapter = new NativeScriptAdapter() {
                @Override public AutoCloseable enter(NativeScriptSession session) {
                    AutoCloseable boundary = nativeBoundary();
                    AutoCloseable entered = nativeAdapter.enter(session);
                    return () -> { try { entered.close(); } finally { boundary.close(); } };
                }
                @Override public Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception {
                    return nativeAdapter.start(session, request);
                }
                @Override public Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception {
                    return nativeAdapter.invoke(session, request);
                }
                @Override public void validate(NativeScriptSession session) { nativeAdapter.validate(session); }
                @Override public void close(NativeScriptSession session) throws Exception { nativeAdapter.close(session); }
                @Override public Map<String, Object> describe(NativeScriptSession session) { return nativeAdapter.describe(session); }
            };
        }

        AutoCloseable nativeBoundary() {
            MockedStatic<ServerManager> application = mockStatic(ServerManager.class);
            application.when(ServerManager::getApplicationContext).thenReturn(nativeApplication);
            MockedStatic<Server> servers = mockStatic(Server.class);
            MockedStatic<ThreadManager> threads = mockStatic(ThreadManager.class);
            MockedStatic<TimerManager> clock = mockStatic(TimerManager.class);
            MockedStatic<GameConfig> config = mockStatic(GameConfig.class);
            MockedStatic<Quest> quests = mockStatic(Quest.class);
            servers.when(Server::getInstance).thenReturn(server);
            threads.when(ThreadManager::getInstance).thenReturn(workers);
            clock.when(TimerManager::getInstance).thenReturn(timers);
            config.when(() -> GameConfig.getServerLong("mob_status_monitor_proc")).thenReturn(10L);
            config.when(() -> GameConfig.getServerInt("mob_status_monitor_idle")).thenReturn(10);
            quests.when(() -> Quest.getInstance(20600)).thenReturn(quest);
            return () -> { quests.close(); config.close(); clock.close(); threads.close(); servers.close(); application.close(); };
        }

        void open() throws Exception {
            var work = runtime.acceptOpeningWork();
            runtime.submitOpening(work, () -> { runtime.reserveOrigin(work).adopt(this); runtime.bindAdoptedOrigin(); }, () -> {});
            work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            context = runtime.context(runtime.mount(new RuntimeModule.Spec("script-world", kind, Set.of("native-script"),
                    new ExecutionScope.Limits(16, 4, 16, 4)), new RuntimeModule() {}, true));
        }
        Portal portal(String script) {
            Portal portal = mock(Portal.class);
            when(portal.getId()).thenReturn(6);
            when(portal.getScriptName()).thenReturn(script);
            when(map.getPortal(6)).thenReturn(portal);
            return portal;
        }
        void reactor() {
            Reactor reactor = mock(Reactor.class);
            when(reactor.getId()).thenReturn(1029000);
            when(reactor.getObjectId()).thenReturn(9001);
            when(reactor.getMap()).thenReturn(map);
            when(map.getReactorByOid(9001)).thenReturn(reactor);
        }
        NativeScriptResult start(Map<String, Object> options, String entry, String source) throws Exception {
            return call(new NativeScriptRequest(kind, "start", null, options, entry, List.of(), source, Map.of(), 5000));
        }
        NativeScriptResult invoke(String id, String entry, List<Object> args, String source) throws Exception {
            return call(new NativeScriptRequest(kind, "invoke", id, Map.of(), entry, args, source, Map.of(), 5000));
        }
        NativeScriptResult closeContext(String id) throws Exception {
            return call(new NativeScriptRequest(kind, "close", id, Map.of(), null, List.of(), "", Map.of(), 5000));
        }
        NativeScriptExecution execution(NativeScriptRequest request) {
            return context.runNativeScript(context.acceptWork(), request, adapter);
        }
        NativeScriptResult call(NativeScriptRequest request) throws Exception {
            return execution(request).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        String packets() {
            return output.stream().map(packet -> new String(packet.getBytes(), Charset.forName("GBK")))
                    .reduce("", String::concat);
        }
        void assertDrained() {
            var state = context.scope().snapshot();
            assertEquals(0, state.acceptedWork());
            assertEquals(0, state.runningBodies());
            assertEquals(0, state.pendingCarriers());
            assertEquals(0, state.outstandingResources());
        }
        void close() throws Exception {
            runtime.beginClose(Optional.empty());
            for (var module : runtime.snapshot().modules()) {
                runtime.stop(context.reference(), CharacterRuntime.StopMode.UNLOAD).await(Duration.ofSeconds(10));
            }
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            cleanup.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(10)));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            NPCScriptManager.getInstance().dispose(client);
            QuestScriptManager.getInstance().dispose(client);
        }
        @Override public String kind() { return "native-world-test"; }
        @Override public Object identity() { return client; }
        @Override public GameContext.CharacterRef characterRef() { return character; }
        @Override public void requireCurrent() { if (!online) throw new IllegalStateException("role_offline"); }
        @Override public Map<String, Object> observe() { return Map.of("character_id", 4, "online", online); }
        @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
            permit.requireExecuting(client); online = false; return new ReleaseProof(true);
        }
    }
}
