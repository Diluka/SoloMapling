package org.gms.soloacceptance.command;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.client.command.CommandsExecutor;
import org.gms.config.GameConfig;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.soloacceptance.ObservationException;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** game_command/help own the target policy, native dispatch and process receipt contract. */
class DebugCommandServiceTest {
    private AccountService accounts;
    private CharacterService characters;
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private AccountsDO account;
    private CharactersDO stored;
    private Character character;
    private Client client;
    private Client previousClient;
    private CommandsExecutor dispatcher;
    private Map<String, Command> originalCommands;
    private MockedStatic<Server> serverSingleton;
    private MockedStatic<GameConfig> config;
    private ActorProfiles profiles;
    private NativeDebugCommandBackend backend;
    private DebugCommandService service;
    private Command nativeCommand;
    private int requestSequence;

    @BeforeEach
    void setup() {
        CharacterTestContext.install();
        previousClient = ThreadLocalUtil.getCurrentClient();
        dispatcher = CommandsExecutor.getInstance();
        originalCommands = new HashMap<>(dispatcher.getRegisteredCommands());
        dispatcher.getRegisteredCommands().clear();
        accounts = mock(AccountService.class); characters = mock(CharacterService.class);
        server = mock(Server.class); world = mock(World.class); channel = mock(Channel.class);
        worldPlayers = mock(PlayerStorage.class); channelPlayers = mock(PlayerStorage.class);
        character = mock(Character.class); client = mock(Client.class);
        account = AccountsDO.builder().id(17).name("mcp_primary").webadmin(0).banned(false).build();
        stored = CharactersDO.builder().id(41).accountid(17).name("McpPrimary").world(0).gm(0).build();
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        when(characters.findByName("McpPrimary")).thenReturn(stored);
        when(server.isOnline()).thenReturn(true); when(server.getWorld(0)).thenReturn(world);
        when(world.getId()).thenReturn(0); when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        when(character.getId()).thenReturn(41); when(character.getAccountId()).thenReturn(17);
        when(character.getName()).thenReturn("McpPrimary"); when(character.getWorld()).thenReturn(0);
        when(character.getMapId()).thenReturn(10000);
        when(character.getClient()).thenReturn(client); when(character.isLoggedInWorld()).thenReturn(true);
        when(client.getPlayer()).thenReturn(character); when(client.getAccID()).thenReturn(17);
        when(client.getChannel()).thenReturn(1); when(client.isLoggedIn()).thenReturn(true);
        when(client.tryacquireClient()).thenReturn(true);
        serverSingleton = mockStatic(Server.class);
        serverSingleton.when(Server::getInstance).thenReturn(server);
        config = mockStatic(GameConfig.class);
        profiles = profiles();
        backend = new NativeDebugCommandBackend(accounts, characters);
        service = new DebugCommandService(profiles, backend);
        nativeCommand = register("native-command", 0, "ordinary native command");
    }

    @AfterEach
    void restoreNativeBoundaries() {
        if (config != null) config.close();
        if (serverSingleton != null) serverSingleton.close();
        dispatcher.getRegisteredCommands().clear();
        dispatcher.getRegisteredCommands().putAll(originalCommands);
        if (previousClient == null) ThreadLocalUtil.removeCurrentClient();
        else ThreadLocalUtil.setCurrentClient(previousClient);
    }

    @Test
    void returnedReceiptRestoresWithoutReexecutionEvenAfterTheProfileWasRemoved() {
        String text = "!NATIVE-command  Mixed CASE  argument";
        Client preceding = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(preceding);
        doAnswer(call -> {
            assertSame(client, ThreadLocalUtil.getCurrentClient()); return null;
        }).when(nativeCommand).execute(eq(client), any(String[].class));
        DebugCommandRequest request = request(text);
        Map<String, Object> first = service.execute("operator", "command-1", request);
        profiles.setActorProfiles(Map.of());
        assertEquals(first, service.execute("operator", "command-1", request));
        verify(character).setLastCommandMessage(" Mixed CASE  argument");
        verify(nativeCommand).execute(eq(client), any(String[].class));
        assertEquals("RETURNED", first.get("status"));
        assertEquals("UNREPORTED", first.get("command_outcome"));
        assertEquals(true, first.get("native_handler_invoked"));
        assertEquals(true, first.get("native_handler_returned"));
        assertEquals(41, first.get("character_id"));
        assertFalse(first.containsKey("quantity_after"));
        assertFalse(first.containsKey("persisted_state_verified"));
        assertSame(preceding, ThreadLocalUtil.getCurrentClient());
        verify(character, never()).setGM(anyInt());
    }

