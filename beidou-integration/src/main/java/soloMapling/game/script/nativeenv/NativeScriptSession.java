package soloMapling.game.script.nativeenv;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.gms.util.ThreadLocalUtil;
import soloMapling.game.command.NativeCommandExecutor;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.script.ScriptValues;
import soloMapling.game.script.ScriptResult;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import java.io.Writer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Exact module-owned engine and native binding. The scope remains the only work/resource ledger. */
public final class NativeScriptSession {
    private static final ThreadLocal<NativeScriptSession> CURRENT = new ThreadLocal<>();
    private static final Map<Object, NativeScriptSession> ENGINES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, NativeScriptSession> SCHEDULERS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, NativeScriptSession> EVENTS = Collections.synchronizedMap(new IdentityHashMap<>());
    private final String id = "native-" + UUID.randomUUID();
    private final String kind;
    private final ExecutionScope scope;
    private final Supplier<ExecutionScope.WorkHandle> acceptWork;
    private final Runnable requireCurrent;
    private final NativeScriptAdapter adapter;
    private final Runnable released;
    private final ScopeResources.Registration registration;
    private final ReentrantLock engineLock = new ReentrantLock(true);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private final CountDownLatch cleaned = new CountDownLatch(1);
    private final Map<Object, ScopeResources.Registration> schedulerResources = Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile GraalJSScriptEngine engine;
    private volatile Context context;
    private volatile Object attachment;
    private volatile boolean nativeDisposed;
    private volatile boolean contextClosed;
    private volatile boolean forced;
    private volatile boolean closed;
    private volatile String scriptPath;
    private volatile NativeScriptExecution activeCall;
    private volatile ScriptResult.ScriptError recordedFailure;
    private volatile ScriptResult.Status recordedFailureStatus;

    public NativeScriptSession(String kind, ExecutionScope scope, Supplier<ExecutionScope.WorkHandle> acceptWork,
                        Runnable requireCurrent, NativeScriptAdapter adapter, Runnable released) {
        this.kind = kind; this.scope = scope; this.acceptWork = acceptWork;
        this.requireCurrent = requireCurrent; this.adapter = adapter; this.released = released;
        registration = scope.resources().beginRegistration("native-script-context");
        registration.attach(this, this::requestClose);
    }

    public static NativeScriptSession current() { return CURRENT.get(); }
    public static NativeScriptSession forEngine(Invocable engine) { return ENGINES.get(engine); }
    public static NativeScriptSession forScheduler(Object scheduler) { return SCHEDULERS.get(scheduler); }
    public static NativeScriptSession forEventManager(Object event) { return EVENTS.get(event); }
    public String contextId() { return id; }
    public String kind() { return kind; }
    public GraalJSScriptEngine engine() {
        GraalJSScriptEngine selected = engine;
        if (selected == null) throw new IllegalStateException("native_script_engine_missing");
        return selected;
    }
    public Object attachment() { return attachment; }
    public void attachment(Object value) { attachment = value; }
    public void markNativeDisposed() { nativeDisposed = true; }
    public boolean isClosing() { return closing.get(); }
    public boolean isClosed() { return closed; }
    public boolean contextClosed() { return contextClosed; }
    ExecutionScope scope() { return scope; }
    NativeScriptAdapter adapter() { return adapter; }
    boolean nativeDisposed() { return nativeDisposed; }

    /** Called before the original loader eval, after its host-access flags were set. */
    public synchronized void captureEngine(GraalJSScriptEngine selected, String path) {
        if (engine != null && engine != selected) {
            // A native manager may try another filename after a failed eval. That newly created
            // candidate was never adopted; close that exact candidate without erasing the first debt.
            selected.getPolyglotContext().close(true);
            throw new IllegalStateException("native_script_engine_replaced");
        }
        boolean firstCapture = engine == null;
        engine = selected; scriptPath = path;
        selected.getContext().setWriter(new InvocationWriter(false));
        selected.getContext().setErrorWriter(new InvocationWriter(true));
        context = selected.getPolyglotContext();
        ENGINES.put(selected, this);
        if (firstCapture) bindCommandExecutor();
        NativeScriptExecution call = activeCall;
        if (call != null) bindParameters(call.parameters());
        if (closing.get()) forceCancel();
    }

