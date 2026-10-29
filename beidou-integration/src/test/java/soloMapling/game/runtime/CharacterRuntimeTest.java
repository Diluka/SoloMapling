package soloMapling.game.runtime;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.action.NativeActionFact;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterRuntimeTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach void startWorld() { CharacterTestContext.install(); WorldEpoch.onWorldStarted(); }

    @AfterEach void stopOwnedRuntimes() {
        for (Fixture fixture : fixtures) {
            try {
                if (WorldEpoch.snapshot().active() && WorldEpoch.snapshot().token() == fixture.runtime.acceptedEpoch()) {
                    fixture.role.releaseVerified = true;
                    fixture.runtime.beginClose(Optional.empty());
                    fixture.runQueued();
                    if (fixture.adopted && !fixture.runtime.originReleased()) {
                        fixture.cleanupOrigin();
                        fixture.runQueued();
                    }
                    fixture.runtime.awaitClosed(WAIT);
                }
            } catch (RuntimeException ignored) {
                // Failed/old-world ownership deliberately stays unverified; never invent a release proof.
            } finally {
                fixture.executor.shutdown();
            }
        }
        ExecutionScope.tryBeginWorldStop();
    }

    @Test
    void nativeOpenReservesItsResultBeforeAdoptionAndBindsOnlyInsideTheOneAdmittedBody() {
        Fixture fixture = manual();
        var work = fixture.runtime.acceptOpeningWork();
        AtomicBoolean factoryEntered = new AtomicBoolean();
        assertEquals(CharacterCommandLane.Status.ENQUEUED, fixture.runtime.submitOpening(work, () -> {
            factoryEntered.set(true);
            assertEquals(CharacterRuntime.State.OPENING, fixture.runtime.snapshot().state());
            var origin = fixture.runtime.reserveOrigin(work);
            assertEquals(1, fixture.runtime.snapshot().rootScope().outstandingResources());
            origin.adopt(fixture.role);
            fixture.adopted = true;
            assertEquals(CharacterRuntime.State.OPENING, fixture.runtime.snapshot().state());
            fixture.runtime.bindAdoptedOrigin();
        }, () -> {}).status());
        assertFalse(factoryEntered.get());
        assertEquals(1, fixture.manual.size());
        fixture.runQueued();

        assertTrue(work.isSettled());
        assertEquals(CharacterRuntime.State.BOUND, fixture.runtime.snapshot().state());
        assertEquals("native-test", fixture.runtime.snapshot().originKind());
        assertEquals(101, fixture.runtime.snapshot().characterId());
        assertEquals(Map.of("character_id", 101, "online", true), fixture.runtime.observeOrigin());
        assertEquals(List.of(RuntimeEvents.Kind.CREATED, RuntimeEvents.Kind.BOUND),
                fixture.runtime.events().events().stream().map(RuntimeEvents.Event::kind).toList());
        assertThrows(CharacterRuntime.Invalid.class, fixture.runtime::acceptOpeningWork);
    }

    @Test
    void verifiedAbsentOpenCanCloseWithoutCreatingOrReleasingARole() {
        Fixture fixture = manual();
        var work = fixture.runtime.acceptOpeningWork();
        fixture.runtime.submitOpening(work, () -> fixture.runtime.reserveOrigin(work).confirmAbsent(), () -> {});
        fixture.runQueued();
        fixture.runtime.beginClose(Optional.empty());
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
        assertEquals(0, fixture.role.releases.get());
        assertTrue(fixture.executor.isShutdown());
    }

    @Test
    void stopBetweenOpeningAdmissionAndSubmissionStillSettlesTheExactWorkWithoutEnteringItsBody() {
        Fixture fixture = manual();
        var work = fixture.runtime.acceptOpeningWork();
        fixture.runtime.beginClose(Optional.of(ExecutionScope.tryBeginWorldStop().orElseThrow()));
        AtomicInteger bodies = new AtomicInteger(), settlements = new AtomicInteger();
        assertEquals(CharacterRuntime.State.CLOSING, fixture.runtime.snapshot().state());
        assertThrows(CharacterRuntime.Invalid.class, fixture.runtime::acceptOpeningWork);
        assertThrows(CharacterRuntime.Invalid.class, () -> fixture.runtime.submitOpening(
                mock(ExecutionScope.WorkHandle.class), bodies::incrementAndGet, settlements::incrementAndGet));

        var submission = assertDoesNotThrow(() -> fixture.runtime.submitOpening(
                work, bodies::incrementAndGet, settlements::incrementAndGet));
        assertEquals(CharacterCommandLane.Status.ENQUEUED, submission.status());
        assertFalse(work.isSettled());
        assertEquals(1, fixture.runtime.snapshot().lane().pending());
        fixture.runQueued();

        assertTrue(work.isSettled());
        assertTrue(work.snapshot().skipped());
        assertEquals(0, bodies.get());
        assertEquals(1, settlements.get());
        assertEquals(0, fixture.role.releases.get());
        assertEquals(0, fixture.runtime.lifecycleScope().resources().snapshot().registeredTotal());
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
        assertTrue(fixture.executor.isShutdown());
    }

    @Test
    void independentModulesShareOneLaneAndAConflictingControlWaitsForItsActualOwnerToLeave() {
        Fixture fixture = opened();
        var movement = fixture.mount("movement", Set.of("movement"));
        var chat = fixture.mount("chat", Set.of("chat"));
        var movementContext = fixture.runtime.context(movement);
        var chatContext = fixture.runtime.context(chat);
        var moveWork = movementContext.acceptWork();
        var chatWork = chatContext.acceptWork();
        var moveLane = movementContext.prepareNative("movement", moveWork, () -> {}, (lane, permit, work, role) -> lane);
        var chatLane = chatContext.prepareNative("chat", chatWork, () -> {}, (lane, permit, work, role) -> lane);
        assertSame(moveLane, chatLane);
        List<String> order = new ArrayList<>();
        movementContext.submit("movement", moveWork, () -> {}, () -> order.add("move"), () -> {});
        chatContext.submit("chat", chatWork, () -> {}, () -> order.add("chat"), () -> {});
        assertEquals(2, fixture.manual.size());
        assertEquals(2, fixture.runtime.snapshot().lane().ordinary());

        var contender = fixture.mount("contender", Set.of("movement"));
        assertEquals(CharacterRuntime.ModuleState.MOUNTED, fixture.module("contender").state());
        assertEquals("control_busy", fixture.module("contender").reason());
        assertThrows(CharacterRuntime.Invalid.class, () -> fixture.runtime.context(contender));
        fixture.runQueued();
        assertEquals(List.of("move", "chat"), order);
        var stopped = fixture.runtime.stop(movement, CharacterRuntime.StopMode.UNLOAD);
        assertTrue(stopped.complete());
        var admitted = fixture.runtime.activate(contender);
        assertNotNull(fixture.runtime.context(admitted));
        assertEquals(CharacterRuntime.ModuleState.ACTIVE, fixture.module("chat").state());
    }

    @Test
    void initializationFailureWaitsForItsCreatedResourceToReallyLeave() {
        Fixture fixture = opened();
        AtomicReference<CharacterRuntime.ModuleContext> context = new AtomicReference<>();
        AtomicReference<ScopeResources.Registration> subscription = new AtomicReference<>();
        Object listener = new Object();
        var definition = definition(() -> new RuntimeModule() {
            @Override public void onActivated(CharacterRuntime.ModuleContext active) {
                context.set(active);
                var resource = active.scope().resources().beginRegistration("module-listener");
                resource.attach(listener, () -> {});
                subscription.set(resource);
                throw new IllegalStateException("initialization failed after subscribing");
            }
        }, List.of());
        assertEquals("module_activation_failed", assertThrows(CharacterRuntime.Invalid.class,
                () -> fixture.runtime.mount(definition, new ModuleSelection<>("broken", null, true))).reason());
        assertEquals(CharacterRuntime.ModuleState.STOPPING, fixture.module("broken").state());

        var cleanup = subscription.get().acquireCleanup();
        context.get().submitCleanup(cleanup, () -> subscription.get().confirmReleased(cleanup.permit(), listener), () -> {});
        fixture.runQueued();
        assertTrue(fixture.runtime.snapshot().modules().isEmpty());
        assertTrue(fixture.role.online);
    }

    @Test
    void exportsBecomeAvailableOnlyAfterTheProviderFinishesInitializing() throws Exception {
        Fixture fixture = opened();
        var caller = fixture.runtime.context(fixture.mount("caller", Set.of()));
        CountDownLatch initializing = new CountDownLatch(1), initialized = new CountDownLatch(1);
        var function = new ModuleFunction<RuntimeModule>("sample.ready", "Read initialized module state", List.of(),
                "Returns ready after module initialization", (instance, context, work, arguments) -> "ready");
        var definition = definition(() -> new RuntimeModule() {
            @Override public void onActivated(CharacterRuntime.ModuleContext context) {
                initializing.countDown();
                await(initialized);
            }
        }, List.of(function));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var mounting = executor.submit(() -> fixture.runtime.mount(definition, new ModuleSelection<>("provider", null, true)));
            try {
                await(initializing);
                assertEquals("module_initializing", caller.call("sample.ready", Map.of()).get("reason"));
                assertEquals(false, caller.help().getFirst().get("available"));
                initialized.countDown();
                mounting.get(5, TimeUnit.SECONDS);
                assertEquals("ready", caller.call("sample.ready", Map.of()).get("result"));
            } finally { initialized.countDown(); }
        }
    }

    @Test
    void failedInitializationCannotEraseItsCleanupFailureWhenUpgradingToUnload() {
        Fixture fixture = opened();
        var definition = definition(() -> new RuntimeModule() {
            @Override public void onActivated(CharacterRuntime.ModuleContext context) {
                throw new IllegalStateException("initialization failed");
            }
            @Override public void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
                throw new IllegalStateException("cleanup remains unverified");
            }
        }, List.of());
        assertThrows(CharacterRuntime.Invalid.class,
                () -> fixture.runtime.mount(definition, new ModuleSelection<>("broken", null, true)));
        assertEquals(CharacterRuntime.ModuleState.FAILED, fixture.module("broken").state());
        assertEquals("module_stop_hook_failed", fixture.module("broken").reason());
    }

    @Test
    void reusableDefinitionKeepsConfigurationAndPrivateProgressSeparateAcrossRoles() {
        Fixture first = opened(), second = opened();
        class Counter implements RuntimeModule {
            int value;
            Counter(int initial) { value = initial; }
        }
        var add = new ModuleFunction<Counter>("sample.add", "Advance this module's private progress",
                List.of(new ModuleFunction.Parameter("amount", ModuleFunction.Type.INTEGER, true, "Progress increment")),
                "Returns the updated private progress", (instance, context, work, arguments) ->
                instance.value += ((Number) arguments.get("amount")).intValue());
        var definition = new ModuleDefinition<Integer, Counter>("counter", Set.of(),
                new ExecutionScope.Limits(8, 2, 8, 2), initial -> {}, Counter::new, List.of(add));
        var providerOne = first.runtime.mount(definition, new ModuleSelection<>("counter", 1, true));
        second.runtime.mount(definition, new ModuleSelection<>("counter", 10, true));
        var callerOne = first.runtime.context(first.mount("caller", Set.of()));
        var callerTwo = second.runtime.context(second.mount("caller", Set.of()));
        assertEquals(3, callerOne.call("sample.add", Map.of("amount", 2)).get("result"));
        assertEquals(12, callerTwo.call("sample.add", Map.of("amount", 2)).get("result"));
        assertEquals(5, callerOne.call("sample.add", Map.of("amount", 2)).get("result"));
        assertEquals("sample.add", callerOne.help().getFirst().get("name"));
        assertThrows(IllegalArgumentException.class, () -> callerOne.call("sample.add", Map.of("amount", "two")));
        assertEquals(5, providerOne.instance().value);
        assertEquals("function_missing", callerOne.call("missing.function", Map.of()).get("reason"));
        var duplicate = assertThrows(CharacterRuntime.Invalid.class,
                () -> first.runtime.mount(definition, new ModuleSelection<>("duplicate", 0, true)));
        assertEquals("module_function_exists", duplicate.reason());

        first.runtime.stop(providerOne.reference(), CharacterRuntime.StopMode.PAUSE).await(WAIT);
        assertEquals(false, callerOne.help().getFirst().get("available"));
        assertEquals("UNAVAILABLE", callerOne.call("sample.add", Map.of("amount", 2)).get("status"));
        var resumed = first.runtime.activate(providerOne.reference());
        assertEquals(7, callerOne.call("sample.add", Map.of("amount", 2)).get("result"));
        first.runtime.stop(resumed, CharacterRuntime.StopMode.UNLOAD).await(WAIT);
        assertTrue(callerOne.help().isEmpty());
        assertEquals("function_missing", callerOne.call("sample.add", Map.of()).get("reason"));
    }

    @Test
    void unloadingAnExportProviderWaitsForItsActualInvocationToExit() throws Exception {
        Fixture fixture = opened();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean unloaded = new AtomicBoolean();
        var function = new ModuleFunction<RuntimeModule>("sample.wait", "Finish an accepted task", List.of(),
                "Completes after the accepted task exits", (instance, context, work, arguments) -> {
                    entered.countDown();
                    await(release);
                    return "finished";
                });
        var provider = fixture.runtime.mount(definition(() -> new RuntimeModule() {
            @Override public void onUnloaded() { unloaded.set(true); }
        }, List.of(function)), new ModuleSelection<>("provider", null, true));
        var caller = fixture.runtime.context(fixture.mount("caller", Set.of()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var invocation = executor.submit(() -> caller.call("sample.wait", Map.of()));
            try {
                await(entered);
                var stop = fixture.runtime.stop(provider.reference(), CharacterRuntime.StopMode.UNLOAD);
                assertFalse(stop.complete());
                assertFalse(unloaded.get());
                assertEquals("UNAVAILABLE", caller.call("sample.wait", Map.of()).get("status"));
                release.countDown();
                assertEquals("finished", invocation.get(5, TimeUnit.SECONDS).get("result"));
                assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(WAIT));
                assertTrue(unloaded.get());
            } finally { release.countDown(); }
        }
    }

    @Test
    void cancellingQueuedWorkSkipsItsBodyButKeepsTheCarrierUntilPhysicalSettlement() {
        Fixture fixture = opened();
        var context = fixture.runtime.context(fixture.mount("mcp", Set.of("chat")));
        AtomicInteger bodies = new AtomicInteger(), settlements = new AtomicInteger();
        var cancelled = context.acceptWork();
        context.submit("chat", cancelled, () -> {}, bodies::incrementAndGet, settlements::incrementAndGet);
        context.cancel(cancelled);
        var ordinary = context.acceptWork();
        context.submit("chat", ordinary, () -> {}, bodies::incrementAndGet, settlements::incrementAndGet);
        assertFalse(cancelled.isSettled());
        assertEquals(2, fixture.runtime.snapshot().lane().pending());
        fixture.runQueued();
        assertEquals(1, bodies.get());
        assertEquals(2, settlements.get());
        assertTrue(cancelled.isSettled());
        assertTrue(cancelled.snapshot().skipped());
        assertEquals(0, fixture.runtime.snapshot().lane().pending());
        assertEquals(CharacterRuntime.State.BOUND, fixture.runtime.snapshot().state());
    }

    @Test
    void pausedModuleCannotResumeBeforeQueuedCarriersDrainAndThenGetsANewGeneration() {
        Fixture fixture = opened();
        var reference = fixture.mount("mcp", Set.of("chat"));
        var oldContext = fixture.runtime.context(reference);
        var oldWork = oldContext.acceptWork();
        oldContext.submit("chat", oldWork, () -> {}, () -> fail("paused queued body"), () -> {});
        var stop = fixture.runtime.stop(reference, CharacterRuntime.StopMode.PAUSE);
        assertEquals(ExecutionScope.DrainResult.TIMED_OUT, stop.await(Duration.ZERO));
        assertFalse(stop.complete());
        assertThrows(CharacterRuntime.Invalid.class, () -> fixture.runtime.activate(reference));
        assertThrows(CharacterRuntime.Invalid.class, oldContext::acceptWork);
        fixture.runQueued();
        assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(WAIT));
        assertTrue(stop.complete());
        assertEquals(CharacterRuntime.ModuleState.SUSPENDED, fixture.module("mcp").state());

        var resumed = fixture.runtime.activate(reference);
        assertTrue(resumed.generation() > reference.generation());
        var newContext = fixture.runtime.context(resumed);
        assertNotSame(oldContext, newContext);
        assertThrows(CharacterRuntime.Invalid.class, oldContext::acceptWork);
        assertFalse(oldContext.publishOnce(oldWork, () -> fail("old generation publication")));
        var next = newContext.acceptWork();
        AtomicBoolean executed = new AtomicBoolean();
        newContext.submit("chat", next, () -> {}, () -> executed.set(true), () -> {});
        fixture.runQueued();
        assertTrue(executed.get());
        assertTrue(fixture.runtime.stop(resumed, CharacterRuntime.StopMode.PAUSE).complete());
    }

    @Test
    void completedPauseCanUpgradeToUnloadWithoutInvokingTheStopHookTwice() {
        Fixture fixture = opened();
        AtomicInteger stopped = new AtomicInteger(), unloaded = new AtomicInteger();
        var reference = fixture.mount("mcp", Set.of("chat"), new RuntimeModule() {
            @Override public void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) { stopped.incrementAndGet(); }
            @Override public void onUnloaded() { unloaded.incrementAndGet(); }
        }, true);
        assertTrue(fixture.runtime.stop(reference, CharacterRuntime.StopMode.PAUSE).complete());
        assertTrue(fixture.runtime.stop(reference, CharacterRuntime.StopMode.UNLOAD).complete());
        assertTrue(fixture.runtime.snapshot().modules().isEmpty());
        assertEquals(1, stopped.get());
        assertEquals(1, unloaded.get());
        assertTrue(fixture.role.online);
    }

    @Test
    void moduleUnloadPreservesTheRoleAndAllowsFreshControlOnTheSameRuntime() {
        Fixture fixture = opened();
        String runtimeId = fixture.runtime.id();
        var first = fixture.mount("mcp", Set.of("chat"));
        assertTrue(fixture.runtime.stop(first, CharacterRuntime.StopMode.UNLOAD).complete());
        assertEquals(CharacterRuntime.State.BOUND, fixture.runtime.snapshot().state());
        assertEquals(1, fixture.runtime.snapshot().rootScope().outstandingResources());
        assertEquals(0, fixture.role.releases.get());
        assertTrue(fixture.role.online);
        var second = fixture.mount("mcp", Set.of("chat"));
        assertTrue(second.generation() > first.generation());
        assertEquals(runtimeId, fixture.runtime.id());
        assertEquals(101, fixture.runtime.observeOrigin().get("character_id"));
    }

    @Test
    void onlyRootOriginCleanupClosesTheRoleAndItsLaneAfterModulesUnload() {
        Fixture fixture = opened();
        fixture.mount("mcp", Set.of("chat"));
        fixture.runtime.beginClose(Optional.empty());
        assertEquals(CharacterRuntime.State.CLOSING, fixture.runtime.snapshot().state());
        assertEquals(ExecutionScope.DrainResult.TIMED_OUT, fixture.runtime.awaitClosed(Duration.ZERO));
        var cleanup = fixture.cleanupOrigin();
        assertEquals(0, fixture.role.releases.get());
        fixture.runQueued();
        assertTrue(cleanup.work().isSettled());
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
        assertTrue(fixture.runtime.snapshot().lane().closed());
        assertTrue(fixture.runtime.originReleased());
        assertFalse(fixture.role.online);
        assertEquals(1, fixture.role.releases.get());
        fixture.runtime.beginClose(Optional.empty());
        assertEquals(1, fixture.role.releases.get());
        assertTrue(fixture.executor.isShutdown());
    }

    @Test
    void negativeReleaseProofKeepsTheOriginAndRuntimeUnclosedUntilVerifiedRetry() {
        Fixture fixture = opened();
        fixture.role.releaseVerified = false;
        fixture.runtime.beginClose(Optional.empty());
        fixture.cleanupOrigin();
        var failure = assertThrows(CharacterRuntime.Invalid.class, fixture::runQueued);
        assertEquals("native_role_release_unverified", failure.reason());
        assertFalse(fixture.runtime.originReleased());
        assertEquals(CharacterRuntime.State.CLOSING, fixture.runtime.snapshot().state());
        assertNotEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(Duration.ZERO));
        assertTrue(fixture.role.online);

        fixture.role.releaseVerified = true;
        fixture.cleanupOrigin();
        fixture.runQueued();
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertEquals(2, fixture.role.releases.get());
    }

    @Test
    void anUnreleasedModuleResourceBlocksRoleCleanupUntilItsOwnCleanupActuallySettles() {
        Fixture fixture = opened();
        var context = fixture.runtime.context(fixture.mount("mcp", Set.of("chat")));
        Object subscription = new Object();
        var resource = context.scope().resources().beginRegistration("subscription");
        resource.attach(subscription, () -> {});
        fixture.runtime.beginClose(Optional.empty());
        assertEquals(CharacterRuntime.ModuleState.STOPPING, fixture.module("mcp").state());
        fixture.cleanupOrigin();
        var failure = assertThrows(CharacterRuntime.Invalid.class, fixture::runQueued);
        assertEquals("runtime_modules_not_drained", failure.reason());
        assertEquals(0, fixture.role.releases.get());
        assertFalse(fixture.runtime.tryFinalizeClose());

        var cleanup = resource.acquireCleanup();
        context.submitCleanup(cleanup, () -> resource.confirmReleased(cleanup.permit(), subscription), () -> {});
        fixture.runQueued();
        assertTrue(fixture.runtime.snapshot().modules().isEmpty());
        fixture.cleanupOrigin();
        fixture.runQueued();
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
    }

    @Test
    void activationFailureReleasesControlWithoutClosingTheOrigin() {
        Fixture fixture = opened();
        var failure = assertThrows(CharacterRuntime.Invalid.class, () -> fixture.mount("bad", Set.of("chat"), new RuntimeModule() {
                    @Override public void onActivated(CharacterRuntime.ModuleContext context) { throw new IllegalStateException("private hook text"); }
                }, true));
        assertEquals("module_activation_failed", failure.reason());
        assertTrue(fixture.runtime.snapshot().modules().isEmpty());
        assertEquals("module_activation_failed", fixture.runtime.snapshot().failure());
        assertNotNull(fixture.runtime.context(fixture.mount("replacement", Set.of("chat"))));
        assertTrue(fixture.role.online);
        assertEquals(0, fixture.role.releases.get());
    }

    @Test
    void stopHookFailureRemainsVisibleWhileDrainedControlCanBeReused() {
        Fixture fixture = opened();
        var reference = fixture.mount("bad", Set.of("chat"), new RuntimeModule() {
            @Override public void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
                throw new IllegalStateException("private hook text");
            }
        }, true);
        var stop = fixture.runtime.stop(reference, CharacterRuntime.StopMode.PAUSE);
        assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(WAIT));
        assertTrue(stop.complete());
        assertEquals("module_stop_hook_failed", stop.failure());
        assertEquals(CharacterRuntime.ModuleState.FAILED, fixture.module("bad").state());
        assertNotNull(fixture.runtime.context(fixture.mount("replacement", Set.of("chat"))));
        assertEquals(0, fixture.role.releases.get());
    }

    @Test
    void unloadHookFailureKeepsTheFailedInstanceUntilItsCleanupIsVerified() {
        Fixture fixture = opened();
        var definition = definition(() -> new RuntimeModule() {
            @Override public void onUnloaded() { throw new IllegalStateException("private hook text"); }
        }, List.of());
        var reference = fixture.runtime.mount(definition, new ModuleSelection<>("bad", null, true)).reference();
        var stop = fixture.runtime.stop(reference, CharacterRuntime.StopMode.UNLOAD);
        assertFalse(stop.complete());
        assertEquals(ExecutionScope.DrainResult.UNVERIFIED, stop.await(Duration.ZERO));
        assertEquals(CharacterRuntime.ModuleState.FAILED, fixture.module("bad").state());
        assertEquals("module_unload_hook_failed", fixture.runtime.snapshot().failure());
        assertTrue(fixture.role.online);
    }

    @Test
    void stoppingInsideARealCarrierReturnsSelfWaitAndCompletesOnlyAfterThatCarrierExits() throws Exception {
        Fixture fixture = real();
        fixture.open();
        var reference = fixture.mount("mcp", Set.of("chat"));
        var context = fixture.runtime.context(reference);
        var work = context.acceptWork();
        AtomicReference<CharacterRuntime.ModuleStop> stop = new AtomicReference<>();
        CompletableFuture<ExecutionScope.DrainResult> selfWait = new CompletableFuture<>();
        context.submit("chat", work, () -> {}, () -> {
            stop.set(fixture.runtime.stop(reference, CharacterRuntime.StopMode.PAUSE));
            selfWait.complete(stop.get().await(Duration.ZERO));
        }, () -> {});
        assertEquals(ExecutionScope.DrainResult.SELF_WAIT, selfWait.get(5, TimeUnit.SECONDS));
        work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(ExecutionScope.DrainResult.DRAINED, stop.get().await(WAIT));
        assertTrue(stop.get().complete());
        assertEquals(CharacterRuntime.ModuleState.SUSPENDED, fixture.module("mcp").state());
        assertEquals(0, fixture.role.releases.get());
    }

    @Test
    void matchingWorldStopTicketAllowsOnlyOwnedCleanupAndNoFurtherModuleCommands() {
        Fixture fixture = opened();
        var context = fixture.runtime.context(fixture.mount("mcp", Set.of("chat")));
        var ticket = ExecutionScope.tryBeginWorldStop().orElseThrow();
        fixture.runtime.beginClose(Optional.of(ticket));
        assertThrows(CharacterRuntime.Invalid.class, context::acceptWork);
        assertSame(ticket, ExecutionScope.tryBeginWorldStop().orElseThrow());
        fixture.cleanupOrigin();
        fixture.runQueued();
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertEquals(1, fixture.role.releases.get());
        assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
    }

    @Test
    void lateFactoryResultRetainsItsStoppedReferenceAndUsesOnlyTheOriginalRootCleanupWindow() {
        Fixture fixture = manual();
        var work = fixture.runtime.acceptOpeningWork();
        AtomicReference<TestRole> late = new AtomicReference<>();
        fixture.runtime.submitOpening(work, () -> {
            var origin = fixture.runtime.reserveOrigin(work);
            var stop = ExecutionScope.tryBeginWorldStop().orElseThrow();
            fixture.runtime.beginClose(Optional.of(stop));
            TestRole result = new TestRole(WorldEpoch.snapshot().token());
            late.set(result);
            assertSame(stop.stoppedEpoch(), result.characterRef().epoch());
            assertNotSame(fixture.runtime.acceptedEpoch(), result.characterRef().epoch());

            origin.adopt(result);
            assertEquals(1, fixture.runtime.lifecycleScope().resources().snapshot().attached());
            assertEquals(0, fixture.runtime.lifecycleScope().resources().snapshot().pending());
            assertThrows(CharacterRuntime.Invalid.class, fixture.runtime::bindAdoptedOrigin);
            assertThrows(CharacterRuntime.Invalid.class, fixture.runtime::observeOrigin);
            assertSame(stop.stoppedEpoch(), result.characterRef().epoch());
            assertFalse(fixture.runtime.originReleased());

            var cleanup = fixture.runtime.acquireOriginCleanup();
            assertSame(fixture.runtime.lifecycleScope(), cleanup.work().scope());
            assertSame(fixture.runtime.acceptedEpoch(), cleanup.work().acceptedEpoch());
            fixture.runtime.lifecycleScope().runTracked(cleanup.work(), () -> fixture.runtime.releaseOrigin(cleanup));
            assertTrue(fixture.runtime.originReleased());
        }, () -> {});
        fixture.runQueued();
        assertTrue(work.isSettled());
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
        assertEquals(1, late.get().releases.get());
        assertFalse(late.get().online);
        assertEquals(0, fixture.role.releases.get());
    }

    @Test
    void lateFactoryResultAfterRestartRemainsOwnedButCannotBindOrBorrowTheNewWorldsCleanupWindow() {
        Fixture fixture = manual();
        var work = fixture.runtime.acceptOpeningWork();
        AtomicReference<TestRole> late = new AtomicReference<>();
        fixture.runtime.submitOpening(work, () -> {
            var origin = fixture.runtime.reserveOrigin(work);
            fixture.runtime.beginClose(Optional.of(ExecutionScope.tryBeginWorldStop().orElseThrow()));
            WorldEpoch.onWorldStarted();
            TestRole result = new TestRole(WorldEpoch.snapshot().token());
            late.set(result);

            origin.adopt(result);
            assertEquals(1, fixture.runtime.lifecycleScope().resources().snapshot().attached());
            assertEquals(0, fixture.runtime.lifecycleScope().resources().snapshot().pending());
            assertNotSame(fixture.runtime.acceptedEpoch(), result.characterRef().epoch());
            assertThrows(CharacterRuntime.Invalid.class, fixture.runtime::bindAdoptedOrigin);
            assertThrows(RuntimeException.class, fixture.runtime::acquireOriginCleanup);
            assertThrows(RuntimeException.class, origin::confirmAbsent);
            assertFalse(fixture.runtime.originReleased());
        }, () -> {});
        fixture.runQueued();
        assertTrue(work.isSettled());
        assertEquals(1, fixture.runtime.lifecycleScope().resources().snapshot().outstanding());
        assertNotEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(Duration.ZERO));
        assertEquals(CharacterRuntime.State.CLOSING, fixture.runtime.snapshot().state());
        assertEquals(0, late.get().releases.get());
        assertTrue(late.get().online);
    }

    @Test
    void aNewWorldCannotRenewAnOldOriginCleanupWindowOrAnOldModuleContext() {
        Fixture fixture = opened();
        var context = fixture.runtime.context(fixture.mount("mcp", Set.of("chat")));
        fixture.runtime.beginClose(Optional.of(ExecutionScope.tryBeginWorldStop().orElseThrow()));
        WorldEpoch.onWorldStarted();
        assertThrows(CharacterRuntime.Invalid.class, context::acceptWork);
        assertThrows(RuntimeException.class, fixture.runtime::acquireOriginCleanup);
        assertFalse(fixture.runtime.originReleased());
        assertFalse(fixture.runtime.tryFinalizeClose());
        assertEquals(CharacterRuntime.State.CLOSING, fixture.runtime.snapshot().state());
        assertEquals(0, fixture.role.releases.get());
        Fixture replacement = opened();
        assertNotEquals(fixture.runtime.acceptedEpoch(), replacement.runtime.acceptedEpoch());
        assertEquals(CharacterRuntime.State.BOUND, replacement.runtime.snapshot().state());
    }

    @Test
    void anActivationHookStillRunningPreventsUnloadAndNativeOriginRelease() throws Exception {
        Fixture fixture = opened();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<CharacterRuntime.ModuleContext> activating = new AtomicReference<>();
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            var mounting = caller.submit(() -> fixture.mount("mcp", Set.of("chat"), new RuntimeModule() {
                @Override public void onActivated(CharacterRuntime.ModuleContext context) {
                    activating.set(context);
                    entered.countDown();
                    await(release);
                }
            }, true));
            CharacterRuntime.ModuleStop stop;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                stop = fixture.runtime.stop(activating.get().reference(), CharacterRuntime.StopMode.UNLOAD);
                assertFalse(stop.complete());
                assertEquals(ExecutionScope.DrainResult.TIMED_OUT, stop.await(Duration.ZERO));
                fixture.runtime.beginClose(Optional.empty());
                fixture.cleanupOrigin();
                var failure = assertThrows(CharacterRuntime.Invalid.class, fixture::runQueued);
                assertEquals("runtime_modules_not_drained", failure.reason());
                assertEquals(0, fixture.role.releases.get());
                assertFalse(fixture.runtime.tryFinalizeClose());
            } finally {
                release.countDown();
            }
            mounting.get(5, TimeUnit.SECONDS);
            assertTrue(stop.complete());
            fixture.cleanupOrigin();
            fixture.runQueued();
            assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        }
    }

    @Test
    void anUnloadHookStillRunningIsNeitherCompletedNorPermissionToReleaseTheRole() throws Exception {
        Fixture fixture = opened();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var reference = fixture.mount("mcp", Set.of("chat"), new RuntimeModule() {
            @Override public void onUnloaded() { entered.countDown(); await(release); }
        }, true);
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            var unloading = caller.submit(() -> fixture.runtime.stop(reference, CharacterRuntime.StopMode.UNLOAD));
            CharacterRuntime.ModuleStop stop;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                stop = fixture.runtime.stop(reference, CharacterRuntime.StopMode.UNLOAD);
                assertFalse(stop.complete());
                assertEquals(ExecutionScope.DrainResult.TIMED_OUT, stop.await(Duration.ZERO));
                fixture.runtime.beginClose(Optional.empty());
                fixture.cleanupOrigin();
                var failure = assertThrows(CharacterRuntime.Invalid.class, fixture::runQueued);
                assertEquals("runtime_modules_not_drained", failure.reason());
                assertEquals(0, fixture.role.releases.get());
                assertEquals(CharacterRuntime.State.CLOSING, fixture.runtime.snapshot().state());
            } finally {
                release.countDown();
            }
            assertSame(stop, unloading.get(5, TimeUnit.SECONDS));
            assertTrue(stop.complete());
            fixture.cleanupOrigin();
            fixture.runQueued();
            assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        }
    }

    @Test
    void aSecondRuntimeCannotAdoptTheSameNativeCharacterOrDisruptItsCurrentOwner() {
        Fixture first = opened(), second = manual();
        var work = second.runtime.acceptOpeningWork();
        second.runtime.submitOpening(work, () -> {
            var origin = second.runtime.reserveOrigin(work);
            var failure = assertThrows(CharacterRuntime.Invalid.class, () -> origin.adopt(first.role));
            assertEquals("native_role_already_owned", failure.reason());
            origin.confirmAbsent();
        }, () -> {});
        second.runQueued();
        second.runtime.beginClose(Optional.empty());
        assertEquals(ExecutionScope.DrainResult.DRAINED, second.runtime.awaitClosed(WAIT));
        assertEquals(CharacterRuntime.State.BOUND, first.runtime.snapshot().state());
        assertEquals(1, first.runtime.snapshot().rootScope().outstandingResources());
        assertEquals(0, first.role.releases.get());
        assertTrue(first.role.online);

        var context = first.runtime.context(first.mount("mcp", Set.of("chat")));
        AtomicBoolean executed = new AtomicBoolean();
        var command = context.acceptWork();
        context.submit("chat", command, () -> {}, () -> executed.set(true), () -> {});
        first.runQueued();
        assertTrue(executed.get());
    }

    @Test
    void aForeignCleanupPermitForTheSameIdentityCannotInvokeTheOriginReleaseAdapter() {
        Fixture fixture = opened();
        ExecutionScope foreign = new ExecutionScope(fixture.runtime.acceptedEpoch(),
                new ExecutionScope.Limits(4, 1, 4, 1));
        var registration = foreign.resources().beginRegistration("foreign_native_role");
        registration.attach(fixture.role.identity(), () -> {});
        var cleanup = registration.acquireCleanup();
        foreign.runTracked(cleanup.work(), () -> {
            var failure = assertThrows(CharacterRuntime.Invalid.class, () -> fixture.runtime.releaseOrigin(cleanup));
            assertEquals("foreign_origin_cleanup", failure.reason());
            assertEquals(0, fixture.role.releases.get());
            registration.confirmReleased(cleanup.permit(), fixture.role.identity());
        });
        foreign.beginDrain();
        assertEquals(ExecutionScope.DrainResult.DRAINED, foreign.awaitDrained(WAIT));
        assertTrue(fixture.role.online);
        assertFalse(fixture.runtime.originReleased());
        assertEquals(CharacterRuntime.State.BOUND, fixture.runtime.snapshot().state());
    }

    @Test
    void concurrentModuleUnloadCannotAbortTheRemainingRootCloseAndDrain() throws Exception {
        Fixture fixture = opened();
        CountDownLatch stopping = new CountDownLatch(1), release = new CountDownLatch(1);
        fixture.mount("first", Set.of("movement"), new RuntimeModule() {
            @Override public void onStopping(CharacterRuntime.ModuleContext context, CharacterRuntime.StopMode mode) {
                stopping.countDown();
                await(release);
            }
        }, true);
        var second = fixture.mount("second", Set.of("chat"));
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            var closing = caller.submit(() -> fixture.runtime.beginClose(Optional.empty()));
            try {
                assertTrue(stopping.await(5, TimeUnit.SECONDS));
                assertTrue(fixture.runtime.stop(second, CharacterRuntime.StopMode.UNLOAD).complete());
                assertTrue(fixture.runtime.snapshot().modules().stream().noneMatch(module -> module.instanceId().equals("second")));
            } finally {
                release.countDown();
            }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionScope.State.DRAINING, fixture.runtime.snapshot().rootScope().state());
            assertTrue(fixture.runtime.snapshot().modules().isEmpty());
            fixture.cleanupOrigin();
            fixture.runQueued();
            assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
            assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
            assertEquals(1, fixture.role.releases.get());
        }
    }

    @Test
    void closingBarrierWaitsForTheOrchestrationCarrierAfterItsBodyHasExited() throws Exception {
        Fixture fixture = opened();
        var context = fixture.runtime.context(fixture.mount("movement", Set.of("movement")));
        var parent = context.acceptWork();
        assertTrue(parent.claimCarrier());
        assertTrue(context.scope().runTracked(parent, () -> {}));
        assertEquals(ExecutionScope.BodyState.EXITED, parent.snapshot().bodyState());
        assertFalse(parent.isSettled());
        var barrier = fixture.runtime.whenModulesDrained().toCompletableFuture();

        fixture.runtime.beginClose(Optional.empty());
        assertFalse(barrier.isDone());
        assertEquals(CharacterRuntime.State.CLOSING, fixture.runtime.snapshot().state());
        assertEquals(CharacterRuntime.ModuleState.STOPPING, fixture.module("movement").state());
        assertEquals(0, fixture.role.releases.get());
        assertFalse(fixture.runtime.originReleased());

        parent.carrierSettled();
        barrier.get(5, TimeUnit.SECONDS);
        assertTrue(parent.isSettled());
        assertTrue(fixture.runtime.snapshot().modules().isEmpty());
        assertEquals(0, fixture.role.releases.get(), "Module drain is not proof of native role release");
        assertFalse(fixture.runtime.originReleased());

        fixture.cleanupOrigin();
        fixture.runQueued();
        assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
        assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
        assertEquals(1, fixture.role.releases.get());
    }

    @Test
    void closingBarrierRunsSubscribersOutsideTheHostMonitorAndMayQueueNormalRootCleanup() throws Exception {
        Fixture fixture = opened();
        var context = fixture.runtime.context(fixture.mount("movement", Set.of("movement")));
        var parent = context.acceptWork();
        assertTrue(parent.claimCarrier());
        assertTrue(context.scope().runTracked(parent, () -> {}));
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<CharacterRuntime.Snapshot> snapshot = new AtomicReference<>();
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        try (ExecutorService observer = Executors.newSingleThreadExecutor()) {
            var callback = fixture.runtime.whenModulesDrained().thenRun(() -> {
                observer.submit(() -> {
                    try { snapshot.set(fixture.runtime.snapshot()); }
                    catch (Throwable failure) { observerFailure.set(failure); }
                    finally { observed.countDown(); }
                });
                // A lock-held completion would block this other thread while its subscriber is waiting.
                await(observed);
                assertNull(observerFailure.get());
                assertEquals(CharacterRuntime.State.CLOSING, snapshot.get().state());
                assertTrue(snapshot.get().modules().isEmpty());
                fixture.cleanupOrigin();
                assertEquals(0, fixture.role.releases.get());
            });
            fixture.runtime.beginClose(Optional.empty());
            assertFalse(callback.toCompletableFuture().isDone());
            parent.carrierSettled();
            callback.toCompletableFuture().get(5, TimeUnit.SECONDS);
            fixture.runQueued();
            assertEquals(ExecutionScope.DrainResult.DRAINED, fixture.runtime.awaitClosed(WAIT));
            assertEquals(CharacterRuntime.State.CLOSED, fixture.runtime.snapshot().state());
            assertEquals(1, fixture.role.releases.get());
        }
    }

    @Test
    void actionFactsRemainNeutralAndBoundToTheOriginalRoleWorldAndModuleWork() {
        Fixture fixture = opened();
        var context = fixture.runtime.context(fixture.mount("inventory", Set.of("inventory")));
        var other = fixture.runtime.context(fixture.mount("chat", Set.of("chat")));
        var work = context.acceptWork();
        NativeActionFact fact = actionFact(101, fixture.runtime.acceptedEpoch().value());
        assertThrows(CharacterRuntime.Invalid.class, () -> other.recordResult(work, fact));
        assertThrows(CharacterRuntime.Invalid.class, () -> context.recordResult(work,
                actionFact(102, fixture.runtime.acceptedEpoch().value())));
        assertThrows(CharacterRuntime.Invalid.class, () -> context.recordResult(work, actionFact(101, "other_world")));
        assertThrows(CharacterRuntime.Invalid.class, () -> context.recordResult(work, new NativeActionFact() {
            public int senderCharacterId() { return 101; }
            public String epoch() { return fixture.runtime.acceptedEpoch().value(); }
            public NativeActionResult.Outcome outcome() { return NativeActionResult.Outcome.FAILED; }
            public String fixedReason() { return "private\nexception body"; }
        }));
        context.reject(work);
        fixture.runtime.stop(context.reference(), CharacterRuntime.StopMode.PAUSE);
        // A late native fact is observable, but grants no authority to the stopped module.
        context.recordResult(work, fact);
        assertThrows(CharacterRuntime.Invalid.class, context::acceptWork);
        var events = fixture.runtime.events().events().stream()
                .filter(event -> event.kind() == RuntimeEvents.Kind.ACTION_RESULT).toList();
        assertEquals(1, events.size());
        assertEquals("inventory", events.getFirst().moduleId());
        assertEquals(work.snapshot().id(), events.getFirst().workId());
        assertEquals("SUCCEEDED", events.getFirst().outcome());
        assertNull(events.getFirst().reason());
    }

    private static NativeActionFact actionFact(int characterId, String epoch) {
        return new NativeActionFact() {
            public int senderCharacterId() { return characterId; }
            public String epoch() { return epoch; }
            public NativeActionResult.Outcome outcome() { return NativeActionResult.Outcome.SUCCEEDED; }
            public String fixedReason() { return null; }
        };
    }

    private Fixture manual() { return fixture(new ManualExecutor(8)); }
    private Fixture real() { return fixture(new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8))); }
    private Fixture fixture(ExecutorService executor) {
        Fixture fixture = new Fixture(executor);
        fixtures.add(fixture);
        return fixture;
    }
    private Fixture opened() { Fixture fixture = manual(); fixture.open(); return fixture; }
    private static ModuleDefinition<Void, RuntimeModule> definition(
            java.util.function.Supplier<RuntimeModule> factory, List<ModuleFunction<RuntimeModule>> functions) {
        return new ModuleDefinition<>("test-module", Set.of(), new ExecutionScope.Limits(16, 4, 16, 4),
                ignored -> {}, ignored -> factory.get(), functions);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "runtime_hook_latch_timeout"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Fixture {
        final ExecutorService executor;
        final ManualExecutor manual;
        final CharacterRuntime runtime;
        final TestRole role;
        boolean adopted;

        Fixture(ExecutorService executor) {
            this.executor = executor;
            manual = executor instanceof ManualExecutor value ? value : null;
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                    new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 4, 16, 4), 8), executor);
            role = new TestRole(runtime.acceptedEpoch());
        }
        void open() {
            var work = runtime.acceptOpeningWork();
            runtime.submitOpening(work, () -> {
                var origin = runtime.reserveOrigin(work);
                origin.adopt(role);
                adopted = true;
                runtime.bindAdoptedOrigin();
            }, () -> {});
            runQueued();
            if (manual == null) {
                try { work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
            assertEquals(CharacterRuntime.State.BOUND, runtime.snapshot().state());
        }
        CharacterRuntime.ModuleRef mount(String instance, Set<String> controls) {
            return mount(instance, controls, new RuntimeModule() {}, true);
        }
        CharacterRuntime.ModuleRef mount(String instance, Set<String> controls, RuntimeModule module, boolean enabled) {
            var definition = new ModuleDefinition<Void, RuntimeModule>("test-module", controls,
                    new ExecutionScope.Limits(16, 4, 16, 4), ignored -> {}, ignored -> module, List.of());
            return runtime.mount(definition, new ModuleSelection<>(instance, null, enabled)).reference();
        }
        CharacterRuntime.ModuleSnapshot module(String instance) {
            return runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals(instance)).findFirst().orElseThrow();
        }
        ScopeResources.CleanupAttempt cleanupOrigin() {
            var attempt = runtime.acquireOriginCleanup();
            assertEquals(CharacterCommandLane.Status.ENQUEUED,
                    runtime.submitOriginCleanup(attempt, () -> runtime.releaseOrigin(attempt), () -> {}).status());
            return attempt;
        }
        void runQueued() { if (manual != null) while (manual.size() > 0) manual.runNext(); }
    }

    private static final class TestRole implements RoleBinding {
        final Object identity = new Object();
        final GameContext.CharacterRef character;
        final AtomicInteger releases = new AtomicInteger();
        volatile boolean online = true;
        boolean releaseVerified = true;

        TestRole(WorldEpoch.Token epoch) {
            Character actor = mock(Character.class);
            when(actor.getId()).thenReturn(101);
            character = new GameContext.CharacterRef(epoch, actor, mock(Client.class), mock(World.class), mock(Channel.class));
        }
        @Override public String kind() { return "native-test"; }
        @Override public Object identity() { return identity; }
        @Override public GameContext.CharacterRef characterRef() { return character; }
        @Override public void requireCurrent() { if (!online) throw new IllegalStateException("native_role_offline"); }
        @Override public Map<String, Object> observe() { return Map.of("character_id", 101, "online", online); }
        @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
            permit.requireExecuting(identity);
            releases.incrementAndGet();
            if (releaseVerified) online = false;
            return new ReleaseProof(releaseVerified);
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        private final int capacity;
        private final ArrayBlockingQueue<Runnable> queue;
        private boolean shutdown, running;
        ManualExecutor(int capacity) { this.capacity = capacity; queue = new ArrayBlockingQueue<>(capacity); }
        @Override public int commandQueueCapacity() { return capacity; }
        @Override public void execute(Runnable command) {
            if (shutdown || !queue.offer(command)) throw new RejectedExecutionException("manual_full");
        }
        int size() { return queue.size(); }
        void runNext() {
            if (running) throw new IllegalStateException("reentrant_manual_executor");
            Runnable task = queue.remove(); running = true;
            try { task.run(); } finally { running = false; }
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { throw new UnsupportedOperationException("do_not_discard_carriers"); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty() && !running; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
