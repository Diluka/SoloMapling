package soloMapling.game.action;

import java.util.Objects;

/** Only immutable slot facts survive native execution; no Item or Client is retained. */
public record InventoryMoveResult(NativeActionResult.Outcome outcome, NativeActionResult.Dispatch dispatch,
                                  int senderCharacterId, boolean cancelRequested, boolean handlerError,
                                  String fixedReason, Evidence evidence) implements NativeActionFact {
    @Override public String epoch() { return evidence.epoch(); }
    public enum Source { NONE, NATIVE_INVENTORY_SLOTS }
    public record Slot(int slot, boolean present, int itemId, int quantity) { }
    public record Slots(Slot source, Slot target) {
        public Slots { Objects.requireNonNull(source); Objects.requireNonNull(target); }
    }
    public record Evidence(Source source, String epoch, String inventoryType, Slots before, Slots after,
                           boolean identityVerified, boolean changed) {
        public Evidence {
            Objects.requireNonNull(source); Objects.requireNonNull(epoch);
            if (source == Source.NATIVE_INVENTORY_SLOTS && (inventoryType == null || before == null || after == null)) {
                throw new IllegalArgumentException("incomplete_inventory_evidence");
            }
            if (changed && (source != Source.NATIVE_INVENTORY_SLOTS || !identityVerified)) {
                throw new IllegalArgumentException("inventory_change_without_identity");
            }
        }
        public static Evidence none(String epoch) { return new Evidence(Source.NONE, epoch, null, null, null, false, false); }
    }

    public InventoryMoveResult {
        Objects.requireNonNull(outcome); Objects.requireNonNull(dispatch); Objects.requireNonNull(evidence);
        if (fixedReason != null && !fixedReason.matches("[a-z_]{1,96}")) throw new IllegalArgumentException("invalid_action_reason");
        if (outcome == NativeActionResult.Outcome.SUCCEEDED && (dispatch != NativeActionResult.Dispatch.STARTED
                || !evidence.identityVerified() || !evidence.changed())) throw new IllegalArgumentException("action_success_without_evidence");
        if (evidence.changed() && outcome != NativeActionResult.Outcome.SUCCEEDED
                || outcome == NativeActionResult.Outcome.CANCELLED && dispatch != NativeActionResult.Dispatch.NOT_STARTED) {
            throw new IllegalArgumentException("action_result_contradicts_native_fact");
        }
    }
}
