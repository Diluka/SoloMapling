package soloMapling.ArtificialPlayer.BotMovementSystem.NavigationSystem;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementRecording;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.server.BotTickService;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PathFinderCancellationTest {
    private static final int MAP_ID = 910000000;

    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void plainPathPropagatesCancellationWithoutStartingTheNextSegment() {
        Fixture fixture = new Fixture();
        CancellationException cancellation = new CancellationException("stopped macro");
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
             MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class)) {
            movement.when(() -> MovementCommands.executeMovement(fixture.character, fixture.firstRecording))
                    .thenThrow(cancellation);
            assertSame(cancellation, assertThrows(CancellationException.class, () ->
                    fixture.path.executePath(MAP_ID, List.of(fixture.first, fixture.next))));
            verify(fixture.next, never()).getMovementRecording();
            ticks.verify(() -> BotTickService.reportFailure(any()), never());
        }
    }

    @Test
    void awarePathPropagatesCancellationWithoutRepathOrAnotherSegment() {
        Fixture fixture = new Fixture();
        CancellationException cancellation = new CancellationException("stopped aware macro");
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
             MockedStatic<PathFinder> paths = mockStatic(PathFinder.class);
             MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class)) {
            movement.when(() -> MovementCommands.executeMovement(eq(fixture.character), eq(fixture.firstRecording),
                    any(MovementCommands.MidMovementCheck.class))).thenThrow(cancellation);
            assertSame(cancellation, assertThrows(CancellationException.class, () ->
                    fixture.aware(List.of(fixture.first, fixture.next))));
            verify(fixture.next, never()).getMovementRecording();
            paths.verify(() -> PathFinder.createPath(anyInt(), any(), any(), any()), never());
            ticks.verify(() -> BotTickService.reportFailure(any()), never());
        }
    }

    @Test
    void cancellationAtTheEndOfASegmentPreventsTheNextSegment() {
        Fixture fixture = new Fixture();
        CancellationException cancellation = new CancellationException("stopped between segments");
        AtomicBoolean cancelled = new AtomicBoolean();
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class)) {
            movement.when(() -> MovementCommands.checkMovementCancellation(fixture.character)).thenAnswer(call -> {
                if (cancelled.get()) throw cancellation;
                return null;
            });
            movement.when(() -> MovementCommands.executeMovement(fixture.character, fixture.firstRecording))
                    .thenAnswer(call -> { cancelled.set(true); return false; });
            assertSame(cancellation, assertThrows(CancellationException.class, () ->
                    fixture.path.executePath(MAP_ID, List.of(fixture.first, fixture.next))));
            verify(fixture.next, never()).getMovementRecording();
        }
    }

    @Test
    void cancelledStopReactionCannotBeReinterpretedAsANormalRepath() {
        Fixture fixture = new Fixture();
        CancellationException cancellation = new CancellationException("stopped during reaction");
        AtomicBoolean cancelled = new AtomicBoolean();
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
             MockedStatic<PathFinder> paths = mockStatic(PathFinder.class)) {
            movement.when(() -> MovementCommands.checkMovementCancellation(fixture.character)).thenAnswer(call -> {
                if (cancelled.get()) throw cancellation;
                return null;
            });
            movement.when(() -> MovementCommands.executeMovement(eq(fixture.character), eq(fixture.firstRecording),
                    any(MovementCommands.MidMovementCheck.class)))
                    .thenAnswer(call -> { cancelled.set(true); return true; });
            assertSame(cancellation, assertThrows(CancellationException.class, () ->
                    fixture.aware(List.of(fixture.first))));
            paths.verify(() -> PathFinder.createPath(anyInt(), any(), any(), any()), never());
        }
    }

    @Test
    void ordinaryStopReactionStillRepathsAndExecutesTheReplacementSegment() {
        Fixture fixture = new Fixture();
        List<String> replacement = List.of("c2-3");
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
             MockedStatic<PathFinder> paths = mockStatic(PathFinder.class)) {
            movement.when(() -> MovementCommands.executeMovement(eq(fixture.character), eq(fixture.firstRecording),
                    any(MovementCommands.MidMovementCheck.class))).thenReturn(true);
            movement.when(() -> MovementCommands.executeMovement(eq(fixture.character), eq(fixture.nextRecording),
                    any(MovementCommands.MidMovementCheck.class))).thenReturn(false);
            paths.when(() -> PathFinder.createPath(eq(MAP_ID), any(), eq(fixture.end), eq(PathFinder.PathType.RANDOM)))
                    .thenReturn(replacement);
            paths.when(() -> PathFinder.getNavElements(MAP_ID, replacement)).thenReturn(List.of(fixture.next));
            fixture.aware(List.of(fixture.first));
            paths.verify(() -> PathFinder.createPath(eq(MAP_ID), any(), eq(fixture.end), eq(PathFinder.PathType.RANDOM)), times(1));
            movement.verify(() -> MovementCommands.executeMovement(eq(fixture.character), eq(fixture.nextRecording),
                    any(MovementCommands.MidMovementCheck.class)), times(1));
        }
    }

    @Test
    void plainPathReportsAnActiveFailureAndKeepsItsExistingRecoveryContract() {
        activeFailureIsReported(false);
    }

    @Test
    void awarePathReportsAnActiveFailureAndKeepsItsExistingRecoveryContract() {
        activeFailureIsReported(true);
    }

    private static void activeFailureIsReported(boolean aware) {
        Fixture fixture = new Fixture();
        NullPointerException failure = new NullPointerException("active segment failure");
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
             MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class)) {
            if (aware) {
                movement.when(() -> MovementCommands.executeMovement(eq(fixture.character), eq(fixture.firstRecording),
                        any(MovementCommands.MidMovementCheck.class))).thenThrow(failure);
                fixture.aware(List.of(fixture.first, fixture.next));
                movement.verify(() -> MovementCommands.executeMovement(eq(fixture.character), eq(fixture.nextRecording),
                        any(MovementCommands.MidMovementCheck.class)), times(1));
            } else {
                movement.when(() -> MovementCommands.executeMovement(fixture.character, fixture.firstRecording))
                        .thenThrow(failure);
                fixture.path.executePath(MAP_ID, List.of(fixture.first, fixture.next));
                movement.verify(() -> MovementCommands.executeMovement(fixture.character, fixture.nextRecording), times(1));
            }
            ticks.verify(() -> BotTickService.reportFailure(failure), times(1));
        }
    }

    private static final class Fixture {
        final Character character = mock(Character.class);
        final Point end = new Point(20, 0);
        final MovementRecording firstRecording = new MovementRecording(MAP_ID, "c1-2", List.of());
        final MovementRecording nextRecording = new MovementRecording(MAP_ID, "c2-3", List.of());
        final Connector first = connector("c1-2", firstRecording);
        final Connector next = connector("c2-3", nextRecording);
        final PathFinder path;

        Fixture() {
            when(character.getMap()).thenReturn(mock(MapleMap.class));
            when(character.getPosition()).thenReturn(new Point(0, 0));
            path = new PathFinder(character, end);
        }

        void aware(List<NavigationElement> elements) {
            path.executePathAware(MAP_ID, elements, 300, 200, 1, 1, 1);
        }

        private static Connector connector(String name, MovementRecording recording) {
            Connector connector = mock(Connector.class);
            when(connector.getRecordingName()).thenReturn(name);
            when(connector.getMovementRecording()).thenReturn(recording);
            return connector;
        }
    }
}
