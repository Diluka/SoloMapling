package soloMapling.game.tactics;

/** One process-local combat call; detaching it never stops independent tactical observation. */
public final class EngagementBinding {
    final EngagementContext context;
    volatile AttackProposal proposal;
    volatile TacticalDirective directive;
    volatile boolean attached = true;

    EngagementBinding(EngagementContext context) {
        this.context = context;
        directive = new TacticalDirective(TacticalDirective.Action.WAIT, "awaiting_attack_proposal", null, System.nanoTime(), null);
    }
    public boolean attached() { return attached; }
}
