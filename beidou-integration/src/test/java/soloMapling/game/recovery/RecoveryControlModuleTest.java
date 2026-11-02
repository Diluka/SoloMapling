package soloMapling.game.recovery;

import org.gms.client.AbstractCharacterObject;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Disease;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.provider.wz.XMLDomMapleData;
import org.gms.scripting.AbstractPlayerInteraction;
import org.gms.server.ItemInformationProvider;
import org.gms.server.StatEffect;
import org.gms.server.life.MobSkill;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.McpControlModule;
import org.gms.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.potion.PotionActionModule;
import soloMapling.game.potion.PotionItem;
import soloMapling.game.potion.PotionRequest;
import soloMapling.game.potion.PotionResult;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Automatic recovery consumes the real potion operation; world lookup and packet sinks are isolated. */
class RecoveryControlModuleTest {
    private final AtomicLong now = new AtomicLong(1000);
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage players;
    private ItemInformationProvider items;

    @BeforeEach void nativeWorldAndPotionEffects() throws Exception {
        InventoryMetadataTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        players = new PlayerStorage();
        when(server.isOnline()).thenReturn(true);
        when(server.getCurrentTime()).thenAnswer(call -> now.get());
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        when(world.getPlayerStorage()).thenReturn(players);
        when(channel.getPlayerStorage()).thenReturn(players);
        items = mock(ItemInformationProvider.class);
        for (var item : PotionItem.values()) {
            var file = Path.of("upstream/BeiDou-Server/gms-server/wz/Item.wz/Consume/0"
                    + item.itemId() / 10000 + ".img.xml");
            try (var input = new FileInputStream(file.toFile())) {
                var data = new XMLDomMapleData(input, file.getParent());
                var effect = StatEffect.loadItemEffectFromData(
                        data.getChildByPath("0" + item.itemId() + "/spec"), item.itemId());
                when(items.getItemEffect(item.itemId())).thenReturn(effect);
            }
        }
    }

    @AfterEach void stopWorldEpoch() { ExecutionScope.tryBeginWorldStop(); }

    @Test void activationMonitorsOnceAndBothBoundaryResourcesProduceOneRealPotionCall() throws Exception {
        try (var fixture = new Fixture(config(50, 50))) {
            assertEquals("RUNNING", fixture.recovery.status().state().name());
            assertEquals("ALREADY_RUNNING", fixture.recovery.start().outcome().name());
            var stopped = (Map<?, ?>) fixture.caller.call("recovery.stop", Map.of()).get("result");
            assertEquals("STOPPED", stopped.get("outcome"));
            assertEquals("ALREADY_IDLE", fixture.recovery.stop().outcome().name());

            fixture.resources(50, 25);
            var started = (Map<?, ?>) fixture.caller.call("recovery.start", Map.of()).get("result");
            assertEquals("STARTED", started.get("outcome"));
            eventually(() -> fixture.recovery.status().restorations() == 1);
            assertEquals(100, fixture.actor.getHp());
            assertEquals(50, fixture.actor.getMp());
            assertEquals(1, fixture.recovery.status().restoreRequests());
            assertEquals(0, fixture.recovery.status().cleanseRequests());
            assertEquals(50, fixture.recovery.status().lastRestore().hpRestored());
            assertEquals(25, fixture.recovery.status().lastRestore().mpRestored());
            assertTrue(fixture.actor.getInventory(InventoryType.USE).list().isEmpty());
            fixture.recovery.stop();
            fixture.assertRecoveryDrained();
        }
    }

