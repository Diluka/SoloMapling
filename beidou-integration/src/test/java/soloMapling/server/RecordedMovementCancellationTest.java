package soloMapling.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.packet.ByteBufInPacket;
import org.gms.net.packet.Packet;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.channel.Channel;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementPacket;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementRecording;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecordedMovementCancellationTest {
    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @BeforeEach
    void start() { BotTickService.start(); }

    @AfterEach
    void stop() {
        BotTickService.stop();
        BotTickService.interruptCancelledRunning();
        assertTrue(BotTickService.awaitQuiescence(2, TimeUnit.SECONDS));
    }

    @Test
    void realLongReplayLeavesItsSleepAndSendsNoPacketAfterStop() throws Exception {
        Actor actor = actor(931);
        CountDownLatch firstPacket = new CountDownLatch(1);
        doAnswer(call -> { actor.sent.incrementAndGet(); firstPacket.countDown(); return null; })
                .when(actor.map).broadcastMessage(eq(actor.character), any(Packet.class), eq(false));
        AtomicReference<CancellationException> cancelled = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        try (BotTickServiceLifecycleTest.Dispatch dispatch = new BotTickServiceLifecycleTest.Dispatch()) {
            MovementRecording recording = recording(60_000);
            BotTickService.register(931, () -> {
                try {
                    MovementCommands.BotMoveStreamHelper(recording, actor.character, false, null);
                } catch (CancellationException failure) {
                    cancelled.set(failure);
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                    throw failure;
                }
            }, 0, 1);
            Thread worker = Thread.ofVirtual().start(dispatch.next());
            assertTrue(firstPacket.await(2, TimeUnit.SECONDS));
            awaitReplaySleep(worker);
            BotTickService.stop();
            BotTickService.interruptCancelledRunning();
            assertTrue(BotTickService.awaitQuiescence(2, TimeUnit.SECONDS));
            worker.join(1000);
            assertNotNull(cancelled.get());
            assertTrue(interruptPreserved.get());
            assertEquals(1, actor.sent.get(), "Cancellation must not send a stop packet or replay the next segment");
        } finally {
            BotRegistry.unregister(actor.character);
        }
    }

    @Test
    void liveReplayUsesTheRealDecoderAndSendsEveryRecordedPacket() throws Exception {
        Actor actor = actor(932);
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        try (BotTickServiceLifecycleTest.Dispatch dispatch = new BotTickServiceLifecycleTest.Dispatch()) {
            BotTickService.register(932, () -> interrupted.set(MovementCommands.BotMoveStreamHelper(
                    recording(2), actor.character, false, null)), 0, 1);
            dispatch.next().run();
            assertEquals(Boolean.FALSE, interrupted.get());
            assertEquals(3, actor.sent.get());
            assertEquals(new Point(30, 0), actor.position.get());
        } finally {
            BotRegistry.unregister(actor.character);
        }
    }

    @Test
    void interruptedArtificialStopCannotConsumeTheFlagOrEmitAnotherPacket() {
        Actor actor = actor(933);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,
                    () -> MovementCommands.injectArtificialStopPacket(actor.character));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, actor.sent.get());
        } finally {
            Thread.interrupted();
            BotRegistry.unregister(actor.character);
        }
    }

    @Test
    void stoppedRoleEntryCannotBeginAnotherReplayEvenWithoutAThreadInterrupt() throws Exception {
        Actor actor = actor(934);
        AtomicReference<CancellationException> cancelled = new AtomicReference<>();
        try (BotTickServiceLifecycleTest.Dispatch dispatch = new BotTickServiceLifecycleTest.Dispatch()) {
            BotTickService.register(934, () -> {
                BotTickService.unregister(934);
                try {
                    MovementCommands.BotMoveStreamHelper(recording(0), actor.character, false, null);
                } catch (CancellationException failure) {
                    cancelled.set(failure);
                    throw failure;
                }
            }, 0, 1);
            dispatch.next().run();
            assertNotNull(cancelled.get());
            assertEquals(0, actor.sent.get());
        } finally {
            BotRegistry.unregister(actor.character);
        }
    }

    @Test
    void malformedLiveMovementRemainsAReportedError() throws Exception {
        Actor actor = actor(935);
        BotTickServiceLifecycleTest.CapturingAppender appender = new BotTickServiceLifecycleTest.CapturingAppender();
        Logger logger = (Logger) LogManager.getLogger(BotTickService.class);
        appender.start();
        logger.addAppender(appender);
        try (BotTickServiceLifecycleTest.Dispatch dispatch = new BotTickServiceLifecycleTest.Dispatch()) {
            MovementRecording broken = new MovementRecording(1, "broken", List.of(
                    new MovementPacket(0, new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[1])))));
            BotTickService.register(935, () -> MovementCommands.BotMoveStreamHelper(
                    broken, actor.character, false, null), 0, 1);
            dispatch.next().run();
            assertTrue(appender.events.stream().anyMatch(event -> event.getThrown() instanceof IndexOutOfBoundsException));
            assertEquals(0, actor.sent.get());
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            BotRegistry.unregister(actor.character);
        }
    }

    private static void awaitReplaySleep(Thread worker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            for (StackTraceElement frame : worker.getStackTrace()) {
                if (worker.getState() == Thread.State.TIMED_WAITING
                        && frame.getClassName().equals(BotHelpers.class.getName())
                        && frame.getMethodName().equals("waitBetweenTwoLong")) return;
            }
            Thread.sleep(1);
        }
        fail("Replay did not reach the real long timestamp wait");
    }

    private static MovementRecording recording(long gap) {
        List<MovementPacket> packets = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            ByteBuf bytes = Unpooled.buffer(24);
            bytes.writeZero(9).writeByte(1).writeByte(0).writeShortLE((index + 1) * 10).writeShortLE(0)
                    .writeZero(6).writeByte(2).writeShortLE(100);
            packets.add(new MovementPacket(index * gap, new ByteBufInPacket(bytes)));
        }
        return new MovementRecording(1, "test", packets);
    }

    private static Actor actor(int id) {
        Character character = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotClient client = mock(BotClient.class);
        Channel channel = mock(Channel.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point());
        AtomicInteger sent = new AtomicInteger();
        when(character.getId()).thenReturn(id);
        when(character.getClient()).thenReturn(client);
        when(character.getMap()).thenReturn(map);
        when(character.getPosition()).thenAnswer(ignored -> position.get());
        doAnswer(call -> { position.set(call.getArgument(0)); return null; }).when(character).setPosition(any(Point.class));
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(id)).thenReturn(character);
        doAnswer(ignored -> { sent.incrementAndGet(); return null; })
                .when(map).broadcastMessage(eq(character), any(Packet.class), eq(false));
        BotRegistry.register(character);
        return new Actor(character, map, position, sent);
    }

    private record Actor(Character character, MapleMap map, AtomicReference<Point> position, AtomicInteger sent) { }
}
