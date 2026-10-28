package soloMapling.game.command;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.client.command.CommandsExecutor;
import org.gms.config.GameConfig;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Minimal shared script/MCP constraints: text limits and restoration after native failures. */
class NativeCommandExecutorTest {
    private CommandsExecutor nativeExecutor;
    private Map<String, Command> originalCommands;
    private Client client;
    private Character character;
    private Client originalThreadClient;

    @BeforeAll
    static void nativeStatics() { CharacterTestContext.install(); }

    @BeforeEach
    void setup() {
        originalThreadClient = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.removeCurrentClient();
        nativeExecutor = CommandsExecutor.getInstance();
        originalCommands = new HashMap<>(nativeExecutor.getRegisteredCommands());
        nativeExecutor.getRegisteredCommands().clear();
        client = mock(Client.class);
        character = mock(Character.class);
        when(client.getPlayer()).thenReturn(character);
        when(client.tryacquireClient()).thenReturn(true);
        when(character.getMapId()).thenReturn(10000);
        when(character.getName()).thenReturn("PlainClientFixture");
    }

    @AfterEach
    void restoreNativeRegistry() {
        nativeExecutor.getRegisteredCommands().clear();
        nativeExecutor.getRegisteredCommands().putAll(originalCommands);
        if (originalThreadClient == null) ThreadLocalUtil.removeCurrentClient();
        else ThreadLocalUtil.setCurrentClient(originalThreadClient);
    }

    @Test
    void onlyTextValidityAndUnicodeCodePointBudgetAreChecked() {
        for (String invalid : new String[]{null, "", " \t\n"}) {
            assertEquals("invalid_command_text", assertThrows(IllegalArgumentException.class,
                    () -> NativeCommandExecutor.validateCommand(invalid)).getMessage());
        }
        assertDoesNotThrow(() -> NativeCommandExecutor.validateCommand("😀".repeat(16_384)));
        for (String malformed : new String[]{"@text\ud800", "@text\udc00"}) {
            assertEquals("invalid_command_unicode", assertThrows(IllegalArgumentException.class,
                    () -> NativeCommandExecutor.validateCommand(malformed)).getMessage());
        }
        assertEquals("command_text_too_long", assertThrows(IllegalArgumentException.class,
                () -> NativeCommandExecutor.validateCommand("😀".repeat(16_385))).getMessage());
        assertDoesNotThrow(() -> NativeCommandExecutor.validateCommand("  /AnyName ARG  "));
    }

    @Test
    void recoverableNativeErrorsAreUnknownAndRestoreBothPriorClientStates() {
        for (Error failure : List.of(new AssertionError("private assertion text"),
                new NoClassDefFoundError("private linkage text"))) {
            Client previous = failure instanceof AssertionError ? mock(Client.class) : null;
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
            register("errornative", 0, "native error", (actual, args) -> {
                assertSame(client, ThreadLocalUtil.getCurrentClient());
                throw failure;
            });
            try (MockedStatic<GameConfig> ignored = mockStatic(GameConfig.class)) {
                NativeCommandExecutor.Result result = NativeCommandExecutor.execute(client, "@errornative");
                assertEquals("UNKNOWN", result.status());
                assertTrue(result.nativeHandlerInvoked());
                assertFalse(result.nativeHandlerReturned());
                assertEquals("UNREPORTED", result.commandOutcome());
                assertFalse(result.toMap().toString().contains("private"));
            }
            assertSame(previous, ThreadLocalUtil.getCurrentClient());
        }
        verify(client, times(2)).releaseClient();
    }

    @Test
    void fatalNativeErrorsPropagateButStillRestoreThePriorClient() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        for (Error failure : List.of(new InternalError("fatal test"), new ThreadDeath())) {
            register("fatalnative", 0, "native fatal", (actual, args) -> {
                assertSame(client, ThreadLocalUtil.getCurrentClient());
                throw failure;
            });
            try (MockedStatic<GameConfig> ignored = mockStatic(GameConfig.class)) {
                assertSame(failure, assertThrows(failure.getClass(),
                        () -> NativeCommandExecutor.execute(client, "@fatalnative")));
            }
            assertSame(previous, ThreadLocalUtil.getCurrentClient());
        }
        verify(client, times(2)).releaseClient();
    }

    @Test
    void failuresBeforeHandlerInvocationDoNotInventDispatch() {
        assertEquals("native_command_client_unavailable", assertThrows(IllegalArgumentException.class,
                () -> NativeCommandExecutor.execute(null, "@time")).getMessage());
        when(client.getPlayer()).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> NativeCommandExecutor.execute(client, "@time"));
        when(client.getPlayer()).thenReturn(character);
        try (MockedStatic<CommandsExecutor> singleton = mockStatic(CommandsExecutor.class)) {
            singleton.when(CommandsExecutor::getInstance).thenThrow(new IllegalStateException("private initialization"));
            NativeCommandExecutor.Result result = NativeCommandExecutor.execute(client, "@time");
            assertEquals("UNKNOWN", result.status());
            assertFalse(result.nativeHandlerInvoked());
            assertFalse(result.nativeHandlerReturned());
            assertFalse(result.toMap().toString().contains("private"));
            singleton.when(CommandsExecutor::getInstance).thenThrow(new ExceptionInInitializerError("private init error"));
            NativeCommandExecutor.Result initError = NativeCommandExecutor.execute(client, "@time");
            assertEquals("UNKNOWN", initError.status());
            assertFalse(initError.nativeHandlerInvoked());
            assertFalse(initError.nativeHandlerReturned());
            assertFalse(initError.toMap().toString().contains("private"));
        }
    }

    private void register(String name, int rank, String description, NativeBody body) {
        Command command = new Command() {
            @Override public void execute(Client selected, String[] arguments) { body.run(selected, arguments); }
        };
        command.setRank(rank); command.setDescription(description);
        nativeExecutor.getRegisteredCommands().put(name, command);
    }

    @FunctionalInterface
    private interface NativeBody { void run(Client client, String[] arguments); }
}
