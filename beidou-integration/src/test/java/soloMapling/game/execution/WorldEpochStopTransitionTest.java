package soloMapling.game.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.game.context.WorldEpoch;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Lifecycle transitions use only the shared in-memory epoch; no native server or datasource. */
class WorldEpochStopTransitionTest {
    @BeforeEach
    void startAnIndependentWorldEpoch() {
        WorldEpoch.onWorldStarted();
    }

    @AfterEach
    void leaveTheWorldInactive() {
        WorldEpoch.onWorldStopping();
    }

    @Test
    void activeStopCapturesItsExactSourceAndPublishesOneNewInactiveState() {
        WorldEpoch.State source = WorldEpoch.snapshot();

        var transition = WorldEpoch.stopTransition();

        assertTrue(source.active());
        assertTrue(transition.transitioned());
        assertSame(source, transition.source());
        assertSame(transition.stopped(), WorldEpoch.snapshot());
        assertFalse(transition.stopped().active());
        assertNotSame(source, transition.stopped());
        assertNotSame(source.token(), transition.stopped().token());
        assertNotEquals(source.token().value(), transition.stopped().token().value());
        assertSame(source.token(), transition.source().token());
    }

    @Test
    void repeatedStopDoesNotInventAnotherStoppedStateOrToken() {
        var first = WorldEpoch.stopTransition();
        WorldEpoch.State stopped = first.stopped();

        for (int attempt = 0; attempt < 3; attempt++) {
            var duplicate = WorldEpoch.stopTransition();
            assertFalse(duplicate.transitioned());
            assertSame(stopped, duplicate.source());
            assertSame(stopped, duplicate.stopped());
            assertSame(stopped, WorldEpoch.snapshot());
            assertSame(stopped.token(), duplicate.stopped().token());
        }
    }

    @Test
    void anInactiveStateCreatedByTheFacadeDoesNotInventAnActiveTransitionSource() {
        WorldEpoch.onWorldStopping();
        WorldEpoch.State stopped = WorldEpoch.snapshot();

        var unchanged = WorldEpoch.stopTransition();

        assertFalse(stopped.active());
        assertSame(stopped, WorldEpoch.snapshot());
        assertFalse(unchanged.transitioned());
        assertSame(stopped, unchanged.source());
        assertSame(stopped, unchanged.stopped());
    }