    @Test void anotherCallersRestoreCooldownDoesNotBlockNativeCleanseOrResetOnMonitoringRestart() throws Exception {
        try (var fixture = new Fixture(config(50, 50))) {
            fixture.recovery.stop();
            fixture.resources(10, 5);
            assertEquals(PotionResult.Outcome.APPLIED, fixture.manualPotion(2000004).outcome());
            fixture.resources(10, 50);
            fixture.diseases(Disease.POISON, Disease.STUN);
            fixture.recovery.start();
            eventually(() -> fixture.recovery.status().cleanses() == 1);
            eventually(() -> fixture.recovery.status().lastRestore() != null
                    && fixture.recovery.status().lastRestore().outcome() == PotionResult.Outcome.COOLDOWN);
            assertEquals(10, fixture.actor.getHp());
            assertEquals(List.of(Disease.STUN), fixture.recovery.status().lastCleanse().after().diseases());
            assertEquals(new PotionResult.Cooldowns(5000, 5000), fixture.recovery.status().lastCleanse().cooldowns());
            long checks = fixture.recovery.status().checks();
            eventually(() -> fixture.recovery.status().checks() >= checks + 2);
            assertEquals(1, fixture.recovery.status().cleanseRequests());

            now.addAndGet(5000);
            eventually(() -> fixture.recovery.status().restorations() == 1);
            assertEquals(60, fixture.actor.getHp());
            assertEquals(50, fixture.actor.getMp());
            fixture.recovery.stop();
            assertEquals(PotionResult.Outcome.COOLDOWN, fixture.manualPotion(2000004).outcome());
            assertTrue(fixture.actor.getInventory(InventoryType.USE).list().isEmpty());
            fixture.assertRecoveryDrained();
        }
    }

    @Test void disablingHpTriggerStillAllowsAnMpTriggeredPotionToRestoreBothResources() throws Exception {
        var config = RecoveryControlModule.parseConfig(Map.of("hp_threshold_pct", 0, "mp_threshold_pct", 50,
                "cleanse_enabled", "true", "check_interval_ms", 20));
        try (var fixture = new Fixture(config)) {
            fixture.recovery.stop();
            fixture.resources(10, 50);
            fixture.recovery.start();
            eventually(() -> fixture.recovery.status().checks() >= 2);
            assertEquals(10, fixture.actor.getHp());
            assertEquals(0, fixture.recovery.status().restoreRequests());
            fixture.resources(10, 1);
            eventually(() -> fixture.recovery.status().restorations() == 1);
            assertEquals(60, fixture.actor.getHp());
            assertEquals(26, fixture.actor.getMp());
            fixture.recovery.stop();
            fixture.assertRecoveryDrained();
        }
    }

    @Test void deathMapChangeAndReactivationContinueUsingTheCurrentRole() throws Exception {
        try (var fixture = new Fixture(config(50, 50))) {
            fixture.recovery.stop();
            fixture.resources(0, 1);
            fixture.recovery.start();
            eventually(() -> fixture.recovery.status().checks() >= 2);
            assertEquals("RUNNING", fixture.recovery.status().state().name());
            assertEquals(0, fixture.actor.getHp());
            assertEquals(1, fixture.actor.getMp());
            assertEquals(0, fixture.recovery.status().restoreRequests());

            fixture.changeMap(10001);
            fixture.resources(10, 1);
            eventually(() -> fixture.recovery.status().restorations() == 1);
            assertEquals(60, fixture.actor.getHp());
            assertEquals(10001, fixture.actor.getMapId());
            var stopped = fixture.runtime.stop(fixture.recoveryRef, CharacterRuntime.StopMode.PAUSE);
            assertEquals(ExecutionScope.DrainResult.DRAINED, stopped.await(Duration.ofSeconds(5)));
            assertEquals("IDLE", fixture.recovery.status().state().name());
            assertEquals("module_stopping", fixture.recovery.status().reason());
            now.addAndGet(5000);
            fixture.resources(10, 1);
            fixture.recoveryRef = fixture.runtime.activate(fixture.recoveryRef);
            eventually(() -> fixture.actor.getHp() == 60 && fixture.actor.getMp() == 26);
            assertEquals("RUNNING", fixture.recovery.status().state().name());

            // Resume immediately when drain is reported, without first waiting for IDLE publication.
            var paused = fixture.runtime.stop(fixture.recoveryRef, CharacterRuntime.StopMode.PAUSE);
            assertEquals(ExecutionScope.DrainResult.DRAINED, paused.await(Duration.ofSeconds(5)));
            fixture.recoveryRef = fixture.runtime.activate(fixture.recoveryRef);
            eventually(() -> fixture.recovery.status().state() == RecoveryStatus.State.RUNNING
                    && fixture.recovery.status().checks() > 0);
            fixture.recovery.stop();
            fixture.assertRecoveryDrained();
        }
    }

