package soloMapling.game.execution;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterCommandLaneTest {
    private final List<CharacterCommandLane> lanes = new ArrayList<>();

    @BeforeEach void startWorld() { CharacterTestContext.install(); WorldEpoch.onWorldStarted(); }
    @AfterEach void stopOnlyOwnedLanes() { lanes.forEach(CharacterCommandLane::close); ExecutionScope.tryBeginWorldStop(); }

    @Test
    void cancelledQueuedEntriesKeepAllSevenOrdinarySlotsAndCleanupStillHasItsReserve() throws Exception {
        Fixture fixture = real(8,1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closeDone = new CountDownLatch(1);
        AtomicInteger bodies = new AtomicInteger(), settles = new AtomicInteger(), closes = new AtomicInteger();
        List<ExecutionScope.WorkHandle> queued = new ArrayList<>();
        ExecutionScope.WorkHandle running = fixture.scope.acceptWork();
        fixture.lane.submit(running,fixture.permit,() -> { entered.countDown(); await(release); },settles::incrementAndGet);
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            for(int i=0;i<6;i++) {
                ExecutionScope.WorkHandle work=fixture.scope.acceptWork(); queued.add(work);
                assertEquals(CharacterCommandLane.Status.ENQUEUED,fixture.lane.submit(work,fixture.permit,bodies::incrementAndGet,settles::incrementAndGet).status());
            }
            fixture.scope.requestCancel(queued.getFirst());
            assertEquals(7,fixture.lane.snapshot().ordinary());
            ExecutionScope.WorkHandle rejected=fixture.scope.acceptWork();
            assertEquals(CharacterCommandLane.Status.REJECTED,fixture.lane.submit(rejected,fixture.permit,bodies::incrementAndGet,settles::incrementAndGet).status());
            assertTrue(rejected.isSettled()); assertEquals(7,fixture.lane.snapshot().ordinary());
            Object resource=new Object();
            ScopeResources.Registration registration=fixture.scope.resources().beginRegistration("native_character");
            registration.attach(resource,() -> {});
            ScopeResources.CleanupAttempt cleanup=registration.acquireCleanup();
            fixture.scope.requestCancel(cleanup.work());
            assertEquals(CharacterCommandLane.Status.ENQUEUED,fixture.lane.submitOwnedCleanup(cleanup,() -> {
                closes.incrementAndGet(); registration.confirmReleased(cleanup.permit(),resource);
            },closeDone::countDown).status());
            assertEquals(1,fixture.lane.snapshot().cleanup()); assertEquals(0,closes.get());
        } finally { release.countDown(); }
        assertTrue(closeDone.await(5,TimeUnit.SECONDS));
        awaitIdle(fixture.lane);
        assertEquals(5,bodies.get()); assertEquals(8,settles.get()); assertEquals(1,closes.get());
        assertTrue(queued.getFirst().snapshot().queueDeparted());
        assertEquals(ExecutionScope.BodyState.NOT_STARTED,queued.getFirst().snapshot().bodyState());
        assertTrue(queued.getFirst().snapshot().skipped());
        fixture.scope.beginDrain();
        assertEquals(ExecutionScope.DrainResult.DRAINED,fixture.scope.awaitDrained(Duration.ofSeconds(1)));
    }

    @Test
    void doneCompletionViewDoesNotRetireAStillRunningBodyOrItsGrant() throws Exception {
        Fixture fixture=real(3,1);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),settled=new CountDownLatch(1);
        ExecutionScope.WorkHandle work=fixture.scope.acceptWork();
        fixture.lane.submit(work,fixture.permit,() -> {entered.countDown();await(release);},settled::countDown);
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            CompletableFuture<?> completion=work.completion().toCompletableFuture();
            assertTrue(completion.cancel(false)); assertTrue(completion.isDone());
            fixture.scope.beginDrain(); fixture.grant.revoke();
            assertEquals(1,fixture.grant.inFlight());
            assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT,fixture.grant.awaitReleased(Duration.ZERO));
            assertEquals(ExecutionScope.DrainResult.TIMED_OUT,fixture.scope.awaitDrained(Duration.ZERO));
            assertFalse(work.isSettled());
        } finally { release.countDown(); }
        assertTrue(settled.await(5,TimeUnit.SECONDS)); awaitIdle(fixture.lane);
        assertEquals(CharacterCommandLane.GrantDrain.DRAINED,fixture.grant.awaitReleased(Duration.ofSeconds(1)));
        assertEquals(ExecutionScope.DrainResult.DRAINED,fixture.scope.awaitDrained(Duration.ofSeconds(1)));
    }

    @Test
    void rejectedSlowSettlementStaysWithItsGrantWithoutExcludingOtherScopes() throws Exception {
        ManualExecutor executor=new ManualExecutor(1);
        Fixture fixture=manual(1,0,executor);
        fixture.lane.submit(fixture.scope.acceptWork(),fixture.permit,() -> {},() -> {});
        CountDownLatch hook=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutionScope other=scope();
        try(ExecutorService caller=Executors.newSingleThreadExecutor()) {
            var rejection=caller.submit(() -> fixture.lane.submit(fixture.scope.acceptWork(),fixture.permit,() -> fail("capacity"),() -> {
                hook.countDown();
                assertEquals(CharacterCommandLane.GrantDrain.SELF_WAIT,fixture.grant.awaitReleased(Duration.ZERO));
                await(release);
            }));
            assertTrue(hook.await(5,TimeUnit.SECONDS));
            try {
                fixture.grant.revoke();
                executor.runNext();
                assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT,fixture.grant.awaitReleased(Duration.ZERO));
                assertNotNull(fixture.lane.declareGrant(other)); // Caller decides whether its authority conflicts.
            } finally {release.countDown();}
            assertEquals(CharacterCommandLane.Status.REJECTED,rejection.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals(CharacterCommandLane.GrantDrain.DRAINED,fixture.grant.awaitReleased(Duration.ofSeconds(1)));
        assertNotNull(fixture.lane.declareGrant(other));
    }

    @Test
    void duplicateHandleNeverReplacesBodyOrSettlementInQueuedRunningOrTerminalStates() {
        ManualExecutor executor=new ManualExecutor(4); Fixture fixture=manual(4,1,executor);
        AtomicInteger bodies=new AtomicInteger(),settles=new AtomicInteger(),wrong=new AtomicInteger();
        ExecutionScope.WorkHandle work=fixture.scope.acceptWork();
        fixture.lane.submit(work,fixture.permit,() -> {
            bodies.incrementAndGet();
            assertEquals(CharacterCommandLane.Status.DUPLICATE,fixture.lane.submit(work,fixture.permit,wrong::incrementAndGet,wrong::incrementAndGet).status());
        },settles::incrementAndGet);
        assertEquals(CharacterCommandLane.Status.DUPLICATE,fixture.lane.submit(work,fixture.permit,wrong::incrementAndGet,wrong::incrementAndGet).status());
        executor.runNext();
        assertEquals(CharacterCommandLane.Status.DUPLICATE,fixture.lane.submit(work,fixture.permit,wrong::incrementAndGet,wrong::incrementAndGet).status());
        fixture.lane.submit(fixture.scope.acceptWork(),fixture.permit,bodies::incrementAndGet,settles::incrementAndGet);executor.runNext();
        assertEquals(2,bodies.get());assertEquals(2,settles.get());assertEquals(0,wrong.get());
    }

    @Test
    void settlementFailureReturnsSlotExactlyOnceButDoesNotClaimScopeDrainComplete() {
        ManualExecutor executor=new ManualExecutor(2);Fixture fixture=manual(2,1,executor);
        AtomicInteger hooks=new AtomicInteger();ExecutionScope.WorkHandle work=fixture.scope.acceptWork();
        fixture.lane.submit(work,fixture.permit,() -> {},() -> {hooks.incrementAndGet();throw new IllegalStateException("hook_failed");});
        assertThrows(IllegalStateException.class,executor::runNext);
        fixture.grant.revoke(); fixture.scope.beginDrain();
        assertEquals(0,fixture.lane.snapshot().pending());assertEquals(0,fixture.grant.inFlight());
        assertEquals(ExecutionScope.DrainResult.UNVERIFIED,fixture.scope.awaitDrained(Duration.ZERO));
        assertTrue(work.snapshot().settlementFailed());
        fixture.lane.submit(work,fixture.permit,() -> fail("duplicate"),hooks::incrementAndGet);
        assertEquals(1,hooks.get());
    }

    @Test
    void cleanupIsSingleflightReserveBoundedAndFailureRequiresExplicitVerificationAttempt() {
        ManualExecutor executor=new ManualExecutor(3); Fixture fixture=manual(3,1,executor);
        Object first=new Object(),second=new Object();
        ScopeResources.Registration a=fixture.scope.resources().beginRegistration("first");a.attach(first,() -> {});
        ScopeResources.Registration b=fixture.scope.resources().beginRegistration("second");b.attach(second,() -> {});
        ScopeResources.CleanupAttempt one=a.acquireCleanup(),two=b.acquireCleanup();
        AtomicInteger cleanupCalls=new AtomicInteger(),hooks=new AtomicInteger();AtomicBoolean released=new AtomicBoolean();
        fixture.lane.submitOwnedCleanup(one,() -> {cleanupCalls.incrementAndGet();released.set(true);throw new IllegalStateException("after_native_release");},hooks::incrementAndGet);
        assertSame(one,a.acquireCleanup());
        assertEquals(CharacterCommandLane.Status.DUPLICATE,fixture.lane.submitOwnedCleanup(one,() -> fail("duplicate"),() -> fail("duplicate hook")).status());
        assertEquals(CharacterCommandLane.Status.CLEANUP_PENDING,fixture.lane.submitOwnedCleanup(two,() -> fail("reserve"),hooks::incrementAndGet).status());
        assertEquals(2,fixture.scope.snapshot().outstandingResources());
        assertThrows(IllegalStateException.class,executor::runNext);
        assertEquals(0,executor.size());assertEquals(1,cleanupCalls.get());
        ScopeResources.CleanupAttempt retry=a.acquireCleanup();assertNotSame(one,retry);
        fixture.lane.submitOwnedCleanup(retry,() -> {assertTrue(released.get());a.confirmReleased(retry.permit(),first);},hooks::incrementAndGet);executor.runNext();
        ScopeResources.CleanupAttempt next=b.acquireCleanup();
        fixture.lane.submitOwnedCleanup(next,() -> b.confirmReleased(next.permit(),second),hooks::incrementAndGet);executor.runNext();
        assertEquals(1,cleanupCalls.get());assertEquals(4,hooks.get());
        fixture.scope.beginDrain();assertEquals(ExecutionScope.DrainResult.DRAINED,fixture.scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void cleanupSlowHookKeepsItsScopeGrantPendingWithoutBanningOtherOwners() throws Exception {
        Fixture fixture=real(3,1);Object resource=new Object();
        ScopeResources.Registration reg=fixture.scope.resources().beginRegistration("resource");reg.attach(resource,() -> {});
        ScopeResources.CleanupAttempt attempt=reg.acquireCleanup();CountDownLatch hook=new CountDownLatch(1),release=new CountDownLatch(1);
        fixture.lane.submitOwnedCleanup(attempt,() -> reg.confirmReleased(attempt.permit(),resource),() -> {hook.countDown();await(release);});
        assertTrue(hook.await(5,TimeUnit.SECONDS));
        fixture.grant.revoke();
        try {
            assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT,fixture.grant.awaitReleased(Duration.ZERO));
            assertNotNull(fixture.lane.declareGrant(scope()));
            assertFalse(attempt.work().isSettled());
            assertEquals(CharacterCommandLane.Status.DUPLICATE,fixture.lane.submitOwnedCleanup(attempt,
                    () -> fail("released resource must not close again"),() -> fail("duplicate settle")).status());
        } finally {release.countDown();}
        awaitIdle(fixture.lane);
        assertEquals(CharacterCommandLane.GrantDrain.DRAINED,fixture.grant.awaitReleased(Duration.ofSeconds(1)));
    }

    @Test
    void initializationUsesTheSameFifoAndItsPermitCannotBecomeAnOrdinaryWriteBypass() {
        ManualExecutor executor=new ManualExecutor(4);CharacterCommandLane lane=lane(4,1,executor);ExecutionScope scope=scope();
        var admission=lane.admissionPermit(scope);List<String> order=new ArrayList<>();
        ExecutionScope.WorkHandle open=scope.acceptWork();
        lane.submitInitialization(open,admission,() -> order.add("open"),() -> order.add("open_settled"));
        assertThrows(IllegalStateException.class,() -> lane.admissionPermit(scope));
        assertEquals(CharacterCommandLane.Status.REJECTED,lane.submitInitialization(scope.acceptWork(),admission,() -> fail("second OPEN"),() -> {}).status());
        executor.runNext();
        var grant=lane.declareGrant(scope);var permit=lane.bindGrantedPermit(scope,ref(scope),grant,() -> {});
        lane.submit(scope.acceptWork(),permit,() -> order.add("action"),() -> {});
        assertEquals(CharacterCommandLane.Status.REJECTED,lane.submitInitialization(scope.acceptWork(),admission,() -> fail("late initialization"),() -> {}).status());
        executor.runNext();assertEquals(List.of("open","open_settled","action"),order);
    }

    @Test
    void callerMustWaitForInitializationBeforeUsingAConflictingGrant() throws Exception {
        ExecutionScope first=scope(),second=scope();CharacterCommandLane lane=realLane(3,1);
        var admission=lane.admissionPermit(first);var grant=lane.declareGrant(first);
        CountDownLatch entered=new CountDownLatch(1),bodyExit=new CountDownLatch(1),hook=new CountDownLatch(1),hookExit=new CountDownLatch(1);
        lane.submitInitialization(first.acceptWork(),admission,() -> {entered.countDown();await(bodyExit);},() -> {hook.countDown();await(hookExit);});
        assertTrue(entered.await(5,TimeUnit.SECONDS));grant.revoke();
        try {
            assertNotNull(lane.declareGrant(second));
            assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT,grant.awaitReleased(Duration.ZERO));
            bodyExit.countDown();assertTrue(hook.await(5,TimeUnit.SECONDS));
            assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT,grant.awaitReleased(Duration.ZERO));
        } finally {bodyExit.countDown();hookExit.countDown();}
        awaitIdle(lane);assertNotNull(lane.declareGrant(second));
    }

    @Test
    void worldStopAllowsOnlyTheExactOldCleanupWindowAndANewWorldCannotEnterIt() {
        ManualExecutor executor=new ManualExecutor(3);Fixture fixture=manual(3,1,executor);
        Object resource=new Object();ScopeResources.Registration reg=fixture.scope.resources().beginRegistration("native_character");reg.attach(resource,() -> {});
        ExecutionScope.WorkHandle oldAction=fixture.scope.acceptWork();
        var ticket=ExecutionScope.tryBeginWorldStop().orElseThrow();fixture.scope.joinWorldStop(ticket);
        ScopeResources.CleanupAttempt cleanup=reg.acquireCleanup();AtomicInteger calls=new AtomicInteger();
        fixture.lane.submit(oldAction,fixture.permit,() -> fail("old ordinary epoch"),() -> {});
        fixture.lane.submitOwnedCleanup(cleanup,() -> {calls.incrementAndGet();reg.confirmReleased(cleanup.permit(),resource);},() -> {});
        executor.runNext();executor.runNext();assertEquals(1,calls.get());
        assertEquals(ExecutionScope.BodyState.NOT_STARTED,oldAction.snapshot().bodyState());
        WorldEpoch.onWorldStarted();
        assertThrows(IllegalArgumentException.class,() -> fixture.lane.admissionPermit(scope()));
    }

    @Test
    void aNewWorldBeforeCleanupStartsKeepsTheResourcePendingAndSkipsNativeBody() {
        ManualExecutor executor=new ManualExecutor(3);Fixture fixture=manual(3,1,executor);
        Object resource=new Object();ScopeResources.Registration reg=fixture.scope.resources().beginRegistration("resource");reg.attach(resource,() -> {});
        var ticket=ExecutionScope.tryBeginWorldStop().orElseThrow();fixture.scope.joinWorldStop(ticket);var attempt=reg.acquireCleanup();
        fixture.lane.submitOwnedCleanup(attempt,() -> fail("new world must not be cleaned"),() -> {});
        WorldEpoch.onWorldStarted();executor.runNext();
        assertTrue(attempt.work().snapshot().skipped());assertEquals(1,fixture.scope.snapshot().outstandingResources());
        assertEquals(ExecutionScope.DrainResult.TIMED_OUT,fixture.scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void twoRolesRunIndependentlyAndClosingABorrowedLaneNeverShutsItsPool() throws Exception {
        Fixture a=real(3,1),b=real(3,1);CountDownLatch aEntered=new CountDownLatch(1),aExit=new CountDownLatch(1),bDone=new CountDownLatch(1);
        a.lane.submit(a.scope.acceptWork(),a.permit,() -> {aEntered.countDown();await(aExit);},() -> {});
        assertTrue(aEntered.await(5,TimeUnit.SECONDS));
        try {b.lane.submit(b.scope.acceptWork(),b.permit,bDone::countDown,() -> {});assertTrue(bDone.await(2,TimeUnit.SECONDS));}
        finally {aExit.countDown();}
        ThreadPoolExecutor pool=pool(3);CharacterCommandLane borrowed=lane(3,1,pool);
        var first=borrowed.declareGrant(scope());var second=borrowed.declareGrant(scope());borrowed.close();
        assertTrue(first.revoked());assertTrue(second.revoked());
        try {assertFalse(pool.isShutdown());CompletableFuture<Void> external=new CompletableFuture<>();pool.execute(() -> external.complete(null));external.get(2,TimeUnit.SECONDS);}
        finally {pool.shutdown();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test
    void rejectedExecutorSettlesExactlyOnceAndDoesNotLeaveAnOrphanCarrier() {
        ThreadPoolExecutor executor=pool(2);executor.shutdown();Fixture fixture=manual(2,1,executor);
        AtomicInteger hooks=new AtomicInteger();ExecutionScope.WorkHandle work=fixture.scope.acceptWork();
        var result=fixture.lane.submit(work,fixture.permit,() -> fail("executor rejected"),hooks::incrementAndGet);
        assertEquals(CharacterCommandLane.Status.REJECTED,result.status());assertEquals(CharacterCommandLane.Rejection.EXECUTOR,result.rejection());
        assertEquals(1,hooks.get());assertTrue(work.isSettled());assertEquals(0,fixture.lane.snapshot().pending());
        fixture.lane.submit(work,fixture.permit,() -> fail("duplicate"),hooks::incrementAndGet);assertEquals(1,hooks.get());
    }

    @Test
    void parallelUnboundedAndCallerRunsExecutorsCannotPretendToBeNativeSerialLanes() {
        List<ThreadPoolExecutor> invalid=List.of(new ThreadPoolExecutor(2,2,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8)),
                new ThreadPoolExecutor(1,1,1,TimeUnit.SECONDS,new LinkedBlockingQueue<>()),
                new ThreadPoolExecutor(1,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),new ThreadPoolExecutor.CallerRunsPolicy()));
        try {for(var executor:invalid)assertThrows(IllegalArgumentException.class,() -> new CharacterCommandLane(8,1,executor));}
        finally {invalid.forEach(ExecutorService::shutdown);}
    }

    @Test
    void factualCompletionAndScopeDrainedArePublishedOnlyAfterLaneTailAndLeaseExit() throws Exception {
        ManualExecutor executor=new ManualExecutor(3);Fixture fixture=manual(3,1,executor);
        ExecutionScope.WorkHandle work=fixture.scope.acceptWork();
        var observed=work.completion().thenAccept(snapshot -> {
            assertTrue(snapshot.carrierSettled());
            assertEquals(0,fixture.lane.snapshot().pending());
            assertEquals(0,fixture.grant.inFlight());
            assertEquals(CharacterCommandLane.GrantDrain.DRAINED,fixture.grant.awaitReleased(Duration.ZERO));
            assertEquals(ExecutionScope.DrainResult.DRAINED,fixture.scope.awaitDrained(Duration.ZERO));
        });
        fixture.lane.submit(work,fixture.permit,() -> {fixture.grant.revoke();fixture.scope.beginDrain();},() -> {});
        executor.runNext();observed.toCompletableFuture().get(1,TimeUnit.SECONDS);
    }

    @Test
    void anOldGrantWaiterIsNotMadeToWaitForItsSuccessorsNewBody() throws Exception {
        Fixture fixture=real(3,1);Object resource=new Object();
        ScopeResources.Registration reg=fixture.scope.resources().beginRegistration("handoff");reg.attach(resource,() -> {});
        var attempt=reg.acquireCleanup();CountDownLatch entered=new CountDownLatch(1),exit=new CountDownLatch(1),nextEntered=new CountDownLatch(1),nextExit=new CountDownLatch(1);
        fixture.lane.submitOwnedCleanup(attempt,() -> {entered.countDown();await(exit);reg.confirmReleased(attempt.permit(),resource);},() -> {});
        assertTrue(entered.await(5,TimeUnit.SECONDS));fixture.grant.revoke();
        CompletableFuture<CharacterCommandLane.GrantDrain> oldResult=new CompletableFuture<>();
        Thread waiter=Thread.ofPlatform().daemon(true).start(() -> oldResult.complete(fixture.grant.awaitReleased(Duration.ofSeconds(5))));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(waiter.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<until)Thread.sleep(1);
        assertEquals(Thread.State.TIMED_WAITING,waiter.getState());
        var field=CharacterCommandLane.Grant.class.getDeclaredField("monitor");field.setAccessible(true);Object grantMonitor=field.get(fixture.grant);
        try {
            // Hold only the real waiter's monitor, forcing its next observation to occur after handoff.
            synchronized(grantMonitor) {
                exit.countDown();awaitIdle(fixture.lane);
                ExecutionScope next=scope();var granted=fixture.lane.declareGrant(next);
                var permit=fixture.lane.bindGrantedPermit(next,fixture.permit.characterRef(),granted,() -> {});
                fixture.lane.submit(next.acceptWork(),permit,() -> {nextEntered.countDown();await(nextExit);},() -> {});
            }
            assertTrue(nextEntered.await(5,TimeUnit.SECONDS));
            assertEquals(CharacterCommandLane.GrantDrain.DRAINED,oldResult.get(1,TimeUnit.SECONDS));
        } finally {exit.countDown();nextExit.countDown();}
    }

    @Test
    void nullCharacterCannotMintControlAndCleanupWorkCannotUseTheOrdinaryEntry() {
        ManualExecutor executor=new ManualExecutor(3);Fixture fixture=manual(3,1,executor);
        GameContext.CharacterRef empty=new GameContext.CharacterRef(fixture.scope.acceptedEpoch(),null,null,null,null);
        assertThrows(IllegalArgumentException.class,() -> fixture.lane.bindGrantedPermit(fixture.scope,empty,fixture.grant,() -> {}));
        Object identity=new Object();var registration=fixture.scope.resources().beginRegistration("typed_cleanup");registration.attach(identity,() -> {});
        var attempt=registration.acquireCleanup();AtomicInteger hooks=new AtomicInteger();
        var invalid=fixture.lane.submit(attempt.work(),fixture.permit,() -> fail("cleanup authority is not ordinary entry"),hooks::incrementAndGet);
        assertEquals(CharacterCommandLane.Status.REJECTED,invalid.status());assertEquals(CharacterCommandLane.Rejection.PERMIT,invalid.rejection());
        assertEquals(1,hooks.get());assertTrue(attempt.work().isSettled());assertEquals(1,fixture.scope.snapshot().outstandingResources());
        var retry=registration.acquireCleanup();fixture.lane.submitOwnedCleanup(retry,() -> registration.confirmReleased(retry.permit(),identity),() -> {});executor.runNext();
        fixture.scope.beginDrain();assertEquals(ExecutionScope.DrainResult.DRAINED,fixture.scope.awaitDrained(Duration.ZERO));
    }

    @Test
    void redeclaringTheSameScopeDoesNotMakeOldGrantWaitForItsSuccessorsBody() throws Exception {
        Fixture fixture=real(4,1);CountDownLatch oldEntered=new CountDownLatch(1),oldExit=new CountDownLatch(1),nextEntered=new CountDownLatch(1),nextExit=new CountDownLatch(1);
        fixture.lane.submit(fixture.scope.acceptWork(),fixture.permit,() -> {oldEntered.countDown();await(oldExit);},() -> {});
        assertTrue(oldEntered.await(5,TimeUnit.SECONDS));fixture.grant.revoke();
        var successor=fixture.lane.declareGrant(fixture.scope);assertNotSame(fixture.grant,successor);
        var nextPermit=fixture.lane.bindGrantedPermit(fixture.scope,fixture.permit.characterRef(),successor,() -> {});
        fixture.lane.submit(fixture.scope.acceptWork(),nextPermit,() -> {nextEntered.countDown();await(nextExit);},() -> {});
        try {
            assertEquals(CharacterCommandLane.GrantDrain.TIMED_OUT,fixture.grant.awaitReleased(Duration.ZERO));
            oldExit.countDown();assertTrue(nextEntered.await(5,TimeUnit.SECONDS));
            assertEquals(CharacterCommandLane.GrantDrain.DRAINED,fixture.grant.awaitReleased(Duration.ofSeconds(1)));
            assertEquals(1,successor.inFlight());assertFalse(successor.revoked());
        } finally {oldExit.countDown();nextExit.countDown();}
    }

    @Test
    void aPostRevocationRejectedHookDoesNotReopenOldGrantDebtButStillBlocksItsScopeDrain() throws Exception {
        ManualExecutor executor=new ManualExecutor(1);Fixture fixture=manual(1,0,executor);
        fixture.grant.revoke();assertEquals(CharacterCommandLane.GrantDrain.DRAINED,fixture.grant.awaitReleased(Duration.ZERO));
        ExecutionScope other=scope();var otherGrant=fixture.lane.declareGrant(other);
        var otherPermit=fixture.lane.bindGrantedPermit(other,fixture.permit.characterRef(),otherGrant,() -> {});
        AtomicInteger body=new AtomicInteger();fixture.lane.submit(other.acceptWork(),otherPermit,body::incrementAndGet,() -> {});
        CountDownLatch hook=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ExecutorService caller=Executors.newSingleThreadExecutor()) {
            var rejected=caller.submit(() -> fixture.lane.submit(fixture.scope.acceptWork(),fixture.permit,() -> fail("revoked authority"),() -> {hook.countDown();await(release);}));
            assertTrue(hook.await(5,TimeUnit.SECONDS));
            try {
                fixture.scope.beginDrain();
                assertEquals(CharacterCommandLane.GrantDrain.DRAINED,fixture.grant.awaitReleased(Duration.ZERO));
                assertEquals(ExecutionScope.DrainResult.TIMED_OUT,fixture.scope.awaitDrained(Duration.ZERO));
                assertFalse(otherGrant.revoked());
            } finally {release.countDown();}
            assertEquals(CharacterCommandLane.Status.REJECTED,rejected.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals(ExecutionScope.DrainResult.DRAINED,fixture.scope.awaitDrained(Duration.ZERO));
        executor.runNext();assertEquals(1,body.get());
    }

    @Test
    void newRequestsFromARevokedGrantCannotOccupyTheOtherAuthorizedModulesCapacity() {
        ManualExecutor executor=new ManualExecutor(3);Fixture fixture=manual(3,1,executor);
        fixture.grant.revoke();ExecutionScope other=scope();var granted=fixture.lane.declareGrant(other);
        var permit=fixture.lane.bindGrantedPermit(other,fixture.permit.characterRef(),granted,() -> {});
        AtomicInteger settled=new AtomicInteger(),bodies=new AtomicInteger();
        var rejected=fixture.lane.submit(fixture.scope.acceptWork(),fixture.permit,() -> fail("old grant"),settled::incrementAndGet);
        assertEquals(CharacterCommandLane.Status.REJECTED,rejected.status());assertEquals(CharacterCommandLane.Rejection.PERMIT,rejected.rejection());
        assertEquals(1,settled.get());assertEquals(0,executor.size());assertEquals(0,fixture.lane.snapshot().ordinary());
        assertEquals(CharacterCommandLane.Status.ENQUEUED,fixture.lane.submit(other.acceptWork(),permit,bodies::incrementAndGet,() -> {}).status());
        assertEquals(CharacterCommandLane.Status.ENQUEUED,fixture.lane.submit(other.acceptWork(),permit,bodies::incrementAndGet,() -> {}).status());
        executor.runNext();executor.runNext();assertEquals(2,bodies.get());assertFalse(granted.revoked());
    }

    private Fixture real(int capacity,int reserve) {return fixture(realLane(capacity,reserve),scope());}
    private Fixture manual(int capacity,int reserve,ExecutorService executor) {return fixture(lane(capacity,reserve,executor),scope());}
    private Fixture fixture(CharacterCommandLane lane,ExecutionScope scope) {
        var grant=lane.declareGrant(scope);var ref=ref(scope);var permit=lane.bindGrantedPermit(scope,ref,grant,() -> {});
        return new Fixture(scope,lane,grant,permit);
    }
    private CharacterCommandLane lane(int capacity,int reserve,ExecutorService executor) {var lane=new CharacterCommandLane(capacity,reserve,executor);lanes.add(lane);return lane;}
    private CharacterCommandLane realLane(int capacity,int reserve) {var lane=new CharacterCommandLane(capacity,reserve,Thread.ofPlatform().daemon(true).factory());lanes.add(lane);return lane;}
    private static ThreadPoolExecutor pool(int capacity) {return new ThreadPoolExecutor(1,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity));}
    private static ExecutionScope scope() {return new ExecutionScope(WorldEpoch.snapshot().token(),new ExecutionScope.Limits(128,16,16,16));}
    private static GameContext.CharacterRef ref(ExecutionScope scope) {return new GameContext.CharacterRef(scope.acceptedEpoch(),mock(Character.class),mock(Client.class),mock(World.class),mock(Channel.class));}
    private record Fixture(ExecutionScope scope,CharacterCommandLane lane,CharacterCommandLane.Grant grant,CharacterCommandLane.ControlPermit permit) {}
    private static void await(CountDownLatch latch) {try{assertTrue(latch.await(5,TimeUnit.SECONDS),"lane_latch_timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
    private static void awaitIdle(CharacterCommandLane lane) throws InterruptedException {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(lane.snapshot().pending()!=0&&System.nanoTime()<deadline)Thread.sleep(1);assertEquals(0,lane.snapshot().pending());}

    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        private final int capacity;private final ArrayBlockingQueue<Runnable> queue;private boolean shutdown,running;
        ManualExecutor(int capacity){this.capacity=capacity;queue=new ArrayBlockingQueue<>(capacity);}
        @Override public int commandQueueCapacity(){return capacity;}
        @Override public void execute(Runnable command){if(shutdown||!queue.offer(command))throw new RejectedExecutionException("manual_full");}
        int size(){return queue.size();}
        void runNext(){if(running)throw new IllegalStateException("reentrant_manual_executor");Runnable task=queue.remove();running=true;try{task.run();}finally{running=false;}}
        @Override public void shutdown(){shutdown=true;}
        @Override public List<Runnable> shutdownNow(){throw new UnsupportedOperationException("do_not_discard_carriers");}
        @Override public boolean isShutdown(){return shutdown;}
        @Override public boolean isTerminated(){return shutdown&&queue.isEmpty()&&!running;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
