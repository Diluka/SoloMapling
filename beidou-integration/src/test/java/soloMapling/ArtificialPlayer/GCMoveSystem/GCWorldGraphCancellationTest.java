package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GCWorldGraphCancellationTest {
    private Field edgesField;
    private Object previousEdges;

    @BeforeEach
    void isolateCache() throws Exception {
        edgesField = GCWorldGraph.class.getDeclaredField("edges");
        edgesField.setAccessible(true);
        previousEdges = edgesField.get(null);
        edgesField.set(null, null);
    }

    @AfterEach
    void restoreCache() throws Exception {
        edgesField.set(null, previousEdges);
    }

    @Test
    void interruptedScanStopsWorkersAndDoesNotPublishPartialEdges() throws Exception {
        try (Scanner scanner = new Scanner()) {
            CountDownLatch waitingForScan = new CountDownLatch(1);
            AtomicBoolean interruptRestored = new AtomicBoolean();
            CompletableFuture<CancellationException> stopped = new CompletableFuture<>();
            Thread builder = Thread.ofVirtual().start(() -> {
                try {
                    GCWorldGraph.get(() -> {
                        waitingForScan.countDown();
                        return GCWorldGraph.finishBuild(scanner.out, scanner.pool, 1, TimeUnit.MINUTES);
                    });
                    stopped.completeExceptionally(new AssertionError("Interrupted world scan was published"));
                } catch (CancellationException cancellation) {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                    stopped.complete(cancellation);
                } catch (Throwable failure) {
                    stopped.completeExceptionally(failure);
                }
            });
            try {
                assertTrue(waitingForScan.await(3, TimeUnit.SECONDS));
                builder.interrupt();
                CancellationException cancellation = stopped.get(3, TimeUnit.SECONDS);
                assertInstanceOf(InterruptedException.class, cancellation.getCause());
                assertTrue(interruptRestored.get());
                scanner.assertInterrupted();
                assertFalse(GCWorldGraph.isReady());
                assertArrayEquals(new int[0], GCWorldGraph.portalNeighbors(1));
                assertCanBuildAndReuseAfterCancellation();
            } finally {
                scanner.release.countDown();
                builder.join(3000);
                assertFalse(builder.isAlive());
            }
        }
    }

    @Test
    void timedOutScanStopsWorkersAndDoesNotPublishPartialEdges() throws Exception {
        try (Scanner scanner = new Scanner()) {
            assertThrows(CancellationException.class, () -> GCWorldGraph.get(() ->
                    GCWorldGraph.finishBuild(scanner.out, scanner.pool, 0, TimeUnit.NANOSECONDS)));
            scanner.assertInterrupted();
            assertFalse(GCWorldGraph.isReady());
            assertArrayEquals(new int[0], GCWorldGraph.portalNeighbors(1));
            assertCanBuildAndReuseAfterCancellation();
        }
    }

    @Test
    void successfulScanPublishesOnlyTheCompletedGraphAndReusesIt() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Map<Integer, int[]> out = new ConcurrentHashMap<>();
        try {
            pool.execute(() -> out.put(1, new int[]{2}));
            pool.execute(() -> out.put(2, new int[]{3}));
            Map<Integer, int[]> graph = GCWorldGraph.get(() ->
                    GCWorldGraph.finishBuild(out, pool, 3, TimeUnit.SECONDS));
            assertTrue(pool.isTerminated());
            assertTrue(GCWorldGraph.isReady());
            assertEquals(2, graph.size());
            assertArrayEquals(new int[]{3}, graph.get(2));
            assertThrows(UnsupportedOperationException.class, () -> graph.put(3, new int[0]));
            assertSame(graph, GCWorldGraph.get(() -> fail("Completed graph must be reused")));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void assertCanBuildAndReuseAfterCancellation() {
        AtomicInteger builds = new AtomicInteger();
        Map<Integer, int[]> complete = Map.of(20, new int[]{21}, 21, new int[0]);
        assertSame(complete, GCWorldGraph.get(() -> {
            builds.incrementAndGet();
            return complete;
        }));
        assertTrue(GCWorldGraph.isReady());
        assertArrayEquals(new int[]{21}, GCWorldGraph.portalNeighbors(20));
        assertSame(complete, GCWorldGraph.get(() -> fail("Replacement graph must be reused")));
        assertEquals(1, builds.get());
    }

    /** Real worker that has produced one edge but cannot finish until released or interrupted. */
    private static final class Scanner implements AutoCloseable {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final Map<Integer, int[]> out = new ConcurrentHashMap<>();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);

        Scanner() throws InterruptedException {
            CountDownLatch entered = new CountDownLatch(1);
            pool.execute(() -> {
                out.put(1, new int[]{2});
                entered.countDown();
                try {
                    release.await();
                    out.put(2, new int[]{3});
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        }

        void assertInterrupted() throws InterruptedException {
            assertTrue(interrupted.await(3, TimeUnit.SECONDS), "Cancelled scan must interrupt its worker");
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
