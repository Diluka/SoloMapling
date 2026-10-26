package soloMapling.beidou;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.channel.Channel;
import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/** Object identity, rather than a character-id range, determines bot ownership. */
public final class BotRegistry {
    private static final ConcurrentHashMap<Integer, Character> BOTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(Integer.MAX_VALUE);

    private BotRegistry() { }

    public static int allocateId() {
        return allocateId(id -> BOTS.containsKey(id) || isOnline(id) || isPersisted(id));
    }

    static int allocateId(IntPredicate occupied) {
        int candidate;
        do {
            candidate = NEXT_ID.getAndDecrement();
            if (candidate <= 0) {
                throw new IllegalStateException("SoloMapling character id space exhausted");
            }
        } while (occupied.test(candidate));
        return candidate;
    }

    public static void register(Character character) {
        if (character == null || !(character.getClient() instanceof BotClient)) {
            throw new IllegalArgumentException("Only characters with a headless BotClient can be registered");
        }
        Character previous = BOTS.putIfAbsent(character.getId(), character);
        if (previous != null && previous != character) {
            throw new IllegalStateException("Duplicate SoloMapling character id: " + character.getId());
        }
    }

    public static void unregister(Character character) {
        if (character != null) {
            BOTS.remove(character.getId(), character);
        }
    }

    public static boolean isBot(Character character) {
        return character != null && (character.getClient() instanceof BotClient
                || BOTS.get(character.getId()) == character);
    }

    public static boolean isRegistered(Character character) {
        return character != null && BOTS.get(character.getId()) == character;
    }

    /** A stale headless object must not act on a replacement world's channel. */
    public static boolean isCurrent(Character character) {
        if (!isRegistered(character)) return false;
        Channel channel = character.getClient().getChannelServer();
        if (channel == null) return false;
        PlayerStorage storage = channel.getPlayerStorage();
        return storage != null && storage.getCharacterById(character.getId()) == character;
    }

    public static List<Character> snapshot() {
        return List.copyOf(BOTS.values());
    }

    private static boolean isOnline(int id) {
        return Server.getInstance().getWorlds().stream()
                .anyMatch(world -> world.getPlayerStorage().getCharacterById(id) != null);
    }

    private static boolean isPersisted(int id) {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM characters WHERE id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to check SoloMapling character id collision", failure);
        }
    }
}
