package soloMapling.game.execution;

import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;

import java.time.Duration;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One bounded native-write FIFO for one actual character binding. This class chooses no module priority. */
public final class CharacterCommandLane implements AutoCloseable {
    /**
     * Explicit test/host seam: one non-reentrant asynchronous carrier at a time, a finite queue,
     * rejection before acceptance, and no removal of accepted carriers. Never delegate this to a parallel pool.
     */
    public interface SerialBoundedExecutor extends ExecutorService { int commandQueueCapacity(); }

    public enum Status { ENQUEUED, DUPLICATE, REJECTED, CLEANUP_PENDING }
    public enum Rejection { NONE, CAPACITY, PERMIT, EXECUTOR }
    public enum GrantDrain { DRAINED, TIMED_OUT, SELF_WAIT, INTERRUPTED }
    public record Submission(Status status, ExecutionScope.WorkHandle work, Rejection rejection) { }
    public record Snapshot(int pending, int ordinary, int cleanup, boolean closed) { }

    private final Object monitor = new Object();
    private final int capacity;
    private final int cleanupReserve;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    // Values never strongly retain their weak scope key, including through a Grant.
    private final Map<ExecutionScope, ScopeLedger> associated = new WeakHashMap<>();
    private volatile boolean closed;
    private WorldEpoch.Token acceptedEpoch;
    private GameContext.CharacterRef character;
    private ExecutionScope admissionOwner;
    private ExecutionScope.WorkHandle initializationWork;
    private final ThreadLocal<Integer> carrierDepth = ThreadLocal.withInitial(() -> 0);
    private int ordinary;
    private int cleanup;
    private boolean carrierRunning;

    public CharacterCommandLane(int capacity, int cleanupReserve, ThreadFactory threads) {
        this(capacity, cleanupReserve, newExecutor(capacity, threads), true);
    }

    /** Borrowed executors remain the caller's resource; close never shuts them down. */
    public CharacterCommandLane(int capacity, int cleanupReserve, ExecutorService executor) {
        this(capacity, cleanupReserve, executor, false);
    }

    private CharacterCommandLane(int capacity, int cleanupReserve, ExecutorService executor, boolean ownsExecutor) {
        if (capacity < 1 || cleanupReserve < 0 || cleanupReserve >= capacity) throw new IllegalArgumentException("invalid_lane_capacity");
        this.executor = Objects.requireNonNull(executor);
        requireSerialBounded(executor, capacity);
        this.capacity = capacity;
        this.cleanupReserve = cleanupReserve;
        this.ownsExecutor = ownsExecutor;
    }

    /** Caller already authorized this owner. Conflict arbitration and grant handoff remain with that caller. */
    public Grant declareGrant(ExecutionScope scope) {
        Objects.requireNonNull(scope);
        if (!ordinaryCurrent(scope)) throw new IllegalStateException("scope_not_current");
        synchronized (monitor) {
            requireOpen();
            bindEpoch(scope);
            ScopeLedger ledger = associated.computeIfAbsent(scope, ignored -> new ScopeLedger());
            Grant old = ledger.latest.get();
            if (old != null && !old.revoked()) return old;
            Grant grant = new Grant(this, scope, ledger);
            ledger.grants.add(grant);
            ledger.latest = new WeakReference<>(grant);
            return grant;
        }
    }

    public ControlPermit bindGrantedPermit(ExecutionScope scope, GameContext.CharacterRef ref,
                                            Grant grant, Runnable nativeVerify) {
        Objects.requireNonNull(nativeVerify);
        if (ref == null || scope == null || grant == null || grant.lane != this || grant.scope != scope
                || ref.character() == null || ref.client() == null || ref.world() == null || ref.channel() == null
                || ref.epoch() != scope.acceptedEpoch() || !ordinaryCurrent(scope)) throw new IllegalArgumentException("invalid_control_permit");
        synchronized (monitor) {
            requireOpen();
            bindEpoch(scope);
            if (grant.revoked()) throw new IllegalStateException("grant_revoked");
            if (character == null) character = ref;
            else if (!sameCharacter(character, ref)) throw new IllegalArgumentException("lane_character_mismatch");
            associated.computeIfAbsent(scope, ignored -> new ScopeLedger());
            return new ControlPermit(this, scope, ref, grant, nativeVerify);
        }
    }

    /** Only OPEN-style initialization may use this permit; it grants no character command authority. */
    public AdmissionPermit admissionPermit(ExecutionScope scope) {
        Objects.requireNonNull(scope);
        synchronized (monitor) {
            requireOpen();
            bindEpoch(scope);
            if (admissionOwner != null && admissionOwner != scope) throw new IllegalStateException("admission_owner_mismatch");
            if (character != null || initializationWork != null) throw new IllegalStateException("initialization_already_admitted");
            admissionOwner = scope;
            associated.computeIfAbsent(scope, ignored -> new ScopeLedger());
            return new AdmissionPermit(this, scope);
        }
    }

