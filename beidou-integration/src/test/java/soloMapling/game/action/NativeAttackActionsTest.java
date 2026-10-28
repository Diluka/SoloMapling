package soloMapling.game.action;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.SkillFactory;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.WeaponType;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;
import org.gms.server.ItemInformationProvider;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterListener;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapleMap;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;

/** Real native melee parser and Monster HP accounting, with explicit map/aggro host seams. */
class NativeAttackActionsTest {
    private Server server;
    private Character actor;
    private Client client;
    private MapleMap map;
    private Monster target;
    private Inventory equipped;
    private ItemInformationProvider items;
    private ExecutionScope scope;
    private CharacterCommandLane lane;
    private CharacterCommandLane.ControlPermit permit;
    private GameContext.SceneRef scene;
    private ManualExecutor executor;
    private MockedStatic<Server> servers;
    private MockedStatic<ItemInformationProvider> metadata;
    private MockedStatic<SkillFactory> skills;

    @BeforeEach void fixture() throws Exception {
        InventoryMetadataTestContext.install(); WorldEpoch.onWorldStarted();
        Job beginner = Job.BEGINNER; // Initialise its translated labels before any Mockito stubbing frame.
        DataProvider provider = mock(DataProvider.class);
        try (MockedStatic<DataProviderFactory> providers = mockStatic(DataProviderFactory.class)) {
            providers.when(() -> DataProviderFactory.getDataProvider(any(WZFiles.class))).thenReturn(provider);
            Class.forName(SkillFactory.class.getName(), true, SkillFactory.class.getClassLoader());
        }
        skills = mockStatic(SkillFactory.class);
        server = mock(Server.class); when(server.isOnline()).thenReturn(true);
        servers = mockStatic(Server.class); servers.when(Server::getInstance).thenReturn(server);
        actor = mock(Character.class); client = mock(Client.class); map = mock(MapleMap.class);
        when(actor.getId()).thenReturn(41); when(actor.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(actor); when(client.getChannel()).thenReturn(1);
        when(client.isLoggedIn()).thenReturn(true); when(actor.getMap()).thenReturn(map);
        when(map.getCharacterById(41)).thenReturn(actor); when(map.getId()).thenReturn(100000000);
        when(actor.getPosition()).thenAnswer(call -> new Point(0, 0)); when(actor.isAlive()).thenReturn(true);
        when(actor.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(actor.getJob()).thenReturn(beginner); when(actor.getLevel()).thenReturn(10);
        when(actor.getTotalDex()).thenReturn(12); when(actor.getTotalLuk()).thenReturn(4);
        when(actor.getTotalWatk()).thenReturn(10); when(actor.calculateMaxBaseDamage(10)).thenReturn(50);
        when(actor.calculateMaxBaseDamage(10, WeaponType.SWORD1H)).thenReturn(50);
        when(actor.getAttackInterval(anyInt(), anyLong())).thenReturn(Long.MAX_VALUE);
        when(actor.getAutoBanManager()).thenReturn(mock(AutobanManager.class));
        equipped = new Inventory(actor, InventoryType.EQUIPPED, (byte) 24);
        equipped.addItemFromDB(new Equip(1302000, (short) -11));
        when(actor.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        items = mock(ItemInformationProvider.class); when(items.getWeaponType(1302000)).thenReturn(WeaponType.SWORD1H);
        metadata = mockStatic(ItemInformationProvider.class); metadata.when(ItemInformationProvider::getInstance).thenReturn(items);
        MonsterStats stats = new MonsterStats(); stats.hp = 1000; stats.level = 1; stats.name = "memory-test-monster";
        stats.bboxValid = true; stats.bboxMinX = -10; stats.bboxMaxX = 10; stats.bboxMinY = -20; stats.bboxMaxY = 0;
        target = spy(new Monster(100100, stats)); target.setObjectId(501); target.setPosition(new Point(50, 0)); target.setMap(map);
        doNothing().when(target).aggroMonsterDamage(any(Character.class), anyInt());
        when(map.getMonsterByOid(501)).thenReturn(target);
        when(map.damageMonster(eq(actor), eq(target), anyInt())).thenAnswer(call -> {
            target.damage(actor, call.getArgument(2), false); return true;
        });
        World world = mock(World.class); Channel channel = mock(Channel.class); when(channel.getId()).thenReturn(1);
        when(server.getWorld(0)).thenReturn(world); when(world.getChannel(1)).thenReturn(channel);
        PlayerStorage worldPlayers = mock(PlayerStorage.class), channelPlayers = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(worldPlayers); when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        when(worldPlayers.getCharacterById(41)).thenReturn(actor); when(channelPlayers.getCharacterById(41)).thenReturn(actor);
        var ref = new GameContext.CharacterRef(WorldEpoch.snapshot().token(), actor, client, world, channel);
        scene = new GameContext.SceneRef(ref, map, null, null);
        scope = new ExecutionScope(ref.epoch(), new ExecutionScope.Limits(16, 1, 8, 1));
        executor = new ManualExecutor(); lane = new CharacterCommandLane(8, 1, executor);
        permit = lane.bindGrantedPermit(scope, ref, lane.declareGrant(scope), () -> NativeContextChecks.requireCharacterCurrent(server, ref));
    }

    @AfterEach void cleanup() {
        if (lane != null) lane.close(); if (executor != null) executor.shutdown();
        ThreadLocalUtil.removeCurrentClient();
        if (metadata != null) metadata.close(); if (servers != null) servers.close(); if (skills != null) skills.close();
        ExecutionScope.tryBeginWorldStop();
    }

    @Test void realNativeHitHasExactWovenDamageEvidenceAndUsesOneCarrier() {
        var work = scope.acceptWork(); var handle = prepare(work);
        AtomicBoolean settledAtResult = new AtomicBoolean(true);
        var callback = handle.result().thenAccept(result -> settledAtResult.set(work.isSettled()));
        assertSame(NativeBasicActions.submit(handle), NativeBasicActions.submit(handle));
        assertEquals(1, executor.queue.size()); executor.runNext(); callback.toCompletableFuture().join();
        NativeBasicResult result = handle.result().toCompletableFuture().join();
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(true, result.facts().get("hit"));
        assertEquals(1000 - target.getHp(), ((Number) result.facts().get("damage_applied")).intValue());
        assertEquals(1, result.facts().get("native_damage_entries")); assertEquals(false, result.facts().get("native_last_hit"));
        assertFalse(settledAtResult.get()); assertTrue(work.isSettled());
        assertTrue(target.isAttackedBy(actor)); verify(map, times(1)).damageMonster(eq(actor), eq(target), anyInt());
        assertNull(ThreadLocalUtil.getCurrentClient());
    }

    @Test void actualMissIsAnObservedNativeAttemptWithZeroAttributedDamage() {
        target.getStats().eva = 1000; when(actor.getTotalDex()).thenReturn(0); when(actor.getTotalLuk()).thenReturn(0);
        NativeBasicResult result = run();
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(false, result.facts().get("hit"));
        assertEquals(0L, result.facts().get("damage_applied")); assertEquals(1000, target.getHp());
        verify(map).damageMonster(actor, target, 0);
    }

    @Test void nativeDamageNeverExceedsTheRolesBaseBoundAndPhysicalDefenseKeepsAMinimumHit() {
        NativeBasicResult ordinary = run();
        int applied = ((Number)ordinary.facts().get("damage_applied")).intValue();
        assertEquals(SUCCEEDED,ordinary.outcome()); assertTrue(applied >= 5 && applied <= 50);
        assertEquals(1000 - target.getHp(),applied);
        target.getStats().PDDamage = 1000;
        NativeBasicResult defended = run();
        assertEquals(SUCCEEDED,defended.outcome()); assertEquals(1L,defended.facts().get("damage_applied"));
        assertEquals(999 - applied,target.getHp());
    }

    @Test void targetLevelAffectsNativeHitChanceAndDamageWithoutChoosingRandomRolls() {
        target.getStats().eva = 3; target.getStats().level = 10;
        NativeBasicResult ordinary = run();
        assertEquals(SUCCEEDED,ordinary.outcome()); assertEquals(1.0,ordinary.facts().get("hit_chance"));
        int hp = target.getHp();
        target.getStats().level = 110;
        NativeBasicResult missed = run();
        assertEquals(SUCCEEDED,missed.outcome()); assertEquals(0.0,missed.facts().get("hit_chance"));
        assertEquals(0L,missed.facts().get("damage_applied")); assertEquals(hp,target.getHp());
        target.getStats().eva = 0;
        NativeBasicResult levelPenalty = run();
        assertEquals(SUCCEEDED,levelPenalty.outcome()); assertEquals(1L,levelPenalty.facts().get("damage_applied"));
        assertEquals(hp - 1,target.getHp());
    }

    @Test void anotherThreadAndAnotherActorCannotSupplyThisAttacksDamage() throws Exception {
        Character other = mock(Character.class); when(other.getId()).thenReturn(99);
        when(map.damageMonster(eq(actor), eq(target), anyInt())).thenAnswer(call -> {
            CompletableFuture.runAsync(() -> target.damage(other, 3, false)).get(3, TimeUnit.SECONDS);
            target.damage(actor, call.getArgument(2), false); return true;
        });
        NativeBasicResult result = run();
        assertEquals(SUCCEEDED, result.outcome());
        assertEquals(1000 - target.getHp() - 3, ((Number) result.facts().get("damage_applied")).intValue());
        assertEquals(1, result.facts().get("native_damage_entries"));
    }

    @Test void nativeLastHitAndDisposedNegativeHpDoNotInflateAppliedDamage() {
        target.setStartingHp(1);
        when(map.damageMonster(eq(actor), eq(target), anyInt())).thenAnswer(call -> {
            boolean killed = target.damage(actor, call.getArgument(2), false);
            if (killed) target.disposeMapObject(); return true;
        });
        NativeBasicResult result = run();
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(1L, result.facts().get("damage_applied"));
        assertEquals(true, result.facts().get("native_last_hit")); assertEquals(0, result.facts().get("target_hp_after"));
        assertEquals(true, result.facts().get("target_dead_after"));
        assertEquals(-1, target.getHp());
    }

    @Test void anExceptionAfterRealNativeDamageDoesNotEraseIt() {
        when(map.damageMonster(eq(actor), eq(target), anyInt())).thenAnswer(call -> {
            target.damage(actor, call.getArgument(2), false); throw new AssertionError("after native damage");
        });
        Client previous = mock(Client.class); ThreadLocalUtil.setCurrentClient(previous);
        NativeBasicResult result = run();
        assertEquals(SUCCEEDED, result.outcome()); assertTrue(result.handlerError());
        assertEquals(true, result.facts().get("hit")); assertSame(previous, ThreadLocalUtil.getCurrentClient());
    }

    @Test void aWorldStopAfterRealNativeDamagePreservesTheOldEpochFact() {
        when(map.damageMonster(eq(actor), eq(target), anyInt())).thenAnswer(call -> {
            target.damage(actor, call.getArgument(2), false); ExecutionScope.tryBeginWorldStop(); return true;
        });
        NativeBasicResult result = run();
        assertEquals(SUCCEEDED, result.outcome()); assertEquals(scene.actor().epoch().value(), result.epoch());
        assertFalse(WorldEpoch.snapshot().active());
    }

    @Test void nativeDamageBeforeListenerFailureDoesNotInventALastHitReturn() {
        target.setStartingHp(1);
        MonsterListener listener = mock(MonsterListener.class);
        doThrow(new AssertionError("listener failed after HP accounting")).when(listener).monsterDamaged(eq(actor), anyInt());
        target.addListener(listener);
        NativeBasicResult result = run();
        assertEquals(SUCCEEDED, result.outcome()); assertTrue(result.handlerError());
        assertEquals(1L, result.facts().get("damage_applied")); assertEquals(true, result.facts().get("target_dead_after"));
        assertNull(result.facts().get("native_last_hit")); assertEquals(0, result.facts().get("native_damage_returns"));
    }

    @Test void cancellationAndBadTargetStateNeverEnterTheNativeHandler() {
        var work = scope.acceptWork(); var handle = prepare(work);
        NativeBasicActions.submit(handle); scope.requestCancel(work); executor.runNext();
        assertEquals(CANCELLED, handle.result().toCompletableFuture().join().outcome());
        verify(map, never()).damageMonster(any(), any(), anyInt());
        target.setPosition(new Point(121, 0));
        NativeBasicResult tooFar = run(); assertEquals(FAILED, tooFar.outcome());
        assertEquals("attack_target_out_of_range", tooFar.fixedReason());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, tooFar.dispatch());
        assertThrows(NativeActionService.Invalid.class, () -> new NativeAttackAction(0));
    }

    @Test void roleBuffLocksAreNeverAcquiredWhileHoldingTheNativeInventoryLock() {
        AtomicInteger checks = new AtomicInteger();
        when(actor.getBuffedValue(BuffStat.ACC)).thenAnswer(call -> {
            CompletableFuture.runAsync(() -> { equipped.lockInventory(); try { checks.incrementAndGet(); }
                finally { equipped.unlockInventory(); } }).get(2, TimeUnit.SECONDS);
            return null;
        });
        assertEquals(SUCCEEDED, run().outcome()); assertEquals(1, checks.get());
    }

    @Test void aNativeHandlerReturnWithoutDamageAttributionIsNotSuccess() {
        when(map.isOwnershipRestricted(actor)).thenReturn(true);
        NativeBasicResult result = run();
        assertEquals(FAILED, result.outcome()); assertEquals("native_attack_not_applied", result.fixedReason());
        assertEquals(false, result.facts().get("effect_observed")); assertEquals(1000, target.getHp());
    }

    private NativeBasicActions.CommandHandle prepare(ExecutionScope.WorkHandle work) {
        return NativeAttackActions.prepareAttack(server, lane, permit, work, scene, new NativeAttackAction(501));
    }
    private NativeBasicResult run() {
        var handle = prepare(scope.acceptWork()); NativeBasicActions.submit(handle); executor.runNext();
        return handle.result().toCompletableFuture().join();
    }
    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>(); boolean stopped;
        public int commandQueueCapacity() { return 8; }
        public void execute(Runnable command) { if (stopped) throw new RejectedExecutionException(); queue.add(command); }
        void runNext() { queue.remove().run(); }
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; return List.copyOf(queue); }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped && queue.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
