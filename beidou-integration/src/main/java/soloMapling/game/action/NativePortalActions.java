package soloMapling.game.action;

import io.netty.buffer.Unpooled;
import org.gms.constants.id.MapId;
import org.gms.constants.string.CharsetConstants;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.ChangeMapHandler;
import org.gms.net.server.channel.handlers.PlayerMapTransitionHandler;
import org.gms.server.maps.Portal;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finite normal portal input, using native admission, transfer and map-transition completion. */
public final class NativePortalActions {
    private NativePortalActions() { }

    /** Runs only inside the provider's original short lane work. */
    public static PortalResult enterPortal(Server server, GameContext.SceneRef scene,
                                            ExecutionScope.WorkHandle work, PortalRequest request) {
        var ref = scene.actor();
        if (NativeBasicActions.cancelled(work)) {
            return new PortalResult(PortalResult.Outcome.CANCELLED, ref.character().getId(), ref.epoch().value(),
                    request.portalId(), null, null, null, null,
                    new PortalResult.Dispatch(false, false, false),
                    new PortalResult.Transition(false, false, false, false, false), null, true,
                    "portal_cancelled_before_dispatch");
        }
        NativeContextChecks.requireSceneCurrent(server, scene);
        var before = state(scene);
        var portal = scene.map().getPortal(request.portalId());
        String unsupported = null;
        if (scene.memberEim() != null || scene.mapEim() != null) unsupported = "portal_event_scene_unsupported";
        else if (portal != null && portal.getType() != Portal.MAP_PORTAL) unsupported = "portal_type_unsupported";
        else if (portal != null && portal.getScriptName() != null) unsupported = "portal_scripted";
        else if (portal != null && (portal.getTargetMapId() == MapId.NONE
                || portal.getTarget() == null || portal.getTarget().isEmpty())) unsupported = "portal_destination_unavailable";
        else if (portal != null && (portal.getName() == null || portal.getName().isEmpty()
                || scene.map().getPortal(portal.getName()) != portal)) unsupported = "portal_name_unavailable";
        if (portal == null || unsupported != null) {
            return new PortalResult(portal == null ? PortalResult.Outcome.NOT_ENTERED : PortalResult.Outcome.UNSUPPORTED,
                    ref.character().getId(), ref.epoch().value(), request.portalId(), null, null, before, before,
                    new PortalResult.Dispatch(false, false, false),
                    new PortalResult.Transition(false, false, false, false, false), false,
                    work.cancelRequested(), portal == null ? "portal_not_found" : unsupported);
        }

        var exitPosition = portal.getPosition();
        var exit = new PortalResult.Endpoint(scene.map().getId(), portal.getId(), portal.getName(),
                exitPosition.x, exitPosition.y);
        // Resolve the declared native entry so GenericPortal's missing-entry fallback is outside this contract.
        var target = ref.channel().getMapFactory().getMap(portal.getTargetMapId());
        var targetPortal = target == null ? null : target.getPortal(portal.getTarget());
        if (targetPortal == null || target.getId() != portal.getTargetMapId() || target.getEventInstance() != null) {
            return new PortalResult(PortalResult.Outcome.UNSUPPORTED, ref.character().getId(), ref.epoch().value(),
                    request.portalId(), exit, null, before, before,
                    new PortalResult.Dispatch(false, false, false),
                    new PortalResult.Transition(false, false, false, false, false), false,
                    work.cancelRequested(), "portal_target_entry_unavailable");
        }
        var entryPosition = targetPortal.getPosition();
        var entry = new PortalResult.Endpoint(target.getId(), targetPortal.getId(), targetPortal.getName(),
                entryPosition.x, entryPosition.y);
        var dispatched = new AtomicBoolean();
        var acknowledged = new AtomicBoolean();
        boolean handlerError = false;
        boolean handlerReturned = false;
        boolean acknowledgementError = false;
        boolean acknowledgementReturned = false;
        boolean transitionObserved = false;
        Boolean sceneChanged = null;
        PortalResult.State after = null;
        String reason = null;
        try {
            byte[] portalName = portal.getName().getBytes(CharsetConstants.getCharset(ref.client().getLanguage()));
            // BeiDou 1.12: regular click, target marker -1, actual portal name, reserved/wheel/chasing zero.
            handlerError = NativePacketDispatch.dispatch(ref.client(), new ChangeMapHandler(),
                    Unpooled.buffer(portalName.length + 10).writeByte(0).writeIntLE(-1)
                            .writeShortLE(portalName.length).writeBytes(portalName)
                            .writeByte(0).writeByte(0).writeByte(0),
                    () -> {
                        if (NativeBasicActions.cancelled(work)) throw new CancellationException();
                        NativeContextChecks.requireSceneCurrent(server, scene);
                        if (scene.map().getPortal(request.portalId()) != portal
                                || scene.map().getPortal(portal.getName()) != portal) {
                            throw new NativeActionService.Invalid("portal_changed");
                        }
                    }, () -> dispatched.set(true));
            handlerReturned = !handlerError;

            var current = NativeContextChecks.captureScene(server, ref);
            after = state(current);
            sceneChanged = current.map() != scene.map() || current.memberEim() != scene.memberEim()
                    || current.mapEim() != scene.mapEim();
            transitionObserved = sceneChanged || !before.changingMaps() && after.changingMaps();
            if (transitionObserved && after.changingMaps()) {
                // Finish this already-entered native transition even when its initiating call was cancelled.
                var enteredScene = current;
                acknowledgementError = NativePacketDispatch.dispatch(ref.client(), new PlayerMapTransitionHandler(),
                        Unpooled.buffer(0), () -> NativeContextChecks.requireSceneCurrent(server, enteredScene),
                        () -> acknowledged.set(true));
                acknowledgementReturned = !acknowledgementError;
                current = NativeContextChecks.captureScene(server, ref);
                after = state(current);
            }
            boolean reached = current.map() == target;
            PortalResult.Outcome outcome;
            if (transitionObserved && reached && !after.changingMaps() && !handlerError && !acknowledgementError) {
                outcome = PortalResult.Outcome.ENTERED;
            } else if (transitionObserved) {
                outcome = PortalResult.Outcome.UNKNOWN;
                reason = reached ? "portal_transition_incomplete" : "portal_actual_destination_changed";
            } else {
                outcome = PortalResult.Outcome.NOT_ENTERED;
                reason = "native_portal_not_entered";
            }
            return new PortalResult(outcome, ref.character().getId(), ref.epoch().value(), request.portalId(),
                    exit, entry, before, after, new PortalResult.Dispatch(dispatched.get(), handlerReturned, handlerError),
                    new PortalResult.Transition(transitionObserved, acknowledged.get(), acknowledgementReturned,
                            acknowledgementError, transitionObserved && !after.changingMaps()), sceneChanged,
                    work.cancelRequested(), reason);
        } catch (CancellationException cancelled) {
            reason = "portal_cancelled_before_dispatch";
        } catch (NativeActionService.Invalid rejected) {
            reason = rejected.reason();
        } catch (GameContext.Invalid changed) {
            reason = changed.reason().name().toLowerCase(Locale.ROOT);
        }
        return new PortalResult(!dispatched.get()
                ? NativeBasicActions.cancelled(work) ? PortalResult.Outcome.CANCELLED : PortalResult.Outcome.NOT_ENTERED
                : PortalResult.Outcome.UNKNOWN,
                ref.character().getId(), ref.epoch().value(), request.portalId(), exit, entry, before, after,
                new PortalResult.Dispatch(dispatched.get(), handlerReturned, handlerError),
                new PortalResult.Transition(transitionObserved, acknowledged.get(), acknowledgementReturned,
                        acknowledgementError, transitionObserved && after != null && !after.changingMaps()),
                sceneChanged, work.cancelRequested(), reason);
    }

    private static PortalResult.State state(GameContext.SceneRef scene) {
        var actor = scene.actor().character();
        var position = actor.getPosition();
        return new PortalResult.State(scene.actor().world().getId(), scene.actor().channel().getId(), scene.map().getId(),
                System.identityHashCode(scene.map()), position.x, position.y, actor.isAlive(), actor.isChangingMaps());
    }
}
