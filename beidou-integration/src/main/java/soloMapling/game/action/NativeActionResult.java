package soloMapling.game.action;

import java.util.Objects;

/** Receipt, native entry and observed outcome are separate facts. */
public record NativeActionResult(Outcome outcome, Dispatch dispatch, int senderCharacterId,
                                 boolean cancelRequested, boolean handlerError, String fixedReason,
                                 ChatEvidence evidence) implements NativeActionFact {
    public enum Outcome { SUCCEEDED, FAILED, CANCELLED, INDETERMINATE }
    public enum Dispatch { NOT_STARTED, STARTED }

    @Override
    public String epoch() { return evidence.epoch(); }

    public NativeActionResult {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(dispatch);
        Objects.requireNonNull(evidence);
        if (fixedReason != null && !fixedReason.matches("[a-z_]{1,96}")) {
            throw new IllegalArgumentException("invalid_action_reason");
        }
        if (outcome == Outcome.SUCCEEDED && (dispatch != Dispatch.STARTED || !evidence.matched())) {
            throw new IllegalArgumentException("action_success_without_evidence");
        }
        if (evidence.matched() && outcome != Outcome.SUCCEEDED
                || outcome == Outcome.CANCELLED && dispatch != Dispatch.NOT_STARTED) {
            throw new IllegalArgumentException("action_result_contradicts_native_fact");
        }
    }
}
