package soloMapling.game.action;

import io.netty.buffer.Unpooled;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.ChangeMapHandler;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;

import java.util.concurrent.CancellationException;

/** Ordinary revive click, using the native handler's event decision and respawn implementation. */
public final class NativeReviveActions {
    private NativeReviveActions() { }

    /** Called by the provider's short lane work; invocationWork carries the enclosing call's cancellation. */
    public static ReviveResult revive(Server server, GameContext.SceneRef scene, ExecutionScope.WorkHandle invocationWork) {
        var ref = scene.actor();
        if (NativeBasicActions.cancelled(invocationWork)) {
            return new ReviveResult(ReviveResult.Outcome.CANCELLED, ref.character().getId(), ref.epoch().value(),
                    null, null, new ReviveResult.Dispatch(false, false, false),
                    new ReviveResult.Event(ReviveResult.EventRoute.UNCONFIRMED, false, false, false,
                            false, false, false, false, null), false, true, "revive_cancelled_before_dispatch");
        }
        NativeContextChecks.requireSceneCurrent(server, scene);
        var before = state(scene);
        if (before.alive()) {
            return new ReviveResult(ReviveResult.Outcome.ALREADY_ALIVE, ref.character().getId(), ref.epoch().value(),
                    before, before, new ReviveResult.Dispatch(false, false, false),
                    new ReviveResult.Event(scene.memberEim() == null ? ReviveResult.EventRoute.NOT_PRESENT
                            : ReviveResult.EventRoute.UNCONFIRMED, false, false, false,
                            false, false, false, false, null), false, invocationWork.cancelRequested(), null);
        }

        try (var evidence = NativeReviveEvidence.begin(ref.character(), scene.memberEim())) {
            boolean handlerReturned = false;
            boolean handlerError = false;
            boolean alreadyAliveBeforeDispatch = false;
            String reason = null;
            try {
                // BeiDou 1.12 ChangeMapHandler: dying=1, non--1 target marker, empty portal,
                // reserved=0, wheel=0, chasing=0. The native handler selects the real return map.
                handlerError = NativePacketDispatch.dispatch(ref.client(), new ChangeMapHandler(),
                        Unpooled.buffer(10).writeByte(1).writeIntLE(0).writeShortLE(0)
                                .writeByte(0).writeByte(0).writeByte(0),
                        () -> {
                            if (NativeBasicActions.cancelled(invocationWork)) throw new CancellationException();
                            NativeContextChecks.requireSceneCurrent(server, scene);
                            if (ref.character().isAlive()) throw new AlreadyAlive();
                        }, evidence::onDispatch);
                handlerReturned = !handlerError;
            } catch (AlreadyAlive alive) {
                alreadyAliveBeforeDispatch = true;
            } catch (CancellationException cancelled) {
                reason = "revive_cancelled_before_dispatch";
            } catch (NativeActionService.Invalid rejected) {
                reason = rejected.reason();
            } catch (GameContext.Invalid changed) {
                reason = changed.reason().name().toLowerCase(java.util.Locale.ROOT);
            }

            ReviveResult.State after = null;
            boolean sceneChanged = false;
            try {
                // Revive may legitimately change map/EIM; the old scene is only a dispatch precondition.
                var current = NativeContextChecks.captureScene(server, ref);
                after = state(current);
                sceneChanged = current.map() != scene.map() || current.memberEim() != scene.memberEim()
                        || current.mapEim() != scene.mapEim();
            } catch (GameContext.Invalid changed) {
                reason = changed.reason().name().toLowerCase(java.util.Locale.ROOT);
            }

            var fact = evidence.fact();
            var route = scene.memberEim() == null ? ReviveResult.EventRoute.NOT_PRESENT
                    : fact.eventReturned() == 0 ? ReviveResult.EventRoute.UNCONFIRMED
                    : fact.eventAllowsStandardPath() ? ReviveResult.EventRoute.STANDARD_ALLOWED
                    : ReviveResult.EventRoute.TAKEN_OVER;
            ReviveResult.Outcome outcome;
            if (alreadyAliveBeforeDispatch) {
                outcome = ReviveResult.Outcome.ALREADY_ALIVE;
            } else if (!evidence.dispatchStarted() && NativeBasicActions.cancelled(invocationWork)) {
                outcome = ReviveResult.Outcome.CANCELLED;
            } else if (after == null) {
                outcome = ReviveResult.Outcome.UNKNOWN;
            } else if (evidence.dispatchStarted() && after.alive() && after.hp() > 0) {
                outcome = ReviveResult.Outcome.REVIVED;
            } else {
                outcome = ReviveResult.Outcome.NOT_REVIVED;
                if (reason == null) reason = route == ReviveResult.EventRoute.TAKEN_OVER
                        ? "event_takeover_without_revive" : "native_revive_not_observed";
            }
            return new ReviveResult(outcome, ref.character().getId(), ref.epoch().value(), before, after,
                    new ReviveResult.Dispatch(evidence.dispatchStarted(), handlerReturned, handlerError),
                    new ReviveResult.Event(route, fact.eventEntered() > 0, fact.eventReturned() > 0,
                            fact.eventAllowsStandardPath(), fact.handlerRespawnEntered() > 0,
                            fact.handlerRespawnReturned() > 0, fact.respawnEntered() > 0,
                            fact.respawnReturned() > 0, fact.requestedReturnMap()),
                    sceneChanged, invocationWork.cancelRequested(), reason);
        }
    }

    private static ReviveResult.State state(GameContext.SceneRef scene) {
        var actor = scene.actor().character();
        var position = actor.getPosition();
        return new ReviveResult.State(actor.isAlive(), actor.getHp(), actor.getMp(), scene.map().getId(),
                position.x, position.y);
    }

    private static final class AlreadyAlive extends RuntimeException {
        AlreadyAlive() { super(null, null, false, false); }
    }
}
