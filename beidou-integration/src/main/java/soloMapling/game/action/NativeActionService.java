package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.client.Client;
import org.gms.constants.string.CharsetConstants;
import org.gms.net.packet.ByteBufInPacket;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.GeneralChatHandler;
import org.gms.util.ThreadLocalUtil;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.ref.WeakReference;
import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled native chat on the caller's existing lane; no executor, owner policy or history. */
public final class NativeActionService {
    public enum SubmissionStatus { PREPARED, SUBMITTING, ENQUEUED, REJECTED }
    public enum SubmissionRejection { NONE, CAPACITY, PERMIT, EXECUTOR, WORK_ALREADY_BOUND }

    /** Stable nonblocking identity, including re-entry from a synchronous rejection/result callback. */
    public static final class SubmissionReceipt {
        private record Decision(SubmissionStatus status, SubmissionRejection rejection) { }
        private static final Decision PREPARED = new Decision(SubmissionStatus.PREPARED, SubmissionRejection.NONE);
        private static final Decision SUBMITTING = new Decision(SubmissionStatus.SUBMITTING, SubmissionRejection.NONE);
        private final long workId;
        private final AtomicReference<Decision> decision = new AtomicReference<>(PREPARED);
        private SubmissionReceipt(long workId) { this.workId = workId; }
        public long workId() { return workId; }
        public SubmissionStatus status() { return decision.get().status(); }
        public SubmissionRejection rejection() { return decision.get().rejection(); }
        private boolean begin() { return decision.compareAndSet(PREPARED, SUBMITTING); }
        private void decide(SubmissionStatus status, SubmissionRejection rejection) { decision.set(new Decision(status, rejection)); }
    }

    public static final class ActionHandle {
        private final int sender;
        private final String epoch;
        private final WeakReference<ExecutionScope.WorkHandle> work;
        private final SubmissionReceipt receipt;
        private final AtomicReference<Pending> pending;
        private final CompletableFuture<NativeActionResult> result = new CompletableFuture<>();
        private volatile boolean dispatchStarted;

        private ActionHandle(Pending pending) {
            this.sender = pending.scene.actor().character().getId();
            this.epoch = pending.scene.actor().epoch().value();
            this.work = new WeakReference<>(pending.work);
            this.receipt = new SubmissionReceipt(pending.work.snapshot().id());
            this.pending = new AtomicReference<>(pending);
        }

        public CompletionStage<NativeActionResult> result() { return result.minimalCompletionStage(); }
        public boolean dispatchStarted() { return dispatchStarted; }
        public SubmissionReceipt receipt() { return receipt; }
    }

    public static final class Invalid extends RuntimeException {
        private final String reason;
        public Invalid(String reason) {
            super(validReason(reason), null, false, false);
            this.reason = reason;
        }
        public String reason() { return reason; }
        private static String validReason(String reason) {
            if (reason == null || !reason.matches("[a-z_]{1,96}")) throw new IllegalArgumentException("invalid_action_reason");
            return reason;
        }
    }

    private record Pending(Server server, CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                           ExecutionScope.WorkHandle work, GameContext.SceneRef scene, NativeChatAction action,
                           ChatEvidenceSource source) { }

    public ActionHandle prepareChat(Server server, CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                                    ExecutionScope.WorkHandle work, GameContext.SceneRef scene, NativeChatAction action,
                                    ChatEvidenceSource source) {
        Objects.requireNonNull(server); Objects.requireNonNull(lane); Objects.requireNonNull(permit);
        Objects.requireNonNull(work); Objects.requireNonNull(scene); Objects.requireNonNull(action); Objects.requireNonNull(source);
        if (work.isCleanup()) throw new Invalid("cleanup_work_not_an_action");
        if (scene.actor() != permit.characterRef() || scene.actor().epoch() != work.acceptedEpoch()) {
            throw new Invalid("action_context_mismatch");
        }
        return new ActionHandle(new Pending(server, lane, permit, work, scene, action, source));
    }

