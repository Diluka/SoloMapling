package soloMapling.game.runtime;

import soloMapling.game.action.NativeActionFact;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One exact native role, one carrier lane, and replaceable compiled modules. Internal ABI v0.1. */
public final class CharacterRuntime {
    private static final Map<Object, CharacterRuntime> ROLE_OWNERS = new IdentityHashMap<>();
    private final Object monitor = new Object();
    private final Object generation = new Object();
    private final String id = "runtime-" + UUID.randomUUID();
    private final WorldEpoch.Token epoch;
    private final Options options;
    private final ExecutorService ownedExecutor;
    private final ExecutionScope rootScope;
    private final RuntimeEvents events;
    private final CompletableFuture<Void> modulesDrained = new CompletableFuture<>();
    private final Map<String, ModuleEntry> modules = new LinkedHashMap<>();
    private final Map<String, ModuleContext> controlOwners = new LinkedHashMap<>();
    private volatile CharacterCommandLane lane;
    private volatile State state = State.OPENING;
    private volatile RoleBinding binding;
    private volatile GameContext.CharacterRef characterRef;
    private volatile Object originIdentity;
    private volatile String lastFailure;
    private ScopeResources.Registration origin;
    private CharacterCommandLane.AdmissionPermit admission;
    private ExecutionScope.WorkHandle openingWork;
    private ScopeResources.CleanupAttempt originCleanupAttempt;
    private ScopeResources.CleanupAttempt verifiedOriginAttempt;
    private RoleBinding.ReleaseProof originReleaseProof;
    private long nextModuleGeneration;
    private String originKind;
    private Integer characterId;
    private CharacterCommandLane.Snapshot closedLane;

    public record Options(int commandCapacity, int cleanupReserve, ExecutionScope.Limits rootLimits,
                          int eventCapacity, int maxModules) {
        public Options {
            if (commandCapacity < 2 || cleanupReserve < 1 || cleanupReserve >= commandCapacity
                    || eventCapacity < 1 || maxModules < 1 || rootLimits == null || rootLimits.maxResources() < 1) {
                throw new IllegalArgumentException("invalid_runtime_options");
            }
        }
    }

    public enum State { OPENING, BOUND, CLOSING, CLOSED }
    public enum ModuleState { MOUNTED, ACTIVE, STOPPING, SUSPENDED, FAILED, UNLOADED }
    public enum StopMode { PAUSE, UNLOAD }
    public record ModuleSnapshot(String typeId, String instanceId, long generation, boolean enabled,
                                 ModuleState state, List<String> controls, String reason,
                                 ExecutionScope.ScopeSnapshot scope, int runningHooks) { }
    public record Snapshot(String runtimeId, String epoch, State state, String originKind, Integer characterId,
                           List<ModuleSnapshot> modules, ExecutionScope.ScopeSnapshot rootScope,
                           CharacterCommandLane.Snapshot lane, String failure) { }

    public static final class Invalid extends RuntimeException {
        private final String reason;
        public Invalid(String reason) { super(reason, null, false, false); this.reason = reason; }
        public String reason() { return reason; }
    }

    /** The passed serial bounded executor transfers ownership to this runtime; no second queue is made. */
    public CharacterRuntime(WorldEpoch.Token epoch, Options options, ExecutorService executor) {
        this.epoch = Objects.requireNonNull(epoch);
        this.options = Objects.requireNonNull(options);
        ownedExecutor = Objects.requireNonNull(executor);
        rootScope = new ExecutionScope(epoch, new ExecutionScope.OwnerStamp(generation, new Object()), options.rootLimits());
        events = new RuntimeEvents(id, options.eventCapacity());
        try {
            lane = new CharacterCommandLane(options.commandCapacity(), options.cleanupReserve(), executor);
            admission = lane.admissionPermit(rootScope);
        } catch (RuntimeException failure) {
            if (lane != null) lane.close();
            ownedExecutor.shutdown();
            throw failure;
        }
        events.append(RuntimeEvents.Kind.CREATED, null, 0, null, null, null);
    }

