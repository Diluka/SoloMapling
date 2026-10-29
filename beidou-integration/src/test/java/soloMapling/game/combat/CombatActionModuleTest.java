package soloMapling.game.combat;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CombatActionModuleTest {
    @Test void sharedEntryHitsOnlyTheRequestedTargetAndWaitsForTheLegacyStrike() throws Exception {
        try (var fixture = new Fixture()) {
            var result = (Map<?, ?>) fixture.caller.call("combat.engage", Map.of("target_oid", 91)).get("result");
            assertEquals("TARGET_DEAD", result.get("outcome"));
            assertEquals(true, result.get("execution_stopped"));
            assertEquals(0, result.get("initial_job"));
            var hits = (List<?>) result.get("hits");
            assertEquals(1, hits.size());
            assertEquals(91, ((Map<?, ?>) hits.getFirst()).get("object_id"));
            assertEquals(0, ((Map<?, ?>) hits.getFirst()).get("hp_after"));
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

    private static final class Fixture implements AutoCloseable {
        final Character actor = mock(Character.class);
        final Monster target = mock(Monster.class);
        final Monster outside = mock(Monster.class);
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;

        Fixture() {
            CharacterTestContext.install();
            WorldEpoch.onWorldStarted();
            var server = mock(Server.class);
            var world = mock(World.class);
            var channel = mock(Channel.class);
            var client = mock(Client.class);
            var map = mock(MapleMap.class);
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
            when(actor.getMap()).thenReturn(map);
            when(map.getCharacterById(41)).thenReturn(actor);
            when(actor.getPosition()).thenReturn(new Point(0, 0));
            when(actor.getStance()).thenReturn(4);
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
