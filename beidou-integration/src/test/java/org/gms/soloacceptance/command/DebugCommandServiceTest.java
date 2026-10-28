package org.gms.soloacceptance.command;

import org.gms.soloacceptance.ObservationException;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebugCommandServiceTest {
    private static final String GENERATION = "command-generation-test";

    @Test
    void completedRequestRestoresOriginalFactsWithoutLookingUpAnOfflineOrRemovedProfile() {
        AtomicInteger calls = new AtomicInteger();
        ActorProfiles profiles = profiles();
        DebugCommandService service = service(profiles, (profile, itemId, quantity) -> {
            calls.incrementAndGet();
            return success(4, 7);
        }, 2);
        DebugCommandRequest request = request(3);
        Map<String, Object> first = service.execute("operator", "give-1", request);
        profiles.setActorProfiles(Map.of());
        Map<String, Object> repeated = service.execute("operator", "give-1", request);

        assertEquals(first, repeated);
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", repeated.get("status"));
        assertEquals(4L, repeated.get("quantity_before"));
        assertEquals(7L, repeated.get("quantity_after"));
        assertEquals(false, repeated.get("persisted_state_verified"));
        assertThrows(UnsupportedOperationException.class, () -> repeated.put("quantity_after", 100));
    }

    @Test
    void inFlightRetryReadsRunningRecordAndNeverDispatchesAgain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, itemId, quantity) -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return success(0, 1);
        }, 2);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> service.execute("operator", "running-1", request(1)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Map<String, Object> running = service.execute("operator", "running-1", request(1));
                assertEquals("RUNNING", running.get("status"));
                assertEquals(false, running.get("terminal"));
                assertNull(running.get("native_dispatched"));
                assertEquals(1, calls.get());
                finish.countDown();
                Map<String, Object> done = original.get(5, TimeUnit.SECONDS);
                assertEquals(running.get("command_id"), done.get("command_id"));
                assertEquals("SUCCEEDED", done.get("status"));
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void requestIdConflictsAndOwnerNamespacesAreSeparate() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, itemId, quantity) -> {
            calls.incrementAndGet();
            return success(0, quantity);
        }, 4);
        service.execute("operator-a", "same-id", request(1));
        ObservationException conflict = assertThrows(ObservationException.class,
                () -> service.execute("operator-a", "same-id", request(2)));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("request_id_conflict", conflict.reason());
        service.execute("operator-b", "same-id", request(2));
        assertEquals(2, calls.get());
    }

    @Test
    void fullHistoryRejectsNewRequestsAndDoesNotForgetOldEffects() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, itemId, quantity) -> {
            calls.incrementAndGet();
            return success(0, quantity);
        }, 1);
        Map<String, Object> original = service.execute("operator", "kept", request(1));
        ObservationException full = assertThrows(ObservationException.class,
                () -> service.execute("operator", "new", request(1)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        assertEquals("command_history_full", full.reason());
        assertEquals(original, service.execute("operator", "kept", request(1)));
        assertEquals(1, calls.get());
    }

    @Test
    void nativeFailurePartialChangeAndUnexpectedExceptionAreNeverReplayed() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, itemId, quantity) -> switch (calls.incrementAndGet()) {
            case 1 -> new DebugCommandService.Outcome("FAILED", false, "target_offline", null, null, null, null, null);
            case 2 -> new DebugCommandService.Outcome("UNKNOWN", true, "partial_inventory_change", 0L, 1L, "USE", 4, 0);
            default -> throw new IllegalStateException("sensitive account secret");
        }, 4);
        Map<String, Object> failed = service.execute("operator", "failed", request(2));
        Map<String, Object> partial = service.execute("operator", "partial", request(2));
        Map<String, Object> unexpected = service.execute("operator", "unexpected", request(2));
        assertEquals("FAILED", failed.get("status"));
        assertEquals(false, failed.get("native_dispatched"));
        assertEquals("UNKNOWN", partial.get("status"));
        assertEquals(1L, partial.get("quantity_after"));
        assertEquals("UNKNOWN", unexpected.get("status"));
        assertNull(unexpected.get("native_dispatched"));
        assertFalse(unexpected.toString().contains("sensitive"));
        assertEquals(failed, service.execute("operator", "failed", request(2)));
        assertEquals(partial, service.execute("operator", "partial", request(2)));
        assertEquals(unexpected, service.execute("operator", "unexpected", request(2)));
        assertEquals(3, calls.get());
    }

    @Test
    void restartedProcessRejectsOriginalGenerationEvenWithAnEmptyHistory() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService fresh = new DebugCommandService(profiles(), (profile, item, quantity) -> {
            calls.incrementAndGet();
            return success(0, quantity);
        }, "different-process", 4);
        ObservationException stale = assertThrows(ObservationException.class,
                () -> fresh.execute("operator", "original-id", request(1)));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("command_generation_changed", stale.reason());
        assertEquals(0, calls.get());
    }

    @Test
    void invalidInputsAndUnknownProfilesDoNotConsumeCommandHistory() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, item, quantity) -> {
            calls.incrementAndGet();
            return success(0, quantity);
        }, 1);
        for (DebugCommandRequest invalid : new DebugCommandRequest[]{
                new DebugCommandRequest("primary", GENERATION, "give", null),
                new DebugCommandRequest("primary", GENERATION, "shutdown", new DebugCommandRequest.Parameters(2000000, 1)),
                new DebugCommandRequest("primary", GENERATION, "give", new DebugCommandRequest.Parameters(0, 1)),
                request(0), request(-1), request(32768),
                new DebugCommandRequest("unconfigured", GENERATION, "give", new DebugCommandRequest.Parameters(2000000, 1))}) {
            assertThrows(ObservationException.class, () -> service.execute("operator", "invalid", invalid));
        }
        assertThrows(ObservationException.class, () -> service.execute("operator", "bad id", request(1)));
        assertThrows(ObservationException.class, () -> service.execute("", "valid", request(1)));
        assertEquals("SUCCEEDED", service.execute("operator", "valid", request(1)).get("status"));
        assertEquals(1, calls.get());
    }

    @Test
    void helpAndCapabilitiesDescribeOnlyActualIndependentGiveSurface() {
        DebugCommandService service = service(profiles(), (profile, item, quantity) -> {
            fail("help must not enter native backend");
            return null;
        }, 2);
        assertEquals(GENERATION, service.help(null).get("command_generation"));
        assertEquals(true, service.publicCapabilities().get("enabled"));
        assertEquals(false, service.publicCapabilities().get("runtime_required"));
        assertEquals(service.help(null), service.help("give"));
        assertEquals("unsupported_command", assertThrows(ObservationException.class,
                () -> service.help("setgmlevel")).reason());
    }

    private static DebugCommandService service(ActorProfiles profiles, DebugCommandService.Backend backend, int limit) {
        return new DebugCommandService(profiles, backend, GENERATION, limit);
    }

    private static DebugCommandRequest request(int quantity) {
        return new DebugCommandRequest("primary", GENERATION, "give", new DebugCommandRequest.Parameters(2000000, quantity));
    }

    private static DebugCommandService.Outcome success(long before, long after) {
        return new DebugCommandService.Outcome("SUCCEEDED", true, "inventory_increase_verified", before, after, "USE", 4, 0);
    }

    static ActorProfiles profiles() {
        ActorProfiles.Profile profile = new ActorProfiles.Profile();
        profile.setAccountName("mcp_primary");
        profile.setAccountPassword("local-test-only");
        profile.setCharacterName("McpPrimary");
        ActorProfiles profiles = new ActorProfiles();
        profiles.setActorProfiles(Map.of("primary", profile));
        return profiles;
    }
}