    @Test void unavailablePotionProviderDoesNotEndMonitoringAndItsReturnIsObserved() throws Exception {
        try (var fixture = new Fixture(config(50, 50))) {
            var stopped = fixture.runtime.stop(fixture.potionRef, CharacterRuntime.StopMode.PAUSE);
            assertEquals(ExecutionScope.DrainResult.DRAINED, stopped.await(Duration.ofSeconds(5)));
            fixture.resources(10, 1);
            eventually(() -> fixture.recovery.status().restoreRequests() > 0
                    && "potions_unavailable".equals(fixture.recovery.status().reason()));
            assertEquals("RUNNING", fixture.recovery.status().state().name());
            assertEquals(10, fixture.actor.getHp());
            assertEquals(0, fixture.recovery.status().restorations());
            assertNotNull(fixture.recovery.status().reason());
            fixture.potionRef = fixture.runtime.activate(fixture.potionRef);
            eventually(() -> fixture.recovery.status().restorations() == 1);
            assertEquals(60, fixture.actor.getHp());
            assertEquals(26, fixture.actor.getMp());
            fixture.recovery.stop();
            fixture.assertRecoveryDrained();
        }
    }

    @Test void stopWaitsForTheActualPotionBodyAndKeepsTheAppliedEffectAndPotionCooldown() throws Exception {
        try (var fixture = new Fixture(config(50, 50))) {
            fixture.recovery.stop();
            fixture.resources(10, 5);
            var reached = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.onNativeUpdate = () -> { reached.countDown(); await(release); };
            fixture.recovery.start();
            CompletableFuture<RecoveryStopResult> stopped;
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                stopped = CompletableFuture.supplyAsync(fixture.recovery::stop);
                eventually(() -> fixture.recovery.status().state().name().equals("STOPPING"));
                assertFalse(stopped.isDone());
            } finally { release.countDown(); }
            assertEquals("STOPPED", stopped.get(5, TimeUnit.SECONDS).outcome().name());
            assertEquals("stopped", fixture.recovery.status().reason());
            assertEquals(60, fixture.actor.getHp());
            assertEquals(30, fixture.actor.getMp());
            assertEquals(1, fixture.recovery.status().restorations());
            assertTrue(fixture.recovery.status().lastRestore().cancelRequested());
            assertEquals(PotionResult.Outcome.COOLDOWN, fixture.manualPotion(2000004).outcome());
            fixture.assertRecoveryDrained();
        }
    }

    private static RecoveryControlModule.Config config(int hp, int mp) {
        return new RecoveryControlModule.Config(hp, mp, true, 20);
    }

    private static void eventually(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "recovery_did_not_reach_expected_native_state");
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("recovery_native_gate_timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private final class Fixture implements AutoCloseable {
        final Character actor;
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final RecoveryControlModule recovery;
        CharacterRuntime.ModuleRef potionRef;
        CharacterRuntime.ModuleRef recoveryRef;
        Runnable onNativeUpdate = () -> {};

        Fixture(RecoveryControlModule.Config config) throws Exception {
            var client = mock(Client.class);
            when(client.getChannel()).thenReturn(1);
            when(client.isLoggedIn()).thenReturn(true);
            actor = spy(Character.getDefault(client));
            actor.setId(41);
            actor.setName("Recovery41");
            when(client.getPlayer()).thenReturn(actor);
            when(client.getAbstractPlayerInteraction()).thenReturn(new AbstractPlayerInteraction(client));
            changeMap(10000);
            doNothing().when(actor).hpChangeAction(anyInt());
            for (var name : List.of("localMaxHp", "maxHp")) {
                var field = AbstractCharacterObject.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(actor, 100);
            }
            for (var name : List.of("localMaxMp", "maxMp")) {
                var field = AbstractCharacterObject.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(actor, 50);
            }
            actor.updateHpMp(100, 50);
            doAnswer(call -> { call.callRealMethod(); onNativeUpdate.run(); return null; })
                    .when(actor).updateHpMp(anyInt(), anyInt());
            players.addPlayer(actor);
            var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        try (var singleton = mockStatic(Server.class); var metadata = mockStatic(ItemInformationProvider.class)) {
                            singleton.when(Server::getInstance).thenReturn(server);
                            metadata.when(ItemInformationProvider::getInstance).thenReturn(items);
                            command.run();
                        }
                    }));
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), executor);
            var ref = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "recovery-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return ref; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", actor.getId()); }
                @Override public ReleaseProof release(ScopeResources.DrainPermit permit) {
                    permit.requireExecuting(actor); return new ReleaseProof(true);
                }
            };
            var opening = runtime.acceptOpeningWork();
            runtime.submitOpening(opening, () -> {
                runtime.reserveOrigin(opening).adopt(binding); runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            caller = runtime.mount(McpControlModule.DEFINITION,
                    new ModuleSelection<>("mcp", Map.of(), true)).instance().context();
            var potions = new ModuleDefinition<PotionActionModule.Config, PotionActionModule>(
                    PotionActionModule.DEFINITION.typeId(), PotionActionModule.DEFINITION.controls(),
                    PotionActionModule.DEFINITION.limits(), value -> new PotionActionModule(value, server),
                    PotionActionModule.DEFINITION.exports());
            potionRef = runtime.mount(potions, new ModuleSelection<>("potions", PotionActionModule.DEFAULT_CONFIG, true)).reference();
            var definition = new ModuleDefinition<RecoveryControlModule.Config, RecoveryControlModule>(
                    RecoveryControlModule.DEFINITION.typeId(), RecoveryControlModule.DEFINITION.controls(),
                    RecoveryControlModule.DEFINITION.limits(), value -> new RecoveryControlModule(value, server),
                    RecoveryControlModule.DEFINITION.exports());
            var mounted = runtime.mount(definition, new ModuleSelection<>("recovery", config, true));
            recovery = mounted.instance();
            recoveryRef = mounted.reference();
        }

        void resources(int hp, int mp) { actor.updateHpMp(hp, mp); }

        void changeMap(int id) {
            var map = mock(MapleMap.class);
            when(map.getId()).thenReturn(id);
            when(map.getCharacterById(41)).thenReturn(actor);
            actor.setMap(map);
            actor.setMapId(id);
        }

        void diseases(Disease... values) {
            var states = new LinkedHashMap<Disease, Pair<Long, MobSkill>>();
            Arrays.stream(values).forEach(disease -> states.put(disease, new Pair<>(60000L, mock(MobSkill.class))));
            try (var singleton = mockStatic(Server.class)) {
                singleton.when(Server::getInstance).thenReturn(server);
                actor.silentApplyDiseases(states);
            }
        }

        PotionResult manualPotion(int id) { return caller.call(PotionActionModule.USE, new PotionRequest(id)).result(); }

        void assertRecoveryDrained() throws Exception {
            assertEquals("IDLE", recovery.status().state().name());
            eventually(() -> runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals("recovery"))
                    .allMatch(module -> module.scope() == null || module.scope().acceptedWork() == 0
                            && module.scope().runningBodies() == 0 && module.scope().pendingCarriers() == 0));
            assertEquals(0, runtime.snapshot().lane().pending());
        }

        @Override public void close() throws Exception {
            recovery.stop();
            runtime.beginClose(Optional.empty());
            runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
            players.removePlayer(actor.getId());
        }
    }
}
