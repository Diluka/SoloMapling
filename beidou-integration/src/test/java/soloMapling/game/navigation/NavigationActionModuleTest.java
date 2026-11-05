package soloMapling.game.navigation;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Disease;
import org.gms.client.Job;
import org.gms.client.BuffStat;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;
import soloMapling.game.scene.SceneRuntimeManager;
import soloMapling.game.scene.SceneActorBinding;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real terrain planning, physics and native MovePlayerHandler through the exported module entry. */
class NavigationActionModuleTest {
    private final List<Fixture> fixtures = new ArrayList<>();

    private Map<Integer, Map<String, Integer>> equipmentStats;
    private final Map<Integer, Map<String, Integer>> savedEquipmentStats = new HashMap<>();

    @BeforeEach @SuppressWarnings("unchecked") void startWorld() throws Exception {
        InventoryMetadataTestContext.install();
        WorldEpoch.onWorldStarted();
        var field = ItemInformationProvider.class.getDeclaredField("equipStatsCache");
        field.setAccessible(true);
        equipmentStats = (Map<Integer, Map<String, Integer>>) field.get(ItemInformationProvider.getInstance());
        for (int item : List.of(1072001, 1072239)) {
            savedEquipmentStats.put(item, equipmentStats.get(item));
            equipmentStats.put(item, item == 1072239 ? Map.of("fs", 10) : Map.of());
        }
    }
    @AfterEach void closeWorld() throws Exception {
        for (var fixture : fixtures) fixture.close();
        ThreadLocalUtil.removeCurrentClient();
        ExecutionScope.tryBeginWorldStop();
        savedEquipmentStats.forEach((item, stats) -> {
            if (stats == null) equipmentStats.remove(item); else equipmentStats.put(item, stats);
        });
    }

    @Test void nativeWalkingJumpingDroppingAndClimbingArriveThroughTheSharedModuleEntry() {
        arrive(98_982_101, new Point(0, 0), new Point(120, 0),
                List.of(foothold(-200, 0, 300, 0, 1)), List.of(), "WALK");
        arrive(98_982_102, new Point(-50, 0), new Point(150, -40),
                List.of(foothold(-200, 0, 0, 0, 1), foothold(20, -40, 230, -40, 2)), List.of(), "JUMP");
        arrive(98_982_103, new Point(0, -80), new Point(0, 0),
                List.of(foothold(-150, -80, 150, -80, 1), foothold(-250, 0, 250, 0, 2)), List.of(), "DROP");
        arrive(98_982_104, new Point(0, 0), new Point(0, -180),
                List.of(foothold(-200, -180, 200, -180, 1), foothold(-200, 0, 200, 0, 2)),
                List.of(new Rope(0, -180, 0, true)), "CLIMB");
    }

    private void arrive(int mapId, Point from, Point target, List<Foothold> footholds, List<Rope> ropes, String edge) {
        var fixture = fixture(mapId, from, footholds, ropes);
        var result = fixture.moveTo(target);
        assertEquals("ARRIVED", result.get("outcome"), result.toString());
        assertEquals(true, result.get("stable"));
        assertTrue(fixture.position.get().distance(target) <= 8);
        assertTrue(((List<?>) result.get("edge_types")).contains(edge), result.toString());
        assertEquals(0, fixture.module.context().scope().resources().snapshot().outstanding());
        assertEquals(0, fixture.module.context().scope().snapshot().acceptedWork());
        verify(fixture.map, atLeastOnce()).movePlayer(eq(fixture.character), any());
        assertEquals(4, fixture.stance.get() & ~1);
    }

