package soloMapling.ArtificialPlayer.LlmSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Bounded in-memory history for a bot/player conversation, cleared when it ends. */
public final class SocialChatSessionStore {

    private SocialChatSessionStore() {
    }

    private static final ConcurrentMap<String, Deque<LlmMessage>> SESSIONS = new ConcurrentHashMap<>();

    public static void addUser(int botId, int playerId, String content) {
        addTurn(botId, playerId, LlmMessage.Role.USER, content);
    }

    public static void addAssistant(int botId, int playerId, String content) {
        addTurn(botId, playerId, LlmMessage.Role.ASSISTANT, content);
    }

    public static List<LlmMessage> toMessages(int botId, int playerId, int maxTurns) {
        Deque<LlmMessage> turns = SESSIONS.get(key(botId, playerId));
        if (turns == null || maxTurns <= 0) {
            return List.of();
        }
        synchronized (turns) {
            List<LlmMessage> snapshot = new ArrayList<>(turns);
            return List.copyOf(snapshot.subList(Math.max(0, snapshot.size() - maxTurns), snapshot.size()));
        }
    }

    public static void clear(int botId, int playerId) {
        SESSIONS.remove(key(botId, playerId));
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    private static void addTurn(int botId, int playerId, LlmMessage.Role role, String content) {
        if (content == null || content.isBlank() || SocialLlmConfig.historyTurns() <= 0) {
            return;
        }
        Deque<LlmMessage> turns = SESSIONS.computeIfAbsent(key(botId, playerId), ignored -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(new LlmMessage(role, content.trim()));
            int cap = SocialLlmConfig.historyTurns() * 2;
            while (turns.size() > cap) {
                turns.removeFirst();
            }
        }
    }

    private static String key(int botId, int playerId) {
        return botId + ":" + playerId;
    }
}
