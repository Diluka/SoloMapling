package soloMapling.game.execution;

import soloMapling.game.context.WorldEpoch;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local work ownership and actual execution accounting; no actor or controller policy lives here. */
public final class ExecutionScope {
    private static final Object WORLD_STOP = new Object();
    private static WorldStopTicket lastWorldStop;
    private static final ThreadLocal<ArrayDeque<Frame>> BODIES = ThreadLocal.withInitial(ArrayDeque::new);
    final Object monitor = new Object();
    private final WorldEpoch.Token acceptedEpoch;
    private final Limits limits;
    private final ScopeResources resources;
    private final Map<Long, WorkHandle> work = new LinkedHashMap<>();
    private State state = State.ACTIVE;
    private long nextWork;
    private int runningBodies;
    private int pendingCallbacks;
    private WorldStopTicket joinedStop;

    public ExecutionScope(WorldEpoch.Token acceptedEpoch, Limits limits) {
        this.acceptedEpoch = Objects.requireNonNull(acceptedEpoch);
        this.limits = Objects.requireNonNull(limits);
        resources = new ScopeResources(this);
    }

    public enum State { ACTIVE, DRAINING, CLOSED }
    public enum BodyState { NOT_STARTED, RUNNING, EXITED }
    public enum WorkKind { NORMAL, CLEANUP }
    public enum DrainResult { DRAINED, TIMED_OUT, SELF_WAIT, INTERRUPTED, UNVERIFIED }
    /** maxWork bounds NORMAL handles; maxCleanupWork is a separate, reserved cleanup budget. */
    public record Limits(int maxWork, int maxResources, int maxDiagnostics, int maxCleanupWork) {
        public Limits {
            if (maxWork < 1 || maxResources < 0 || maxDiagnostics < 0 || maxCleanupWork < 0
                    || maxResources > 0 && maxCleanupWork == 0) throw new IllegalArgumentException("invalid_scope_limits");
        }
    }
    public record WorkSnapshot(long id, WorkKind kind, boolean cancelRequested, boolean rejected,
                               boolean queueDeparted, BodyState bodyState, boolean skipped,
                               boolean carrierClaimed, boolean carrierSettled, boolean settlementFailed,
                               boolean resultClaimed, boolean resultApplied, int publicationBodies, boolean settled) { }
    public record ScopeSnapshot(State state, int acceptedWork, int runningBodies, int pendingCarriers,
                                int outstandingResources, boolean unloadComplete, List<WorkSnapshot> diagnostics,
                                int pendingCallbacks) { }

    public static final class Invalid extends RuntimeException {
        private final String reason;
        public Invalid(String reason) {
            super(validReason(reason), null, false, false);
            this.reason = reason;
        }
        private static String validReason(String value) {
            if (value == null || !value.matches("[a-z_]{1,64}")) throw new IllegalArgumentException("invalid_scope_reason");
            return value;
        }
        public String reason() { return reason; }
    }

    public static Optional<WorldStopTicket> tryBeginWorldStop() {
        synchronized (WORLD_STOP) {
            WorldEpoch.StopTransition transition = WorldEpoch.stopTransition();
            if (transition.transitioned()) {
                lastWorldStop = new WorldStopTicket(transition.source().token(), transition.stopped().token());
                return Optional.of(lastWorldStop);
            }
            return lastWorldStop != null && transition.stopped().token() == lastWorldStop.stoppedEpoch
                    ? Optional.of(lastWorldStop) : Optional.empty();
        }
    }

    public static final class WorldStopTicket {
        private final WorldEpoch.Token sourceEpoch;
        private final WorldEpoch.Token stoppedEpoch;
        private WorldStopTicket(WorldEpoch.Token source, WorldEpoch.Token stopped) {
            sourceEpoch = source;
            stoppedEpoch = stopped;
        }
        public WorldEpoch.Token sourceEpoch() { return sourceEpoch; }
        public WorldEpoch.Token stoppedEpoch() { return stoppedEpoch; }
    }

    public WorldEpoch.Token acceptedEpoch() { return acceptedEpoch; }
    public Limits limits() { return limits; }
    public ScopeResources resources() { return resources; }

    public boolean isActive() { synchronized (monitor) { return activeLocked(); } }
    public void requireActive() { synchronized (monitor) { requireResourceAdmissionLocked(); } }

    public WorkHandle acceptWork() {
        return acceptWork(null, null);
    }