    @Test void iceAndBothNativeShoeEffectsShareTheOrdinaryGroundProfileWhenProtected() {
        var ordinary = fixture(98_982_121, new Point(0, 0), List.of(foothold(-300, 0, 400, 0, 1)), List.of());
        var ice = fixture(98_982_122, new Point(0, 0), List.of(foothold(-300, 0, 400, 0, 1)), List.of(),
                fixture -> when(fixture.map.getFootholdSpeed()).thenReturn(0.2f));
        var natural = fixture(98_982_123, new Point(0, 0), List.of(foothold(-300, 0, 400, 0, 1)), List.of(), fixture -> {
            when(fixture.map.getFootholdSpeed()).thenReturn(0.2f);
            fixture.shoe.set(shoe(1072239, 0));
        });
        var scrolled = fixture(98_982_124, new Point(0, 0), List.of(foothold(-300, 0, 400, 0, 1)), List.of(), fixture -> {
            when(fixture.map.getFootholdSpeed()).thenReturn(0.2f);
            fixture.shoe.set(shoe(1072001, ItemConstants.SPIKES));
        });
        for (var fixture : List.of(ordinary, ice, natural, scrolled)) {
            var result = fixture.moveTo(new Point(120, 0));
            assertEquals("ARRIVED", result.get("outcome"), result.toString());
            assertEquals(true, result.get("stable"));
            assertTrue(fixture.position.get().distance(new Point(120, 0)) <= 8);
            assertEquals(0, fixture.module.context().scope().snapshot().acceptedWork());
            assertEquals(0, fixture.module.context().scope().resources().snapshot().outstanding());
        }
        assertEquals(ordinary.movements.subList(0, 4), natural.movements.subList(0, 4));
        assertEquals(ordinary.movements.subList(0, 4), scrolled.movements.subList(0, 4));
        assertTrue(ice.movements.get(3).x < ordinary.movements.get(3).x,
                "ordinary shoes accelerate more slowly on ice at the same speed/jump stats");
    }

    @Test void effectiveShoeChangeStopsTheIcePlanWhileOrdinaryGroundKeepsTheSamePlan() {
        var changedAt = new AtomicReference<Point>();
        var ice = fixture(98_982_125, new Point(0, 0), List.of(foothold(-300, 0, 450, 0, 1)), List.of(), fixture -> {
            when(fixture.map.getFootholdSpeed()).thenReturn(0.2f);
            doAnswer(call -> {
                if (fixture.movements.size() == 8) {
                    changedAt.set(new Point(fixture.position.get()));
                    fixture.shoe.set(shoe(1072001, ItemConstants.SPIKES));
                }
                return null;
            }).when(fixture.map).movePlayer(eq(fixture.character), any());
        });
        var changed = ice.moveTo(new Point(300, 0));
        assertEquals("BLOCKED", changed.get("outcome"), changed.toString());
        assertEquals("attributes_changed", changed.get("reason"));
        assertEquals(true, changed.get("stable"));
        assertNotNull(changedAt.get());
        assertTrue(ice.position.get().x > changedAt.get().x, "cleanup integrates retained momentum with the new shoe rule");
        assertEquals(0, ice.module.context().scope().resources().snapshot().outstanding());
        assertEquals("ARRIVED", ice.moveTo(new Point(0, 0)).get("outcome"));

        var ordinary = fixture(98_982_126, new Point(0, 0), List.of(foothold(-300, 0, 450, 0, 1)), List.of(), fixture -> {
            doAnswer(call -> {
                if (fixture.movements.size() == 8) fixture.shoe.set(shoe(1072001, ItemConstants.SPIKES));
                return null;
            }).when(fixture.map).movePlayer(eq(fixture.character), any());
        });
        assertEquals("ARRIVED", ordinary.moveTo(new Point(160, 0)).get("outcome"),
                "a pure anti-slip change on ordinary ground does not change the effective profile");
    }