    public Submission submit(ExecutionScope.WorkHandle work, ControlPermit permit, Runnable body, Runnable onWorkSettled) {
        return submitEntry(work, permit, null, false, body, onWorkSettled);
    }

    public Submission submitInitialization(ExecutionScope.WorkHandle work, AdmissionPermit permit,
                                            Runnable body, Runnable onWorkSettled) {
        return submitEntry(work, null, permit, false, body, onWorkSettled);
    }

    public Submission submitOwnedCleanup(ScopeResources.CleanupAttempt attempt, Runnable body, Runnable onWorkSettled) {
        Objects.requireNonNull(attempt);
        return submitEntry(attempt.work(), null, null, true, body, onWorkSettled);
    }

    private Submission submitEntry(ExecutionScope.WorkHandle work, ControlPermit control, AdmissionPermit admission,
                                     boolean isCleanup, Runnable body, Runnable settled) {
        Objects.requireNonNull(work); Objects.requireNonNull(body); Objects.requireNonNull(settled);
        Entry entry = new Entry(work, control, admission, isCleanup, body, settled);
        Rejection rejection = Rejection.NONE;
        synchronized (monitor) {
            // claimCarrier is bookkeeping only; no callback or completion occurs under either ledger lock.
            if (!work.claimCarrier()) return new Submission(Status.DUPLICATE, work, Rejection.NONE);
            boolean permit = isCleanup ? associated.containsKey(work.scope())
                    : control != null ? control.lane == this && control.scope == work.scope()
                    : admission != null && admission.lane == this && admission.scope == work.scope()
                        && admissionOwner == work.scope() && initializationWork == null && character == null;
            if (permit && isCleanup == work.isCleanup()) {
                if (control != null) {
                    // Invalid new submissions settle their scope without occupying the FIFO or reviving a grant.
                    permit = control.grant.claimCarrier();
                    if (permit) entry.carrierGrant = control.grant;
                } else {
                    entry.neutralLedger = associated.get(work.scope());
                    if (entry.neutralLedger != null) entry.neutralLedger.pending++;
                }
            }
            if (closed || !permit || isCleanup != work.isCleanup() || acceptedEpoch != work.acceptedEpoch()) rejection = Rejection.PERMIT;
            else if (isCleanup ? cleanup >= cleanupReserve : ordinary >= capacity - cleanupReserve) rejection = Rejection.CAPACITY;
            else {
                entry.slotHeld = true;
                if (isCleanup) cleanup++;
                else {
                    ordinary++;
                    if (admission != null) initializationWork = work;
                }
            }
        }
        if (rejection != Rejection.NONE) {
            entry.reject();
            return new Submission(isCleanup && rejection == Rejection.CAPACITY ? Status.CLEANUP_PENDING : Status.REJECTED, work, rejection);
        }
        try {
            executor.execute(entry);
            return new Submission(Status.ENQUEUED, work, Rejection.NONE);
        } catch (RuntimeException failure) {
            // A conforming executor rejects before running. Even a broken adapter cannot settle a running body twice.
            if (!entry.reject()) return new Submission(Status.ENQUEUED, work, Rejection.NONE);
            return new Submission(isCleanup ? Status.CLEANUP_PENDING : Status.REJECTED, work, Rejection.EXECUTOR);
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) { return new Snapshot(ordinary + cleanup, ordinary, cleanup, closed); }
    }

