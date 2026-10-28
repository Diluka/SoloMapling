package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventManager;
import org.gms.scripting.portal.PortalPlayerInteraction;
import org.gms.scripting.reactor.ReactorActionManager;
import org.gms.server.ThreadManager;
import org.gms.server.TimerManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Graal and unmodified native scripts with explicitly substituted game-world collaborators. */
class BeidouWorldScriptsTest {
    private final List<GraalJSScriptEngine> engines = new ArrayList<>();
    private Client client;
    private Character player;
    private MapleMap map;
    private Channel channel;
    private NativeScriptSession session;
    private final AtomicReference<Object> attachment = new AtomicReference<>();
    private final AtomicReference<GraalJSScriptEngine> engine = new AtomicReference<>();
    private final AtomicReference<Invocable> invocable = new AtomicReference<>();
    private Client previousClient;

    @BeforeEach void installNativeCollaboratorsWithoutServerOrDatasource() {
        CharacterTestContext.install();
        client = mock(Client.class);
        player = mock(Character.class);
        map = mock(MapleMap.class);
        channel = mock(Channel.class);
        session = mock(NativeScriptSession.class);
        when(client.getPlayer()).thenReturn(player);
        when(client.getChannelServer()).thenReturn(channel);
        when(client.getLanguage()).thenReturn(3);
        when(player.getClient()).thenReturn(client);
        when(player.getMap()).thenReturn(map);
        when(player.getId()).thenReturn(4);
        when(map.getId()).thenReturn(701000000);
        when(channel.getId()).thenReturn(1);
        when(session.attachment()).thenAnswer(call -> attachment.get());
        doAnswer(call -> { attachment.set(call.getArgument(0)); return null; }).when(session).attachment(any());
        when(session.engine()).thenAnswer(call -> engine.get());
        when(session.invocable()).thenAnswer(call -> invocable.get());
        previousClient = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
    }

    @AfterEach void closeOnlyOwnedGuestEngines() {
        for (GraalJSScriptEngine owned : engines) owned.close();
        if (previousClient == null) ThreadLocalUtil.removeCurrentClient();
        else ThreadLocalUtil.setCurrentClient(previousClient);
    }

    @Test void eachAdapterClientScopeRestoresPreviousAndAbsentClientsEvenOnFailure() throws Exception {
        Client previous = mock(Client.class);
        List<NativeScriptAdapter> adapters = List.of(new NativePortalScripts(client),
                new NativeReactorScripts(client), new NativeEventScripts(client));
        for (NativeScriptAdapter adapter : adapters) {
            ThreadLocalUtil.setCurrentClient(previous);
            try (AutoCloseable ignored = adapter.enter(session)) {
                assertSame(client, ThreadLocalUtil.getCurrentClient());
            }
            assertSame(previous, ThreadLocalUtil.getCurrentClient());

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                try (AutoCloseable ignored = adapter.enter(session)) {
                    assertSame(client, ThreadLocalUtil.getCurrentClient());
                    throw new IllegalStateException("expected guest failure");
                }
            });
            assertEquals("expected guest failure", failure.getMessage());
            assertSame(previous, ThreadLocalUtil.getCurrentClient());

