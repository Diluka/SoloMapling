package soloMapling.game.combat;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.BuffStat;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
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
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.navigation.NavigationActionModule;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CombatActionModuleTest {
    @BeforeEach void installNativeContext() { CharacterTestContext.install(); Job.values(); }
    @Test void sharedEntryHitsOnlyTheRequestedTargetAndWaitsForTheLegacyStrike() throws Exception {
        try (var fixture = new Fixture()) {
            var call = fixture.caller.call(CombatActionModule.ENGAGE,
                    new CombatRequest(91, Set.of(91), CombatRequest.Choice.AUTO, 30_000), null);
            var result = call.result();
            assertEquals(CombatResult.Outcome.TARGET_DEAD, result.outcome());
            assertEquals(0, result.initialJob());
            assertEquals(1, result.hits().size());
            assertEquals(91, result.hits().getFirst().objectId());
            assertEquals(0, result.hits().getFirst().hpAfter());
            verify(fixture.target, atLeastOnce()).damage(eq(fixture.actor), anyInt(), eq(false));
            verify(fixture.outside, never()).damage(any(), anyInt(), anyBoolean());
            assertEquals(0, fixture.runtime.snapshot().lane().pending());
        }
    }

    @Test void targetOutsideReachReportsMissingNavigationAndLeavesTheProviderDrained() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.target.getPosition()).thenReturn(new Point(300, 0));
            var result = (Map<?, ?>) fixture.caller.call("combat.engage", Map.of("target_oid", 91)).get("result");
            assertEquals("UNAVAILABLE", result.get("outcome"));
            assertEquals("navigation_unavailable", result.get("reason"));
            verify(fixture.target, never()).damage(any(), anyInt(), anyBoolean());
            assertEquals(0, fixture.runtime.snapshot().lane().pending());
        }
    }

    @Test void aNativeLinkageFailureReturnsToTheCallerAfterTheDamageWorkSettles() throws Exception {
        try (var fixture = new Fixture()) {
            var failure = new NoSuchMethodError("native_drop_linkage_failure");
            doAnswer(invocation -> { fixture.exp.set(500); throw failure; }).when(fixture.map)
                    .killMonster(eq(fixture.target), eq(fixture.actor), eq(false), anyInt());
            assertSame(failure, assertThrows(NoSuchMethodError.class,
                    () -> fixture.caller.call("combat.engage", Map.of("target_oid", 91))));
            assertEquals(0, fixture.target.getHp());
            assertEquals(500, fixture.actor.getExp());
            assertEquals(0, fixture.runtime.snapshot().lane().pending());
        }
    }

    @Test void engagementProjectsItsApproachOntoTheSlopeBeforeCallingRealNavigation() throws Exception {
        try (var fixture = new Fixture(true)) {
            var footholds = new FootholdTree(new Point(-500, -500), new Point(1000, 500));
            footholds.insert(new Foothold(new Point(0, 0), new Point(500, 100), 1));
            when(fixture.map.getFootholds()).thenReturn(footholds);
            when(fixture.target.getPosition()).thenReturn(new Point(400, 80));
            var result = (Map<?, ?>) fixture.caller.call("combat.engage", Map.of("target_oid", 91)).get("result");
            assertEquals("TARGET_DEAD", result.get("outcome"), result.toString());
            assertTrue((int) result.get("navigation_calls") >= 1);
            Point actual = fixture.position.get();
            assertTrue(actual.x >= 340, actual.toString());
            assertTrue(Math.abs(actual.y - actual.x * .2) <= 4, actual.toString());
            verify(fixture.map, atLeastOnce()).movePlayer(eq(fixture.actor), any());
            verify(fixture.outside, never()).damage(any(), anyInt(), anyBoolean());
            assertEquals(0, fixture.runtime.snapshot().lane().pending());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Character actor = mock(Character.class);
        final Monster target = mock(Monster.class);
        final Monster outside = mock(Monster.class);
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final Server server = mock(Server.class);
        final MapleMap map = mock(MapleMap.class);
        final AtomicReference<Point> position = new AtomicReference<>(new Point(0, 0));
        final AtomicInteger stance = new AtomicInteger(4);
        final AtomicInteger exp = new AtomicInteger();

        Fixture() { this(false); }

        Fixture(boolean navigation) {
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
            when(actor.getJob()).thenReturn(Job.BEGINNER);
            when(actor.getLevel()).thenReturn(1);
            when(actor.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
            var hp = new AtomicInteger(100);
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
            when(target.dropsDisabled()).thenReturn(true);
            when(map.getMonsterByOid(91)).thenReturn(target);
            when(outside.getObjectId()).thenReturn(92);
            when(outside.getPosition()).thenReturn(new Point(20, 0));
            when(outside.isAlive()).thenReturn(true);
            when(outside.isBoss()).thenReturn(true);
            doReturn(List.of(outside, target)).when(map).getMapObjectsInRange(any(), anyDouble(), eq(List.of(MapObjectType.MONSTER)));
            var lane = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
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
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            var combat = new ModuleDefinition<CombatActionModule.Config, CombatActionModule>(
                    CombatActionModule.DEFINITION.typeId(), CombatActionModule.DEFINITION.controls(),
                    CombatActionModule.DEFINITION.limits(), config -> new CombatActionModule(config, server),
                    CombatActionModule.DEFINITION.exports());
            runtime.mount(combat, new ModuleSelection<>("combat", CombatActionModule.DEFAULT_CONFIG, true));
            if (navigation) {
                var definition = new ModuleDefinition<NavigationActionModule.Config, NavigationActionModule>(
                        NavigationActionModule.DEFINITION.typeId(), NavigationActionModule.DEFINITION.controls(),
                        NavigationActionModule.DEFINITION.limits(), config -> new NavigationActionModule(config, server),
                        NavigationActionModule.DEFINITION.exports());
                runtime.mount(definition, new ModuleSelection<>("navigation", NavigationActionModule.DEFAULT_CONFIG, true));
            }
            var controller = new ModuleDefinition<Void, RuntimeModule>("test-controller", Set.of(),
                    new ExecutionScope.Limits(8, 2, 4, 2), config -> new RuntimeModule() {}, List.of());
            caller = runtime.context(runtime.mount(controller, new ModuleSelection<>("controller", null, true)).reference());
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
