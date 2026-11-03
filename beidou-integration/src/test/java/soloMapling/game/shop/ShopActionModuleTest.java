package soloMapling.game.shop;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.server.ItemInformationProvider;
import org.gms.server.Shop;
import org.gms.server.ShopFactory;
import org.gms.server.life.NPC;
import org.gms.server.life.NPCStats;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.McpControlModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.InventoryMetadataTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.inventory.SaleItem;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleCall;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;

import java.awt.Point;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runtime, native Shop.sell and InventoryManipulator are real; world/metadata and packet delivery are isolated. */
class ShopActionModuleTest {
    private static final int NPC_ID = 1011100;
    private static final int JUNK = 4000000;
    private static final int KEEP = 4000001;
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage players;
    private ItemInformationProvider items;
    private ShopFactory shops;
    private Shop nativeShop;

    @BeforeEach void nativeWorldAndPriceBoundary() throws Exception {
        InventoryMetadataTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        players = new PlayerStorage();
        when(server.isOnline()).thenReturn(true);
        when(server.getCurrentTime()).thenReturn(10000L);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        when(world.getPlayerStorage()).thenReturn(players);
        when(channel.getPlayerStorage()).thenReturn(players);
        items = mock(ItemInformationProvider.class);
        when(items.getPrice(anyInt(), anyInt())).thenAnswer(call -> call.<Integer>getArgument(1) * 3);
        shops = mock(ShopFactory.class);
        var constructor = Shop.class.getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        nativeShop = constructor.newInstance(1011100, NPC_ID);
        when(shops.getShopForNPC(NPC_ID)).thenReturn(nativeShop);
    }

    @AfterEach void stopEpoch() { ExecutionScope.tryBeginWorldStop(); }

    @Test void typedAndJsonShopCallsPreserveConversationAndSettleRealItemsAndMeso() throws Exception {
        try (var fixture = new Fixture(41)) {
            var overflowingNpcId = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(NPC_ID));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.caller.call("shop.open", Map.of("npcId", overflowingNpcId)));
            assertNull(fixture.actor.getShop());
            fixture.assertDrained();
            var conversation = mock(NPCConversationManager.class);
            when(fixture.client.getCM()).thenReturn(conversation);
            fixture.stock(InventoryType.ETC, 1, JUNK, 5);
            fixture.stock(InventoryType.ETC, 2, KEEP, 4);

            assertEquals(ShopResult.Outcome.OPENED, fixture.open().outcome());
            assertSame(nativeShop, fixture.actor.getShop());
            assertSame(conversation, fixture.client.getCM());
            var alreadyOpen = (Map<?, ?>) fixture.caller.call("shop.open", Map.of("npcId", NPC_ID)).get("result");
            assertEquals("ALREADY_OPEN", alreadyOpen.get("outcome"));
            var sold = fixture.sell(new SaleItem(InventoryType.ETC, (short) 1, JUNK, 2));
            assertEquals(ShopResult.Outcome.SOLD, sold.outcome());
            assertEquals(3, fixture.quantity(InventoryType.ETC, 1));
            assertEquals(4, fixture.quantity(InventoryType.ETC, 2));
            assertEquals(106, fixture.actor.getMeso());
            assertEquals(100, sold.mesoBefore());
            assertEquals(106, sold.mesoAfter());
            assertTrue(sold.sales().getFirst().dispatchStarted());
            assertFalse(sold.sales().getFirst().handlerError());
            var jsonSale = (Map<?, ?>) fixture.caller.call("shop.sell", Map.of("items", List.of(Map.of(
                    "inventoryType", "ETC", "slot", 1, "expectedItemId", JUNK, "quantity", 1)))).get("result");
            assertEquals("SOLD", jsonSale.get("outcome"));
            assertEquals(2, fixture.quantity(InventoryType.ETC, 1));
            assertEquals(4, fixture.quantity(InventoryType.ETC, 2));
            assertEquals(109, fixture.actor.getMeso());

