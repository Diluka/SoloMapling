package soloMapling.game.action;

/** Safe native outcome summary for the host; detailed evidence stays with the action. */
public interface NativeActionFact {
    int senderCharacterId();
    String epoch();
    NativeActionResult.Outcome outcome();
    String fixedReason();
}
