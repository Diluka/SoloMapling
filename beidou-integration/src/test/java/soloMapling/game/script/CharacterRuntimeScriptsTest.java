package soloMapling.game.script;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the public runtime seam with a neutral native-role source, without MCP or a database. */
class CharacterRuntimeScriptsTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach void startWorld() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
    }

    @AfterEach void closeOwnedRuntimes() throws Exception {
        for (Fixture fixture : fixtures) fixture.close();
        WorldEpoch.onWorldStopping();
    }

    @Test void absentGameFunctionsGiveAnHonestEmptyDirectory() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> help = fixture.context.scriptHelp(List.of());
        assertEquals("javascript", help.get("language"));
        assertEquals(List.of(), help.get("functions"));
        assertEquals(List.of(), help.get("unavailable"));
        assertEquals(List.of("move", "attack"), fixture.context.scriptHelp(List.of("move", "attack")).get("unavailable"));
        ScriptResult result = fixture.run("typeof cm + ':' + typeof functions.move", Map.of());
        assertSucceeded(result);
        assertEquals("undefined:undefined", result.result());
        ScriptResult hostLookup = fixture.run("Java.type('java.lang.System')", Map.of());
        assertEquals(ScriptResult.Status.RUNTIME_ERROR, hostLookup.status());
        assertClean(hostLookup);
    }

    @Test void evaluatesParametersCollectsLanguageOutputAndReturnsDetachedJson() throws Exception {
        Fixture fixture = fixture();
        ScriptResult result = fixture.run("console.log('参数', parameters.base); print('完成'); console.error('诊断'); "
                + "({answer: parameters.base * 2, items: [true, null, '中文']})", Map.of("base", 21));
        assertSucceeded(result);
        Map<?, ?> value = assertInstanceOf(Map.class, result.result());
        assertEquals(42, ((Number) value.get("answer")).intValue());
        assertEquals(java.util.Arrays.asList(true, null, "中文"), value.get("items"));
        assertTrue(result.stdout().contains("参数 21"));
        assertTrue(result.stdout().contains("完成"));
        assertTrue(result.stderr().contains("诊断"));
        assertFalse(result.outputTruncated());
        fixture.assertSettled();
    }

    @Test void syntaxErrorHasARealSourceLocationAndClosesTheContext() throws Exception {
        Fixture fixture = fixture();
        ScriptResult result = fixture.run("const = ;", Map.of());
        assertEquals(ScriptResult.Status.PARSE_ERROR, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().line() >= 1);
        assertTrue(result.error().column() >= 1);
        assertClean(result);
        fixture.assertSettled();
    }

    @Test void runtimeErrorPreservesAlreadyCollectedOutputAndClosesTheContext() throws Exception {
        Fixture fixture = fixture();
        ScriptResult result = fixture.run("print('before'); throw new Error('expected_failure');", Map.of());
        assertEquals(ScriptResult.Status.RUNTIME_ERROR, result.status());
        assertTrue(result.stdout().contains("before"));
        assertTrue(result.error().message().contains("expected_failure"));
        assertClean(result);
        fixture.assertSettled();
    }

    @Test void unpairedSurrogatesCannotEscapeThroughResultsObjectKeysOrErrors() throws Exception {
        Fixture fixture = fixture();
        for (String source : List.of("'\\uD800'", "'\\uDC00'", "({'\\uD800': 1})", "({x: '\\uDC00'})")) {
            ScriptResult result = fixture.run(source, Map.of());
            assertEquals(ScriptResult.Status.RESULT_ERROR, result.status(), source);
            assertNull(result.result());
            assertClean(result);
        }
        ScriptResult failed = fixture.run("throw new Error('bad\\uD800text\\uDC00');", Map.of());
        assertEquals(ScriptResult.Status.RUNTIME_ERROR, failed.status());
        assertTrue(failed.error().message().contains("bad\ufffdtext\ufffd"));
        assertClean(failed);
        ScriptResult output = fixture.run("print('\\uD800'); console.error('\\uDC00'); 'ok'", Map.of());
        assertSucceeded(output);
        assertTrue(output.stdout().chars().noneMatch(character -> character >= 0xD800 && character <= 0xDFFF));
        assertTrue(output.stderr().chars().noneMatch(character -> character >= 0xD800 && character <= 0xDFFF));
        fixture.assertSettled();
    }

    @Test void validSurrogatePairsKeepTheirCompleteUnicodeMeaning() throws Exception {
        Fixture fixture = fixture();
        ScriptResult result = fixture.run("print('\\uD83D\\uDE00'); ({'\\uD83D\\uDE00': parameters.emoji})",
                Map.of("emoji", "\ud83d\ude00"));
        assertSucceeded(result);
        assertEquals(Map.of("\ud83d\ude00", "\ud83d\ude00"), result.result());
        assertTrue(result.stdout().contains("\ud83d\ude00"));
        fixture.assertSettled();
    }

    @Test void executableAndCyclicResultsAreRejectedInsteadOfLeakingGuestValues() throws Exception {
        Fixture fixture = fixture();
        for (String source : List.of("(() => 1)", "const x = {}; x.self = x; x")) {
            ScriptResult result = fixture.run(source, Map.of());
            assertEquals(ScriptResult.Status.RESULT_ERROR, result.status(), source);
            assertNull(result.result());
            assertClean(result);
            fixture.assertSettled();
        }
    }

    @Test void nonFiniteDeferredAndExcessivelyNestedResultsAreExplicitErrors() throws Exception {
        Fixture fixture = fixture();
        for (String source : List.of("NaN", "Infinity", "1n", "123456789012345678901234567890n", "Promise.resolve(1)",
                "let value = 0; for (let i = 0; i < 40; i++) value = {nested: value}; value")) {
            ScriptResult result = fixture.run(source, Map.of());
            assertEquals(ScriptResult.Status.RESULT_ERROR, result.status(), source);
            assertNull(result.result());
            assertClean(result);
            fixture.assertSettled();
        }
    }

    @Test void parameterSnapshotIsDetachedBeforeTheCallerCanChangeItsMap() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("value", 42);
        ScriptExecution execution = fixture.context.runScript(fixture.context.acceptWork(),
                new ScriptRequest("parameters.value", parameters, 5000));
        parameters.put("value", 999);
        ScriptResult result = await(execution);
        assertSucceeded(result);
        assertEquals(42, ((Number) result.result()).intValue());
        fixture.assertSettled();
    }

    @Test void finiteComputationCannotReportSuccessAfterItsOneMillisecondDeadline() throws Exception {
        Fixture fixture = fixture();
        ScriptResult result = await(fixture.context.runScript(fixture.context.acceptWork(),
                new ScriptRequest("Array.from({length: 100000}, (_, i) => Math.sqrt(i)).reduce((a, b) => a + b, 0)",
                        Map.of(), 1)));
        assertEquals(ScriptResult.Status.TIMED_OUT, result.status());
        assertClean(result);
        fixture.assertSettled();
    }

    @Test void outputAndResultBoundariesDoNotKeepUnboundedData() throws Exception {
        Fixture fixture = fixture();
        ScriptResult output = fixture.run("print('中'.repeat(30000)); 7", Map.of());
        assertSucceeded(output);
        assertTrue(output.outputTruncated());
        assertTrue(output.stdout().length() <= 16384);
        assertEquals(7, ((Number) output.result()).intValue());
        ScriptResult result = fixture.run("'x'.repeat(70000)", Map.of());
        assertEquals(ScriptResult.Status.RESULT_ERROR, result.status());
        assertNull(result.result());
        assertClean(result);
        fixture.assertSettled();
    }

    @Test void registeredDescriptionAndBindingUseTheSameDefinition() throws Exception {
        Fixture fixture = fixture();
        fixture.context.registerScriptFunction(new ScriptFunction("double", "纯运算测试", List.of("value"), "number"),
                values -> ((Number) values.getFirst()).intValue() * 2);
        Map<String, Object> help = fixture.context.scriptHelp(List.of("double", "missing"));
        List<?> functions = assertInstanceOf(List.class, help.get("functions"));
        assertEquals(1, functions.size());
        Map<?, ?> description = assertInstanceOf(Map.class, functions.getFirst());
        assertEquals("double", description.get("name"));
        assertEquals("functions.double", description.get("binding"));
        assertEquals("纯运算测试", description.get("description"));
        assertEquals(List.of("value"), description.get("parameters"));
        assertEquals(List.of("missing"), help.get("unavailable"));
        ScriptResult result = fixture.run("functions.double(parameters.value)", Map.of("value", 21));
        assertSucceeded(result);
        assertEquals(42, ((Number) result.result()).intValue());
    }

    @Test void functionDirectoriesAndSameNamesBelongToTheExactInvokingModule() throws Exception {
        Fixture fixture = fixture();
        fixture.context.registerScriptFunction(new ScriptFunction("value", "first", List.of(), "number"), values -> 1);
        var otherRef = fixture.runtime.mount(new RuntimeModule.Spec("script-test", "other", Set.of(),
                new ExecutionScope.Limits(8, 0, 8, 0)), new RuntimeModule() {}, true);
        var other = fixture.runtime.context(otherRef);
        assertEquals(List.of(), other.scriptHelp(List.of()).get("functions"));
        other.registerScriptFunction(new ScriptFunction("value", "second", List.of(), "number"), values -> 2);
        ScriptResult first = fixture.run("functions.value()", Map.of());
        ScriptResult second = await(other.runScript(other.acceptWork(), new ScriptRequest("functions.value()", Map.of(), 5000)));
        assertSucceeded(first);
        assertSucceeded(second);
        assertEquals(1, ((Number) first.result()).intValue());
        assertEquals(2, ((Number) second.result()).intValue());
        assertEquals(ExecutionScope.DrainResult.DRAINED,
                fixture.runtime.stop(other.reference(), CharacterRuntime.StopMode.UNLOAD).await(WAIT));
        assertThrows(CharacterRuntime.Invalid.class, () -> other.scriptHelp(List.of()));
        assertEquals(1, ((Number) fixture.run("functions.value()", Map.of()).result()).intValue());
        fixture.assertSettled();
    }

    @Test void parametersMustRemainBoundedJsonAndRejectedInputSettlesTheAcceptedWork() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> nested = Map.of("value", 0);
        for (int i = 0; i < 40; i++) nested = Map.of("nested", nested);
        for (Map<String, Object> parameters : List.of(Map.<String, Object>of("bad", Double.NaN),
                Map.<String, Object>of("bad", new Object()), nested)) {
            var work = fixture.context.acceptWork();
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.context.runScript(work, new ScriptRequest("42", parameters, 5000)));
            assertTrue(work.isSettled());
            fixture.assertSettled();
        }
    }

    @Test void deadlineStopsAnEnteredPureScriptInfiniteLoopAndSettlesItsOriginalWork() throws Exception {
        Fixture fixture = fixture();
        assertSucceeded(fixture.run("1", Map.of())); // Warm only the engine, not the loop under test.
        CountDownLatch entered = fixture.signalFunction();
        ExecutionScope.WorkHandle work = fixture.context.acceptWork();
        ScriptExecution execution = fixture.context.runScript(work,
                new ScriptRequest("functions.entered(); while (true) {}", Map.of(), 1000));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "guest_loop_did_not_start");
        assertSame(work, execution.work());
        ScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.TIMED_OUT, result.status());
        assertClean(result);
        assertTrue(work.isSettled());
        fixture.assertSettled();
        assertSucceeded(fixture.run("6 * 7", Map.of()));
    }

    @Test void explicitCancellationStopsAnEnteredLoopWithoutWaitingForItsDeadline() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = fixture.signalFunction();
        AtomicInteger ticks = new AtomicInteger();
        fixture.context.registerScriptFunction(new ScriptFunction("tick", "独立检查执行已停止", List.of(), "null"), values -> {
            ticks.incrementAndGet();
            return null;
        });
        ScriptExecution execution = fixture.context.runScript(fixture.context.acceptWork(),
                new ScriptRequest("functions.entered(); while (true) { functions.tick(); }", Map.of(), 5000));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "guest_loop_did_not_start");
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ticks.get() == 0 && System.nanoTime() < limit) Thread.onSpinWait();
        assertTrue(ticks.get() > 0);
        execution.cancel();
        ScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.CANCELLED, result.status());
        assertClean(result);
        fixture.assertSettled();
        int stoppedAt = ticks.get();
        Thread.sleep(30);
        assertEquals(stoppedAt, ticks.get(), "guest_code_continued_after_cancellation_completed");
    }

    @Test void cancellingTheAcceptedWorkBeforeScriptStartDoesNotCreateAnEngineDebt() throws Exception {
        Fixture fixture = fixture();
        var work = fixture.context.acceptWork();
        fixture.context.cancel(work);
        ScriptResult result = await(fixture.context.runScript(work, new ScriptRequest("while (true) {}", Map.of(), 5000)));
        assertEquals(ScriptResult.Status.CANCELLED, result.status());
        assertClean(result);
        assertTrue(work.isSettled());
        fixture.assertSettled();
    }

    @Test void longScriptDoesNotOccupyTheRolesShortCommandLane() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = fixture.signalFunction();
        ScriptExecution execution = fixture.startLoop(5000);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch command = new CountDownLatch(1);
        var shortWork = fixture.context.acceptWork();
        assertEquals(CharacterCommandLane.Status.ENQUEUED,
                fixture.context.submit("test-control", shortWork, () -> {}, command::countDown, () -> {}).status());
        assertTrue(command.await(2, TimeUnit.SECONDS), "script_blocked_the_short_command_lane");
        shortWork.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(execution.completion().toCompletableFuture().isDone());
        execution.cancel();
        assertEquals(ScriptResult.Status.CANCELLED, await(execution).status());
        fixture.assertSettled();
    }

    @Test void modulePauseDrainsItsLoopAndResumeCannotReviveTheOldGenerationOrFunctions() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = fixture.signalFunction();
        ScriptExecution execution = fixture.startLoop(5000);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CharacterRuntime.ModuleContext old = fixture.context;
        var stop = fixture.runtime.stop(old.reference(), CharacterRuntime.StopMode.PAUSE);
        assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(WAIT));
        assertEquals(ScriptResult.Status.CANCELLED, await(execution).status());
        assertTrue(fixture.role.online);
        var resumed = fixture.runtime.activate(old.reference());
        fixture.context = fixture.runtime.context(resumed);
        assertNotEquals(old.reference().generation(), resumed.generation());
        assertThrows(CharacterRuntime.Invalid.class, () -> old.scriptHelp(List.of()));
        assertThrows(CharacterRuntime.Invalid.class, old::acceptWork);
        assertEquals(List.of(), fixture.context.scriptHelp(List.of()).get("functions"));
        assertSucceeded(fixture.run("42", Map.of()));
        fixture.assertSettled();
    }

    @Test void rootCloseWaitsForTheLoopAndContextBeforeReleasingTheNativeRole() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = fixture.signalFunction();
        ScriptExecution execution = fixture.startLoop(5000);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        fixture.runtime.beginClose(Optional.empty());
        ScriptResult result = await(execution);
        assertEquals(ScriptResult.Status.CANCELLED, result.status());
        assertClean(result);
        assertTrue(fixture.runtime.snapshot().modules().isEmpty());
        assertTrue(fixture.role.online, "module_stop_must_not_release_the_native_origin");
        fixture.releaseOrigin();
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertFalse(fixture.role.online);
        assertTrue(fixture.executor.isShutdown());
    }

    private Fixture fixture() throws Exception {
        Fixture fixture = new Fixture();
        fixtures.add(fixture);
        fixture.open();
        return fixture;
    }

    private static ScriptResult await(ScriptExecution execution) throws Exception {
        return execution.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void assertSucceeded(ScriptResult result) {
        assertEquals(ScriptResult.Status.SUCCEEDED, result.status(), () -> String.valueOf(result.toMap()));
        assertNull(result.error());
        assertClean(result);
    }

    private static void assertClean(ScriptResult result) {
        assertTrue(result.engineExited(), "engine_exit_unverified");
        assertTrue(result.contextClosed(), "context_release_unverified");
        assertTrue(result.workSettled(), "work_settlement_unverified");
    }

    private static final class Fixture {
        final ExecutorService executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        final CharacterRuntime runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 4, 16, 4), 128, 8), executor);
        final TestRole role = new TestRole(runtime.acceptedEpoch());
        CharacterRuntime.ModuleContext context;

        void open() throws Exception {
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(role);
                runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var module = runtime.mount(new RuntimeModule.Spec("script-test", "scripts", Set.of("test-control"),
                    new ExecutionScope.Limits(16, 4, 16, 4)), new RuntimeModule() {}, true);
            context = runtime.context(module);
        }

        ScriptResult run(String source, Map<String, Object> parameters) throws Exception {
            return await(context.runScript(context.acceptWork(), new ScriptRequest(source, parameters, 5000)));
        }

        CountDownLatch signalFunction() {
            CountDownLatch entered = new CountDownLatch(1);
            context.registerScriptFunction(new ScriptFunction("entered", "测试执行检查点", List.of(), "null"), values -> {
                entered.countDown();
                return null;
            });
            return entered;
        }

        ScriptExecution startLoop(long timeout) {
            return context.runScript(context.acceptWork(),
                    new ScriptRequest("functions.entered(); while (true) {}", Map.of(), timeout));
        }

        void assertSettled() {
            var scope = context.scope().snapshot();
            assertEquals(0, scope.acceptedWork());
            assertEquals(0, scope.runningBodies());
            assertEquals(0, scope.pendingCarriers());
            assertEquals(0, scope.outstandingResources());
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
            Character actor = mock(Character.class);
            when(actor.getId()).thenReturn(101);
            character = new GameContext.CharacterRef(epoch, actor, mock(Client.class), mock(World.class), mock(Channel.class));
        }

        @Override public String kind() { return "script-test"; }
        @Override public Object identity() { return identity; }
        @Override public GameContext.CharacterRef characterRef() { return character; }
        @Override public void requireCurrent() { if (!online) throw new IllegalStateException("native_role_offline"); }
        @Override public Map<String, Object> observe() { return Map.of("character_id", 101, "online", online); }
        @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
            permit.requireExecuting(identity);
            online = false;
            return new ReleaseProof(true);
        }
    }
}
