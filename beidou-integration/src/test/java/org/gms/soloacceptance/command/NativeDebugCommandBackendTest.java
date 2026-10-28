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
import org.gms.soloacceptance.actor.ActorProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.command.NativeCommandExecutor;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeDebugCommandBackendTest {
    private final ActorProfiles.Resolved profile = new ActorProfiles.Resolved(
            "primary", "mcp_primary", "test-password", "McpPrimary", 0, 1, false);
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
    private NativeDebugCommandBackend backend;
    private MockedStatic<NativeCommandExecutor> executor;

    @BeforeEach
    void setup() {
        CharacterTestContext.install();
        accounts = mock(AccountService.class); characters = mock(CharacterService.class);
        server = mock(Server.class); world = mock(World.class); channel = mock(Channel.class);
        worldPlayers = mock(PlayerStorage.class); channelPlayers = mock(PlayerStorage.class);
        character = mock(Character.class);
        // A plain native client deliberately requires no AcceptanceClient, module, or runtime.
        client = mock(Client.class);
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
        when(character.getClient()).thenReturn(client); when(character.isLoggedInWorld()).thenReturn(true);
        when(client.getPlayer()).thenReturn(character); when(client.getAccID()).thenReturn(17);
        when(client.getChannel()).thenReturn(1); when(client.isLoggedIn()).thenReturn(true);
        backend = new NativeDebugCommandBackend(accounts, characters, () -> server);
        executor = mockStatic(NativeCommandExecutor.class);
        executor.when(() -> NativeCommandExecutor.validateCommand(any())).thenCallRealMethod();
    }

    @AfterEach
    void stop() { if (executor != null) executor.close(); }

    @Test
    void forwardsConfiguredNativeClientAndCompleteUnmodifiedCommandToSharedExecutorOnce() {
        String command = "!Any-Native-Command  Mixed CASE  arguments";
        executor.when(() -> NativeCommandExecutor.execute(client, command)).thenReturn(returned());
        var result = backend.execute(profile, command);
        assertEquals("RETURNED", result.status());
        assertEquals(true, result.nativeHandlerInvoked());
        assertEquals(true, result.nativeHandlerReturned());
        assertEquals("UNREPORTED", result.commandOutcome());
        assertEquals(41, result.characterId()); assertEquals(0, result.world());
        executor.verify(() -> NativeCommandExecutor.execute(client, command), times(1));
        verify(character, never()).setGM(anyInt());
    }

    @Test
    void anyCommandNameUsesTheSameNativeEntryWithoutGivingItemsOrChangingPermissions() {
        for (String command : new String[]{"@time", "!item 2000000 1", "/native-command arg", "@newly-registered-name"}) {
            executor.when(() -> NativeCommandExecutor.execute(client, command)).thenReturn(returned());
            assertEquals("RETURNED", backend.execute(profile, command).status());
            executor.verify(() -> NativeCommandExecutor.execute(client, command), times(1));
        }
    }

    @Test
    void rejectsEmptyOverlongAndMalformedUnicodeBeforeNativeHandler() {
        for (String command : new String[]{null, "", "  ", "x".repeat(16_385), "@time\ud800"}) {
            var result = backend.execute(profile, command);
            assertEquals("FAILED", result.status());
            assertEquals(false, result.nativeHandlerInvoked());
            assertEquals("invalid_command_text", result.reason());
        }
        verifyNoInteractions(accounts, characters);
    }

    @Test
    void preservesExistingOrdinaryFixtureEligibilityChecks() {
        account.setWebadmin(1);
        assertRejected("profile_target_not_eligible"); account.setWebadmin(0);
        account.setBanned(true);
        assertRejected("profile_target_not_eligible"); account.setBanned(false);
        stored.setGm(1);
        assertRejected("profile_target_not_eligible"); stored.setGm(0);
        stored.setAccountid(18);
        assertRejected("profile_target_not_eligible"); stored.setAccountid(17);
        stored.setWorld(1);
        assertRejected("profile_target_not_eligible");
    }

    @Test
    void rejectsOfflineMissingAndReplacementObjectsBeforeSharedInvocation() {
        when(worldPlayers.getCharacterById(41)).thenReturn(null);
        assertRejected("profile_target_not_online");
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(mock(Character.class));
        assertRejected("profile_target_not_online");
    }

    @Test
    void rechecksExactTargetImmediatelyBeforeSharedInvocation() {
        when(worldPlayers.getCharacterById(41)).thenReturn(character, mock(Character.class));
        assertRejected("profile_target_not_online");
        executor.verify(() -> NativeCommandExecutor.execute(any(), anyString()), never());
    }

    @Test
    void mismatchedLiveNameAccountAndClientCannotSelectAnotherRole() {
        when(character.getName()).thenReturn("someone-else");
        assertRejected("profile_target_not_online");
        when(character.getName()).thenReturn("McpPrimary");
        when(character.getAccountId()).thenReturn(18);
        assertRejected("profile_target_not_online");
        when(character.getAccountId()).thenReturn(17);
        when(client.getPlayer()).thenReturn(mock(Character.class));
        assertRejected("profile_target_not_online");
    }

    @Test
    void environmentBotsRemainExcludedFromTheExistingMcpTargetPolicy() {
        BotClient bot = mock(BotClient.class);
        when(character.getClient()).thenReturn(bot); when(bot.getPlayer()).thenReturn(character);
        when(bot.getAccID()).thenReturn(17); when(bot.getChannel()).thenReturn(1); when(bot.isLoggedIn()).thenReturn(true);
        assertRejected("profile_target_not_online");
    }

    @Test
    void sharedUnknownReceiptIsKeptWithoutRetryingOrExposingNativeExceptionText() {
        String command = "@native-command argument";
        executor.when(() -> NativeCommandExecutor.execute(client, command)).thenReturn(
                new NativeCommandExecutor.Result("UNKNOWN", true, false, "UNREPORTED", "native_handler_failed"));
        var result = backend.execute(profile, command);
        assertEquals("UNKNOWN", result.status());
        assertEquals(true, result.nativeHandlerInvoked()); assertEquals(false, result.nativeHandlerReturned());
        assertEquals("UNREPORTED", result.commandOutcome());
        executor.verify(() -> NativeCommandExecutor.execute(client, command), times(1));
    }

    @Test
    void nativeAssertionErrorThroughTheMcpChainRestoresUnknownWithoutRunningAgain() {
        executor.close(); executor = null;
        CommandsExecutor nativeExecutor = CommandsExecutor.getInstance();
        var original = new HashMap<>(nativeExecutor.getRegisteredCommands());
        Command command = mock(Command.class);
        when(command.getRank()).thenReturn(0);
        doThrow(new AssertionError("private native assertion")).when(command).execute(eq(client), any(String[].class));
        when(client.tryacquireClient()).thenReturn(true);
        when(character.getMapId()).thenReturn(10000);
        try (MockedStatic<GameConfig> config = mockStatic(GameConfig.class)) {
            config.when(() -> GameConfig.getServerBoolean("deterred_player_command")).thenReturn(false);
            nativeExecutor.getRegisteredCommands().put("assertion-probe", command);
            DebugCommandService service = new DebugCommandService(DebugCommandServiceTest.profiles(), backend,
                    "test-generation", 2);
            DebugCommandRequest request = new DebugCommandRequest("primary", "test-generation", "@assertion-probe");
            var first = service.execute("operator", "native-assertion", request);
            assertEquals("UNKNOWN", first.get("status"));
            assertEquals(true, first.get("terminal"));
            assertEquals(true, first.get("native_handler_invoked"));
            assertEquals(false, first.get("native_handler_returned"));
            assertFalse(first.toString().contains("private"));
            assertEquals(first, service.execute("operator", "native-assertion", request));
            verify(command, times(1)).execute(eq(client), any(String[].class));
            verify(client).releaseClient();
        } finally {
            nativeExecutor.getRegisteredCommands().clear();
            nativeExecutor.getRegisteredCommands().putAll(original);
        }
    }

    @Test
    void validationReadFailureIsFixedAndNeverReachesTheSharedExecutor() {
        when(accounts.findByName(anyString())).thenThrow(new IllegalStateException("private account details"));
        var result = backend.execute(profile, "@time");
        assertEquals("FAILED", result.status()); assertEquals(false, result.nativeHandlerInvoked());
        assertEquals("command_validation_unavailable", result.reason());
        assertFalse(result.toString().contains("private"));
        executor.verify(() -> NativeCommandExecutor.execute(any(), anyString()), never());
    }

    private void assertRejected(String reason) {
        var result = backend.execute(profile, "@time");
        assertEquals("FAILED", result.status()); assertEquals(false, result.nativeHandlerInvoked());
        assertEquals(false, result.nativeHandlerReturned()); assertEquals("NOT_INVOKED", result.commandOutcome());
        assertEquals(reason, result.reason());
        executor.verify(() -> NativeCommandExecutor.execute(any(), anyString()), never());
    }
    private static NativeCommandExecutor.Result returned() {
        return new NativeCommandExecutor.Result("RETURNED", true, true, "UNREPORTED", "native_handler_returned");
    }
}