    /** Caller requests terminal lane shutdown after arranging owned cleanup; this is not a drain confirmation. */
    @Override public void close() {
        ArrayList<Grant> revoke = new ArrayList<>();
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            for (ScopeLedger ledger : associated.values()) revoke.addAll(ledger.grants);
        }
        revoke.forEach(Grant::revoke);
        if (ownsExecutor) executor.shutdown();
    }

    private void bindEpoch(ExecutionScope scope) {
        if (acceptedEpoch == null) acceptedEpoch = scope.acceptedEpoch();
        else if (acceptedEpoch != scope.acceptedEpoch()) throw new IllegalArgumentException("lane_epoch_mismatch");
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("lane_closed"); }

    private boolean ordinaryCurrent(ExecutionScope scope) {
        WorldEpoch.State now = WorldEpoch.snapshot();
        return !closed && scope.isActive() && now.active() && now.token() == scope.acceptedEpoch();
    }

    private static boolean sameCharacter(GameContext.CharacterRef a, GameContext.CharacterRef b) {
        return a.epoch() == b.epoch() && a.character() == b.character() && a.client() == b.client()
                && a.world() == b.world() && a.channel() == b.channel();
    }

    private final class Entry implements Runnable {
        final ExecutionScope.WorkHandle work;
        final ControlPermit control;
        final AdmissionPermit admission;
        final boolean isCleanup;
        final Runnable body;
        final Runnable settled;
        final AtomicInteger state = new AtomicInteger(); // 0 not entered, 1 carrier running, 2 rejected/finished
        final AtomicBoolean tailFinished = new AtomicBoolean();
        boolean slotHeld;
        Grant carrierGrant;
        ScopeLedger neutralLedger;
        boolean serialCarrier;
        EntryLease lease;

        Entry(ExecutionScope.WorkHandle work, ControlPermit control, AdmissionPermit admission,
              boolean isCleanup, Runnable body, Runnable settled) {
            this.work = work; this.control = control; this.admission = admission; this.isCleanup = isCleanup;
            this.body = body; this.settled = settled;
        }

        @Override public void run() {
            if (!state.compareAndSet(0, 1)) return;
            enterCarrierThread();
            enterRelatedCarrierThread();
            boolean serial;
            synchronized (monitor) { serial = !carrierRunning; if (serial) carrierRunning = true; }
            serialCarrier = serial;
            try {
                if (!serial) { work.reject(); return; }
                work.scope().runTracked(work, () -> {
                    if (isCleanup) { body.run(); return; } // Scope validates its exact DrainPermit, including stop windows.
                    if (!ordinaryCurrent(work.scope()) || work.cancelRequested()) return;
                    if (admission != null) {
                        synchronized (monitor) { if (initializationWork != work || character != null) return; }
                    }
                    if (control != null) {
                        lease = control.grant.enter();
                        if (lease == null) return;
                        control.nativeVerify.run();
                        if (!ordinaryCurrent(work.scope()) || work.cancelRequested() || control.grant.revoked()) return;
                    }
                    body.run();
                });
            } finally {
                settle();
            }
        }

        boolean reject() {
            if (!state.compareAndSet(0, 2)) return false;
            enterCarrierThread();
            enterRelatedCarrierThread();
            try { work.reject(); }
            finally {
                settle();
            }
            return true;
        }

        private void settle() {
            try {
                work.settleCarrier(() -> {
                    try { settled.run(); }
                    finally { finishTail(); }
                });
            } finally { finishTail(); }
        }

        private void finishTail() {
            if (!tailFinished.compareAndSet(false, true)) return;
            if (lease != null) lease.close();
            ArrayList<Grant> changed = new ArrayList<>();
            synchronized (monitor) {
                if (serialCarrier) carrierRunning = false;
                releaseSlot();
                if (neutralLedger != null) { neutralLedger.pending--; changed.addAll(neutralLedger.grants); }
            }
            state.set(2);
            exitCarrierThread();
            if (carrierGrant != null) carrierGrant.carrierExited();
            if (neutralLedger != null) neutralLedger.exitThread();
            changed.forEach(Grant::changed);
        }

        private void releaseSlot() {
            if (!slotHeld) return;
            slotHeld = false;
            if (isCleanup) cleanup--;
            else ordinary--;
        }

        private void enterRelatedCarrierThread() {
            if (carrierGrant != null) carrierGrant.carrierEntered();
            if (neutralLedger != null) neutralLedger.enterThread();
        }
    }

    private void enterCarrierThread() { carrierDepth.set(carrierDepth.get() + 1); }
    private void exitCarrierThread() {
        int depth = carrierDepth.get() - 1;
        if (depth == 0) carrierDepth.remove(); else carrierDepth.set(depth);
    }

    private static final class ScopeLedger {
        volatile int pending;
        final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
        final Set<Grant> grants = Collections.newSetFromMap(new WeakHashMap<>());
        WeakReference<Grant> latest = new WeakReference<>(null);
        void enterThread() { depth.set(depth.get() + 1); }
        void exitThread() { int remaining = depth.get() - 1; if (remaining == 0) depth.remove(); else depth.set(remaining); }
    }

    public static final class ControlPermit {
        private final CharacterCommandLane lane;
        private final ExecutionScope scope;
        private final GameContext.CharacterRef ref;
        private final Grant grant;
        private final Runnable nativeVerify;
        private ControlPermit(CharacterCommandLane lane, ExecutionScope scope, GameContext.CharacterRef ref,
                              Grant grant, Runnable nativeVerify) {
            this.lane = lane; this.scope = scope; this.ref = ref; this.grant = grant; this.nativeVerify = nativeVerify;
        }
        public GameContext.CharacterRef characterRef() { return ref; }
        public void revoke() { grant.revoke(); }
    }

    public static final class AdmissionPermit {
        private final CharacterCommandLane lane;
        private final ExecutionScope scope;
        private AdmissionPermit(CharacterCommandLane lane, ExecutionScope scope) { this.lane = lane; this.scope = scope; }
    }

    public static final class Grant {
        private final CharacterCommandLane lane;
        private final ExecutionScope scope;
        private final ScopeLedger scopeLedger;
        private final Object monitor = new Object();
        private final Map<Thread, Integer> threads = new IdentityHashMap<>();
        private boolean revoked;
        private int inFlight;
        private int pendingCarriers;
        private Grant(CharacterCommandLane lane, ExecutionScope scope, ScopeLedger scopeLedger) {
            this.lane = lane; this.scope = scope; this.scopeLedger = scopeLedger;
        }
        public void revoke() { synchronized (monitor) { revoked = true; monitor.notifyAll(); } }
        public boolean revoked() { synchronized (monitor) { return revoked; } }
        public int inFlight() { synchronized (monitor) { return inFlight; } }
        private void changed() { synchronized (monitor) { monitor.notifyAll(); } }
        private boolean releasedLocked() { return revoked && inFlight == 0 && pendingCarriers == 0 && scopeLedger.pending == 0; }
        private boolean claimCarrier() {
            synchronized (monitor) {
                if (revoked || lane.closed) return false;
                pendingCarriers++;
                return true;
            }
        }
        private void carrierEntered() { synchronized (monitor) { threads.merge(Thread.currentThread(), 1, Integer::sum); } }
        private void carrierExited() {
            synchronized (monitor) {
                pendingCarriers--;
                removeThread(Thread.currentThread());
                monitor.notifyAll();
            }
        }
        private EntryLease enter() {
            synchronized (monitor) {
                if (revoked || lane.closed) return null;
                inFlight++;
                Thread thread = Thread.currentThread();
                threads.merge(thread, 1, Integer::sum);
                return new EntryLease(this, thread);
            }
        }
        private void exit(Thread thread) {
            synchronized (monitor) {
                inFlight--;
                removeThread(thread);
                monitor.notifyAll();
            }
        }
        private void removeThread(Thread thread) { threads.computeIfPresent(thread, (key, count) -> count == 1 ? null : count - 1); }
        public GrantDrain awaitReleased(Duration timeout) {
            Objects.requireNonNull(timeout);
            if (timeout.isNegative()) throw new IllegalArgumentException("negative_timeout");
            long nanos = timeout.toNanos();
            long started = System.nanoTime();
            synchronized (monitor) {
                if (releasedLocked()) return GrantDrain.DRAINED;
                if (threads.containsKey(Thread.currentThread()) || scopeLedger.depth.get() > 0 || lane.carrierDepth.get() > 0) return GrantDrain.SELF_WAIT;
                while (!releasedLocked()) {
                    long remaining = nanos - (System.nanoTime() - started);
                    if (remaining <= 0) return GrantDrain.TIMED_OUT;
                    try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return GrantDrain.INTERRUPTED; }
                }
                return GrantDrain.DRAINED;
            }
        }
    }

    private static final class EntryLease implements AutoCloseable {
        private final Grant grant;
        private final Thread thread;
        private final AtomicBoolean closed = new AtomicBoolean();
        EntryLease(Grant grant, Thread thread) { this.grant = grant; this.thread = thread; }
        @Override public void close() { if (closed.compareAndSet(false, true)) grant.exit(thread); }
    }

    private static ExecutorService newExecutor(int capacity, ThreadFactory threads) {
        if (capacity < 1) throw new IllegalArgumentException("invalid_lane_capacity");
        return new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
                Objects.requireNonNull(threads), new ThreadPoolExecutor.AbortPolicy());
    }

    private static void requireSerialBounded(ExecutorService executor, int capacity) {
        if (executor instanceof ThreadPoolExecutor pool) {
            long queue = (long) pool.getQueue().size() + pool.getQueue().remainingCapacity();
            if (pool.getCorePoolSize() == 1 && pool.getMaximumPoolSize() == 1 && queue >= capacity
                    && queue < Integer.MAX_VALUE && pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy) return;
        } else if (executor instanceof SerialBoundedExecutor serial && serial.commandQueueCapacity() >= capacity
                && serial.commandQueueCapacity() < Integer.MAX_VALUE) return;
        throw new IllegalArgumentException("executor_must_be_serial_and_bounded");
    }
}
