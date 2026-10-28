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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    void inputIsForwardedUnchangedToExactlyOneNativeHandleCall() {
        CommandsExecutor selected = mock(CommandsExecutor.class);
        String text = "  /MiXeD Arg  保留原文  ";
        try (MockedStatic<CommandsExecutor> singleton = mockStatic(CommandsExecutor.class)) {
            singleton.when(CommandsExecutor::getInstance).thenReturn(selected);
            NativeCommandExecutor.Result result = NativeCommandExecutor.execute(client, text);
            verify(selected).handle(same(client), same(text));
            verifyNoMoreInteractions(selected);
            assertEquals("RETURNED", result.status());
            assertTrue(result.nativeHandlerInvoked());
            assertTrue(result.nativeHandlerReturned());
            assertEquals("UNREPORTED", result.commandOutcome());
        }
    }

    @Test
    void arbitraryRegisteredCommandsUseTheRealNativeParserAndReceiveNativeParameters() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String[]> parameters = new AtomicReference<>();
        register("anothernativecommand", 0, "native description", (actual, args) -> {
            assertSame(client, actual);
            assertSame(client, ThreadLocalUtil.getCurrentClient());
            calls.incrementAndGet(); parameters.set(args);
        });
        try (MockedStatic<GameConfig> ignored = mockStatic(GameConfig.class)) {
            NativeCommandExecutor.Result result = NativeCommandExecutor.execute(client,
                    "@AnotherNativeCommand Arg1 KEEPCase");
            assertEquals("RETURNED", result.status());
        }
        assertEquals(1, calls.get());
        assertArrayEquals(new String[]{"arg1", "keepcase"}, parameters.get());
        verify(character).setLastCommandMessage("Arg1 KEEPCase");
        verify(client).releaseClient();
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
    }

    @Test
    void nativeClientBindingIsRemovedWhenTheOriginalThreadHadNoClient() {
        assertNull(ThreadLocalUtil.getCurrentClient());
        register("threadlocalnative", 0, "native context", (actual, args) -> {
            assertSame(client, ThreadLocalUtil.getCurrentClient());
            ThreadLocalUtil.setCurrentClient(mock(Client.class));
        });
        try (MockedStatic<GameConfig> ignored = mockStatic(GameConfig.class)) {
            assertEquals("RETURNED", NativeCommandExecutor.execute(client, "@threadlocalnative").status());
        }
        assertNull(ThreadLocalUtil.getCurrentClient());
    }

    @Test
    void originalGmRejectionIsNotReportedAsCommandSuccess() {
        AtomicInteger effects = new AtomicInteger();
        register("restrictednative", 6, "native admin command", (actual, args) -> effects.incrementAndGet());
        try (MockedStatic<GameConfig> ignored = mockStatic(GameConfig.class)) {
            NativeCommandExecutor.Result result = NativeCommandExecutor.execute(client, "@restrictednative arg");
            assertEquals("RETURNED", result.status());
            assertEquals("UNREPORTED", result.commandOutcome());
            assertEquals(true, result.toMap().get("native_handler_returned"));
            assertFalse(result.toMap().containsKey("success"));
        }
        assertEquals(0, effects.get());
        verify(character).yellowMessage(anyString());
        verify(character, never()).setGMLevel(anyInt());
        verify(client).releaseClient();
    }

    @Test
    void unknownNamesAndNativeBusyReturnsAreLeftToTheNativeHandler() {
        try (MockedStatic<GameConfig> ignored = mockStatic(GameConfig.class)) {
            NativeCommandExecutor.Result missing = NativeCommandExecutor.execute(client, "@notregistered arg");
            assertEquals("RETURNED", missing.status());
            assertEquals("UNREPORTED", missing.commandOutcome());
        }
        verify(character).yellowMessage(anyString());
        when(client.tryacquireClient()).thenReturn(false);
        NativeCommandExecutor.Result busy = NativeCommandExecutor.execute(client, "@anything");
        assertEquals("RETURNED", busy.status());
        assertEquals("UNREPORTED", busy.commandOutcome());
        verify(character).dropMessage(eq(5), anyString());
    }

    @Test
    void nativeExceptionRetainsOnlyUnknownInvocationFacts() {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        register("throwingnative", 0, "throwing native", (actual, args) -> {
            assertSame(client, ThreadLocalUtil.getCurrentClient());
            throw new IllegalStateException("private-command-and-account-text");
        });
        try (MockedStatic<GameConfig> ignored = mockStatic(GameConfig.class)) {
            NativeCommandExecutor.Result result = NativeCommandExecutor.execute(client, "@throwingnative secret");
            assertEquals("UNKNOWN", result.status());
            assertTrue(result.nativeHandlerInvoked());
            assertFalse(result.nativeHandlerReturned());
            assertEquals("UNREPORTED", result.commandOutcome());
            assertEquals("native_handler_failed", result.reason());
            assertFalse(result.toMap().toString().contains("secret"));
            assertFalse(result.toMap().toString().contains("private"));
            assertThrows(UnsupportedOperationException.class, () -> result.toMap().put("status", "SUCCEEDED"));
        }
        verify(client).releaseClient();
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
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

    @Test
    void helpReflectsNativeRegistrationRankAndDescriptionWithoutChangingThem() {
        register("zeta", 4, "zeta description", (actual, args) -> fail("catalog must not execute"));
        register("alpha", 0, "alpha description", (actual, args) -> fail("catalog must not execute"));
        Map<String, Object> all = NativeCommandExecutor.describeCommands(null);
        assertEquals(List.of("alpha", "zeta"), all.get("commands"));
        assertEquals("native_registered_commands", all.get("source"));
        assertEquals(List.of(Map.of("name", "zeta", "rank", 4, "description", "zeta description")),
                NativeCommandExecutor.describeCommands("ZETA").get("command_details"));
        nativeExecutor.getRegisteredCommands().remove("zeta");
        assertEquals(List.of("alpha"), NativeCommandExecutor.describeCommands(null).get("commands"));
        assertEquals("native_command_not_registered", assertThrows(IllegalArgumentException.class,
                () -> NativeCommandExecutor.describeCommands("zeta")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> NativeCommandExecutor.describeCommands(" "));
        assertThrows(UnsupportedOperationException.class, () -> all.put("commands", List.of("fake")));
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