            ThreadLocalUtil.removeCurrentClient();
            try (AutoCloseable ignored = adapter.enter(session)) {
                assertSame(client, ThreadLocalUtil.getCurrentClient());
            }
            assertNull(ThreadLocalUtil.getCurrentClient());
        }
    }

    @Test void originalPortalEnterGetsTheSameRealPiGlobalAndSendsItsNativeNotice() throws Exception {
        Portal portal = portal();
        NativePortalScripts adapter = new NativePortalScripts(client, originalScript());
        Object result = adapter.start(session, request("portal", "start", Map.of("portal_id", 6), null));

        assertEquals(Boolean.TRUE, result);
        PortalPlayerInteraction pi = assertInstanceOf(PortalPlayerInteraction.class, engine.get().get("pi"));
        assertSame(portal, pi.getPortal());
        assertSame(player, pi.getPlayer());
        assertEquals(Boolean.TRUE, engine.get().eval("pi.getPortal().getId() === 6"));
        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(packets.capture());
        byte[] notice = packets.getValue().getBytes();
        assertEquals(0x44, Byte.toUnsignedInt(notice[0]));
        assertEquals(5, Byte.toUnsignedInt(notice[2]));
        assertTrue(new String(notice, Charset.forName("GBK")).contains("传送阵正在维护"));
        assertEquals("portal/Pachinko_port_in.js", adapter.describe(session).get("script_path"));

        adapter.close(session);
        verify(session).markNativeDisposed();
        verify(portal, never()).enterPortal(any());
    }

    @Test void portalBindingRefusesMissingReplacedAndMovedMapObjects() throws Exception {
        NativePortalScripts adapter = new NativePortalScripts(client, originalScript());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> adapter.start(session, request("portal", "start", Map.of("portal_id", 6), null)));
        assertEquals("native_portal_not_found", missing.getMessage());
        Portal portal = portal();
        adapter.start(session, request("portal", "start", Map.of("portal_id", 6), null));
        when(map.getPortal(6)).thenReturn(mock(Portal.class));
        assertEquals("stale_native_portal", assertThrows(IllegalArgumentException.class,
                () -> adapter.validate(session)).getMessage());
        when(map.getPortal(6)).thenReturn(portal);
        when(portal.getScriptName()).thenReturn("otherScript");
        assertEquals("stale_native_portal_script", assertThrows(IllegalArgumentException.class,
                () -> adapter.validate(session)).getMessage());
        when(portal.getScriptName()).thenReturn("Pachinko_port_in");
        when(player.getMap()).thenReturn(mock(MapleMap.class));
        assertEquals("stale_native_script_context", assertThrows(IllegalArgumentException.class,
                () -> adapter.validate(session)).getMessage());
    }

    @Test void portalRejectsForeignEntryAndArgumentsBeforeLoadingAnEngine() {
        NativePortalScripts adapter = new NativePortalScripts(client, path -> fail("must not load a rejected request"));
        assertEquals("invalid_portal_script_entry", assertThrows(IllegalArgumentException.class,
                () -> adapter.start(session, request("portal", "start", Map.of("portal_id", 6), "action"))).getMessage());
        NativeScriptRequest arguments = new NativeScriptRequest("portal", "start", null, Map.of("portal_id", 6),
                "enter", List.of("untrusted pi"), "", Map.of(), 1000);
        assertEquals("invalid_native_script_arguments", assertThrows(IllegalArgumentException.class,
                () -> adapter.start(session, arguments)).getMessage());
    }

    @Test void originalReactorMissingHitIsOptionalWhileMissingTouchRemainsAnError() throws Exception {
        reactor();
        NativeReactorScripts adapter = new NativeReactorScripts(client, originalScript());
        assertNull(adapter.start(session, request("reactor", "start", Map.of("reactor_oid", 9001), "hit")));
        assertThrows(NoSuchMethodException.class,
                () -> adapter.invoke(session, request("reactor", "invoke", Map.of(), "touch")));
        verify(map, never()).isAllReactorState(anyInt(), anyInt());
        verify(map, never()).killMonster(anyInt());
        verify(client, never()).sendPacket(any());
    }

    @Test void originalReactorActReadsTheExactCurrentMapWithoutGrantingItsRewardBranch() throws Exception {
        Reactor reactor = reactor();
        NativeReactorScripts adapter = new NativeReactorScripts(client, originalScript());
        adapter.start(session, request("reactor", "start", Map.of("reactor_oid", 9001), null));

        ReactorActionManager rm = assertInstanceOf(ReactorActionManager.class, engine.get().get("rm"));
        assertSame(reactor, rm.getReactor());
        assertSame(player, rm.getPlayer());
        verify(map).isAllReactorState(1029000, 4);
        verify(map, never()).killMonster(anyInt());
        verify(client, never()).sendPacket(any());
        assertEquals("reactor/1029000.js", adapter.describe(session).get("script_path"));
        adapter.close(session);
        verify(session).markNativeDisposed();
    }

    @Test void reactorBindingRejectsMissingWrongMapAndReplacedReactor() throws Exception {
        NativeReactorScripts adapter = new NativeReactorScripts(client, originalScript());
        assertEquals("native_reactor_not_found", assertThrows(IllegalArgumentException.class,
                () -> adapter.start(session, request("reactor", "start", Map.of("reactor_oid", 9001), null))).getMessage());
        Reactor reactor = reactor();
        when(reactor.getMap()).thenReturn(mock(MapleMap.class));
        assertEquals("native_reactor_not_found", assertThrows(IllegalArgumentException.class,
                () -> adapter.start(session, request("reactor", "start", Map.of("reactor_oid", 9001), null))).getMessage());
        when(reactor.getMap()).thenReturn(map);
        adapter.start(session, request("reactor", "start", Map.of("reactor_oid", 9001), null));
        when(map.getReactorByOid(9001)).thenReturn(mock(Reactor.class));
        assertEquals("stale_native_reactor", assertThrows(IllegalArgumentException.class,
                () -> adapter.validate(session)).getMessage());
    }

    @Test void originalEventInitAndNativeScheduledCallbackUseTheSameOwnedInvocable() throws Exception {
        Server server = mock(Server.class);
        World world = mock(World.class);
        ThreadManager threads = mock(ThreadManager.class);
        TimerManager timers = mock(TimerManager.class);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        AtomicReference<Runnable> nativeTick = new AtomicReference<>();
        AtomicLong time = new AtomicLong(1000);
        List<String> callbacks = new ArrayList<>();
        when(channel.getWorld()).thenReturn(0);
        when(server.getWorld(0)).thenReturn(world);
        when(server.getCurrentTime()).thenAnswer(call -> time.get());
        when(world.getTransportationTime(10000)).thenReturn(2500);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(threads).newTask(any());
        when(timers.register(any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            nativeTick.set(call.getArgument(0)); return task;
        });
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<ThreadManager> worker = mockStatic(ThreadManager.class);
             MockedStatic<TimerManager> timer = mockStatic(TimerManager.class);
             MockedStatic<GameConfig> config = mockStatic(GameConfig.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            worker.when(ThreadManager::getInstance).thenReturn(threads);
            timer.when(TimerManager::getInstance).thenReturn(timers);
            config.when(() -> GameConfig.getServerLong("mob_status_monitor_proc")).thenReturn(10L);
            config.when(() -> GameConfig.getServerInt("mob_status_monitor_idle")).thenReturn(10);
            Function<String, GraalJSScriptEngine> loader = originalScript();
            NativeEventScripts adapter = new NativeEventScripts(client, path -> {
                GraalJSScriptEngine loaded = loader.apply(path);
                invocable.set(new RecordingInvocable(loaded, callbacks));
                return loaded;
            });
            adapter.start(session, request("event", "start", Map.of("script", "KerningTrain"), null));

            EventManager em = assertInstanceOf(EventManager.class, engine.get().get("em"));
            assertSame(channel, em.getChannelServer());
            assertSame(invocable.get(), em.getIv());
            verify(session).ownEventManager(em);
            assertEquals(2500, ((Number) engine.get().get("rideTime")).intValue());
            verify(world).getTransportationTime(10000);
            engine.get().eval("em.schedule('cancelSchedule', 25)");
            assertNotNull(nativeTick.get());
            time.set(1026);
            nativeTick.get().run();
            assertEquals(List.of("init", "cancelSchedule"), callbacks);
            assertTrue(em.getInstances().isEmpty(), "this slice does not create EIMs or map instances");

            adapter.close(session);
            verify(session).markNativeDisposed();
            verify(task).cancel(false);
            assertNull(em.getChannelServer());
            assertEquals(List.of("init", "cancelSchedule", "cancelSchedule"), callbacks);
            assertEquals("stale_native_event_manager", assertThrows(IllegalArgumentException.class,
                    () -> adapter.validate(session)).getMessage());
            assertDoesNotThrow(() -> adapter.close(session));
            assertEquals(List.of("init", "cancelSchedule", "cancelSchedule"), callbacks,
                    "closing an already cancelled native manager must not call its cleared Invocable");
            verify(channel, never()).getEventSM();
        }
    }

    private Portal portal() {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(6);
        when(portal.getName()).thenReturn("pachinkoDoor");
        when(portal.getScriptName()).thenReturn("Pachinko_port_in");
        when(map.getPortal(6)).thenReturn(portal);
        return portal;
    }

    private Reactor reactor() {
        Reactor reactor = mock(Reactor.class);
        when(map.getId()).thenReturn(101040001);
        when(map.getReactorByOid(9001)).thenReturn(reactor);
        when(reactor.getObjectId()).thenReturn(9001);
        when(reactor.getId()).thenReturn(1029000);
        when(reactor.getMap()).thenReturn(map);
        return reactor;
    }

    private Function<String, GraalJSScriptEngine> originalScript() {
        return path -> {
            try {
                GraalJSScriptEngine loaded = (GraalJSScriptEngine) new ScriptEngineManager().getEngineByName("graal.js");
                engines.add(loaded);
                loaded.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
                loaded.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostClassLookup", true);
                loaded.eval(Files.readString(Path.of("upstream/BeiDou-Server/gms-server/scripts-zh-CN", path)));
                engine.set(loaded);
                invocable.set(loaded);
                return loaded;
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
    }

    private NativeScriptRequest request(String kind, String operation, Map<String, Object> options, String entry) {
        return new NativeScriptRequest(kind, operation, "start".equals(operation) ? null : "test-context",
                options, entry, List.of(), "", Map.of(), 1000);
    }

    private record RecordingInvocable(Invocable original, List<String> calls) implements Invocable {
        @Override public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
            calls.add(name); return original.invokeFunction(name, args);
        }
        @Override public Object invokeMethod(Object object, String name, Object... args) throws ScriptException, NoSuchMethodException {
            return original.invokeMethod(object, name, args);
        }
        @Override public <T> T getInterface(Class<T> type) { return original.getInterface(type); }
        @Override public <T> T getInterface(Object object, Class<T> type) { return original.getInterface(object, type); }
    }
}
