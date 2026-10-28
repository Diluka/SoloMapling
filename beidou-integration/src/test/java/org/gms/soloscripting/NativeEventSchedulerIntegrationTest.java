package org.gms.soloscripting;

import com.alibaba.fastjson2.JSONObject;
import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventManager;
import org.gms.server.ThreadManager;
import org.gms.server.TimerManager;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;
import soloMapling.game.script.ScriptResult;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptResult;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Must run against newly woven classes: exercises native call-site advice and real async carriers. */
class NativeEventSchedulerIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private final List<Fixture> fixtures = new ArrayList<>();
    private JSONObject properties;
    private JSONObject previousProperties;
    private boolean ownsThreads;
    private boolean ownsTimers;

    @BeforeEach void startNativeSchedulers() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        Field config = GameConfig.class.getDeclaredField("config"); config.setAccessible(true);
        Field values = GameConfig.class.getDeclaredField("properties"); values.setAccessible(true);
        properties = (JSONObject) values.get(config.get(null));
        previousProperties = JSONObject.parseObject(properties.toJSONString());
        setConfig("mob_status_monitor_proc", "java.lang.Long", "10");
        setConfig("mob_status_monitor_idle", "java.lang.Integer", "100");
        Field workers = ThreadManager.class.getDeclaredField("executorService"); workers.setAccessible(true);
        ExecutorService previousWorkers = (ExecutorService) workers.get(ThreadManager.getInstance());
        ownsThreads = previousWorkers == null || previousWorkers.isShutdown();
        if (ownsThreads) ThreadManager.getInstance().start();
        Field timers = TimerManager.class.getDeclaredField("ses"); timers.setAccessible(true);
        ScheduledThreadPoolExecutor previousTimers = (ScheduledThreadPoolExecutor) timers.get(TimerManager.getInstance());
        ownsTimers = previousTimers == null || previousTimers.isShutdown();
        if (ownsTimers) TimerManager.getInstance().start();
        Server.getInstance().forceUpdateCurrentTime();
    }

    @AfterEach void stopOnlyTestOwnedSchedulers() throws Exception {
        try { for (Fixture fixture : fixtures) fixture.close(); }
        finally {
            if (ownsTimers) TimerManager.getInstance().stop();
            if (ownsThreads) ThreadManager.getInstance().stop();
            if (properties != null) { properties.clear(); properties.putAll(previousProperties); }
            WorldEpoch.onWorldStopping();
        }
    }

    @Test void realNativeScheduleCallsBackInTheSameOwnedEngineThenTrulyDrains() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.call("start", null, "em.schedule('scheduled', 0); 0", Map.of("base", 21));
        assertEquals(ScriptResult.Status.SUCCEEDED, opened.status(), () -> String.valueOf(opened.toMap()));
        assertEquals("OPEN", opened.contextState());
        assertTrue(awaitNativeTick(fixture.adapter.callback));
        assertEquals(1, fixture.adapter.callbackCount.get());
        assertEquals(21, fixture.adapter.callbackBase.get());
        assertTrue(fixture.adapter.callbackHasClient.get(), "native_callback_requires_the_exact_current_client");
        NativeScriptResult read = fixture.call("invoke", opened.contextId(), "count", Map.of("base", 21));
        assertEquals(1, ((Number) read.result()).intValue());
        assertSame(fixture.adapter.session.invocable().getClass(), fixture.adapter.manager.getIv().getClass());
        NativeScriptResult closed = fixture.call("close", opened.contextId(), "", Map.of());
        assertEquals("CLOSED", closed.contextState(), () -> String.valueOf(closed.toMap()));
        assertTrue(closed.contextClosed()); assertTrue(closed.invocationSettled());
        fixture.assertDrained();
        assertNull(fixture.adapter.manager.getIv());
        assertNull(fixture.adapter.manager.getChannelServer());
    }

    @Test void ownedEimMethodsRejectBeforePrefillWhileUnownedNativeSchedulerStillRuns() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.call("start", null, "0", Map.of());
        assertEquals(ScriptResult.Status.SUCCEEDED, opened.status());
        EventManager owned = fixture.adapter.manager;
        List<Integer> before = queueState(owned);
        UnsupportedOperationException instance = assertThrows(UnsupportedOperationException.class,
                () -> owned.newInstance("unsupported_instance"));
        assertEquals("native_event_instances_not_supported", instance.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> owned.newMarriage("unsupported_marriage"));
        long stableUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
        while (System.nanoTime() < stableUntil) {
            assertEquals(before, queueState(owned), "unsupported_entry_must_not_schedule_native_prefill");
            Thread.sleep(5);
        }
        assertTrue(owned.getInstances().isEmpty());

        CountDownLatch ordinaryCallback = new CountDownLatch(1);
        try (GraalJSScriptEngine original = GraalJSScriptEngine.create()) {
            original.put("record", (ProxyExecutable) ignored -> { ordinaryCallback.countDown(); return null; });
            original.eval("function untouched() { record(); } function cancelSchedule() {}");
            EventManager ordinary = new EventManager(fixture.role.channel, original, "ordinary_test");
            assertNull(NativeScriptSession.forEventManager(ordinary));
            try {
                ordinary.schedule("untouched", 0);
                assertTrue(awaitNativeTick(ordinaryCallback), "channel_owned_scheduler_must_not_be_intercepted_as_runtime_owned");
            } finally { ordinary.cancel(); }
        }
        NativeScriptResult closed = fixture.call("close", opened.contextId(), "", Map.of());
        assertEquals("CLOSED", closed.contextState());
        fixture.assertDrained();
    }

    private static boolean awaitNativeTick(CountDownLatch signal) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (signal.getCount() != 0 && System.nanoTime() < deadline) {
            Server.getInstance().forceUpdateCurrentTime();
            signal.await(10, TimeUnit.MILLISECONDS);
        }
        return signal.getCount() == 0;
    }

    private static List<Integer> queueState(EventManager manager) throws Exception {
        Field ready = EventManager.class.getDeclaredField("readyInstances"); ready.setAccessible(true);
        Field loading = EventManager.class.getDeclaredField("onLoadInstances"); loading.setAccessible(true);
        Field next = EventManager.class.getDeclaredField("readyId"); next.setAccessible(true);
        return List.of(((List<?>) ready.get(manager)).size(), (Integer) loading.get(manager), (Integer) next.get(manager));
    }

    private static void setConfig(String key, String type, String value) {
        GameConfigDO config = new GameConfigDO();
        config.setConfigType("server"); config.setConfigSubType("global"); config.setConfigCode(key);
        config.setConfigClazz(type); config.setConfigValue(value); GameConfig.add(config);
    }

    private Fixture fixture() throws Exception {
        Fixture fixture = new Fixture(); fixtures.add(fixture); fixture.open(); return fixture;
    }

    private static final class EventAdapter implements NativeScriptAdapter {
        private final Client client;
        private final Channel channel;
        final CountDownLatch callback = new CountDownLatch(1);
        final AtomicInteger callbackCount = new AtomicInteger();
        final AtomicInteger callbackBase = new AtomicInteger();
        final AtomicBoolean callbackHasClient = new AtomicBoolean();
        NativeScriptSession session;
        EventManager manager;
        EventAdapter(Client client, Channel channel) { this.client = client; this.channel = channel; }
        @Override public AutoCloseable enter(NativeScriptSession session) { return NativeWorldScriptSupport.enterClient(client); }
        @Override public Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception {
            this.session = session;
            GraalJSScriptEngine engine = GraalJSScriptEngine.create(null, Context.newBuilder("js").allowHostAccess(HostAccess.ALL));
            session.captureEngine(engine, "event/native_scheduler_test.js");
            engine.put("record", (ProxyExecutable) values -> {
                callbackCount.incrementAndGet(); callbackBase.set(values[0].asInt());
                callbackHasClient.set(ThreadLocalUtil.getCurrentClient() == client); callback.countDown(); return null;
            });
            engine.eval("var count = 0; function init() {} function scheduled(ignored) { count++; record(parameters.base); } function cancelSchedule() {}");
            manager = new EventManager(channel, session.invocable(), "owned_test");
            session.ownEventManager(manager); engine.put("em", manager);
            return session.invocable().invokeFunction("init", (Object) null);
        }
        @Override public Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception {
            return session.invocable().invokeFunction(request.entry(), request.args().toArray());
        }
        @Override public void close(NativeScriptSession session) { manager.cancel(); session.markNativeDisposed(); }
        @Override public Map<String, Object> describe(NativeScriptSession session) { return Map.of("kind", "event"); }
    }

    private static final class Fixture {
        final ExecutorService executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        final CharacterRuntime runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 4, 16, 4), 128, 8), executor);
        final TestRole role = new TestRole(runtime.acceptedEpoch());
        final EventAdapter adapter = new EventAdapter(role.client, role.channel);
        CharacterRuntime.ModuleContext context;
        void open() throws Exception {
            var work = runtime.acceptOpeningWork();
            runtime.submitOpening(work, () -> { runtime.reserveOrigin(work).adopt(role); runtime.bindAdoptedOrigin(); }, () -> {});
            work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            context = runtime.context(runtime.mount(new RuntimeModule.Spec("native-event-test", "event", Set.of("event"),
                    new ExecutionScope.Limits(16, 4, 16, 4)), new RuntimeModule() {}, true));
        }
        NativeScriptResult call(String operation, String id, String source, Map<String, Object> parameters) throws Exception {
            return context.runNativeScript(context.acceptWork(), new NativeScriptRequest("event", operation, id,
                    Map.of(), null, List.of(), source, parameters, 5000), adapter).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        void assertDrained() {
            var state = context.scope().snapshot();
            assertEquals(0, state.acceptedWork()); assertEquals(0, state.runningBodies());
            assertEquals(0, state.pendingCarriers()); assertEquals(0, state.outstandingResources());
        }
        void close() throws Exception {
            if (runtime.snapshot().state() == CharacterRuntime.State.CLOSED) return;
            runtime.beginClose(Optional.empty());
            for (var module : runtime.snapshot().modules()) runtime.stop(context.reference(), CharacterRuntime.StopMode.UNLOAD).await(WAIT);
            if (!runtime.originReleased()) {
                var cleanup = runtime.acquireOriginCleanup();
                runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
                cleanup.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(WAIT));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class TestRole implements RoleBinding {
        final Object identity = new Object();
        final Client client = mock(Client.class);
        final Channel channel = mock(Channel.class);
        final GameContext.CharacterRef character;
        boolean online = true;
        TestRole(WorldEpoch.Token epoch) {
            Character actor = mock(Character.class); when(actor.getId()).thenReturn(101);
            when(channel.getWorld()).thenReturn(0); when(channel.getId()).thenReturn(1);
            character = new GameContext.CharacterRef(epoch, actor, client, mock(World.class), channel);
        }
        @Override public String kind() { return "native-event-test"; }
        @Override public Object identity() { return identity; }
        @Override public GameContext.CharacterRef characterRef() { return character; }
        @Override public void requireCurrent() { if (!online) throw new IllegalStateException("native_role_offline"); }
        @Override public Map<String, Object> observe() { return Map.of("character_id", 101); }
        @Override public ReleaseProof release(ScopeResources.DrainPermit permit) { permit.requireExecuting(identity); online = false; return new ReleaseProof(true); }
    }
}