    @Test void iceGroundTransitionsRetainJumpDropAndRopeExecution() {
        for (int shape = 0; shape < 3; shape++) {
            var from = shape == 0 ? new Point(-50, 0) : shape == 1 ? new Point(0, -80) : new Point(0, 0);
            var target = shape == 0 ? new Point(150, -40) : shape == 1 ? new Point(0, 0) : new Point(0, -180);
            var footholds = shape == 0
                    ? List.of(foothold(-200, 0, 0, 0, 1), foothold(20, -40, 230, -40, 2))
                    : shape == 1 ? List.of(foothold(-150, -80, 150, -80, 1), foothold(-250, 0, 250, 0, 2))
                    : List.of(foothold(-200, -180, 200, -180, 1), foothold(-200, 0, 200, 0, 2));
            var fixture = fixture(98_982_127 + shape, from, footholds,
                    shape == 2 ? List.of(new Rope(0, -180, 0, true)) : List.of(),
                    configured -> when(configured.map.getFootholdSpeed()).thenReturn(0.2f));
            var result = fixture.moveTo(target);
            assertEquals("ARRIVED", result.get("outcome"), result.toString());
            assertEquals(true, result.get("stable"));
            assertTrue(fixture.position.get().distance(target) <= 8);
            assertTrue(((List<?>) result.get("edge_types")).contains(List.of("JUMP", "DROP", "CLIMB").get(shape)));
        }
    }

    private static Item shoe(int id, int flag) {
        var shoe = mock(Item.class);
        when(shoe.getItemId()).thenReturn(id);
        when(shoe.getFlag()).thenReturn((short) flag);
        return shoe;
    }

    @Test void acknowledgedNativeEntryFallsWhileIdleThenAcceptsTheRoute() throws Exception {
        var fixture = fixture(98_982_134, new Point(0, -39),
                List.of(foothold(-200, 0, 300, 0, 1)), List.of());
        var binding = SceneRuntimeManager.current(fixture.server, fixture.module.context().characterRef());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (binding.backgroundSnapshot().stream().noneMatch(task -> task.get("name").equals("scene-entry-fall-41")
                && task.get("settled").equals(true)) && System.nanoTime() < until) Thread.sleep(5);

        assertEquals(new Point(0, 0), fixture.position.get(), "entry falls without a navigation call");
        var falling = fixture.movements.stream().takeWhile(position -> position.y < 0).toList();
        assertTrue(falling.size() >= 2, "the observed native spawn falls through physical frames");
        assertTrue(falling.stream().allMatch(position -> position.x == 0 && position.y > -39));
        assertTrue(binding.backgroundSnapshot().stream().anyMatch(task -> task.get("name").equals("scene-entry-fall-41")
                && task.get("settled").equals(true)), "the entry's original work and carrier have actually settled");

        var result = fixture.moveTo(new Point(120, 0));
        assertEquals("ARRIVED", result.get("outcome"), result.toString());
        assertTrue(((List<?>) result.get("edge_types")).contains("WALK"));
        assertEquals(0, fixture.position.get().y);
        assertEquals(0, fixture.module.context().scope().resources().snapshot().outstanding());
    }

