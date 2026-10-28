package soloMapling.game.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import soloMapling.game.execution.ExecutionScope;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One runtime-owned carrier for one existing scope work. The guest runs off the character lane.
 * The carrier joins the actual guest thread, closes its lexical context, then settles that same work.
 * Neither Future cancellation nor an engine cancellation request is used as exit evidence.
 */
public final class ScriptExecution {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int JSON_LIMIT = 16_384;
    private static final int OUTPUT_LIMIT = 16_384;
    private final ExecutionScope.WorkHandle work;
    private final ScriptRequest request;
    private final String parametersJson;
    private final List<ScriptRegistry.Entry> functions;
    private final Runnable requireCurrent;
    private final long began = System.nanoTime();
    private final AtomicReference<ScriptResult.Status> stopping = new AtomicReference<>();
    private final CompletableFuture<ScriptResult> completion = new CompletableFuture<>();
    private final CapturedOutput stdout = new CapturedOutput();
    private final CapturedOutput stderr = new CapturedOutput();
    private volatile Context context;
    private volatile boolean contextClosed;
    private volatile boolean engineExited;
    private volatile ScriptResult.Status status = ScriptResult.Status.CANCELLED;
    private volatile ScriptResult.ScriptError error;
    private Object result;

    private ScriptExecution(ExecutionScope.WorkHandle work, ScriptRequest request,
                            List<ScriptRegistry.Entry> functions, Runnable requireCurrent) {
        this.work = work;
        this.request = request;
        this.functions = List.copyOf(functions);
        this.requireCurrent = requireCurrent;
        this.parametersJson = encodeJson(request.parameters());
    }

    /** The owner has already accepted this work; no new scope, action queue or work ledger is made. */
    public static ScriptExecution start(ExecutionScope.WorkHandle work, ScriptRequest request,
                                         List<ScriptRegistry.Entry> functions, Runnable requireCurrent) {
        if (!work.claimCarrier()) throw new IllegalArgumentException("script_work_already_submitted");
        try {
            ScriptExecution execution = new ScriptExecution(work, request, functions, requireCurrent);
            Thread.ofVirtual().name("character-script-carrier").start(execution::carry);
            return execution;
        } catch (RuntimeException | Error failed) {
            work.reject();
            work.carrierSettled();
            throw failed;
        }
    }

    public ExecutionScope.WorkHandle work() { return work; }
    public CompletionStage<ScriptResult> completion() { return completion.minimalCompletionStage(); }

    /** Nonblocking: the carrier performs engine cancellation and still waits for real termination. */
    public void cancel() {
        stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
        work.scope().requestCancel(work);
    }