    /** One shared native dispatcher, exposed as a language function in this exact engine. */
    private void bindCommandExecutor() {
        String temporary = "__soloNativeCommandBridge";
        engine().put(temporary, (ProxyExecutable) arguments -> {
            if (arguments.length != 1 || !arguments[0].isString()) {
                throw new IllegalArgumentException("executeCommand_requires_one_string");
            }
            NativeScriptExecution call = activeCall;
            if (CURRENT.get() != this || call == null || closing.get() || closed || forced || call.shouldStop()) {
                throw new IllegalStateException("native_command_context_inactive");
            }
            validate();
            // The native adapter has already installed this invocation's real Client.
            // A returned handler is only a dispatch fact, not proof of a game effect.
            return ScriptValues.json(NativeCommandExecutor.execute(
                    ThreadLocalUtil.getCurrentClient(), arguments[0].asString()).toMap());
        });
        try {
            // Use the JSR-223 scope to publish a real guest global, as with parameters.
            // The closure retains the host callback; callers receive only detached JSON.
            engine().eval("""
                    (function (invoke, parse) {
                        globalThis.executeCommand = function (commandText) {
                            return parse(invoke(...arguments));
                        };
                    })(__soloNativeCommandBridge, JSON.parse);
                    delete globalThis.__soloNativeCommandBridge;
                    """);
        } catch (ScriptException failure) {
            recordFailure(failure);
            throw new IllegalStateException("native_command_binding_failed");
        } finally {
            engine().getBindings(ScriptContext.ENGINE_SCOPE).remove(temporary);
        }
    }

    void bindParameters(Map<String, Object> parameters) {
        try {
            // JSR-223 imports its bindings into a script-engine global scope. Put an actual language
            // global through that same engine; Context.getBindings().putMember alone is insufficient.
            engine().eval("globalThis.parameters = JSON.parse(" + ScriptValues.json(ScriptValues.json(parameters)) + ")");
        } catch (ScriptException failure) { recordFailure(failure); throw new IllegalStateException("native_script_parameters_failed"); }
    }

    /** Preserve a failure before an original manager catches and logs it. No Throwable is retained. */
    public void recordFailure(Throwable failure) {
        if (recordedFailure != null) return;
        Throwable selected = failure;
        while (selected.getCause() != null && !(selected instanceof org.graalvm.polyglot.PolyglotException)) {
            selected = selected.getCause();
        }
        Integer line = null; Integer column = null;
        ScriptResult.Status status = ScriptResult.Status.RUNTIME_ERROR;
        if (selected instanceof org.graalvm.polyglot.PolyglotException polyglot) {
            status = polyglot.isSyntaxError() ? ScriptResult.Status.PARSE_ERROR : status;
            if (polyglot.getSourceLocation() != null) {
                line = polyglot.getSourceLocation().getStartLine(); column = polyglot.getSourceLocation().getStartColumn();
            }
        } else if (failure instanceof ScriptException script) {
            line = script.getLineNumber() < 1 ? null : script.getLineNumber();
            column = script.getColumnNumber() < 1 ? null : script.getColumnNumber();
        }
        recordedFailureStatus = status;
        recordedFailure = new ScriptResult.ScriptError(ScriptValues.errorText(selected.getMessage()), line, column);
    }
    ScriptResult.ScriptError recordedFailure() { return recordedFailure; }
    ScriptResult.Status recordedFailureStatus() { return recordedFailureStatus; }

    void enter(NativeScriptExecution call) throws InterruptedException {
        while (!engineLock.tryLock(10, TimeUnit.MILLISECONDS)) {
            if (call.shouldStop()) throw new InterruptedException("native_script_cancelled");
        }
        activeCall = call; CURRENT.set(this);
    }
    void leave() { CURRENT.remove(); activeCall = null; engineLock.unlock(); }
    void validateRole() { requireCurrent.run(); }
    void validate() { validateRole(); adapter.validate(this); }
    void clearInvocationFailure() { recordedFailure = null; recordedFailureStatus = null; }

