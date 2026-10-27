package soloMapling.game.context;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;

import java.util.Objects;

/** Internal native references. Constructing a reference never grants control of a character. */
public final class GameContext {
    private GameContext() { }

    public record CharacterRef(WorldEpoch.Token epoch, Character character, Client client,
                               World world, Channel channel) { }

    public record SceneRef(CharacterRef actor, MapleMap map, EventInstanceManager memberEim,
                           EventInstanceManager mapEim) { }

    public enum Failure {
        WORLD_OFFLINE, WORLD_CHANGED, CHARACTER_CHANGED, NOT_IN_WORLD,
        SCENE_CHANGED, EVENT_CHANGED, RUNTIME_CHANGED, READ_UNAVAILABLE
    }

    /** Fixed structural failure, with no transport status or native exception text attached. */
    public static final class Invalid extends RuntimeException {
        private final Failure reason;

        public Invalid(Failure reason) {
            super(Objects.requireNonNull(reason).name(), null, false, false);
            this.reason = reason;
        }

        public Failure reason() { return reason; }
    }
}
