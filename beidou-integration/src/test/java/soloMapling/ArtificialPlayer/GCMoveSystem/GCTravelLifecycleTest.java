package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLWZFile;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GCTravelLifecycleTest {
    @TempDir
    Path temporaryWz;

    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void detachedHeadlessCharacterCannotStartANewTrip() {
        Character character = headless(781);
        try (MockedStatic<BotRegistry> registry = mockStatic(BotRegistry.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            GCTravel.travel(character, 10, ignored -> fail("Detached trips must not re-arm old callbacks"));
            assertFalse(GCTravel.isTraveling(character));
            movement.verify(() -> GCMovement.enable(character), never());
            verify(character, never()).changeMap(anyInt());
            verify(character, never()).setLoggedIn(anyBoolean());
        }
    }

    @Test
    void detachedPollCancelsItsFutureAndRemovesTravelAndMovementState() throws Exception {
        Character character = headless(782);
        Class<?> tripType = Class.forName(GCTravel.class.getName() + "$Trip");
        Constructor<?> constructor = tripType.getDeclaredConstructor(Character.class, int.class, Consumer.class);
        constructor.setAccessible(true);
        Object trip = constructor.newInstance(character, 10, null);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        Field taskField = tripType.getDeclaredField("task");
        taskField.setAccessible(true);
        taskField.set(trip, task);
        Field tripsField = GCTravel.class.getDeclaredField("TRIPS");
        tripsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> trips = (Map<Integer, Object>) tripsField.get(null);
        trips.put(character.getId(), trip);
        Method poll = GCTravel.class.getDeclaredMethod("poll", tripType);
        poll.setAccessible(true);
        try (MockedStatic<BotRegistry> registry = mockStatic(BotRegistry.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            poll.invoke(null, trip);
            poll.invoke(null, trip);
            assertFalse(GCTravel.isTraveling(character));
            verify(task, times(1)).cancel(false);
            movement.verify(() -> GCMovement.disable(character), times(1));
            verify(character, never()).changeMap(anyInt());
            verify(character, never()).setLoggedIn(anyBoolean());
        } finally {
            trips.remove(character.getId(), trip);
        }
    }

    @Test
    void realGmDiagnosticCharacterKeepsItsExistingTravelContract() {
        Character character = mock(Character.class);
        when(character.getClient()).thenReturn(mock(Client.class));
        when(character.getMap()).thenReturn(mock(MapleMap.class));
        when(character.getMapId()).thenReturn(10);
        AtomicBoolean called = new AtomicBoolean();
        try (MockedStatic<BotRegistry> registry = mockStatic(BotRegistry.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            GCTravel.travel(character, 10, result -> called.set(result));
            assertTrue(called.get());
            registry.verifyNoInteractions();
        }
    }

    @Test
    void routeScanCancellationStopsNativeReadersAndTheNextRouteRebuilds() throws Exception {
        Field cache = GCWorldGraph.class.getDeclaredField("edges");
        cache.setAccessible(true);
        Object previous = cache.get(null);
        try {
            for (boolean timeout : List.of(false, true)) {
                cache.set(null, null);
                try (RouteScan scan = new RouteScan(temporaryWz.resolve("cancel-" + timeout), 2, true, timeout)) {
                    CompletableFuture<CancellationException> result = new CompletableFuture<>();
                    AtomicBoolean interruptRestored = new AtomicBoolean();
                    Thread caller = Thread.ofVirtual().start(() -> {
                        try {
                            scan.report();
                            result.completeExceptionally(new AssertionError("Cancelled route scan returned a route"));
                        } catch (CancellationException cancelled) {
                            interruptRestored.set(Thread.currentThread().isInterrupted());
                            result.complete(cancelled);
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
                    try {
                        assertTrue(scan.readingSecondMap.await(3, TimeUnit.SECONDS));
                        if (!timeout) caller.interrupt();
                        CancellationException cancelled = result.get(3, TimeUnit.SECONDS);
                        assertEquals(!timeout, interruptRestored.get());
                        if (!timeout) assertInstanceOf(InterruptedException.class, cancelled.getCause());
                        assertTrue(scan.readerInterrupted.await(3, TimeUnit.SECONDS));
                        assertTrue(scan.workers.awaitTermination(3, TimeUnit.SECONDS));
                    } finally {
                        scan.releaseReader.countDown();
                        caller.join(3000);
                        assertFalse(caller.isAlive());
                    }
                }
                // Changed WZ connectivity must produce a new route; a cancelled partial
                // graph must not become the cache used by the public diagnostic.
                try (RouteScan replacement = new RouteScan(temporaryWz.resolve("replacement-" + timeout), 4, false, false)) {
                    assertTrue(replacement.report().contains("2 hops [4, 3]"));
                    assertTrue(replacement.report().contains("2 hops [4, 3]"));
                    assertEquals(3, replacement.nativeReads.get());
                }
            }
        } finally {
            cache.set(null, previous);
        }
    }

    /** WZ storage and scanner threads are the external collaborators of route diagnostics. */
    private static final class RouteScan implements AutoCloseable {
        final ExecutorService workers = Executors.newSingleThreadExecutor();
        final ExecutorService scanner = mock(ExecutorService.class);
        final CountDownLatch readingSecondMap = new CountDownLatch(1);
        final CountDownLatch releaseReader = new CountDownLatch(1);
        final CountDownLatch readerInterrupted = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger nativeReads = new java.util.concurrent.atomic.AtomicInteger();
        final DataProvider data;
        final List<Path> mapFiles;
        final Path nativeArea;
        final Character character = mock(Character.class);

        RouteScan(Path wz, int intermediate, boolean holdReader, boolean timeout) throws Exception {
            Path area = Files.createDirectories(wz.resolve("Map/Map0"));
            mapFiles = List.of(mapFile(area, 1, intermediate), mapFile(area, intermediate, 3), mapFile(area, 3, 999999999));
            data = spy(new XMLWZFile(wz));
            doAnswer(call -> {
                nativeReads.incrementAndGet();
                if (holdReader && call.<String>getArgument(0).endsWith(String.format("%09d.img", intermediate))) {
                    readingSecondMap.countDown();
                    try {
                        assertTrue(releaseReader.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException cancelled) {
                        readerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return call.callRealMethod();
            }).when(data).getData(anyString());
            nativeArea = Path.of(WZFiles.MAP.getFilePath(), "Map/Map0");
            when(character.getMap()).thenReturn(mock(MapleMap.class));
            when(character.getMapId()).thenReturn(1);
            doAnswer(call -> {
                Runnable body = call.getArgument(0);
                workers.execute(() -> {
                    try (MockedStatic<DataProviderFactory> storage = mockStatic(DataProviderFactory.class)) {
                        storage.when(() -> DataProviderFactory.getDataProvider(WZFiles.MAP)).thenReturn(data);
                        body.run();
                    }
                });
                return null;
            }).when(scanner).execute(any(Runnable.class));
            doAnswer(call -> { workers.shutdown(); return null; }).when(scanner).shutdown();
            doAnswer(call -> workers.shutdownNow()).when(scanner).shutdownNow();
            when(scanner.awaitTermination(10, TimeUnit.MINUTES)).thenAnswer(call -> {
                if (timeout) {
                    assertTrue(readingSecondMap.await(3, TimeUnit.SECONDS));
                    return false;
                }
                return workers.awaitTermination(10, TimeUnit.MINUTES);
            });
        }

        String report() throws Exception {
            try (MockedStatic<Files> filesystem = mockStatic(Files.class, CALLS_REAL_METHODS);
                 MockedStatic<Executors> executors = mockStatic(Executors.class, CALLS_REAL_METHODS)) {
                for (int area = 0; area <= 9; area++) {
                    Path directory = nativeArea.resolveSibling("Map" + area);
                    filesystem.when(() -> Files.isDirectory(directory)).thenReturn(area == 0);
                }
                filesystem.when(() -> Files.list(nativeArea)).thenAnswer(call -> mapFiles.stream());
                executors.when(() -> Executors.newFixedThreadPool(anyInt())).thenReturn(scanner);
                return GCMovement.routeReport(character, 3);
            }
        }

        private static Path mapFile(Path area, int id, int destination) throws Exception {
            return Files.writeString(area.resolve(String.format("%09d.img.xml", id)), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <imgdir name="map"><imgdir name="portal"><imgdir name="0">
                    <int name="tm" value="%d"/><int name="pt" value="2"/>
                    </imgdir></imgdir></imgdir>
                    """.formatted(destination));
        }

        @Override
        public void close() throws Exception {
            releaseReader.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static Character headless(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getClient()).thenReturn(mock(BotClient.class));
        return character;
    }
}
