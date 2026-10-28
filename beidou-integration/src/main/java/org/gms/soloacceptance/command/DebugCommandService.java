package org.gms.soloacceptance.command;

import org.gms.soloacceptance.ObservationException;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import soloMapling.game.command.NativeCommandExecutor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Independent command transport; native command rules and permissions remain in the server. */
@Service
@ConditionalOnProperty(prefix = "solomapling.acceptance",
        name = {"enabled", "actors-enabled", "debug-commands-enabled"}, havingValue = "true", matchIfMissing = false)
public final class DebugCommandService {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final int HISTORY_LIMIT = 256;
    private static final Outcome UNKNOWN = new Outcome(
            "UNKNOWN", null, null, "UNREPORTED", "command_execution_unknown", null, null);
    private final ActorProfiles profiles;
    private final Backend backend;
    private final String generation;
    private final int historyLimit;
    private final Map<Key, Entry> history = new LinkedHashMap<>();

    @FunctionalInterface
    public interface Backend {
        Outcome execute(ActorProfiles.Resolved profile, String command);
        default Map<String, Object> describeCommands(String name) {
            return NativeCommandExecutor.describeCommands(name);
        }
    }

    /** The receipt describes the handler call, never a command-specific gameplay outcome. */
    public record Outcome(String status, Boolean nativeHandlerInvoked, Boolean nativeHandlerReturned,
                          String commandOutcome, String reason, Integer characterId, Integer world) { }

    private record Key(String owner, String requestId) { }
    private record Signature(String profileId, String expectedGeneration, String commandSha256) { }

    private static final class Entry {
        final String commandId = "command-" + UUID.randomUUID();
        final String requestId;
        final Signature signature;
        volatile Outcome outcome;

        Entry(String requestId, Signature signature) {
            this.requestId = requestId;
            this.signature = signature;
        }
    }

    @Autowired
    public DebugCommandService(ActorProfiles profiles, NativeDebugCommandBackend backend) {
        this(profiles, backend, "command-generation-" + UUID.randomUUID(), HISTORY_LIMIT);
    }

    DebugCommandService(ActorProfiles profiles, Backend backend, String generation, int historyLimit) {
        this.profiles = Objects.requireNonNull(profiles);
        this.backend = Objects.requireNonNull(backend);
        this.generation = Objects.requireNonNull(generation);
        if (historyLimit < 1) throw new IllegalArgumentException("history limit must be positive");
        this.historyLimit = historyLimit;
    }

    public Map<String, Object> publicCapabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("enabled_tools", List.of("game_command_help", "game_command"));
        result.put("generic_command", true);
        result.put("command_dispatcher", "CommandsExecutor.handle");
        result.put("command_outcome", "UNREPORTED");
        result.put("command_generation", generation);
        result.put("administrator_required", true);
        result.put("runtime_required", false);
        result.put("target", "configured_online_ordinary_profile");
        result.put("history_limit", historyLimit);
        result.put("request_history", "owner_scoped_process_lifetime_no_eviction");
        result.put("recovery", "repeat_same_request_id_and_arguments_with_original_command_generation");
        result.put("cancellation_supported", false);
        result.put("maximum_command_characters", NativeCommandExecutor.MAX_COMMAND_CHARACTERS);
        result.put("character_counting", "unicode_code_points");
        result.put("native_permissions", "unchanged_client_character_permissions");
        result.put("completion", "RETURNED means only native handler returned; inspect native feedback and game state");
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> help(String command) {
        Map<String, Object> result = new LinkedHashMap<>(publicCapabilities());
        try {
            result.putAll(backend.describeCommands(command));
        } catch (IllegalArgumentException unknown) {
            throw invalid("native_command_not_registered");
        } catch (RuntimeException unavailable) {
            throw new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, "native_command_catalog_unavailable");
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> execute(String owner, String requestId, DebugCommandRequest request) {
        if (owner == null || owner.isBlank()) throw invalid("invalid_command_owner");
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw invalid("invalid_request_id");
        if (request == null) throw invalid("malformed_command");
        if (!generation.equals(request.expectedCommandGeneration())) {
            throw new ObservationException(HttpStatus.CONFLICT, "command_generation_changed");
        }
        try { NativeCommandExecutor.validateCommand(request.command()); }
        catch (IllegalArgumentException invalid) { throw invalid("invalid_command_text"); }
        Signature signature = new Signature(request.profileId(), request.expectedCommandGeneration(), digest(request.command()));
        Key key = new Key(owner, requestId);
        Entry entry;
        synchronized (history) {
            Entry previous = history.get(key);
            if (previous != null) return restore(previous, signature);
        }
        // Restoration only needs the immutable signature, not a still-online profile.
        ActorProfiles.Resolved profile = profiles.resolve(request.profileId(), null, null);
        synchronized (history) {
            Entry previous = history.get(key);
            if (previous != null) return restore(previous, signature);
            if (history.size() >= historyLimit) {
                throw new ObservationException(HttpStatus.TOO_MANY_REQUESTS, "command_history_full");
            }
            entry = new Entry(requestId, signature);
            history.put(key, entry);
        }
        try {
            Outcome outcome = backend.execute(profile, request.command());
            entry.outcome = validOutcome(outcome) ? outcome : unknown();
        } catch (RuntimeException ignored) {
            // Finalization below consumes unknown effects without retaining exception contents.
        } catch (Error failed) {
            if (failed instanceof VirtualMachineError || failed instanceof ThreadDeath) throw failed;
        } finally {
            // Every exited invocation has a terminal record, including fatal errors propagated
            // to the transport. A later retry must never treat an exited body as still RUNNING.
            if (entry.outcome == null) entry.outcome = unknown();
        }
        return snapshot(entry);
    }

    private Map<String, Object> restore(Entry previous, Signature signature) {
        if (!previous.signature.equals(signature)) {
            throw new ObservationException(HttpStatus.CONFLICT, "request_id_conflict");
        }
        return snapshot(previous);
    }

    private static boolean validOutcome(Outcome outcome) {
        return outcome != null && List.of("RETURNED", "FAILED", "UNKNOWN").contains(outcome.status());
    }

    private static Outcome unknown() {
        return UNKNOWN;
    }

    private Map<String, Object> snapshot(Entry entry) {
        Outcome outcome = entry.outcome;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command_generation", generation);
        result.put("command_id", entry.commandId);
        result.put("request_id", entry.requestId);
        result.put("profile_id", entry.signature.profileId());
        result.put("command_sha256", entry.signature.commandSha256());
        result.put("status", outcome == null ? "RUNNING" : outcome.status());
        result.put("terminal", outcome != null);
        result.put("native_handler_invoked", outcome == null ? null : outcome.nativeHandlerInvoked());
        result.put("native_handler_returned", outcome == null ? null : outcome.nativeHandlerReturned());
        result.put("command_outcome", outcome == null ? "UNREPORTED" : outcome.commandOutcome());
        result.put("reason", outcome == null ? "command_running" : outcome.reason());
        result.put("character_id", outcome == null ? null : outcome.characterId());
        result.put("world", outcome == null ? null : outcome.world());
        return Collections.unmodifiableMap(result);
    }

    private static String digest(String command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(command.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable");
        }
    }

    private static ObservationException invalid(String reason) {
        return new ObservationException(HttpStatus.BAD_REQUEST, reason);
    }
}
