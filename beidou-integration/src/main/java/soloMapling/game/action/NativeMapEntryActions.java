package soloMapling.game.action;

import io.netty.buffer.Unpooled;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.PlayerMapTransitionHandler;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;

/** Native client acknowledgement of an already-observed map entry, inside the original role lane. */
public final class NativeMapEntryActions {
    private NativeMapEntryActions() { }

    public static boolean acknowledge(Server server, GameContext.SceneRef enteredScene, Runnable dispatchStarted) {
        return acknowledge(server, enteredScene, () -> {}, dispatchStarted);
    }

    /** Scene membership adds its exact entry qualification to both native dispatch checkpoints. */
    public static boolean acknowledge(Server server, GameContext.SceneRef enteredScene,
                                      Runnable requireEntryCurrent, Runnable dispatchStarted) {
        return NativePacketDispatch.dispatch(enteredScene.actor().client(), new PlayerMapTransitionHandler(),
                Unpooled.buffer(0), () -> {
                    NativeContextChecks.requireSceneCurrent(server, enteredScene);
                    requireEntryCurrent.run();
                }, dispatchStarted);
    }
}
