package soloMapling.game.action;

import java.util.Objects;

/** Immutable facts only: this value never retains a native object, packet, probe or chat text. */
public record ChatEvidence(Source source, String sourceId, String epoch, Long cursorBefore,
                           Long cursorAfter, Long matchedSequence, Completeness completeness,
                           boolean uncertainPacket) {
    public enum Source { SELF_NATIVE_CHAT_PACKET, OBSERVER_NATIVE_CHAT_PACKET, NONE }
    public enum Completeness { COMPLETE, PARTIAL, UNAVAILABLE, NOT_APPLICABLE }

    public ChatEvidence {
        Objects.requireNonNull(source);
        Objects.requireNonNull(completeness);
        if (sourceId == null || !sourceId.matches("[A-Za-z0-9._:-]{1,128}")
                || epoch == null || !epoch.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid_chat_evidence_stamp");
        }
        if (cursorBefore != null && cursorBefore < 0 || cursorAfter != null && cursorAfter < 0
                || matchedSequence != null && (cursorBefore == null || cursorAfter == null
                || matchedSequence <= cursorBefore || matchedSequence > cursorAfter)) {
            throw new IllegalArgumentException("invalid_chat_evidence_cursor");
        }
        if (source == Source.NONE && (cursorBefore != null || cursorAfter != null || matchedSequence != null
                || completeness != Completeness.NOT_APPLICABLE || uncertainPacket)
                || source != Source.NONE && completeness == Completeness.NOT_APPLICABLE
                || completeness == Completeness.COMPLETE && (cursorBefore == null || cursorAfter == null
                || cursorAfter < cursorBefore)) {
            throw new IllegalArgumentException("invalid_chat_evidence_source");
        }
    }

    public boolean matched() { return matchedSequence != null; }

    public static ChatEvidence none(String epoch) {
        return new ChatEvidence(Source.NONE, "none", epoch, null, null, null,
                Completeness.NOT_APPLICABLE, false);
    }
}
