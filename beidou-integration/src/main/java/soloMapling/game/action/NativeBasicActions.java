package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import org.gms.net.PacketHandler;
import org.gms.net.server.Server;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static soloMapling.game.action.NativeActionResult.Dispatch.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;
import static soloMapling.game.action.NativeActionService.SubmissionRejection.*;
import static soloMapling.game.action.NativeActionService.SubmissionStatus.*;

/** Shared short-command accounting. Only finite typed action facades may construct an operation. */
public final class NativeBasicActions {
    private NativeBasicActions() { }

    public interface Handle {
        CompletionStage<NativeBasicResult> result();
        boolean dispatchStarted();
        NativeActionService.SubmissionReceipt receipt();
    }

    @FunctionalInterface
    interface Operation { NativeBasicResult execute(Context context); }

    private record Pending(Server server, CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                           ExecutionScope.WorkHandle work, GameContext.SceneRef scene, Operation operation) { }

    public static final class CommandHandle implements Handle {
        private final String action;
        private final int sender;
        private final String epoch;
        private final WeakReference<ExecutionScope.WorkHandle> work;
        private final NativeActionService.SubmissionReceipt receipt;
        private final AtomicReference<Pending> pending;
        private final CompletableFuture<NativeBasicResult> result = new CompletableFuture<>();
        private volatile boolean dispatched;

        private CommandHandle(String action, Pending pending) {
            this.action = action;
            sender = pending.scene.actor().character().getId();
            epoch = pending.scene.actor().epoch().value();
            work = new WeakReference<>(pending.work);
            receipt = new NativeActionService.SubmissionReceipt(pending.work.snapshot().id());
            this.pending = new AtomicReference<>(pending);
        }
        @Override public CompletionStage<NativeBasicResult> result() { return result.minimalCompletionStage(); }
        @Override public boolean dispatchStarted() { return dispatched; }
        @Override public NativeActionService.SubmissionReceipt receipt() { return receipt; }
    }

    static CommandHandle prepare(Server server, CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                                  ExecutionScope.WorkHandle work, GameContext.SceneRef scene, String action, Operation body) {
        Objects.requireNonNull(server); Objects.requireNonNull(lane); Objects.requireNonNull(permit);
        Objects.requireNonNull(work); Objects.requireNonNull(scene); Objects.requireNonNull(body);
        if (work.isCleanup()) throw new NativeActionService.Invalid("cleanup_work_not_an_action");
        if (scene.actor() != permit.characterRef() || scene.actor().epoch() != work.acceptedEpoch()) {
            throw new NativeActionService.Invalid("action_context_mismatch");
        }
        return new CommandHandle(action, new Pending(server, lane, permit, work, scene, body));
    }

    /** Exactly one lane submission; callbacks and repeated submission never wait for the carrier. */
    public static NativeActionService.SubmissionReceipt submit(Handle source) {
        if (!(source instanceof CommandHandle handle)) throw new IllegalArgumentException("not_a_short_native_command");
        if (!handle.receipt.begin()) return handle.receipt;
        Pending pending = Objects.requireNonNull(handle.pending.get());
        CharacterCommandLane.Submission submission;
        try {
            submission = pending.lane.submit(pending.work, pending.permit, () -> execute(handle), () -> settled(handle));
        } catch (RuntimeException rejected) {
            handle.receipt.decide(REJECTED, EXECUTOR);
            finish(handle, fallback(handle, pending.work.cancelRequested(), "native_action_submission_failed"));
            return handle.receipt;
        }
        if (submission.status() == CharacterCommandLane.Status.DUPLICATE) {
            handle.receipt.decide(REJECTED, WORK_ALREADY_BOUND);
            finish(handle, fallback(handle, false, "native_action_work_already_bound"));
        } else if (submission.status() == CharacterCommandLane.Status.ENQUEUED) {
            handle.receipt.decide(ENQUEUED, NONE);
        } else {
            handle.receipt.decide(REJECTED, NativeActionService.SubmissionRejection.valueOf(submission.rejection().name()));
            if (!handle.result.isDone()) settled(handle);
        }
        return handle.receipt;
    }