    /** Exactly one physical submission. This method never waits for native execution or an earlier submit. */
    public SubmissionReceipt submitChat(ActionHandle handle) {
        Objects.requireNonNull(handle);
        if (!handle.receipt.begin()) return handle.receipt;
        Pending pending = handle.pending.get();
        if (pending == null) throw new IllegalStateException("action_inputs_released_before_submit");
        CharacterCommandLane.Submission submission;
        try {
            submission = pending.lane.submit(pending.work, pending.permit, () -> execute(handle), () -> settled(handle));
        } catch (RuntimeException unavailable) {
            handle.receipt.decide(SubmissionStatus.REJECTED, SubmissionRejection.EXECUTOR);
            finish(handle, notStarted(handle, pending.work.cancelRequested(), "native_action_submission_failed"));
            return handle.receipt;
        }
        if (submission.status() == CharacterCommandLane.Status.DUPLICATE) {
            handle.receipt.decide(SubmissionStatus.REJECTED, SubmissionRejection.WORK_ALREADY_BOUND);
            // Another handle owns the carrier: do not reject, settle or cancel its work.
            finish(handle, notStarted(handle, false, "native_action_work_already_bound"));
        } else if (submission.status() == CharacterCommandLane.Status.ENQUEUED) {
            handle.receipt.decide(SubmissionStatus.ENQUEUED, SubmissionRejection.NONE);
        } else {
            handle.receipt.decide(SubmissionStatus.REJECTED,
                    SubmissionRejection.valueOf(submission.rejection().name()));
            if (!handle.result.isDone()) settled(handle);
        }
        return handle.receipt;
    }

    /** Validation is neutral: MCP and Bot adapters independently check their exact identity policy. */
    public static void validateChat(GameContext.CharacterRef actor, String text) { encode(actor, text); }