    public String id() { return id; }
    public WorldEpoch.Token acceptedEpoch() { return epoch; }
    public ExecutionScope lifecycleScope() { return rootScope; }
    public RuntimeEvents.Window events(long since, int limit) { return events.read(since, limit); }

    /** Closing barrier for asynchronous module resources; completion never grants root cleanup authority. */
    public CompletionStage<Void> whenModulesDrained() {
        signalModulesDrained();
        return modulesDrained.minimalCompletionStage();
    }

    private void signalModulesDrained() {
        boolean ready;
        synchronized (monitor) {
            ready = (state == State.CLOSING || state == State.CLOSED) && modules.isEmpty()
                    && rootScope.snapshot().state() != ExecutionScope.State.ACTIVE;
        }
        // Cleanup subscribers may submit to the same lane. Never invoke them under the host monitor.
        if (ready) modulesDrained.complete(null);
    }

    public ExecutionScope.WorkHandle acceptOpeningWork() {
        synchronized (monitor) {
            require(state == State.OPENING && openingWork == null, "runtime_open_already_accepted");
            openingWork = rootScope.acceptWork();
            trackRoot(openingWork);
            return openingWork;
        }
    }

    public CharacterCommandLane.Submission submitOpening(ExecutionScope.WorkHandle work, Runnable body, Runnable settled) {
        CharacterCommandLane current;
        synchronized (monitor) {
            // A stop can race the gap between accepting OPEN and attaching its carrier. The
            // already-owned work must still reach the original lane and settle as skipped.
            require((state == State.OPENING || state == State.CLOSING) && work == openingWork, "invalid_runtime_open_work");
            current = requireLane();
        }
        return current.submitInitialization(work, admission, body, settled);
    }

    /** Called from the admitted native OPEN factory body, before it can create any role resource. */
    public OriginResource reserveOrigin(ExecutionScope.WorkHandle work) {
        synchronized (monitor) {
            require(work == openingWork && origin == null, "runtime_origin_already_reserved");
            origin = rootScope.resources().beginOwnedResult(work, "native_role");
            return new OriginResource(origin);
        }
    }

    public final class OriginResource {
        private final ScopeResources.Registration registration;
        private OriginResource(ScopeResources.Registration registration) { this.registration = registration; }
        public void requirePendingCleanupOrigin() { registration.requirePendingCleanupOrigin(); }
        public void creationFailed(String fixedReason) { registration.creationFailed(fixedReason); }
        public void confirmAbsent() { registration.confirmAbsent(); tryFinalizeClose(); }
        public void adopt(RoleBinding exactBinding) {
            Objects.requireNonNull(exactBinding);
            GameContext.CharacterRef ref = Objects.requireNonNull(exactBinding.characterRef());
            Object identity = exactBinding.identity();
            // An admitted factory can return after the world stopped or restarted. Its exact
            // result still belongs to this root; epoch checks gate binding and cleanup authority,
            // not ownership of the resource debt. Never refresh the source's captured reference.
            require(identity != null && ref.character() != null
                    && ref.client() != null && ref.world() != null && ref.channel() != null, "runtime_origin_mismatch");
            synchronized (monitor) {
                require(binding == null || binding == exactBinding, "runtime_origin_mismatch");
                require(characterRef == null || sameCharacter(characterRef, ref), "runtime_origin_mismatch");
                require(originIdentity == null || originIdentity == identity, "runtime_origin_mismatch");
                synchronized (ROLE_OWNERS) {
                    CharacterRuntime previous = ROLE_OWNERS.get(ref.character());
                    require(previous == null || previous == CharacterRuntime.this || previous.state == State.CLOSED,
                            "native_role_already_owned");
                    ROLE_OWNERS.put(ref.character(), CharacterRuntime.this);
                }
                // Preserve ownership if attachment becomes unverified; only proved absence/release clears it.
                binding = exactBinding;
                characterRef = ref;
                originIdentity = identity;
                registration.attach(identity, () -> { /* Final release stays on this runtime's lane. */ });
            }
        }
    }

