package soloMapling.game.scene;

import org.gms.net.packet.Packet;

/** Scene-local behavior. Native facts and the member's original execution ownership stay authoritative. */
public interface SceneRuntimeModule {
    default void onEnter(SceneActorBinding actor) { }
    default void onLeave(SceneActorBinding actor) { }
    default void onPacket(SceneActorBinding actor, Packet packet) { }
    /** Called after every departed member's scene task has actually settled. */
    default void close() { }
}
