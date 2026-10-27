package soloMapling.game.action;

import org.gms.client.Character;
import org.gms.client.Client;
import org.junit.jupiter.api.Test;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Finite decoded-window matcher tests; these are evidence contracts, not live broadcasts. */
class ChatEvidenceSourceTest {
    @Test void exactNewPacketProvesAPositiveFactDespiteALaterMalformedWindowItem() {
        Feed feed = new Feed();
        feed.events.add(packet(1L, 41, "原文", 0));
        feed.events.add(Map.of("sequence", "unreadable"));
        try (var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"))) {
            feed.latest = 2;
            ChatEvidence result = probe.observe();
            assertTrue(result.matched());
            assertEquals(1L, result.matchedSequence());
            assertEquals(ChatEvidence.Completeness.PARTIAL, result.completeness());
            assertFalse(result.toString().contains("原文"));
        }
    }

    @Test void anErrorReadingALaterItemCannotEraseAnAlreadyMatchedNativePacket() {
        Feed feed = new Feed();
        feed.events.add(packet(1L, 41, "原文", 0));
        feed.events.add(new java.util.HashMap<>() {
            @Override public Object get(Object key) { throw new AssertionError("private event error"); }
        });
        try (var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"))) {
            feed.latest = 2;
            assertTrue(probe.observe().matched());
        }
    }

    @Test void historyWrongSenderWrongTextWrongShowAndTruncationCannotProveThisCall() {
        Feed feed = new Feed();
        feed.latest = 1;
        feed.events.add(packet(1L, 41, "原文", 0));
        try (var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"))) {
            feed.events.add(packet(2L, 42, "原文", 0));
            feed.events.add(packet(3L, 41, "其他原文", 0));
            feed.events.add(packet(4L, 41, "原文", 1));
            Map<String, Object> truncated = new LinkedHashMap<>(packet(5L, 41, "原文", 0));
            truncated.put("text_truncated", true); feed.events.add(truncated);
            feed.latest = 5;
            ChatEvidence result = probe.observe();
            assertFalse(result.matched());
            assertEquals(1L, result.cursorBefore());
            assertEquals(ChatEvidence.Completeness.COMPLETE, result.completeness());
        }
    }

    @Test void lostWindowAndMalformedPotentialChatAreSeparateFromCompleteNegativeEvidence() {
        Feed feed = new Feed();
        try (var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"))) {
            feed.latest = 1; feed.expired = true;
            feed.events.add(Map.of("sequence", 1L, "event_type", "unknown", "decode_status", "malformed", "opcode", 0xA2));
            var result = probe.observe();
            assertFalse(result.matched());
            assertTrue(result.uncertainPacket());
            assertEquals(ChatEvidence.Completeness.PARTIAL, result.completeness());
        }
    }

    @Test void unreadableWindowHasNoInventedNegativeProofAndKeepsAnUnavailableCursorNull() {
        Feed feed = new Feed();
        try (var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"))) {
            feed.failWindow = true; feed.failLatest = true;
            var result = probe.observe();
            assertEquals(ChatEvidence.Completeness.UNAVAILABLE, result.completeness());
            assertNull(result.cursorAfter());
            assertFalse(result.matched());
        }
    }