    @Test void pausingNavigationWaitingForEntryFallLeavesTheSceneOwnedFallRunning() throws Exception {
        var enteredLanding = new CountDownLatch(1);
        var releaseLanding = new CountDownLatch(1);
        var enteredCleanupFrame = new CountDownLatch(1);
        var releaseCleanupFrame = new CountDownLatch(1);
        var enteredScene = new AtomicReference<SceneActorBinding>();
        var fixture = fixture(98_982_135, new Point(0, -120),
                List.of(foothold(-200, 0, 300, 0, 1)), List.of(), configured -> {
                    doAnswer(call -> {
                        enteredLanding.countDown();
                        assertTrue(releaseLanding.await(5, TimeUnit.SECONDS));
                        var binding = enteredScene.get();
                        if (binding != null && binding.context().scope().snapshot().diagnostics().stream()
                                .anyMatch(work -> work.kind() == ExecutionScope.WorkKind.CLEANUP
                                        && work.bodyState() == ExecutionScope.BodyState.RUNNING)) {
                            enteredCleanupFrame.countDown();
                            assertTrue(releaseCleanupFrame.await(5, TimeUnit.SECONDS));
                        }
                        return null;
                    }).when(configured.map).movePlayer(eq(configured.character), any());
                });
        var context = fixture.module.context();
        var binding = SceneRuntimeManager.current(fixture.server, context.characterRef());
        enteredScene.set(binding);
        var result = CompletableFuture.supplyAsync(() -> fixture.moveTo(new Point(120, 0)));
        try {
            assertTrue(enteredLanding.await(3, TimeUnit.SECONDS));
            long admittedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (context.scope().snapshot().acceptedWork() == 0
                    && System.nanoTime() < admittedUntil) Thread.sleep(5);
            assertTrue(context.scope().snapshot().acceptedWork() > 0);
            var stopped = fixture.runtime.stop(context.reference(), CharacterRuntime.StopMode.PAUSE);

            assertEquals(ExecutionScope.DrainResult.DRAINED, stopped.await(Duration.ofSeconds(5)));
            var facts = result.get(5, TimeUnit.SECONDS);
            assertEquals("CANCELLED", facts.get("outcome"), facts.toString());
            assertTrue(binding.backgroundSnapshot().stream().anyMatch(task -> task.get("name").equals("scene-entry-fall-41")
                    && task.get("settled").equals(false) && task.get("cancel_requested").equals(false)));
        } finally { releaseLanding.countDown(); }
        try {
            assertTrue(enteredCleanupFrame.await(3, TimeUnit.SECONDS));
            var worldStop = ExecutionScope.tryBeginWorldStop().orElseThrow();
            binding.context().scope().joinWorldStop(worldStop);
            assertTrue(binding.active(), "world-stop cancellation can precede the native leave hook");
            fixture.runtime.beginClose(Optional.of(worldStop));
            assertFalse(fixture.runtime.whenModulesDrained().toCompletableFuture().isDone());
            assertFalse(binding.scene().closed(), "the entry's native cleanup frame still owns the single cleanup slot");
        } finally { releaseCleanupFrame.countDown(); }
        fixture.runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(new Point(0, 0), fixture.position.get());
        assertTrue(binding.scene().closed());
        assertEquals(0, binding.context().scope().resources().snapshot().outstanding());
        assertEquals(0, context.scope().resources().snapshot().outstanding());
        assertEquals(0, context.scope().snapshot().acceptedWork());
    }

    @Test void javaActionEntryReturnsTypedResultAfterNativeMovementAndWorkSettlement() throws Exception {
        var fixture = fixture(98_982_106, new Point(0, 0),
                List.of(foothold(-200, 0, 300, 0, 1)), List.of());
        var call = fixture.caller.call(NavigationActionModule.MOVE_TO, new MoveToRequest(120, 0));
        assertEquals(ModuleCall.Status.SUCCEEDED, call.status());
        var context = fixture.module.context();
        var facts = call.result();
        assertEquals(NavigationResult.Outcome.ARRIVED, facts.outcome());
        assertTrue(facts.progress().stable());
        assertEquals(fixture.position.get().x, facts.progress().position().x());
        assertTrue(fixture.position.get().distance(new Point(120, 0)) <= 8);
        assertEquals(0, context.scope().snapshot().acceptedWork());
        assertEquals(0, context.scope().resources().snapshot().outstanding());
        verify(fixture.map, atLeastOnce()).movePlayer(eq(fixture.character), any());
    }

    @Test void unreachableTargetReturnsNoPathWithoutMovingTheNativeRole() {
        var fixture = fixture(98_982_101, new Point(0, 0), List.of(foothold(-200, 0, 300, 0, 1)), List.of());
        var result = fixture.moveTo(new Point(0, -200));
        assertEquals("NO_PATH", result.get("outcome"));
        assertEquals(new Point(0, 0), fixture.position.get());
        verify(fixture.map, never()).movePlayer(any(), any());
    }

    @Test void callerBudgetStopsSharedNavigationAndSettlesBeforeReturning() {
        var fixture = fixture(98_982_107, new Point(0, 0),
                List.of(foothold(-200, 0, 600, 0, 1)), List.of());
        assertEquals("ARRIVED", fixture.moveTo(new Point(0, 0)).get("outcome"));
        var result = (Map<?, ?>) fixture.caller.call("navigation.moveTo",
                Map.of("x", 500, "y", 0, "timeout_ms", 200)).get("result");
        assertEquals("TIMED_OUT", result.get("outcome"), result.toString());
        assertEquals("navigation_timeout", result.get("reason"));
        assertTrue(fixture.position.get().x < 500);
        assertEquals(true, result.get("stable"));
        assertEquals(4, fixture.stance.get() & ~1);
        assertTrue(((Number) result.get("elapsed_ms")).longValue() < 1500, result.toString());
        assertEquals(0, fixture.module.context().scope().snapshot().acceptedWork());
        assertEquals(0, fixture.module.context().scope().resources().snapshot().outstanding());
    }