    private static void execute(CommandHandle handle) {
        Pending pending = handle.pending.get();
        if (pending == null) return;
        Context context = new Context(pending.server, pending.scene, pending.work, handle.action,
                () -> handle.dispatched = true);
        NativeBasicResult result;
        try {
            context.requireCurrent();
            result = Objects.requireNonNull(pending.operation.execute(context));
            if (!handle.action.equals(result.action()) || result.senderCharacterId() != handle.sender
                    || !handle.epoch.equals(result.epoch()) || (result.dispatch() == STARTED) != handle.dispatched) {
                throw new NativeActionService.Invalid("native_result_identity_mismatch");
            }
        } catch (Cancelled ignored) {
            result = handle.dispatched ? fallback(handle, true, "native_action_outcome_unknown")
                    : new NativeBasicResult(handle.action, CANCELLED, NOT_STARTED, handle.sender, handle.epoch,
                    true, false, "action_cancelled_before_dispatch", Map.of());
        } catch (NativeActionService.Invalid rejected) {
            result = fallback(handle, pending.work.cancelRequested(), rejected.reason());
        } catch (Throwable unavailable) {
            result = fallback(handle, pending.work.cancelRequested(), "native_action_validation_failed");
        }
        finish(handle, result);
    }

    private static void settled(CommandHandle handle) {
        if (handle.result.isDone()) { handle.pending.set(null); return; }
        ExecutionScope.WorkHandle work = handle.work.get();
        boolean cancelled = work != null && cancelled(work);
        if (!handle.dispatched && cancelled) {
            finish(handle, new NativeBasicResult(handle.action, CANCELLED, NOT_STARTED, handle.sender, handle.epoch,
                    true, false, "action_cancelled_before_dispatch", Map.of()));
        } else finish(handle, fallback(handle, cancelled, work != null && work.snapshot().rejected()
                ? "native_action_queue_rejected" : "native_action_not_dispatched"));
    }

    private static NativeBasicResult fallback(CommandHandle handle, boolean cancelled, String reason) {
        return new NativeBasicResult(handle.action, handle.dispatched ? INDETERMINATE : FAILED,
                handle.dispatched ? STARTED : NOT_STARTED, handle.sender, handle.epoch, cancelled,
                handle.dispatched, handle.dispatched ? "native_action_outcome_unknown" : reason, Map.of());
    }

    private static void finish(CommandHandle handle, NativeBasicResult result) {
        handle.pending.set(null);
        handle.result.complete(result);
    }

    static boolean cancelled(ExecutionScope.WorkHandle work) {
        WorldEpoch.State world = WorldEpoch.snapshot();
        return work.cancelRequested() || !world.active() || world.token() != work.acceptedEpoch();
    }

    /** Package-only native dispatch boundary, never exposed as a tool or a public arbitrary-handler API. */
    static final class Context {
        private final Server server;
        private final GameContext.SceneRef scene;
        private final ExecutionScope.WorkHandle work;
        private final String action;
        private final Runnable checkpoint;
        private boolean dispatched;
        private boolean handlerError;

        Context(Server server, GameContext.SceneRef scene, ExecutionScope.WorkHandle work, String action) {
            this(server, scene, work, action, () -> { });
        }
        Context(Server server, GameContext.SceneRef scene, ExecutionScope.WorkHandle work, String action, Runnable checkpoint) {
            this.server = server; this.scene = scene; this.work = work; this.action = action; this.checkpoint = checkpoint;
        }
        Server server() { return server; }
        GameContext.SceneRef scene() { return scene; }
        ExecutionScope.WorkHandle work() { return work; }
        GameContext.CharacterRef actor() { return scene.actor(); }
        boolean dispatchStarted() { return dispatched; }
        boolean handlerError() { return handlerError; }
        void requireCurrent() {
            if (cancelled(work)) throw new Cancelled();
            NativeContextChecks.requireSceneCurrent(server, scene);
        }
        void dispatch(PacketHandler handler, ByteBuf packet) {
            handlerError |= NativePacketDispatch.dispatch(actor().client(), handler, packet, this::requireCurrent,
                    () -> { dispatched = true; checkpoint.run(); });
        }
        NativeBasicResult result(NativeActionResult.Outcome outcome, String fixedReason, Map<String, Object> facts) {
            return new NativeBasicResult(action, outcome, dispatched ? STARTED : NOT_STARTED,
                    actor().character().getId(), actor().epoch().value(), work.cancelRequested(), handlerError, fixedReason, facts);
        }
    }

    private static final class Cancelled extends RuntimeException {
        private Cancelled() { super(null, null, false, false); }
    }
}