    @Test
    void nativePermissionUnknownCommandAndBusyFeedbackNeverClaimGameplaySuccess() {
        Command restricted = register("restricted", 6, "administrator only");
        assertReturnedWithoutGameplayClaim(service.execute("operator", "restricted", request("@restricted")));
        verify(restricted, never()).execute(any(), any(String[].class));
        assertReturnedWithoutGameplayClaim(service.execute("operator", "missing", request("@not-registered")));
        verify(character, times(2)).yellowMessage(anyString());
        when(client.tryacquireClient()).thenReturn(false);
        assertReturnedWithoutGameplayClaim(service.execute("operator", "busy", request("@native-command")));
        verify(character).dropMessage(eq(5), anyString());
        verify(nativeCommand, never()).execute(any(), any(String[].class));
    }

    @Test
    void targetEligibilityAndExactOnlineObjectsProtectOtherPlayersAndEnvironmentBots() {
        account.setWebadmin(1); assertRejected("profile_target_not_eligible"); account.setWebadmin(0);
        account.setBanned(true); assertRejected("profile_target_not_eligible"); account.setBanned(false);
        stored.setGm(1); assertRejected("profile_target_not_eligible"); stored.setGm(0);
        stored.setAccountid(18); assertRejected("profile_target_not_eligible"); stored.setAccountid(17);
        stored.setWorld(1); assertRejected("profile_target_not_eligible"); stored.setWorld(0);
        when(server.isOnline()).thenReturn(false); assertRejected("server_offline");
        when(server.isOnline()).thenReturn(true);
        when(worldPlayers.getCharacterById(41)).thenReturn(null); assertRejected("profile_target_not_online");
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(mock(Character.class)); assertRejected("profile_target_not_online");
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        when(character.getName()).thenReturn("another-player"); assertRejected("profile_target_not_online");
        when(character.getName()).thenReturn("McpPrimary");
        when(character.getAccountId()).thenReturn(18); assertRejected("profile_target_not_online");
        when(character.getAccountId()).thenReturn(17);
        when(client.getPlayer()).thenReturn(mock(Character.class)); assertRejected("profile_target_not_online");
        when(client.getPlayer()).thenReturn(character);
        BotClient bot = mock(BotClient.class);
        when(character.getClient()).thenReturn(bot); when(bot.getPlayer()).thenReturn(character);
        when(bot.getAccID()).thenReturn(17); when(bot.getChannel()).thenReturn(1); when(bot.isLoggedIn()).thenReturn(true);
        assertRejected("profile_target_not_online");
        verify(nativeCommand, never()).execute(any(), any(String[].class));
    }

    @Test
    void replacementDuringValidationAndUnavailableStorageNeverReachTheNativeCommand() {
        when(worldPlayers.getCharacterById(41)).thenReturn(character, mock(Character.class));
        assertRejected("profile_target_not_online");
        when(accounts.findByName(anyString())).thenThrow(new IllegalStateException("private account details"));
        var failed = service.execute("operator", "read-failure", request("@native-command"));
        assertEquals("command_validation_unavailable", failed.get("reason"));
        assertFalse(failed.toString().contains("private"));
        verify(nativeCommand, never()).execute(any(), any(String[].class));
    }

    @Test
    void inFlightRetryReturnsRunningWhileTheOriginalNativeBodyStillExecutes() {
        DebugCommandRequest request = request("@native-command");
        doAnswer(call -> {
            try (var caller = Executors.newSingleThreadExecutor()) {
                var running = caller.submit(() -> service.execute("operator", "running", request)).get(5, TimeUnit.SECONDS);
                assertEquals("RUNNING", running.get("status"));
                assertEquals(false, running.get("terminal"));
                assertNull(running.get("native_handler_invoked"));
            }
            return null;
        }).when(nativeCommand).execute(eq(client), any(String[].class));
        assertEquals("RETURNED", service.execute("operator", "running", request).get("status"));
        verify(nativeCommand).execute(eq(client), any(String[].class));
    }

    @Test
    void changedArgumentsConflictAndEachOwnerHasAnIndependentRequestNamespace() {
        service.execute("operator-a", "same-id", request("@native-command"));
        for (DebugCommandRequest changed : List.of(request("@native-command "), request("@NATIVE-command"),
                new DebugCommandRequest("other", generation(), "@native-command"))) {
            var conflict = assertThrows(ObservationException.class, () -> service.execute("operator-a", "same-id", changed));
            assertEquals("request_id_conflict", conflict.reason());
        }
        service.execute("operator-b", "same-id", request("@native-command"));
        verify(nativeCommand, times(2)).execute(eq(client), any(String[].class));
    }

