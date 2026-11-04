package soloMapling.game.action;

import org.gms.client.BuffStat;
import org.gms.client.AbstractCharacterObject;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.SkillFactory;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.constants.game.CharacterStance;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.Packet;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapleMap;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotMobHitboxProvider;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.scene.PresentationSceneModule;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real damage handler and HP/MP settlement; world membership and WZ geometry are controlled boundaries. */
class NativeContactDamageActionsTest {
    private Server server;
    private Character actor;
    private Client client;
    private MapleMap map;
    private Monster target;
    private Equip armor;
    private GameContext.SceneRef scene;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private CharacterCommandLane.ControlPermit permit;
    private ThreadPoolExecutor executor;
    private MockedStatic<Server> servers;
    private MockedStatic<SkillFactory> skills;
    private MockedStatic<BotMobHitboxProvider> bodies;

    @BeforeEach void fixture() throws Exception {
        InventoryMetadataTestContext.install();
        Job.values();
        WorldEpoch.onWorldStarted();
        try (var providers = mockStatic(DataProviderFactory.class)) {
            providers.when(() -> DataProviderFactory.getDataProvider(any(WZFiles.class))).thenReturn(mock(DataProvider.class));
            Class.forName(SkillFactory.class.getName(), true, SkillFactory.class.getClassLoader());
        }
        skills = mockStatic(SkillFactory.class);
        server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getCurrentTime()).thenReturn(1000L);
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(server);
        client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(client.isLoggedIn()).thenReturn(true);
        actor = spy(Character.getDefault(client));
        actor.setId(41);
        actor.setName("Contact41");
        actor.setPosition(new Point(0, 0));
        when(client.getPlayer()).thenReturn(actor);
        doReturn(mock(AutobanManager.class)).when(actor).getAutoBanManager();
        for (var name : List.of("maxHp", "localMaxHp")) {
            var field = AbstractCharacterObject.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(actor, 100);
        }
        for (var name : List.of("maxMp", "localMaxMp")) {
            var field = AbstractCharacterObject.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(actor, 50);
        }
        actor.updateHpMp(100, 50);
        armor = new Equip(1040002, (short) -5);
        armor.setWdef((short) 20);
        actor.getInventory(InventoryType.EQUIPPED).addItemFromDB(armor);

        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(10000);
        when(map.getCharacterById(41)).thenReturn(actor);
        actor.setMap(map);
        actor.setMapId(10000);
        var stats = new MonsterStats();
        stats.hp = 1000;
        stats.PADamage = 100;
        stats.name = "contact-material";
        target = new Monster(100100, stats);
        target.setObjectId(501);
        target.setPosition(new Point(0, 0));
        target.setMap(map);
        when(map.getAllMonsters()).thenReturn(List.of(target));
        when(map.getMonsterByOid(501)).thenReturn(target);
        when(map.getMapObject(501)).thenReturn(target);
        bodies = mockStatic(BotMobHitboxProvider.class);
        bodies.when(() -> BotMobHitboxProvider.getMobBounds(target)).thenAnswer(call ->
                new Rectangle(target.getPosition().x - 10, target.getPosition().y - 20, 20, 20));