    @Test void observerCheckpointLossIsUnavailableEvidenceWhileSelfCheckpointLossRejectsBeforeEntry() {
        Feed observer = new Feed(); observer.failCurrent = true;
        try (var probe = source(ChatEvidence.Source.OBSERVER_NATIVE_CHAT_PACKET, observer).begin(actor(), new NativeChatAction("原文"))) {
            assertEquals(ChatEvidence.Source.OBSERVER_NATIVE_CHAT_PACKET, probe.observe().source());
            assertEquals(ChatEvidence.Completeness.UNAVAILABLE, probe.observe().completeness());
        }
        assertThrows(IllegalStateException.class,
                () -> source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, observer).begin(actor(), new NativeChatAction("原文")));
    }

    @Test void sourceAndCursorInvariantsCannotConstructSuccessWithoutARealSource() {
        assertThrows(IllegalArgumentException.class, () -> new ChatEvidence(ChatEvidence.Source.NONE,
                "none", "epoch", 0L, 1L, 1L, ChatEvidence.Completeness.COMPLETE, false));
        assertThrows(IllegalArgumentException.class, () -> new ChatEvidence(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET,
                "self", "epoch", null, null, null, ChatEvidence.Completeness.COMPLETE, false));
        assertThrows(IllegalArgumentException.class, () -> new ChatEvidence(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET,
                "self", "epoch", 2L, 1L, null, ChatEvidence.Completeness.COMPLETE, false));
        assertThrows(IllegalArgumentException.class, () -> new NativeActionResult(NativeActionResult.Outcome.SUCCEEDED,
                NativeActionResult.Dispatch.STARTED, 41, false, false, null, ChatEvidence.none("epoch")));
    }

    @Test void aClosedProbeCannotReadItsOldPacketSourceAgain() {
        Feed feed = new Feed();
        var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"));
        probe.close();
        assertThrows(IllegalStateException.class, probe::observe);
        assertEquals(0, feed.reads);
    }

    @Test void aClosedReceiverDoesNotTurnAReadableHistoricalWindowIntoNegativeProof() {
        Feed feed = new Feed();
        try (var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"))) {
            feed.receiving = false;
            var evidence = probe.observe();
            assertFalse(evidence.matched());
            assertEquals(ChatEvidence.Completeness.PARTIAL, evidence.completeness());
            assertEquals(false, feed.lastWindow.get("cursor_expired"));
            assertEquals(false, feed.lastWindow.get("truncated"));
        }
    }

    @Test void positivePacketsRemainFactsAfterReceptionWasClosedOrItsCoverageReadFailed() {
        for (boolean failedRead : new boolean[]{false, true}) {
            Feed feed = new Feed();
            try (var probe = source(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, feed).begin(actor(), new NativeChatAction("原文"))) {
                feed.events.add(packet(1L, 41, "原文", 0)); feed.latest = 1;
                feed.receiving = false; feed.failReception = failedRead;
                var evidence = probe.observe();
                assertTrue(evidence.matched());
                assertEquals(ChatEvidence.Completeness.PARTIAL, evidence.completeness());
                assertEquals(false, feed.lastWindow.get("cursor_expired"));
                assertEquals(false, feed.lastWindow.get("truncated"));
            }
        }
    }

    @Test void aFeedWithoutAnExplicitReceptionGuaranteeCannotClaimCompleteNegativeEvidence() {
        Feed feed = new Feed();
        ChatEvidenceSource.PacketFeed unproven = new ChatEvidenceSource.PacketFeed() {
            public void requireCurrent() { feed.requireCurrent(); }
            public long latestCursor() { return feed.latestCursor(); }
            public Map<String, Object> window(long since, int limit) { return feed.window(since, limit); }
        };
        try (var probe = ChatEvidenceSource.packets(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET, "unproven", unproven)
                .begin(actor(), new NativeChatAction("原文"))) {
            assertEquals(ChatEvidence.Completeness.PARTIAL, probe.observe().completeness());
        }
    }

    private static ChatEvidenceSource source(ChatEvidence.Source source, Feed feed) {
        return ChatEvidenceSource.packets(source, "test-source", feed);
    }

    private static GameContext.CharacterRef actor() {
        Character character = mock(Character.class); when(character.getId()).thenReturn(41);
        return new GameContext.CharacterRef(WorldEpoch.snapshot().token(), character, mock(Client.class), null, null);
    }

    private static Map<String, Object> packet(long seq, int sender, String text, int show) {
        return Map.of("sequence", seq, "event_type", "chat_text", "decode_status", "decoded",
                "opcode", 0xA2, "sender_character_id", sender, "text", text, "show", show, "text_truncated", false);
    }

    private static final class Feed implements ChatEvidenceSource.PacketFeed {
        final List<Map<String, Object>> events = new ArrayList<>();
        long latest; boolean expired; boolean failWindow; boolean failLatest; boolean failCurrent; int reads;
        boolean receiving = true; boolean failReception; Map<String, Object> lastWindow;
        public void requireCurrent() { if (failCurrent) throw new IllegalStateException("private source"); }
        public long latestCursor() { if (failLatest) throw new IllegalStateException("private cursor"); return latest; }
        public boolean completeReception() { if (failReception) throw new IllegalStateException("private coverage"); return receiving; }
        public Map<String, Object> window(long since, int limit) {
            reads++; if (failWindow) throw new IllegalStateException("private window");
            return lastWindow = Map.of("latest_cursor", latest, "items", List.copyOf(events), "cursor_expired", expired, "truncated", false);
        }
    }
}