    private void carry() {
        Thread guest = null;
        try {
            guest = Thread.ofVirtual().name("character-script-guest").start(() -> {
                try { work.scope().runTracked(work, this::evaluate); }
                catch (RuntimeException failed) {
                    status = ScriptResult.Status.RUNTIME_ERROR;
                    error = new ScriptResult.ScriptError("script_context_unavailable", null, null);
                } finally { engineExited = true; }
            });
            while (guest.isAlive()) {
                checkStop();
                Context current = context;
                if (stopping.get() != null && current != null) {
                    // close(true) forces a pure guest loop to stop, even when guest code catches errors.
                    // It can block while the engine stops, so it never runs on the lane or an HTTP thread.
                    try { current.close(true); contextClosed = true; }
                    catch (RuntimeException failed) {
                        error = new ScriptResult.ScriptError("script_context_close_failed", null, null);
                    }
                }
                try { guest.join(10); }
                catch (InterruptedException interrupted) {
                    stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
                    work.scope().requestCancel(work);
                }
            }
            // A skipped body never created a context and has no guest resource to release.
            if (context == null) contextClosed = true;
        } catch (RuntimeException | Error failed) {
            if (guest == null) { work.reject(); engineExited = true; contextClosed = true; }
            status = ScriptResult.Status.RUNTIME_ERROR;
            error = new ScriptResult.ScriptError("script_carrier_failed", null, null);
        } finally {
            if (work.cancelRequested() || !work.scope().isActive()) {
                stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
            }
            // Keep an unverified cleanup debt in the original ledger instead of claiming a clean close.
            boolean cleaned = engineExited && contextClosed;
            try { work.settleCarrier(() -> { if (!cleaned) throw new IllegalStateException("script_cleanup_unverified"); }); }
            catch (RuntimeException ignored) { /* The work records settlementFailed and remains outstanding. */ }
            ScriptResult.Status stopped = stopping.get();
            ScriptResult.Status finalStatus = !cleaned ? ScriptResult.Status.CLEANUP_ERROR
                    : stopped != null ? stopped : status;
            completion.complete(new ScriptResult(finalStatus, finalStatus == ScriptResult.Status.SUCCEEDED ? result : null,
                    stdout.text(), stderr.text(), stdout.truncated || stderr.truncated, error,
                    engineExited, contextClosed, work.isSettled()));
            if (cleaned) context = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void evaluate() {
        Context created = null;
        try {
            verifyCurrent();
            if (checkStop()) return;
            created = Context.newBuilder("js").allowAllAccess(false).allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(name -> false).allowIO(false).allowCreateThread(false)
                    .allowNativeAccess(false).allowPolyglotAccess(PolyglotAccess.NONE)
                    .option("engine.WarnInterpreterOnly", "false")
                    .out(stdout).err(stderr).build();
            context = created;
            // Cancellation before publication must also stop this newly published context.
            if (checkStop()) return;
            Value parse = created.eval("js", "JSON.parse");
            created.getBindings("js").putMember("parameters", parse.execute(parametersJson));
            Value publishedFunctions = created.eval("js", "Object.create(null)");
            for (ScriptRegistry.Entry function : functions) {
                publishedFunctions.putMember(function.definition().name(), (ProxyExecutable) arguments -> {
                    verifyCurrent();
                    try {
                        List<Object> values = new ArrayList<>();
                        for (Value argument : arguments) values.add(readJson(argument, 0, new ArrayList<>(), new int[]{0}));
                        Object value = function.invoke(Collections.unmodifiableList(values));
                        return parse.execute(encodeJson(value));
                    } finally { verifyCurrent(); }
                });
            }
            created.getBindings("js").putMember("functions", publishedFunctions);
            if (checkStop()) return;
            Value value = created.eval(Source.newBuilder("js", request.source(), "mcp-script.js").buildLiteral());
            try {
                result = readJson(value, 0, new ArrayList<>(), new int[]{0});
                encodeJson(result);
                checkStop();
                status = ScriptResult.Status.SUCCEEDED;
            } catch (InvalidJson invalid) {
                status = ScriptResult.Status.RESULT_ERROR;
                error = new ScriptResult.ScriptError(invalid.getMessage(), null, null);
            }
        } catch (PolyglotException failed) {
            status = failed.isSyntaxError() ? ScriptResult.Status.PARSE_ERROR : ScriptResult.Status.RUNTIME_ERROR;
            SourceSection location = failed.getSourceLocation();
            error = new ScriptResult.ScriptError(bounded(failed.getMessage(), 1_024),
                    location == null ? null : location.getStartLine(), location == null ? null : location.getStartColumn());
        } catch (RuntimeException failed) {
            status = ScriptResult.Status.RUNTIME_ERROR;
            error = new ScriptResult.ScriptError("script_execution_failed", null, null);
        } finally {
            if (created != null) {
                try { created.close(true); contextClosed = true; }
                catch (RuntimeException failed) {
                    error = new ScriptResult.ScriptError("script_context_close_failed", null, null);
                }
            } else contextClosed = true;
        }
    }

    private boolean checkStop() {
        if (work.cancelRequested() || !work.scope().isActive()) {
            stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
        } else if (System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(request.timeoutMs())) {
            stopping.compareAndSet(null, ScriptResult.Status.TIMED_OUT);
        }
        return stopping.get() != null;
    }

    private void verifyCurrent() {
        try { requireCurrent.run(); }
        catch (RuntimeException unavailable) {
            // Module/root stopping can win before beginDrain reaches the scope. Preserve that
            // cancellation even if the guest exits before the carrier's next polling turn.
            stopping.compareAndSet(null, ScriptResult.Status.CANCELLED);
            work.scope().requestCancel(work);
            throw unavailable;
        }
    }

    /** Converts guest values while its context is still cancellable; only detached JSON crosses out. */
    private static Object readJson(Value value, int depth, List<Value> ancestors, int[] size) {
        if (depth > 32 || ++size[0] > JSON_LIMIT) throw new InvalidJson("script_result_limit");
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) {
            String string = value.asString();
            requireUnicode(string, "script_result_not_json");
            size[0] += string.length();
            if (size[0] > JSON_LIMIT) throw new InvalidJson("script_result_limit");
            return string;
        }
        if (value.isNumber()) {
            if ("bigint".equals(value.getMetaObject().getMetaSimpleName())) throw new InvalidJson("script_result_not_json");
            if (value.fitsInLong()) return value.asLong();
            double number = value.asDouble();
            if (!Double.isFinite(number)) throw new InvalidJson("script_result_not_json");
            return number;
        }
        if (value.canExecute() || value.isHostObject() || value.isProxyObject()) throw new InvalidJson("script_result_not_json");
        for (Value ancestor : ancestors) if (ancestor.equals(value)) throw new InvalidJson("script_result_cycle");
        ancestors.add(value);
        try {
            if (value.hasArrayElements()) {
                long length = value.getArraySize();
                if (length > JSON_LIMIT) throw new InvalidJson("script_result_limit");
                List<Object> array = new ArrayList<>();
                for (long i = 0; i < length; i++) array.add(readJson(value.getArrayElement(i), depth + 1, ancestors, size));
                return Collections.unmodifiableList(array);
            }
            if (value.hasMembers()) {
                // Promise and other deferred values are not successful synchronous script results.
                if (value.hasMember("then") && value.getMember("then").canExecute()) throw new InvalidJson("script_result_not_json");
                Map<String, Object> object = new LinkedHashMap<>();
                for (String key : value.getMemberKeys()) {
                    requireUnicode(key, "script_result_not_json");
                    size[0] += key.length();
                    if (size[0] > JSON_LIMIT) throw new InvalidJson("script_result_limit");
                    object.put(key, readJson(value.getMember(key), depth + 1, ancestors, size));
                }
                return Collections.unmodifiableMap(object);
            }
            throw new InvalidJson("script_result_not_json");
        } finally { ancestors.remove(ancestors.size() - 1); }
    }

    private static String encodeJson(Object value) {
        checkJsonTree(value, 0, new IdentityHashMap<>(), new int[]{0});
        try {
            String json = JSON.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > JSON_LIMIT) throw new InvalidJson("script_json_limit");
            return json;
        } catch (JsonProcessingException failed) { throw new InvalidJson("script_parameters_not_json"); }
    }

