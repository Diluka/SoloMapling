package soloMapling.game.script.nativeenv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.script.ScriptValues;
import soloMapling.game.script.ScriptResult;

import javax.script.ScriptException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One bounded guest invocation, using the caller's original scope work and no character-lane slot. */
public final class NativeScriptExecution {
    /** Detached text produced by this invocation; consumers only record it in memory. */
    public record Output(String channel, String text, boolean truncated) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionScope.WorkHandle work;
    private final NativeScriptSession session;
    private final NativeScriptRequest request;
    private final NativeScriptSession.GuestCall callback;
    private final Consumer<Output> output;
    private final long began = System.nanoTime();
    private final AtomicReference<ScriptResult.Status> stopping = new AtomicReference<>();
    private final CompletableFuture<NativeScriptResult> completion = new CompletableFuture<>();
    private final CompletableFuture<Object> callbackResult = new CompletableFuture<>();
    final ScriptValues.CapturedOutput stdout = new ScriptValues.CapturedOutput();
    final ScriptValues.CapturedOutput stderr = new ScriptValues.CapturedOutput();
    private final Writer stdoutWriter = new OutputWriter("stdout", stdout);
    private final Writer stderrWriter = new OutputWriter("stderr", stderr);
    private volatile ScriptResult.Status status = ScriptResult.Status.CANCELLED;
    private volatile ScriptResult.ScriptError error;
    private volatile boolean guestExited;
    private Object result;
    private Object rawCallbackResult;

    private NativeScriptExecution(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                  NativeScriptRequest request, NativeScriptSession.GuestCall callback,
                                  Consumer<Output> output) {
        this.work = work; this.session = session; this.request = request; this.callback = callback; this.output = output;
    }
    public static NativeScriptExecution start(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                              NativeScriptRequest request) {
        return start(work, session, request, ignored -> { });
    }
    public static NativeScriptExecution start(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                              NativeScriptRequest request, Consumer<Output> output) {
        return start(work, session, request, null, output);
    }
    private static NativeScriptExecution start(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                               NativeScriptRequest request, NativeScriptSession.GuestCall callback,
                                               Consumer<Output> output) {
        if (!work.claimCarrier()) throw new IllegalArgumentException("native_script_work_already_submitted");
        NativeScriptExecution execution = new NativeScriptExecution(work, session, request, callback, output);
        try { Thread.ofVirtual().name("native-script-carrier").start(execution::carry); }
        catch (RuntimeException | Error failed) { work.reject(); work.carrierSettled(); session.requestClose(); throw failed; }
        return execution;
    }
    static NativeScriptExecution callback(ExecutionScope.WorkHandle work, NativeScriptSession session,
                                           NativeScriptSession.GuestCall callback) {
        NativeScriptRequest request = new NativeScriptRequest(session.kind(), "invoke", session.contextId(),
                Map.of(), null, List.of(), "", Map.of(), NativeScriptRequest.DEFAULT_TIMEOUT_MS);
        return start(work, session, request, callback, ignored -> { });
    }
    public ExecutionScope.WorkHandle work() { return work; }
    Writer output(boolean error) { return error ? stderrWriter : stdoutWriter; }
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
        if (stopping.get() == null) return false;
        if (!work.cancelRequested()) work.scope().requestCancel(work);
        return true;
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
            if (guestExited) finishOutput();
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
    /** Finish each invocation's encoder only after its guest can no longer write. */
    private void finishOutput() {
        for (Writer output : List.of(stdoutWriter, stderrWriter)) {
            try { output.close(); }
            catch (IOException failed) {
                status = ScriptResult.Status.RESULT_ERROR;
                if (error == null) error = new ScriptResult.ScriptError("native_script_output_unavailable", null, null);
            }
        }
    }
    /** One encoder spans writes so a surrogate pair split by Graal stays a single character. */
    private final class OutputWriter extends Writer {
        private final String channel;
        private final ScriptValues.CapturedOutput captured;
        private final Writer encoder;
        private int emitted;
        private boolean reportedTruncation;

        private OutputWriter(String channel, ScriptValues.CapturedOutput captured) {
            this.channel = channel; this.captured = captured;
            encoder = new OutputStreamWriter(captured, StandardCharsets.UTF_8);
        }
        @Override public synchronized void write(char[] text, int offset, int length) throws IOException {
            encoder.write(text, offset, length);
            encoder.flush();
            publish();
        }
        @Override public synchronized void flush() throws IOException { encoder.flush(); publish(); }
        @Override public synchronized void close() throws IOException { encoder.close(); publish(); }

        private void publish() {
            if (reportedTruncation) return;
            String text = captured.text();
            boolean truncated = captured.isTruncated();
            while (emitted < text.length()) {
                int end = emitted;
                int bytes = 0;
                while (end < text.length()) {
                    int point = text.codePointAt(end);
                    int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
                    if (bytes + size > 4096) break;
                    bytes += size; end += Character.charCount(point);
                }
                output.accept(new Output(channel, text.substring(emitted, end), truncated));
                emitted = end;
                reportedTruncation |= truncated;
            }
            if (truncated && !reportedTruncation) {
                reportedTruncation = true;
                output.accept(new Output(channel, "", true));
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
