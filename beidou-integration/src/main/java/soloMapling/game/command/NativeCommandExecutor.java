package soloMapling.game.command;

import org.gms.client.Client;
import org.gms.client.command.CommandsExecutor;
import org.gms.util.ThreadLocalUtil;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One native command entry shared by script bindings and administrator transports. */
public final class NativeCommandExecutor {
    public static final int MAX_COMMAND_CHARACTERS = 16_384;

    private NativeCommandExecutor() { }

    /** Only transport-sized text is checked here; syntax and authority remain native. */
    public static void validateCommand(String commandText) {
        if (commandText == null || commandText.isBlank()) {
            throw new IllegalArgumentException("invalid_command_text");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(commandText)) {
            throw new IllegalArgumentException("invalid_command_unicode");
        }
        if (commandText.codePointCount(0, commandText.length()) > MAX_COMMAND_CHARACTERS) {
            throw new IllegalArgumentException("command_text_too_long");
        }
    }

    /**
     * The native API is void and can return after a private refusal message. RETURNED
     * therefore describes only this invocation, never a successful game-side effect.
     */
    public static Result execute(Client client, String commandText) {
        validateCommand(commandText);
        if (client == null) {
            throw new IllegalArgumentException("native_command_client_unavailable");
        }
        Object player;
        try {
            player = client.getPlayer();
        } catch (RuntimeException ignored) {
            return unknown(false, "native_handler_unavailable");
        } catch (Error failure) {
            rethrowFatal(failure);
            return unknown(false, "native_handler_unavailable");
        }
        if (player == null) throw new IllegalArgumentException("native_command_client_unavailable");
        CommandsExecutor executor;
        Client previous;
        try {
            executor = CommandsExecutor.getInstance();
            if (executor == null) return unknown(false, "native_handler_unavailable");
            previous = ThreadLocalUtil.getCurrentClient();
        } catch (RuntimeException ignored) {
            return unknown(false, "native_handler_unavailable");
        } catch (Error failure) {
            rethrowFatal(failure);
            return unknown(false, "native_handler_unavailable");
        }
        boolean invoked = false;
        try {
            ThreadLocalUtil.setCurrentClient(client);
            invoked = true;
            executor.handle(client, commandText);
            return new Result("RETURNED", true, true, "UNREPORTED", "native_handler_returned");
        } catch (RuntimeException ignored) {
            return unknown(invoked, invoked ? "native_handler_failed" : "native_handler_unavailable");
        } catch (Error failure) {
            rethrowFatal(failure);
            return unknown(invoked, invoked ? "native_handler_failed" : "native_handler_unavailable");
        } finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        }
    }

    private static Result unknown(boolean invoked, String reason) {
        return new Result("UNKNOWN", invoked, false, "UNREPORTED", reason);
    }

    private static void rethrowFatal(Error failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    /** No native objects, text or exception details are retained in an invocation fact. */
    public record Result(String status, boolean nativeHandlerInvoked, boolean nativeHandlerReturned,
                         String commandOutcome, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", status);
            value.put("native_handler_invoked", nativeHandlerInvoked);
            value.put("native_handler_returned", nativeHandlerReturned);
            value.put("command_outcome", commandOutcome);
            value.put("reason", reason);
            return Collections.unmodifiableMap(value);
        }
    }
}
