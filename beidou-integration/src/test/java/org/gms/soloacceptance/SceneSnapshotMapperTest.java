package org.gms.soloacceptance;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.life.Monster;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneSnapshotMapperTest {
    @BeforeEach
    void nativeStaticContext() {
        CharacterTestContext.install();
    }

    @Test
    void boundsEachCategoryAndOrdersByRuntimeObjectIdentity() {
        MapleMap map = mock(MapleMap.class);
        Character first = mock(Character.class);
        Character second = mock(Character.class);
        when(first.getObjectId()).thenReturn(21);
        when(first.getId()).thenReturn(501);
        when(first.getName()).thenReturn("ActorOne");
        when(first.getType()).thenReturn(MapObjectType.PLAYER);
        when(second.getObjectId()).thenReturn(31);
        when(second.getId()).thenReturn(502);
        when(second.getType()).thenReturn(MapObjectType.PLAYER);
        NPC npc = mock(NPC.class);
        when(npc.getObjectId()).thenReturn(12);
        when(npc.getId()).thenReturn(1012000);
        when(npc.getType()).thenReturn(MapObjectType.NPC);
        when(map.getMapObjects()).thenReturn(List.of(second, npc, first));
        when(map.getPortals()).thenReturn(List.of());

        Map<String, Object> snapshot = SceneSnapshotMapper.snapshot(map, 1);

        assertEquals(2, section(snapshot, "players").get("total_count"));
        assertEquals(true, section(snapshot, "players").get("truncated"));
        assertEquals(21, entries(snapshot, "players").getFirst().get("oid"));
        assertEquals(501, entries(snapshot, "players").getFirst().get("id"));
        assertEquals("ActorOne", entries(snapshot, "players").getFirst().get("name"));
        assertEquals(false, entries(snapshot, "players").getFirst().get("is_bot"));
        assertEquals(1, entries(snapshot, "npcs").size());
        assertEquals(1012000, entries(snapshot, "npcs").getFirst().get("template_id"));
        assertEquals(false, section(snapshot, "npcs").get("truncated"));
        verify(map, times(1)).getMapObjects();
        verify(map, times(1)).getPortals();
        verifyNoMoreInteractions(map);
    }

    @Test
    void moneyDropsAreNotMistakenForItemTemplatesAndSnapshotsCopyCoordinates() {
        MapleMap map = mock(MapleMap.class);
        MapItem money = mock(MapItem.class);
        Point position = new Point(35, -20);
        when(money.getObjectId()).thenReturn(4);
        when(money.getType()).thenReturn(MapObjectType.ITEM);
        when(money.getPosition()).thenReturn(position);
        when(money.getMeso()).thenReturn(200);
        when(money.getOwnerId()).thenReturn(12);
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(drop.getObjectId()).thenReturn(5);
        when(drop.getType()).thenReturn(MapObjectType.ITEM);
        when(drop.getItem()).thenReturn(item);
        when(item.getItemId()).thenReturn(4000000);
        when(item.getQuantity()).thenReturn((short) 3);
        when(map.getMapObjects()).thenReturn(List.of(money, drop));
        when(map.getPortals()).thenReturn(List.of());

        Map<String, Object> snapshot = SceneSnapshotMapper.snapshot(map, 10);
        position.translate(500, 500);

        Map<String, Object> capturedMoney = entries(snapshot, "items").getFirst();
        assertNull(capturedMoney.get("item_id"));
        assertEquals(200, capturedMoney.get("meso"));
        assertEquals(0, capturedMoney.get("quantity"));
        assertEquals(Map.of("x", 35, "y", -20), capturedMoney.get("position"));
        assertEquals(4000000, entries(snapshot, "items").get(1).get("item_id"));
        assertEquals(3, entries(snapshot, "items").get(1).get("quantity"));
        verify(money, never()).getItemId();
        verify(drop, never()).getItemId();
    }

    @Test
    void exposesRealMonsterReactorAndPortalFieldsAndReportsUnsupportedTypes() {
        MapleMap map = mock(MapleMap.class);
        Monster monster = mock(Monster.class);
        when(monster.getObjectId()).thenReturn(1);
        when(monster.getType()).thenReturn(MapObjectType.MONSTER);
        when(monster.getId()).thenReturn(100100);
        when(monster.getHp()).thenReturn(5);
        when(monster.getMaxHp()).thenReturn(10);
        Reactor reactor = mock(Reactor.class);
        when(reactor.getObjectId()).thenReturn(2);
        when(reactor.getType()).thenReturn(MapObjectType.REACTOR);
        when(reactor.getId()).thenReturn(9108000);
        when(reactor.getState()).thenReturn((byte) 2);
        when(reactor.isAlive()).thenReturn(true);
        MapObject unsupported = mock(MapObject.class);
        when(unsupported.getObjectId()).thenReturn(3);
        when(unsupported.getType()).thenReturn(MapObjectType.DOOR);
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(0);
        when(portal.getType()).thenReturn(2);
        when(portal.getName()).thenReturn("west00");
        when(portal.getTargetMapId()).thenReturn(101000000);
        when(map.getMapObjects()).thenReturn(List.of(unsupported, reactor, monster));
        when(map.getPortals()).thenReturn(List.of(portal));

        Map<String, Object> snapshot = SceneSnapshotMapper.snapshot(map, 500);

        assertEquals(5, entries(snapshot, "monsters").getFirst().get("hp"));
        assertEquals(10, entries(snapshot, "monsters").getFirst().get("max_hp"));
        assertEquals((byte) 2, entries(snapshot, "reactors").getFirst().get("state"));
        assertEquals(true, entries(snapshot, "reactors").getFirst().get("alive"));
        assertEquals("west00", entries(snapshot, "portals").getFirst().get("name"));
        assertEquals(101000000, entries(snapshot, "portals").getFirst().get("target_map_id"));
        assertEquals(1, snapshot.get("unsupported_object_count"));
        assertEquals(Map.of("DOOR", 1), snapshot.get("unsupported_object_types"));
        assertEquals(0, section(snapshot, "players").get("total_count"));
    }

    @Test
    void rejectsUnboundedLimitsBeforeReadingTheWorld() {
        MapleMap map = mock(MapleMap.class);
        assertThrows(IllegalArgumentException.class, () -> SceneSnapshotMapper.snapshot(map, 0));
        assertThrows(IllegalArgumentException.class, () -> SceneSnapshotMapper.snapshot(map, 501));
        verifyNoInteractions(map);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> snapshot, String category) {
        return (Map<String, Object>) snapshot.get(category);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> snapshot, String category) {
        return (List<Map<String, Object>>) section(snapshot, category).get("items");
    }
}
