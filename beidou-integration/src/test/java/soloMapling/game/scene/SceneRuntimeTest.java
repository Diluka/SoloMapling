package soloMapling.game.scene;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.BuffStat;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.life.Monster;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.action.NativeMapEntryActions;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RoleBinding;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLWZFile;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Scene ownership through actual role adoption and close; native map/storage remain explicit boundaries. */
class SceneRuntimeTest {
    @BeforeAll static void nativeHostContext() { CharacterTestContext.install(); Job.values(); }

    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final Channel channel = mock(Channel.class);
    private final Map<Integer, Character> players = new HashMap<>();
    private final List<CharacterRuntime> runtimes = new ArrayList<>();

    @BeforeEach void startWorld() {
        WorldEpoch.onWorldStarted();
        when(server.isOnline()).thenReturn(true);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        var storage = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(anyInt())).thenAnswer(call -> players.get(call.<Integer>getArgument(0)));
    }

    @AfterEach void closeWorld() throws Exception {
        for (CharacterRuntime runtime : runtimes) close(runtime);
        ExecutionScope.tryBeginWorldStop();
        assertTrue(SceneRuntimeManager.snapshots().isEmpty());
    }

    @Test void consecutiveIntentPreemptionWaitsForTheOriginalCarrierAndItsLateNativeActionLock() throws Exception {
        var actor = open(47, nativeMap());
        var binding = SceneRuntimeManager.current(server, actor);
        var context = binding.context();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var normal = context.acceptWork();
        var first = binding.dispatch().request(SceneActionDispatch.Priority.NORMAL, context, normal, null, "normal_contract").arrangement();
        context.submit(SceneMembershipModule.CONTROL, normal, first::requireCurrent,
                () -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                }, () -> {});
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var equipment = context.acceptWork();
            var second = binding.dispatch().request(SceneActionDispatch.Priority.EQUIPMENT_PICKUP, context, equipment,
                    null, "equipment_contract").arrangement();
            var escape = context.acceptWork();
            var third = binding.dispatch().request(SceneActionDispatch.Priority.LETHAL_ESCAPE, context, escape,
                    null, "escape_contract").arrangement();
            assertFalse(first.valid()); assertFalse(second.valid()); assertFalse(third.current());
            assertFalse(context.scope().runTracked(equipment, () -> fail("the replaced activity must not start")));
            try (var data = mockStatic(DataProviderFactory.class, CALLS_REAL_METHODS)) {
                data.when(() -> DataProviderFactory.getDataProvider(WZFiles.CHARACTER)).thenReturn(
                        new XMLWZFile(Path.of("upstream/BeiDou-Server/gms-server/wz/Character.wz")));
                assertEquals(699, SceneActionTiming.bodyDurationMs(0, 5, 4));
                SceneRuntimeManager.attackAction(actor.character(), 0, 5, 4);
            }
            release.countDown();
            normal.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue(normal.isSettled()); assertTrue(equipment.isSettled());
            assertTrue(binding.dispatch().actionLockRemainingMs() > 0);
            assertFalse(third.current(), "a lock recorded after the request still blocks its successor");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!third.current() && System.nanoTime() < until) Thread.sleep(5);
            assertTrue(third.current());
            assertTrue(context.scope().runTracked(escape, third::requireCurrent));
            binding.dispatch().release(third, "escape_contract_complete");
        } finally { release.countDown(); }
    }


    @Test void actualMapIdentitySharesMembersAndKeepsDeathLocalUntilTheLastRoleLeaves() throws Exception {
        var map = nativeMap();
        var first = open(41, map);
        var second = open(42, map);
        var sameIdInstance = open(43, nativeMap());
        var scene = SceneRuntimeManager.current(server, first).scene();
        assertSame(scene, SceneRuntimeManager.current(server, second).scene());
        assertNotSame(scene, SceneRuntimeManager.current(server, sameIdInstance).scene());
        assertEquals(2, scene.members().size());
        assertSame(map, scene.environment().map());

        when(first.character().isAlive()).thenReturn(false);
        SceneRuntimeManager.current(server, first).requireCurrent();
        assertEquals(2, scene.members().size());

        close(runtimes.get(0));
        assertFalse(scene.closed());
        assertEquals(1, scene.members().size());
        close(runtimes.get(1));
        assertTrue(scene.closed());
        assertTrue(scene.members().isEmpty());
        assertFalse(SceneRuntimeManager.current(server, sameIdInstance).scene().closed());
    }

    @Test void ordinaryHeadlessEntryAcknowledgesNativelyAndKeepsTheSameMonsterDriverAlive() throws Exception {
        var map = nativeMap();
        when(map.getFootholdSpeed()).thenReturn(1.0f);
        var geometry = new FootholdTree(new Point(-100, -100), new Point(100, 100));
        geometry.insert(new Foothold(new Point(-100, 0), new Point(100, 0), 1));
        when(map.getFootholds()).thenReturn(geometry);
        var monster = mock(Monster.class);
        when(monster.getId()).thenReturn(1210100);
        when(monster.getMap()).thenReturn(map);
        when(monster.getPosition()).thenReturn(new Point(0, 0));
        var changing = new AtomicBoolean(true);
        var enteredAck = new CountDownLatch(1);
        var releaseAck = new CountDownLatch(1);
        var actor = open(44, map, character -> {
            when(character.isChangingMaps()).thenAnswer(ignored -> changing.get());
            doAnswer(ignored -> {
                assertSame(character.getClient(), ThreadLocalUtil.getCurrentClient());
                enteredAck.countDown();
                assertTrue(releaseAck.await(5, TimeUnit.SECONDS));
                changing.set(false);
                return null;
            }).when(character).setMapTransitionComplete();
        }, ignored -> {});
        var binding = SceneRuntimeManager.current(server, actor);
        try {
            assertTrue(enteredAck.await(3, TimeUnit.SECONDS));
            assertFalse(MonsterSceneModule.canDrive(monster, actor.character()));
            var taskBefore = binding.backgroundSnapshot().stream()
                    .filter(task -> task.get("name").equals("scene-monsters-44")).findFirst().orElseThrow();
            releaseAck.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!MonsterSceneModule.canDrive(monster, actor.character()) && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(changing.get());
            assertTrue(MonsterSceneModule.canDrive(monster, actor.character()));
            var taskAfter = binding.backgroundSnapshot().stream()
                    .filter(task -> task.get("name").equals("scene-monsters-44")).findFirst().orElseThrow();
            assertEquals(taskBefore.get("work_id"), taskAfter.get("work_id"));
            assertEquals("RUNNING", taskAfter.get("body_state"));
            assertEquals(false, taskAfter.get("settled"));
        } finally { releaseAck.countDown(); }
        close(runtimes.getLast());
        verify(actor.character()).setMapTransitionComplete();
    }

    @Test void synchronousNativeEntryAcknowledgementIsNotRepeatedByTheQueuedSceneWork() throws Exception {
        var changing = new AtomicBoolean(true);
        var actor = open(45, nativeMap(), character -> {
            when(character.isChangingMaps()).thenAnswer(ignored -> changing.get());
            doAnswer(ignored -> { changing.set(false); return null; }).when(character).setMapTransitionComplete();
        }, character -> assertFalse(NativeMapEntryActions.acknowledge(server,
                NativeContextChecks.captureScene(server, character), () -> {})));
        var context = SceneRuntimeManager.current(server, actor).context();
        var afterEntry = context.acceptWork();
        context.submit(SceneMembershipModule.CONTROL, afterEntry, () -> {}, () -> {}, () -> {});
        afterEntry.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        close(runtimes.getLast());
        assertFalse(changing.get());
        verify(actor.character()).setMapTransitionComplete();
    }

    @Test void closingWaitsForTheAlreadyDispatchedNativeEntryWorkToActuallyExit() throws Exception {
        var changing = new AtomicBoolean(true);
        var enteredAck = new CountDownLatch(1);
        var releaseAck = new CountDownLatch(1);
        var actor = open(46, nativeMap(), character -> {
            when(character.isChangingMaps()).thenAnswer(ignored -> changing.get());
            doAnswer(ignored -> {
                enteredAck.countDown();
                assertTrue(releaseAck.await(5, TimeUnit.SECONDS));
                changing.set(false);
                return null;
            }).when(character).setMapTransitionComplete();
        }, ignored -> {});
        var runtime = runtimes.getLast();
        var scene = SceneRuntimeManager.current(server, actor).scene();
        try {
            assertTrue(enteredAck.await(3, TimeUnit.SECONDS));
            runtime.beginClose(Optional.empty());
            assertFalse(runtime.whenModulesDrained().toCompletableFuture().isDone());
            assertFalse(scene.closed());
            assertEquals("CLOSING", scene.snapshot().get("state"));
        } finally { releaseAck.countDown(); }
        close(runtime);
        assertTrue(scene.closed());
        verify(actor.character()).setMapTransitionComplete();
    }

    private MapleMap nativeMap() {
        var map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        when(map.getCharacterById(anyInt())).thenAnswer(call -> {
            Character character = players.get(call.<Integer>getArgument(0));
            return character != null && character.getMap() == map ? character : null;
        });
        return map;
    }

    private GameContext.CharacterRef open(int id, MapleMap map) throws Exception {
        return open(id, map, ignored -> {}, ignored -> {});
    }

    private GameContext.CharacterRef open(int id, MapleMap map, Consumer<Character> prepare,
                                          Consumer<GameContext.CharacterRef> afterBound) throws Exception {
        var character = mock(Character.class);
        var client = mock(Client.class);
        when(character.getId()).thenReturn(id);
        when(character.getClient()).thenReturn(client);
        when(character.getMap()).thenReturn(map);
        when(character.getPosition()).thenReturn(new Point(0, 0));
        when(character.getJob()).thenReturn(Job.BEGINNER);
        when(character.isAlive()).thenReturn(true);
        when(character.isHidden()).thenReturn(true);
        when(character.getHp()).thenReturn(100);
        when(character.getBuffSource(BuffStat.HOMING_BEACON)).thenReturn(-1);
        when(client.getPlayer()).thenReturn(character);
        when(client.getChannel()).thenReturn(1);
        when(client.isLoggedIn()).thenReturn(true);
        prepare.accept(character);
        players.put(id, character);
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        var runtime = new CharacterRuntime(WorldEpoch.snapshot().token(),
                new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 1, 8, 1)), executor);
        runtimes.add(runtime);
        var actor = new GameContext.CharacterRef(runtime.acceptedEpoch(), character, client, world, channel);
        RoleBinding origin = new RoleBinding() {
            @Override public String kind() { return "scene-runtime-test"; }
            @Override public Object identity() { return character; }
            @Override public GameContext.CharacterRef characterRef() { return actor; }
            @Override public void requireCurrent() { }
            @Override public Map<String, Object> observe() { return Map.of("character_id", id); }
            @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                permit.requireExecuting(character);
                players.remove(id);
                return new ReleaseProof(true);
            }
        };
        var opening = runtime.acceptOpeningWork();
        runtime.submitOpening(opening, () -> {
            runtime.reserveOrigin(opening).adopt(origin);
            runtime.bindAdoptedOrigin();
            runtime.initializeSceneMembership(server);
            afterBound.accept(actor);
        }, () -> {});
        opening.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(CharacterRuntime.State.BOUND, runtime.snapshot().state());
        return actor;
    }

    private void close(CharacterRuntime runtime) throws Exception {
        if (runtime.snapshot().state() == CharacterRuntime.State.CLOSED) return;
        runtime.beginClose(Optional.empty());
        runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var cleanup = runtime.acquireOriginCleanup();
        runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
        cleanup.work().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
    }
}
