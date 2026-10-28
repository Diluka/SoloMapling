package org.gms.soloacceptance.command;

import org.gms.soloacceptance.ObservationException;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DebugCommandServiceTest {
    private static final String GENERATION = "command-generation-test";

    @Test
    void returnedRequestRestoresItsReceiptAfterTheProfileWasRemoved() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> originalLine = new AtomicReference<>();
        ActorProfiles profiles = profiles();
        DebugCommandService service = service(profiles, (profile, command) -> {
            calls.incrementAndGet(); originalLine.set(command); return returned();
        }, 2);
        DebugCommandRequest request = request("!some-command  Mixed CASE  argument");
        Map<String, Object> first = service.execute("operator", "command-1", request);
        profiles.setActorProfiles(Map.of());
        Map<String, Object> repeated = service.execute("operator", "command-1", request);
        assertEquals(first, repeated);
        assertEquals(request.command(), originalLine.get());
        assertEquals(1, calls.get());
        assertEquals("RETURNED", repeated.get("status"));
        assertEquals("UNREPORTED", repeated.get("command_outcome"));
        assertEquals(true, repeated.get("native_handler_invoked"));
        assertEquals(true, repeated.get("native_handler_returned"));
        assertFalse(repeated.containsKey("quantity_after"));
        assertFalse(repeated.containsKey("persisted_state_verified"));
        assertThrows(UnsupportedOperationException.class, () -> repeated.put("status", "SUCCEEDED"));
    }

    @Test
    void historyKeepsOnlySignatureAndReceiptRatherThanCommandText() throws Exception {
        String secret = "@some-command private-command-argument";
        DebugCommandService service = service(profiles(), (profile, command) -> returned(), 2);
        Map<String, Object> response = service.execute("operator", "private", request(secret));
        assertFalse(response.toString().contains(secret));
        assertFalse(response.containsKey("command"));
        assertEquals(64, ((String) response.get("command_sha256")).length());
        Field history = DebugCommandService.class.getDeclaredField("history");
        history.setAccessible(true);
        for (Object entry : ((Map<?, ?>) history.get(service)).values()) {
            for (Field field : entry.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(entry);
                assertFalse(value instanceof DebugCommandRequest);
                assertFalse(String.valueOf(value).contains(secret));
                assertFalse(String.valueOf(value).contains("private-command-argument"));
            }
        }
    }

    @Test
    void inFlightRetryReturnsRunningWithoutDispatchingAgain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, command) -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return returned();
        }, 2);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> service.execute("operator", "running-1", request("@time")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Map<String, Object> running = service.execute("operator", "running-1", request("@time"));
                assertEquals("RUNNING", running.get("status"));
                assertEquals(false, running.get("terminal"));
                assertNull(running.get("native_handler_invoked"));
                assertNull(running.get("native_handler_returned"));
                assertEquals(1, calls.get());
                finish.countDown();
                assertEquals(running.get("command_id"), original.get(5, TimeUnit.SECONDS).get("command_id"));
            } finally { finish.countDown(); }
        }
    }

    @Test
    void changedRawTextOrProfileConflictsButOtherOwnersHaveSeparateNamespaces() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, command) -> { calls.incrementAndGet(); return returned(); }, 4);
        service.execute("operator-a", "same-id", request("@time"));
        for (DebugCommandRequest changed : List.of(request("@time "), request("@TIME"),
                new DebugCommandRequest("other", GENERATION, "@time"))) {
            ObservationException conflict = assertThrows(ObservationException.class,
                    () -> service.execute("operator-a", "same-id", changed));
            assertEquals(HttpStatus.CONFLICT, conflict.status());
            assertEquals("request_id_conflict", conflict.reason());
        }
        service.execute("operator-b", "same-id", request("@time "));
        assertEquals(2, calls.get());
    }

    @Test
    void fullHistoryRejectsNewRequestsWithoutForgettingOriginalReceipts() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, command) -> { calls.incrementAndGet(); return returned(); }, 1);
        Map<String, Object> original = service.execute("operator", "kept", request("@time"));
        ObservationException full = assertThrows(ObservationException.class,
                () -> service.execute("operator", "new", request("@time")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        assertEquals(original, service.execute("operator", "kept", request("@time")));
        assertEquals(1, calls.get());
    }

    @Test
    void failedUnknownAndUnexpectedBackendExceptionsRemainConsumed() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, command) -> switch (calls.incrementAndGet()) {
            case 1 -> new DebugCommandService.Outcome("FAILED", false, false, "NOT_INVOKED", "target_offline", null, null);
            case 2 -> new DebugCommandService.Outcome("UNKNOWN", true, false, "UNREPORTED", "native_handler_failed", 4, 0);
            default -> throw new IllegalStateException("sensitive account secret");
        }, 4);
        for (String id : List.of("failed", "unknown", "unexpected")) {
            Map<String, Object> first = service.execute("operator", id, request("@command"));
            assertEquals(first, service.execute("operator", id, request("@command")));
            assertFalse(first.toString().contains("sensitive"));
            assertNotEquals("SUCCEEDED", first.get("status"));
        }
        assertEquals(3, calls.get());
    }

    @Test
    void recoverableErrorsAlwaysLeaveAnUnknownReceiptAndAreNotReplayed() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, command) -> {
            if (calls.incrementAndGet() == 1) throw new AssertionError("private assertion detail");
            throw new LinkageError("private linkage detail");
        }, 2);
        for (String id : List.of("assertion-error", "linkage-error")) {
            Map<String, Object> first = service.execute("operator", id, request("@command"));
            assertEquals("UNKNOWN", first.get("status"));
            assertEquals(true, first.get("terminal"));
            assertNull(first.get("native_handler_invoked"));
            assertFalse(first.toString().contains("private"));
            assertEquals(first, service.execute("operator", id, request("@command")));
        }
        assertEquals(2, calls.get());
    }

    @Test
    void fatalErrorsPropagateButTheAcceptedRequestStillSettlesUnknown() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, command) -> {
            calls.incrementAndGet();
            throw new VirtualMachineError("private fatal detail") { };
        }, 1);
        assertThrows(VirtualMachineError.class,
                () -> service.execute("operator", "fatal", request("@command")));
        Map<String, Object> restored = service.execute("operator", "fatal", request("@command"));
        assertEquals("UNKNOWN", restored.get("status"));
        assertEquals(true, restored.get("terminal"));
        assertNull(restored.get("native_handler_invoked"));
        assertFalse(restored.toString().contains("private"));
        assertEquals(1, calls.get());
    }

    @Test
    void aFreshProcessRejectsTheOriginalGenerationBeforeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService fresh = new DebugCommandService(profiles(), (profile, command) -> {
            calls.incrementAndGet(); return returned();
        }, "different-process", 4);
        ObservationException stale = assertThrows(ObservationException.class,
                () -> fresh.execute("operator", "original-id", request("@time")));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("command_generation_changed", stale.reason());
        assertEquals(0, calls.get());
    }

    @Test
    void invalidInputsDoNotConsumeHistoryAndValidationDoesNotFilterCommandNames() {
        AtomicInteger calls = new AtomicInteger();
        DebugCommandService service = service(profiles(), (profile, command) -> { calls.incrementAndGet(); return returned(); }, 1);
        for (String text : new String[]{null, "", "  ", "x".repeat(16_385)}) {
            assertThrows(ObservationException.class, () -> service.execute("operator", "invalid", request(text)));
        }
        assertThrows(ObservationException.class, () -> service.execute("operator", "invalid",
                new DebugCommandRequest("unconfigured", GENERATION, "@time")));
        assertThrows(ObservationException.class, () -> service.execute("operator", "bad id", request("@time")));
        assertThrows(ObservationException.class, () -> service.execute("", "valid", request("@time")));
        assertEquals("RETURNED", service.execute("operator", "valid", request("!not-hardcoded-anywhere arg1 arg2")).get("status"));
        assertEquals(1, calls.get());
    }

    @Test
    void helpQueriesTheBackendCatalogButCapabilitiesDoNotScanIt() {
        AtomicInteger catalogs = new AtomicInteger();
        DebugCommandService.Backend backend = new DebugCommandService.Backend() {
            @Override public DebugCommandService.Outcome execute(ActorProfiles.Resolved profile, String command) {
                fail("help never executes commands"); return null;
            }
            @Override public Map<String, Object> describeCommands(String name) {
                catalogs.incrementAndGet();
                if ("missing".equals(name)) throw new IllegalArgumentException("not registered");
                return Map.of("commands", List.of(name == null ? "native-time" : name),
                        "command_details", List.of(Map.of("name", "native-time", "rank", 0, "description", "native description")));
            }
        };
        DebugCommandService service = service(profiles(), backend, 2);
        Map<String, Object> caps = service.publicCapabilities();
        assertEquals(0, catalogs.get());
        assertEquals(true, caps.get("generic_command"));
        assertEquals(false, caps.get("runtime_required"));
        assertFalse(caps.containsKey("give_inventory_types"));
        assertEquals(List.of("native-time"), service.help(null).get("commands"));
        assertEquals(List.of("custom"), service.help("custom").get("commands"));
        assertEquals("native_command_not_registered", assertThrows(ObservationException.class,
                () -> service.help("missing")).reason());
        assertEquals(3, catalogs.get());
    }

    private static DebugCommandService service(ActorProfiles profiles, DebugCommandService.Backend backend, int limit) {
        return new DebugCommandService(profiles, backend, GENERATION, limit);
    }
    private static DebugCommandRequest request(String command) {
        return new DebugCommandRequest("primary", GENERATION, command);
    }
    private static DebugCommandService.Outcome returned() {
        return new DebugCommandService.Outcome("RETURNED", true, true, "UNREPORTED", "native_handler_returned", 4, 0);
    }
    static ActorProfiles profiles() {
        ActorProfiles.Profile profile = new ActorProfiles.Profile();
        profile.setAccountName("mcp_primary"); profile.setAccountPassword("local-test-only"); profile.setCharacterName("McpPrimary");
        ActorProfiles profiles = new ActorProfiles(); profiles.setActorProfiles(Map.of("primary", profile)); return profiles;
    }
}