    private static void checkJsonTree(Object value, int depth, IdentityHashMap<Object, Boolean> ancestors, int[] size) {
        if (depth > 32 || ++size[0] > JSON_LIMIT) throw new InvalidJson("script_json_limit");
        if (value == null || value instanceof Boolean) return;
        if (value instanceof String string) {
            requireUnicode(string, "script_value_not_json");
            size[0] += string.length();
            if (size[0] > JSON_LIMIT) throw new InvalidJson("script_json_limit");
            return;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal) {
            if (!Double.isFinite(((Number) value).doubleValue())) throw new InvalidJson("script_value_not_json");
            return;
        }
        if (ancestors.put(value, Boolean.TRUE) != null) throw new InvalidJson("script_json_cycle");
        try {
            if (value instanceof Map<?, ?> object) {
                for (Map.Entry<?, ?> entry : object.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new InvalidJson("script_value_not_json");
                    requireUnicode(key, "script_value_not_json");
                    size[0] += key.length();
                    checkJsonTree(entry.getValue(), depth + 1, ancestors, size);
                }
            } else if (value instanceof List<?> array) {
                for (Object entry : array) checkJsonTree(entry, depth + 1, ancestors, size);
            } else throw new InvalidJson("script_value_not_json");
        } finally { ancestors.remove(value); }
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "script_error";
        // Java strings can contain unpaired UTF-16 surrogates. A guest exception must never make
        // the JSON/MCP transport itself fail, so error text replaces only those invalid code units.
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    safe.append(character).append(value.charAt(++index));
                } else safe.append('\ufffd');
            } else if (Character.isLowSurrogate(character)) safe.append('\ufffd');
            else safe.append(character);
        }
        value = safe.toString();
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static void requireUnicode(String value, String reason) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new InvalidJson(reason);
                }
            } else if (Character.isLowSurrogate(character)) throw new InvalidJson(reason);
        }
    }

    private static final class InvalidJson extends IllegalArgumentException {
        private InvalidJson(String message) { super(message); }
    }

    private static final class CapturedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;
        @Override public synchronized void write(int value) {
            if (bytes.size() < OUTPUT_LIMIT) bytes.write(value); else truncated = true;
        }
        @Override public synchronized void write(byte[] data, int offset, int length) {
            int accepted = Math.min(length, OUTPUT_LIMIT - bytes.size());
            bytes.write(data, offset, accepted);
            truncated |= accepted < length;
        }
        private synchronized String text() {
            byte[] data = bytes.toByteArray();
            int end = data.length;
            // Output can be cut in the middle of one UTF-8 code point. Drop only that incomplete tail.
            if (truncated && end > 0) {
                int start = end - 1;
                while (start >= 0 && (data[start] & 0xc0) == 0x80) start--;
                if (start >= 0) {
                    int lead = data[start] & 0xff;
                    int needed = lead < 0x80 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
                    if (end - start < needed) end = start;
                }
            }
            return new String(data, 0, end, StandardCharsets.UTF_8);
        }
    }
}
