package soloMapling.game.combat;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.BuffStat;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.packet.Packet;
import org.gms.net.opcodes.SendOpcode;
import org.gms.constants.skills.Rogue;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLWZFile;
import org.mockito.MockedStatic;
import soloMapling.game.tactics.CombatTacticsControlModule;
import soloMapling.game.navigation.NavigationActionModule;
import soloMapling.game.navigation.MoveToRequest;
import soloMapling.game.navigation.NavigationPlan;
import java.nio.file.Path;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CombatActionModuleTest {
    private MockedStatic<DataProviderFactory> timingMetadata;
    @BeforeEach void installNativeContext() throws Exception {
        CharacterTestContext.install(); InventoryMetadataTestContext.install(); Job.values();
        try (var providers = mockStatic(DataProviderFactory.class)) {
            providers.when(() -> DataProviderFactory.getDataProvider(any(WZFiles.class))).thenReturn(mock(DataProvider.class));
            Class.forName(org.gms.client.SkillFactory.class.getName(), true, org.gms.client.SkillFactory.class.getClassLoader());
        }
        timingMetadata = realTimingMetadata();
    }
    @AfterEach void releaseTimingMetadata() { timingMetadata.close(); }

    private static MockedStatic<DataProviderFactory> realTimingMetadata() {
        var factory = mockStatic(DataProviderFactory.class, CALLS_REAL_METHODS);
        for (var kind : List.of(WZFiles.CHARACTER, WZFiles.SKILL, WZFiles.MOB)) {
            DataProvider data = new XMLWZFile(Path.of("upstream/BeiDou-Server/gms-server/wz", kind.name().charAt(0)
                    + kind.name().substring(1).toLowerCase(java.util.Locale.ROOT) + ".wz"));
            factory.when(() -> DataProviderFactory.getDataProvider(kind)).thenReturn(data);
        }
        return factory;
    }
    @Test void sharedEntryHitsOnlyTheRequestedTargetAndWaitsForTheLegacyStrike() throws Exception {
        try (var fixture = new Fixture()) {
            var call = fixture.caller.call(CombatActionModule.ENGAGE,
                    new CombatRequest(91, Set.of(91), CombatRequest.Choice.AUTO, 30_000), null);
            var result = call.result();
            assertEquals(CombatResult.Outcome.TARGET_DEAD, result.outcome());
            assertEquals(Job.THIEF.getId(), result.initialJob());
            assertEquals(1, result.hits().size());
            assertEquals(91, result.hits().getFirst().objectId());
            assertEquals(0, result.hits().getFirst().hpAfter());
            int attacks = result.attacks().stream().mapToInt(CombatResult.AttackUse::count).sum();
            verify(fixture.map, times(attacks)).damageMonster(eq(fixture.actor), eq(fixture.target), anyInt());
            verify(fixture.map, times(1)).killMonster(fixture.target, fixture.actor, true);
            verify(fixture.map, never()).killMonster(eq(fixture.target), eq(fixture.actor), eq(false), anyInt());
            verify(fixture.map, never()).damageMonster(eq(fixture.actor), eq(fixture.outside), anyInt());
            verify(fixture.outside, never()).damage(any(), anyInt(), anyBoolean());
            fixture.assertCombatDrained();
        }
    }

    @Test void theLegacyStrikeKeepsItsExistingSettlementEntry() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.hp.set(1);
            var result = BotAttackDriver.strike(fixture.actor, List.of(fixture.target),
                    BotAttackProfile.basicSwing(), null, false, false);
            assertTrue(result.anyKilled());
            verify(fixture.target).damage(eq(fixture.actor), anyInt(), eq(false));
            verify(fixture.map, never()).damageMonster(any(), any(), anyInt());
            verify(fixture.map).killMonster(eq(fixture.target), eq(fixture.actor), eq(false), eq(300));
        }
    }

    @Test void missingTacticsReturnsUnavailableWithoutStartingAnotherPursuit() throws Exception {
        try (var fixture = new Fixture(false)) {
            when(fixture.target.getPosition()).thenReturn(new Point(300, 0));
            var result = (Map<?, ?>) fixture.caller.call("combat.engage", Map.of("target_oid", 91)).get("result");
            assertEquals("UNAVAILABLE", result.get("outcome"));
            assertEquals("tactics_unavailable", result.get("reason"));
            verify(fixture.target, never()).damage(any(), anyInt(), anyBoolean());
            fixture.assertCombatDrained();
        }
    }

    @Test void pursuitPassesTheOriginalEngagementBudgetThroughTheRealNavigationProvider() throws Exception {
        try (var fixture = new Fixture(Job.THIEF, 1332000,
                configured -> doReturn(new Point(650, 0)).when(configured.target).getPosition(), false)) {
            var requested = new CountDownLatch(1);
            var release = new CompletableFuture<Void>();
            var request = new AtomicReference<MoveToRequest>();
            var providerWork = new AtomicReference<ExecutionScope.WorkHandle>();
            var capturedAt = new AtomicLong();
            var navigationConfig = new NavigationActionModule.Config(8, 7000, 3000);
            var navigation = spy(new NavigationActionModule(navigationConfig, fixture.server));
            doAnswer(call -> {
                var input = call.<MoveToRequest>getArgument(0);
                if (input.x() > 500 && input.y() == 0) {
                    request.set(input); providerWork.set(call.getArgument(2)); capturedAt.set(System.nanoTime());
                    requested.countDown(); release.join();
                }
                return call.callRealMethod();
            }).when(navigation).plan(any(), any(), any());
            var definition = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), config -> navigation, NavigationActionModule.DEFINITION.exports());
            fixture.runtime.mount(definition, new ModuleSelection<>("navigation", navigationConfig, true));
            assertEquals(NavigationPlan.Outcome.PLANNED,
                    fixture.caller.call(NavigationActionModule.PLAN, new MoveToRequest(0, 0, 7000)).result().outcome());
            var tactics = fixture.mountTactics();
            long beforeEngagement = System.nanoTime();
            var combat = CompletableFuture.supplyAsync(() -> fixture.caller.call(CombatActionModule.ENGAGE,
                    new CombatRequest(91, Set.of(91), CombatRequest.Choice.AUTO, 10_000)).result());
            try {
                assertTrue(requested.await(5, TimeUnit.SECONDS));
                long minimumRemaining = 10_000 - (capturedAt.get() - beforeEngagement + 999_999) / 1_000_000;
                assertTrue(request.get().timeoutMs() >= minimumRemaining,
                        "the plan must receive the original engagement's remaining deadline");
                assertTrue(request.get().timeoutMs() <= 10_000);
                assertFalse(providerWork.get().isSettled());
                var stop = CompletableFuture.supplyAsync(tactics::stop);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!providerWork.get().cancelRequested() && System.nanoTime() < until) Thread.sleep(5);
                assertTrue(providerWork.get().cancelRequested());
                assertFalse(stop.isDone(), "the original planning call still has to leave its provider");
                release.complete(null);
                stop.get(5, TimeUnit.SECONDS);
                combat.get(5, TimeUnit.SECONDS);
                assertTrue(providerWork.get().isSettled());
                fixture.assertCombatDrained();
                assertEquals(0, fixture.runtime.context(fixture.tacticsRef).scope().snapshot().acceptedWork());
                assertEquals(new Point(0, 0), fixture.position.get());
                verify(fixture.map, never()).movePlayer(any(), any());
                verify(fixture.map, never()).damageMonster(any(), any(), anyInt());
            } finally { release.complete(null); }
        }
    }

    @Test void aReadOnlyLocalPlanRetainsTheNavigationConfigurationBudget() throws Exception {
        try (var fixture = new Fixture(false)) {
            var config = new NavigationActionModule.Config(8, 7000, 3000);
            var definition = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), value -> new NavigationActionModule(value, fixture.server),
                    NavigationActionModule.DEFINITION.exports());
            fixture.runtime.mount(definition, new ModuleSelection<>("navigation", config, true));
            var plan = fixture.caller.call(NavigationActionModule.PLAN, new MoveToRequest(0, 0, 10_000)).result();
            assertEquals(NavigationPlan.Outcome.PLANNED, plan.outcome());
            assertEquals(config, plan.executionConfig());
            assertEquals(7000, plan.timeoutMs());
            assertEquals(0, fixture.runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals("navigation"))
                    .findFirst().orElseThrow().scope().acceptedWork());
            assertEquals(new Point(0, 0), fixture.position.get());
            verify(fixture.map, never()).movePlayer(any(), any());
        }
    }

    @Test void theSameTacticalMonitorContinuesAfterItsOwnReadOnlyPlanTimesOut() throws Exception {
        var planningThread = new AtomicReference<Thread>();
        var delayNextGeometryRead = new AtomicBoolean(true);
        var firstPlanEntered = new CountDownLatch(1);
        var geometryReleased = new CompletableFuture<Void>();
        var nextObservationPlanned = new CountDownLatch(1);
        var nextPlanReleased = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var activeTactics = new AtomicReference<CombatTacticsControlModule>();
        var holdNextObservation = new AtomicBoolean(true);
        var firstOutcome = new AtomicReference<NavigationPlan.Outcome>();
        try (var fixture = new Fixture(Job.THIEF, 1332000, configured -> {
            var geometry = configured.map.getFootholds();
            doAnswer(call -> {
                if (Thread.currentThread() == planningThread.get() && delayNextGeometryRead.compareAndSet(true, false)) {
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1);
                    while (System.nanoTime() < deadline) Thread.onSpinWait();
                }
                return geometry;
            }).when(configured.map).getFootholds();
        }, false)) {
            var navigationConfig = new NavigationActionModule.Config(8, 1, 3000);
            var navigation = spy(new NavigationActionModule(navigationConfig, fixture.server));
            doAnswer(call -> {
                int sequence = calls.incrementAndGet();
                if (sequence == 1) { firstPlanEntered.countDown(); geometryReleased.join(); }
                if (activeTactics.get() != null && activeTactics.get().status().observations() > 0
                        && holdNextObservation.compareAndSet(true, false)) {
                    nextObservationPlanned.countDown(); nextPlanReleased.join();
                }
                planningThread.set(Thread.currentThread());
                try {
                    var plan = (NavigationPlan) call.callRealMethod();
                    if (sequence == 1) firstOutcome.set(plan.outcome());
                    return plan;
                } finally { planningThread.set(null); }
            }).when(navigation).plan(any(), any(), any());
            var definition = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                    NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                    NavigationActionModule.DEFINITION.limits(), config -> navigation, NavigationActionModule.DEFINITION.exports());
            fixture.runtime.mount(definition, new ModuleSelection<>("navigation", navigationConfig, true));
            var tactics = fixture.mountTactics();
            activeTactics.set(tactics);
            try {
                assertTrue(firstPlanEntered.await(5, TimeUnit.SECONDS));
                var generation = fixture.tacticsRef.generation();
                var scope = fixture.runtime.context(fixture.tacticsRef).scope();
                var original = scope.snapshot().diagnostics().stream()
                        .filter(work -> work.bodyState() == ExecutionScope.BodyState.RUNNING && work.carrierClaimed())
                        .findFirst().orElseThrow();
                assertEquals(CombatTacticsControlModule.State.RUNNING, tactics.status().state());
                geometryReleased.complete(null);
                assertTrue(nextObservationPlanned.await(5, TimeUnit.SECONDS));
                assertEquals(NavigationPlan.Outcome.TIMED_OUT, firstOutcome.get());
                assertEquals("navigation_plan_timeout", tactics.status().reason());
                assertEquals(CombatTacticsControlModule.State.RUNNING, tactics.status().state());
                long observations = tactics.status().observations();
                nextPlanReleased.complete(null);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (tactics.status().observations() <= observations && System.nanoTime() < until) Thread.sleep(5);
                assertTrue(tactics.status().observations() > observations);
                assertSame(scope, fixture.runtime.context(fixture.tacticsRef).scope());
                assertEquals(generation, fixture.tacticsRef.generation());
                assertTrue(scope.snapshot().diagnostics().stream().anyMatch(work -> work.id() == original.id()
                        && work.bodyState() == ExecutionScope.BodyState.RUNNING && !work.settled()));
                tactics.stop();
                assertEquals(0, scope.snapshot().acceptedWork());
                assertEquals(0, scope.snapshot().pendingCarriers());
                assertEquals(0, scope.resources().snapshot().outstanding());
                assertEquals(new Point(0, 0), fixture.position.get());
                fixture.assertCombatDrained();
            } finally { geometryReleased.complete(null); nextPlanReleased.complete(null); }
        } finally { geometryReleased.complete(null); nextPlanReleased.complete(null); }
    }

    @Test void normalTimeoutDrainsTheEngagementWithoutAnUncaughtLaneCancellation() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.hp.set(10_000);
            var result = fixture.caller.call(CombatActionModule.ENGAGE,
                    new CombatRequest(91, Set.of(91), CombatRequest.Choice.AUTO, 100), null).result();

            assertEquals(CombatResult.Outcome.TIMED_OUT, result.outcome());
            assertTrue(fixture.target.isAlive());
            fixture.assertCombatDrained();
            assertTrue(fixture.laneFailures.isEmpty(), fixture.laneFailures.toString());
        }
    }

    @Test void aNativeLinkageFailureReturnsToTheCallerAfterTheDamageWorkSettles() throws Exception {
        var failure = new NoSuchMethodError("native_drop_linkage_failure");
        try (var fixture = new Fixture(prepared ->
                doAnswer(invocation -> { prepared.exp.set(500); throw failure; }).when(prepared.map)
                        .killMonster(prepared.target, prepared.actor, true))) {
            assertSame(failure, assertThrows(NoSuchMethodError.class,
                    () -> fixture.caller.call("combat.engage", Map.of("target_oid", 91))));
            assertEquals(0, fixture.target.getHp());
            assertEquals(500, fixture.actor.getExp());
            fixture.assertCombatDrained();
        }
    }

    @Test void thiefWeaponBranchAndInheritedShadowPartnerReachTheRealAttackPacket() throws Exception {
        try (var claw = new Fixture(Job.THIEF, 1472000)) {
            var result = claw.caller.call(CombatActionModule.ENGAGE,
                    new CombatRequest(91, Set.of(91), CombatRequest.Choice.AUTO, 30_000), null).result();
            assertEquals(Rogue.LUCKY_SEVEN, result.attacks().getFirst().skillId());
            assertEquals("RANGED", result.attacks().getFirst().route());
            assertEquals(2, claw.attackPackets(SendOpcode.RANGED_ATTACK).getFirst()[6] & 0x0F);
        }
        try (var dagger = new Fixture(Job.THIEF, 1332000)) {
            var result = dagger.caller.call(CombatActionModule.ENGAGE,
                    new CombatRequest(91, Set.of(91), CombatRequest.Choice.AUTO, 30_000), null).result();
            assertEquals(Rogue.DOUBLE_STAB, result.attacks().getFirst().skillId());
            assertEquals("CLOSE", result.attacks().getFirst().route());
        }
        try (var hermit = new Fixture(Job.HERMIT, 1472000)) {
            var result = hermit.caller.call(CombatActionModule.ENGAGE,
                    new CombatRequest(91, Set.of(91), CombatRequest.Choice.SINGLE, 30_000), null).result();
            assertEquals(Rogue.LUCKY_SEVEN, result.attacks().getFirst().skillId());
            assertEquals(Job.THIEF.getId(), result.attacks().getFirst().providerJob());
            assertEquals(List.of(411, 410, 400), result.activePath());
            assertEquals(4, hermit.attackPackets(SendOpcode.RANGED_ATTACK).getFirst()[6] & 0x0F);
        }
    }

    @Test void higherMageWithoutLearnedAncestorSkillsUsesTheRealBasicGeometry() throws Exception {
        try (var fixture = new Fixture(Job.BISHOP, 1372000)) {
            assertTrue(CombatActionModule.inAttackRange(fixture.actor, fixture.target));
            when(fixture.target.getPosition()).thenReturn(new Point(300, 0));
            assertFalse(CombatActionModule.inAttackRange(fixture.actor, fixture.target));
            assertTrue(fixture.attackPackets(SendOpcode.MAGIC_ATTACK).isEmpty());
            fixture.assertCombatDrained();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Character actor = mock(Character.class);
        final Monster target = mock(Monster.class);
        final Monster outside = mock(Monster.class);
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        CharacterRuntime.ModuleRef tacticsRef;
        final Server server = mock(Server.class);
        final MapleMap map = mock(MapleMap.class);
        final AtomicReference<Point> position = new AtomicReference<>(new Point(0, 0));
        final AtomicInteger stance = new AtomicInteger(4);
        final AtomicInteger exp = new AtomicInteger();
        final AtomicInteger hp = new AtomicInteger(100);
        final List<Packet> packets = new CopyOnWriteArrayList<>();
        final List<Throwable> laneFailures = new CopyOnWriteArrayList<>();

        Fixture() { this(Job.THIEF, 1332000); }
        Fixture(boolean tactics) { this(Job.THIEF, 1332000, fixture -> {}, tactics); }
        Fixture(Consumer<Fixture> beforeOpen) { this(Job.THIEF, 1332000, beforeOpen); }
        Fixture(Job job, int weaponId) { this(job, weaponId, fixture -> {}); }

        Fixture(Job job, int weaponId, Consumer<Fixture> beforeOpen) {
            this(job, weaponId, beforeOpen, true);
        }

        Fixture(Job job, int weaponId, Consumer<Fixture> beforeOpen, boolean tactics) {
            WorldEpoch.onWorldStarted();
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var client = mock(Client.class);
            var storage = mock(PlayerStorage.class);
            when(server.isOnline()).thenReturn(true);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannel(1)).thenReturn(channel);
            when(channel.getId()).thenReturn(1);
            when(world.getPlayerStorage()).thenReturn(storage);
            when(channel.getPlayerStorage()).thenReturn(storage);
            when(storage.getCharacterById(41)).thenReturn(actor);
            when(actor.getId()).thenReturn(41);
            when(actor.getClient()).thenReturn(client);
            when(client.getPlayer()).thenReturn(actor);
            when(client.getChannel()).thenReturn(1);
            when(client.isLoggedIn()).thenReturn(true);
            when(actor.getMap()).thenReturn(map);
            when(map.getCharacterById(41)).thenReturn(actor);
            when(actor.getPosition()).thenAnswer(invocation -> new Point(position.get()));
            when(actor.getStance()).thenAnswer(invocation -> stance.get());
            doAnswer(invocation -> { position.set(new Point(invocation.getArgument(0))); return null; })
                    .when(actor).setPosition(any());
            doAnswer(invocation -> { stance.set(invocation.getArgument(0)); return null; }).when(actor).setStance(anyInt());
            when(actor.getHp()).thenReturn(50);
            when(actor.getCurrentMaxHp()).thenReturn(100);
            when(actor.isAlive()).thenReturn(true);
            when(actor.getChair()).thenReturn(-1);
            when(actor.getTotalMoveSpeedStat()).thenReturn(100);
            when(actor.getTotalJumpStat()).thenReturn(100);
            when(actor.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null);
            when(actor.getExp()).thenAnswer(invocation -> exp.get());
            when(map.getId()).thenReturn(98_982_719);
            when(map.getFootholdSpeed()).thenReturn(1.0f);
            when(map.getMapArea()).thenReturn(new Rectangle(-500, -500, 1500, 1000));
            when(map.getRopes()).thenReturn(List.of());
            var footholds = new FootholdTree(new Point(-500, -500), new Point(1000, 500));
            footholds.insert(new Foothold(new Point(-500, 0), new Point(1000, 0), 1));
            when(map.getFootholds()).thenReturn(footholds);
            when(actor.getJob()).thenReturn(job);
            when(actor.getLevel()).thenReturn(1);
            Inventory equipment = mock(Inventory.class);
            if (weaponId != 0) {
                var weapon = new Item(weaponId, (short) -11, (short) 1);
                when(equipment.getItem((short) -11)).thenReturn(weapon);
            }
            when(actor.getInventory(InventoryType.EQUIPPED)).thenReturn(equipment);
            doAnswer(invocation -> { packets.add(invocation.getArgument(1)); return null; }).when(map)
                    .broadcastMessage(eq(actor), any(Packet.class), eq(false));
            when(target.getObjectId()).thenReturn(91);
            when(target.getId()).thenReturn(100100);
            when(target.getPosition()).thenReturn(new Point(40, 0));
            when(target.getStats()).thenReturn(mock(MonsterStats.class));
            when(target.isBoss()).thenReturn(true);
            when(target.isAlive()).thenAnswer(invocation -> hp.get() > 0);
            when(target.getHp()).thenAnswer(invocation -> hp.get());
            when(target.damage(eq(actor), anyInt(), eq(false))).thenAnswer(invocation -> {
                hp.updateAndGet(value -> Math.max(0, value - (int) invocation.getArgument(1)));
                return hp.get() == 0;
            });
            when(map.damageMonster(eq(actor), eq(target), anyInt())).thenCallRealMethod();
            when(target.dropsDisabled()).thenReturn(true);
            when(map.getMonsterByOid(91)).thenReturn(target);
            when(outside.getObjectId()).thenReturn(92);
            when(outside.getPosition()).thenReturn(new Point(20, 0));
            when(outside.isAlive()).thenReturn(true);
            when(outside.isBoss()).thenReturn(true);
            doReturn(List.of(outside, target)).when(map).getMapObjectsInRange(any(), anyDouble(), eq(List.of(MapObjectType.MONSTER)));
            // Configure the native collaborators before opening starts persistent scene readers.
            beforeOpen.accept(this);
            var lane = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), body -> {
                var thread = new Thread(() -> {
                    try (var metadata = realTimingMetadata()) { body.run(); }
                }, "combat-test-lane");
                thread.setUncaughtExceptionHandler((worker, failure) -> laneFailures.add(failure));
                return thread;
            });
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), lane);
            var ref = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "combat-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return ref; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", 41); }
                @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                    permit.requireExecuting(actor); return new ReleaseProof(true);
                }
            };
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(binding); runtime.bindAdoptedOrigin();
                runtime.initializeSceneMembership(server);
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            if (tactics) {
                mountTactics();
            }
            var combat = new ModuleDefinition<CombatActionModule.Config, CombatActionModule>(
                    CombatActionModule.DEFINITION.typeId(), CombatActionModule.DEFINITION.controls(),
                    CombatActionModule.DEFINITION.limits(), config -> new CombatActionModule(config, server),
                    CombatActionModule.DEFINITION.exports());
            runtime.mount(combat, new ModuleSelection<>("combat", CombatActionModule.DEFAULT_CONFIG, true));
            var controller = new ModuleDefinition<Void, RuntimeModule>("test-controller", Set.of(),
                    new ExecutionScope.Limits(8, 2, 4, 2), config -> new RuntimeModule() {}, List.of());
            caller = runtime.context(runtime.mount(controller, new ModuleSelection<>("controller", null, true)).reference());
        }

        CombatTacticsControlModule mountTactics() {
            var definition = new ModuleDefinition<CombatTacticsControlModule.Config, CombatTacticsControlModule>(
                    CombatTacticsControlModule.DEFINITION.typeId(), CombatTacticsControlModule.DEFINITION.controls(),
                    CombatTacticsControlModule.DEFINITION.limits(), config -> new CombatTacticsControlModule(config, server),
                    CombatTacticsControlModule.DEFINITION.exports());
            var mounted = runtime.mount(definition, new ModuleSelection<>("tactics", CombatTacticsControlModule.DEFAULT_CONFIG, true));
            tacticsRef = mounted.reference();
            return mounted.instance();
        }

        void assertCombatDrained() {
            var scope = runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals("combat"))
                    .findFirst().orElseThrow().scope();
            assertEquals(0, scope.acceptedWork());
            assertEquals(0, scope.runningBodies());
            assertEquals(0, scope.pendingCarriers());
        }

        List<byte[]> attackPackets(SendOpcode opcode) {
            return packets.stream().map(Packet::getBytes).filter(bytes ->
                    ((bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8) == opcode.getValue()).toList();
        }

        @Override public void close() throws Exception {
            runtime.beginClose(Optional.empty());
            runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
            ExecutionScope.tryBeginWorldStop();
        }
    }
}
