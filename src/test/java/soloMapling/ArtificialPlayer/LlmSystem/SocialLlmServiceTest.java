package soloMapling.ArtificialPlayer.LlmSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialLlmServiceTest {

    @AfterEach
    void clearClient() {
        SocialLlmService.configure((LlmClient) null);
    }

    @Test
    void fakeClientSuccessReturnsNormalizedText() throws Exception {
        LlmRequest request = request();
        AtomicReference<LlmRequest> received = new AtomicReference<>();
        SocialLlmService.configure(sent -> {
            received.set(sent);
            return CompletableFuture.completedFuture("  hello\n  mapler  ");
        });
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();

        SocialLlmService.completeAsync(request, 1_000, value -> {
            reply.set(value);
            completed.countDown();
        }, () -> {
            failures.incrementAndGet();
            completed.countDown();
        });

        assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertSame(request, received.get());
        assertEquals("hello mapler", reply.get());
        assertEquals(0, failures.get());
    }

    @Test
    void emptyResponseRunsFailureCallback() throws Exception {
        SocialLlmService.configure(request -> CompletableFuture.completedFuture(" \n "));
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        SocialLlmService.completeAsync(request(), 1_000,
                ignored -> {
                    successes.incrementAndGet();
                    completed.countDown();
                },
                () -> {
                    failures.incrementAndGet();
                    completed.countDown();
                });

        assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, successes.get());
        assertEquals(1, failures.get());
    }

    @Test
    void exceptionalCompletionRunsFailureCallback() throws Exception {
        SocialLlmService.configure(request ->
                CompletableFuture.failedFuture(new IllegalStateException("provider unavailable")));
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        SocialLlmService.completeAsync(request(), 1_000,
                ignored -> {
                    successes.incrementAndGet();
                    completed.countDown();
                },
                () -> {
                    failures.incrementAndGet();
                    completed.countDown();
                });

        assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, successes.get());
        assertEquals(1, failures.get());
    }

    @Test
    void timeoutRunsFailureCallbackOnce() throws Exception {
        CompletableFuture<String> pending = new CompletableFuture<>();
        SocialLlmService.configure(request -> pending);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        SocialLlmService.completeAsync(request(), 25,
                ignored -> {
                    successes.incrementAndGet();
                    completed.countDown();
                },
                () -> {
                    failures.incrementAndGet();
                    completed.countDown();
                });

        assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, successes.get());
        assertEquals(1, failures.get());
        assertFalse(pending.complete("late reply"));
        assertEquals(0, successes.get());
        assertEquals(1, failures.get());
    }

    @Test
    void pendingCompletionDoesNotBlockCallerAndShutdownDisablesClient() {
        CompletableFuture<String> pending = new CompletableFuture<>();
        SocialLlmService.configure(request -> pending);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        SocialLlmService.completeAsync(request(), 1000,
                ignored -> successes.incrementAndGet(), failures::incrementAndGet);

        assertEquals(0, successes.get());
        assertEquals(0, failures.get());
        pending.complete("reply");
        assertEquals(1, successes.get());
        SocialLlmService.shutdown();
        assertFalse(SocialLlmService.isEnabled());
    }

    @Test
    void synchronousProviderFailureAndMissingClientUseFallback() {
        AtomicInteger failures = new AtomicInteger();
        SocialLlmService.configure(request -> {
            throw new IllegalStateException("provider unavailable");
        });
        SocialLlmService.completeAsync(request(), 1000,
                ignored -> { }, failures::incrementAndGet);
        assertEquals(1, failures.get());

        SocialLlmService.shutdown();
        SocialLlmService.completeAsync(request(), 1000,
                ignored -> { }, failures::incrementAndGet);
        assertEquals(2, failures.get());
    }

    private static LlmRequest request() {
        return new LlmRequest(
                List.of(LlmMessage.system("Be concise."), LlmMessage.user("Hello")),
                "fake-model",
                32,
                0.5);
    }
}
