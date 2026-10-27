package org.gms.soloacceptance.actor;

import org.gms.client.Client;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.soloacceptance.ObservationException;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.CharacterTestContext;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WovenActorAccountOwnershipTest {
    private static final AtomicInteger IDS = new AtomicInteger(940_000_000);

    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void actorCannotClaimOrReplaceAnAlreadyOnlineOrdinaryClient() {
        int id = IDS.incrementAndGet();
        SessionCoordinator coordinator = SessionCoordinator.getInstance();
        Client ordinary = ordinary(id, 1);
        coordinator.updateOnlineClient(ordinary);
        try {
            assertSame(ordinary, ActorAccountOwnership.currentOnlineClient(id));
            assertThrows(ObservationException.class, () -> ActorAccountOwnership.claim(id));
            assertThrows(ObservationException.class,
                    () -> coordinator.updateOnlineClient(ActorAccountOwnershipTest.actor(id, Client.Type.LOGIN)));
            assertSame(ordinary, ActorAccountOwnership.currentOnlineClient(id));
            verify(ordinary, never()).forceDisconnect();
        } finally {
            coordinator.closeSession(ordinary, false);
        }
    }

    @Test
    void concurrentAuthorizedClientsCannotReplaceEachOtherOrDisconnectTheWinner() throws Exception {
        int id = IDS.incrementAndGet();
        SessionCoordinator coordinator = SessionCoordinator.getInstance();
        ActorAccountOwnership.Lease lease = ActorAccountOwnership.claim(id);
        AcceptanceClient first = ActorAccountOwnershipTest.actor(id, Client.Type.LOGIN);
        AcceptanceClient second = ActorAccountOwnershipTest.actor(id, Client.Type.CHANNEL);
        ActorAccountOwnership.authorize(lease, first);
        ActorAccountOwnership.authorize(lease, second);
        var ready = new CountDownLatch(1);
        var winners = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> publishAfter(coordinator, first, ready, winners));
            var b = executor.submit(() -> publishAfter(coordinator, second, ready, winners));
            ready.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            assertEquals(1, winners.get());
            Client winner = ActorAccountOwnership.currentOnlineClient(id);
            assertTrue(winner == first || winner == second);
            coordinator.updateOnlineClient(winner);
            verify(first, never()).forceDisconnect();
            verify(second, never()).forceDisconnect();
            assertThrows(ObservationException.class, () -> ActorAccountOwnership.release(lease));
        } finally {
            Client winner = ActorAccountOwnership.currentOnlineClient(id);
            winner.setHwid(new Hwid("12345678"));
            coordinator.closeSession(winner, false);
            ActorAccountOwnership.release(lease);
        }
    }

    @Test
    void nativeLoginHandoffAndStaleCleanupPreserveTheCurrentChannelOwner() {
        int id = IDS.incrementAndGet();
        SessionCoordinator coordinator = SessionCoordinator.getInstance();
        ActorAccountOwnership.Lease lease = ActorAccountOwnership.claim(id);
        AcceptanceClient login = ActorAccountOwnershipTest.actor(id, Client.Type.LOGIN);
        AcceptanceClient channel = ActorAccountOwnershipTest.actor(id, Client.Type.CHANNEL);
        ActorAccountOwnership.authorize(lease, login);
        ActorAccountOwnership.authorize(lease, channel);
        try (MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class)) {
            login.setHwid(new Hwid("12345678"));
            coordinator.updateOnlineClient(login);
            coordinator.closeLoginSession(login);
            assertNull(ActorAccountOwnership.currentOnlineClient(id));
            Client contender = ordinary(id, 3);
            doCallRealMethod().when(contender).getLoginState();
            assertEquals(Client.LOGIN_LOGGEDIN, contender.getLoginState());
            database.verifyNoInteractions();
            channel.setHwid(new Hwid("87654321"));
            coordinator.updateOnlineClient(channel);
            assertSame(channel, ActorAccountOwnership.currentOnlineClient(id));

            doCallRealMethod().when(contender).finishLogin();
            doCallRealMethod().when(contender).updateLoginState(Client.LOGIN_NOTLOGGEDIN);
            assertEquals(7, contender.finishLogin());
            assertEquals(Client.LOGIN_LOGGEDIN, contender.getLoginState());
            assertThrows(ObservationException.class, () -> coordinator.updateOnlineClient(contender));
            coordinator.closeSession(contender, false);
            coordinator.closeLoginSession(login);
            login.updateLoginState(Client.LOGIN_NOTLOGGEDIN);
            contender.updateLoginState(Client.LOGIN_NOTLOGGEDIN);
            assertSame(channel, ActorAccountOwnership.currentOnlineClient(id));
            assertEquals(new Hwid("87654321"), channel.getHwid());
            verify(channel, never()).forceDisconnect();
            verify(contender).closeSession();
            database.verifyNoInteractions();
        } finally {
            coordinator.closeSession(channel, false);
            assertTrue(ActorAccountOwnership.release(lease));
        }
    }

    @Test
    void nativeLoginStateReadsRemainAvailableToTheAuthorizedActorAndOrdinaryAccounts() throws Exception {
        int actorId = IDS.incrementAndGet();
        int ordinaryId = IDS.incrementAndGet();
        ActorAccountOwnership.Lease lease = ActorAccountOwnership.claim(actorId);
        AcceptanceClient actor = ActorAccountOwnershipTest.actor(actorId, Client.Type.LOGIN);
        ActorAccountOwnership.authorize(lease, actor);
        Client ordinary = ordinary(ordinaryId, 1);
        doCallRealMethod().when(ordinary).getLoginState();
        try (MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class)) {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class);
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(result);
            when(result.next()).thenReturn(true);
            when(result.getDate("birthday")).thenReturn(Date.valueOf("2000-01-01"));
            when(result.getInt("loggedin")).thenReturn(Client.LOGIN_NOTLOGGEDIN);

            assertEquals(Client.LOGIN_NOTLOGGEDIN, actor.getLoginState());
            assertEquals(Client.LOGIN_NOTLOGGEDIN, ordinary.getLoginState());
            verify(statement).setInt(1, actorId);
            verify(statement).setInt(1, ordinaryId);
            verify(statement, times(2)).executeQuery();
            verify(statement, never()).executeUpdate();
        } finally {
            assertTrue(ActorAccountOwnership.release(lease));
        }
    }

    @Test
    void ordinaryReplacementStillRunsTheNativeForceDisconnectPath() {
        int id = IDS.incrementAndGet();
        SessionCoordinator coordinator = SessionCoordinator.getInstance();
        Client first = ordinary(id, 1);
        Client next = ordinary(id, 2);
        coordinator.updateOnlineClient(first);
        try {
            coordinator.updateOnlineClient(next);
            verify(first).forceDisconnect();
            assertSame(next, ActorAccountOwnership.currentOnlineClient(id));
        } finally {
            coordinator.closeSession(next, false);
        }
    }

    @Test
    void lateCloseRetainsTheCurrentOwnersSharedHardwareAndLoginReservations() throws Exception {
        assertStaleHardwareCleanup(true);
    }

    @Test
    void lateCloseReleasesOnlyItsOwnDifferentHardwareAndLoginReservation() throws Exception {
        assertStaleHardwareCleanup(false);
    }

    @SuppressWarnings("unchecked")
    private static void assertStaleHardwareCleanup(boolean sharedHardware) throws Exception {
        int id = IDS.incrementAndGet();
        SessionCoordinator coordinator = SessionCoordinator.getInstance();
        ActorAccountOwnership.Lease lease = ActorAccountOwnership.claim(id);
        AcceptanceClient stale = ActorAccountOwnershipTest.actor(id, Client.Type.LOGIN);
        AcceptanceClient current = ActorAccountOwnershipTest.actor(id, Client.Type.CHANNEL);
        ActorAccountOwnership.authorize(lease, stale);
        ActorAccountOwnership.authorize(lease, current);
        Hwid previousHwid = new Hwid(String.format("%08X", id));
        Hwid currentHwid = sharedHardware ? previousHwid : new Hwid(String.format("%08X", id + 10_000));
        stale.setHwid(previousHwid);
        current.setHwid(currentHwid);
        var hwidsField = SessionCoordinator.class.getDeclaredField("onlineRemoteHwids");
        var loginsField = SessionCoordinator.class.getDeclaredField("loginRemoteHosts");
        hwidsField.setAccessible(true);
        loginsField.setAccessible(true);
        Set<Hwid> hardware = (Set<Hwid>) hwidsField.get(coordinator);
        Map<String, Client> reservations = (Map<String, Client>) loginsField.get(coordinator);
        String staleHost = SessionCoordinator.getSessionRemoteHost(stale);
        String currentHost = SessionCoordinator.getSessionRemoteHost(current);
        try {
            hardware.add(previousHwid);
            hardware.add(currentHwid);
            reservations.put(stale.getRemoteAddress(), current);
            reservations.put(staleHost, stale);
            reservations.put(currentHost, current);
            coordinator.updateOnlineClient(current);

            coordinator.closeSession(stale, false);
            coordinator.closeLoginSession(stale);

            assertTrue(stale.transportClosed());
            assertNull(stale.getHwid());
            assertSame(current, ActorAccountOwnership.currentOnlineClient(id));
            assertEquals(currentHwid, current.getHwid());
            assertTrue(hardware.contains(currentHwid));
            assertEquals(sharedHardware, hardware.contains(previousHwid));
            assertSame(current, reservations.get(current.getRemoteAddress()));
            assertSame(current, reservations.get(currentHost));
            if (!sharedHardware) assertFalse(reservations.containsKey(staleHost));
        } finally {
            coordinator.closeSession(current, false);
            reservations.remove(current.getRemoteAddress(), current);
            reservations.remove(currentHost, current);
            reservations.remove(staleHost, stale);
            hardware.remove(previousHwid);
            hardware.remove(currentHwid);
            assertTrue(ActorAccountOwnership.release(lease));
        }
    }

    private static Client ordinary(int id, int session) {
        Client client = mock(Client.class);
        when(client.getAccID()).thenReturn(id);
        when(client.getSessionId()).thenReturn(id * 10L + session);
        when(client.getRemoteAddress()).thenReturn("127.0.0.1");
        when(client.getHwid()).thenReturn(new Hwid("ABCDEF12"));
        return client;
    }

    private static void publishAfter(SessionCoordinator coordinator, Client client,
                                     CountDownLatch ready, AtomicInteger winners) {
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            coordinator.updateOnlineClient(client);
            winners.incrementAndGet();
        } catch (ObservationException rejected) {
            assertEquals("actor_account_in_use", rejected.reason());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