    /** The enclosing call supplies cancellation; this scope still owns all actual work and settlement. */
    public WorkHandle acceptWork(ExecutionScope callerScope, WorkHandle callerWork) {
        synchronized (monitor) {
            requireResourceAdmissionLocked();
            return newWorkLocked(null, callerScope, callerWork);
        }
    }

    WorkHandle acceptCleanupLocked(ScopeResources.DrainPermit permit) {
        if (permit.ownerScope() != this || !permit.validLocked()) throw new Invalid("drain_not_allowed");
        return newWorkLocked(permit, null, null);
    }

    private WorkHandle newWorkLocked(ScopeResources.DrainPermit permit, ExecutionScope callerScope, WorkHandle callerWork) {
        requireWorkCapacityLocked(permit != null);
        WorkHandle handle = new WorkHandle(this, ++nextWork, permit, callerScope, callerWork);
        work.put(handle.id, handle);
        return handle;
    }

    private void requireWorkCapacityLocked(boolean cleanup) {
        long active = work.values().stream().filter(handle -> (handle.cleanupPermit != null) == cleanup).count();
        if (active >= (cleanup ? limits.maxCleanupWork() : limits.maxWork())) {
            throw new Invalid(cleanup ? "cleanup_work_capacity_exceeded" : "work_capacity_exceeded");
        }
    }

    public void requestCancel(WorkHandle handle) {
        synchronized (monitor) {
            requireOwnLocked(handle);
            handle.cancelRequested = true;
            monitor.notifyAll();
        }
    }

    public boolean runTracked(WorkHandle handle, Runnable body) {
        Objects.requireNonNull(body);
        WorkSnapshot complete = null;
        boolean enter;
        synchronized (monitor) {
            requireOwnLocked(handle);
            if (handle.bodyAttempted) return false;
            handle.bodyAttempted = true;
            if (handle.carrierClaimed) handle.queueDeparted = true;
            enter = handle.cleanupPermit == null ? activeLocked() && !handle.cancelRequested()
                    : handle.cleanupPermit.validLocked();
            if (!enter) {
                handle.skipped = true;
                complete = finishLocked(handle);
            } else {
                handle.bodyState = BodyState.RUNNING;
                runningBodies++;
            }
        }
        signal(handle, complete);
        if (!enter) return false;
        push(handle, Phase.BODY);
        try {
            body.run();
            return true;
        } finally {
            pop(handle, Phase.BODY);
            synchronized (monitor) {
                handle.bodyState = BodyState.EXITED;
                runningBodies--;
                complete = finishLocked(handle);
            }
            signal(handle, complete);
        }
    }

    /** Controlled application is independent of the factual body/carrier completion stage. */
    public boolean publishOnce(WorkHandle handle, Runnable apply) {
        Objects.requireNonNull(apply);
        synchronized (monitor) {
            requireOwnLocked(handle);
            if (!activeLocked() || handle.cleanupPermit != null || handle.cancelRequested()
                    || !handle.bodyAttempted || handle.skipped || handle.resultClaimed) return false;
            if (!work.containsKey(handle.id)) requireWorkCapacityLocked(false);
            handle.resultClaimed = true;
            handle.publicationBodies++;
            runningBodies++;
            work.put(handle.id, handle);
        }
        push(handle, Phase.PUBLICATION);
        try {
            apply.run();
            synchronized (monitor) { handle.resultApplied = true; }
            return true;
        } finally {
            pop(handle, Phase.PUBLICATION);
            WorkSnapshot complete;
            synchronized (monitor) {
                handle.publicationBodies--;
                runningBodies--;
                complete = finishLocked(handle);
            }
            signal(handle, complete);
        }
    }

    public void beginDrain() {
        synchronized (monitor) {
            if (state == State.ACTIVE) state = State.DRAINING;
            for (WorkHandle handle : new ArrayList<>(work.values())) {
                if (handle.cleanupPermit != null) continue;
                handle.cancelRequested = true;
            }
            changedLocked();
        }
        resources.requestCancellation();
    }

    public void joinWorldStop(WorldStopTicket ticket) {
        synchronized (monitor) {
            WorldEpoch.State current = WorldEpoch.snapshot();
            if (ticket == null || ticket.sourceEpoch != acceptedEpoch || current.active()
                    || current.token() != ticket.stoppedEpoch) throw new Invalid("foreign_world_stop");
            joinedStop = ticket;
        }
        beginDrain();
    }

