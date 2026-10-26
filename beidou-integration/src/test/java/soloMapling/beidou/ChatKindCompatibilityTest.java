package soloMapling.beidou;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatKindCompatibilityTest {
    @Test
    void normalAndHiddenGmGeneralChatAreObserved() {
        assertTrue(HostCompatibility.isGeneralChatKind("General"));
        assertTrue(HostCompatibility.isGeneralChatKind("GM General"));
    }

    @Test
    void otherChatKindsAndMissingValuesAreExcluded() {
        for (String kind : new String[] {"Party", "Trade", "Whisper", "Guild", "GM", "general", ""}) {
            assertFalse(HostCompatibility.isGeneralChatKind(kind), kind);
        }
        assertFalse(HostCompatibility.isGeneralChatKind(null));
    }
}