    @Test
    void actualProcessHistoryLimitRejectsNewRequestsWithoutForgettingReceipts() {
        assertEquals(256, service.publicCapabilities().get("history_limit"));
        var first = service.execute("operator", "kept", request("@native-command"));
        for (int i = 1; i < 256; i++) service.execute("operator", "fill-" + i, request("@native-command"));
        var full = assertThrows(ObservationException.class,
                () -> service.execute("operator", "new", request("@native-command")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        assertEquals(first, service.execute("operator", "kept", request("@native-command")));
        verify(nativeCommand, times(256)).execute(eq(client), any(String[].class));
    }

    @Test
    void nativeFailuresStayUnknownAndConsumedIncludingFatalExits() {
        for (Throwable failure : List.of(new IllegalStateException("private exception"),
                new AssertionError("private assertion"), new LinkageError("private linkage"),
                new InternalError("private fatal"))) {
            doThrow(failure).when(nativeCommand).execute(eq(client), any(String[].class));
            String id = "failure-" + requestSequence++;
            if (failure instanceof VirtualMachineError) {
                assertSame(failure, assertThrows(failure.getClass(), () -> service.execute("operator", id, request("@native-command"))));
            }
            var receipt = service.execute("operator", id, request("@native-command"));
            assertEquals("UNKNOWN", receipt.get("status"));
            assertEquals(true, receipt.get("terminal"));
            assertFalse(receipt.toString().contains("private"));
            assertEquals(receipt, service.execute("operator", id, request("@native-command")));
        }
        verify(nativeCommand, times(4)).execute(eq(client), any(String[].class));
        verify(client, times(4)).releaseClient();
    }

    @Test
    void invalidInputsAndOldProcessGenerationAreRejectedBeforeNativeDispatch() {
        for (String text : new String[]{null, "", "  ", "x".repeat(16_385), "@native-command\ud800"}) {
            assertEquals("invalid_command_text", assertThrows(ObservationException.class,
                    () -> service.execute("operator", "invalid", request(text))).reason());
        }
        assertThrows(ObservationException.class, () -> service.execute("operator", "bad id", request("@native-command")));
        assertThrows(ObservationException.class, () -> service.execute("", "valid", request("@native-command")));
        assertThrows(ObservationException.class, () -> service.execute("operator", "unconfigured",
                new DebugCommandRequest("unconfigured", generation(), "@native-command")));
        var fresh = new DebugCommandService(profiles, backend);
        assertEquals("command_generation_changed", assertThrows(ObservationException.class,
                () -> fresh.execute("operator", "stale", request("@native-command"))).reason());
        verifyNoInteractions(nativeCommand);
        assertEquals("RETURNED", service.execute("operator", "invalid", request("@native-command")).get("status"));
    }

    @Test
    void commandHistoryRetainsSignaturesAndReceiptsWithoutThePrivateText() throws Exception {
        String privateText = "@native-command private-command-argument";
        var receipt = service.execute("operator", "private", request(privateText));
        assertFalse(receipt.toString().contains("private-command-argument"));
        assertFalse(receipt.containsKey("command"));
        assertEquals(64, ((String) receipt.get("command_sha256")).length());
        Field history = DebugCommandService.class.getDeclaredField("history");
        history.setAccessible(true);
        for (Object entry : ((Map<?, ?>) history.get(service)).values()) {
            for (Field field : entry.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                assertFalse(String.valueOf(field.get(entry)).contains("private-command-argument"));
            }
        }
    }

    @Test
    void helpReadsTheRealNativeRegistryWithoutExecutingOrChangingCommands() {
        Command restricted = register("zeta", 4, "native privileged command");
        assertEquals(List.of("native-command", "zeta"), service.help(null).get("commands"));
        assertEquals(List.of(Map.of("name", "zeta", "rank", 4, "description", "native privileged command")),
                service.help("ZETA").get("command_details"));
        dispatcher.getRegisteredCommands().remove("zeta");
        assertEquals(List.of("native-command"), service.help(null).get("commands"));
        assertEquals("native_command_not_registered", assertThrows(ObservationException.class,
                () -> service.help("zeta")).reason());
        verify(nativeCommand, never()).execute(any(), any(String[].class));
        verify(restricted, never()).execute(any(), any(String[].class));
    }

    private Command register(String name, int rank, String description) {
        Command command = mock(Command.class);
        when(command.getRank()).thenReturn(rank); when(command.getDescription()).thenReturn(description);
        dispatcher.getRegisteredCommands().put(name, command);
        return command;
    }

    private void assertRejected(String reason) {
        var receipt = service.execute("operator", "rejected-" + requestSequence++, request("@native-command"));
        assertEquals("FAILED", receipt.get("status"));
        assertEquals(false, receipt.get("native_handler_invoked"));
        assertEquals("NOT_INVOKED", receipt.get("command_outcome"));
        assertEquals(reason, receipt.get("reason"));
    }

    private static void assertReturnedWithoutGameplayClaim(Map<String, Object> receipt) {
        assertEquals("RETURNED", receipt.get("status"));
        assertEquals("UNREPORTED", receipt.get("command_outcome"));
    }

    private String generation() { return (String) service.publicCapabilities().get("command_generation"); }
    private DebugCommandRequest request(String command) { return new DebugCommandRequest("primary", generation(), command); }

    static ActorProfiles profiles() {
        ActorProfiles.Profile profile = new ActorProfiles.Profile();
        profile.setAccountName("mcp_primary"); profile.setAccountPassword("local-test-only"); profile.setCharacterName("McpPrimary");
        ActorProfiles profiles = new ActorProfiles(); profiles.setActorProfiles(Map.of("primary", profile)); return profiles;
    }
}
