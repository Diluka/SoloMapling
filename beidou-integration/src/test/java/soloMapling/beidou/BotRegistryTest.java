package soloMapling.beidou;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BotRegistryTest {
    @Test
    void allocationIsPositiveUniqueAndSkipsOccupiedCandidatesUnderConcurrency() {
        Set<Integer> allocated = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 2_000).parallel().forEach(ignored -> {
            int id = BotRegistry.allocateId(candidate -> candidate % 2 == 0);
            assertTrue(id > 0);
            assertEquals(1, id % 2);
            assertTrue(allocated.add(id));
        });
        assertEquals(2_000, allocated.size());
    }

    @Test
    void absentCharacterIsNeverClassifiedAsABot() {
        assertFalse(BotRegistry.isBot(null));
    }
}
