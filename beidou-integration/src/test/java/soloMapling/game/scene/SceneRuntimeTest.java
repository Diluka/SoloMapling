package soloMapling.game.scene;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Scene ownership through actual role adoption and close; native map/storage remain explicit boundaries. */
class SceneRuntimeTest {
    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final Channel channel = mock(Channel.class);
    private final Map<Integer, Character> players = new HashMap<>();
    private final List<CharacterRuntime> runtimes = new ArrayList<>();

    @BeforeEach void startWorld() {
        CharacterTestContext.install();
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
        when(client.getPlayer()).thenReturn(character);
        when(client.getChannel()).thenReturn(1);
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
            @Override public Optional<Server> clientSimulationServer() { return Optional.of(server); }
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
