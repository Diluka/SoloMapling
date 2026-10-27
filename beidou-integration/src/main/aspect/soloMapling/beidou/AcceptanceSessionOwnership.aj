package soloMapling.beidou;

import org.gms.client.Client;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.soloacceptance.actor.AcceptanceClient;
import org.gms.soloacceptance.actor.AcceptanceOnlineClientLookup;
import org.gms.soloacceptance.actor.ActorAccountOwnership;

/** Protect only acceptance ownership; ordinary client replacement keeps the native behavior. */
public privileged aspect AcceptanceSessionOwnership {
    declare parents: SessionCoordinator implements AcceptanceOnlineClientLookup;

    public Client SessionCoordinator.soloAcceptanceCurrentOnlineClient(int accountId) {
        synchronized (ActorAccountOwnership.accountLock(accountId)) {
            return onlineClients.get(accountId);
        }
    }

    int around(Client client): execution(int Client.finishLogin()) && this(client) {
        synchronized (ActorAccountOwnership.accountLock(client.getAccID())) {
            Client current = ActorAccountOwnership.currentOnlineClient(client.getAccID());
            if (!ActorAccountOwnership.mayFinishLogin(client, current)) return 7;
            return proceed(client);
        }
    }

    int around(Client client): execution(int Client.getLoginState()) && this(client) {
        synchronized (ActorAccountOwnership.accountLock(client.getAccID())) {
            Client current = ActorAccountOwnership.currentOnlineClient(client.getAccID());
            // Native getLoginState also resets an in-progress transition directly in SQL.
            // Reject a competing session before that method can touch the owner's state.
            if (!ActorAccountOwnership.mayFinishLogin(client, current)) return Client.LOGIN_LOGGEDIN;
            return proceed(client);
        }
    }

    void around(SessionCoordinator coordinator, Client incoming):
            execution(void SessionCoordinator.updateOnlineClient(Client)) && this(coordinator) && args(incoming) {
        if (incoming == null) {
            proceed(coordinator, incoming);
            return;
        }
        synchronized (ActorAccountOwnership.accountLock(incoming.getAccID())) {
            Client current = coordinator.onlineClients.get(incoming.getAccID());
            ActorAccountOwnership.requirePublication(incoming, current);
            // The native method otherwise force-disconnects this same object on a repeated update.
            if (incoming instanceof AcceptanceClient && current == incoming) return;
            proceed(coordinator, incoming);
        }
    }

    void around(Client client, int state): execution(void Client.updateLoginState(int)) && this(client) && args(state) {
        synchronized (ActorAccountOwnership.accountLock(client.getAccID())) {
            Client current = ActorAccountOwnership.currentOnlineClient(client.getAccID());
            if (!ActorAccountOwnership.mayUpdateLoginState(client, state, current)) return;
            proceed(client, state);
        }
    }

    void around(SessionCoordinator coordinator, Client closing):
            execution(void SessionCoordinator.closeLoginSession(Client)) && this(coordinator) && args(closing) {
        if (closing == null) {
            proceed(coordinator, closing);
            return;
        }
        synchronized (ActorAccountOwnership.accountLock(closing.getAccID())) {
            Client current = coordinator.onlineClients.get(closing.getAccID());
            if (ActorAccountOwnership.staleProtectedClose(closing, current)) {
                closeStaleTransport(coordinator, closing, current);
                return;
            }
            proceed(coordinator, closing);
        }
    }

    void around(SessionCoordinator coordinator, Client closing, Boolean immediately):
            execution(void SessionCoordinator.closeSession(Client, Boolean)) && this(coordinator) && args(closing, immediately) {
        if (closing == null) {
            proceed(coordinator, closing, immediately);
            return;
        }
        synchronized (ActorAccountOwnership.accountLock(closing.getAccID())) {
            Client current = coordinator.onlineClients.get(closing.getAccID());
            if (ActorAccountOwnership.staleProtectedClose(closing, current)) {
                closeStaleTransport(coordinator, closing, current);
                return;
            }
            proceed(coordinator, closing, immediately);
        }
    }

    private static void closeStaleTransport(SessionCoordinator coordinator, Client closing, Client current) {
        // Native closeSession removes onlineClients without an identity check. Still release
        // only the stale client's transport/HWID/login reservation, not the new owner's entry.
        String remoteHost = SessionCoordinator.getSessionRemoteHost(closing);
        String remoteAddress = closing.getRemoteAddress();
        if (remoteAddress != null) coordinator.loginRemoteHosts.remove(remoteAddress, closing);
        if (remoteHost != null) coordinator.loginRemoteHosts.remove(remoteHost, closing);
        Hwid staleHwid = closing.getHwid();
        if (staleHwid != null && !staleHwid.equals(current.getHwid())) {
            coordinator.onlineRemoteHwids.remove(staleHwid);
        }
        closing.setHwid(null);
        closing.closeSession();
    }
}