    @Test
    void concurrentStopsHaveOneWinnerAndEveryLoserObservesItsExactStoppedState() throws Exception {
        WorldEpoch.State source = WorldEpoch.snapshot();
        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch release = new CountDownLatch(1);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var futures = IntStream.range(0, count).mapToObj(index -> workers.submit(() -> {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS), "all stop callers must be released");
                return WorldEpoch.stopTransition();
            })).toList();
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS), "all stop callers must reach the barrier");
                release.countDown();
                var transitions = futures.stream()
                        .map(future -> assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS)))
                        .toList();
                var winners = transitions.stream().filter(transition -> transition.transitioned()).toList();
                assertEquals(1, winners.size());
                var winner = winners.getFirst();
                assertSame(source, winner.source());
                assertSame(winner.stopped(), WorldEpoch.snapshot());
                assertNotSame(source.token(), winner.stopped().token());
                for (var transition : transitions) {
                    assertSame(winner.stopped(), transition.stopped());
                    assertFalse(transition.stopped().active());
                    if (!transition.transitioned()) assertSame(winner.stopped(), transition.source());
                }
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "stop callers must actually exit");
        }
    }

    @Test
    void worldStopTicketBindsTheExactTransitionAndKnownDuplicateStopsReuseIt() {
        WorldEpoch.State source = WorldEpoch.snapshot();

        ExecutionScope.WorldStopTicket ticket = ExecutionScope.beginWorldStop();
        WorldEpoch.State stopped = WorldEpoch.snapshot();

        assertFalse(stopped.active());
        assertSame(source.token(), ticket.sourceEpoch());
        assertSame(stopped.token(), ticket.stoppedEpoch());
        assertNotSame(ticket.sourceEpoch(), ticket.stoppedEpoch());
        assertSame(ticket, ExecutionScope.tryBeginWorldStop().orElseThrow());
        assertSame(ticket, ExecutionScope.beginWorldStop());
        assertSame(stopped, WorldEpoch.snapshot());
        assertTrue(Arrays.stream(ExecutionScope.WorldStopTicket.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test
    void unknownInactiveWorldCannotMintATicketOrChangeItsEpoch() {
        WorldEpoch.onWorldStopping();
        WorldEpoch.State unknownStopped = WorldEpoch.snapshot();

        assertTrue(ExecutionScope.tryBeginWorldStop().isEmpty());
        assertInvalid("unknown_world_stop", ExecutionScope::beginWorldStop);

        assertSame(unknownStopped, WorldEpoch.snapshot());
        assertSame(unknownStopped.token(), WorldEpoch.snapshot().token());
    }

    @Test
    void directCasTransitionDoesNotBecomeAScopeTicketWithoutItsRecordedSource() {
        var directStop = WorldEpoch.stopTransition();

        assertTrue(directStop.transitioned());
        assertTrue(ExecutionScope.tryBeginWorldStop().isEmpty());
        assertInvalid("unknown_world_stop", ExecutionScope::beginWorldStop);
        assertSame(directStop.stopped(), WorldEpoch.snapshot());
    }

    @Test
    void independentlyOwnedScopesFromTheSameSourceCanJoinTheSameWorldStop() {
        WorldEpoch.Token source = WorldEpoch.snapshot().token();
        Object runtime = new Object();
        ExecutionScope first = scope(source, new ExecutionScope.OwnerStamp(runtime, new Object()));
        ExecutionScope second = scope(source, new ExecutionScope.OwnerStamp(runtime, new Object()));
        ExecutionScope.WorldStopTicket ticket = ExecutionScope.beginWorldStop();

        assertDoesNotThrow(() -> first.joinWorldStop(ticket));
        assertDoesNotThrow(() -> second.joinWorldStop(ticket));

        assertSame(source, first.acceptedEpoch());
        assertSame(source, second.acceptedEpoch());
        assertEquals(ExecutionScope.State.CLOSED, first.snapshot().state());
        assertEquals(ExecutionScope.State.CLOSED, second.snapshot().state());
        assertTrue(first.snapshot().unloadComplete());
        assertTrue(second.snapshot().unloadComplete());
    }

    @Test
    void identicalOwnerStampDoesNotLetAnotherWorldSourceJoinTheStop() {
        ExecutionScope.OwnerStamp owner = new ExecutionScope.OwnerStamp(new Object(), new Object());
        ExecutionScope old = scope(WorldEpoch.snapshot().token(), owner);
        WorldEpoch.onWorldStarted();
        ExecutionScope current = scope(WorldEpoch.snapshot().token(), owner);
        ExecutionScope.WorldStopTicket ticket = ExecutionScope.beginWorldStop();

        assertInvalid("foreign_world_stop", () -> old.joinWorldStop(ticket));
        assertDoesNotThrow(() -> current.joinWorldStop(ticket));

        assertNotSame(old.acceptedEpoch(), ticket.sourceEpoch());
        assertSame(current.acceptedEpoch(), ticket.sourceEpoch());
        assertEquals(ExecutionScope.State.CLOSED, current.snapshot().state());
    }

    @Test
    void aNewStartInvalidatesTheOldTicketAndANewStopKeepsItsOwnSource() {
        ExecutionScope.OwnerStamp owner = new ExecutionScope.OwnerStamp(new Object(), new Object());
        ExecutionScope old = scope(WorldEpoch.snapshot().token(), owner);
        ExecutionScope.WorldStopTicket oldTicket = ExecutionScope.beginWorldStop();
        WorldEpoch.onWorldStarted();
        WorldEpoch.State restarted = WorldEpoch.snapshot();
        ExecutionScope current = scope(restarted.token(), owner);

        assertInvalid("foreign_world_stop", () -> old.joinWorldStop(oldTicket));
        assertInvalid("foreign_world_stop", () -> current.joinWorldStop(oldTicket));
        assertSame(restarted, WorldEpoch.snapshot());
        assertTrue(current.isActive());

        ExecutionScope.WorldStopTicket currentTicket = ExecutionScope.beginWorldStop();
        assertNotSame(oldTicket, currentTicket);
        assertSame(restarted.token(), currentTicket.sourceEpoch());
        assertSame(WorldEpoch.snapshot().token(), currentTicket.stoppedEpoch());
        assertNotSame(oldTicket.stoppedEpoch(), currentTicket.stoppedEpoch());
        assertInvalid("foreign_world_stop", () -> old.joinWorldStop(oldTicket));
        assertInvalid("foreign_world_stop", () -> old.joinWorldStop(currentTicket));
        assertDoesNotThrow(() -> current.joinWorldStop(currentTicket));
    }

    @Test
    void aPriorKnownStopDoesNotAuthorizeANewUnknownInactiveEpoch() {
        ExecutionScope.WorldStopTicket known = ExecutionScope.beginWorldStop();
        WorldEpoch.onWorldStarted();
        WorldEpoch.onWorldStopping();
        WorldEpoch.State unknownStopped = WorldEpoch.snapshot();

        assertNotSame(known.stoppedEpoch(), unknownStopped.token());
        assertTrue(ExecutionScope.tryBeginWorldStop().isEmpty());
        assertInvalid("unknown_world_stop", ExecutionScope::beginWorldStop);
        assertSame(unknownStopped, WorldEpoch.snapshot());
    }

    private ExecutionScope scope(WorldEpoch.Token epoch, ExecutionScope.OwnerStamp owner) {
        return new ExecutionScope(epoch, owner, new ExecutionScope.Limits(4, 2, 4, 1));
    }

    private void assertInvalid(String reason, Runnable operation) {
        ExecutionScope.Invalid failure = assertThrows(ExecutionScope.Invalid.class, operation::run);
        assertEquals(reason, failure.reason());
        assertEquals(reason, failure.getMessage());
    }
}
