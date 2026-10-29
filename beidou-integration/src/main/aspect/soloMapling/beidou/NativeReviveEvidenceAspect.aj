package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import soloMapling.game.action.NativeReviveEvidence;

/** Observe native revive decisions and steps without changing their routing or effects. */
public aspect NativeReviveEvidenceAspect {
    boolean around(EventInstanceManager event, Character actor):
            execution(boolean EventInstanceManager.revivePlayer(Character)) && this(event) && args(actor) {
        NativeReviveEvidence.onEventEntered(event, actor);
        try {
            boolean result = proceed(event, actor);
            NativeReviveEvidence.onEventReturned(event, actor, result);
            return result;
        } finally {
            NativeReviveEvidence.onEventExited(event, actor);
        }
    }

    before(Character actor, int returnMap):
            execution(void Character.respawn(int)) && this(actor) && args(returnMap) {
        NativeReviveEvidence.onHandlerRespawnEntered(actor, returnMap);
    }

    after(Character actor) returning:
            execution(void Character.respawn(int)) && this(actor) {
        NativeReviveEvidence.onHandlerRespawnReturned(actor);
    }

    before(Character actor, EventInstanceManager event, int returnMap):
            execution(void Character.respawn(EventInstanceManager, int)) && this(actor) && args(event, returnMap) {
        NativeReviveEvidence.onRespawnEntered(actor, event, returnMap);
    }

    after(Character actor, EventInstanceManager event) returning:
            execution(void Character.respawn(EventInstanceManager, int)) && this(actor) && args(event, *) {
        NativeReviveEvidence.onRespawnReturned(actor, event);
    }
}