    @Test void configuredArrivalToleranceMustCoverTheGroundStoppingDistance() {
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> NavigationActionModule.parseConfig(Map.of("arrival_tolerance_px", 3)));
        assertEquals("invalid_navigation_config:arrival_tolerance_px", rejected.getMessage());
        assertDoesNotThrow(() -> NavigationActionModule.parseConfig(Map.of("arrival_tolerance_px", 4)));
    }

    @Test void nativeMapMovementRulesAndMidRouteStunStopFurtherNavigationInput() {
        var stunned = new AtomicBoolean();
        var calls = new AtomicInteger();
        var maximumStep = new AtomicInteger();
        var previous = new AtomicInteger();
        var stoppedAt = new AtomicReference<Point>();
        var fixture = fixture(98_982_105, new Point(0, 0),
                List.of(foothold(-200, 0, 400, 0, 1)), List.of(), configured -> {
            when(configured.character.getTotalMoveSpeedStat()).thenReturn(200);
            when(configured.character.getTotalJumpStat()).thenReturn(123);
            when(configured.map.getFieldLimit()).thenReturn((int) FieldLimit.MOVEMENTSKILLS.getValue());
            when(configured.character.hasDisease(Disease.STUN)).thenAnswer(call -> stunned.get());
            doAnswer(call -> {
                Point actual = configured.position.get();
                maximumStep.accumulateAndGet(actual.x - previous.getAndSet(actual.x), Math::max);
                if (calls.incrementAndGet() == 10) {
                    stoppedAt.set(new Point(actual));
                    stunned.set(true);
                }
                return null;
            }).when(configured.map).movePlayer(eq(configured.character), any());
        });
        var result = fixture.moveTo(new Point(300, 0));
        assertEquals("BLOCKED", result.get("outcome"));
        assertEquals("movement_actor_immobilized", result.get("reason"));
        assertTrue(maximumStep.get() <= 7, "the map forces native base movement despite speed equipment/buffs");
        assertNotNull(stoppedAt.get());
        assertTrue(stoppedAt.get().x > 0);
        assertTrue(fixture.position.get().x >= stoppedAt.get().x);
        assertTrue(fixture.position.get().x - stoppedAt.get().x <= 8,
                "stopping retains and settles the previous physical momentum");
        assertEquals(true, result.get("stable"));
        assertEquals(0, fixture.module.context().scope().resources().snapshot().outstanding());
    }

    @Test void pausingNavigationWhileAirborneSettlesItsMotionBeforeTheModuleSuspends() throws Exception {
        var airborne = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = fixture(98_982_102, new Point(-50, 0),
                List.of(foothold(-200, 0, 0, 0, 1), foothold(20, -40, 230, -40, 2)), List.of(), configured -> {
                    doAnswer(call -> {
                        if (configured.position.get().y < -5 && airborne.getCount() != 0) {
                            airborne.countDown();
                            assertTrue(release.await(NavigationActionModule.DEFAULT_CONFIG.timeoutMs(), TimeUnit.MILLISECONDS));
                        }
                        return null;
                    }).when(configured.map).movePlayer(eq(configured.character), any());
                });
        var result = CompletableFuture.supplyAsync(() -> fixture.moveTo(new Point(150, -40)));
        CharacterRuntime.ModuleStop stopped;
        try {
            assertTrue(airborne.await(NavigationActionModule.DEFAULT_CONFIG.timeoutMs(), TimeUnit.MILLISECONDS));
            assertTrue(fixture.position.get().y < -5);
            stopped = fixture.runtime.stop(fixture.module.context().reference(), CharacterRuntime.StopMode.PAUSE);
            assertFalse(stopped.complete(), "the admitted native body must exit before pause completes");
        } finally { release.countDown(); }
        assertEquals(ExecutionScope.DrainResult.DRAINED, stopped.await(Duration.ofSeconds(5)));
        var facts = result.get(5, TimeUnit.SECONDS);
        assertEquals("CANCELLED", facts.get("outcome"));
        assertEquals(true, facts.get("stable"));
        assertTrue(fixture.position.get().x < 150);
        assertEquals(4, fixture.stance.get() & ~1);
        assertEquals(0, fixture.module.context().scope().resources().snapshot().outstanding());
        assertEquals(CharacterRuntime.ModuleState.SUSPENDED, fixture.runtime.snapshot().modules().stream()
                .filter(row -> row.instanceId().equals("pathfinder")).findFirst().orElseThrow().state());
    }

    @Test void deathDuringNavigationStopsMotionWithoutReplacingTheNativeLifePose() {
        var diedAt = new AtomicReference<Point>();
        var fixture = fixture(98_982_132, new Point(0, 0),
                List.of(foothold(-200, 0, 400, 0, 1)), List.of(), configured -> {
            when(configured.character.getHp()).thenAnswer(call -> {
                if (configured.movements.size() >= 6 && diedAt.get() == null) {
                    diedAt.set(new Point(configured.position.get()));
                    configured.hp.set(0);
                    configured.stance.set(18);
                }
                return configured.hp.get();
            });
        });

        var result = fixture.moveTo(new Point(300, 0));

        assertEquals("BLOCKED", result.get("outcome"), result.toString());
        assertEquals("movement_actor_dead", result.get("reason"));
        assertNotNull(diedAt.get());
        assertEquals(diedAt.get(), fixture.position.get());
        assertEquals(18, fixture.stance.get());
        assertEquals(0, fixture.module.context().scope().resources().snapshot().outstanding());
    }

    @Test void leavingTheSceneWaitsForTheOriginalNavigationBodyAndWorkToSettle() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = fixture(98_982_133, new Point(0, -39),
                List.of(foothold(-200, 0, 400, 0, 1)), List.of(), configured -> {
            doAnswer(call -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            }).when(configured.map).movePlayer(eq(configured.character), any());
        });
        var binding = SceneRuntimeManager.current(fixture.server, fixture.module.context().characterRef());
        var navigation = CompletableFuture.supplyAsync(() -> fixture.moveTo(new Point(300, 0)));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.runtime.beginClose(Optional.empty());
            assertFalse(binding.scene().closed());
            assertEquals("CLOSING", binding.scene().snapshot().get("state"));
            assertFalse(fixture.runtime.whenModulesDrained().toCompletableFuture().isDone());
        } finally {
            release.countDown();
        }
        navigation.get(5, TimeUnit.SECONDS);
        fixture.runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(binding.scene().closed());
        assertEquals(1, fixture.movements.size(), "the departed entry cannot send more fall or route frames");
    }

    private Fixture fixture(int mapId, Point from, List<Foothold> footholds, List<Rope> ropes) {
        return fixture(mapId, from, footholds, ropes, configured -> { });
    }

    private Fixture fixture(int mapId, Point from, List<Foothold> footholds, List<Rope> ropes,
                            Consumer<Fixture> configure) {
        var fixture = new Fixture(mapId, from, footholds, ropes, configure);
        fixtures.add(fixture);
        return fixture;
    }

    private static Foothold foothold(int x1, int y1, int x2, int y2, int id) {
        return new Foothold(new Point(x1, y1), new Point(x2, y2), id);
    }

    private static final class Fixture {
        final Server server = mock(Server.class);
        final Character character = mock(Character.class);
        final Client client = mock(Client.class);
        final MapleMap map = mock(MapleMap.class);
        final AtomicReference<Point> position;
        final AtomicInteger stance = new AtomicInteger(4);
        final AtomicInteger hp = new AtomicInteger(50);
        final AtomicReference<Item> shoe = new AtomicReference<>();
        final List<Point> movements = new ArrayList<>();
        final CharacterRuntime runtime;
        final NavigationActionModule module;
        final CharacterRuntime.ModuleContext caller;

        Fixture(int mapId, Point from, List<Foothold> footholds, List<Rope> ropes, Consumer<Fixture> configure) {
            position = new AtomicReference<>(new Point(from));
            var world = mock(World.class);
            var channel = mock(Channel.class);
            when(server.isOnline()).thenReturn(true);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannel(1)).thenReturn(channel);
            when(channel.getId()).thenReturn(1);
            var worldPlayers = mock(PlayerStorage.class);
            var channelPlayers = mock(PlayerStorage.class);
            when(world.getPlayerStorage()).thenReturn(worldPlayers);
            when(channel.getPlayerStorage()).thenReturn(channelPlayers);
            when(worldPlayers.getCharacterById(41)).thenReturn(character);
            when(channelPlayers.getCharacterById(41)).thenReturn(character);
            when(character.getId()).thenReturn(41);
            when(character.getClient()).thenReturn(client);
            when(client.getPlayer()).thenReturn(character);
            when(client.getChannel()).thenReturn(1);
            when(client.isLoggedIn()).thenReturn(true);
            when(character.getHp()).thenAnswer(call -> hp.get());
            when(character.isAlive()).thenAnswer(call -> hp.get() > 0);
            var job = Job.BEGINNER;
            when(character.getJob()).thenReturn(job);
            when(character.getChair()).thenReturn(-1);
            when(character.getTotalMoveSpeedStat()).thenReturn(100);
            when(character.getTotalJumpStat()).thenReturn(100);
            when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null);
            var equipped = mock(Inventory.class);
            when(equipped.getItem((short) -7)).thenAnswer(call -> shoe.get());
            when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
            when(character.getMap()).thenReturn(map);
            when(map.getCharacterById(41)).thenReturn(character);
            when(map.getId()).thenReturn(mapId);
            when(map.getFootholdSpeed()).thenReturn(1.0f);
            when(map.getMapArea()).thenReturn(new Rectangle(-500, -500, 1000, 1000));
            when(map.getRopes()).thenReturn(ropes);
            var tree = new FootholdTree(new Point(-500, -500), new Point(500, 500));
            footholds.forEach(tree::insert);
            when(map.getFootholds()).thenReturn(tree);
            when(character.getPosition()).thenAnswer(call -> new Point(position.get()));
            when(character.getStance()).thenAnswer(call -> stance.get());
            doAnswer(call -> { position.set(new Point(call.getArgument(0))); movements.add(new Point(position.get())); return null; }).when(character).setPosition(any());
            doAnswer(call -> { stance.set(call.getArgument(0)); return null; }).when(character).setStance(anyInt());
            configure.accept(this);
            var lane = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), lane);
            var actor = new GameContext.CharacterRef(runtime.acceptedEpoch(), character, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "navigation-test"; }
                @Override public Object identity() { return character; }
                @Override public GameContext.CharacterRef characterRef() { return actor; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", 41); }
                @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                    permit.requireExecuting(character);
                    return new ReleaseProof(true);
                }
            };
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(binding);
                runtime.bindAdoptedOrigin();
                runtime.initializeSceneMembership(server);
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            var nav = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), config -> new NavigationActionModule(config, server),
                    NavigationActionModule.DEFINITION.exports());
            module = runtime.mount(nav, new ModuleSelection<>("pathfinder", NavigationActionModule.DEFAULT_CONFIG, true)).instance();
            var controller = new ModuleDefinition<Void, RuntimeModule>("test-controller", Set.of(),
                    new ExecutionScope.Limits(8, 2, 4, 2), config -> new RuntimeModule() {}, List.of());
            caller = runtime.context(runtime.mount(controller, new ModuleSelection<>("controller", null, true)).reference());
        }

        Map<?, ?> moveTo(Point target) {
            return (Map<?, ?>) caller.call("navigation.moveTo", Map.of("x", target.x, "y", target.y)).get("result");
        }

        void close() throws Exception {
            runtime.beginClose(Optional.empty());
            runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
        }
    }
}
