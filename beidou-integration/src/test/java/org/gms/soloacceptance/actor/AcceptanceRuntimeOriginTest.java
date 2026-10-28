package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.NoteService;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real runtime root/scope/lane; native identity, observation and persistence are explicit test boundaries. */
class AcceptanceRuntimeOriginTest {
    private NativeActorBackend backend;
    private NativeActorHandle handle;
    private World world;
    private Channel channel;
    private Character character;
    private ManualExecutor executor;
    private CharacterRuntime runtime;
    private AcceptanceRuntimeOrigin origin;
    private boolean preserveUnverifiedLateOpen;

    @BeforeEach
    void oneRuntimeOwnsTheOpeningWorkAndSourceHandshake() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        backend = mock(NativeActorBackend.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        character = mock(Character.class);
        when(character.getId()).thenReturn(41);
        handle = nativeHandle(world, channel);
        executor = new ManualExecutor();
        runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 1, 16, 1), 4), executor);
        verifiedNativeClose();
    }

    @AfterEach
    void releaseOnlyThisKnownFixture() {
        try {
            // A failed late-result regression must retain its unknown root debt, not fabricate absence
            // or produce a second teardown failure by asking an unbound runtime to clean an unowned role.
            if (preserveUnverifiedLateOpen) return;
            if (runtime.snapshot().state() != CharacterRuntime.State.CLOSED) {
                runtime.beginClose(Optional.empty());
                executor.drain();
                if (!runtime.originReleased() && origin != null && origin.adoptedHandle() != null) {
                    verifiedNativeClose();
                    cleanup();
                }
                assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ZERO));
            }
        } finally {
            assertAll("fixture cleanup", executor::shutdown, ExecutionScope::tryBeginWorldStop);
        }
    }

    @Test
    void exactHandleAndFreshNativeFactsRemainTheSourcesResponsibility() {
        AcceptanceRoleBinding binding = new AcceptanceRoleBinding(backend, handle);
        assertSame(handle, binding.identity());
        assertSame(handle.characterRef(), binding.characterRef());
        assertEquals("mcp_acceptance", binding.kind());
        when(backend.snapshot(handle)).thenReturn(Map.of("level", 1), Map.of("level", 2));

        open(adopted -> {
            adopted.adopt(handle); // Same source result is idempotent, not another RoleBinding/resource.
            runtime.bindAdoptedOrigin();
        });

        assertEquals(Map.of("level", 1), runtime.observeOrigin());
        assertEquals(Map.of("level", 2), runtime.observeOrigin());
        assertEquals(1, runtime.lifecycleScope().resources().snapshot().registeredTotal());
        assertSame(handle, origin.adoptedHandle());
        verify(backend, times(2)).snapshot(handle);
        verify(backend, atLeastOnce()).requireCurrentRole(handle);
    }

    @Test
    void ordinaryPolicyStillRejectsAStaleElevatedOrEnvironmentRole() {
        AccountService accounts = mock(AccountService.class);
        CharacterService characters = mock(CharacterService.class);
        NoteService notes = mock(NoteService.class);
        NativeActorBackend guarded = spy(new NativeActorBackend(mock(ActorProfiles.class), accounts, characters,
                notes, () -> { throw new AssertionError("No server or persistence lookup belongs to this policy fixture"); }));
        AcceptanceRoleBinding binding = new AcceptanceRoleBinding(guarded, handle);
        doReturn(false).when(guarded).isCurrent(handle);
        assertEquals("actor_session_stale", assertThrows(ObservationException.class, binding::requireCurrent).reason());

        doReturn(true).when(guarded).isCurrent(handle);
        when(character.gmLevel()).thenReturn(2);
        assertEquals("actor_session_stale", assertThrows(ObservationException.class, binding::requireCurrent).reason());
        when(character.gmLevel()).thenReturn(0);
        try (MockedStatic<BotRegistry> bots = mockStatic(BotRegistry.class)) {
            bots.when(() -> BotRegistry.isBot(character)).thenReturn(true);
            assertEquals("actor_session_stale", assertThrows(ObservationException.class, binding::requireCurrent).reason());
        }
        verifyNoInteractions(accounts, characters, notes);
    }

    @Test
    void cleanupNeedsAnExecutingRootPermitAndReturnsOnlyVerifiedSourceEvidence() {
        AcceptanceRoleBinding binding = new AcceptanceRoleBinding(backend, handle);
        assertThrows(ObservationException.class, () -> binding.release(null));
        open(adopted -> runtime.bindAdoptedOrigin());
        runtime.beginClose(Optional.empty());
        ScopeResources.CleanupAttempt attempt = runtime.acquireOriginCleanup();
        assertThrows(ObservationException.class, () -> binding.release(attempt.permit()));
        verify(backend, never()).close(any(), any());

        runtime.submitOriginCleanup(attempt, () -> assertTrue(runtime.releaseOrigin(attempt).released()), () -> { });
        executor.drain();

        assertTrue(runtime.originReleased());
        assertEquals(CharacterRuntime.State.CLOSED, runtime.snapshot().state());
        Map<String, Object> projected = origin.cleanupEvidence();
        assertEquals(verifiedEvidence(), projected);
        projected.put("fixture_retained", false);
        assertEquals(true, origin.cleanupEvidence().get("fixture_retained"));
        assertThrows(ObservationException.class, () -> binding.release(attempt.permit()));
        verify(backend, times(1)).close(eq(handle), eq(attempt.permit()));
    }

    @Test
    void incompleteNativeProofStaysOwnedAndCanOnlyBeReleasedByAnExplicitVerifiedRetry() {
        open(adopted -> runtime.bindAdoptedOrigin());
        doAnswer(call -> {
            call.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(handle);
            return Map.of("native_cleanup_verified", true, "persisted_state_verified", false);
        }).when(backend).close(eq(handle), any());
        runtime.beginClose(Optional.empty());
        ScopeResources.CleanupAttempt failed = runtime.acquireOriginCleanup();
        runtime.submitOriginCleanup(failed, () -> runtime.releaseOrigin(failed), () -> { });
        assertEquals("native_actor_cleanup_unverified", assertThrows(ObservationException.class, executor::drain).reason());

        assertNull(origin.cleanupEvidence());
        assertFalse(runtime.originReleased());
        assertEquals(1, runtime.lifecycleScope().resources().snapshot().attached());
        assertNotEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ZERO));
        verifiedNativeClose();
        cleanup();
        assertTrue(runtime.originReleased());
        assertEquals(1, runtime.lifecycleScope().resources().snapshot().registeredTotal());
        verify(backend, times(2)).close(eq(handle), any());
    }

    @Test
    void inlineRollbackUsesTheOriginalRootLedgerAndDoesNotQueueBehindItsOwnOpen() {
        AtomicInteger verified = new AtomicInteger();
        doAnswer(call -> {
            call.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(handle);
            assertTrue(executor.queue.isEmpty());
            assertEquals(1, runtime.snapshot().lane().ordinary());
            verified.incrementAndGet();
            return verifiedEvidence();
        }).when(backend).close(eq(handle), any());

        open(adopted -> {
            adopted.rollback(handle);
            adopted.rollback(handle);
        });

        assertEquals(1, verified.get());
        assertTrue(runtime.originReleased());
        assertEquals(1, runtime.lifecycleScope().resources().snapshot().registeredTotal());
        assertEquals(1, runtime.lifecycleScope().resources().snapshot().releasedTotal());
        assertEquals(verifiedEvidence(), origin.cleanupEvidence());
    }

    @Test
    void partialOpenUsesOnlyTheAcceptedFactoryOriginAndDoesNotInventAHandleOrNativeClose() {
        ExecutionScope.WorkHandle work = runtime.acceptOpeningWork();
        runtime.submitOpening(work, () -> {
            origin = new AcceptanceRuntimeOrigin(runtime, backend, work);
            origin.bindOrigin(world, channel);
            origin.requireOrigin(world, channel);
            assertThrows(ObservationException.class, () -> origin.requireOrigin(mock(World.class), channel));
            origin.creationFailed("native_open_cleanup_unverified");
            assertEquals(1, runtime.lifecycleScope().resources().snapshot().unverified());
            origin.confirmAbsent(); // This controlled fixture did not create any native role.
        }, () -> { });
        executor.drain();

        assertNull(origin.adoptedHandle());
        assertNull(origin.cleanupEvidence());
        assertTrue(runtime.originReleased());
        assertEquals("resource_pending_cleanup_not_owned",
                assertThrows(IllegalStateException.class, () -> origin.requireOrigin(world, channel)).getMessage());
        verify(backend, never()).close(any(), any());
    }

    @Test
    void anotherResultCannotReplaceAnAdoptedHandleOrItsOriginPair() {
        open(adopted -> {
            NativeActorHandle other = nativeHandle(world, channel);
            assertThrows(ObservationException.class, () -> adopted.adopt(other));
            assertEquals("resource_pending_cleanup_not_owned",
                    assertThrows(IllegalStateException.class, () -> adopted.bindOrigin(mock(World.class), channel)).getMessage());
            assertThrows(ObservationException.class, () -> adopted.rollback(other));
            assertThrows(ObservationException.class, adopted::confirmAbsent);
            assertSame(handle, adopted.adoptedHandle());
        });
        assertEquals(1, runtime.lifecycleScope().resources().snapshot().attached());
        verify(backend, never()).close(any(), any());
    }

    @Test
    void lateOpenHandleAfterWorldStopMustBecomeRootOwnedBeforeNormalBindingIsRejected() {
        WorldEpoch.Token accepted = runtime.acceptedEpoch();
        ExecutionScope.WorkHandle opening = runtime.acceptOpeningWork();
        runtime.submitOpening(opening, () -> {
            origin = new AcceptanceRuntimeOrigin(runtime, backend, opening);
            origin.bindOrigin(world, channel);
            assertEquals(1, runtime.lifecycleScope().resources().snapshot().pending());

            ExecutionScope.WorldStopTicket stop = ExecutionScope.tryBeginWorldStop().orElseThrow();
            runtime.beginClose(Optional.of(stop));
            assertSame(accepted, stop.sourceEpoch());
            assertEquals(ExecutionScope.BodyState.RUNNING, opening.snapshot().bodyState());

            // This is the real Handle constructor used by native OPEN: it captures the now-stopped
            // token, while the accepted factory body and its source resource still belong to E0.
            handle = nativeHandle(world, channel);
            verifiedNativeClose();
            WorldEpoch.Token lateRefEpoch = handle.characterRef().epoch();
            assertSame(stop.stoppedEpoch(), lateRefEpoch);
            assertNotSame(accepted, lateRefEpoch);
            RuntimeException adoptFailure = null;
            try { origin.adopt(handle); }
            catch (RuntimeException rejected) { adoptFailure = rejected; }

            assertEquals(1, runtime.lifecycleScope().resources().snapshot().attached(),
                    "A known late native handle must be owned by the root even when normal adoption/binding is rejected; "
                            + "adopt failure=" + (adoptFailure == null ? "none" : adoptFailure.getMessage()));
            assertEquals(0, runtime.lifecycleScope().resources().snapshot().pending());
            assertSame(handle, origin.adoptedHandle());
            assertThrows(RuntimeException.class, runtime::bindAdoptedOrigin);
            assertSame(lateRefEpoch, handle.characterRef().epoch(), "The source must not refresh or coerce its ref epoch");

            origin.rollback(handle);
            assertTrue(runtime.originReleased());
            assertEquals(1, runtime.lifecycleScope().resources().snapshot().registeredTotal());
            assertEquals(1, runtime.lifecycleScope().resources().snapshot().releasedTotal());
            assertEquals(verifiedEvidence(), origin.cleanupEvidence());
            verify(backend, times(1)).close(eq(handle), any());
        }, () -> { });

        try {
            executor.drain();
            assertTrue(opening.isSettled());
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ZERO));
            assertEquals(CharacterRuntime.State.CLOSED, runtime.snapshot().state());
        } finally {
            preserveUnverifiedLateOpen = !runtime.originReleased();
        }
    }

    private void open(Consumer<AcceptanceRuntimeOrigin> afterAdoption) {
        ExecutionScope.WorkHandle work = runtime.acceptOpeningWork();
        assertEquals(CharacterCommandLane.Status.ENQUEUED, runtime.submitOpening(work, () -> {
            origin = new AcceptanceRuntimeOrigin(runtime, backend, work);
            origin.bindOrigin(world, channel);
            origin.adopt(handle);
            afterAdoption.accept(origin);
        }, () -> { }).status());
        executor.drain();
        assertTrue(work.isSettled());
    }

    private void cleanup() {
        ScopeResources.CleanupAttempt attempt = runtime.acquireOriginCleanup();
        assertEquals(CharacterCommandLane.Status.ENQUEUED,
                runtime.submitOriginCleanup(attempt, () -> runtime.releaseOrigin(attempt), () -> { }).status());
        executor.drain();
    }

    private NativeActorHandle nativeHandle(World exactWorld, Channel exactChannel) {
        return new NativeActorHandle("primary", 17, mock(AcceptanceClient.class), character,
                exactWorld, exactChannel, mock(ActorAccountOwnership.Lease.class), false, false);
    }

    private void verifiedNativeClose() {
        doAnswer(call -> {
            call.<ScopeResources.DrainPermit>getArgument(1).requireExecuting(handle);
            return verifiedEvidence();
        }).when(backend).close(eq(handle), any());
    }

    private static Map<String, Object> verifiedEvidence() {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("native_cleanup_verified", true);
        proof.put("persisted_state_verified", true);
        proof.put("native_dialogue_cleanup_verified", true);
        proof.put("fixture_retained", true);
        return proof;
    }

    private static final class ManualExecutor extends AbstractExecutorService
            implements CharacterCommandLane.SerialBoundedExecutor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;
        @Override public int commandQueueCapacity() { return 8; }
        @Override public void execute(Runnable command) {
            if (shutdown || queue.size() >= 8) throw new RejectedExecutionException();
            queue.addLast(command);
        }
        void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = List.copyOf(queue);
            queue.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
