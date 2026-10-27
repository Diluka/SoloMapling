package soloMapling.game.execution;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Scope-local ownership and registration handshakes; native release facts remain consumer-owned. */
public final class ScopeResources {
    private final ExecutionScope scope;
    private final Set<Registration> outstanding = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Registration> identities = new IdentityHashMap<>();
    private boolean cancellationRequested;
    private long registeredTotal;
    private long releasedTotal;
    private long absentTotal;
    private long cancellationSignals;
    private long cancellationSignalFailures;

    ScopeResources(ExecutionScope scope) { this.scope = Objects.requireNonNull(scope); }

    public Registration beginRegistration(String kind) {
        requireIdentifier(kind, "invalid_resource_kind");
        synchronized (scope.monitor) {
            scope.requireResourceAdmissionLocked();
            return registerLocked(kind, null);
        }
    }

    public Registration beginOwnedResult(ExecutionScope.WorkHandle origin, String kind) {
        requireIdentifier(kind, "invalid_resource_kind");
        synchronized (scope.monitor) {
            require(origin != null && !origin.isCleanup() && scope.executingBodyLocked(origin),
                    "resource_origin_not_executing");
            return registerLocked(kind, origin);
        }
    }

    public Report snapshot() {
        synchronized (scope.monitor) {
            int pending = 0;
            int attached = 0;
            int unverified = 0;
            for (Registration registration : outstanding) {
                switch (registration.state) {
                    case PENDING -> pending++;
                    case ATTACHED -> attached++;
                    case UNVERIFIED -> unverified++;
                    default -> throw new IllegalStateException("terminal_resource_retained");
                }
            }
            return new Report(outstanding.size(), pending, attached, unverified, registeredTotal,
                    releasedTotal, absentTotal, cancellationSignals, cancellationSignalFailures);
        }
    }

    int outstandingLocked() { return outstanding.size(); }

    /** Called outside the scope lock; each nonblocking adapter is signalled at most once. */
    void requestCancellation() {
        List<Runnable> signals = new ArrayList<>();
        synchronized (scope.monitor) {
            cancellationRequested = true;
            for (Registration registration : outstanding) {
                Runnable signal = registration.cancelSignalLocked();
                if (signal != null) signals.add(signal);
            }
            scope.changedLocked();
        }
        signals.forEach(this::signalCancellation);
    }

    private Registration registerLocked(String kind, ExecutionScope.WorkHandle origin) {
        require(outstanding.size() < scope.limits().maxResources(), "resource_capacity_exceeded");
        Registration registration = new Registration(kind, origin);
        outstanding.add(registration);
        registeredTotal++;
        scope.changedLocked();
        return registration;
    }

