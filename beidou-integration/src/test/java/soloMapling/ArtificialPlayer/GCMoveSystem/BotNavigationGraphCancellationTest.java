package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

    @AfterEach
    void cleanTestGraph() throws Exception {
        BotNavigationGraphProvider.cancelPendingLoads();
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
