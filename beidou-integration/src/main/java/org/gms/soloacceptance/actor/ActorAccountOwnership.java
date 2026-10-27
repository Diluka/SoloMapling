package org.gms.soloacceptance.actor;

import org.gms.client.Client;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixture account is reserved before native login and released only after native logout.
 * All guarded coordinator operations use the same bounded set of account locks.
 */
public final class ActorAccountOwnership {
    private static final Object[] LOCKS = new Object[256];
    private static final Map<Integer, Lease> LEASES = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private ActorAccountOwnership() { }

    public static Object accountLock(int accountId) {
        return LOCKS[(accountId ^ (accountId >>> 16)) & (LOCKS.length - 1)];
    }

    public static Lease claim(int accountId) {
        if (accountId <= 0) throw error(HttpStatus.BAD_REQUEST, "invalid_actor_account");
        synchronized (accountLock(accountId)) {
            if (LEASES.containsKey(accountId) || currentOnlineClient(accountId) != null) {
                throw error(HttpStatus.CONFLICT, "actor_account_in_use");
            }
            Lease lease = new Lease(accountId);
            LEASES.put(accountId, lease);
            return lease;
        }
    }

    public static void authorize(Lease lease, Client client) {
        if (lease == null || !(client instanceof AcceptanceClient)) {
            throw error(HttpStatus.BAD_REQUEST, "invalid_actor_client");
        }
        synchronized (accountLock(lease.accountId)) {
            requireLease(lease);
            if (client.getAccID() > 0 && client.getAccID() != lease.accountId) {
                throw error(HttpStatus.CONFLICT, "actor_account_mismatch");
            }
            // LOGIN and CHANNEL are the only two objects in a native handoff.
            if (!lease.clients.containsKey(client) && lease.clients.size() >= 2) {
                throw error(HttpStatus.CONFLICT, "actor_client_capacity_exceeded");
            }
            lease.clients.put(client, Boolean.TRUE);
        }
    }

    public static Client currentOnlineClient(int accountId) {
        synchronized (accountLock(accountId)) {
            Object coordinator = SessionCoordinator.getInstance();
            if (!(coordinator instanceof AcceptanceOnlineClientLookup lookup)) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_ownership_guard_unavailable");
            }
            return lookup.soloAcceptanceCurrentOnlineClient(accountId);
        }
    }

    public static boolean owns(Lease lease, Client client) {
        if (lease == null || client == null) return false;
        synchronized (accountLock(lease.accountId)) {
            return LEASES.get(lease.accountId) == lease && lease.clients.containsKey(client);
        }
    }

    public static boolean release(Lease lease) {
        if (lease == null) return false;
        synchronized (accountLock(lease.accountId)) {
            if (LEASES.get(lease.accountId) != lease) return false;
            if (currentOnlineClient(lease.accountId) != null) {
                throw error(HttpStatus.CONFLICT, "actor_online_cleanup_unverified");
            }
            LEASES.remove(lease.accountId, lease);
            lease.clients.clear();
            return true;
        }
    }

    /** Called under the account lock before the native method can force-disconnect an owner. */
    public static void requirePublication(Client incoming, Client current) {
        if (incoming == null) return;
        Lease lease = LEASES.get(incoming.getAccID());
        if (incoming instanceof AcceptanceClient) {
            if (lease == null || !lease.clients.containsKey(incoming)) {
                throw error(HttpStatus.CONFLICT, "actor_account_ownership_lost");
            }
            if (current != null && current != incoming) {
                throw error(HttpStatus.CONFLICT, "actor_account_in_use");
            }
        } else if (lease != null || current instanceof AcceptanceClient) {
            throw error(HttpStatus.CONFLICT, "actor_account_reserved");
        }
    }

    public static boolean mayFinishLogin(Client incoming, Client current) {
        try {
            requirePublication(incoming, current);
            return true;
        } catch (ObservationException conflict) {
            return false;
        }
    }

    /** A late logout may close its own transport, never remove a different protected owner. */
    public static boolean staleProtectedClose(Client closing, Client current) {
        return closing != null && current != null && closing != current
                && (closing instanceof AcceptanceClient || current instanceof AcceptanceClient
                    || LEASES.containsKey(closing.getAccID()));
    }

    /** Preserve the current account's database state when a stale native cleanup resumes. */
    public static boolean mayUpdateLoginState(Client client, int state, Client current) {
        if (client == null) return false;
        Lease lease = LEASES.get(client.getAccID());
        if (state == Client.LOGIN_LOGGEDIN) {
            requirePublication(client, current);
            return true;
        }
        if (staleProtectedClose(client, current)) return false;
        if (client instanceof AcceptanceClient) {
            return lease != null && lease.clients.containsKey(client);
        }
        return lease == null;
    }

    private static void requireLease(Lease lease) {
        if (LEASES.get(lease.accountId) != lease) {
            throw error(HttpStatus.CONFLICT, "actor_account_ownership_lost");
        }
    }

    private static ObservationException error(HttpStatus status, String reason) {
        return new ObservationException(status, reason);
    }

    public static final class Lease {
        private final int accountId;
        private final IdentityHashMap<Client, Boolean> clients = new IdentityHashMap<>();

        private Lease(int accountId) { this.accountId = accountId; }
        public int accountId() { return accountId; }
    }
}
