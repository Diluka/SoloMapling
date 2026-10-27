package soloMapling.beidou;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.ChatLogger;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.AcceptanceClient;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import soloMapling.game.action.ChatEvidence;
import soloMapling.game.action.ChatEvidenceSource;
import soloMapling.game.action.NativeActionResult;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real BotClient/Character/storage/Map/handler and observer transport with substituted host routing,
 * native clock and logger. A manual serial executor keeps static routing fixtures on the test thread.
 * No datasource, socket, map entry script, V2 product runtime or live game server is started.
 */
class BotNativeActionsTest {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_692_000_000);
    private final List<ExecutionScope> scopes = new ArrayList<>();
    private final List<ExecutionScope.WorkHandle> work = new ArrayList<>();
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private BotClient botClient;
    private Character bot;
    private AcceptanceClient observerClient;
    private Character observer;
    private MapleMap map;
    private GameContext.SceneRef observerScene;
    private BotGameContexts.Binding binding;
    private GameContext.SceneRef botScene;
    private ManualExecutor executor;
    private CharacterCommandLane lane;
    private BotNativeActions actions;
    private MockedStatic<Server> singleton;
    private MockedStatic<DatabaseConnection> database;
    private MockedStatic<ChatLogger> chatLogger;

    @BeforeEach
    void nativeBotAndObserverWithoutDatasource() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        worldPlayers = new PlayerStorage();
        channelPlayers = new PlayerStorage();
        when(server.isOnline()).thenReturn(true);
        when(server.getCurrentTime()).thenReturn(1000L);
        when(server.getWorld(0)).thenReturn(world);
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(world.getId()).thenReturn(0);
        when(world.getChannel(1)).thenReturn(channel);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getId()).thenReturn(1);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        singleton = mockStatic(Server.class);
        singleton.when(Server::getInstance).thenReturn(server);
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenAnswer(call -> {
            throw new AssertionError("bot action fixtures must not open JDBC");
        });
        chatLogger = mockStatic(ChatLogger.class);

        map = new MapleMap(10000, 0, 1, 10000, 1f);
        botClient = new BotClient(0, 1);
        botClient.setLanguage(3);
        bot = addCharacter(botClient, "NativeActionBot");
        BotRegistry.register(bot);
        observerClient = new AcceptanceClient(Client.Type.CHANNEL, -913L, "127.0.0.113", world, channel);
        observerClient.setLanguage(3);
        observer = addCharacter(observerClient, "ActionObserver");
        binding = BotGameContexts.capture(server, bot, botClient, world, channel);
        botScene = BotGameContexts.captureScene(server, binding);
        observerScene = NativeContextChecks.captureScene(server,
                NativeContextChecks.captureCharacter(server, observer, observerClient, world, channel));
        executor = new ManualExecutor(8);
        lane = new CharacterCommandLane(8, 1, executor);
        actions = new BotNativeActions(new NativeActionService());
        assertBotStillRegistered();
    }

    @AfterEach
    void releaseOnlyOwnedFixtures() {
        try {
            scopes.forEach(ExecutionScope::beginDrain);
            // A rejected preparation has no carrier to retire its already accepted work.
            work.stream().filter(item -> !item.snapshot().carrierClaimed()).forEach(ExecutionScope.WorkHandle::reject);
            if (executor != null) executor.runAll();
            for (ExecutionScope scope : scopes) {
                assertEquals(ExecutionScope.DrainResult.DRAINED, scope.awaitDrained(Duration.ZERO));
            }
        } finally {
            try {
                if (lane != null) lane.close();
                if (executor != null) executor.shutdown();
            } finally {
                BotRegistry.unregister(bot);
                WorldEpoch.onWorldStopping();
                ThreadLocalUtil.removeCurrentClient();
                try {
                    if (database != null) database.verifyNoInteractions();
                } finally {
                    try { if (database != null) database.close(); }
                    finally {
                        try { if (chatLogger != null) chatLogger.close(); }
                        finally { if (singleton != null) singleton.close(); }
                    }
                }
            }
        }
    }

    @Test
    void botWithoutReceiveHistoryKeepsRealBroadcastOutcomeUnknown() throws Exception {
        ExecutionScope scope = scope();
        NativeActionService.ActionHandle handle = prepare(scope, "没有自身收包历史", null);
        actions.submitChat(handle);
        assertEquals(1, executor.size());
        assertFalse(handle.result().toCompletableFuture().isDone());
        assertEquals(0, bot.getAutoBanManager().getLastSpam(7));

        executor.runAll();

        NativeActionResult result = result(handle);
        assertEquals(NativeActionResult.Outcome.INDETERMINATE, result.outcome());
        assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch());
        assertEquals(ChatEvidence.Source.NONE, result.evidence().source());
        assertFalse(result.evidence().matched());
        assertFalse(result.handlerError());
        assertEquals(1, observerPackets().size(), "the real native map still broadcasts to its observer");
        assertEquals("没有自身收包历史", observerPackets().getFirst().get("text"));
        assertEquals(1000L, bot.getAutoBanManager().getLastSpam(7));
        assertBotStillRegistered();
    }

    @Test
    void realNativeMapBroadcastToSeparateObserverIsExplicitObserverEvidence() throws Exception {
        Client previous = mock(Client.class);
        ThreadLocalUtil.setCurrentClient(previous);
        String text = "机器人原生中文广播";
        NativeActionService.ActionHandle handle = prepare(scope(), text, observerEvidence(null));

        actions.submitChat(handle);
        executor.runAll();

        NativeActionResult result = result(handle);
        assertEquals(NativeActionResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch());
        assertEquals(bot.getId(), result.senderCharacterId());
        assertEquals(ChatEvidence.Source.OBSERVER_NATIVE_CHAT_PACKET, result.evidence().source());
        assertTrue(result.evidence().matched());
        assertFalse(result.handlerError());
        assertEquals(0L, result.evidence().cursorBefore());
        assertEquals(1L, result.evidence().matchedSequence());
        assertEquals(0xA2, observerPackets().getFirst().get("opcode"));
        assertEquals(bot.getId(), observerPackets().getFirst().get("sender_character_id"));
        assertEquals(text, observerPackets().getFirst().get("text"));
        assertSame(previous, ThreadLocalUtil.getCurrentClient());
        assertFalse(result.toString().contains(text));
        assertBotStillRegistered();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sender", "text", "show", "truncated"})
    void unrelatedOrIncompleteObserverPacketCannotProveTheBotAction(String mismatch) throws Exception {
        NativeActionService.ActionHandle handle = prepare(scope(), "必须精确匹配本次发言", observerEvidence(mismatch));
        actions.submitChat(handle);
        executor.runAll();

        NativeActionResult result = result(handle);
        assertEquals(NativeActionResult.Outcome.INDETERMINATE, result.outcome());
        assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch());
        assertEquals(ChatEvidence.Source.OBSERVER_NATIVE_CHAT_PACKET, result.evidence().source());
        assertFalse(result.evidence().matched());
        assertFalse(result.handlerError());
        assertEquals(1, observerPackets().size(), "the source adapter altered evidence after a real native broadcast");
        assertEquals("必须精确匹配本次发言", observerPackets().getFirst().get("text"));
        assertEquals(1000L, bot.getAutoBanManager().getLastSpam(7));
        assertBotStillRegistered();
    }

    @Test
    void matchingHistoricalObserverPacketIsNotEvidenceForANewMutedChat() throws Exception {
        String text = "historical native echo";
        observerClient.sendPacket(PacketCreator.getChatText(bot.getId(), text, false, 0));
        assertEquals(text, observerPackets().getFirst().get("text"));
        map.setMuted(true);
        NativeActionService.ActionHandle handle = prepare(scope(), text, observerEvidence(null));

        actions.submitChat(handle);
        executor.runAll();

        NativeActionResult result = result(handle);
        assertEquals(NativeActionResult.Outcome.INDETERMINATE, result.outcome());
        assertEquals(1L, result.evidence().cursorBefore());
        assertFalse(result.evidence().matched());
        assertFalse(result.handlerError());
        assertEquals(1, observerPackets().size());
        assertEquals(0, bot.getAutoBanManager().getLastSpam(7));
        assertBotStillRegistered();
    }

    @Test
    void observerLeavingTheCapturedSceneCannotSupplyEvidence() throws Exception {
        NativeActionService.ActionHandle handle = prepare(scope(), "观察者已经离场", observerEvidence(null));
        actions.submitChat(handle);
        members(map).remove(observer);
        MapleMap other = new MapleMap(10001, 0, 1, 10001, 1f);
        observer.setMap(other);
        observer.setMapId(other.getId());
        members(other).add(observer);

        executor.runAll();

        NativeActionResult result = result(handle);
        assertEquals(NativeActionResult.Outcome.INDETERMINATE, result.outcome());
        assertEquals(NativeActionResult.Dispatch.STARTED, result.dispatch());
        assertFalse(result.evidence().matched());
        assertFalse(result.handlerError());
        assertEquals(0, observerPackets().size());
        assertEquals(1000L, bot.getAutoBanManager().getLastSpam(7));
        assertBotStillRegistered();
    }

    @Test
    void botRegistryOwnershipIsCheckedAgainWhenTheCommandActuallyRuns() throws Exception {
        NativeActionService.ActionHandle handle = prepare(scope(), "旧bot归属不可再派发", observerEvidence(null));
        actions.submitChat(handle);
        BotRegistry.unregister(bot);

        assertThrows(GameContext.Invalid.class, executor::runAll);

        NativeActionResult result = result(handle);
        assertNotEquals(NativeActionResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, result.dispatch());
        assertEquals(0, observerPackets().size());
        assertEquals(0, bot.getAutoBanManager().getLastSpam(7));
        assertSame(bot, map.getCharacterById(bot.getId()));
        assertFalse(BotRegistry.isRegistered(bot));
    }

    @Test
    void queuedActionDoesNotReviveAfterWorldEpochChanges() throws Exception {
        NativeActionService.ActionHandle handle = prepare(scope(), "旧世界不能复活动作", observerEvidence(null));
        actions.submitChat(handle);
        WorldEpoch.onWorldStopping();
        WorldEpoch.onWorldStarted();

        executor.runAll();

        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, result(handle).dispatch());
        assertNotEquals(NativeActionResult.Outcome.SUCCEEDED, result(handle).outcome());
        assertEquals(0, observerPackets().size());
        assertEquals(0, bot.getAutoBanManager().getLastSpam(7));
        assertBotStillRegistered();
    }

    @Test
    void unloadingOneModuleLeavesTheBotAndOtherModuleChatActive() throws Exception {
        ExecutionScope moduleA = scope();
        ExecutionScope moduleB = scope();
        NativeActionService.ActionHandle a = prepare(moduleA, "已取消的模块A", observerEvidence(null));
        NativeActionService.ActionHandle b = prepare(moduleB, "仍然生效的模块B", observerEvidence(null));
        actions.submitChat(a);
        actions.submitChat(b);
        moduleA.beginDrain();

        executor.runAll();

        assertEquals(NativeActionResult.Outcome.CANCELLED, result(a).outcome());
        assertEquals(NativeActionResult.Dispatch.NOT_STARTED, result(a).dispatch());
        assertEquals(NativeActionResult.Outcome.SUCCEEDED, result(b).outcome());
        assertEquals(ExecutionScope.DrainResult.DRAINED, moduleA.awaitDrained(Duration.ZERO));
        assertTrue(moduleB.isActive());
        assertEquals(1, observerPackets().size());
        assertEquals("仍然生效的模块B", observerPackets().getFirst().get("text"));
        assertFalse(executor.isShutdown(), "a module does not own the borrowed executor");
        assertBotStillRegistered();
    }

    private NativeActionService.ActionHandle prepare(ExecutionScope scope, String text, ChatEvidenceSource evidence) {
        CharacterCommandLane.Grant grant = lane.declareGrant(scope);
        ExecutionScope.WorkHandle accepted = scope.acceptWork();
        work.add(accepted);
        NativeChatAction action = new NativeChatAction(text);
        return evidence == null ? actions.prepareChat(server, binding, lane, grant, accepted, botScene, action)
                : actions.prepareChat(server, binding, lane, grant, accepted, botScene, action, evidence);
    }

    private ChatEvidenceSource observerEvidence(String mismatch) {
        return ChatEvidenceSource.packets(ChatEvidence.Source.OBSERVER_NATIVE_CHAT_PACKET, "observer:" + observer.getId(),
                new ChatEvidenceSource.PacketFeed() {
                    @Override public void requireCurrent() {
                        NativeContextChecks.requireSceneCurrent(server, observerScene);
                        if (observerScene.map() != botScene.map() || observerScene.actor().world() != botScene.actor().world()
                                || observerScene.actor().channel() != botScene.actor().channel()
                                || observerScene.memberEim() != botScene.memberEim()
                                || observerScene.mapEim() != botScene.mapEim()) {
                            throw new GameContext.Invalid(GameContext.Failure.SCENE_CHANGED);
                        }
                    }
                    @Override public long latestCursor() {
                        requireCurrent();
                        long cursor = observerClient.latestPacketSequence();
                        requireCurrent();
                        return cursor;
                    }
                    @Override public Map<String, Object> window(long sinceCursor, int limit) {
                        requireCurrent();
                        Map<String, Object> value = observerClient.packetEvents(sinceCursor, limit);
                        requireCurrent();
                        if (mismatch == null) return value;
                        Map<String, Object> window = new LinkedHashMap<>(value);
                        List<Map<String, Object>> altered = new ArrayList<>();
                        for (Map<String, Object> original : observerPackets(value)) {
                            Map<String, Object> item = new LinkedHashMap<>(original);
                            switch (mismatch) {
                                case "sender" -> item.put("sender_character_id", bot.getId() + 1);
                                case "text" -> item.put("text", "另一条发言");
                                case "show" -> item.put("show", 1);
                                case "truncated" -> item.put("text_truncated", true);
                                default -> throw new AssertionError("unknown test mismatch");
                            }
                            altered.add(item);
                        }
                        window.put("items", altered);
                        return window;
                    }
                });
    }

    private ExecutionScope scope() {
        ExecutionScope scope = new ExecutionScope(WorldEpoch.snapshot().token(),
                new ExecutionScope.OwnerStamp(new Object(), new Object()), new ExecutionScope.Limits(8, 0, 8, 0));
        scopes.add(scope);
        return scope;
    }

    private Character addCharacter(Client client, String name) throws Exception {
        Character character = Character.getDefault(client);
        character.setId(NEXT_ID.incrementAndGet());
        character.setName(name + character.getId());
        character.setWorld(0);
        client.setPlayer(character);
        character.setMap(map);
        character.setMapId(map.getId());
        character.setCanRecvPartySearchInvite(false);
        character.setEnteredChannelWorld();
        character.setAutoBanManager(new AutobanManager(character));
        members(map).add(character); // Fixture assembly only; no map scripts or background timers.
        worldPlayers.addPlayer(character);
        channelPlayers.addPlayer(character);
        return character;
    }

    private void assertBotStillRegistered() {
        assertTrue(BotRegistry.isRegistered(bot));
        assertSame(botClient, bot.getClient());
        assertSame(bot, botClient.getPlayer());
        assertSame(bot, map.getCharacterById(bot.getId()));
        assertSame(bot, worldPlayers.getCharacterById(bot.getId()));
        assertSame(bot, channelPlayers.getCharacterById(bot.getId()));
        assertFalse(bot.isLoggedIn());
        assertFalse(bot.isLoggedInWorld());
        assertFalse(bot.isAwayFromWorld());
        assertTrue(botClient.isLoggedIn());
    }

    private static NativeActionResult result(NativeActionService.ActionHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private List<Map<String, Object>> observerPackets() { return observerPackets(observerClient.packetEvents(0, 500)); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> observerPackets(Map<String, Object> window) {
        return (List<Map<String, Object>>) window.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Collection<Character> members(MapleMap map) throws Exception {
        Field field = MapleMap.class.getDeclaredField("characters");
        field.setAccessible(true);
        return (Collection<Character>) field.get(map);
    }

    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        private final ArrayBlockingQueue<Runnable> queue;
        private boolean shutdown;
        private boolean running;
        ManualExecutor(int capacity) { queue = new ArrayBlockingQueue<>(capacity); }
        @Override public int commandQueueCapacity() { return queue.size() + queue.remainingCapacity(); }
        @Override public void execute(Runnable command) {
            if (shutdown || !queue.offer(command)) throw new RejectedExecutionException("manual_executor_full");
        }
        int size() { return queue.size(); }
        void runAll() {
            if (running) throw new IllegalStateException("manual_executor_reentry");
            Runnable next;
            while ((next = queue.poll()) != null) {
                running = true;
                try { next.run(); }
                finally { running = false; }
            }
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { throw new UnsupportedOperationException("accepted_carriers_must_run"); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty() && !running; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
