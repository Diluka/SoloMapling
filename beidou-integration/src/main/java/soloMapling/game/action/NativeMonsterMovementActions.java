package soloMapling.game.action;

import io.netty.buffer.Unpooled;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.MoveLifeHandler;
import org.gms.server.life.Monster;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.scene.MonsterControlCoordinator;

import java.awt.Point;
import java.util.concurrent.CancellationException;

/** Finite trusted monster ground input; caller already owns the controller's short role lane work. */
public final class NativeMonsterMovementActions {
    private NativeMonsterMovementActions() { }

    public record MovementResult(boolean dispatched, boolean handlerError, boolean positionConfirmed,
                                 int objectId, int xBefore, int yBefore, int xAfter, int yAfter) { }

    public static MovementResult move(Server server, GameContext.SceneRef scene,
                                      ExecutionScope.WorkHandle work, Monster monster, short moveId,
                                      Point destination, int velocityX, int stance, int foothold, int durationMs) {
        var before = new Point(monster.getPosition());
        var dispatched = new boolean[1];
        // The native monster protocol uses a two-pixel wire offset, including start position.
        var packet = Unpooled.buffer(50).writeIntLE(monster.getObjectId()).writeShortLE(moveId)
                .writeByte(0).writeByte(-1).writeByte(0).writeByte(0).writeShortLE(0).writeZero(8)
                .writeByte(0).writeIntLE(0).writeShortLE(before.x).writeShortLE(before.y + 2)
                .writeByte(1).writeByte(0).writeShortLE(destination.x).writeShortLE(destination.y + 2)
                .writeShortLE(velocityX).writeShortLE(0).writeShortLE(foothold)
                .writeByte(stance).writeShortLE(durationMs);
        boolean handlerError = NativePacketDispatch.dispatch(scene.actor().client(), new MoveLifeHandler(), packet,
                () -> {
                    if (work.cancelRequested()) throw new CancellationException("monster_movement_cancelled");
                    NativeContextChecks.requireSceneCurrent(server, scene);
                    if (scene.map().getMonsterByOid(monster.getObjectId()) != monster || !monster.isAlive()
                            || monster.getController() != scene.actor().character()
                            || !MonsterControlCoordinator.canControl(monster, scene.actor().character())) {
                        throw new NativeActionService.Invalid("monster_controller_changed");
                    }
                }, () -> dispatched[0] = true);
        var after = monster.getPosition();
        return new MovementResult(dispatched[0], handlerError, destination.equals(after), monster.getObjectId(),
                before.x, before.y, after.x, after.y);
    }
}