    private static byte[] encode(GameContext.CharacterRef actor, String text) {
        Objects.requireNonNull(actor);
        if (text == null || text.isBlank() || text.length() > Byte.MAX_VALUE
                || text.charAt(0) == '@' || text.charAt(0) == '/'
                || text.charAt(0) == '!' && actor.character().isGM()) throw new Invalid("invalid_chat_text");
        try {
            ByteBuffer bytes = CharsetConstants.getCharset(actor.client().getLanguage()).newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] encoded = new byte[bytes.remaining()];
            bytes.get(encoded);
            return encoded;
        } catch (CharacterCodingException ignored) {
            throw new Invalid("chat_text_not_encodable");
        }
    }

    private static void execute(ActionHandle handle) {
        Pending pending = handle.pending.get();
        if (pending == null) return;
        ChatEvidenceSource.Probe probe = null;
        NativeActionResult result;
        boolean handlerError = false;
        try {
            NativeContextChecks.requireSceneCurrent(pending.server, pending.scene);
            byte[] encoded = encode(pending.scene.actor(), pending.action.text());
            try { probe = pending.source.begin(pending.scene.actor(), pending.action); }
            catch (RuntimeException unavailable) { throw new Invalid("chat_evidence_unavailable"); }
            if (probe == null) throw new Invalid("chat_evidence_unavailable");
            Client client = pending.scene.actor().client();
            ByteBuf packet = Unpooled.buffer();
            Client previous = ThreadLocalUtil.getCurrentClient();
            try {
                packet.writeShortLE(encoded.length).writeBytes(encoded).writeByte(0);
                ThreadLocalUtil.setCurrentClient(client);
                GeneralChatHandler handler = new GeneralChatHandler();
                if (!handler.validateState(client)) throw new Invalid("native_packet_state_rejected");
                NativeContextChecks.requireSceneCurrent(pending.server, pending.scene);
                if (cancelled(pending.work)) throw new CancelledBeforeDispatch();
                // No future completion or consumer callback is allowed between this fact and native entry.
                handle.dispatchStarted = true;
                handler.handlePacket(new ByteBufInPacket(packet), client);
            } catch (Throwable failure) {
                if (!handle.dispatchStarted) {
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    throw new Invalid("native_action_validation_failed");
                }
                handlerError = true;
            } finally {
                if (previous == null) ThreadLocalUtil.removeCurrentClient();
                else ThreadLocalUtil.setCurrentClient(previous);
                packet.release();
            }
            ChatEvidence evidence;
            try { evidence = Objects.requireNonNull(probe.observe()); }
            catch (Throwable unavailable) { evidence = ChatEvidence.none(handle.epoch); }
            result = reduce(handle, pending.work.cancelRequested(), handlerError, evidence);
        } catch (CancelledBeforeDispatch cancelled) {
            result = cancelled(handle);
        } catch (Invalid rejected) {
            result = notStarted(handle, pending.work.cancelRequested(), rejected.reason());
        } catch (GameContext.Invalid changed) {
            result = cancelled(pending.work) ? cancelled(handle)
                    : notStarted(handle, pending.work.cancelRequested(), contextReason(changed.reason()));
        } catch (Throwable unavailable) {
            result = handle.dispatchStarted
                    ? new NativeActionResult(NativeActionResult.Outcome.INDETERMINATE, NativeActionResult.Dispatch.STARTED,
                    handle.sender, pending.work.cancelRequested(), true, "native_chat_outcome_unknown", ChatEvidence.none(handle.epoch))
                    : notStarted(handle, pending.work.cancelRequested(), "native_action_validation_failed");
        } finally {
            if (probe != null) {
                try { probe.close(); }
                catch (Throwable ignored) { /* Probe cleanup cannot erase an observed native fact. */ }
            }
        }
        finish(handle, result);
    }

    private static NativeActionResult reduce(ActionHandle handle, boolean cancelRequested, boolean handlerError,
                                               ChatEvidence evidence) {
        if (!handle.epoch.equals(evidence.epoch())) evidence = ChatEvidence.none(handle.epoch);
        boolean negative = evidence.source() == ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET
                && evidence.completeness() == ChatEvidence.Completeness.COMPLETE && !evidence.uncertainPacket();
        NativeActionResult.Outcome outcome = evidence.matched() ? NativeActionResult.Outcome.SUCCEEDED
                : !handlerError && negative ? NativeActionResult.Outcome.FAILED : NativeActionResult.Outcome.INDETERMINATE;
        return new NativeActionResult(outcome, NativeActionResult.Dispatch.STARTED, handle.sender, cancelRequested,
                handlerError, outcome == NativeActionResult.Outcome.SUCCEEDED ? null
                : outcome == NativeActionResult.Outcome.FAILED ? "native_chat_not_broadcast" : "native_chat_outcome_unknown", evidence);
    }

    private static void settled(ActionHandle handle) {
        if (handle.result.isDone()) { handle.pending.set(null); return; }
        Pending pending = handle.pending.get();
        ExecutionScope.WorkHandle work = pending == null ? handle.work.get() : pending.work;
        boolean cancelled = work != null && cancelled(work);
        finish(handle, handle.dispatchStarted
                ? new NativeActionResult(NativeActionResult.Outcome.INDETERMINATE, NativeActionResult.Dispatch.STARTED,
                handle.sender, cancelled, true, "native_chat_outcome_unknown", ChatEvidence.none(handle.epoch))
                : cancelled ? cancelled(handle) : notStarted(handle, false,
                work != null && work.snapshot().rejected() ? "native_action_queue_rejected" : "native_action_not_dispatched"));
    }

    private static void finish(ActionHandle handle, NativeActionResult result) {
        handle.pending.set(null); // Drop text, native objects and adapter closures before completing consumer callbacks.
        handle.result.complete(result);
    }

    private static NativeActionResult cancelled(ActionHandle handle) {
        return new NativeActionResult(NativeActionResult.Outcome.CANCELLED, NativeActionResult.Dispatch.NOT_STARTED,
                handle.sender, true, false, "chat_cancelled_before_dispatch", ChatEvidence.none(handle.epoch));
    }

    private static NativeActionResult notStarted(ActionHandle handle, boolean cancelled, String reason) {
        return new NativeActionResult(NativeActionResult.Outcome.FAILED, NativeActionResult.Dispatch.NOT_STARTED,
                handle.sender, cancelled, false, reason, ChatEvidence.none(handle.epoch));
    }

    private static boolean cancelled(ExecutionScope.WorkHandle work) {
        WorldEpoch.State world = WorldEpoch.snapshot();
        return work.cancelRequested() || !world.active() || world.token() != work.acceptedEpoch();
    }

    private static String contextReason(GameContext.Failure failure) {
        return switch (failure) {
            case WORLD_OFFLINE -> "native_world_offline";
            case WORLD_CHANGED, RUNTIME_CHANGED -> "native_world_changed";
            case SCENE_CHANGED, EVENT_CHANGED -> "native_scene_changed";
            case READ_UNAVAILABLE -> "native_context_unavailable";
            case CHARACTER_CHANGED, NOT_IN_WORLD -> "native_character_changed";
        };
    }

    private static final class CancelledBeforeDispatch extends RuntimeException { }
}
