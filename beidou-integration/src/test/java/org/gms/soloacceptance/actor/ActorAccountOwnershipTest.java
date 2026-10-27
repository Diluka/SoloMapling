package org.gms.soloacceptance.actor;

import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActorAccountOwnershipTest {
    private static final AtomicInteger IDS = new AtomicInteger(930_000_000);

    @BeforeAll
    static void context() { CharacterTestContext.install(); }

    @Test
    void onlyOneConcurrentClaimCanOwnAnOfflineAccount() throws Exception {
        int id = IDS.incrementAndGet();
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var lease = new AtomicReference<ActorAccountOwnership.Lease>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> claimAfter(start, id, successes, lease));
            var second = executor.submit(() -> claimAfter(start, id, successes, lease));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            assertEquals(1, successes.get());
            assertNotNull(lease.get());
            ActorAccountOwnership.release(lease.get());
        }
    }

    @Test
    void onlyExplicitAcceptanceClientsMayUseTheCurrentLease() {
        int id = IDS.incrementAndGet();
        ActorAccountOwnership.Lease lease = ActorAccountOwnership.claim(id);
        try {
            Client ordinary = mock(Client.class);
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                    () -> ActorAccountOwnership.authorize(lease, ordinary)).status());
            AcceptanceClient login = actor(id, Client.Type.LOGIN);
            AcceptanceClient channel = actor(id, Client.Type.CHANNEL);
            ActorAccountOwnership.authorize(lease, login);
            ActorAccountOwnership.authorize(lease, channel);
            assertTrue(ActorAccountOwnership.owns(lease, login));
            assertTrue(ActorAccountOwnership.owns(lease, channel));
            assertFalse(ActorAccountOwnership.owns(lease, actor(id, Client.Type.CHANNEL)));
            assertThrows(ObservationException.class,
                    () -> ActorAccountOwnership.authorize(lease, actor(id, Client.Type.CHANNEL)));
            assertThrows(ObservationException.class,
                    () -> ActorAccountOwnership.requirePublication(ordinary, channel));
            assertFalse(ActorAccountOwnership.mayFinishLogin(ordinary, channel));
        } finally {
            assertTrue(ActorAccountOwnership.release(lease));
        }
    }

    @Test
    void releasedLeaseCannotAuthorizeClientsOrReleaseItsReplacement() {
        int id = IDS.incrementAndGet();
        ActorAccountOwnership.Lease old = ActorAccountOwnership.claim(id);
        AcceptanceClient previous = actor(id, Client.Type.LOGIN);
        ActorAccountOwnership.authorize(old, previous);
        assertTrue(ActorAccountOwnership.release(old));
        ActorAccountOwnership.Lease replacement = ActorAccountOwnership.claim(id);
        try {
            assertFalse(ActorAccountOwnership.release(old));
            assertThrows(ObservationException.class, () -> ActorAccountOwnership.authorize(old, previous));
            assertFalse(ActorAccountOwnership.owns(old, previous));
            assertFalse(ActorAccountOwnership.mayUpdateLoginState(previous, Client.LOGIN_NOTLOGGEDIN, null));
        } finally {
            assertTrue(ActorAccountOwnership.release(replacement));
        }
    }

    private static void claimAfter(CountDownLatch start, int id, AtomicInteger successes,
                                   AtomicReference<ActorAccountOwnership.Lease> lease) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            lease.set(ActorAccountOwnership.claim(id));
            successes.incrementAndGet();
        } catch (ObservationException rejected) {
            assertEquals(HttpStatus.CONFLICT, rejected.status());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    static AcceptanceClient actor(int accountId, Client.Type type) {
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        AcceptanceClient client = spy(new AcceptanceClient(type, accountId * 10L + type.ordinal(),
                "127.0.0.1", world, channel));
        client.setAccID(accountId);
        return client;
    }
}
