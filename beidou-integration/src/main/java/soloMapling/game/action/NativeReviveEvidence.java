package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;

/** Thread-confined evidence from the exact native revive request, with no event ownership. */
public final class NativeReviveEvidence {
    private static final ThreadLocal<Probe> CURRENT = new ThreadLocal<>();
    private NativeReviveEvidence() { }

    static Probe begin(Character actor, EventInstanceManager initialEvent) {
        var probe = new Probe(actor, initialEvent, CURRENT.get());
        CURRENT.set(probe);
        return probe;
    }

    public static void onEventEntered(EventInstanceManager event, Character actor) {
        var probe = CURRENT.get();
        if (probe != null && probe.matchesEvent(event, actor)) {
            probe.eventEntered++;
            probe.eventDepth++;
        }
    }

    public static void onEventReturned(EventInstanceManager event, Character actor, boolean allowsStandardPath) {
        var probe = CURRENT.get();
        if (probe != null && probe.matchesEvent(event, actor)) {
            probe.eventReturned++;
            probe.eventAllowsStandardPath = allowsStandardPath;
        }
    }

    public static void onEventExited(EventInstanceManager event, Character actor) {
        var probe = CURRENT.get();
        if (probe != null && probe.matchesEvent(event, actor)) probe.eventDepth--;
    }

    /** The one-argument entry outside playerRevive is the handler's standard route. */
    public static void onHandlerRespawnEntered(Character actor, int returnMap) {
        var probe = CURRENT.get();
        if (probe != null && probe.matchesActor(actor) && probe.eventDepth == 0) {
            probe.handlerRespawnEntered++;
            probe.requestedReturnMap = returnMap;
        }
    }

    public static void onHandlerRespawnReturned(Character actor) {
        var probe = CURRENT.get();
        if (probe != null && probe.matchesActor(actor) && probe.eventDepth == 0) probe.handlerRespawnReturned++;
    }

    /** Count the actual two-argument step once, including an event's own respawn. */
    public static void onRespawnEntered(Character actor, EventInstanceManager event, int returnMap) {
        var probe = CURRENT.get();
        if (probe != null && probe.matchesRespawn(actor, event)) {
            probe.respawnEntered++;
            probe.requestedReturnMap = returnMap;
        }
    }

    public static void onRespawnReturned(Character actor, EventInstanceManager event) {
        var probe = CURRENT.get();
        if (probe != null && probe.matchesRespawn(actor, event)) probe.respawnReturned++;
    }

    /** The decision is known only when eventReturned is positive. */
    record Fact(int eventEntered, int eventReturned, boolean eventAllowsStandardPath,
                int respawnEntered, int respawnReturned, int handlerRespawnEntered,
                int handlerRespawnReturned, Integer requestedReturnMap) { }

    static final class Probe implements AutoCloseable {
        private Character actor;
        private EventInstanceManager initialEvent;
        private Probe previous;
        private int eventEntered, eventReturned, eventDepth;
        private int respawnEntered, respawnReturned, handlerRespawnEntered, handlerRespawnReturned;
        private Integer requestedReturnMap;
        private boolean eventAllowsStandardPath, dispatched, closed;

        private Probe(Character actor, EventInstanceManager initialEvent, Probe previous) {
            this.actor = actor;
            this.initialEvent = initialEvent;
            this.previous = previous;
        }

        private boolean matchesActor(Character exactActor) { return !closed && actor == exactActor; }
        private boolean matchesEvent(EventInstanceManager exactEvent, Character exactActor) {
            return matchesActor(exactActor) && initialEvent == exactEvent;
        }
        private boolean matchesRespawn(Character exactActor, EventInstanceManager event) {
            return matchesActor(exactActor) && (event == null || event == initialEvent);
        }

        void onDispatch() { dispatched = true; }
        boolean dispatchStarted() { return dispatched; }
        Fact fact() {
            return new Fact(eventEntered, eventReturned, eventAllowsStandardPath, respawnEntered,
                    respawnReturned, handlerRespawnEntered, handlerRespawnReturned, requestedReturnMap);
        }

        @Override public void close() {
            if (closed) return;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            actor = null;
            initialEvent = null;
            previous = null;
            closed = true;
        }
    }
}
