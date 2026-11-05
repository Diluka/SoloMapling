package org.gms.soloacceptance;

import io.netty.buffer.Unpooled;
import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Stat;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteBufInPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneCapturePacketProjectionTest {
    @BeforeAll static void nativeContext() { CharacterTestContext.install(); }

    @Test
    void outputKeepsNativeAttackFieldsTargetsAndPayloadInsteadOfInferringActionsFromSnapshots() {
        var map = mock(MapleMap.class);
        var actor = mock(Character.class);
        var client = mock(Client.class);
        when(client.getPlayer()).thenReturn(actor);
        when(actor.getId()).thenReturn(7);
        when(actor.getObjectId()).thenReturn(7);
        when(actor.getClient()).thenReturn(client);
        when(actor.getMap()).thenReturn(map);
        var damage = Map.of(300, List.of(17, 23));
        var melee = PacketCreator.closeRangeAttack(actor, 1001004, 3, 6, 0x12, damage, 4, 1, 9);
        var ranged = PacketCreator.rangedAttack(actor, 4001004, 2, 7, 0x12, 2070000, damage, 5, 0, 8);
        var magic = PacketCreator.magicAttack(actor, 2001004, 4, 8, 0x12, damage, 250, 6, 1, 7);
        try (var subscription = SceneCaptureEvents.open(map)) {
            for (var packet : List.of(melee, ranged, magic)) SceneCaptureEvents.output(client, packet, true, null);
            var records = assertInstanceOf(List.class, subscription.drain().get("items"));
            assertEquals(3, records.size());
            var event = assertInstanceOf(Map.class, records.getFirst());
            assertEquals("output", event.get("category"));
            var values = assertInstanceOf(Map.class, event.get("values"));
            assertEquals("complete", values.get("decode_status"));
            assertArrayEquals(melee.getBytes(), Base64.getDecoder().decode((String) values.get("payload_base64")));
            var fields = assertInstanceOf(Map.class, values.get("protocol_fields"));
            assertEquals(7, fields.get("character_id"));
            assertEquals(1001004, fields.get("skill_id"));
            assertEquals(3, fields.get("skill_level"));
            assertEquals(6, fields.get("stance"));
            assertEquals(1, fields.get("direction"));
            assertEquals(4, fields.get("speed"));
            assertEquals(9, fields.get("display"));
            assertEquals(List.of(Map.of("monster_oid", 300, "target_marker", 0, "damage", List.of(17, 23))), fields.get("targets"));
            var rangedValues = assertInstanceOf(Map.class, assertInstanceOf(Map.class, records.get(1)).get("values"));
            var rangedFields = assertInstanceOf(Map.class, rangedValues.get("protocol_fields"));
            assertEquals(2070000, rangedFields.get("projectile_item_id"));
            var magicValues = assertInstanceOf(Map.class, assertInstanceOf(Map.class, records.get(2)).get("values"));
            assertEquals(250, assertInstanceOf(Map.class, magicValues.get("protocol_fields")).get("charge"));
        }
    }

    @Test
    void outputSeparatesMovementFragmentsAttributeNewValuesAndBuffMasks() {
        var map = mock(MapleMap.class);
        var actor = mock(Character.class);
        var client = mock(Client.class);
        when(client.getPlayer()).thenReturn(actor);
        when(actor.getId()).thenReturn(7);
        when(actor.getClient()).thenReturn(client);
        when(actor.getMap()).thenReturn(map);
        byte[] movementBytes = {1, 0, 12, 0, -4, -1, 20, 0, 0, 0, 2, 0, 19, 30, 0};
        var buffer = Unpooled.wrappedBuffer(movementBytes);
        try (var subscription = SceneCaptureEvents.open(map)) {
            SceneCaptureEvents.output(client, PacketCreator.movePlayer(7, new ByteBufInPacket(buffer), movementBytes.length), true, null);
            SceneCaptureEvents.output(client, PacketCreator.updatePlayerStats(new ArrayList<>(List.of(
                    new Pair<>(Stat.HP, 54), new Pair<>(Stat.MP, 12))), true, actor), true, null);
            SceneCaptureEvents.output(client, PacketCreator.giveBuff(1001003, 30_000,
                    List.of(new Pair<>(BuffStat.WDEF, 5))), true, null);
            SceneCaptureEvents.output(client, PacketCreator.killMonster(300, 1), true, null);
            var events = assertInstanceOf(List.class, subscription.drain().get("items"));
            var move = assertInstanceOf(Map.class, assertInstanceOf(Map.class, events.getFirst()).get("values"));
            var moveFields = assertInstanceOf(Map.class, move.get("protocol_fields"));
            var fragments = assertInstanceOf(List.class, moveFields.get("fragments"));
            var fragment = assertInstanceOf(Map.class, fragments.getFirst());
            assertEquals(Map.of("x", 12, "y", -4), fragment.get("position"));
            assertEquals(19, fragment.get("stance"));
            assertEquals(30, fragment.get("duration_ms"));
            var stats = assertInstanceOf(Map.class, assertInstanceOf(Map.class, events.get(1)).get("values"));
            assertEquals(Map.of("HP", 54, "MP", 12), assertInstanceOf(Map.class, stats.get("protocol_fields")).get("changed_stats"));
            var buff = assertInstanceOf(Map.class, assertInstanceOf(Map.class, events.get(2)).get("values"));
            assertEquals("complete", buff.get("decode_status"));
            assertEquals(Long.toUnsignedString(BuffStat.WDEF.getValue()),
                    assertInstanceOf(Map.class, buff.get("protocol_fields")).get("second_mask"));
            assertEquals(List.of(Map.of("stat", "WDEF", "value", 5, "source_id", 1001003, "duration_ms", 30_000)),
                    assertInstanceOf(Map.class, buff.get("protocol_fields")).get("updates"));
            assertTrue(buff.containsKey("payload_base64"));
            var removed = assertInstanceOf(Map.class, assertInstanceOf(Map.class, events.get(3)).get("values"));
            assertEquals(300, assertInstanceOf(Map.class, removed.get("protocol_fields")).get("monster_oid"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void unrelatedMessagesKeepTheirBodiesPrivateAndTruncatedSceneMessagesStayExplicit() {
        var map = mock(MapleMap.class);
        var actor = mock(Character.class);
        var client = mock(Client.class);
        when(client.getPlayer()).thenReturn(actor);
        when(actor.getClient()).thenReturn(client);
        when(actor.getMap()).thenReturn(map);
        var unrelated = OutPacket.create(SendOpcode.CHATTEXT);
        unrelated.writeString("private message body");
        Packet truncatedAttack = () -> new byte[] {(byte) 0xba, 0, 7};
        try (var subscription = SceneCaptureEvents.open(map)) {
            SceneCaptureEvents.output(client, unrelated, true, null);
            SceneCaptureEvents.output(client, truncatedAttack, true, null);
            var events = assertInstanceOf(List.class, subscription.drain().get("items"));
            assertEquals(1, events.size());
            var values = assertInstanceOf(Map.class, assertInstanceOf(Map.class, events.getFirst()).get("values"));
            assertEquals("malformed", values.get("decode_status"));
            assertFalse(values.containsKey("payload_base64"));
            assertFalse(values.containsKey("protocol_fields"));
            assertEquals(SendOpcode.CLOSE_RANGE_ATTACK.getValue(), values.get("opcode"));
        }
    }
}