    /** Native creation/adoption is not yet online success. The origin adapter proves its own identity policy. */
    public void bindAdoptedOrigin() {
        RoleBinding exact = requireBinding();
        exact.requireCurrent();
        GameContext.CharacterRef ref = characterRef;
        String kind = safeId(exact.kind());
        require(ref.epoch() == epoch && ref.character() != null && ref.client() != null
                && ref.world() != null && ref.channel() != null, "runtime_origin_mismatch");
        synchronized (monitor) {
            require(state == State.OPENING && binding == exact && origin != null, "runtime_not_opening");
            rootScope.requireActive();
            require(sameCharacter(ref, exact.characterRef()) && exact.identity() == originIdentity, "runtime_origin_mismatch");
            synchronized (ROLE_OWNERS) {
                CharacterRuntime previous = ROLE_OWNERS.get(ref.character());
                require(previous == this, "native_role_already_owned");
            }
            originKind = kind;
            characterId = ref.character().getId();
            state = State.BOUND;
            events.append(RuntimeEvents.Kind.BOUND, null, 0, null, null, null);
        }
    }

    /** This is a fresh origin projection, not a runtime-maintained copy of native role fields. */
    public Map<String, Object> observeOrigin() {
        RoleBinding exact = requireBound();
        Map<String, Object> facts = new LinkedHashMap<>(Objects.requireNonNull(exact.observe()));
        exact.requireCurrent();
        synchronized (monitor) {
            require(state == State.BOUND && binding == exact, "runtime_binding_changed");
            rootScope.requireActive();
        }
        return Collections.unmodifiableMap(facts);
    }

    public ModuleRef mount(RuntimeModule.Spec spec, RuntimeModule module, boolean enabled) {
        Objects.requireNonNull(spec); Objects.requireNonNull(module);
        ModuleEntry entry;
        synchronized (monitor) {
            require(state == State.BOUND, "runtime_not_bound");
            require(!modules.containsKey(spec.instanceId()), "module_instance_exists");
            require(modules.size() < options.maxModules(), "runtime_module_capacity");
            entry = new ModuleEntry(spec, module, enabled);
            modules.put(spec.instanceId(), entry);
            event(RuntimeEvents.Kind.MODULE_MOUNTED, entry, null, null, null);
        }
        if (enabled) activate(entry.ref);
        return entry.ref;
    }

    /** A stable instance label receives a fresh handle/scope generation on each resumed activation. */
    public ModuleRef activate(ModuleRef ref) {
        ModuleEntry entry;
        ModuleContext context;
        synchronized (monitor) {
            entry = entry(ref);
            require(state == State.BOUND && entry.enabled, "module_not_enabled");
            if (entry.state == ModuleState.ACTIVE) return entry.ref;
            require(entry.state == ModuleState.MOUNTED || entry.state == ModuleState.SUSPENDED, "module_not_activatable");
            if (entry.spec.controls().stream().anyMatch(controlOwners::containsKey)) {
                entry.reason = "control_busy";
                return entry.ref;
            }
            if (entry.context != null) throw new Invalid("module_scope_not_drained");
            entry.ref = new ModuleRef(entry.spec.instanceId(), ++nextModuleGeneration);
            ExecutionScope scope = new ExecutionScope(epoch,
                    new ExecutionScope.OwnerStamp(generation, new Object()), entry.spec.limits());
            CharacterCommandLane.Grant grant = requireLane().declareGrant(scope);
            context = new ModuleContext(entry.ref, scope, grant);
            entry.context = context;
            entry.stop = null;
            entry.state = ModuleState.ACTIVE;
            entry.reason = null;
            entry.spec.controls().forEach(key -> controlOwners.put(key, context));
            beginHookLocked(entry);
            event(RuntimeEvents.Kind.MODULE_ACTIVE, entry, null, null, null);
        }
        try { entry.module.onActivated(context); }
        catch (RuntimeException failure) {
            lastFailure = "module_activation_failed";
            stop(entry.ref, StopMode.PAUSE);
            synchronized (monitor) {
                if (entry.stop != null) entry.stop.failure = lastFailure;
                if (entry.state == ModuleState.SUSPENDED) entry.state = ModuleState.FAILED;
                entry.reason = lastFailure;
            }
            throw new Invalid("module_activation_failed");
        } finally { endHook(entry); }
        return entry.ref;
    }

