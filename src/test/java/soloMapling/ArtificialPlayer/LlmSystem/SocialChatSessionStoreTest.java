package soloMapling.ArtificialPlayer.LlmSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialChatSessionStoreTest {

    @AfterEach
    void clearSessions() {
        SocialChatSessionStore.clearAll();
    }

    @Test
    void retainsBoundedHistoryAndKeepsConversationPairsSeparate() {
        for (int turn = 0; turn < 20; turn++) {
            SocialChatSessionStore.addUser(1, 2, "turn " + turn);
        }
        SocialChatSessionStore.addAssistant(1, 3, "other player");

        List<LlmMessage> history = SocialChatSessionStore.toMessages(1, 2, 100);
        assertEquals(16, history.size());
        assertEquals("turn 4", history.getFirst().content());
        assertEquals("turn 19", history.getLast().content());
        assertEquals(List.of(LlmMessage.assistant("other player")),
                SocialChatSessionStore.toMessages(1, 3, 10));
    }

    @Test
    void clearEndsHistoryOnlyForSelectedConversation() {
        SocialChatSessionStore.addUser(1, 2, "hello");
        SocialChatSessionStore.addUser(1, 3, "still active");

        SocialChatSessionStore.clear(1, 2);

        assertTrue(SocialChatSessionStore.toMessages(1, 2, 10).isEmpty());
        assertEquals(1, SocialChatSessionStore.toMessages(1, 3, 10).size());
    }
}
