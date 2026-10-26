package soloMapling.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BotTickServiceLifecycleTest {
    @BeforeEach
    void startGeneration() {
        BotTickService.stop();
        assertTrue(BotTickService.awaitQuiescence(1, TimeUnit.SECONDS));
        BotTickService.start();
    }

    @AfterEach
    void stopGeneration() {
        BotTickService.stop();
        assertTrue(BotTickService.awaitQuiescence(1, TimeUnit.SECONDS));
    }

    @Test
    void drainWaitsForTheActualBodyAndStoppedBodyCannotRegisterAgain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(901, () -> {
                entered.countDown();
                await(release);
                BotTickService.register(902, () -> fail("Stopped tick resurrected"), 0, 1);
                finished.set(true);
            }, 0, 1);
            Thread worker = Thread.ofVirtual().start(dispatch.next());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            BotTickService.stop();
            CompletableFuture<Boolean> drained = new CompletableFuture<>();
            Thread waiter = Thread.ofVirtual().start(() ->
                    drained.complete(BotTickService.awaitQuiescence(2, TimeUnit.SECONDS)));
            try {
                assertThrows(TimeoutException.class, () -> drained.get(100, TimeUnit.MILLISECONDS));
                assertFalse(finished.get());
            } finally {
                release.countDown();
            }
            assertTrue(drained.get(2, TimeUnit.SECONDS));
            assertTrue(finished.get());
            assertFalse(BotTickService.isRegistered(902));
            worker.join(1000);
            waiter.join(1000);
        } finally {
            release.countDown();
        }
    }

    @Test
    void queuedOldRunnableCannotEnterAReplacementGeneration() throws Exception {
        AtomicInteger called = new AtomicInteger();
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(903, called::incrementAndGet, 0, 1);
            Runnable old = dispatch.next();
            BotTickService.stop();
            assertTrue(BotTickService.awaitQuiescence(1, TimeUnit.SECONDS));
            BotTickService.start();
            BotTickService.register(903, called::incrementAndGet, 0, 1);
            old.run();
            assertEquals(0, called.get());
            dispatch.next().run();
            assertEquals(1, called.get());
        }
    }

    @Test
    void timeoutIsBoundedEvenWhileTheCallbackIsStillRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(904, () -> { entered.countDown(); await(release); }, 0, 1);
            Thread worker = Thread.ofVirtual().start(dispatch.next());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            BotTickService.stop();
            long began = System.nanoTime();
            try {
                assertFalse(BotTickService.awaitQuiescence(30, TimeUnit.MILLISECONDS));
                assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(1));
            } finally {
                release.countDown();
            }
            assertTrue(BotTickService.awaitQuiescence(2, TimeUnit.SECONDS));
            worker.join(1000);
        } finally {
            release.countDown();
        }
    }

    @Test
    void callbackStoppingItselfReturnsFailureWithoutSelfWaiting() throws Exception {
        AtomicBoolean result = new AtomicBoolean(true);
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(905, () -> {
                BotTickService.stop();
                result.set(BotTickService.awaitQuiescence(1, TimeUnit.DAYS));
            }, 0, 1);
            long began = System.nanoTime();
            dispatch.next().run();
            assertFalse(result.get());
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(1));
            assertTrue(BotTickService.awaitQuiescence(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeCallbackFailuresRemainVisible() throws Exception {
        NullPointerException failure = new NullPointerException("active-world-domain-failure");
        CapturingAppender appender = new CapturingAppender();
        Logger logger = (Logger) LogManager.getLogger(BotTickService.class);
        appender.start();
        logger.addAppender(appender);
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(906, () -> { throw failure; }, 0, 1);
            dispatch.next().run();
            assertTrue(appender.events.stream().anyMatch(event -> event.getThrown() == failure
                    && event.getMessage().getFormattedMessage().contains("906")));
            assertTrue(BotTickService.awaitQuiescence(1, TimeUnit.SECONDS));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void cancelledSleepingBodyIsInterruptedAndActuallyLeavesBeforeDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean exited = new AtomicBoolean();
        CapturingAppender appender = new CapturingAppender();
        Logger logger = (Logger) LogManager.getLogger(BotTickService.class);
        appender.start();
        logger.addAppender(appender);
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(907, () -> {
                entered.countDown();
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(interrupted);
                } finally {
                    exited.set(true);
                }
            }, 0, 1);
            Thread worker = Thread.ofVirtual().start(dispatch.next());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            BotTickService.stop();
            BotTickService.interruptCancelledRunning();
            assertTrue(BotTickService.awaitQuiescence(2, TimeUnit.SECONDS));
            assertTrue(exited.get());
            assertTrue(appender.events.isEmpty());
            worker.join(1000);
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void activeCancellationAndCancelledGenerationDomainFailuresRemainVisible() throws Exception {
        CapturingAppender appender = new CapturingAppender();
        Logger logger = (Logger) LogManager.getLogger(BotTickService.class);
        appender.start();
        logger.addAppender(appender);
        CancellationException active = new CancellationException("unexpected-active-cancellation");
        NullPointerException stoppedFailure = new NullPointerException("stopped-domain-failure");
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(908, () -> { throw active; }, 0, 1);
            dispatch.next().run();
            assertTrue(appender.events.stream().anyMatch(event -> event.getThrown() == active));
            BotTickService.unregister(908);
            BotTickService.register(909, () -> { BotTickService.stop(); throw stoppedFailure; }, 0, 1);
            dispatch.next().run();
            assertTrue(appender.events.stream().anyMatch(event -> event.getThrown() == stoppedFailure));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void stoppedGenerationFutureCancellationDoesNotBecomeAnError() throws Exception {
        CapturingAppender appender = new CapturingAppender();
        Logger logger = (Logger) LogManager.getLogger(BotTickService.class);
        appender.start();
        logger.addAppender(appender);
        try (Dispatch dispatch = new Dispatch()) {
            BotTickService.register(910, () -> {
                CompletableFuture<Void> promise = new CompletableFuture<>();
                BotTickService.stop();
                promise.cancel(false);
                promise.join();
            }, 0, 1);
            dispatch.next().run();
            assertTrue(appender.events.isEmpty());
            assertTrue(BotTickService.awaitQuiescence(1, TimeUnit.SECONDS));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void runningDiagnosticsCaptureABoundedPeakSnapshot() throws Exception {
        CountDownLatch entered = new CountDownLatch(9);
        CountDownLatch release = new CountDownLatch(1);
        try (Dispatch dispatch = new Dispatch()) {
            for (int id = 920; id < 929; id++) {
                BotTickService.register(id, () -> { entered.countDown(); await(release); }, 0, 1);
            }
            for (int index = 0; index < 9; index++) Thread.ofVirtual().start(dispatch.next());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            BotTickService.stop();
            String snapshot = BotTickService.runningDiagnostics();
            assertTrue(snapshot.startsWith("running=9"));
            assertEquals(8, snapshot.lines().filter(line -> line.startsWith("botId=")).count());
            assertTrue(snapshot.lines().filter(line -> line.startsWith("  at ")).count() <= 64);
            release.countDown();
            assertTrue(BotTickService.awaitQuiescence(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test callback release timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** Capture submitted bodies without starting the process-wide scheduler pools. */
    private static final class Dispatch implements AutoCloseable {
        final List<Runnable> queued = new ArrayList<>();
        final MockedStatic<ExecutorServiceManager> executor = mockStatic(ExecutorServiceManager.class);
        final AtomicBoolean driver;
        final boolean previouslyStarted;

        Dispatch() throws Exception {
            Field field = BotTickService.class.getDeclaredField("DRIVER_STARTED");
            field.setAccessible(true);
            driver = (AtomicBoolean) field.get(null);
            previouslyStarted = driver.getAndSet(false);
            executor.when(ExecutorServiceManager::getScheduledExecutorService)
                    .thenReturn(mock(ScheduledExecutorService.class));
            executor.when(() -> ExecutorServiceManager.runAsync(any(Runnable.class)))
                    .thenAnswer(call -> { queued.add(call.getArgument(0)); return null; });
        }

        Runnable next() throws Exception {
            Method drive = BotTickService.class.getDeclaredMethod("drive");
            drive.setAccessible(true);
            drive.invoke(null);
            assertFalse(queued.isEmpty());
            return queued.removeFirst();
        }

        @Override
        public void close() {
            executor.close();
            driver.set(previouslyStarted);
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        CapturingAppender() {
            super("macro-lifecycle-test", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }
        @Override
        public void append(LogEvent event) { events.add(event.toImmutable()); }
    }
}