    public ModuleContext context(ModuleRef ref) {
        synchronized (monitor) {
            ModuleEntry entry = entry(ref);
            require(entry.state == ModuleState.ACTIVE && entry.context != null, "module_not_active");
            return entry.context;
        }
    }

    public ModuleRef setEnabled(ModuleRef ref, boolean enabled) {
        ModuleEntry entry;
        synchronized (monitor) { entry = entry(ref); entry.enabled = enabled; }
        if (!enabled) { stop(ref, StopMode.PAUSE); return entry.ref; }
        return activate(entry.ref);
    }

    public final class ModuleRef {
        private final Object owner = generation;
        private final String instanceId;
        private final long moduleGeneration;
        private ModuleRef(String instanceId, long moduleGeneration) { this.instanceId = instanceId; this.moduleGeneration = moduleGeneration; }
        public String instanceId() { return instanceId; }
        public long generation() { return moduleGeneration; }
    }

    /** Trusted in-process action adapter seam. Preparation must not submit or execute native work. */
    @FunctionalInterface public interface NativePreparation<T> {
        T prepare(CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                  ExecutionScope.WorkHandle work, GameContext.CharacterRef character);
    }

    public final class ModuleContext {
        private final ModuleRef ref;
        private final ExecutionScope scope;
        private CharacterCommandLane.Grant grant;
        private ModuleContext(ModuleRef ref, ExecutionScope scope, CharacterCommandLane.Grant grant) {
            this.ref = ref; this.scope = scope; this.grant = grant;
        }
        public ModuleRef reference() { return ref; }
        public ExecutionScope scope() { return scope; }
        public ExecutionScope.WorkHandle acceptWork() {
            synchronized (monitor) {
                requireActive(this, null);
                ExecutionScope.WorkHandle work = scope.acceptWork();
                event(RuntimeEvents.Kind.COMMAND_ACCEPTED, entry(ref), work.snapshot().id(), null, null);
                work.completion().whenComplete((result, failure) -> {
                    events.append(RuntimeEvents.Kind.COMMAND_SETTLED, ref.instanceId, ref.moduleGeneration,
                            work.snapshot().id(), failure == null ? null : "work_completion_failed", null);
                    tryFinishStop(ref);
                    tryFinalizeClose();
                });
                return work;
            }
        }
        public void cancel(ExecutionScope.WorkHandle work) { requireOwn(work); scope.requestCancel(work); }
        public void reject(ExecutionScope.WorkHandle work) { requireOwn(work); work.reject(); }
        public boolean publishOnce(ExecutionScope.WorkHandle work, Runnable apply) {
            requireOwn(work);
            return scope.publishOnce(work, () -> {
                synchronized (monitor) { requireActive(this, null); }
                apply.run();
            });
        }
        public CharacterCommandLane.Submission submit(String control, ExecutionScope.WorkHandle work,
                                                       Runnable preflight, Runnable body, Runnable settled) {
            requireOwn(work);
            CharacterCommandLane current;
            CharacterCommandLane.ControlPermit permit;
            synchronized (monitor) { current = requireLane(); permit = permit(this, control, preflight); }
            return current.submit(work, permit, body, settled);
        }
        public <T> T prepareNative(String control, ExecutionScope.WorkHandle work, Runnable preflight,
                                   NativePreparation<T> preparation) {
            requireOwn(work); Objects.requireNonNull(preparation);
            CharacterCommandLane current;
            CharacterCommandLane.ControlPermit permit;
            synchronized (monitor) { current = requireLane(); permit = permit(this, control, preflight); }
            return preparation.prepare(current, permit, work, permit.characterRef());
        }
        public void recordResult(ExecutionScope.WorkHandle work, NativeActionFact result) {
            requireOwn(work); Objects.requireNonNull(result);
            require(characterId != null && result.senderCharacterId() == characterId
                    && epoch.value().equals(result.epoch()), "foreign_action_result");
            // This records an observed native fact; it does not revive the old module or apply its state.
            events.append(RuntimeEvents.Kind.ACTION_RESULT, ref.instanceId, ref.moduleGeneration,
                    work.snapshot().id(), result.fixedReason(), result.outcome().name());
        }
        public CharacterCommandLane.Submission submitCleanup(ScopeResources.CleanupAttempt cleanup,
                                                              Runnable body, Runnable settled) {
            requireOwn(cleanup.work());
            CharacterCommandLane current;
            synchronized (monitor) { current = requireLane(); }
            cleanup.work().completion().whenComplete((ignored, failure) -> { tryFinishStop(ref); tryFinalizeClose(); });
            return current.submitOwnedCleanup(cleanup, body, settled);
        }
        private void requireOwn(ExecutionScope.WorkHandle work) {
            require(work != null && work.scope() == scope, "foreign_module_work");
        }
    }

