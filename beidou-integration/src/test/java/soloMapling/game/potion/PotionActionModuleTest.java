package soloMapling.game.potion;

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
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleCall;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real WZ potion effects, API.useItem and character settlement; only world, metadata lookup and packet sinks are isolated. */
class PotionActionModuleTest {
    private final AtomicLong now = new AtomicLong(1000);
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage players;
    private ItemInformationProvider items;

    @BeforeEach void worldAndNativeEffects() throws Exception {
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
                var effect = StatEffect.loadItemEffectFromData(data.getChildByPath("0" + item.itemId() + "/spec"), item.itemId());
                when(items.getItemEffect(item.itemId())).thenReturn(effect);
            }
        }
    }

    @AfterEach void stopEpoch() { ExecutionScope.tryBeginWorldStop(); }

    @Test void typedAndJsonUseApplyNativeRatiosRoundingAndClampingWithoutStockOrCombat() throws Exception {
        try (var fixture = new Fixture(41, PotionActionModule.DEFAULT_CONFIG)) {
            fixture.resources(10, 5);
            var restored = fixture.use(2000004);
            assertEquals(PotionResult.Outcome.APPLIED, restored.outcome());
            assertEquals(50, restored.hpRestored());
            assertEquals(25, restored.mpRestored());
            assertEquals(60, fixture.actor.getHp());
            assertEquals(30, fixture.actor.getMp());
            assertEquals(new PotionResult.Cooldowns(5000, 0), restored.cooldowns());
            assertTrue(fixture.actor.getInventory(InventoryType.USE).list().isEmpty());

            now.addAndGet(5000);
            fixture.resources(90, 50);
            var json = (Map<?, ?>) fixture.caller.call("potions.use", Map.of("itemId", 2000004)).get("result");
            assertEquals("APPLIED", json.get("outcome"));
            assertEquals(11, json.get("hp_restored"));
            assertEquals(1, json.get("mp_restored"));
            assertEquals(101, fixture.actor.getHp());
            assertEquals(51, fixture.actor.getMp());
            assertTrue(fixture.actor.getInventory(InventoryType.USE).list().isEmpty());
            fixture.assertDrained();
        }
    }

    @Test void zombifyModifiesOnlyTheNativeHpRecoveryAndRemainsPresent() throws Exception {
        try (var fixture = new Fixture(41, PotionActionModule.DEFAULT_CONFIG)) {
            fixture.resources(10, 5);
            fixture.diseases(Disease.ZOMBIFY);
            var result = fixture.use(2000004);
            assertEquals(PotionResult.Outcome.APPLIED, result.outcome());
            assertEquals(25, result.hpRestored());
            assertEquals(25, result.mpRestored());
            assertEquals(List.of(Disease.ZOMBIFY), result.after().diseases());
            fixture.assertDrained();
        }
    }

    @Test void nativeAllCureRemovesTheSixSupportedDiseasesAndTheirExpiryWhilePreservingTheOthers() throws Exception {
        try (var fixture = new Fixture(41, PotionActionModule.DEFAULT_CONFIG)) {
            var cured = List.of(Disease.POISON, Disease.SEAL, Disease.DARKNESS, Disease.WEAKEN, Disease.SLOW, Disease.CURSE);
            var retained = List.of(Disease.SEDUCE, Disease.ZOMBIFY, Disease.CONFUSE, Disease.STUN);
            for (int index = 0; index < cured.size(); index += 2) {
                fixture.diseases(cured.get(index), cured.get(index + 1));
                var result = fixture.use(2050004);
                assertEquals(PotionResult.Outcome.APPLIED, result.outcome());
                assertEquals(2, result.removedDiseases().size());
                assertTrue(result.removedDiseases().containsAll(cured.subList(index, index + 2)));
                assertTrue(fixture.allDiseases().isEmpty());
                now.addAndGet(5000);
            }
            for (int index = 0; index < retained.size(); index += 2) {
                var pair = retained.subList(index, index + 2);
                fixture.diseases(pair.toArray(Disease[]::new));
                var noEffect = fixture.use(2050004);
                assertEquals(PotionResult.Outcome.NO_EFFECT, noEffect.outcome());
                assertEquals(new PotionResult.Cooldowns(0, 0), noEffect.cooldowns());
                assertTrue(noEffect.after().diseases().containsAll(pair));
                assertEquals(2, fixture.allDiseases().size());
                pair.forEach(fixture.actor::dispelDebuff);
            }
            assertTrue(fixture.actor.getInventory(InventoryType.USE).list().isEmpty());
            fixture.assertDrained();
        }
    }

    @Test void groupsRemainIndependentAndResourceChangesDoNotOpenASecondRestoreWindow() throws Exception {
        var config = PotionActionModule.parseConfig(Map.of("restore_cooldown_ms", 1200, "cleanse_cooldown_ms", 2400));
        try (var fixture = new Fixture(41, config)) {
            assertEquals(PotionResult.Outcome.NO_EFFECT, fixture.use(2000004).outcome());
            fixture.resources(10, 51);
            assertEquals(new PotionResult.Cooldowns(1200, 0), fixture.use(2000004).cooldowns());
            fixture.resources(101, 5);
            assertEquals(PotionResult.Outcome.COOLDOWN, fixture.use(2000004).outcome());
            assertEquals(5, fixture.actor.getMp());
            fixture.diseases(Disease.POISON);
            assertEquals(new PotionResult.Cooldowns(1200, 2400), fixture.use(2050004).cooldowns());
            now.addAndGet(1200);
            var restored = fixture.use(2000004);
            assertEquals(PotionResult.Outcome.APPLIED, restored.outcome());
            assertEquals(0, restored.hpRestored());
            assertEquals(25, restored.mpRestored());
            assertEquals(new PotionResult.Cooldowns(1200, 1200), restored.cooldowns());
            fixture.assertDrained();
        }
    }

    @Test void unsupportedAndDeadCallsDoNotDispatchOrChangeEitherCooldown() throws Exception {
        try (var fixture = new Fixture(41, PotionActionModule.DEFAULT_CONFIG)) {
            fixture.resources(10, 5);
            var unsupported = fixture.use(999999);
            assertEquals(PotionResult.Outcome.UNSUPPORTED, unsupported.outcome());
            assertEquals(999999, unsupported.itemId());
            assertFalse(unsupported.dispatched());
            assertEquals(unsupported.before(), unsupported.after());
            assertEquals(new PotionResult.Cooldowns(0, 0), unsupported.cooldowns());
            verify(items, never()).getItemEffect(999999);
            fixture.resources(0, 5);
            var dead = fixture.use(2000004);
            assertEquals(PotionResult.Outcome.DEAD, dead.outcome());
            assertFalse(dead.dispatched());
            assertEquals(0, fixture.actor.getHp());
            assertEquals(new PotionResult.Cooldowns(0, 0), dead.cooldowns());
            fixture.assertDrained();
        }
    }

    @Test void pauseRetainsCooldownAndAnotherRoleOwnsAnIndependentWindow() throws Exception {
        try (var first = new Fixture(41, PotionActionModule.DEFAULT_CONFIG);
             var second = new Fixture(42, PotionActionModule.DEFAULT_CONFIG)) {
            first.resources(10, 5);
            assertEquals(PotionResult.Outcome.APPLIED, first.use(2000004).outcome());
            var stopped = first.runtime.stop(first.potionRef, CharacterRuntime.StopMode.PAUSE);
            assertEquals(ExecutionScope.DrainResult.DRAINED, stopped.await(Duration.ofSeconds(3)));
            assertEquals(ModuleCall.Status.UNAVAILABLE, first.caller.call(PotionActionModule.USE, new PotionRequest(2000004)).status());
            now.addAndGet(1000);
            first.potionRef = first.runtime.activate(first.potionRef);
            assertEquals(4000, first.use(2000004).cooldowns().restoreMs());
            second.resources(10, 5);
            assertEquals(PotionResult.Outcome.APPLIED, second.use(2000004).outcome());
            now.addAndGet(4000);
            assertEquals(PotionResult.Outcome.APPLIED, first.use(2000004).outcome());
            assertEquals(PotionResult.Outcome.COOLDOWN, second.use(2000004).outcome());
            first.assertDrained();
            second.assertDrained();
        }
    }

    @Test void cancellationAfterNativeEffectKeepsTheEffectAndCooldownAndWaitsForItsRealExit() throws Exception {
        try (var fixture = new Fixture(41, PotionActionModule.DEFAULT_CONFIG)) {
            fixture.resources(10, 5);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.onNativeUpdate = () -> { entered.countDown(); await(release); };
            var parent = fixture.caller.acceptWork();
            var result = new AtomicReference<PotionResult>();
            var call = CompletableFuture.runAsync(() -> fixture.caller.scope().runTracked(parent,
                    () -> result.set(fixture.caller.call(PotionActionModule.USE, new PotionRequest(2000004), parent).result())));
            CharacterRuntime.ModuleStop stop;
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                fixture.caller.cancel(parent);
                stop = fixture.runtime.stop(fixture.potionRef, CharacterRuntime.StopMode.PAUSE);
                assertFalse(stop.complete());
                assertFalse(call.isDone());
            } finally { release.countDown(); }
            call.get(3, TimeUnit.SECONDS);
            assertEquals(PotionResult.Outcome.APPLIED, result.get().outcome());
            assertEquals(60, fixture.actor.getHp());
            assertTrue(result.get().cancelRequested());
            assertEquals(new PotionResult.Cooldowns(5000, 0), result.get().cooldowns());
            assertEquals(ExecutionScope.DrainResult.DRAINED, stop.await(Duration.ofSeconds(3)));
            fixture.potionRef = fixture.runtime.activate(fixture.potionRef);
            assertEquals(PotionResult.Outcome.COOLDOWN, fixture.use(2000004).outcome());
            fixture.assertDrained();
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("potion_test_gate_timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private final class Fixture implements AutoCloseable {
        final Character actor;
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        CharacterRuntime.ModuleRef potionRef;
        Runnable onNativeUpdate = () -> {};

        Fixture(int id, PotionActionModule.Config config) throws Exception {
            var client = mock(Client.class);
            when(client.getChannel()).thenReturn(1);
            when(client.isLoggedIn()).thenReturn(true);
            actor = spy(Character.getDefault(client));
            actor.setId(id);
            actor.setName("Potion" + id);
            when(client.getPlayer()).thenReturn(actor);
            when(client.getAbstractPlayerInteraction()).thenReturn(new AbstractPlayerInteraction(client));
            var map = mock(MapleMap.class);
            when(map.getId()).thenReturn(10000);
            when(map.getCharacterById(id)).thenReturn(actor);
            actor.setMap(map);
            actor.setMapId(10000);
            doNothing().when(actor).hpChangeAction(anyInt());
            for (var fieldName : List.of("localMaxHp", "maxHp")) {
                var field = AbstractCharacterObject.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(actor, 101);
            }
            for (var fieldName : List.of("localMaxMp", "maxMp")) {
                var field = AbstractCharacterObject.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(actor, 51);
            }
            actor.updateHpMp(101, 51);
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
                @Override public String kind() { return "potion-test"; }
                @Override public Object identity() { return actor; }
                @Override public GameContext.CharacterRef characterRef() { return ref; }
                @Override public void requireCurrent() { }
                @Override public Map<String, Object> observe() { return Map.of("character_id", id); }
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
            potionRef = runtime.mount(potions, new ModuleSelection<>("potions", config, true)).reference();
        }

        void resources(int hp, int mp) { actor.updateHpMp(hp, mp); }

        void diseases(Disease... values) {
            var states = new LinkedHashMap<Disease, Pair<Long, MobSkill>>();
            Arrays.stream(values).forEach(disease -> states.put(disease, new Pair<>(60000L, mock(MobSkill.class))));
            try (var singleton = mockStatic(Server.class)) {
                singleton.when(Server::getInstance).thenReturn(server);
                actor.silentApplyDiseases(states);
            }
        }

        Map<Disease, Pair<Long, MobSkill>> allDiseases() {
            try (var singleton = mockStatic(Server.class)) {
                singleton.when(Server::getInstance).thenReturn(server);
                return actor.getAllDiseases();
            }
        }

        PotionResult use(int itemId) {
            var result = caller.call(PotionActionModule.USE, new PotionRequest(itemId));
            assertEquals(ModuleCall.Status.SUCCEEDED, result.status());
            return result.result();
        }

        void assertDrained() {
            assertEquals(0, runtime.snapshot().lane().pending());
            var potions = runtime.snapshot().modules().stream().filter(module -> module.instanceId().equals("potions"))
                    .findFirst().orElseThrow();
            if (potions.scope() != null) {
                assertEquals(0, potions.scope().acceptedWork());
                assertEquals(0, potions.scope().runningBodies());
                assertEquals(0, potions.scope().outstandingResources());
            }
        }

        @Override public void close() throws Exception {
            runtime.beginClose(java.util.Optional.empty());
            runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var cleanup = runtime.acquireOriginCleanup();
            runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
            assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
            players.removePlayer(actor.getId());
        }
    }
}
