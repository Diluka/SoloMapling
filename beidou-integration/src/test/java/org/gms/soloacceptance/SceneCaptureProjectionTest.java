package org.gms.soloacceptance;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.Pet;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneCaptureProjectionTest {
    @BeforeAll
    static void nativeContext() { CharacterTestContext.install(); }

    @Test
    void aSceneWithoutAnAcceptanceActorKeepsAllObjectsAndFreezesNativeLifeAndControllerFacts() {
        AcceptanceObservationRuntime.onWorldStarted();
        try {
            var server = mock(Server.class);
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var maps = mock(MapManager.class);
            var storage = mock(PlayerStorage.class);
            var map = mock(MapleMap.class);
            when(server.isOnline()).thenReturn(true);
            when(server.getWorlds()).thenReturn(List.of(world));
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannels()).thenReturn(List.of(channel));
            when(world.getChannel(1)).thenReturn(channel);
            when(world.getPlayerStorage()).thenReturn(storage);
            when(channel.getId()).thenReturn(1);
            when(channel.getPlayerStorage()).thenReturn(storage);
            when(channel.getMapFactory()).thenReturn(maps);
            when(maps.getMaps()).thenReturn(Map.of(104040000, map));
            when(map.getId()).thenReturn(104040000);
            var bounds = new Rectangle(-100, -200, 1000, 800);
            when(map.getMapArea()).thenReturn(bounds);
            var footholds = new FootholdTree(new Point(-100, -200), new Point(900, 600));
            footholds.insert(new Foothold(new Point(-80, 0), new Point(200, 0), 1));
            when(map.getFootholds()).thenReturn(footholds);
            when(map.getFootholdSpeed()).thenReturn(1.0f);

            var player = mock(Character.class);
            var client = mock(Client.class);
            var equipped = mock(Inventory.class);
            Job job = Job.BEGINNER;
            var hp = new AtomicInteger(85);
            var position = new Point(12, -4);
            when(player.getId()).thenReturn(7);
            when(player.getObjectId()).thenReturn(7);
            when(player.getType()).thenReturn(MapObjectType.PLAYER);
            when(player.getName()).thenReturn("Observer");
            when(player.getJob()).thenReturn(job);
            when(player.getMap()).thenReturn(map);
            when(player.getClient()).thenReturn(client);
            when(player.isLoggedInWorld()).thenReturn(true);
            when(player.getPosition()).thenReturn(position);
            when(player.getStance()).thenReturn(19);
            when(player.isFacingLeft()).thenReturn(true);
            when(player.isAlive()).thenReturn(true);
            when(player.isChangingMaps()).thenReturn(true);
            when(player.getHp()).thenAnswer(ignored -> hp.get());
            when(player.getMp()).thenReturn(31);
            when(player.getMaxHp()).thenReturn(100);
            when(player.getCurrentMaxHp()).thenReturn(120);
            when(player.getMaxMp()).thenReturn(40);
            when(player.getCurrentMaxMp()).thenReturn(50);
            when(player.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
            when(equipped.getItem((short) -11)).thenReturn(new Item(1302000, (short) -11, (short) 1));
            when(player.getPets()).thenReturn(new Pet[3]);
            when(storage.getAllCharacters()).thenReturn(List.of(player));
            when(map.getAllPlayers()).thenReturn(List.of(player));

            var monster = mock(Monster.class);
            when(monster.getObjectId()).thenReturn(300);
            when(monster.getType()).thenReturn(MapObjectType.MONSTER);
            when(monster.getId()).thenReturn(100101);
            when(monster.getPosition()).thenReturn(new Point(50, 0));
            when(monster.getMap()).thenReturn(map);
            when(monster.getHp()).thenReturn(30);
            when(monster.getMaxHp()).thenReturn(40);
            when(monster.isAlive()).thenReturn(true);
            when(monster.getController()).thenReturn(player);
            when(monster.isControllerHasAggro()).thenReturn(true);
            var objects = new ArrayList<MapObject>(List.of(player, monster));
            for (int i = 0; i < 125; i++) {
                var item = mock(MapItem.class);
                when(item.getObjectId()).thenReturn(1000 + i);
                when(item.getType()).thenReturn(MapObjectType.ITEM);
                when(item.getPosition()).thenReturn(new Point(i, 0));
                when(item.getItem()).thenReturn(new Item(4000001, (short) 1, (short) 2));
                objects.add(item);
            }
            when(map.getMapObjects()).thenReturn(objects);
            var observations = new AcceptanceObservationService(() -> server, Map::of, 8192);
            var directory = observations.observe(new ObservationRequest("world", null, 100));
            var scenes = assertInstanceOf(Map.class, directory.get("scenes"));
            var sceneRows = assertInstanceOf(List.class, scenes.get("items"));
            String sceneRef = (String) assertInstanceOf(Map.class, sceneRows.getFirst()).get("scene_ref");

            var captured = new SceneCaptureService(observations).snapshot(sceneRef);
            var frame = assertInstanceOf(Map.class, captured.get("frame"));
            var nativeState = assertInstanceOf(Map.class, frame.get("native"));
            var items = assertInstanceOf(Map.class, nativeState.get("items"));
            assertEquals(125, items.get("total_count"));
            assertEquals(125, items.get("recorded_count"));
            assertEquals(false, items.get("truncated"));
            assertEquals(true, items.get("complete"));
            var players = assertInstanceOf(Map.class, nativeState.get("players"));
            var capturedPlayers = assertInstanceOf(List.class, players.get("items"));
            var capturedPlayer = assertInstanceOf(Map.class, capturedPlayers.getFirst());
            assertEquals(85, capturedPlayer.get("hp"));
            assertEquals(31, capturedPlayer.get("mp"));
            assertEquals(120, capturedPlayer.get("max_hp"));
            assertEquals(19, capturedPlayer.get("stance"));
            assertEquals(true, capturedPlayer.get("changing_maps"));
            assertEquals(1302000, capturedPlayer.get("weapon_item_id"));
            var monsters = assertInstanceOf(Map.class, nativeState.get("monsters"));
            var capturedMonsters = assertInstanceOf(List.class, monsters.get("items"));
            var capturedMonster = assertInstanceOf(Map.class, capturedMonsters.getFirst());
            var controller = assertInstanceOf(Map.class, capturedMonster.get("controller"));
            assertEquals(7, controller.get("character_id"));
            assertEquals("native_client", controller.get("source"));
            assertEquals(true, controller.get("driver_eligible"));
            assertEquals(true, capturedMonster.get("controller_has_aggro"));
            assertEquals("unknown", capturedMonster.get("lifecycle_identity"));
            var simulation = assertInstanceOf(Map.class, frame.get("simulation"));
            assertEquals("UNAVAILABLE", simulation.get("state"));

            when(player.getPets()).thenThrow(new IllegalStateException("native_pet_array_unavailable"));
            var partial = new SceneCaptureService(observations).snapshot(sceneRef);
            var partialFrame = assertInstanceOf(Map.class, partial.get("frame"));
            var partialNative = assertInstanceOf(Map.class, partialFrame.get("native"));
            var partialPlayers = assertInstanceOf(Map.class, partialNative.get("players"));
            assertEquals(1, partialPlayers.get("recorded_count"));
            var partialPets = assertInstanceOf(Map.class, partialNative.get("pets"));
            assertNull(partialPets.get("total_count"));
            assertEquals(false, partialPets.get("count_complete"));
            assertEquals(false, partialPets.get("complete"));
            assertEquals(1, partialPets.get("unavailable_owner_count"));

            hp.set(0);
            position.x = 500;
            bounds.width = 5;
            objects.clear();
            assertEquals(85, capturedPlayer.get("hp"));
            assertEquals(Map.of("x", 12, "y", -4), capturedPlayer.get("position"));
            assertEquals(125, assertInstanceOf(List.class, items.get("items")).size());
            var staticScene = assertInstanceOf(Map.class, captured.get("static_scene"));
            assertEquals(1000, assertInstanceOf(Map.class, staticScene.get("bounds")).get("width"));
            verify(monster, never()).aggroSwitchController(any(), anyBoolean());
            verify(map, never()).addPlayer(any());
        } finally {
            AcceptanceObservationRuntime.onWorldStopped();
        }
    }
}
