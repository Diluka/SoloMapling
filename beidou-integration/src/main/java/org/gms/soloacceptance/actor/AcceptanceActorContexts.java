package org.gms.soloacceptance.actor;

import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.BotRegistry;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ScopeResources;

/** MCP ownership policy around the shared, identity-only native context checks. */
public final class AcceptanceActorContexts {
    private AcceptanceActorContexts() { }

    public static GameContext.CharacterRef requireCharacter(Server server, NativeActorHandle handle) {
        try {
            requireIdentity(handle);
            NativeContextChecks.requireCharacterCurrent(server, handle.characterRef());
            return handle.characterRef();
        } catch (GameContext.Invalid invalid) {
            throw boundaryFailure(invalid);
        } catch (ObservationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
        }
    }

    public static GameContext.SceneRef captureScene(Server server, NativeActorHandle handle) {
        GameContext.CharacterRef actor = requireCharacter(server, handle);
        try {
            return NativeContextChecks.captureScene(server, actor);
        } catch (GameContext.Invalid invalid) {
            throw boundaryFailure(invalid);
        } catch (RuntimeException unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
        }
    }

    public static void requireSceneCurrent(Server server, GameContext.SceneRef scene) {
        try {
            NativeContextChecks.requireSceneCurrent(server, scene);
        } catch (GameContext.Invalid invalid) {
            throw boundaryFailure(invalid);
        } catch (RuntimeException unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
        }
    }

    /** Only the executing cleanup work may use its exact owned result during draining. */
    public static void requireDrainPermit(NativeActorHandle handle, ScopeResources.DrainPermit permit) {
        try {
            if (permit == null) throw new IllegalStateException("missing cleanup permit");
            permit.requireExecuting(handle);
        } catch (RuntimeException invalid) {
            throw error(HttpStatus.CONFLICT, "actor_close_cleanup_unverified_scope");
        }
    }

    /** The resource-specific permission never grants an ordinary character action lease. */
    public static void requireCloseCharacter(Server server, NativeActorHandle handle,
                                             ScopeResources.DrainPermit permit) {
        requireDrainPermit(handle, permit);
        try {
            requireIdentity(handle);
            World world = server.getWorld(handle.world().getId());
            Character character = handle.character();
            MapleMap map = character.getMap();
            if (world != handle.world() || world.getChannel(handle.channel().getId()) != handle.channel()
                    || handle.client().getWorld() != world.getId()
                    || handle.client().getChannel() != handle.channel().getId()
                    || world.getPlayerStorage() == null || handle.channel().getPlayerStorage() == null
                    || world.getPlayerStorage().getCharacterById(character.getId()) != character
                    || handle.channel().getPlayerStorage().getCharacterById(character.getId()) != character
                    || map == null || map.getCharacterById(character.getId()) != character) {
                throw error(HttpStatus.CONFLICT, "actor_close_cleanup_unverified_stale_session");
            }
        } catch (ObservationException known) {
            if (known.status() == HttpStatus.SERVICE_UNAVAILABLE) throw known;
            throw error(HttpStatus.CONFLICT, "actor_close_cleanup_unverified_stale_session");
        } catch (RuntimeException unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
        }
    }

    private static void requireIdentity(NativeActorHandle handle) {
        if (handle == null || handle.closeEvidence() != null || handle.accountId() < 1
                || handle.profileId() == null || handle.profileId().isBlank()) {
            throw error(HttpStatus.CONFLICT, "actor_session_stale");
        }
        AcceptanceClient client = handle.client();
        Character character = handle.character();
        if (handle.characterRef() == null || handle.characterRef().character() != character
                || handle.characterRef().client() != client || handle.characterRef().world() != handle.world()
                || handle.characterRef().channel() != handle.channel() || character.getClient() != client
                || client.getPlayer() != character || client.getAccID() != handle.accountId()
                || character.getAccountId() != handle.accountId() || !client.isLoggedIn()
                || !character.isLoggedInWorld() || client.transportClosed() || character.gmLevel() != 0
                || BotRegistry.isBot(character) || !ActorAccountOwnership.owns(handle.ownership(), client)
                || ActorAccountOwnership.currentOnlineClient(handle.accountId()) != client) {
            throw error(HttpStatus.CONFLICT, "actor_session_stale");
        }
    }

    private static ObservationException boundaryFailure(GameContext.Invalid invalid) {
        return switch (invalid.reason()) {
            case WORLD_OFFLINE -> error(HttpStatus.SERVICE_UNAVAILABLE, "actor_world_offline");
            case WORLD_CHANGED -> error(HttpStatus.GONE, "stale_actor_session");
            case RUNTIME_CHANGED -> error(HttpStatus.CONFLICT, "runtime_changed");
            case READ_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "actor_observation_unavailable");
            case SCENE_CHANGED, EVENT_CHANGED -> error(HttpStatus.CONFLICT, "actor_context_mismatch");
            case CHARACTER_CHANGED, NOT_IN_WORLD -> error(HttpStatus.CONFLICT, "actor_session_stale");
        };
    }

    private static ObservationException error(HttpStatus status, String reason) {
        return new ObservationException(status, reason);
    }
}
