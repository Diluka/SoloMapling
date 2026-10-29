package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.action.ChatEvidence;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeEvents;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual host, scopes and one bounded lane; the role origin is an explicit no-I/O contract substitute. */
class McpControlModuleTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private CharacterRuntime runtime;
    private ThreadPoolExecutor executor;
    private TestRole role;
    private McpControlModule module;
    private Optional<ExecutionScope.WorldStopTicket> stop = Optional.empty();

    @BeforeEach
    void startWorld() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
    }

    @AfterEach
    void closeOnlyTheOwnedFixture() throws Exception {
        try {
            if (runtime != null && runtime.snapshot().state() != CharacterRuntime.State.CLOSED) closeRoot();
            if (executor != null) assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            if (executor != null) executor.shutdown();
            ExecutionScope.tryBeginWorldStop();
        }
    }

    @Test
    void theModuleDeclaresOnlyItsControlsAndCannotOperateBeforeHostActivation() {
        McpControlModule inactive = McpControlModule.DEFINITION.factory().apply(Map.of());
        assertEquals("mcp-control", McpControlModule.DEFINITION.typeId());
        assertEquals(Set.of("chat", "interaction", "inventory", "movement", "combat"), McpControlModule.DEFINITION.controls());
        assertEquals(new ExecutionScope.Limits(16, 2, 16, 2), McpControlModule.DEFINITION.limits());
        assertEquals("module_not_active", assertThrows(CharacterRuntime.Invalid.class, inactive::context).reason());
        assertEquals("module_not_active", assertThrows(CharacterRuntime.Invalid.class, inactive::acceptWork).reason());
        assertEquals("module_not_active", assertThrows(CharacterRuntime.Invalid.class, inactive::reference).reason());
        assertThrows(CharacterRuntime.Invalid.class,
                () -> inactive.submitInteraction(null, () -> {}, () -> {}, () -> {}));
        assertThrows(CharacterRuntime.Invalid.class,
                () -> inactive.prepareChat(null, () -> {}, (lane, permit, work, character) -> null));
        assertThrows(CharacterRuntime.Invalid.class, () -> inactive.recordResult(null, null));
    }

    @Test
    void activationUsesTheHostContextWhileTheNativeRoleRemainsARootResource() throws Exception {
        openAndMount();
        assertSame(runtime.context(module.reference()), module.context());
        assertNotSame(runtime.lifecycleScope(), module.context().scope());
        assertEquals(1, runtime.lifecycleScope().snapshot().outstandingResources());
        assertEquals(0, module.context().scope().snapshot().outstandingResources());
        var work = module.acceptWork();
        assertSame(module.context().scope(), work.scope());
        assertEquals(runtime.acceptedEpoch(), work.acceptedEpoch());
        module.context().reject(work);
        await(work);
        assertEquals(Map.of("character_id", 73, "online", true), runtime.observeOrigin());
        assertEquals(0, role.releases.get());
    }

    @Test
    void chatPreparationDoesNotQueueAndBothControlsUseOnePhysicalHostLane() throws Exception {
        openAndMount();
        AtomicInteger preflights = new AtomicInteger(), bodies = new AtomicInteger(), settlements = new AtomicInteger();
        var chatWork = module.acceptWork();
        Prepared prepared = module.prepareChat(chatWork, preflights::incrementAndGet,
                (lane, permit, work, character) -> new Prepared(lane, permit, work, character));
        assertSame(chatWork, prepared.work());
        assertSame(role.character, prepared.character());
        assertFalse(chatWork.snapshot().carrierClaimed()); // Preparing never attaches a physical carrier.
        assertEquals(0, runtime.snapshot().lane().pending());
        assertEquals(0, preflights.get());
        assertFalse(chatWork.isSettled());

        var interaction = module.acceptWork();
        Prepared interactionContext = module.context().prepareNative("interaction", interaction, () -> {},
                (lane, permit, work, character) -> new Prepared(lane, permit, work, character));
        assertSame(prepared.lane(), interactionContext.lane());
        assertEquals(CharacterCommandLane.Status.ENQUEUED, module.submitInteraction(interaction,
                preflights::incrementAndGet, bodies::incrementAndGet, settlements::incrementAndGet).status());
        await(interaction);
        // A prepared native service submits this exact work once, without another module wrapper.
        assertEquals(CharacterCommandLane.Status.ENQUEUED, prepared.lane().submit(prepared.work(), prepared.permit(),
                bodies::incrementAndGet, settlements::incrementAndGet).status());
        await(chatWork);
        // ThreadPoolExecutor task counts are approximate while a worker finalizes its task.
        // Native command identity and body/settlement counts prove single execution below.
        assertTrue(chatWork.isSettled());
        assertTrue(interaction.isSettled());
        assertEquals(2, preflights.get());
        assertEquals(2, bodies.get());
        assertEquals(2, settlements.get());
        assertEquals(0, runtime.snapshot().lane().pending());
        assertEquals(0, role.releases.get());
    }

    @Test
    void pauseAndUnloadLeaveTheNativeDialogueAndRoleUntouchedButHostRejectsOldControl() throws Exception {
        openAndMount();
        var context = module.context();
        var reference = module.reference();
        var work = module.acceptWork();
        module.submitInteraction(work, () -> {}, () -> {}, () -> {});
        await(work);
        Object dialogue = role.dialogue;
        assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.stop(reference, CharacterRuntime.StopMode.PAUSE).await(WAIT));
        assertSame(context, module.context());
        assertSame(reference, module.reference());
        assertThrows(CharacterRuntime.Invalid.class, module::acceptWork);
        assertThrows(CharacterRuntime.Invalid.class,
                () -> module.submitInteraction(work, () -> {}, () -> fail("old control entered"), () -> {}));
        assertThrows(CharacterRuntime.Invalid.class,
                () -> module.prepareChat(work, () -> {}, (lane, permit, accepted, character) -> fail("old preparation entered")));
        assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.stop(reference, CharacterRuntime.StopMode.UNLOAD).await(WAIT));
        assertSame(context, module.context());
        assertThrows(CharacterRuntime.Invalid.class, module::acceptWork);
        assertSame(dialogue, role.dialogue);
        assertTrue(role.online);
        assertEquals(0, role.releases.get());
        verify(role.character.client(), never()).disconnect(anyBoolean(), anyBoolean());
        assertEquals(Map.of("character_id", 73, "online", true), runtime.observeOrigin());
    }

    @Test
    void anUnloadedContextCanRecordItsAcceptedWorkFactWithoutRevivingControlAndRootCloseStillWorks() throws Exception {
        openAndMount();
        var reference = module.reference();
        var work = module.acceptWork();
        module.context().reject(work);
        await(work);
        NativeActionResult fact = new NativeActionResult(NativeActionResult.Outcome.FAILED,
                NativeActionResult.Dispatch.NOT_STARTED, 73, false, false, "native_action_queue_rejected",
                ChatEvidence.none(runtime.acceptedEpoch().value()));
        assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.stop(reference, CharacterRuntime.StopMode.UNLOAD).await(WAIT));

        module.recordResult(work, fact);

        RuntimeEvents.Event recorded = runtime.events().events().stream()
                .filter(event -> event.kind() == RuntimeEvents.Kind.ACTION_RESULT).findFirst().orElseThrow();
        assertEquals("primary", recorded.moduleId());
        assertEquals(reference.generation(), recorded.moduleGeneration());
        assertEquals(work.snapshot().id(), recorded.workId());
        assertEquals("FAILED", recorded.outcome());
        assertEquals("native_action_queue_rejected", recorded.reason());
        assertEquals(CharacterRuntime.State.BOUND, runtime.snapshot().state());
        assertThrows(CharacterRuntime.Invalid.class, module::acceptWork);
        assertEquals(0, role.releases.get());
        closeRoot();
        assertEquals(1, role.releases.get());
        assertNull(role.dialogue);
        assertEquals(CharacterRuntime.State.CLOSED, runtime.snapshot().state());
    }

    @Test
    void worldStoppingInvalidatesOrdinaryModuleWorkThroughTheHost() throws Exception {
        openAndMount();
        var captured = module.context();
        stop = ExecutionScope.tryBeginWorldStop();
        assertTrue(stop.isPresent());
        assertThrows(ExecutionScope.Invalid.class, module::acceptWork);
        assertSame(captured, module.context());
        assertEquals(0, role.releases.get());
        assertTrue(role.online);
    }

    private void openAndMount() throws Exception {
        executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                Thread.ofVirtual().factory());
        runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(
                8, 1, new ExecutionScope.Limits(16, 1, 16, 1)), executor);
        role = new TestRole(runtime.acceptedEpoch());
        var open = runtime.acceptOpeningWork();
        runtime.submitOpening(open, () -> {
            runtime.reserveOrigin(open).adopt(role);
            runtime.bindAdoptedOrigin();
        }, () -> {});
        await(open);
        module = runtime.mount(McpControlModule.DEFINITION,
                new ModuleSelection<>("primary", Map.of(), true)).instance();
    }

    private void closeRoot() throws Exception {
        runtime.beginClose(stop);
        if (!runtime.originReleased()) {
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            await(cleanup.work());
        }
        assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(WAIT));
    }

    private static void await(ExecutionScope.WorkHandle work) throws Exception {
        work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private record Prepared(CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                            ExecutionScope.WorkHandle work, GameContext.CharacterRef character) {}

    private static final class TestRole implements RoleBinding {
        final Object identity = new Object();
        final GameContext.CharacterRef character;
        final AtomicInteger releases = new AtomicInteger();
        Object dialogue = new Object();
        boolean online = true;

        TestRole(WorldEpoch.Token epoch) {
            Character actor = mock(Character.class);
            when(actor.getId()).thenReturn(73);
            character = new GameContext.CharacterRef(epoch, actor, mock(Client.class), mock(World.class), mock(Channel.class));
        }
        @Override public String kind() { return "module-test-role"; }
        @Override public Object identity() { return identity; }
        @Override public GameContext.CharacterRef characterRef() { return character; }
        @Override public void requireCurrent() {
            if (!online) throw new CharacterRuntime.Invalid("fixture_role_not_current");
        }
        @Override public Map<String, Object> observe() { return Map.of("character_id", 73, "online", online); }
        @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
            permit.requireExecuting(identity);
            releases.incrementAndGet();
            online = false;
            dialogue = null;
            return new ReleaseProof(true);
        }
    }
}