        var world = mock(World.class);
        var channel = mock(Channel.class);
        var players = new PlayerStorage();
        players.addPlayer(actor);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        when(world.getPlayerStorage()).thenReturn(players);
        when(channel.getPlayerStorage()).thenReturn(players);
        var ref = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), actor, client, world, channel);
        scene = new GameContext.SceneRef(ref, map, null, null);
        scope = new ExecutionScope(ref.epoch(), new ExecutionScope.Limits(4, 1, 2, 1));
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), body ->
                new Thread(() -> {
                    try (var workerServer = mockStatic(Server.class);
                         var workerSkills = mockStatic(SkillFactory.class);
                         var workerBodies = mockStatic(BotMobHitboxProvider.class)) {
                        workerServer.when(Server::getInstance).thenReturn(server);
                        workerBodies.when(() -> BotMobHitboxProvider.getMobBounds(target)).thenAnswer(call ->
                                new Rectangle(target.getPosition().x - 10, target.getPosition().y - 20, 20, 20));
                        body.run();
                    }
                }, "contact-test-lane"));
        lane = new CharacterCommandLane(4, 1, executor);
        permit = lane.bindGrantedPermit(scope, ref, lane.declareGrant(scope),
                () -> NativeContextChecks.requireSceneCurrent(server, scene));
    }

    @AfterEach void cleanup() throws InterruptedException {
        if (lane != null) lane.close();
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        if (bodies != null) bodies.close();
        if (skills != null) skills.close();
        if (servers != null) servers.close();
        ThreadLocalUtil.removeCurrentClient();
        ExecutionScope.tryBeginWorldStop();
    }

    @Test void nativeBodyContactLosesHpAndUsesActualEquipmentDefense() {
        var first = contact();
        assertTrue(first.dispatched());
        assertFalse(first.handlerError());
        assertEquals("contact_physical_v1", first.policy());
        assertEquals(501, first.monsterOid());
        assertEquals(20, first.physicalDefense());
        assertEquals(90, first.computedDamage());
        assertEquals(90, first.hpLost());
        assertEquals(10, actor.getHp());
        assertEquals(0, first.mpLost());

        actor.updateHpMp(100, 50);
        armor.setWdef((short) 100);
        var defended = contact();
        assertEquals(50, defended.computedDamage());
        assertEquals(50, defended.hpLost());
        assertEquals(50, actor.getHp());
        assertEquals(0, lane.snapshot().pending());
    }

    @Test void nativeMagicGuardSpendsMpAndDeathAndRecoverySynchronizeTheVisiblePose() {
        actor.setStance(CharacterStance.STAND_LEFT_STANCE);
        doReturn(50).when(actor).getBuffedValue(BuffStat.MAGIC_GUARD);
        var shielded = contact();
        assertFalse(shielded.handlerError());
        assertEquals(45, shielded.hpLost());
        assertEquals(45, shielded.mpLost());
        assertEquals(55, actor.getHp());
        assertEquals(5, actor.getMp());
        var lethal = contact();
        assertFalse(lethal.handlerError());
        assertTrue(lethal.dead());
        assertEquals(0, actor.getHp());
        assertEquals(0, actor.getMp());
        assertEquals(CharacterStance.DEAD_LEFT_STANCE, actor.getStance());
        assertNull(contact());
        var output = org.mockito.ArgumentCaptor.forClass(Packet.class);
        verify(map, times(3)).broadcastMessage(eq(actor), output.capture(), eq(false));
        var movements = output.getAllValues().stream().map(Packet::getBytes)
                .filter(bytes -> ((bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8)) == SendOpcode.MOVE_PLAYER.getValue())
                .toList();
        assertEquals(1, movements.size());
        var packet = movements.getFirst();
        assertEquals(CharacterStance.DEAD_LEFT_STANCE, packet[22]);

        target.getStats().setFriendly(true);
        actor.updateHpMp(100, 50);
        assertNull(contact());
        assertEquals(CharacterStance.STAND_LEFT_STANCE, actor.getStance());
        assertNull(contact());
        verify(map, times(4)).broadcastMessage(eq(actor), any(Packet.class), eq(false));
    }

    @Test void missingBodyContactAndFriendlyMonstersDoNotDispatchDamage() {
        target.setPosition(new Point(100, 0));
        assertNull(contact());
        target.setPosition(new Point(0, 0));
        target.getStats().setFriendly(true);
        assertNull(contact());
        assertEquals(100, actor.getHp());
        assertEquals(50, actor.getMp());
    }

    @Test void monsterRemovedBetweenTheSnapshotAndDispatchSkipsThisContactAttempt() {
        when(map.getMonsterByOid(501)).thenReturn(null);
        clearInvocations(client);

        assertNull(contact());

        assertEquals(100, actor.getHp());
        assertEquals(50, actor.getMp());
        verify(map).getAllMonsters();
        verify(map).getMonsterByOid(501);
        verify(client, never()).sendPacket(any());
        assertEquals(0, lane.snapshot().pending());
    }

    @Test void sceneContactCancellationDuringNativeAdmissionPreventsDamage() {
        var work = scope.acceptWork();
        var cancelled = new AtomicReference<CancellationException>();
        doAnswer(call -> { scope.requestCancel(work); return true; }).when(client).isLoggedIn();
        lane.submit(work, permit, () -> {
            try { NativeContactDamageActions.contact(server, scene, work); }
            catch (CancellationException stopped) { cancelled.set(stopped); }
        }, () -> {});
        work.completion().toCompletableFuture().join();
        assertNotNull(cancelled.get());
        assertEquals(100, actor.getHp());
        assertEquals(50, actor.getMp());
        assertTrue(work.isSettled());
    }

    private NativeContactDamageActions.ContactResult contact() {
        var work = scope.acceptWork();
        var result = new AtomicReference<NativeContactDamageActions.ContactResult>();
        var failure = new AtomicReference<Throwable>();
        lane.submit(work, permit, () -> {
            try {
                var previous = ThreadLocalUtil.getCurrentClient();
                result.set(NativeContactDamageActions.contact(server, scene, work));
                new PresentationSceneModule().syncLifeState(server, scene, work);
                assertSame(previous, ThreadLocalUtil.getCurrentClient());
            }
            catch (RuntimeException | Error problem) { failure.set(problem); }
        }, () -> {});
        work.completion().toCompletableFuture().join();
        if (failure.get() != null) throw new AssertionError("contact_material_failed", failure.get());
        assertTrue(work.isSettled());
        return result.get();
    }

}