    public ModuleStop stop(ModuleRef ref, StopMode mode) {
        Objects.requireNonNull(mode);
        ModuleEntry entry;
        ModuleContext context;
        synchronized (monitor) {
            entry = entry(ref);
            if (entry.stop != null && !entry.stop.complete) {
                if (mode == StopMode.UNLOAD) entry.stop.mode = mode;
                return entry.stop;
            }
            if (entry.stop != null && entry.stop.complete && mode == StopMode.PAUSE) return entry.stop;
            context = entry.context;
            entry.stop = new ModuleStop(ref, context, mode);
            entry.state = ModuleState.STOPPING;
            entry.reason = mode == StopMode.UNLOAD ? "unloading" : "paused";
            if (context != null) context.grant.revoke();
            if (context != null) beginHookLocked(entry);
            event(RuntimeEvents.Kind.MODULE_STOPPING, entry, null, entry.reason, null);
        }
        if (context != null) {
            try { entry.module.onStopping(context, mode); }
            catch (RuntimeException failure) { entry.stop.failure = "module_stop_hook_failed"; lastFailure = entry.stop.failure; }
            finally { endHook(entry); }
            context.scope.beginDrain();
        }
        tryFinishStop(ref);
        return entry.stop;
    }

    public final class ModuleStop {
        private final ModuleRef ref;
        private final ModuleContext context;
        private volatile StopMode mode;
        private volatile String failure;
        private volatile boolean complete;
        private boolean finishing;
        private Thread finisher;
        private ModuleStop(ModuleRef ref, ModuleContext context, StopMode mode) { this.ref = ref; this.context = context; this.mode = mode; }
        public boolean complete() { tryFinishStop(ref); return complete; }
        public String failure() { return failure; }
        public ExecutionScope.DrainResult await(Duration timeout) {
            Objects.requireNonNull(timeout);
            if (timeout.isNegative()) throw new IllegalArgumentException("negative_module_stop_timeout");
            synchronized (monitor) {
                ModuleEntry current = modules.get(ref.instanceId);
                if (current != null && current.ref == ref && current.hookThreads.containsKey(Thread.currentThread())) {
                    return ExecutionScope.DrainResult.SELF_WAIT;
                }
            }
            long began = System.nanoTime();
            long budget;
            try { budget = timeout.toNanos(); } catch (ArithmeticException overflow) { budget = Long.MAX_VALUE; }
            if (context != null) {
                ExecutionScope.DrainResult result = context.scope.awaitDrained(timeout);
                if (result != ExecutionScope.DrainResult.DRAINED) return result;
            }
            tryFinishStop(ref);
            synchronized (monitor) {
                if (finisher == Thread.currentThread()) return ExecutionScope.DrainResult.SELF_WAIT;
                while (!complete) {
                    long remaining = budget - (System.nanoTime() - began);
                    if (remaining <= 0) return ExecutionScope.DrainResult.TIMED_OUT;
                    try { java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); return ExecutionScope.DrainResult.INTERRUPTED;
                    }
                }
            }
            return ExecutionScope.DrainResult.DRAINED;
        }
    }

    private void tryFinishStop(ModuleRef ref) {
        ModuleEntry finished;
        ModuleContext context;
        boolean unload;
        synchronized (monitor) {
            finished = modules.get(ref.instanceId);
            if (finished == null || finished.ref != ref || finished.stop == null || finished.stop.complete
                    || finished.stop.finishing || finished.runningHooks != 0) return;
            context = finished.stop.context;
            if (context != null && (!context.scope.snapshot().unloadComplete()
                    || context.grant.awaitReleased(Duration.ZERO) != CharacterCommandLane.GrantDrain.DRAINED)) return;
            unload = finished.stop.mode == StopMode.UNLOAD;
            if (!unload) {
                finishStopLocked(finished, context, false);
            } else {
                finished.stop.finishing = true;
                finished.stop.finisher = Thread.currentThread();
                beginHookLocked(finished);
            }
        }
        if (unload) {
            try { finished.module.onUnloaded(); }
            catch (RuntimeException failure) {
                lastFailure = "module_unload_hook_failed";
                finished.stop.failure = lastFailure;
            } finally {
                endHook(finished);
                synchronized (monitor) {
                    finished.stop.finisher = null;
                    finishStopLocked(finished, context, true);
                }
            }
        }
        tryFinalizeClose();
    }

    private void finishStopLocked(ModuleEntry entry, ModuleContext context, boolean remove) {
        if (context != null) controlOwners.entrySet().removeIf(e -> e.getValue() == context);
        if (context != null) context.grant = null;
        entry.context = null;
        entry.stop.complete = true;
        entry.state = remove ? ModuleState.UNLOADED : entry.stop.failure == null ? ModuleState.SUSPENDED : ModuleState.FAILED;
        event(remove ? RuntimeEvents.Kind.MODULE_UNLOADED : RuntimeEvents.Kind.MODULE_STOPPED,
                entry, null, entry.stop.failure, null);
        if (remove) modules.remove(entry.ref.instanceId);
        monitor.notifyAll();
    }

    public void beginClose(Optional<ExecutionScope.WorldStopTicket> stop) {
        Objects.requireNonNull(stop);
        List<ModuleRef> refs;
        synchronized (monitor) {
            if (state == State.CLOSED) return;
            if (state != State.CLOSING) events.append(RuntimeEvents.Kind.CLOSING, null, 0, null, null, null);
            state = State.CLOSING;
            refs = modules.values().stream().map(e -> e.ref).toList();
        }
        RuntimeException firstFailure = null;
        try {
            for (ModuleRef ref : refs) {
                try {
                    ModuleContext context;
                    synchronized (monitor) {
                        ModuleEntry entry = modules.get(ref.instanceId);
                        if (entry == null || entry.ref != ref) continue;
                        context = entry.context;
                    }
                    if (context != null) stop.filter(t -> t.sourceEpoch() == epoch).ifPresent(context.scope::joinWorldStop);
                    stop(ref, StopMode.UNLOAD);
                } catch (Invalid changed) {
                    synchronized (monitor) {
                        ModuleEntry current = modules.get(ref.instanceId);
                        if ("stale_module_reference".equals(changed.reason()) && (current == null || current.ref != ref)) continue;
                    }
                    if (firstFailure == null) firstFailure = changed;
                } catch (RuntimeException failure) {
                    if (firstFailure == null) firstFailure = failure;
                }
            }
        } finally {
            try { stop.filter(t -> t.sourceEpoch() == epoch).ifPresent(rootScope::joinWorldStop); }
            finally { rootScope.beginDrain(); tryFinalizeClose(); }
        }
        if (firstFailure != null) {
            lastFailure = "runtime_module_stop_failed";
            throw new Invalid(lastFailure);
        }
    }

    public ScopeResources.CleanupAttempt acquireOriginCleanup() {
        synchronized (monitor) {
            require(origin != null && binding != null, "runtime_origin_missing");
            originCleanupAttempt = origin.acquireCleanup();
            return originCleanupAttempt;
        }
    }

    public CharacterCommandLane.Submission submitOriginCleanup(ScopeResources.CleanupAttempt attempt,
                                                                Runnable body, Runnable settled) {
        require(attempt.work().scope() == rootScope, "foreign_origin_cleanup");
        CharacterCommandLane current;
        synchronized (monitor) { current = requireLane(); }
        trackRoot(attempt.work());
        return current.submitOwnedCleanup(attempt, body, settled);
    }

    /** Runs only inside the exact origin cleanup work, including an admitted OPEN rollback. */
    public RoleBinding.ReleaseProof releaseOrigin(ScopeResources.CleanupAttempt attempt) {
        require(attempt != null && attempt.work().scope() == rootScope, "foreign_origin_cleanup");
        RoleBinding exact;
        ScopeResources.Registration registration;
        synchronized (monitor) {
            if (attempt == verifiedOriginAttempt && originReleaseProof != null) return originReleaseProof;
            require(attempt == originCleanupAttempt, "foreign_origin_cleanup");
            require(modules.isEmpty(), "runtime_modules_not_drained");
            exact = requireBinding(); registration = origin;
        }
        Object identity;
        synchronized (monitor) { identity = originIdentity; require(identity == exact.identity(), "runtime_origin_mismatch"); }
        attempt.permit().requireExecuting(identity);
        RoleBinding.ReleaseProof proof = Objects.requireNonNull(exact.release(attempt.permit()));
        require(proof.released(), "native_role_release_unverified");
        registration.confirmReleased(attempt.permit(), identity);
        synchronized (monitor) { verifiedOriginAttempt = attempt; originReleaseProof = proof; }
        return proof;
    }

    public boolean originReleased() { return rootScope.resources().snapshot().outstanding() == 0; }

    public ExecutionScope.DrainResult awaitClosed(Duration timeout) {
        synchronized (monitor) {
            if (modules.values().stream().anyMatch(entry -> entry.hookThreads.containsKey(Thread.currentThread()))) {
                return ExecutionScope.DrainResult.SELF_WAIT;
            }
        }
        ExecutionScope.DrainResult result = rootScope.awaitDrained(timeout);
        if (result == ExecutionScope.DrainResult.DRAINED) tryFinalizeClose();
        return result;
    }

    public boolean tryFinalizeClose() {
        signalModulesDrained();
        CharacterCommandLane finished;
        synchronized (monitor) {
            if (state == State.CLOSED) return true;
            if (state != State.CLOSING || !modules.isEmpty() || !rootScope.snapshot().unloadComplete()
                    || lane == null || lane.snapshot().pending() != 0) return false;
            finished = lane;
            finished.close();
            closedLane = finished.snapshot();
            lane = null; admission = null; binding = null; openingWork = null; originIdentity = null;
            state = State.CLOSED;
            events.append(RuntimeEvents.Kind.CLOSED, null, 0, null, null, null);
        }
        GameContext.CharacterRef releasedRef = characterRef;
        characterRef = null;
        if (releasedRef != null) synchronized (ROLE_OWNERS) { ROLE_OWNERS.remove(releasedRef.character(), this); }
        ownedExecutor.shutdown();
        return true;
    }

    public Snapshot snapshot() {
        tryFinalizeClose();
        synchronized (monitor) {
            List<ModuleSnapshot> snapshots = modules.values().stream().map(entry -> new ModuleSnapshot(
                    entry.spec.typeId(), entry.spec.instanceId(), entry.ref.moduleGeneration, entry.enabled, entry.state,
                    entry.spec.controls().stream().sorted().toList(), entry.reason,
                    entry.context == null ? null : entry.context.scope.snapshot(), entry.runningHooks)).toList();
            return new Snapshot(id, epoch.value(), state, originKind, characterId, snapshots, rootScope.snapshot(),
                    lane == null ? closedLane : lane.snapshot(), lastFailure);
        }
    }

    private void trackRoot(ExecutionScope.WorkHandle work) {
        work.completion().whenComplete((ignored, failure) -> tryFinalizeClose());
    }

    private void beginHookLocked(ModuleEntry entry) {
        entry.runningHooks++;
        entry.hookThreads.merge(Thread.currentThread(), 1, Integer::sum);
    }

    private void endHook(ModuleEntry entry) {
        synchronized (monitor) {
            entry.runningHooks--;
            entry.hookThreads.computeIfPresent(Thread.currentThread(), (thread, count) -> count == 1 ? null : count - 1);
            monitor.notifyAll();
        }
        tryFinishStop(entry.ref);
        tryFinalizeClose();
    }

    private CharacterCommandLane.ControlPermit permit(ModuleContext context, String control, Runnable preflight) {
        Objects.requireNonNull(preflight);
        requireActive(context, Objects.requireNonNull(control));
        RoleBinding exact = requireBinding();
        GameContext.CharacterRef fixed = characterRef;
        return requireLane().bindGrantedPermit(context.scope, fixed, context.grant, () -> {
            synchronized (monitor) { requireActive(context, control); require(binding == exact, "runtime_binding_changed"); }
            exact.requireCurrent();
            require(sameCharacter(fixed, exact.characterRef()) && originIdentity == exact.identity(), "runtime_origin_mismatch");
            preflight.run();
        });
    }

    private void requireActive(ModuleContext context, String control) {
        ModuleEntry entry = entry(context.ref);
        require(state == State.BOUND && entry.enabled && entry.state == ModuleState.ACTIVE && entry.context == context,
                "module_not_active");
        rootScope.requireActive();
        if (control != null) require(controlOwners.get(control) == context, "module_control_not_owned");
    }

    private ModuleEntry entry(ModuleRef ref) {
        require(ref != null && ref.owner == generation, "foreign_module_reference");
        ModuleEntry entry = modules.get(ref.instanceId);
        require(entry != null && entry.ref == ref, "stale_module_reference");
        return entry;
    }

    private RoleBinding requireBound() {
        RoleBinding exact;
        synchronized (monitor) {
            require(state == State.BOUND, "runtime_not_bound");
            rootScope.requireActive();
            exact = requireBinding();
        }
        exact.requireCurrent();
        require(sameCharacter(characterRef, exact.characterRef()) && originIdentity == exact.identity(), "runtime_origin_mismatch");
        return exact;
    }

    private RoleBinding requireBinding() { RoleBinding exact = binding; require(exact != null, "runtime_origin_missing"); return exact; }
    private CharacterCommandLane requireLane() { CharacterCommandLane current = lane; require(current != null, "runtime_closed"); return current; }
    private static void require(boolean condition, String reason) { if (!condition) throw new Invalid(reason); }
    private static boolean sameCharacter(GameContext.CharacterRef one, GameContext.CharacterRef two) {
        return one != null && two != null && one.epoch() == two.epoch() && one.character() == two.character()
                && one.client() == two.client() && one.world() == two.world() && one.channel() == two.channel();
    }
    private static String safeId(String value) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"), "invalid_origin_kind");
        return value;
    }
    private void event(RuntimeEvents.Kind kind, ModuleEntry entry, Long work, String reason, String outcome) {
        events.append(kind, entry.ref.instanceId, entry.ref.moduleGeneration, work, reason, outcome);
    }

    private final class ModuleEntry {
        final RuntimeModule.Spec spec;
        final RuntimeModule module;
        ModuleRef ref;
        boolean enabled;
        ModuleState state = ModuleState.MOUNTED;
        String reason;
        ModuleContext context;
        ModuleStop stop;
        int runningHooks;
        final Map<Thread, Integer> hookThreads = new IdentityHashMap<>();
        ModuleEntry(RuntimeModule.Spec spec, RuntimeModule module, boolean enabled) {
            this.spec = spec; this.module = module; this.enabled = enabled;
            ref = new ModuleRef(spec.instanceId(), ++nextModuleGeneration);
        }
    }
}
