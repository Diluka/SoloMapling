package soloMapling.game.script.nativeenv;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.CommandsExecutor;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;
import soloMapling.game.script.ScriptResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native engine/session mechanics, independently of the MCP transport and native game managers. */
class NativeScriptRuntimeTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach void startWorld() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
    }

    @AfterEach void closeRuntimes() throws Exception {
        for (Fixture fixture : fixtures) fixture.close();
        ExecutionScope.tryBeginWorldStop();
    }

    @Test void nativeContextRetainsOneEngineAndGlobalsUntilExplicitClose() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("({answer: parameters.base * 2})", Map.of("base", 21));
        assertOpen(opened);
        assertEquals(42, ((Number) ((Map<?, ?>) opened.result()).get("answer")).intValue());
        assertEquals(1, fixture.context.scope().snapshot().outstandingResources());
        assertEquals(0, fixture.context.scope().snapshot().acceptedWork());
        assertEquals(1, fixture.context.nativeScriptContexts().size());
        Object engine = fixture.adapter.session.engine();
        NativeScriptResult first = fixture.invoke(opened.contextId(), "next", List.of(3), "");
        NativeScriptResult second = fixture.invoke(opened.contextId(), "next", List.of(4), "");
        assertOpen(first); assertOpen(second);
        assertEquals(1, ((Number) ((Map<?, ?>) first.result()).get("count")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) second.result()).get("count")).intValue());
        assertSame(engine, fixture.adapter.session.engine());
        assertClosed(fixture.closeContext(opened.contextId()));
        assertEquals(1, fixture.adapter.closes.get());
        fixture.assertDrained();
    }

    @Test void languageOutputAndNullJsonArgumentsSurviveTheNativeBoundary() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> options = new LinkedHashMap<>(); options.put("nullable", null);
        NativeScriptResult opened = await(fixture.call(request("start", null, options, null, List.of(),
                "console.log('参数', parameters.base); print('完成'); console.error('诊断'); ({value: null})",
                Map.of("base", 21), 5000)));
        assertOpen(opened);
        assertTrue(opened.stdout().contains("参数 21"));
        assertTrue(opened.stdout().contains("完成"));
        assertTrue(opened.stderr().contains("诊断"));
        assertTrue(fixture.adapter.sawNullOption);
        NativeScriptResult result = fixture.invoke(opened.contextId(), "nullable", Arrays.asList((Object) null), "");
        assertOpen(result); assertNull(result.result());
        assertClosed(fixture.closeContext(opened.contextId()));
    }

    @Test void languageOutputKeepsSplitSurrogatePairsAndRespectsUtf8Budget() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start(
                "console.log('中文😀'); print('🌟'); console.error('😀'); ({label:'中文😀'})", Map.of());
        assertOpen(opened);
        assertEquals(Map.of("label", "中文😀"), opened.result());
        assertEquals("中文😀\n🌟\n", opened.stdout());
        assertEquals("😀\n", opened.stderr());
        assertFalse(opened.outputTruncated());

        NativeScriptResult bounded = fixture.invoke(opened.contextId(), null, List.of(),
                "print('x'.repeat(16382) + '😀'); 'done'");
        assertOpen(bounded);
        assertEquals("x".repeat(16382), bounded.stdout());
        assertTrue(bounded.outputTruncated());
        assertClosed(fixture.closeContext(opened.contextId()));
    }

    @Test void parametersAreBoundAsDataWithoutChargingTheirSourceEscapingAgain() throws Exception {
        for (String payload : List.of("x".repeat(5_000), "\\".repeat(5_000), "\"".repeat(5_000), "中文😀".repeat(500))) {
            Fixture fixture = fixture();
            NativeScriptResult opened = fixture.start("parameters.payload", Map.of("payload", payload));
            assertOpen(opened);
            assertEquals(payload, opened.result());
            assertClosed(fixture.closeContext(opened.contextId()));
        }
    }

    @Test void returnedJsonRemainsUsableAfterTheNativeContextCloses() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start(
                "({nullable:null, enabled:true, total:42, fraction:1.25, items:['中文','😀',null]})", Map.of());
        assertOpen(opened);
        assertClosed(fixture.closeContext(opened.contextId()));
        Map<?, ?> result = assertInstanceOf(Map.class, opened.result());
        assertNull(result.get("nullable"));
        assertEquals(true, result.get("enabled"));
        assertEquals(42, ((Number) result.get("total")).intValue());
        assertEquals(1.25, ((Number) result.get("fraction")).doubleValue());
        assertEquals(Arrays.asList("中文", "😀", null), result.get("items"));
    }

    @Test void nativeJavaResultsCannotExportHostObjectsOrCyclicGraphs() throws Exception {
        List<Object> cycle = new ArrayList<>(); cycle.add(cycle);
        for (Object invalid : List.of(cycle, new Object(), Map.of("number", Double.NaN))) {
            Fixture fixture = fixture();
            fixture.adapter.startResult = invalid;
            NativeScriptResult result = fixture.start("", Map.of());
            assertEquals(ScriptResult.Status.RESULT_ERROR, result.status());
            assertClosed(result);
            fixture.assertDrained();
        }
    }

    @Test void nativeResultLimitsApplyToEncodedUtf8AndNesting() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("'x'.repeat(16382)", Map.of());
        assertOpen(opened);
        assertEquals("x".repeat(16_382), opened.result());
        assertClosed(fixture.closeContext(opened.contextId()));

        for (String source : List.of("'x'.repeat(16383)", "'中'.repeat(5461)",
                "(() => { let value=0; for(let i=0;i<40;i++) value={nested:value}; return value; })()")) {
            Fixture failed = fixture();
            NativeScriptResult result = failed.start(source, Map.of());
            assertEquals(ScriptResult.Status.RESULT_ERROR, result.status());
            assertClosed(result);
            failed.assertDrained();
        }
    }

    @Test void errorsAndInvalidUnicodeRemainSerializableAndSettleTheirInvocation() throws Exception {
        for (String source : List.of("const = ;", "throw new Error('expected_failure');", "'\\uD800'", "1n",
                "(() => 1)", "Promise.resolve(1)", "NaN", "Infinity", "({'\\uD800': 1})",
                "throw new Error('bad\\uD800text\\uDC00' + '😀'.repeat(2000));",
                "(() => { const value={}; value.self=value; return value; })()")) {
            Fixture fixture = fixture();
            NativeScriptResult result = fixture.start(source, Map.of());
            assertNotEquals(ScriptResult.Status.SUCCEEDED, result.status(), source);
            assertTrue(result.engineExited());
            assertTrue(result.invocationSettled());
            assertNotNull(result.error());
            if (source.contains("expected_failure")) {
                assertTrue(result.error().message().contains("expected_failure"), "retain_original_guest_failure");
            }
            if (source.contains("bad\\uD800text")) {
                String message = result.error().message();
                assertTrue(message.contains("bad\ufffdtext\ufffd"));
                assertEquals(1_024, message.codePointCount(0, message.length()));
                assertFalse(java.lang.Character.isHighSurrogate(message.charAt(message.length() - 1)));
            }
            assertNotNull(result.toMap());
            if (!"CLOSED".equals(result.contextState())) fixture.closeContext(result.contextId());
            fixture.assertDrained();
        }
    }

    @Test void sourceInvocationRevalidatesTheNativeObjectsBeforeRunningAnyCode() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("0", Map.of());
        assertOpen(opened);
        fixture.adapter.valid = false;
        NativeScriptResult result = fixture.invoke(opened.contextId(), null, List.of(), "entered(); 42");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, result.status());
        assertEquals(1, fixture.adapter.entered.getCount(), "stale_context_must_not_enter_source");
        assertTrue(result.invocationSettled());
    }

    @Test void anEnteredInfiniteLoopReallyTimesOutAndReleasesTheOwnedContext() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("0", Map.of());
        assertOpen(opened);
        NativeScriptExecution execution = fixture.call(request("invoke", opened.contextId(), Map.of(), null, List.of(),
                "entered(); while (true) {}", Map.of(), 500));
        assertTrue(fixture.adapter.entered.await(5, TimeUnit.SECONDS));
        NativeScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.TIMED_OUT, result.status());
        assertClosed(result);
        fixture.assertDrained();
        assertOpen(fixture.start("42", Map.of()));
    }

    @Test void cancellationAfterGuestEntryWaitsForRealExitAndContextRelease() throws Exception {
        Fixture fixture = fixture();
        NativeScriptExecution execution = fixture.call(request("start", null, Map.of(), null, List.of(),
                "entered(); while (true) {}", Map.of(), 5000));
        assertTrue(fixture.adapter.entered.await(5, TimeUnit.SECONDS));
        assertEquals(0, fixture.runtime.snapshot().lane().pending());
        execution.cancel();
        NativeScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.CANCELLED, result.status());
        assertClosed(result);
        fixture.assertDrained();
    }

    @Test void externalNativeCallbacksReuseTheSameEngineAndOriginalModuleScope() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("0", Map.of());
        assertOpen(opened);
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Object value = caller.submit(() -> fixture.adapter.session.invocable().invokeFunction("next", 7))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, ((Number) ((Map<?, ?>) value).get("count")).intValue());
        }
        assertEquals(0, fixture.context.scope().snapshot().acceptedWork());
        assertEquals(0, fixture.context.scope().snapshot().pendingCarriers());
        assertEquals(0, fixture.runtime.snapshot().lane().pending());
        assertClosed(fixture.closeContext(opened.contextId()));
    }

    @Test void externalCallbacksKeepTheParametersOfTheirExistingNativeEnvironment() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("function readParameters() { return parameters.base; } 0", Map.of("base", 21));
        assertOpen(opened);
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Object value = caller.submit(() -> fixture.adapter.session.invocable().invokeFunction("readParameters"))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(21, ((Number) value).intValue());
        }
        assertClosed(fixture.closeContext(opened.contextId()));
        fixture.assertDrained();
    }

    @Test void commandBridgeIsAvailableBeforeTheNativeFileAndInLaterManagedCallbacks() throws Exception {
        Fixture fixture = fixture();
        fixture.adapter.commandClient = fixture.role.character.client();
        fixture.adapter.sourceDuringStart = "var initialCommandFact = executeCommand('/first ARG 中文');"
                + "function runCommand(text) { return executeCommand(text); }";
        NativeScriptResult opened = fixture.start("({fact: initialCommandFact, "
                + "plainJson: Object.getPrototypeOf(initialCommandFact) === Object.prototype, "
                + "hostApi: typeof initialCommandFact.getClass, "
                + "temporary: typeof __soloNativeCommandBridge, "
                + "help: typeof executeCommandHelp, commandHelp: typeof commandHelp})", Map.of());
        assertOpen(opened);
        Map<?, ?> value = (Map<?, ?>) opened.result();
        assertEquals("RETURNED", ((Map<?, ?>) value.get("fact")).get("status"));
        assertEquals("UNREPORTED", ((Map<?, ?>) value.get("fact")).get("command_outcome"));
        assertEquals(true, value.get("plainJson"));
        assertEquals("undefined", value.get("hostApi"));
        assertEquals("undefined", value.get("temporary"));
        assertEquals("undefined", value.get("help"));
        assertEquals("undefined", value.get("commandHelp"));
        Object engine = fixture.adapter.session.engine();

        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Object callback = caller.submit(() -> fixture.adapter.session.invocable()
                    .invokeFunction("runCommand", "/second  ARG two"))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("RETURNED", ((Map<?, ?>) callback).get("status"));
            assertEquals("UNREPORTED", ((Map<?, ?>) callback).get("command_outcome"));
        }
        assertSame(engine, fixture.adapter.session.engine());
        assertEquals(List.of("/first ARG 中文", "/second  ARG two"), fixture.adapter.commands);
        assertEquals(0, fixture.runtime.snapshot().lane().pending(), "commands_do_not_enter_the_role_lane");
        assertClosed(fixture.closeContext(opened.contextId()));
        fixture.assertDrained();
    }

    @Test void commandBridgeRejectsArityAndNonStringValuesWithoutCoercionOrNativeCalls() throws Exception {
        Fixture fixture = fixture();
        fixture.adapter.commandClient = fixture.role.character.client();
        for (String source : List.of("executeCommand()", "executeCommand('/one', '/two')",
                "executeCommand(null)", "executeCommand(undefined)", "executeCommand(3)",
                "executeCommand(true)", "executeCommand(['/one'])",
                "executeCommand({toString() { return 'private-command-marker'; }})")) {
            NativeScriptResult result = fixture.start(source, Map.of());
            assertNotEquals(ScriptResult.Status.SUCCEEDED, result.status(), source);
            assertNotNull(result.error());
            assertTrue(result.error().message().contains("executeCommand_requires_one_string"));
            assertFalse(result.error().message().contains("private-command-marker"));
            assertTrue(fixture.adapter.commands.isEmpty());
            assertTrue(result.invocationSettled());
            if (!"CLOSED".equals(result.contextState())) fixture.closeContext(result.contextId());
            fixture.assertDrained();
        }
    }

    @Test void commandBridgeRechecksTheNativeBindingBeforeItsHostCall() throws Exception {
        Fixture fixture = fixture();
        fixture.adapter.commandClient = fixture.role.character.client();
        NativeScriptResult opened = fixture.start("0", Map.of());
        assertOpen(opened);
        NativeScriptResult result = fixture.invoke(opened.contextId(), null, List.of(),
                "invalidateCommandContext(); executeCommand('/must-not-run')");
        assertNotEquals(ScriptResult.Status.SUCCEEDED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().message().contains("native_test_context_stale"));
        assertTrue(fixture.adapter.commands.isEmpty());
        assertTrue(result.invocationSettled());
    }

    @Test void cleanupDeadlineActuallyStopsAnEnteredNativeCancelScheduleLoop() throws Exception {
        Fixture fixture = fixture();
        fixture.adapter.loopDuringClose = true;
        NativeScriptResult opened = fixture.start("function cancelSchedule() { entered(); while (true) {} } 0", Map.of());
        assertOpen(opened);
        NativeScriptExecution closing = fixture.call(request("close", opened.contextId(), Map.of(), null, List.of(), "", Map.of(), 5000));
        assertTrue(fixture.adapter.entered.await(5, TimeUnit.SECONDS), "cleanup_guest_loop_must_really_enter");
        NativeScriptResult result = await(closing);
        assertClosed(result);
        fixture.assertDrained();
        assertEquals(1, fixture.adapter.closes.get());
    }

    @Test void cancellationCannotClaimAnUninterruptibleHostCallHasExited() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("0", Map.of());
        assertOpen(opened);
        NativeScriptExecution execution = fixture.call(request("invoke", opened.contextId(), Map.of(), null, List.of(),
                "hostWait(); 42", Map.of(), 5000));
        try {
            assertTrue(fixture.adapter.hostEntered.await(5, TimeUnit.SECONDS));
            execution.cancel();
            fixture.runtime.beginClose(Optional.empty());
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> execution.completion().toCompletableFuture().get(150, TimeUnit.MILLISECONDS));
            assertTrue(fixture.context.scope().snapshot().runningBodies() > 0);
            assertTrue(fixture.context.scope().snapshot().outstandingResources() > 0);
            assertFalse(fixture.runtime.whenModulesDrained().toCompletableFuture().isDone());
            assertNotEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
            assertTrue(fixture.role.online);
        } finally { fixture.adapter.hostReleased.countDown(); }
        NativeScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.CANCELLED, result.status());
        assertClosed(result);
        fixture.assertDrained();
    }

    @Test void nativeContextExcludesASecondNativeStart() throws Exception {
        Fixture fixture = fixture();
        NativeScriptResult opened = fixture.start("0", Map.of());
        var otherWork = fixture.context.acceptWork();
        try {
            assertThrows(RuntimeException.class, () -> fixture.context.runNativeScript(otherWork,
                    request("start", null, Map.of(), null, List.of(), "42", Map.of(), 1000), fixture.adapter));
        } finally { otherWork.reject(); }
        assertClosed(fixture.closeContext(opened.contextId()));
    }

    @Test void pausingTheModuleStopsTheLoopAndOldGenerationCannotResumeItsEngine() throws Exception {
        Fixture fixture = fixture();
        NativeScriptExecution execution = fixture.call(request("start", null, Map.of(), null, List.of(),
                "entered(); while (true) {}", Map.of(), 5000));
        assertTrue(fixture.adapter.entered.await(5, TimeUnit.SECONDS));
        CharacterRuntime.ModuleContext old = fixture.context;
        var stop = fixture.runtime.stop(old.reference(), CharacterRuntime.StopMode.PAUSE);
        assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(WAIT));
        NativeScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.CANCELLED, result.status());
        assertClosed(result);
        fixture.assertDrained();
        assertTrue(fixture.role.online);
        fixture.context = fixture.runtime.context(fixture.runtime.activate(old.reference()));
        assertThrows(CharacterRuntime.Invalid.class, old::acceptWork);
        assertTrue(fixture.context.nativeScriptContexts().isEmpty());
    }

    @Test void rootCloseWaitsForOwnedNativeContextBeforeReleasingTheRole() throws Exception {
        Fixture fixture = fixture();
        NativeScriptExecution execution = fixture.call(request("start", null, Map.of(), null, List.of(),
                "entered(); while (true) {}", Map.of(), 5000));
        assertTrue(fixture.adapter.entered.await(5, TimeUnit.SECONDS));
        fixture.runtime.beginClose(Optional.empty());
        NativeScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.CANCELLED, result.status());
        assertClosed(result);
        assertTrue(fixture.runtime.snapshot().modules().isEmpty());
        assertTrue(fixture.role.online);
        fixture.releaseOrigin();
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertFalse(fixture.role.online);
    }

    private Fixture fixture() throws Exception {
        Fixture fixture = new Fixture(); fixtures.add(fixture); fixture.open(); return fixture;
    }

    private static NativeScriptRequest request(String operation, String contextId, Map<String, Object> options,
                                               String entry, List<Object> args, String source,
                                               Map<String, Object> parameters, long timeout) {
        return new NativeScriptRequest("npc", operation, contextId, options, entry, args, source, parameters, timeout);
    }

    private static NativeScriptResult await(NativeScriptExecution execution) throws Exception {
        return execution.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void assertOpen(NativeScriptResult result) {
        assertEquals(ScriptResult.Status.SUCCEEDED, result.status(), () -> String.valueOf(result.toMap()));
        assertEquals("OPEN", result.contextState()); assertFalse(result.contextClosed());
        assertTrue(result.engineExited()); assertTrue(result.invocationSettled());
        assertNotNull(result.contextId());
    }

    private static void assertClosed(NativeScriptResult result) {
        assertEquals("CLOSED", result.contextState(), () -> String.valueOf(result.toMap()));
        assertTrue(result.engineExited()); assertTrue(result.contextClosed()); assertTrue(result.invocationSettled());
    }

    private static final class NeutralAdapter implements NativeScriptAdapter {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch hostEntered = new CountDownLatch(1);
        final CountDownLatch hostReleased = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        volatile NativeScriptSession session;
        volatile boolean valid = true;
        volatile boolean loopDuringClose;
        volatile Client commandClient;
        String sourceDuringStart = "";
        Object startResult;
        final List<String> commands = new ArrayList<>();
        boolean sawNullOption;

        @Override public AutoCloseable enter(NativeScriptSession session) {
            if (commandClient == null) return () -> { };
            Client previous = ThreadLocalUtil.getCurrentClient();
            ThreadLocalUtil.setCurrentClient(commandClient);
            CommandsExecutor dispatcher = mock(CommandsExecutor.class);
            MockedStatic<CommandsExecutor> bridge = mockStatic(CommandsExecutor.class);
            bridge.when(CommandsExecutor::getInstance).thenReturn(dispatcher);
            doAnswer(call -> {
                assertSame(commandClient, ThreadLocalUtil.getCurrentClient());
                commands.add(call.getArgument(1));
                return null;
            }).when(dispatcher).handle(eq(commandClient), anyString());
            return () -> {
                bridge.close();
                if (previous == null) ThreadLocalUtil.removeCurrentClient();
                else ThreadLocalUtil.setCurrentClient(previous);
            };
        }

        @Override public Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception {
            this.session = session;
            sawNullOption = request.options().containsKey("nullable") && request.options().get("nullable") == null;
            GraalJSScriptEngine engine = GraalJSScriptEngine.create(null, Context.newBuilder("js")
                    .allowHostAccess(HostAccess.NONE));
            session.captureEngine(engine, "native-test.js");
            if (!sourceDuringStart.isEmpty()) engine.eval(sourceDuringStart);
            engine.put("invalidateCommandContext", (ProxyExecutable) values -> {
                valid = false; return null;
            });
            engine.put("entered", (ProxyExecutable) values -> {
                entered.countDown(); return null;
            });
            engine.put("hostWait", (ProxyExecutable) values -> {
                hostEntered.countDown();
                boolean released = false;
                while (!released) {
                    try { released = hostReleased.await(50, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* A native host method can ignore Java interruption. */ }
                }
                return null;
            });
            engine.eval("var count = 0; function next(value) { return {count: ++count, value: value}; }"
                    + "function nullable(value) { return value; }");
            return startResult;
        }
        @Override public Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception {
            return session.invocable().invokeFunction(request.entry(), request.args().toArray());
        }
        @Override public void validate(NativeScriptSession session) {
            if (!valid) throw new IllegalStateException("native_test_context_stale");
        }
        @Override public void close(NativeScriptSession session) throws Exception {
            closes.incrementAndGet();
            if (loopDuringClose) {
                try { session.invocable().invokeFunction("cancelSchedule"); }
                catch (javax.script.ScriptException stopped) {
                    Throwable cause = stopped;
                    while (cause.getCause() != null && !(cause instanceof PolyglotException)) cause = cause.getCause();
                    if (!(cause instanceof PolyglotException polyglot) || !polyglot.isCancelled()) throw stopped;
                    // Like EventManager.cancel, finish the native cleanup after a guest callback stops.
                    session.markNativeDisposed();
                }
            }
        }
        @Override public Map<String, Object> describe(NativeScriptSession session) { return Map.of("kind", "npc"); }
    }

    private static final class Fixture {
        final ExecutorService executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        final CharacterRuntime runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 4, 16, 4), 8), executor);
        final TestRole role = new TestRole(runtime.acceptedEpoch());
        final NeutralAdapter adapter = new NeutralAdapter();
        CharacterRuntime.ModuleContext context;

        void open() throws Exception {
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(role); runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            context = runtime.context(runtime.mount(new RuntimeModule.Spec("native-test", "native", Set.of("test-control"),
                    new ExecutionScope.Limits(16, 4, 16, 4)), new RuntimeModule() {}, true));
        }
        NativeScriptExecution call(NativeScriptRequest request) {
            return context.runNativeScript(context.acceptWork(), request, adapter);
        }
        NativeScriptResult start(String source, Map<String, Object> parameters) throws Exception {
            return await(call(request("start", null, Map.of(), null, List.of(), source, parameters, 5000)));
        }
        NativeScriptResult invoke(String id, String entry, List<Object> args, String source) throws Exception {
            return await(call(request("invoke", id, Map.of(), entry, args, source, Map.of(), 5000)));
        }
        NativeScriptResult closeContext(String id) throws Exception {
            return await(call(request("close", id, Map.of(), null, List.of(), "", Map.of(), 5000)));
        }
        void assertDrained() {
            var scope = context.scope().snapshot();
            assertEquals(0, scope.acceptedWork()); assertEquals(0, scope.runningBodies());
            assertEquals(0, scope.pendingCarriers()); assertEquals(0, scope.outstandingResources());
        }
        void releaseOrigin() throws Exception {
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            cleanup.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        void close() throws Exception {
            if (runtime.snapshot().state() == CharacterRuntime.State.CLOSED) return;
            runtime.beginClose(Optional.empty());
            for (var module : runtime.snapshot().modules()) {
                runtime.stop(context.reference(), CharacterRuntime.StopMode.UNLOAD).await(WAIT);
            }
            if (!runtime.originReleased()) releaseOrigin();
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(WAIT));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class TestRole implements RoleBinding {
        final Object identity = new Object();
        final GameContext.CharacterRef character;
        volatile boolean online = true;
        TestRole(WorldEpoch.Token epoch) {
            Character actor = mock(Character.class); when(actor.getId()).thenReturn(101);
            Client client = mock(Client.class);
            when(client.getPlayer()).thenReturn(actor); when(actor.getClient()).thenReturn(client);
            character = new GameContext.CharacterRef(epoch, actor, client, mock(World.class), mock(Channel.class));
        }
        @Override public String kind() { return "native-test"; }
        @Override public Object identity() { return identity; }
        @Override public GameContext.CharacterRef characterRef() { return character; }
        @Override public void requireCurrent() { if (!online) throw new IllegalStateException("native_role_offline"); }
        @Override public Map<String, Object> observe() { return Map.of("character_id", 101, "online", online); }
        @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
            permit.requireExecuting(identity); online = false; return new ReleaseProof(true);
        }
    }
}
