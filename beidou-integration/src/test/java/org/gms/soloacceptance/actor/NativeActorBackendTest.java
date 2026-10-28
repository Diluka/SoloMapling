package org.gms.soloacceptance.actor;

import soloMapling.game.execution.ExecutionScope;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.Equip;
import org.gms.constants.id.MapId;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.channel.handlers.PlayerLoggedinHandler;
import org.gms.net.server.handlers.login.CharSelectedHandler;
import org.gms.net.server.coordinator.matchchecker.MatchCheckerCoordinator;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.scripting.quest.QuestActionManager;
import org.gms.server.CashShop;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.life.Monster;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.NoteService;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Guard and observable-state tests use in-memory mocks, never a datasource. */
class NativeActorBackendTest {
    private ActorProfiles profiles;
    private AccountService accounts;
    private CharacterService characters;
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private Character character;
    private AcceptanceClient client;
    private MapleMap map;
    private NativeActorHandle handle;
    private NativeActorBackend backend;
    private MockedStatic<ActorAccountOwnership> ownership;
    private ActorAccountOwnership.Lease lease;
    private NativeActorExecutionFixture execution;

    @BeforeEach
    void setup() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        profiles = new ActorProfiles();
        ActorProfiles.Profile profile = new ActorProfiles.Profile();
        profile.setAccountName("mcp_primary");
        profile.setAccountPassword("test-private-password");
        profile.setCharacterName("McpPrimary");
        profiles.setActorProfiles(Map.of("primary", profile));
        accounts = mock(AccountService.class);
        characters = mock(CharacterService.class);
        server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        world = mock(World.class);
        channel = mock(Channel.class);
        worldPlayers = mock(PlayerStorage.class);
        channelPlayers = mock(PlayerStorage.class);
        when(server.getWorld(0)).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        when(world.getChannel(1)).thenReturn(channel);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        when(worldPlayers.getAllCharacters()).thenReturn(List.of());
        backend = new NativeActorBackend(profiles, accounts, characters, mock(NoteService.class), () -> server);
        character = mock(Character.class);
        client = mock(AcceptanceClient.class);
        map = mock(MapleMap.class);
        when(character.getId()).thenReturn(41);
        when(character.getAccountId()).thenReturn(17);
        when(character.getName()).thenReturn("McpPrimary");
        when(character.getClient()).thenReturn(client);
        when(character.getMap()).thenReturn(map);
        when(character.getMapId()).thenReturn(10000);
        when(character.getPosition()).thenReturn(new Point(4, 8));
        when(character.getLevel()).thenReturn(1);
        when(character.getHp()).thenReturn(50);
        when(character.getMp()).thenReturn(5);
        when(character.getJob()).thenReturn(Job.BEGINNER);
        when(character.isLoggedIn()).thenReturn(true);
        when(character.isLoggedInWorld()).thenReturn(true);
        when(client.getPlayer()).thenReturn(character);
        when(client.getAccID()).thenReturn(17);
        when(client.getChannel()).thenReturn(1);
        when(client.isLoggedIn()).thenReturn(true);
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        when(map.getCharacterById(41)).thenReturn(character);
        when(map.getId()).thenReturn(10000);
        when(map.getForcedReturnId()).thenReturn(MapId.NONE);
        lease = mock(ActorAccountOwnership.Lease.class);
        ownership = mockStatic(ActorAccountOwnership.class);
        ownership.when(() -> ActorAccountOwnership.claim(17)).thenReturn(lease);
        ownership.when(() -> ActorAccountOwnership.owns(lease, client)).thenReturn(true);
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(client);
        handle = new NativeActorHandle("primary", 17, client, character, world, channel, lease, false, false);
        execution = NativeActorExecutionFixture.owned(handle);
    }

    @AfterEach
    void closeStaticOwnershipMock() {
        ExecutionScope.tryBeginWorldStop();
        ownership.close();
    }

    @Test
    void profileValidationNeverTouchesGameDataOrWorld() {
        backend.validateProfile("primary", 0, 1);
        assertEquals("profile_channel_mismatch", assertThrows(ObservationException.class,
                () -> backend.validateProfile("primary", 0, 2)).reason());
        verifyNoInteractions(accounts, characters, server);
    }

    @Test
    void cancelledOpenDoesNotReadOrWriteFixtures() {
        NativeActorExecutionFixture attempt = new NativeActorExecutionFixture();
        assertThrows(CancellationException.class, () -> attempt.open(backend, () -> true));
        assertEquals(0, attempt.scope.resources().snapshot().outstanding());
        verifyNoInteractions(accounts, characters, server);
    }

    @Test
    void anUnverifiedNoHandleNativeAttemptRetainsItsPendingOwnershipEvidence() {
        AccountsDO account = account();
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        when(accounts.findById(17)).thenReturn(account);
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(null);
        NativeActorExecutionFixture attempt = new NativeActorExecutionFixture();
        try (MockedConstruction<AcceptanceClient> clients = mockConstruction(AcceptanceClient.class,
                (nativeClient, context) -> when(nativeClient.login(anyString(), anyString(), any())).thenAnswer(invocation -> {
                    ExecutionScope.tryBeginWorldStop();
                    return 7;
                }))) {
            ObservationException failure = assertThrows(ObservationException.class,
                    () -> attempt.open(backend, () -> false));
            assertEquals("actor_open_cleanup_unverified", failure.reason());
            assertEquals(1, attempt.scope.resources().snapshot().outstanding());
            assertEquals(1, attempt.scope.resources().snapshot().unverified());
            verify(clients.constructed().getFirst(), never()).disconnect(anyBoolean(), anyBoolean());
            ownership.verify(() -> ActorAccountOwnership.release(lease), never());
        }
    }

    @Test
    void anAdmittedNoHandleAttemptCanFinishItsExactRollbackInTheControlledStopWindow() {
        AccountsDO account = account();
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        when(accounts.findById(17)).thenReturn(account);
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(null);
        ownership.when(() -> ActorAccountOwnership.release(lease)).thenReturn(true);
        NativeActorExecutionFixture attempt = new NativeActorExecutionFixture();
        try (MockedStatic<SessionCoordinator> sessions = mockStatic(SessionCoordinator.class);
             MockedConstruction<AcceptanceClient> clients = mockConstruction(AcceptanceClient.class,
                     (nativeClient, context) -> when(nativeClient.login(anyString(), anyString(), any())).thenAnswer(invocation -> {
                         attempt.stopWorld();
                         return 7;
                     }))) {
            sessions.when(SessionCoordinator::getInstance).thenReturn(mock(SessionCoordinator.class));
            ObservationException failure = assertThrows(ObservationException.class,
                    () -> attempt.open(backend, () -> false));
            assertEquals("actor_native_login_rejected", failure.reason());
            assertEquals(0, attempt.scope.resources().snapshot().outstanding());
            verify(clients.constructed().getFirst(), times(1)).closeSession();
            ownership.verify(() -> ActorAccountOwnership.release(lease), times(1));
        }
    }

    @Test
    void missingAccountRequiresExplicitFixtureCreationPermission() throws Exception {
        assertEquals("actor_account_missing", assertThrows(ObservationException.class,
                () -> open(() -> false)).reason());
        verify(accounts, never()).addAccount(any());
        verifyNoInteractions(characters);
    }

    @Test
    void refusesExistingPrivilegedAndOnlineAccountsBeforeNativeLogin() throws Exception {
        AccountsDO account = account();
        account.setWebadmin(1);
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        assertEquals("actor_account_not_eligible", assertThrows(ObservationException.class,
                () -> open(() -> false)).reason());
        account.setWebadmin(0);
        account.setLoggedin(Client.LOGIN_LOGGEDIN);
        when(accounts.findById(17)).thenReturn(account);
        assertEquals("actor_account_in_use", assertThrows(ObservationException.class,
                () -> open(() -> false)).reason());
        verify(accounts, never()).update(any());
        verify(accounts, never()).addAccount(any());
        verifyNoInteractions(characters);
    }

    @Test
    void refusesAnOnlineCharacterEvenWhenSqlLoginStateSaysOffline() {
        AccountsDO account = account();
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        when(accounts.findById(17)).thenReturn(account);
        when(worldPlayers.getAllCharacters()).thenReturn(List.of(character));
        assertEquals("actor_account_in_use", assertThrows(ObservationException.class,
                () -> open(() -> false)).reason());
        verifyNoInteractions(characters);
    }

    @Test
    void interruptedFixtureBootstrapCanResumeNativeTermsAcceptanceOnRetry() throws Exception {
        profiles.getActorProfiles().get("primary").setAllowCreate(true);
        AccountsDO account = account();
        account.setTos(false);
        when(accounts.findByName("mcp_primary")).thenReturn(null, account);
        when(accounts.findById(17)).thenReturn(account);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class,
                () -> open(() -> checks.incrementAndGet() > 1));
        verify(accounts, times(1)).addAccount(any());

        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(null);
        ownership.when(() -> ActorAccountOwnership.release(lease)).thenReturn(true);
        try (MockedStatic<SessionCoordinator> sessions = mockStatic(SessionCoordinator.class);
             MockedConstruction<AcceptanceClient> clients = mockConstruction(AcceptanceClient.class,
                     (nativeClient, context) -> {
                         when(nativeClient.login(eq("mcp_primary"), eq("test-private-password"), any(Hwid.class)))
                                 .thenReturn(23, 0);
                         // Stop before native publication; this test deliberately has no datasource.
                         when(nativeClient.finishLogin()).thenReturn(7);
                     })) {
            sessions.when(SessionCoordinator::getInstance).thenReturn(mock(SessionCoordinator.class));
            assertEquals("actor_native_login_conflict", assertThrows(ObservationException.class,
                    () -> open(() -> false)).reason());
            AcceptanceClient nativeClient = clients.constructed().getFirst();
            verify(nativeClient, times(1)).acceptToS();
            verify(nativeClient, times(2)).login(eq("mcp_primary"), eq("test-private-password"), any(Hwid.class));
        }
        // Retrying an interrupted bootstrap must not recreate or delete its retained account.
        verify(accounts, times(1)).addAccount(any());
        verifyNoInteractions(characters);
    }

    @Test
    void existingAccountWithoutBootstrapPermissionDoesNotAcceptTermsOrPublishLogin() {
        AccountsDO account = account();
        account.setTos(false);
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        when(accounts.findById(17)).thenReturn(account);
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(null);
        ownership.when(() -> ActorAccountOwnership.release(lease)).thenReturn(true);
        try (MockedStatic<SessionCoordinator> sessions = mockStatic(SessionCoordinator.class);
             MockedConstruction<AcceptanceClient> clients = mockConstruction(AcceptanceClient.class,
                     (nativeClient, context) -> when(nativeClient.login(anyString(), anyString(), any(Hwid.class)))
                             .thenReturn(23))) {
            sessions.when(SessionCoordinator::getInstance).thenReturn(mock(SessionCoordinator.class));
            assertEquals("actor_native_login_rejected", assertThrows(ObservationException.class,
                    () -> open(() -> false)).reason());
            verify(clients.constructed().getFirst(), never()).acceptToS();
            verify(clients.constructed().getFirst(), never()).finishLogin();
        }
        verifyNoInteractions(characters);
    }

    @Test
    void aHandleIsOwnedBeforeNativeEntryValidationCanFailAndRollbackRunsInItsCleanupWork() {
        AccountsDO account = account();
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        when(accounts.findById(17)).thenReturn(account);
        when(characters.findByName("McpPrimary")).thenReturn(stored());
        when(characters.findById(41)).thenReturn(stored());
        MatchCheckerCoordinator match = mock(MatchCheckerCoordinator.class);
        when(world.getMatchCheckerCoordinator()).thenReturn(match);
        when(match.getMatchConfirmationLeaderid(41)).thenReturn(-1);
        ownership.when(() -> ActorAccountOwnership.release(lease)).thenReturn(true);
        NativeActorBackend guarded = spy(backend);
        java.util.concurrent.atomic.AtomicReference<NativeActorHandle> adopted = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<AcceptanceClient> active = new java.util.concurrent.atomic.AtomicReference<>();
        NativeActorExecutionFixture openScope = new NativeActorExecutionFixture();
        NativeActorBackend.OpenOwnership openOwnership = new NativeActorBackend.OpenOwnership() {
            public void bindOrigin(World value, Channel current) { openScope.bindOrigin(value, current); }
            public void adopt(NativeActorHandle value) { openScope.adopt(value); adopted.set(value); }
            public void rollback(NativeActorHandle value) {
                assertSame(adopted.get(), value);
                Map<String, Object> evidence = openScope.close(guarded);
                assertEquals(true, evidence.get("native_cleanup_verified"));
            }
            public void confirmAbsent() { openScope.confirmAbsent(); }
            public void creationFailed(String reason) { openScope.creationFailed(reason); }
            public void requireOrigin(World value, Channel current) { openScope.requireOrigin(value, current); }
        };
        doAnswer(invocation -> {
            assertSame(adopted.get(), invocation.getArgument(0));
            return false;
        }).when(guarded).isCurrent(any());
        try (MockedStatic<SessionCoordinator> sessions = mockStatic(SessionCoordinator.class);
             MockedConstruction<AcceptanceClient> clients = mockConstruction(AcceptanceClient.class,
                     (nativeClient, context) -> {
                         when(nativeClient.getAccID()).thenReturn(17);
                         when(nativeClient.getWorld()).thenReturn(0);
                         when(nativeClient.getChannel()).thenReturn(1);
                         when(nativeClient.getLanguage()).thenReturn(3);
                         when(nativeClient.getWorldServer()).thenReturn(world);
                         when(nativeClient.getChannelServer()).thenReturn(channel);
                         when(nativeClient.login(anyString(), anyString(), any())).thenReturn(0);
                         when(nativeClient.isLoggedIn()).thenReturn(true);
                         when(nativeClient.finishLogin()).thenAnswer(invocation -> {
                             account.setLoggedin(Client.LOGIN_LOGGEDIN);
                             return 0;
                         });
                         when(nativeClient.isInTransition()).thenReturn(true);
                         if (context.getCount() == 2) {
                             active.set(nativeClient);
                             when(nativeClient.getPlayer()).thenReturn(character);
                             when(character.getClient()).thenReturn(nativeClient);
                             ownership.when(() -> ActorAccountOwnership.owns(lease, nativeClient)).thenReturn(true);
                             ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(nativeClient);
                             doAnswer(invocation -> {
                                 account.setLoggedin(Client.LOGIN_NOTLOGGEDIN);
                                 when(nativeClient.getPlayer()).thenReturn(null);
                                 when(nativeClient.isLoggedIn()).thenReturn(false);
                                 when(character.isLoggedIn()).thenReturn(false);
                                 when(character.isLoggedInWorld()).thenReturn(false);
                                 when(worldPlayers.getCharacterById(41)).thenReturn(null);
                                 when(channelPlayers.getCharacterById(41)).thenReturn(null);
                                 when(map.getCharacterById(41)).thenReturn(null);
                                 ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(null);
                                 return null;
                             }).when(nativeClient).disconnect(false, false);
                         }
                     });
             MockedConstruction<CharSelectedHandler> selected = mockConstruction(CharSelectedHandler.class,
                     (handler, context) -> {
                         when(handler.validateState(any())).thenReturn(true);
                         doAnswer(invocation -> {
                             account.setLoggedin(Client.LOGIN_SERVER_TRANSITION);
                             return null;
                         }).when(handler).handlePacket(any(), any());
                     });
             MockedConstruction<PlayerLoggedinHandler> entered = mockConstruction(PlayerLoggedinHandler.class,
                     (handler, context) -> when(handler.validateState(any())).thenReturn(true))) {
            sessions.when(SessionCoordinator::getInstance).thenReturn(mock(SessionCoordinator.class));
            ObservationException rejected = assertThrows(ObservationException.class, () -> openScope.open(() ->
                    guarded.open("primary", null, null, () -> false, openOwnership)));
            assertEquals("actor_native_entry_unverified", rejected.reason());
            assertNotNull(adopted.get());
            verify(active.get(), times(1)).disconnect(false, false);
        }
    }

    @Test
    void currentSessionRequiresExactNativeObjectsAtEveryRegistration() {
        assertTrue(backend.isCurrent(handle));
        when(map.getCharacterById(41)).thenReturn(mock(Character.class));
        assertFalse(backend.isCurrent(handle));
        when(map.getCharacterById(41)).thenReturn(character);
        when(server.getWorld(0)).thenReturn(mock(World.class));
        assertFalse(backend.isCurrent(handle));
        when(server.getWorld(0)).thenReturn(world);
        when(client.getChannel()).thenReturn(2);
        assertFalse(backend.isCurrent(handle));
        when(client.getChannel()).thenReturn(1);
        when(client.getAccID()).thenReturn(18);
        assertFalse(backend.isCurrent(handle));
        verifyNoInteractions(accounts, characters);
    }

    @Test
    void aHandleCannotRecaptureTheNewWorldEpochEvenWhenAllNativeObjectsAreReused() {
        var captured = handle.characterRef();
        assertTrue(backend.isCurrent(handle));
        ExecutionScope.tryBeginWorldStop();
        WorldEpoch.onWorldStarted();
        assertSame(captured, handle.characterRef());
        assertNotSame(captured.epoch(), WorldEpoch.snapshot().token());
        assertFalse(backend.isCurrent(handle));
        assertEquals("actor_session_stale", assertThrows(ObservationException.class, () -> backend.snapshot(handle)).reason());
    }

    @Test
    void aNativeReadFailurePropagatesUnavailableInsteadOfReturningAnIdentityLoss() {
        when(character.getMap()).thenThrow(new IllegalStateException("private-map-read-detail"));
        ObservationException current = assertThrows(ObservationException.class, () -> backend.isCurrent(handle));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, current.status());
        assertEquals("actor_observation_unavailable", current.reason());
        assertFalse(String.valueOf(current.getMessage()).contains("private-map-read-detail"));
        assertEquals("actor_observation_unavailable", assertThrows(ObservationException.class,
                () -> backend.snapshot(handle)).reason());
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
    }

    @Test
    void disposedSceneDoesNotInvalidateTheRoleOrPreventNativeLogoutAfterEpochStops() {
        EventInstanceManager disposed = mock(EventInstanceManager.class);
        when(disposed.isEventDisposed()).thenReturn(true);
        when(map.getEventInstance()).thenReturn(disposed);
        assertTrue(backend.isCurrent(handle));
        assertEquals("actor_context_mismatch", assertThrows(ObservationException.class,
                () -> backend.snapshot(handle)).reason());
        clearInvocations(disposed);
        MatchCheckerCoordinator match = mock(MatchCheckerCoordinator.class);
        when(world.getMatchCheckerCoordinator()).thenReturn(match);
        when(match.getMatchConfirmationLeaderid(41)).thenReturn(-1);
        AccountsDO account = account();
        account.setLoggedin(Client.LOGIN_LOGGEDIN);
        when(accounts.findById(17)).thenReturn(account);
        when(characters.findById(41)).thenReturn(stored());
        ownership.when(() -> ActorAccountOwnership.release(lease)).thenReturn(true);
        doAnswer(invocation -> {
            account.setLoggedin(Client.LOGIN_NOTLOGGEDIN);
            when(client.getPlayer()).thenReturn(null);
            when(client.isLoggedIn()).thenReturn(false);
            when(character.isLoggedIn()).thenReturn(false);
            when(character.isLoggedInWorld()).thenReturn(false);
            when(character.getMap()).thenReturn(null);
            when(character.getClient()).thenReturn(null);
            when(worldPlayers.getCharacterById(41)).thenReturn(null);
            when(channelPlayers.getCharacterById(41)).thenReturn(null);
            when(map.getCharacterById(41)).thenReturn(null);
            ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(null);
            return null;
        }).when(client).disconnect(false, false);
        execution.stopWorld();
        assertFalse(backend.isCurrent(handle));
        Map<String, Object> closed = execution.close(backend);
        assertEquals(true, closed.get("native_cleanup_verified"));
        assertEquals(true, closed.get("persisted_state_verified"));
        verify(client).disconnect(false, false);
        verify(disposed, never()).isEventDisposed();
    }

    @Test
    void staleCloseCannotStartNativeLogoutOrTouchPersistence() {
        when(server.getWorld(0)).thenReturn(mock(World.class));
        ObservationException failure = assertThrows(ObservationException.class, () -> execution.close(backend));
        assertTrue(failure.reason().contains("cleanup_unverified"));
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        verifyNoInteractions(accounts, characters);
    }

    @Test
    void verifiedCloseEvidenceIsIdempotentAndDoesNotOperateOnAnAlreadyClosedSession() {
        Map<String, Object> evidence = Map.of("native_cleanup_verified", true, "fixture_retained", true);
        handle.recordClose(evidence);
        assertEquals(evidence, execution.close(backend));
        assertFalse(backend.isCurrent(handle));
        verifyNoInteractions(accounts, characters);
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
    }

    @Test
    void timedOutLogoutCanBeVerifiedLaterWithoutLoggingOutAgainOrReadingClearedCharacterFields() {
        backend = new NativeActorBackend(profiles, accounts, characters, mock(NoteService.class), () -> server, 0);
        MatchCheckerCoordinator match = mock(MatchCheckerCoordinator.class);
        when(world.getMatchCheckerCoordinator()).thenReturn(match);
        when(match.getMatchConfirmationLeaderid(41)).thenReturn(-1);
        AccountsDO account = account();
        account.setLoggedin(Client.LOGIN_LOGGEDIN);
        when(accounts.findById(17)).thenReturn(account);

        ObservationException first = assertThrows(ObservationException.class, () -> execution.close(backend));
        assertTrue(first.reason().contains("cleanup_unverified"));
        assertNotNull(handle.closeContext());
        assertNull(handle.closeEvidence());

        // Model a native logout that completed after the first bounded wait, followed by empty().
        account.setLoggedin(Client.LOGIN_NOTLOGGEDIN);
        when(client.getPlayer()).thenReturn(null);
        when(client.isLoggedIn()).thenReturn(false);
        when(character.isLoggedIn()).thenReturn(false);
        when(character.isLoggedInWorld()).thenReturn(false);
        when(character.getMap()).thenReturn(null);
        when(character.getClient()).thenReturn(null);
        when(character.getLevel()).thenReturn(999); // The verification must use its frozen expectation.
        when(worldPlayers.getCharacterById(41)).thenReturn(null);
        when(channelPlayers.getCharacterById(41)).thenReturn(null);
        when(map.getCharacterById(41)).thenReturn(null);
        when(characters.findById(41)).thenReturn(stored());
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(null);
        ownership.when(() -> ActorAccountOwnership.release(lease)).thenReturn(true);

        // Native CM/QM maps must also be empty, even after Character.empty() cleared its client/map.
        NPCConversationManager remainingCm = mock(NPCConversationManager.class);
        when(client.getCM()).thenReturn(remainingCm);
        assertTrue(assertThrows(ObservationException.class, () -> execution.close(backend)).reason().contains("cleanup_unverified"));
        ownership.verify(() -> ActorAccountOwnership.release(lease), never());
        when(client.getCM()).thenReturn(null);
        QuestActionManager remainingQm = mock(QuestActionManager.class);
        when(client.getQM()).thenReturn(remainingQm);
        assertTrue(assertThrows(ObservationException.class, () -> execution.close(backend)).reason().contains("cleanup_unverified"));
        ownership.verify(() -> ActorAccountOwnership.release(lease), never());
        when(client.getQM()).thenReturn(null);

        Map<String, Object> evidence = execution.cleanup(permit -> backend.close(handle, permit));
        assertEquals(true, evidence.get("native_cleanup_verified"));
        assertEquals(true, evidence.get("persisted_state_verified"));
        assertEquals(true, evidence.get("native_dialogue_cleanup_verified"));
        verify(client, times(1)).invalidateDialogue();
        verify(client, times(1)).disconnect(false, false);
        verify(match, times(1)).dismissMatchConfirmation(41);
        assertEquals(evidence, execution.close(backend));
        ownership.verify(() -> ActorAccountOwnership.release(lease), times(1));
    }

    @Test
    void resumedCloseRefusesAForeignOwnerAndKeepsOwnershipReserved() {
        handle.beginClose(new NativeActorHandle.CloseContext(map, 41, 17, 0, "McpPrimary",
                1, 0, 50, 5, 0, 0, 10000));
        ownership.when(() -> ActorAccountOwnership.currentOnlineClient(17)).thenReturn(mock(Client.class));
        ObservationException failure = assertThrows(ObservationException.class, () -> execution.close(backend));
        assertEquals("actor_close_cleanup_unverified_stale_owner", failure.reason());
        verify(client, never()).disconnect(anyBoolean(), anyBoolean());
        ownership.verify(() -> ActorAccountOwnership.release(lease), never());
        verifyNoInteractions(accounts, characters);
    }

    @Test
    void snapshotReportsNativeIdentityAndBoundedPublicInventoryWithoutPasswordsOrPackets() {
        when(characters.findById(41)).thenReturn(stored());
        CashShop cashShop = mock(CashShop.class);
        when(character.getCashShop()).thenReturn(cashShop);
        when(cashShop.getCash(CashShop.NX_CREDIT)).thenReturn(101);
        when(cashShop.getCash(CashShop.MAPLE_POINT)).thenReturn(202);
        when(cashShop.getCash(CashShop.NX_PREPAID)).thenReturn(404);
        Map<String, Object> dialogue = Map.of("active", false, "supported", false);
        when(client.dialogueState()).thenReturn(dialogue);
        for (InventoryType type : List.of(InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.USE,
                InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
            Inventory inventory = mock(Inventory.class);
            when(inventory.list()).thenReturn(List.of());
            when(character.getInventory(type)).thenReturn(inventory);
        }
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(2000000);
        when(item.getPosition()).thenReturn((short) 1);
        when(item.getQuantity()).thenReturn((short) 7);
        when(character.getInventory(InventoryType.USE).list()).thenReturn(java.util.Collections.nCopies(101, item));
        Map<String, Object> snapshot = backend.snapshot(handle);
        assertEquals(41, snapshot.get("character_id"));
        assertEquals(17, snapshot.get("account_id"));
        assertEquals(false, snapshot.get("is_bot"));
        assertEquals(true, snapshot.get("persisted"));
        assertEquals(Map.of("x", 4, "y", 8), snapshot.get("position"));
        assertEquals(Map.of("nx_credit", 101, "maple_point", 202, "nx_prepaid", 404), snapshot.get("tickets_summary"));
        assertEquals(dialogue, snapshot.get("dialogue"));
        Map<?, ?> use = (Map<?, ?>) ((Map<?, ?>) snapshot.get("inventory_summary")).get("use");
        assertEquals(101, use.get("count"));
        assertEquals(true, use.get("truncated"));
        assertEquals(100, ((List<?>) use.get("items")).size());
        assertFalse(snapshot.toString().contains("test-private-password"));
        assertFalse(snapshot.containsKey("packet_payload"));
        verifyNoInteractions(accounts);
    }

    @Test
    void actorSnapshotReadsCurrentStatsEquipmentAndSceneWithoutRetainingNativeValues() {
        prepareObservedInventories();
        when(character.getTotalStr()).thenReturn(12);
        when(character.getTotalDex()).thenReturn(13);
        when(character.getTotalInt()).thenReturn(14);
        when(character.getTotalLuk()).thenReturn(15);
        when(character.getTotalWatk()).thenReturn(17);
        when(character.getTotalMagic()).thenReturn(18);
        when(character.getCurrentMaxHp()).thenReturn(60);
        when(character.getCurrentMaxMp()).thenReturn(40);

        Inventory equipped = character.getInventory(InventoryType.EQUIPPED);
        AtomicBoolean inventoryLocked = new AtomicBoolean();
        doAnswer(call -> { inventoryLocked.set(true); return null; }).when(equipped).lockInventory();
        doAnswer(call -> { inventoryLocked.set(false); return null; }).when(equipped).unlockInventory();
        Equip sword = observedEquip(-11, 1302000), cash = observedEquip(-111, 1702000);
        AtomicInteger strength = new AtomicInteger(3);
        when(sword.getStr()).thenAnswer(call -> { assertTrue(inventoryLocked.get()); return (short) strength.get(); });
        when(sword.getFlag()).thenReturn((short) 8);
        when(equipped.list()).thenReturn(List.of(sword, cash));

        MapItem drop = mock(MapItem.class), coins = mock(MapItem.class);
        Item item = mock(Item.class);
        Monster monster = mock(Monster.class);
        AtomicBoolean dropLocked = new AtomicBoolean();
        doAnswer(call -> { dropLocked.set(true); return null; }).when(drop).lockItem();
        doAnswer(call -> { dropLocked.set(false); return null; }).when(drop).unlockItem();
        when(drop.getObjectId()).thenReturn(30);
        Point dropPosition = new Point(1, 2);
        when(drop.getPosition()).thenReturn(dropPosition);
        when(drop.getItem()).thenAnswer(call -> { assertTrue(dropLocked.get()); return item; });
        when(item.getItemId()).thenReturn(2000000);
        when(item.getQuantity()).thenAnswer(call -> { assertTrue(dropLocked.get()); return (short) 4; });
        when(coins.getObjectId()).thenReturn(20);
        when(coins.getPosition()).thenReturn(new Point(3, 4));
        when(coins.getMeso()).thenReturn(12);
        when(monster.getObjectId()).thenReturn(10);
        when(monster.getId()).thenReturn(100100);
        when(monster.getPosition()).thenReturn(new Point(5, 6));
        when(monster.getHp()).thenAnswer(call -> { assertFalse(dropLocked.get()); return 8; });
        when(monster.getMaxHp()).thenReturn(10);
        when(map.getMapObjects()).thenReturn(List.of(drop, monster, coins));

        Map<String, Object> before = backend.snapshot(handle);
        assertEquals(Map.of("str", 12, "dex", 13, "int", 14, "luk", 15, "watk", 17, "magic", 18,
                "max_hp", 60, "max_mp", 40, "hp", 50, "mp", 5), before.get("stats"));
        Map<String, Object> equippedFacts = observedPage(before, "equipped");
        assertEquals(true, equippedFacts.get("complete"));
        assertEquals(2, equippedFacts.get("count"));
        assertEquals(-111, observedRows(equippedFacts).getFirst().get("slot"));
        Map<String, Object> swordFact = observedRows(equippedFacts).get(1);
        assertEquals(8, swordFact.get("flags"));
        assertEquals(3, observedPage(swordFact, "attributes").get("str"));
        Map<String, Object> scene = observedPage(before, "scene_objects");
        assertEquals("best_effort", scene.get("snapshot_consistency"));
        List<Map<String, Object>> drops = observedRows(observedPage(scene, "items"));
        assertEquals(20, drops.getFirst().get("object_id"));
        assertEquals(12, drops.getFirst().get("meso"));
        assertNull(drops.getFirst().get("item_id"));
        assertEquals(2000000, drops.get(1).get("item_id"));
        assertEquals(4, drops.get(1).get("quantity"));
        assertEquals(8, observedRows(observedPage(scene, "monsters")).getFirst().get("hp"));
        assertFalse(inventoryLocked.get());
        assertFalse(dropLocked.get());

        strength.set(7);
        dropPosition.translate(100, 100);
        when(character.getHp()).thenReturn(45);
        when(monster.getHp()).thenReturn(3);
        Map<String, Object> after = backend.snapshot(handle);
        assertEquals(50, observedPage(before, "stats").get("hp"));
        assertEquals(45, observedPage(after, "stats").get("hp"));
        assertEquals(3, observedPage(swordFact, "attributes").get("str"));
        assertEquals(7, observedPage(observedRows(observedPage(after, "equipped")).get(1), "attributes").get("str"));
        assertEquals(Map.of("x", 1, "y", 2), drops.get(1).get("position"));
        assertEquals(8, observedRows(observedPage(scene, "monsters")).getFirst().get("hp"));
    }

    @Test
    void actorSnapshotBoundsSceneCategoriesWithoutClaimingAnEmptyOrCompleteResult() {
        prepareObservedInventories();
        List<MapObject> objects = new ArrayList<>();
        for (int id = 1; id <= 101; id++) {
            MapItem drop = mock(MapItem.class);
            when(drop.getObjectId()).thenReturn(id);
            when(drop.getPosition()).thenReturn(new Point(id, 0));
            objects.add(drop);
            Monster monster = mock(Monster.class);
            when(monster.getObjectId()).thenReturn(1000 + id);
            when(monster.getPosition()).thenReturn(new Point(id, 0));
            objects.add(monster);
        }
        when(map.getMapObjects()).thenReturn(objects);
        Map<String, Object> scene = observedPage(backend.snapshot(handle), "scene_objects");
        for (String kind : List.of("items", "monsters")) {
            Map<String, Object> page = observedPage(scene, kind);
            assertEquals(100, observedRows(page).size());
            assertEquals(101, page.get("total_count"));
            assertEquals(true, page.get("truncated"));
            assertEquals(false, page.get("complete"));
        }
    }

    @Test
    void actorSnapshotNativeReadFailuresReleaseResourcesAndDoNotReturnPartialSuccess() {
        prepareObservedInventories();
        Inventory equipped = character.getInventory(InventoryType.EQUIPPED);
        AtomicBoolean locked = new AtomicBoolean();
        doAnswer(call -> { locked.set(true); return null; }).when(equipped).lockInventory();
        doAnswer(call -> { locked.set(false); return null; }).when(equipped).unlockInventory();
        when(equipped.list()).thenThrow(new IllegalStateException("equipment unavailable"));
        assertThrows(RuntimeException.class, () -> backend.snapshot(handle));
        assertFalse(locked.get());

        doReturn(List.of()).when(equipped).list();
        MapItem broken = mock(MapItem.class);
        when(broken.getItem()).thenThrow(new IllegalStateException("drop unavailable"));
        when(map.getMapObjects()).thenReturn(List.of(broken));
        assertThrows(RuntimeException.class, () -> backend.snapshot(handle));
        verify(broken).unlockItem();
        when(map.getMapObjects()).thenThrow(new IllegalStateException("map unavailable"));
        assertThrows(RuntimeException.class, () -> backend.snapshot(handle));
    }

    private void prepareObservedInventories() {
        when(characters.findById(41)).thenReturn(stored());
        for (InventoryType type : List.of(InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.USE,
                InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
            Inventory inventory = mock(Inventory.class);
            when(inventory.list()).thenReturn(List.of());
            when(character.getInventory(type)).thenReturn(inventory);
        }
    }

    private static Equip observedEquip(int slot, int itemId) {
        Equip equip = mock(Equip.class);
        when(equip.getPosition()).thenReturn((short) slot);
        when(equip.getItemId()).thenReturn(itemId);
        when(equip.getQuantity()).thenReturn((short) 1);
        return equip;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> observedPage(Map<String, Object> facts, String key) {
        return (Map<String, Object>) facts.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> observedRows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void persistenceVerificationChecksNativeMapRulesAndRejectsChangedIdentityAndState() {
        CharactersDO stored = stored();
        assertTrue(backend.matchesPersistedState(handle, stored, map));
        when(map.getForcedReturnId()).thenReturn(999);
        assertFalse(backend.matchesPersistedState(handle, stored, map));
        stored.setMap(999);
        assertTrue(backend.matchesPersistedState(handle, stored, map));
        when(map.getForcedReturnId()).thenReturn(MapId.NONE);
        when(character.getHp()).thenReturn(0);
        when(map.getReturnMapId()).thenReturn(100);
        stored.setMap(100);
        stored.setHp(0);
        assertTrue(backend.matchesPersistedState(handle, stored, map));
        stored.setAccountid(18);
        assertFalse(backend.matchesPersistedState(handle, stored, map));
        stored.setAccountid(17);
        stored.setMeso(1);
        assertFalse(backend.matchesPersistedState(handle, stored, map));
        verifyNoInteractions(accounts, characters);
    }

    @Test
    void syntheticLoopbackAddressIsStableAndDistinctForProfileAccounts() {
        assertEquals("127.0.0.17", NativeActorBackend.remoteAddress(17));
        assertNotEquals(NativeActorBackend.remoteAddress(17), NativeActorBackend.remoteAddress(18));
        assertEquals("127.1.2.3", NativeActorBackend.remoteAddress(0x010203));
    }

    @Test
    void sameJvmWorldRestartInitializesNativeAccountViewBeforeTheNextSave() throws Exception {
        World restartedWorld = nativeWorldWithEmptyAccountViews();
        Server retainedServer = mock(Server.class);
        ReentrantReadWriteLock loginLock = new ReentrantReadWriteLock();
        Map<Integer, Set<Integer>> retainedAccounts = new HashMap<>();
        retainedAccounts.put(17, new HashSet<>(Set.of(41)));
        Map<Integer, Integer> retainedWorlds = new HashMap<>(Map.of(41, 0));
        Map<Integer, Short> retainedCounts = new HashMap<>(Map.of(17, (short) 1));
        nativeField(Server.class, retainedServer, "lgnRLock", loginLock.readLock());
        nativeField(Server.class, retainedServer, "lgnWLock", loginLock.writeLock());
        nativeField(Server.class, retainedServer, "accountChars", retainedAccounts);
        nativeField(Server.class, retainedServer, "worldChars", retainedWorlds);
        nativeField(Server.class, retainedServer, "accountCharacterCount", retainedCounts);
        when(retainedServer.getWorld(0)).thenReturn(restartedWorld);
        when(retainedServer.getWorlds()).thenReturn(List.of(restartedWorld));
        doCallRealMethod().when(retainedServer).loadAccountCharacters(client);
        doCallRealMethod().when(retainedServer).updateCharacterEntry(character);
        when(character.generateCharacterEntry()).thenReturn(character);

        // Reproduce #67 using the native cached-account early return and exact failing save prefix.
        retainedServer.loadAccountCharacters(client);
        assertThrows(NullPointerException.class, () -> retainedServer.updateCharacterEntry(character));

        NativeActorBackend.prepareCharacterViews(retainedServer, client, restartedWorld);
        assertDoesNotThrow(() -> retainedServer.updateCharacterEntry(character));
        assertEquals(List.of(character), restartedWorld.getAccountCharactersView(17));
        assertEquals(Set.of(41), retainedAccounts.get(17));
        assertEquals(0, retainedWorlds.get(41));
        assertEquals((short) 1, retainedCounts.get(17));
        verifyNoInteractions(accounts, characters);
    }

    @Test
    void nativeAccountViewPreparationPreservesExistingOtherCharacters() throws Exception {
        World currentWorld = nativeWorldWithEmptyAccountViews();
        Character other = mock(Character.class);
        when(other.getId()).thenReturn(42);
        currentWorld.loadAccountCharactersView(17, List.of(other));

        NativeActorBackend.prepareCharacterViews(server, client, currentWorld);
        currentWorld.registerAccountCharacterView(17, character);

        assertEquals(List.of(character, other), currentWorld.getAccountCharactersView(17));
        verify(server).loadAccountCharacters(client);
        verifyNoInteractions(accounts, characters);
    }

    private static World nativeWorldWithEmptyAccountViews() throws Exception {
        World nativeWorld = mock(World.class, CALLS_REAL_METHODS);
        nativeField(World.class, nativeWorld, "accountChars", new HashMap<Integer, SortedMap<Integer, Character>>());
        nativeField(World.class, nativeWorld, "accountCharsLock", new ReentrantLock());
        return nativeWorld;
    }

    private static void nativeField(Class<?> declaringClass, Object target, String name, Object value) throws Exception {
        Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private NativeActorHandle open(java.util.function.BooleanSupplier cancelled) {
        return new NativeActorExecutionFixture().open(backend, cancelled);
    }

    private AccountsDO account() {
        AccountsDO account = new AccountsDO();
        account.setId(17);
        account.setName("mcp_primary");
        account.setWebadmin(0);
        account.setBanned(false);
        account.setLoggedin(Client.LOGIN_NOTLOGGEDIN);
        return account;
    }

    private CharactersDO stored() {
        CharactersDO row = new CharactersDO();
        row.setId(41);
        row.setAccountid(17);
        row.setName("McpPrimary");
        row.setWorld(0);
        row.setGm(0);
        row.setLevel(1);
        row.setExp(0);
        row.setHp(50);
        row.setMp(5);
        row.setMeso(0);
        row.setJob(0);
        row.setMap(10000);
        row.setParty(-1);
        return row;
    }
}