            var closed = (Map<?, ?>) fixture.caller.call("shop.close", Map.of()).get("result");
            assertEquals("CLOSED", closed.get("outcome"));
            assertNull(fixture.actor.getShop());
            assertSame(conversation, fixture.client.getCM());
            verify(conversation, never()).dispose();
            fixture.assertDrained();
        }
    }

    @Test void changedSlotAndInsufficientQuantityDoNotSellAnyItem() throws Exception {
        try (var fixture = new Fixture(41)) {
            fixture.stock(InventoryType.ETC, 1, JUNK, 5);
            fixture.stock(InventoryType.ETC, 2, KEEP, 4);
            fixture.open();
            var result = fixture.sell(
                    new SaleItem(InventoryType.ETC, (short) 1, KEEP, 1),
                    new SaleItem(InventoryType.ETC, (short) 2, KEEP, 5));
            assertEquals(ShopResult.Outcome.REJECTED, result.outcome());
            assertEquals(ShopResult.SaleOutcome.ITEM_CHANGED, result.sales().get(0).outcome());
            assertEquals(ShopResult.SaleOutcome.INSUFFICIENT_QUANTITY, result.sales().get(1).outcome());
            assertTrue(result.sales().stream().noneMatch(ShopResult.Sale::dispatchStarted));
            assertEquals(5, fixture.quantity(InventoryType.ETC, 1));
            assertEquals(4, fixture.quantity(InventoryType.ETC, 2));
            assertEquals(100, fixture.actor.getMeso());
        }
    }

    @Test void rechargeablePartialQuantityIsRejectedAndExactWholeStackUsesNativeSettlement() throws Exception {
        try (var fixture = new Fixture(41)) {
            fixture.stock(InventoryType.USE, 1, 2070000, 100);
            fixture.open();
            var partial = fixture.sell(new SaleItem(InventoryType.USE, (short) 1, 2070000, 10));
            assertEquals(ShopResult.Outcome.REJECTED, partial.outcome());
            assertEquals(ShopResult.SaleOutcome.NATIVE_WHOLE_STACK_REQUIRED, partial.sales().getFirst().outcome());
            assertFalse(partial.sales().getFirst().dispatchStarted());
            assertEquals(100, fixture.quantity(InventoryType.USE, 1));
            assertEquals(100, fixture.actor.getMeso());

            var sold = fixture.sell(new SaleItem(InventoryType.USE, (short) 1, 2070000, 100));
            assertEquals(ShopResult.Outcome.SOLD, sold.outcome());
            assertNull(fixture.actor.getInventory(InventoryType.USE).getItem((short) 1));
            assertEquals(400, fixture.actor.getMeso());
            // Fixture.close intentionally closes the root while this module still owns the native shop.
        }
    }

    @Test void pauseAfterFirstSalePreservesThatFactAndClosesOnlyTheOwnedCharactersShop() throws Exception {
        try (var fixture = new Fixture(41)) {
            fixture.stock(InventoryType.ETC, 1, JUNK, 4);
            fixture.stock(InventoryType.ETC, 2, KEEP, 4);
            var otherClient = mock(Client.class);
            var other = Character.getDefault(otherClient);
            other.setShop(nativeShop);
            fixture.open();
            var stopping = new AtomicReference<CharacterRuntime.ModuleStop>();
            fixture.afterNativeSale = () -> {
                fixture.afterNativeSale = () -> {};
                stopping.set(fixture.runtime.stop(fixture.shopRef, CharacterRuntime.StopMode.PAUSE));
            };
            var result = fixture.sell(
                    new SaleItem(InventoryType.ETC, (short) 1, JUNK, 2),
                    new SaleItem(InventoryType.ETC, (short) 2, KEEP, 2));
            assertEquals(ShopResult.Outcome.PARTIAL, result.outcome());
            assertEquals(ShopResult.SaleOutcome.SOLD, result.sales().getFirst().outcome());
            assertTrue(result.cancelRequested());
            assertEquals(2, fixture.quantity(InventoryType.ETC, 1));
            assertEquals(4, fixture.quantity(InventoryType.ETC, 2));
            assertEquals(106, fixture.actor.getMeso());
            assertNotNull(stopping.get());
            assertEquals(ExecutionScope.DrainResult.DRAINED, stopping.get().await(Duration.ofSeconds(5)));
            assertNull(fixture.actor.getShop());
            assertSame(nativeShop, other.getShop());
            fixture.assertDrained();
        }
    }

    @Test void cancellingARepeatOpenDoesNotCloseAnEarlierCallersInteraction() throws Exception {
        try (var fixture = new Fixture(41)) {
            var original = fixture.open();
            var callerWork = fixture.caller.acceptWork();
            fixture.delayedSubmissionOrdinal = 1;
            var checking = CompletableFuture.supplyAsync(() -> {
                fixture.delayedSubmitter = Thread.currentThread();
                var observed = new AtomicReference<ShopResult>();
                fixture.caller.scope().runTracked(callerWork, () -> {
                    var call = fixture.caller.call(ShopActionModule.OPEN, new ShopOpenRequest(NPC_ID), callerWork);
                    assertEquals(ModuleCall.Status.SUCCEEDED, call.status());
                    observed.set(call.result());
                });
                return observed.get();
            });
            try {
                assertTrue(fixture.delayedSubmissionReached.await(5, TimeUnit.SECONDS));
                fixture.caller.cancel(callerWork);
                fixture.allowDelayedSubmission.countDown();

                var result = checking.get(5, TimeUnit.SECONDS);
                assertEquals(ShopResult.Outcome.CANCELLED, result.outcome());
                assertTrue(result.cancelRequested());
                assertFalse(result.nativeDispatched());
                assertSame(nativeShop, fixture.actor.getShop());
                var stillOpen = fixture.open();
                assertEquals(ShopResult.Outcome.ALREADY_OPEN, stillOpen.outcome());
                assertEquals(original.interactionId(), stillOpen.interactionId());
                assertEquals(ShopResult.Outcome.CLOSED, fixture.shop.close(original.interactionId()).outcome());
                assertNull(fixture.actor.getShop());
                fixture.assertDrained();
            } finally {
                fixture.allowDelayedSubmission.countDown();
            }
        }
    }

    @Test void anAcceptedButSkippedSecondSaleRetainsTheFirstSaleOnPause() throws Exception {
        try (var fixture = new Fixture(41)) {
            fixture.stock(InventoryType.ETC, 1, JUNK, 4);
            fixture.stock(InventoryType.ETC, 2, KEEP, 4);
            fixture.open();
            var selling = CompletableFuture.supplyAsync(() -> {
                fixture.delayedSubmitter = Thread.currentThread();
                return fixture.sell(
                        new SaleItem(InventoryType.ETC, (short) 1, JUNK, 2),
                        new SaleItem(InventoryType.ETC, (short) 2, KEEP, 2));
            });
            try {
                assertTrue(fixture.delayedSubmissionReached.await(5, TimeUnit.SECONDS));
                assertEquals(2, fixture.quantity(InventoryType.ETC, 1));
                assertEquals(106, fixture.actor.getMeso());
                var stopping = fixture.runtime.stop(fixture.shopRef, CharacterRuntime.StopMode.PAUSE);
                assertEquals(ExecutionScope.DrainResult.TIMED_OUT, stopping.await(Duration.ZERO));
                fixture.allowDelayedSubmission.countDown();

                var result = selling.get(5, TimeUnit.SECONDS);
                assertEquals(ShopResult.Outcome.PARTIAL, result.outcome());
                assertTrue(result.cancelRequested());
                var firstSale = result.sales().getFirst();
                assertEquals(ShopResult.SaleOutcome.SOLD, firstSale.outcome());
                assertEquals(4, firstSale.quantityBefore());
                assertEquals(2, firstSale.quantityAfter());
                assertEquals(100, firstSale.mesoBefore());
                assertEquals(106, firstSale.mesoAfter());
                assertEquals(2, fixture.quantity(InventoryType.ETC, 1));
                assertEquals(4, fixture.quantity(InventoryType.ETC, 2));
                assertEquals(106, fixture.actor.getMeso());
                assertTrue(result.sales().stream().filter(sale -> sale.request().slot() == 2)
                        .noneMatch(ShopResult.Sale::dispatchStarted));
                assertEquals(ExecutionScope.DrainResult.DRAINED, stopping.await(Duration.ofSeconds(5)));
                assertNull(fixture.actor.getShop());
                fixture.assertDrained();
            } finally {
                fixture.allowDelayedSubmission.countDown();
            }
        }
    }

    @Test void anOldSaleBatchCannotContinueIntoAReopenedViewOfTheSameCachedShop() throws Exception {
        try (var fixture = new Fixture(41)) {
            fixture.stock(InventoryType.ETC, 1, JUNK, 4);
            fixture.stock(InventoryType.ETC, 2, KEEP, 4);
            var first = fixture.open();
            var selling = CompletableFuture.supplyAsync(() -> {
                fixture.delayedSubmitter = Thread.currentThread();
                return fixture.sell(
                        new SaleItem(InventoryType.ETC, (short) 1, JUNK, 2),
                        new SaleItem(InventoryType.ETC, (short) 2, KEEP, 2));
            });
            try {
                assertTrue(fixture.delayedSubmissionReached.await(5, TimeUnit.SECONDS));
                assertEquals(2, fixture.quantity(InventoryType.ETC, 1));
                assertEquals(4, fixture.quantity(InventoryType.ETC, 2));
                assertEquals(106, fixture.actor.getMeso());
                assertEquals(ShopResult.Outcome.CLOSED, fixture.shop.close(first.interactionId()).outcome());
                var successor = fixture.open();
                assertEquals(ShopResult.Outcome.OPENED, successor.outcome());
                assertNotEquals(first.interactionId(), successor.interactionId());
                assertSame(nativeShop, fixture.actor.getShop(), "native factory reuses the same Shop object");
                fixture.allowDelayedSubmission.countDown();

                var result = selling.get(5, TimeUnit.SECONDS);
                assertEquals(ShopResult.Outcome.PARTIAL, result.outcome());
                assertEquals(ShopResult.SaleOutcome.SOLD, result.sales().getFirst().outcome());
                assertEquals(ShopResult.SaleOutcome.NOT_ATTEMPTED, result.sales().getLast().outcome());
                assertEquals("shop_interaction_changed", result.sales().getLast().reason());
                assertFalse(result.sales().getLast().dispatchStarted());
                assertEquals(4, fixture.quantity(InventoryType.ETC, 2));
                assertEquals(106, fixture.actor.getMeso());
                assertSame(nativeShop, fixture.actor.getShop());
            } finally {
                fixture.allowDelayedSubmission.countDown();
            }
        }
    }

    @Test void cleanupForAnOldInteractionCannotCloseTheSuccessorView() throws Exception {
        try (var fixture = new Fixture(41)) {
            var first = fixture.open();
            assertEquals(ShopResult.Outcome.CLOSED, fixture.shop.close(first.interactionId()).outcome());
            var second = fixture.open();
            assertEquals(ShopResult.Outcome.OPENED, second.outcome());
            assertNotEquals(first.interactionId(), second.interactionId());
            assertEquals(ShopResult.Outcome.REJECTED, fixture.shop.close(first.interactionId()).outcome());
            assertSame(nativeShop, fixture.actor.getShop());
        }
    }

    @Test void npcOutsideTheDeclaredOperationRangeDoesNotOpenAnInteraction() throws Exception {
        try (var fixture = new Fixture(41)) {
            fixture.actor.setPosition(new Point(201, 84));
            var result = fixture.open();
            assertEquals(ShopResult.Outcome.REJECTED, result.outcome());
            assertFalse(result.nativeDispatched());
            assertNull(fixture.actor.getShop());
            fixture.assertDrained();
        }
    }

    private final class Fixture implements AutoCloseable {
        final Client client;
        final Character actor;
        final CharacterRuntime runtime;
        final CharacterRuntime.ModuleContext caller;
        final ThreadPoolExecutor executor;
        final ShopActionModule shop;
        CharacterRuntime.ModuleRef shopRef;
        Runnable afterNativeSale = () -> {};
        volatile Thread delayedSubmitter;
        volatile int delayedSubmissionOrdinal = 2;
        final AtomicInteger delayedSubmissions = new AtomicInteger();
        final CountDownLatch delayedSubmissionReached = new CountDownLatch(1);
        final CountDownLatch allowDelayedSubmission = new CountDownLatch(1);

        Fixture(int id) throws Exception {
            client = mock(Client.class);
            when(client.getChannel()).thenReturn(1);
            when(client.isLoggedIn()).thenReturn(true);
            actor = spy(Character.getDefault(client));
            actor.setId(id);
            actor.setName("Shop" + id);
            when(client.getPlayer()).thenReturn(actor);
            var map = mock(MapleMap.class);
            when(map.getId()).thenReturn(100000102);
            when(map.getCharacterById(id)).thenReturn(actor);
            var npc = new NPC(NPC_ID, mock(NPCStats.class));
            npc.setObjectId(7101);
            npc.setPosition(new Point(100, 84));
            when(map.getNPCById(NPC_ID)).thenReturn(npc);
            when(map.getMapObject(7101)).thenReturn(npc);
            when(map.getMapObjects()).thenReturn(List.of(npc));
            actor.setMap(map);
            actor.setMapId(100000102);
            actor.setPosition(new Point(100, 84));
            actor.setMapTransitionComplete();
            actor.gainMeso(100, false);
            doAnswer(call -> { call.callRealMethod(); afterNativeSale.run(); return null; })
                    .when(actor).gainMeso(anyInt(), eq(false));
            players.addPlayer(actor);
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), command ->
                    new Thread(() -> {
                        try (var singleton = mockStatic(Server.class);
                             var metadata = mockStatic(ItemInformationProvider.class);
                             var factory = mockStatic(ShopFactory.class)) {
                            singleton.when(Server::getInstance).thenReturn(server);
                            metadata.when(ItemInformationProvider::getInstance).thenReturn(items);
                            factory.when(ShopFactory::getInstance).thenReturn(shops);
                            command.run();
                        }
                    })) {
                @Override public void execute(Runnable command) {
                    // Delay only the old caller before acceptance by the actual executor FIFO.
                    // The native lane remains free for other calls and owned cleanup.
                    if (Thread.currentThread() == delayedSubmitter && delayedSubmissions.incrementAndGet() == delayedSubmissionOrdinal) {
                        delayedSubmissionReached.countDown();
                        try {
                            if (!allowDelayedSubmission.await(5, TimeUnit.SECONDS)) {
                                throw new RejectedExecutionException("shop_delayed_submission_timeout");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new RejectedExecutionException(interrupted);
                        }
                    }
                    super.execute(command);
                }
            };
            runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8, 1,
                    new ExecutionScope.Limits(16, 2, 16, 2)), executor);
            var ref = new GameContext.CharacterRef(runtime.acceptedEpoch(), actor, client, world, channel);
            var binding = new RoleBinding() {
                @Override public String kind() { return "shop-test"; }
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
                runtime.reserveOrigin(opening).adopt(binding);
                runtime.bindAdoptedOrigin();
            }, () -> {});
            opening.completion().toCompletableFuture().join();
            caller = runtime.mount(McpControlModule.DEFINITION,
                    new ModuleSelection<>("mcp", Map.of(), true)).instance().context();
            var definition = new ModuleDefinition<ShopActionModule.Config, ShopActionModule>(
                    ShopActionModule.DEFINITION.typeId(), ShopActionModule.DEFINITION.controls(),
                    ShopActionModule.DEFINITION.limits(), ignored -> new ShopActionModule(server),
                    ShopActionModule.DEFINITION.exports());
            var mounted = runtime.mount(definition,
                    new ModuleSelection<>("shop", ShopActionModule.DEFAULT_CONFIG, true));
            shop = mounted.instance();
            shopRef = mounted.reference();
        }

        void stock(InventoryType type, int slot, int id, int quantity) {
            actor.getInventory(type).addItemFromDB(new Item(id, (short) slot, (short) quantity));
        }

        int quantity(InventoryType type, int slot) {
            var item = actor.getInventory(type).getItem((short) slot);
            return item == null ? 0 : item.getQuantity();
        }

        ShopResult open() {
            var result = caller.call(ShopActionModule.OPEN, new ShopOpenRequest(NPC_ID));
            assertEquals(ModuleCall.Status.SUCCEEDED, result.status());
            return result.result();
        }

        ShopResult sell(SaleItem... entries) {
            var result = caller.call(ShopActionModule.SELL, new ShopSellRequest(List.of(entries)));
            assertEquals(ModuleCall.Status.SUCCEEDED, result.status());
            return result.result();
        }

        void assertDrained() {
            assertEquals(0, runtime.snapshot().lane().pending());
            var module = runtime.snapshot().modules().stream().filter(value -> value.instanceId().equals("shop"))
                    .findFirst().orElseThrow();
            if (module.scope() != null) {
                assertEquals(0, module.scope().acceptedWork());
                assertEquals(0, module.scope().runningBodies());
                assertEquals(0, module.scope().outstandingResources());
            }
        }

        @Override public void close() throws Exception {
            try {
                runtime.beginClose(Optional.empty());
                runtime.whenModulesDrained().toCompletableFuture().get(5, TimeUnit.SECONDS);
                var cleanup = runtime.acquireOriginCleanup();
                runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {});
                assertEquals(ExecutionScope.DrainResult.DRAINED, runtime.awaitClosed(Duration.ofSeconds(5)));
                assertNull(actor.getShop());
            } finally {
                players.removePlayer(actor.getId());
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
