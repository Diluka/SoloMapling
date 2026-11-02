package soloMapling.game.action;

import org.gms.client.AbstractCharacterObject;
import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Disease;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.autoban.AutobanFactory;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.WeaponType;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLDomMapleData;
import org.gms.scripting.AbstractPlayerInteraction;
import org.gms.server.ItemInformationProvider;
import org.gms.server.StatEffect;
import org.gms.server.life.Element;
import org.gms.server.life.MobSkill;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapleMap;
import org.gms.util.Pair;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real WZ, learned skill records, native handlers, HP/MP and Monster damage; world/transport are isolated. */
class NativeCombatActionsTest {
    private final Map<Integer, Skill> learned = new LinkedHashMap<>();
    private Character actor;
    private Client client;
    private Server server;
    private MapleMap map;
    private GameContext.SceneRef scene;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private CharacterCommandLane.ControlPermit permit;
    private ThreadPoolExecutor executor;
    private ItemInformationProvider items;
    private MockedStatic<Server> servers;
    private MockedStatic<ItemInformationProvider> metadata;
    private MockedStatic<SkillFactory> skills;

    @BeforeEach void nativeFixture() throws Exception {
        InventoryMetadataTestContext.install();
        WorldEpoch.onWorldStarted();
        Job.values();
        try (var providers = mockStatic(DataProviderFactory.class)) {
            providers.when(() -> DataProviderFactory.getDataProvider(any(WZFiles.class))).thenReturn(mock(DataProvider.class));
            Class.forName(SkillFactory.class.getName(), true, SkillFactory.class.getClassLoader());
        }
        skills = mockStatic(SkillFactory.class);
        for (int id : List.of(1001003, 1001004, 1001005, 2001002, 2001003, 2001004, 2001005, 2321004)) {
            var file = Path.of("upstream/BeiDou-Server/gms-server/wz/Skill.wz", id / 10000 + ".img.xml");
            try (var input = new FileInputStream(file.toFile())) {
                var data = new XMLDomMapleData(input, file.getParent());
                var levels = data.getChildByPath("skill/" + id + "/level");
                var skill = new Skill(id);
                skill.setElement(Element.NEUTRAL);
                boolean buff = id == 1001003 || id == 2001002 || id == 2001003 || id == 2321004;
                for (int level = 1; level <= levels.getChildren().size(); level++) {
                    skill.addLevelEffect(StatEffect.loadSkillEffectFromData(levels.getChildByPath(String.valueOf(level)), id, buff));
                }
                learned.put(id, skill);
                skills.when(() -> SkillFactory.getSkill(id)).thenReturn(skill);
            }
        }
        server = mock(Server.class);
        when(server.isOnline()).thenReturn(true);
        when(server.getCurrentTime()).thenReturn(10_000L);
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(server);
        items = mock(ItemInformationProvider.class);
        when(items.getWeaponType(1302000)).thenReturn(WeaponType.SWORD1H);
        when(items.getWeaponType(1372000)).thenReturn(WeaponType.WAND);
        metadata = mockStatic(ItemInformationProvider.class);
        metadata.when(ItemInformationProvider::getInstance).thenReturn(items);

        client = mock(Client.class);
        when(client.isLoggedIn()).thenReturn(true);
        when(client.getChannel()).thenReturn(1);
        actor = spy(Character.getDefault(client));
        actor.setId(41);
        actor.setName("NativeSkill41");
        actor.setLevel(30);
        when(client.getPlayer()).thenReturn(actor);
        when(client.getAbstractPlayerInteraction()).thenReturn(new AbstractPlayerInteraction(client));
        doNothing().when(actor).hpChangeAction(anyInt());
        doReturn(Long.MAX_VALUE).when(actor).getAttackInterval(anyInt(), anyLong());
        doReturn(mock(AutobanManager.class)).when(actor).getAutoBanManager();
        for (var name : List.of("maxHp", "localMaxHp")) setInt(AbstractCharacterObject.class, name, 201);
        for (var name : List.of("maxMp", "localMaxMp")) setInt(AbstractCharacterObject.class, name, 103);
        setInt(AbstractCharacterObject.class, "attrStr", 100);
        setInt(AbstractCharacterObject.class, "attrDex", 20);
        setInt(AbstractCharacterObject.class, "attrInt", 100);
        setInt(AbstractCharacterObject.class, "attrLuk", 10);
        setInt(Character.class, "localstr", 100);
        setInt(Character.class, "localdex", 20);
        setInt(Character.class, "localint_", 100);
        setInt(Character.class, "localluk", 10);
        setInt(Character.class, "localwatk", 20);
        setInt(Character.class, "localmagic", 120);
        actor.updateHpMp(201, 103);
        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        when(map.getCharacterById(41)).thenReturn(actor);
        when(map.getMapObjectsInRect(any(Rectangle.class), anyList())).thenReturn(List.of(actor));
        actor.setMap(map);
        actor.setMapId(100000000);
        actor.setPosition(new Point());
        var world = mock(World.class);
        var channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        when(client.getChannelServer()).thenReturn(channel);
        when(client.getWorldServer()).thenReturn(world);
        var players = new PlayerStorage(); players.addPlayer(actor);
        when(world.getPlayerStorage()).thenReturn(players);
        when(channel.getPlayerStorage()).thenReturn(players);
        var ref = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), actor, client, world, channel);
        scene = new GameContext.SceneRef(ref, map, null, null);
        scope = new ExecutionScope(ref.epoch(), new ExecutionScope.Limits(16, 1, 8, 1));
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), body ->
                new Thread(() -> {
                    try (var worldLookup = mockStatic(Server.class);
                         var skillLookup = mockStatic(SkillFactory.class);
                         var itemLookup = mockStatic(ItemInformationProvider.class)) {
                        worldLookup.when(Server::getInstance).thenReturn(server);
                        itemLookup.when(ItemInformationProvider::getInstance).thenReturn(items);
                        learned.forEach((id, skill) -> skillLookup.when(() -> SkillFactory.getSkill(id)).thenReturn(skill));
                        body.run();
                    }
                }, "native-combat-action-test"));
        lane = new CharacterCommandLane(8, 1, executor);
        permit = lane.bindGrantedPermit(scope, ref, lane.declareGrant(scope),
                () -> NativeContextChecks.requireCharacterCurrent(server, ref));
        role(Job.MAGICIAN, 1372000);
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (actor != null) actor.cancelAllBuffs(false);
        } finally {
            if (lane != null) lane.close();
            if (executor != null) {
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            ThreadLocalUtil.removeCurrentClient();
            if (metadata != null) metadata.close();
            if (servers != null) servers.close();
            if (skills != null) skills.close();
            ExecutionScope.tryBeginWorldStop();
        }
    }

    @Test void learnedMagicClawUsesCurrentWzLevelAndConsumesNativeMpOnceForItsTwoLines() throws Exception {
        assertNull(NativeCombatActions.plan(actor, 2001005));
        learn(2001005, 1);
        var target = monster(501, 40);
        var firstPlan = NativeCombatActions.plan(actor, 2001005);
        assertEquals(1, firstPlan.skillLevel());
        assertEquals(2, firstPlan.damageLines());
        var first = strike(firstPlan, List.of(target));
        assertEquals(NativeCombatActions.Outcome.APPLIED, first.outcome());
        assertFalse(first.handlerError());
        assertEquals(10, first.mpBefore() - first.mpAfter());
        assertEquals(1, first.hits().size());
        assertTrue(target.getHp() < 10000);
        learn(2001005, 20);
        var changed = NativeCombatActions.plan(actor, 2001005);
        assertEquals(20, changed.skillLevel());
        var second = strike(changed, List.of(target));
        assertEquals(NativeCombatActions.Outcome.APPLIED, second.outcome());
        assertEquals(20, second.mpBefore() - second.mpAfter());
        assertEquals(73, actor.getMp());
        assertTrue(actor.getInventory(InventoryType.USE).list().isEmpty());
    }

    @Test void slashBlastConsumesOneRealHpMpCostForTheWholeNativeTargetSet() throws Exception {
        role(Job.WARRIOR, 1302000);
        learn(1001005, 1);
        var first = monster(501, 40); var second = monster(502, 60);
        var result = strike(NativeCombatActions.plan(actor, 1001005), List.of(first, second));
        assertEquals(NativeCombatActions.Outcome.APPLIED, result.outcome());
        assertFalse(result.handlerError());
        assertEquals(8, result.hpBefore() - result.hpAfter());
        assertEquals(6, result.mpBefore() - result.mpAfter());
        assertEquals(2, result.hits().size());
        assertTrue(first.getHp() < 10000 && second.getHp() < 10000);
        verify(map).damageMonster(eq(actor), eq(first), anyInt());
        verify(map).damageMonster(eq(actor), eq(second), anyInt());
    }

    @Test void nativeInfinityAllowsTheLearnedAncestorSpellAtZeroMpWithoutAnMpCheatPoint() throws Exception {
        role(Job.BISHOP, 1372000);
        learn(2001005, 1);
        learn(2321004, 1);
        // Material preparation installs the real native Infinity effect; casting it is not this test's action.
        learned.get(2321004).getEffect(1).silentApplyBuff(actor, server.getCurrentTime());
        assertNotNull(actor.getBuffedValue(BuffStat.INFINITY));
        actor.updateHpMp(201, 0);
        var target = monster(501, 40);
        var result = strike(NativeCombatActions.plan(actor, 2001005), List.of(target));
        assertEquals(NativeCombatActions.Outcome.APPLIED, result.outcome());
        assertFalse(result.handlerError());
        assertEquals(0, result.mpBefore());
        assertEquals(0, result.mpAfter());
        assertEquals(0, actor.getMp());
        assertTrue(target.getHp() < 10000);
        verify(actor.getAutoBanManager(), never()).addPoint(eq(AutobanFactory.MPCON), anyString());
    }

    @Test void resourceAndSealRejectionsHaveNoNativeAttackEffectWhileSealedBasicAttackRemainsLegal() throws Exception {
        learn(2001005, 1);
        var target = monster(501, 40);
        actor.updateHpMp(201, 9);
        var lowMp = strike(NativeCombatActions.plan(actor, 2001005), List.of(target));
        assertEquals(NativeCombatActions.Outcome.RESOURCE_UNAVAILABLE, lowMp.outcome());
        assertFalse(lowMp.dispatched());
        assertEquals(9, actor.getMp());
        role(Job.WARRIOR, 1302000);
        learn(1001005, 1);
        actor.updateHpMp(8, 103);
        var lowHp = strike(NativeCombatActions.plan(actor, 1001005), List.of(target));
        assertEquals(NativeCombatActions.Outcome.RESOURCE_UNAVAILABLE, lowHp.outcome());
        assertFalse(lowHp.dispatched());
        assertEquals(8, actor.getHp());
        actor.updateHpMp(201, 103);
        actor.silentApplyDiseases(Map.of(Disease.SEAL, new Pair<>(60000L, mock(MobSkill.class))));
        var sealed = strike(NativeCombatActions.plan(actor, 1001005), List.of(target));
        assertEquals(NativeCombatActions.Outcome.REJECTED, sealed.outcome());
        assertEquals("skill_sealed", sealed.reason());
        assertFalse(sealed.dispatched());
        assertEquals(10000, target.getHp());
        assertEquals(201, actor.getHp()); assertEquals(103, actor.getMp());
        assertFalse(actor.skillIsCooling(1001005));
        var basic = strike(NativeCombatActions.plan(actor, 0), List.of(target));
        assertEquals(NativeCombatActions.Outcome.APPLIED, basic.outcome());
        assertTrue(target.getHp() < 10000);
        assertTrue(actor.hasDisease(Disease.SEAL));
    }

    @Test void magicGuardRunsNativeSpecialMoveAndRegistersTheLearnedEffectWithItsActualCost() throws Exception {
        learn(2001002, 1);
        var result = onLane(work -> NativeCombatActions.buff(server, scene, work, 2001002));
        assertEquals(NativeCombatActions.Outcome.APPLIED, result.outcome());
        assertFalse(result.handlerError());
        assertEquals(6, result.mpBefore() - result.mpAfter());
        assertEquals(111000, result.durationMs());
        assertEquals(Integer.valueOf(11), actor.getBuffedValue(BuffStat.MAGIC_GUARD));
        assertEquals(2001002, actor.getBuffSource(BuffStat.MAGIC_GUARD));
        assertEquals(97, actor.getMp());
    }

    private void setInt(Class<?> declaring, String name, int value) throws Exception {
        var field = declaring.getDeclaredField(name); field.setAccessible(true); field.setInt(actor, value);
    }

    private void role(Job job, int weaponId) {
        actor.setJob(job);
        var equipped = actor.getInventory(InventoryType.EQUIPPED);
        equipped.removeSlot((short) -11);
        var weapon = new Equip(weaponId, (short) -11);
        weapon.setWatk((short) 20);
        weapon.setMatk((short) 20);
        equipped.addItemFromDB(weapon);
    }

    private void learn(int skillId, int level) {
        actor.changeSkillLevel(learned.get(skillId), (byte) level, learned.get(skillId).getMaxLevel(), -1);
    }

    private Monster monster(int oid, int x) {
        var stats = new MonsterStats(); stats.hp = 10000; stats.level = 1; stats.name = "NativeCombatTarget";
        stats.bboxValid = true; stats.bboxMinX = -10; stats.bboxMaxX = 10; stats.bboxMinY = -20; stats.bboxMaxY = 0;
        var target = spy(new Monster(100100, stats));
        target.setObjectId(oid); target.setPosition(new Point(x, 0)); target.setMap(map);
        doNothing().when(target).aggroMonsterDamage(any(Character.class), anyInt());
        when(map.getMonsterByOid(oid)).thenReturn(target);
        when(map.damageMonster(eq(actor), eq(target), anyInt())).thenCallRealMethod();
        return target;
    }

    private NativeCombatActions.StrikeResult strike(NativeCombatActions.Plan plan, List<Monster> targets) throws Exception {
        return onLane(work -> NativeCombatActions.strike(server, scene, work, plan, targets, false));
    }

    private <T> T onLane(Function<ExecutionScope.WorkHandle, T> action) throws Exception {
        var work = scope.acceptWork();
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        lane.submit(work, permit, () -> {
            try { result.set(action.apply(work)); }
            catch (RuntimeException | Error stopped) { failure.set(stopped); }
        }, () -> {});
        work.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(work.isSettled());
        assertEquals(0, lane.snapshot().pending());
        if (failure.get() instanceof Error fatal) throw fatal;
        if (failure.get() instanceof RuntimeException stopped) throw stopped;
        assertNotNull(result.get());
        return result.get();
    }

}