    private boolean normalAdmissionOpenLocked() {
        try {
            scope.requireResourceAdmissionLocked();
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private void signalCancellation(Runnable signal) {
        try {
            signal.run();
        } catch (Throwable ignored) {
            // Every signal already has a ledger reservation; one adapter must not strand the rest.
            synchronized (scope.monitor) {
                cancellationSignalFailures++;
                scope.changedLocked();
            }
        }
    }

    public record Report(int outstanding, int pending, int attached, int unverified,
                         long registeredTotal, long releasedTotal, long absentTotal,
                         long cancellationSignals, long cancellationSignalFailures) { }

    private enum State { PENDING, ATTACHED, UNVERIFIED, ABSENT, RELEASED }

    public final class Registration {
        private final String kind;
        private final ExecutionScope.WorkHandle origin;
        private State state = State.PENDING;
        private Object identity;
        private WeakReference<Object> releasedIdentity;
        private Runnable cancelSignal;
        private boolean cancelSignalled;
        private String creationFailure;
        private CleanupAttempt attempt;
        private DrainPermit currentPermit;

        private Registration(String kind, ExecutionScope.WorkHandle origin) {
            this.kind = kind;
            this.origin = origin;
        }

        private ScopeResources owner() { return ScopeResources.this; }

        public String kind() { return kind; }

        public boolean released() {
            synchronized (scope.monitor) { return state == State.RELEASED || state == State.ABSENT; }
        }

        public Object identity() {
            synchronized (scope.monitor) {
                return state == State.RELEASED ? releasedIdentity.get() : identity;
            }
        }

        public String state() {
            synchronized (scope.monitor) { return state.name(); }
        }

        public void attach(Object exactIdentity, Runnable nonblockingCancelSignal) {
            Objects.requireNonNull(exactIdentity, "resource_identity_missing");
            Objects.requireNonNull(nonblockingCancelSignal, "resource_cancel_signal_missing");
            Runnable signal = null;
            synchronized (scope.monitor) {
                if (state == State.ATTACHED && identity == exactIdentity
                        || state == State.RELEASED && releasedIdentity.get() == exactIdentity) return;
                require(state == State.PENDING, "resource_handshake_settled");
                require(!identities.containsKey(exactIdentity), "resource_already_owned");
                identity = exactIdentity;
                cancelSignal = nonblockingCancelSignal;
                state = State.ATTACHED;
                identities.put(identity, this);
                if (cancellationRequested || !normalAdmissionOpenLocked()) signal = cancelSignalLocked();
                scope.changedLocked();
            }
            if (signal != null) signalCancellation(signal);
        }

        /** Unknown partial creation remains owned and outstanding until explicit absence is proved. */
        public void creationFailed(String fixedReason) {
            requireIdentifier(fixedReason, "invalid_resource_failure");
            synchronized (scope.monitor) {
                if (state == State.UNVERIFIED && fixedReason.equals(creationFailure)) return;
                require(state == State.PENDING, "resource_handshake_settled");
                creationFailure = fixedReason;
                state = State.UNVERIFIED;
                scope.changedLocked();
            }
        }

        public void confirmAbsent() {
            synchronized (scope.monitor) {
                require(identity == null, "resource_identity_already_attached");
                if (state == State.ABSENT) return;
                require(state == State.PENDING || state == State.UNVERIFIED, "resource_handshake_settled");
                state = State.ABSENT;
                outstanding.remove(this);
                absentTotal++;
                scope.changedLocked();
            }
        }

        /** Only the actual admitted factory body may perform its no-handle partial cleanup. */
        public void requirePendingCleanupOrigin() {
            synchronized (scope.monitor) {
                require(outstanding.contains(this) && identity == null && origin != null
                        && scope.executingBodyLocked(origin) && scope.drainWindowValidLocked(),
                        "resource_pending_cleanup_not_owned");
            }
        }

        public CleanupAttempt acquireCleanup() {
            synchronized (scope.monitor) {
                require(state == State.ATTACHED && outstanding.contains(this), "resource_not_attached");
                if (attempt != null && !attempt.work.isSettled()) return attempt;
                require(scope.drainWindowValidLocked(), "resource_cleanup_window_expired");
                DrainPermit previous = currentPermit;
                DrainPermit permit = new DrainPermit(this);
                currentPermit = permit;
                try {
                    ExecutionScope.WorkHandle work = scope.acceptCleanupLocked(permit);
                    permit.work = work;
                    attempt = new CleanupAttempt(work, permit);
                    scope.changedLocked();
                    return attempt;
                } catch (RuntimeException failure) {
                    currentPermit = previous;
                    throw failure;
                }
            }
        }

        /** Records a release fact from an attempt which actually entered; this never starts native work. */
        public void confirmReleased(DrainPermit permit, Object exactIdentity) {
            synchronized (scope.monitor) {
                require(permit != null && permit.registration == this && exactIdentity != null,
                        "resource_cleanup_identity_mismatch");
                if (state == State.RELEASED) {
                    require(releasedIdentity.get() == exactIdentity, "resource_cleanup_identity_mismatch");
                    return;
                }
                require(identity == exactIdentity, "resource_cleanup_identity_mismatch");
                require(state == State.ATTACHED && outstanding.contains(this) && permit.startedProofLocked(),
                        "resource_release_unproven");
                state = State.RELEASED;
                cancelSignal = null;
                outstanding.remove(this);
                identities.remove(identity, this);
                releasedIdentity = new WeakReference<>(identity);
                identity = null;
                releasedTotal++;
                scope.changedLocked();
            }
        }

        private Runnable cancelSignalLocked() {
            if (state != State.ATTACHED || cancelSignalled) return null;
            cancelSignalled = true;
            cancellationSignals++;
            return scope.reserveCancellationSignalLocked(cancelSignal);
        }
    }

    public static final class CleanupAttempt {
        private final ExecutionScope.WorkHandle work;
        private final DrainPermit permit;

        private CleanupAttempt(ExecutionScope.WorkHandle work, DrainPermit permit) {
            this.work = work;
            this.permit = permit;
        }

        public ExecutionScope.WorkHandle work() { return work; }
        public DrainPermit permit() { return permit; }
        public CompletionStage<ExecutionScope.WorkSnapshot> completion() { return work.completion(); }
    }

    /** Exact resource cleanup authority; this cannot authorize an ordinary character command. */
    public static final class DrainPermit {
        private final Registration registration;
        private ExecutionScope.WorkHandle work;

        private DrainPermit(Registration registration) { this.registration = registration; }

        ExecutionScope ownerScope() { return registration.owner().scope; }

        boolean validLocked() {
            ScopeResources resources = registration.owner();
            return registration.state == State.ATTACHED && registration.currentPermit == this
                    && resources.outstanding.contains(registration) && registration.identity != null
                    && resources.scope.drainWindowValidLocked() && (work == null || !work.isSettled());
        }

        boolean startedProofLocked() {
            return work != null && ownerScope().enteredCleanupLocked(work, this);
        }

        public boolean isValidFor(Object exactIdentity) {
            ExecutionScope scope = ownerScope();
            synchronized (scope.monitor) {
                return registration.identity == exactIdentity && validLocked();
            }
        }

        public void requireExecuting(Object exactIdentity) {
            ExecutionScope scope = ownerScope();
            synchronized (scope.monitor) {
                require(registration.identity == exactIdentity && validLocked() && work != null
                        && scope.executingBodyLocked(work), "resource_cleanup_not_executing");
            }
        }
    }

    private static void requireIdentifier(String value, String reason) {
        require(value != null && value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"), reason);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
