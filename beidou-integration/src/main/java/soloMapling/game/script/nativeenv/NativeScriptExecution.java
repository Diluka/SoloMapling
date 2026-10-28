package soloMapling.game.script.nativeenv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.script.ScriptValues;
import soloMapling.game.script.ScriptResult;

import javax.script.ScriptException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded guest invocation, using the caller's original scope work and no character-lane slot. */
public final class NativeScriptExecution {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionScope.WorkHandle work;
    private final NativeScriptSession session;
    private final NativeScriptRequest request;
    private final NativeScriptSession.GuestCall callback;
    private final long began = System.nanoTime();
    private final AtomicReference<ScriptResult.Status> stopping = new AtomicReference<>();
    private final CompletableFuture<NativeScriptResult> completion = new CompletableFuture<>();
    private final CompletableFuture<Object> callbackResult = new CompletableFuture<>();
    final ScriptValues.CapturedOutput stdout = new ScriptValues.CapturedOutput();
    final ScriptValues.CapturedOutput stderr = new ScriptValues.CapturedOutput();
    private volatile ScriptResult.Status status = ScriptResult.Status.CANCELLED;
    private volatile ScriptResult.ScriptError error;
    private volatile boolean guestExited;
    private Object result;
    private Object rawCallbackResult;

