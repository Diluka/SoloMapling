package soloMapling.game.navigation;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real terrain planning, physics and native MovePlayerHandler through the exported module entry. */
class NavigationModuleTest {
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach void startWorld() { CharacterTestContext.install(); WorldEpoch.onWorldStarted(); }
    @AfterEach void closeWorld() throws Exception {
        for (var fixture : fixtures) fixture.close();
        ThreadLocalUtil.removeCurrentClient();
        ExecutionScope.tryBeginWorldStop();
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

    @Test void unreachableTargetReturnsNoPathWithoutMovingTheNativeRole() {
        var fixture = fixture(98_982_101, new Point(0, 0), List.of(foothold(-200, 0, 300, 0, 1)), List.of());
        var result = fixture.moveTo(new Point(0, -200));
        assertEquals("NO_PATH", result.get("outcome"));
        assertEquals(new Point(0, 0), fixture.position.get());
        verify(fixture.map, never()).movePlayer(any(), any());
    }

    @Test void pausingNavigationWhileAirborneSettlesItsMotionBeforeTheModuleSuspends() throws Exception {
        var fixture = fixture(98_982_102, new Point(-50, 0),
                List.of(foothold(-200, 0, 0, 0, 1), foothold(20, -40, 230, -40, 2)), List.of());
        var result = CompletableFuture.supplyAsync(() -> fixture.moveTo(new Point(150, -40)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (fixture.position.get().y >= -5 && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(fixture.position.get().y < -5);
        var stopped = fixture.runtime.stop(fixture.module.context().reference(), CharacterRuntime.StopMode.PAUSE);
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

    private Fixture fixture(int mapId, Point from, List<Foothold> footholds, List<Rope> ropes) {
        var fixture = new Fixture(mapId, from, footholds, ropes);
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
        final CharacterRuntime runtime;
        final NavigationModule module;
        final CharacterRuntime.ModuleContext caller;

        Fixture(int mapId, Point from, List<Foothold> footholds, List<Rope> ropes) {
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
            when(character.getHp()).thenReturn(50);
            when(character.getChair()).thenReturn(-1);
            when(character.getTotalMoveSpeedStat()).thenReturn(100);
            when(character.getTotalJumpStat()).thenReturn(100);
            when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
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
            doAnswer(call -> { position.set(new Point(call.getArgument(0))); return null; }).when(character).setPosition(any());
            doAnswer(call -> { stance.set(call.getArgument(0)); return null; }).when(character).setStance(anyInt());
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
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            var nav = new ModuleDefinition<NavigationModule.Config, NavigationModule>(
                    NavigationModule.DEFINITION.typeId(), NavigationModule.DEFINITION.controls(),
                    NavigationModule.DEFINITION.limits(), config -> new NavigationModule(config, server),
                    NavigationModule.DEFINITION.exports());
            module = runtime.mount(nav, new ModuleSelection<>("pathfinder", NavigationModule.DEFAULT_CONFIG, true)).instance();
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