    public Invocable invocable() {
        return new Invocable() {
            @Override public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
                return managed(() -> engine().invokeFunction(name, args));
            }
            @Override public Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
                return managed(() -> engine().invokeMethod(thiz, name, args));
            }
            @Override public <T> T getInterface(Class<T> type) { return engine().getInterface(type); }
            @Override public <T> T getInterface(Object thiz, Class<T> type) { return engine().getInterface(thiz, type); }
        };
    }
    private Object managed(GuestCall body) throws ScriptException, NoSuchMethodException {
        if (CURRENT.get() == this) {
            if (forced) return null; // Original cancelSchedule cannot run after a proven forced engine stop.
            try { return body.call(); }
            catch (NoSuchMethodException absent) { throw absent; } // Original managers may recover or ignore optional callbacks.
            catch (ScriptException failure) { recordFailure(failure); throw failure; }
            catch (Exception failure) { recordFailure(failure); throw new ScriptException("native_script_callback_failed"); }
        }
        if (closing.get()) return null; // A disposed native scheduler may finish a previously copied tick.
        ExecutionScope.WorkHandle work;
        try { validate(); work = acceptWork.get(); }
        catch (RuntimeException inactive) { return null; }
        return NativeScriptExecution.callback(work, this, body).awaitCallback();
    }
    @FunctionalInterface interface GuestCall { Object call() throws Exception; }

    /** The exact owned EventManager uses this same managed Invocable; channel-global managers are never borrowed. */
    public void ownEventManager(Object event) {
        if (EVENTS.putIfAbsent(event, this) != null) throw new IllegalStateException("native_event_already_owned");
        try {
            java.lang.reflect.Field field = event.getClass().getDeclaredField("ess"); field.setAccessible(true);
            Object scheduler = field.get(event);
            ScopeResources.Registration resource = scope.resources().beginRegistration("native-event-scheduler");
            resource.attach(scheduler, this::requestClose);
            schedulerResources.put(scheduler, resource); SCHEDULERS.put(scheduler, this);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native_event_scheduler_unavailable"); }
    }

    /** Wraps the original ThreadManager carrier, without another queue or replacement scheduler. */
    public Runnable schedulerTask(Object scheduler, Runnable original, boolean dispose) {
        ScopeResources.Registration resource = schedulerResources.get(scheduler);
        if (resource == null) return original;
        ScopeResources.CleanupAttempt cleanup = dispose ? resource.acquireCleanup() : null;
        ExecutionScope.WorkHandle work = cleanup == null ? acceptWork.get() : cleanup.work();
        if (!work.claimCarrier()) throw new IllegalStateException("native_scheduler_work_already_carried");
        return new SchedulerTask(work, () -> {
            original.run();
            if (cleanup != null) resource.confirmReleased(cleanup.permit(), scheduler);
        });
    }
    public void rejectSchedulerTask(Runnable task) {
        if (task instanceof SchedulerTask tracked) { tracked.work.reject(); tracked.work.carrierSettled(); }
    }
    private final class SchedulerTask implements Runnable {
        private final ExecutionScope.WorkHandle work;
        private final Runnable body;
        SchedulerTask(ExecutionScope.WorkHandle work, Runnable body) { this.work = work; this.body = body; }
        @Override public void run() {
            try { scope.runTracked(work, body); }
            finally { work.carrierSettled(); }
        }
    }

    /** Track the complete native timer tick, including callbacks copied outside its native lock. */
    public void schedulerTick(Runnable original) {
        if (closing.get()) return;
        ExecutionScope.WorkHandle work;
        try { work = acceptWork.get(); }
        catch (RuntimeException stopped) { return; }
        if (!work.claimCarrier()) throw new IllegalStateException("native_tick_work_already_carried");
        try { scope.runTracked(work, original); }
        finally { work.carrierSettled(); }
    }

    public void requestClose() {
        closing.set(true);
        if (cleanupStarted.compareAndSet(false, true)) Thread.ofVirtual().name("native-script-close").start(this::cleanup);
    }
    void forceCancel() {
        Context selected = context;
        if (selected != null && !contextClosed) {
            forced = true;
            selected.close(true);
            contextClosed = true;
        }
    }
    boolean closeAndAwait(long milliseconds) throws InterruptedException {
        requestClose();
        return cleaned.await(milliseconds, TimeUnit.MILLISECONDS) && closed;
    }

    private void cleanup() {
        ScopeResources.CleanupAttempt cleanup = null;
        boolean acquired = false;
        AtomicBoolean cleanupExited = new AtomicBoolean();
        Thread watchdog = null;
        try {
            engineLock.lockInterruptibly(); acquired = true;
            watchdog = Thread.ofVirtual().name("native-script-cleanup-deadline").start(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (!cleanupExited.get() && System.nanoTime() < deadline) {
                    try { Thread.sleep(5); } catch (InterruptedException finished) { return; }
                }
                if (!cleanupExited.get()) {
                    try { forceCancel(); } catch (RuntimeException ignored) { /* Retain cleanup debt if the guest cannot exit. */ }
                }
            });
            cleanup = registration.acquireCleanup();
            if (!cleanup.work().claimCarrier()) throw new IllegalStateException("native_cleanup_already_carried");
            ScopeResources.CleanupAttempt exact = cleanup;
            scope.runTracked(exact.work(), () -> {
                CURRENT.set(this);
                try (AutoCloseable nativeContext = adapter.enter(this)) {
                    exact.permit().requireExecuting(this);
                    adapter.close(this);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    for (ScopeResources.Registration scheduler : new ArrayList<>(schedulerResources.values())) {
                        while (!scheduler.released()) {
                            if (System.nanoTime() >= deadline) throw new IllegalStateException("native_scheduler_cleanup_unverified");
                            try { Thread.sleep(2); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("native_cleanup_interrupted"); }
                        }
                    }
                    if (context != null && !contextClosed) { context.close(true); contextClosed = true; }
                    if (context == null) contextClosed = true;
                    registration.confirmReleased(exact.permit(), this);
                    closed = true;
                } catch (Exception failure) { throw new IllegalStateException("native_script_cleanup_unverified"); }
                finally { CURRENT.remove(); }
            });
        } catch (Throwable failure) {
            // Keep the exact resource debt in the existing scope; do not erase it on cleanup failure.
        } finally {
            cleanupExited.set(true);
            if (watchdog != null) {
                watchdog.interrupt();
                try { watchdog.join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (cleanup != null && cleanup.work().snapshot().carrierClaimed()) cleanup.work().carrierSettled();
            if (acquired) engineLock.unlock();
            if (closed) {
                if (engine != null) ENGINES.remove(engine);
                EVENTS.values().removeIf(value -> value == this);
                SCHEDULERS.values().removeIf(value -> value == this);
                attachment = null; engine = null; context = null;
                released.run();
            }
            cleaned.countDown();
        }
    }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("context_id", id); result.put("kind", kind); result.put("context_state", closed ? "CLOSED" : closing.get() ? "CLOSING" : "OPEN");
        result.put("script_path", scriptPath); result.put("context_closed", contextClosed);
        result.put("native_disposed", nativeDisposed); result.put("forced_engine_stop", forced);
        try { result.putAll(adapter.describe(this)); }
        catch (RuntimeException unavailable) { result.put("binding_available", false); }
        return Collections.unmodifiableMap(result);
    }

    private final class InvocationWriter extends Writer {
        private final boolean error;
        InvocationWriter(boolean error) { this.error = error; }
        @Override public void write(char[] text, int offset, int length) throws IOException {
            NativeScriptExecution call = activeCall;
            if (call == null) return;
            call.output(error).write(text, offset, length);
        }
        @Override public void flush() throws IOException {
            NativeScriptExecution call = activeCall;
            if (call != null) call.output(error).flush();
        }
        @Override public void close() { }
    }
}
