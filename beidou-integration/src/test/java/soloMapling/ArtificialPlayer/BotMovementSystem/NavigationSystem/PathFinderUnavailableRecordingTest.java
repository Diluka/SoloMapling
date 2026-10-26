package soloMapling.ArtificialPlayer.BotMovementSystem.NavigationSystem;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotMovementSystem.InPacketReader;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementPacket;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementRecording;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementRecordingRaw;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.TimeCoordinatePair;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.server.BotTickService;

import java.awt.Point;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PathFinderUnavailableRecordingTest {
    private static final int MAP_ID = 200080101;
    private static final String RECORDING = "m1";
    private static final Point START = new Point(0, 0);
    private static final Point END = new Point(100, 0);

    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void missingStartPointIsAnUnavailableRecording() {
        try (MockedStatic<InPacketReader> packets = packets(raw(10, 20), recording(10, 20))) {
            assertTrue(PathFinder.getPathBetweenTwoPointsInMainArea(
                    MAP_ID, RECORDING, new Point(-200, 0), END).isEmpty());
        }
    }

    @Test
    void missingEndPointIsAnUnavailableRecording() {
        try (MockedStatic<InPacketReader> packets = packets(raw(10, 20), recording(10, 20))) {
            assertTrue(PathFinder.getPathBetweenTwoPointsInMainArea(
                    MAP_ID, RECORDING, START, new Point(400, 0)).isEmpty());
        }
    }

    @Test
    void pointsWithoutAChronologicalTimePairAreUnavailable() {
        try (MockedStatic<InPacketReader> packets = packets(raw(30, 20), recording(20, 30))) {
            assertTrue(PathFinder.getPathBetweenTwoPointsInMainArea(MAP_ID, RECORDING, START, END).isEmpty());
        }
    }

    @Test
    void emptyTrimIsUnavailableInsteadOfACompletedEmptyMovement() {
        try (MockedStatic<InPacketReader> packets = packets(raw(10, 20), recording(90))) {
            assertTrue(PathFinder.getPathBetweenTwoPointsInMainArea(MAP_ID, RECORDING, START, END).isEmpty());
        }
    }

    @Test
    void legalTimePairReturnsTheActualTrimmedPackets() {
        MovementRecording original = recording(0, 10, 15, 20, 30);
        try (MockedStatic<InPacketReader> packets = packets(raw(10, 20), original)) {
            MovementRecording cropped = PathFinder.getPathBetweenTwoPointsInMainArea(
                    MAP_ID, RECORDING, START, END).orElseThrow();
            assertEquals(List.of(10L, 15L, 20L), cropped.getMovementPacketList().stream()
                    .map(MovementPacket::getTimestamp).toList());
            assertSame(original.getMovementPacketList().get(1), cropped.getMovementPacketList().getFirst());
            assertSame(original.getMovementPacketList().get(3), cropped.getMovementPacketList().getLast());
        }
    }

    @Test
    void resourceFailuresRemainVisibleToTheCaller() {
        IllegalStateException failure = new IllegalStateException("recording resource failed");
        try (MockedStatic<InPacketReader> packets = mockStatic(InPacketReader.class)) {
            packets.when(() -> InPacketReader.getMovementRecordingRaw(MAP_ID, RECORDING)).thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                    PathFinder.getPathBetweenTwoPointsInMainArea(MAP_ID, RECORDING, START, END)));
        }
    }

    @Test
    void unavailableMainAreaStopsBothPathVariantsBeforeTheNextSegment() {
        for (boolean aware : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            try (MockedStatic<InPacketReader> packets = packets(raw(30, 20), recording(20, 30));
                 MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
                 MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class)) {
                assertFalse(fixture.execute(aware, List.of(fixture.main, fixture.next)));
                verify(fixture.next, never()).getMovementRecording();
                movement.verify(() -> MovementCommands.executeMovement(any(), any(MovementRecording.class)), never());
                movement.verify(() -> MovementCommands.executeMovement(any(), any(MovementRecording.class),
                        any(MovementCommands.MidMovementCheck.class)), never());
                ticks.verifyNoInteractions();
            }
        }
    }

    @Test
    void legalMainAreaCompletesBothPathVariants() {
        for (boolean aware : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            try (MockedStatic<InPacketReader> packets = packets(raw(10, 20), recording(10, 20));
                 MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class)) {
                assertTrue(fixture.execute(aware, List.of(fixture.main)));
                if (aware) {
                    movement.verify(() -> MovementCommands.executeMovement(eq(fixture.character),
                            any(MovementRecording.class), any(MovementCommands.MidMovementCheck.class)), times(1));
                } else {
                    movement.verify(() -> MovementCommands.executeMovement(eq(fixture.character),
                            any(MovementRecording.class)), times(1));
                }
            }
        }
    }

    @Test
    void emptyNavigationElementsDoNotMeanCompletedMovement() {
        Fixture fixture = new Fixture();
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class)) {
            assertFalse(fixture.execute(false, List.of()));
            assertFalse(fixture.execute(true, List.of()));
        }
    }

    @Test
    void unexpectedFailureStillReportsAndRecoversButCompletionRemainsFalse() {
        for (boolean aware : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            NullPointerException failure = new NullPointerException("active recording failure");
            try (MockedStatic<InPacketReader> packets = mockStatic(InPacketReader.class);
                 MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
                 MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class)) {
                packets.when(() -> InPacketReader.getMovementRecordingRaw(MAP_ID, RECORDING)).thenThrow(failure);
                assertFalse(fixture.execute(aware, List.of(fixture.main, fixture.next)));
                verify(fixture.next, times(1)).getMovementRecording();
                ticks.verify(() -> BotTickService.reportFailure(failure), times(1));
            }
        }
    }

    @Test
    void unavailableRepathPropagatesFalseInsteadOfCompletingTheOriginalPath() {
        Fixture fixture = new Fixture();
        Connector initial = mock(Connector.class);
        MovementRecording initialRecording = recording(10);
        when(initial.getMovementRecording()).thenReturn(initialRecording);
        List<String> replacement = List.of(RECORDING, "c1-2");
        try (MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
             MockedStatic<PathFinder> paths = mockStatic(PathFinder.class)) {
            movement.when(() -> MovementCommands.executeMovement(eq(fixture.character), eq(initialRecording),
                    any(MovementCommands.MidMovementCheck.class))).thenReturn(true);
            paths.when(() -> PathFinder.createPath(eq(MAP_ID), any(), eq(END), eq(PathFinder.PathType.RANDOM)))
                    .thenReturn(replacement);
            paths.when(() -> PathFinder.getNavElements(MAP_ID, replacement))
                    .thenReturn(List.of(fixture.main, fixture.next));
            paths.when(() -> PathFinder.getPathBetweenTwoPointsInMainArea(MAP_ID, RECORDING, START, END))
                    .thenReturn(Optional.empty());
            assertFalse(fixture.execute(true, List.of(initial)));
            verify(fixture.next, never()).getMovementRecording();
        }
    }

    private static MovementRecordingRaw raw(long startTime, long endTime) {
        MovementRecordingRaw raw = spy(new MovementRecordingRaw(MAP_ID, RECORDING, List.of()));
        doReturn(List.of(new TimeCoordinatePair(startTime, List.of(START), List.of((byte) 4)),
                new TimeCoordinatePair(endTime, List.of(END), List.of((byte) 4))))
                .when(raw).getTimeCoordPath();
        return raw;
    }

    private static MovementRecording recording(long... timestamps) {
        return new MovementRecording(MAP_ID, RECORDING,
                Arrays.stream(timestamps).mapToObj(time -> new MovementPacket(time, null)).toList());
    }

    private static MockedStatic<InPacketReader> packets(MovementRecordingRaw raw, MovementRecording recording) {
        MockedStatic<InPacketReader> packets = mockStatic(InPacketReader.class);
        packets.when(() -> InPacketReader.getMovementRecordingRaw(MAP_ID, RECORDING)).thenReturn(raw);
        packets.when(() -> InPacketReader.getMovementRecording(MAP_ID, RECORDING)).thenReturn(recording);
        return packets;
    }

    private static final class Fixture {
        final Character character = mock(Character.class);
        final MainArea main = new MainArea(MAP_ID, RECORDING);
        final Connector next = mock(Connector.class);
        final PathFinder path;

        Fixture() {
            when(character.getMap()).thenReturn(mock(MapleMap.class));
            when(character.getPosition()).thenReturn(START);
            when(next.getStartPosition()).thenReturn(END);
            when(next.getMovementRecording()).thenReturn(recording(10, 20));
            path = new PathFinder(character, END);
        }

        boolean execute(boolean aware, List<NavigationElement> elements) {
            return aware ? path.executePathAware(MAP_ID, elements, 300, 200, 1, 1, 1)
                    : path.executePath(MAP_ID, elements);
        }
    }
}
