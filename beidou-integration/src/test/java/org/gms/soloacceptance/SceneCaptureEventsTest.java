package org.gms.soloacceptance;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A recorder observes native changes without an AcceptanceClient or a simulated scene host. */
class SceneCaptureEventsTest {
    @BeforeAll static void context() { CharacterTestContext.install(); }

    @Test void nativeControllerHpAndStanceEventsStayOnTheirExactMapAndSurviveTheFinalDrain() {
        var map = mock(MapleMap.class);
        var otherMap = mock(MapleMap.class);
        when(map.getId()).thenReturn(104040000);
        when(otherMap.getId()).thenReturn(104040000);
        var player = mock(Character.class);
        when(player.getId()).thenReturn(2);
        when(player.getClient()).thenReturn(mock(Client.class));
        when(player.getMap()).thenReturn(map);
        when(player.isLoggedInWorld()).thenReturn(true);
        var stats = new MonsterStats();
        stats.hp = 100;
        var monster = spy(new Monster(100101, stats));
        monster.setObjectId(501);
        monster.setMap(map);
        doNothing().when(monster).aggroUpdatePuppetVisibility();

        try (var packets = mockStatic(PacketCreator.class);
             var capture = SceneCaptureEvents.open(map);
             var other = SceneCaptureEvents.open(otherMap)) {
            monster.aggroSwitchController(player, false);
            assertSame(player, monster.getController());
            assertEquals(25, monster.applyAndGetHpDamage(25, false));
            monster.setStance(19);
            monster.aggroSwitchController(null, false);
            assertNull(monster.getController());
            assertEquals(75, monster.getHp());

            capture.close();
            var drained = capture.drain();
            var events = items(drained);
            assertTrue((boolean) drained.get("subscription_closed"));
            assertFalse((boolean) drained.get("gap"));
            assertEquals(List.of("controller_changed", "monster_hp_accounted", "stance_changed", "controller_changed"),
                    events.stream().map(event -> event.get("kind")).toList());
            var last = values(events.getLast());
            assertEquals(2, values((Map<?, ?>) last.get("controller_before")).get("character_id"));
            assertEquals(false, values((Map<?, ?>) last.get("controller_after")).get("available"));
            assertTrue(items(other.drain()).isEmpty());

            monster.applyAndGetHpDamage(1, false);
            assertEquals(74, monster.getHp());
            assertTrue(items(capture.drain()).isEmpty());
            assertFalse(SceneCaptureEvents.interested(map));
        }
    }

    @Test void nativeChangesThatOutrunThePublishedWindowReportARealGapWithoutStoppingGameplay() {
        var map = mock(MapleMap.class);
        var stats = new MonsterStats();
        stats.hp = 100;
        var monster = new Monster(100101, stats);
        monster.setMap(map);
        try (var capture = SceneCaptureEvents.open(map)) {
            for (int index = 0; index < SceneCaptureEvents.CAPACITY + 3; index++) monster.setStance(index % 2);
            var drained = capture.drain();
            var events = items(drained);
            assertEquals(SceneCaptureEvents.CAPACITY, events.size());
            assertTrue((boolean) drained.get("gap"));
            assertEquals(3L, drained.get("dropped"));
            assertEquals((long) SceneCaptureEvents.CAPACITY + 3, drained.get("total_since_last"));
            assertEquals(4L, events.getFirst().get("source_seq"));
            assertEquals(0, monster.getStance());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> frame) {
        return (List<Map<String, Object>>) frame.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Map<?, ?> event) {
        return event.containsKey("values") ? (Map<String, Object>) event.get("values") : (Map<String, Object>) event;
    }
}