    public DrainResult awaitDrained(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("negative_drain_timeout");
        long budget;
        try { budget = timeout.toNanos(); } catch (ArithmeticException overflow) { budget = Long.MAX_VALUE; }
        long began = System.nanoTime();
        synchronized (monitor) {
            if (containsThisScope()) return DrainResult.SELF_WAIT;
            if (state == State.ACTIVE) return DrainResult.UNVERIFIED;
            while (state != State.CLOSED) {
                if (work.values().stream().anyMatch(handle -> handle.settlementFailed)) return DrainResult.UNVERIFIED;
                long remaining = budget - (System.nanoTime() - began);
                if (remaining <= 0) return DrainResult.TIMED_OUT;
                try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return DrainResult.INTERRUPTED;
                }
            }
            return DrainResult.DRAINED;
        }
    }

    public ScopeSnapshot snapshot() {
        synchronized (monitor) {
            return new ScopeSnapshot(state, work.size(), runningBodies,
                    (int) work.values().stream().filter(handle -> handle.carrierClaimed && !handle.carrierSettled).count(),
                    resources.outstandingLocked(), state == State.CLOSED,
                    work.values().stream().limit(limits.maxDiagnostics()).map(WorkHandle::snapshotLocked).toList(), pendingCallbacks);
        }
    }

    private boolean activeLocked() {
        WorldEpoch.State current = WorldEpoch.snapshot();
        return state == State.ACTIVE && current.active() && current.token() == acceptedEpoch;
    }

    void requireResourceAdmissionLocked() {
        if (!activeLocked()) throw new Invalid("scope_not_active");
    }

    boolean executingBodyLocked(WorkHandle handle) {
        if (handle == null || handle.scope != this || handle.bodyState != BodyState.RUNNING) return false;
        Frame current = BODIES.get().peekLast();
        return current != null && current.scope == this && current.work == handle && current.phase == Phase.BODY;
    }

    boolean enteredCleanupLocked(WorkHandle handle, ScopeResources.DrainPermit permit) {
        return handle != null && handle.scope == this && handle.cleanupPermit == permit && !handle.skipped
                && (handle.bodyState == BodyState.RUNNING || handle.bodyState == BodyState.EXITED);
    }

    Runnable reserveCancellationSignalLocked(Runnable signal) {
        pendingCallbacks++;
        AtomicBoolean entered = new AtomicBoolean();
        return () -> {
            if (!entered.compareAndSet(false, true)) return;
            synchronized (monitor) {
                pendingCallbacks--;
                runningBodies++;
            }
            push(null, Phase.CANCELLATION);
            try { signal.run(); }
            finally {
                pop(null, Phase.CANCELLATION);
                synchronized (monitor) {
                    runningBodies--;
                    changedLocked();
                }
            }
        };
    }

    boolean drainWindowValidLocked() {
        WorldEpoch.State current = WorldEpoch.snapshot();
        if (current.active()) return current.token() == acceptedEpoch;
        synchronized (WORLD_STOP) {
            WorldStopTicket known = joinedStop != null ? joinedStop : lastWorldStop;
            return known != null && known.sourceEpoch == acceptedEpoch && known.stoppedEpoch == current.token();
        }
    }

    void changedLocked() {
        if (state == State.DRAINING && work.isEmpty() && runningBodies == 0 && pendingCallbacks == 0
                && resources.outstandingLocked() == 0) {
            state = State.CLOSED;
        }
        monitor.notifyAll();
    }

    private WorkSnapshot finishLocked(WorkHandle handle) {
        WorkSnapshot complete = null;
        if (handle.bodyFinishedLocked() && (!handle.carrierClaimed || handle.carrierSettled) && !handle.factualSignalled) {
            handle.factualSignalled = true;
            complete = handle.snapshotLocked();
        }
        if (handle.settledLocked()) work.remove(handle.id, handle);
        changedLocked();
        return complete;
    }

    private void requireOwnLocked(WorkHandle handle) {
        if (handle == null || handle.scope != this) throw new Invalid("foreign_work");
    }

    private static void signal(WorkHandle handle, WorkSnapshot snapshot) {
        if (snapshot != null) handle.factual.complete(snapshot);
    }

    private void push(WorkHandle handle, Phase phase) { BODIES.get().addLast(new Frame(this, handle, phase)); }
    private void pop(WorkHandle handle, Phase phase) {
        ArrayDeque<Frame> frames = BODIES.get();
        Frame frame = frames.removeLast();
        if (frame.scope != this || frame.work != handle || frame.phase != phase) throw new IllegalStateException("scope_stack_mismatch");
        if (frames.isEmpty()) BODIES.remove();
    }
    private boolean containsThisScope() { return BODIES.get().stream().anyMatch(frame -> frame.scope == this); }
    private enum Phase { BODY, PUBLICATION, SETTLEMENT, CANCELLATION }
    private record Frame(ExecutionScope scope, WorkHandle work, Phase phase) { }

    public static final class WorkHandle {
        private final ExecutionScope scope;
        private final long id;
        private final ScopeResources.DrainPermit cleanupPermit;
        private final ExecutionScope callerScope;
        private final WorkHandle callerWork;
        private final CompletableFuture<WorkSnapshot> factual = new CompletableFuture<>();
        private boolean cancelRequested;
        private boolean rejected;
        private boolean queueDeparted;
        private boolean bodyAttempted;
        private BodyState bodyState = BodyState.NOT_STARTED;
        private boolean skipped;
        private boolean carrierClaimed;
        private boolean carrierSettled;
        private boolean settlementClaimed;
        private boolean settlementFailed;
        private boolean resultClaimed;
        private boolean resultApplied;
        private int publicationBodies;
        private boolean factualSignalled;

        private WorkHandle(ExecutionScope scope, long id, ScopeResources.DrainPermit permit,
                           ExecutionScope callerScope, WorkHandle callerWork) {
            this.scope = scope; this.id = id; cleanupPermit = permit;
            this.callerScope = callerScope; this.callerWork = callerWork;
        }
        public ExecutionScope scope() { return scope; }
        public WorldEpoch.Token acceptedEpoch() { return scope.acceptedEpoch; }
        public boolean cancelRequested() {
            synchronized (scope.monitor) { if (cancelRequested) return true; }
            return callerScope != null && !callerScope.isActive() || callerWork != null && callerWork.cancelRequested();
        }
        public boolean isCleanup() { return cleanupPermit != null; }
        public CompletionStage<WorkSnapshot> completion() { return factual.minimalCompletionStage(); }
        public WorkSnapshot snapshot() { synchronized (scope.monitor) { return snapshotLocked(); } }
        public boolean isSettled() { synchronized (scope.monitor) { return settledLocked(); } }

        public boolean claimCarrier() {
            synchronized (scope.monitor) {
                if (carrierClaimed || bodyAttempted) return false;
                carrierClaimed = true;
                return true;
            }
        }

        public void reject() {
            WorkSnapshot complete;
            synchronized (scope.monitor) {
                if (bodyAttempted) return;
                rejected = true;
                bodyAttempted = true;
                skipped = true;
                complete = scope.finishLocked(this);
            }
            signal(this, complete);
        }

        /** Includes the settle hook in the actual-body stack; never invokes caller code under the ledger lock. */
        public boolean settleCarrier(Runnable onWorkSettled) {
            Objects.requireNonNull(onWorkSettled);
            synchronized (scope.monitor) {
                if (!carrierClaimed || settlementClaimed) return false;
                settlementClaimed = true;
                scope.runningBodies++;
            }
            boolean failed = true;
            scope.push(this, Phase.SETTLEMENT);
            try {
                onWorkSettled.run();
                failed = false;
                return true;
            } finally {
                scope.pop(this, Phase.SETTLEMENT);
                WorkSnapshot complete;
                synchronized (scope.monitor) {
                    settlementFailed = failed;
                    carrierSettled = true;
                    scope.runningBodies--;
                    complete = scope.finishLocked(this);
                }
                signal(this, complete);
            }
        }

        public void carrierSettled() { settleCarrier(() -> { }); }
        private boolean bodyFinishedLocked() { return bodyAttempted && (skipped || bodyState == BodyState.EXITED); }
        private boolean settledLocked() {
            return bodyFinishedLocked() && (!carrierClaimed || carrierSettled) && !settlementFailed && publicationBodies == 0;
        }
        private WorkSnapshot snapshotLocked() {
            return new WorkSnapshot(id, cleanupPermit == null ? WorkKind.NORMAL : WorkKind.CLEANUP,
                    cancelRequested(), rejected, queueDeparted, bodyState, skipped, carrierClaimed, carrierSettled,
                    settlementFailed, resultClaimed, resultApplied, publicationBodies, settledLocked());
        }
    }
}