    private NativeScriptExecution(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                  NativeScriptRequest request, NativeScriptSession.GuestCall callback) {
        this.work = work; this.session = session; this.request = request; this.callback = callback;
    }
    public static NativeScriptExecution start(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                              NativeScriptRequest request) {
        return start(work, session, request, null);
    }
    private static NativeScriptExecution start(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                               NativeScriptRequest request, NativeScriptSession.GuestCall callback) {
        if (!work.claimCarrier()) throw new IllegalArgumentException("native_script_work_already_submitted");
        NativeScriptExecution execution = new NativeScriptExecution(work, session, request, callback);
        try { Thread.ofVirtual().name("native-script-carrier").start(execution::carry); }
        catch (RuntimeException | Error failed) { work.reject(); work.carrierSettled(); session.requestClose(); throw failed; }
        return execution;
    }
    static NativeScriptExecution callback(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                           NativeScriptSession.GuestCall callback) {
        NativeScriptRequest request = new NativeScriptRequest(session.kind(), "invoke", session.contextId(),
                Map.of(), null, List.of(), "", Map.of(), 5_000);
        return start(work, session, request, callback);
    }
    public ExecutionScope.WorkHandle work() { return work; }
    Map<String, Object> parameters() { return request.parameters(); }
    public CompletionStage<NativeScriptResult> completion() { return completion.minimalCompletionStage(); }
    public void cancel() {
        stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
        work.scope().requestCancel(work); session.requestClose();
    }
    boolean shouldStop() {
        if (work.cancelRequested() || !work.scope().isActive() || session.isClosing()) {
            stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
        } else if (System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(request.timeoutMs())) {
            stopping.compareAndSet(null, ScriptResult.Status.TIMED_OUT);
        }
        return stopping.get() != null;
    }
    private void carry() {
        Thread guest = null;
        boolean closeNeeded = "close".equals(request.operation());
        try {
            guest = Thread.ofVirtual().name("native-script-guest").start(() -> {
                try { work.scope().runTracked(work, this::evaluate); }
                finally { guestExited = true; }
            });
            while (guest.isAlive()) {
                if (shouldStop()) {
                    session.requestClose();
                    try { session.forceCancel(); }
                    catch (RuntimeException failed) { error = new ScriptResult.ScriptError("native_script_stop_unverified", null, null); }
                }
                try { guest.join(10); }
                catch (InterruptedException stopped) { cancel(); }
            }
            closeNeeded |= stopping.get() != null || status != ScriptResult.Status.SUCCEEDED || session.nativeDisposed();
            if (closeNeeded && !session.closeAndAwait(5_000)) {
                status = ScriptResult.Status.CLEANUP_ERROR;
                if (error == null) error = new ScriptResult.ScriptError("native_script_cleanup_unverified", null, null);
            }
        } catch (Throwable failed) {
            if (guest == null) { work.reject(); guestExited = true; }
            status = ScriptResult.Status.CLEANUP_ERROR;
            error = new ScriptResult.ScriptError("native_script_carrier_failed", null, null);
            session.requestClose();
        } finally {
            boolean verified = guestExited && (!closeNeeded || session.isClosed() && session.contextClosed());
            try { work.settleCarrier(() -> { if (!verified) throw new IllegalStateException("native_script_cleanup_unverified"); }); }
            catch (RuntimeException ignored) { /* Original work retains the failed settlement debt. */ }
            ScriptResult.Status finalStatus = !verified ? ScriptResult.Status.CLEANUP_ERROR
                    : stopping.get() != null ? stopping.get() : status;
            NativeScriptResult response = new NativeScriptResult(finalStatus,
                    finalStatus == ScriptResult.Status.SUCCEEDED ? result : null, stdout.text(), stderr.text(),
                    stdout.isTruncated() || stderr.isTruncated(), error, session.contextId(),
                    session.isClosed() ? "CLOSED" : session.isClosing() ? "CLOSING" : "OPEN",
                    guestExited, session.contextClosed(), work.isSettled(), session.describe());
            completion.complete(response);
            if (callback != null) {
                if (finalStatus == ScriptResult.Status.SUCCEEDED) callbackResult.complete(rawCallbackResult);
                else callbackResult.completeExceptionally(new ScriptException("native_script_callback_" + finalStatus.name().toLowerCase()));
            }
        }
    }
    private void evaluate() {
        boolean entered = false;
        try {
            if (shouldStop()) return;
            session.enter(this); entered = true;
            if (shouldStop()) return;
            session.clearInvocationFailure();
            try (AutoCloseable nativeContext = session.adapter().enter(session)) {
            if ("close".equals(request.operation())) { status = ScriptResult.Status.SUCCEEDED; result = null; return; }
            if ("start".equals(request.operation())) session.validateRole(); else session.validate();
            if (callback == null && !"start".equals(request.operation())) session.bindParameters(request.parameters());
            Object value;
            if (callback != null) { rawCallbackResult = callback.call(); value = null; }
            else {
                value = "start".equals(request.operation()) ? session.adapter().start(session, request)
                        : request.source().isBlank() ? session.adapter().invoke(session, request) : null;
                if (!request.source().isBlank()) {
                    session.validate();
                    var context = session.engine().getPolyglotContext();
                    session.bindParameters(request.parameters());
                    Value evaluated = context.eval("js", request.source());
                    value = ScriptValues.detachedJson(evaluated);
                }
            }
            if (session.recordedFailure() != null) {
                error = session.recordedFailure(); status = session.recordedFailureStatus(); return;
            }
            try { result = JSON.readValue(ScriptValues.json(value), Object.class); }
            catch (IllegalArgumentException invalid) { status = ScriptResult.Status.RESULT_ERROR; error = new ScriptResult.ScriptError("native_script_result_not_json", null, null); return; }
            shouldStop();
            status = ScriptResult.Status.SUCCEEDED;
            }
        } catch (PolyglotException failed) {
            session.recordFailure(failed); error = session.recordedFailure(); status = session.recordedFailureStatus();
        } catch (IllegalArgumentException invalidResult) {
            status = ScriptResult.Status.RESULT_ERROR; error = new ScriptResult.ScriptError(ScriptValues.errorText(invalidResult.getMessage()), null, null);
        } catch (InterruptedException stopped) {
            stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
        } catch (Throwable failed) {
            session.recordFailure(failed); error = session.recordedFailure(); status = session.recordedFailureStatus();
        } finally { if (entered) session.leave(); }
    }
    Object awaitCallback() throws ScriptException, NoSuchMethodException {
        try { return callbackResult.get(); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); cancel(); throw new ScriptException("native_script_callback_interrupted"); }
        catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof NoSuchMethodException absent) throw absent;
            throw new ScriptException("native_script_callback_failed");
        }
    }
}
