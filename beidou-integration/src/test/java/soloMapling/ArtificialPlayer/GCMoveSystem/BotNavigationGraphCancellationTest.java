package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MapManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotNavigationGraphCancellationTest {
    private static final AtomicInteger MAP_IDS = new AtomicInteger(-990000);
    private final int mapId = MAP_IDS.decrementAndGet();

    @BeforeAll
    static void context() {
        CharacterTestContext.install();
        BotNavigationGraphProvider.cancelPendingLoads();
    }

    @BeforeEach
    void allowNavigationLoads() {
        GCMovement.start();
    }

    @AfterEach
    void cleanTestGraph() throws Exception {
        BotNavigationGraphProvider.cancelPendingLoads();
        GCMovement.start();
        Object key = key();
        for (String name : List.of("GRAPHS", "PENDING_GRAPHS", "LAST_BUILD_REPORTS")) {
            cache(name).remove(key);
        }
        cache("COLLIDABLE_WALL_IDS_BY_MAP_ID").remove(mapId);
        cache("COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID").remove(mapId);
        Method file = BotNavigationGraphProvider.class.getDeclaredMethod("graphFile", key.getClass());
        file.setAccessible(true);
        Files.deleteIfExists((Path) file.invoke(null, key));
    }

    @Test
    void loadRegisteredAfterCancellationStillTerminatesItsPromise() throws Exception {
        MapleMap map = map();
        CountDownLatch sessionRead = new CountDownLatch(1);
        CountDownLatch resumeRegistration = new CountDownLatch(1);
        CompletableFuture<CompletableFuture<BotNavigationGraph>> returned = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
                movement.when(GCMovement::isStopping).thenAnswer(call -> {
                    sessionRead.countDown();
                    await(resumeRegistration);
                    return false;
                });
                returned.complete(load(map));
            } catch (Throwable failure) {
                returned.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(sessionRead.await(3, TimeUnit.SECONDS));
            BotNavigationGraphProvider.cancelPendingLoads();
            resumeRegistration.countDown();
            CompletableFuture<BotNavigationGraph> promise = returned.get(3, TimeUnit.SECONDS);
            assertTrue(promise.isDone(), "An obsolete load must not leave join() waiting forever");
            assertThrows(CancellationException.class, () -> promise.get(1, TimeUnit.SECONDS));
            verify(map, never()).getFootholds();
            assertNull(BotNavigationGraphProvider.peekGraph(map));
        } finally {
            resumeRegistration.countDown();
            worker.join(3000);
        }
    }

    @Test
    void completedBuildCannotPublishAfterItsGenerationWasCancelled() throws Exception {
        MapleMap map = map();
        CountDownLatch built = new CountDownLatch(1);
        CountDownLatch resumePublication = new CountDownLatch(1);
        AtomicBoolean held = new AtomicBoolean();
        when(map.getId()).thenAnswer(call -> {
            if (BotNavigationGraphProvider.getLastBuildReport(mapId) != null && held.compareAndSet(false, true)) {
                built.countDown();
                await(resumePublication);
            }
            return mapId;
        });
        CompletableFuture<CompletableFuture<BotNavigationGraph>> returned = new CompletableFuture<>();
        Thread worker = runLoad(map, returned);
        try {
            assertTrue(built.await(3, TimeUnit.SECONDS));
            BotNavigationGraphProvider.cancelPendingLoads();
            resumePublication.countDown();
            CompletableFuture<BotNavigationGraph> promise = returned.get(3, TimeUnit.SECONDS);
            assertTrue(promise.isCancelled());
            assertNull(BotNavigationGraphProvider.peekGraph(map));
            assertTrue(cache("PENDING_GRAPHS").isEmpty());
        } finally {
            resumePublication.countDown();
            worker.join(3000);
        }
    }

    @Test
    void obsoleteCancellationDoesNotReportAWarmupFailure() throws Exception {
        failureDuringBuild(new CompletionException(new CancellationException("stopped graph")), true, false);
    }

    @Test
    void activeCancellationRemainsAReportedFailure() throws Exception {
        failureDuringBuild(new CancellationException("unexpected active cancellation"), false, true);
    }

    @Test
    void obsoleteDomainFailureRemainsVisible() throws Exception {
        failureDuringBuild(new NullPointerException("obsolete graph domain failure"), true, true);
    }

    @Test
    void stoppingMovementWaitsForOldMapReadersAndCancelsTheirNextPhysicsProbe() throws Exception {
        MapleMap map = map();
        CountDownLatch readingMap = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(map.getRopes()).thenAnswer(call -> {
            readingMap.countDown();
            awaitUninterruptibly(releaseReader, interrupted);
            // A real warmup next reaches this probe in its jump simulation. Cancellation
            // must stop it before the native fallback touches a disposed foothold tree.
            BotPhysicsEngine.pointBelowIndexed(map, new Point());
            return List.of();
        });
        CompletableFuture<CompletableFuture<BotNavigationGraph>> returned = new CompletableFuture<>();
        Thread reader = runLoad(map, returned);
        CompletableFuture<Void> stopping = null;
        try {
            assertTrue(readingMap.await(3, TimeUnit.SECONDS));
            stopping = CompletableFuture.runAsync(GCMovement::stopAll);
            assertTrue(interrupted.await(3, TimeUnit.SECONDS), "Shutdown must interrupt the live warmup");
            assertFalse(stopping.isDone(), "A cancelled promise must not release native map disposal yet");
            releaseReader.countDown();
            stopping.get(3, TimeUnit.SECONDS);
            assertTrue(returned.get(3, TimeUnit.SECONDS).isCancelled());
            verify(map, never()).getPointBelow(any());
            assertNull(BotNavigationGraphProvider.peekGraph(map));
        } finally {
            releaseReader.countDown();
            reader.join(3000);
            if (stopping != null) stopping.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void stoppedNavigationRejectsLateRoutesAndResumesOnTheSameExecutor() throws Exception {
        MapleMap map = map();
        MapManager maps = mock(MapManager.class);
        when(maps.getMap(mapId)).thenReturn(map);
        assertTrue(BotNavigationGraphProvider.stopAndAwaitLoads(1, TimeUnit.SECONDS));
        BotNavigationGraphProvider.warmGraphsForRouteAsync(maps, List.of(mapId + 1), BotMovementProfile.base());
        BotNavigationGraphProvider.warmGraphAsync(map, BotMovementProfile.base());
        verifyNoInteractions(maps);
        verify(map, never()).getFootholds();

        GCMovement.start();
        BotNavigationGraphProvider.warmGraphsForRouteAsync(maps, List.of(mapId), BotMovementProfile.base());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (BotNavigationGraphProvider.peekGraph(map) == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(BotNavigationGraphProvider.peekGraph(map), "The reusable executor must warm the next world");
        verify(maps).getMap(mapId);
        verify(maps, never()).getMap(mapId + 1);
    }

    @Test
    void drainTimeoutDoesNotClaimAStillRunningReaderHasStopped() throws Exception {
        MapleMap map = map();
        CountDownLatch readingMap = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(map.getRopes()).thenAnswer(call -> {
            readingMap.countDown();
            awaitUninterruptibly(releaseReader, interrupted);
            BotNavigationGraphProvider.checkBuildActive();
            return List.of();
        });
        CompletableFuture<CompletableFuture<BotNavigationGraph>> returned = new CompletableFuture<>();
        Thread reader = runLoad(map, returned);
        try {
            assertTrue(readingMap.await(3, TimeUnit.SECONDS));
            assertFalse(BotNavigationGraphProvider.stopAndAwaitLoads(20, TimeUnit.MILLISECONDS));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertFalse(returned.isDone(), "Timeout must preserve the running reader's actual state");
            releaseReader.countDown();
            assertTrue(BotNavigationGraphProvider.stopAndAwaitLoads(3, TimeUnit.SECONDS));
            assertTrue(returned.get(3, TimeUnit.SECONDS).isCancelled());
        } finally {
            releaseReader.countDown();
            reader.join(3000);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch release, CountDownLatch interrupted) throws InterruptedException {
        boolean restoreInterrupt = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            while (true) {
                try {
                    assertTrue(release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                    return;
                } catch (InterruptedException cancellation) {
                    restoreInterrupt = true;
                    interrupted.countDown();
                }
            }
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private void failureDuringBuild(RuntimeException failure, boolean cancelGeneration, boolean reported) throws Exception {
        MapleMap map = map();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch releaseBuild = new CountDownLatch(1);
        when(map.getFootholds()).thenAnswer(call -> {
            building.countDown();
            await(releaseBuild);
            throw failure;
        });
        CapturingAppender appender = new CapturingAppender();
        Logger logger = (Logger) LogManager.getLogger(BotNavigationGraphProvider.class);
        Level previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
        CompletableFuture<CompletableFuture<BotNavigationGraph>> returned = new CompletableFuture<>();
        Thread worker = runLoad(map, returned);
        try {
            assertTrue(building.await(3, TimeUnit.SECONDS));
            if (cancelGeneration) BotNavigationGraphProvider.cancelPendingLoads();
            releaseBuild.countDown();
            CompletableFuture<BotNavigationGraph> promise = returned.get(3, TimeUnit.SECONDS);
            assertTrue(promise.isCompletedExceptionally());
            if (reported) assertTrue(appender.failure.await(3, TimeUnit.SECONDS), appender.events::toString);
            assertEquals(reported, appender.events.stream().anyMatch(event -> event.getThrown() == failure
                    && event.getMessage().getFormattedMessage().startsWith("Failed to warm bot nav graph")));
            assertNull(BotNavigationGraphProvider.peekGraph(map));
        } finally {
            releaseBuild.countDown();
            worker.join(3000);
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private Thread runLoad(MapleMap map, CompletableFuture<CompletableFuture<BotNavigationGraph>> returned) {
        return Thread.ofVirtual().start(() -> {
            try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
                returned.complete(load(map));
            } catch (Throwable failure) {
                returned.completeExceptionally(failure);
            }
        });
    }

    private MapleMap map() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getRopes()).thenReturn(List.of());
        when(map.getPortals()).thenReturn(List.of());
        return map;
    }

    private Object key() throws Exception {
        Class<?> type = Class.forName(BotNavigationGraphProvider.class.getName() + "$GraphCacheKey");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        BotMovementProfile profile = BotMovementProfile.base();
        return constructor.newInstance(mapId, profile.totalSpeedStat(), profile.totalJumpStat(), profile.snowShoes());
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<BotNavigationGraph> load(MapleMap map) throws Exception {
        Object key = key();
        Method method = BotNavigationGraphProvider.class.getDeclaredMethod("getOrStartGraphLoad",
                MapleMap.class, BotMovementProfile.class, key.getClass(), boolean.class);
        method.setAccessible(true);
        try {
            return (CompletableFuture<BotNavigationGraph>) method.invoke(null, map, BotMovementProfile.base(), key, false);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> cache(String name) throws Exception {
        Field field = BotNavigationGraphProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled graph load was not released");
    }

    private static final class CapturingAppender extends AbstractAppender {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch failure = new CountDownLatch(1);
        CapturingAppender() {
            super("graph-cancellation-test", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }
        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
            if (event.getMessage().getFormattedMessage().startsWith("Failed to warm bot nav graph")) {
                failure.countDown();
            }
        }
    }
}
