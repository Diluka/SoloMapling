package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;

import java.util.Map;
import java.util.Objects;

/** An owned native player session, bound to the exact runtime objects that admitted it. */
public final class NativeActorHandle {
    private final String profileId;
    private final int accountId;
    private final AcceptanceClient client;
    private final Character character;
    private final World world;
    private final Channel channel;
    private final ActorAccountOwnership.Lease ownership;
    private final boolean accountCreated;
    private final boolean characterCreated;
    private volatile Map<String, Object> closeEvidence;
    private volatile CloseContext closeContext;

    NativeActorHandle(String profileId, int accountId, AcceptanceClient client, Character character,
                      World world, Channel channel, ActorAccountOwnership.Lease ownership,
                      boolean accountCreated, boolean characterCreated) {
        this.profileId = Objects.requireNonNull(profileId);
        this.accountId = accountId;
        this.client = Objects.requireNonNull(client);
        this.character = Objects.requireNonNull(character);
        this.world = Objects.requireNonNull(world);
        this.channel = Objects.requireNonNull(channel);
        this.ownership = Objects.requireNonNull(ownership);
        this.accountCreated = accountCreated;
        this.characterCreated = characterCreated;
    }

    public String profileId() { return profileId; }
    public int accountId() { return accountId; }
    public AcceptanceClient client() { return client; }
    public Character character() { return character; }
    public World world() { return world; }
    public Channel channel() { return channel; }
    ActorAccountOwnership.Lease ownership() { return ownership; }
    public boolean accountCreated() { return accountCreated; }
    public boolean characterCreated() { return characterCreated; }
    Map<String, Object> closeEvidence() { return closeEvidence; }
    void recordClose(Map<String, Object> evidence) { closeEvidence = Map.copyOf(evidence); }
    CloseContext closeContext() { return closeContext; }
    void beginClose(CloseContext context) { closeContext = Objects.requireNonNull(context); }

    /** Survives native Character.empty(), including delayed removal of the character's map/client. */
    record CloseContext(MapleMap lastMap, int characterId, int accountId, int worldId, String name,
                        int level, int exp, int hp, int mp, int meso, int jobId, int nativeSavedMap) { }
}
