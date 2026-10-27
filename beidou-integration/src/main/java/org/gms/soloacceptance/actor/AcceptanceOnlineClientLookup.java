package org.gms.soloacceptance.actor;

import org.gms.client.Client;

/** Narrow read-only view woven into the native coordinator; no reflective field access. */
public interface AcceptanceOnlineClientLookup {
    Client soloAcceptanceCurrentOnlineClient(int accountId);
}
