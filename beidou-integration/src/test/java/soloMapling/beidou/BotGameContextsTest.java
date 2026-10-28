package soloMapling.beidou;

import soloMapling.game.execution.ExecutionScope;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.scripting.event.EventManager;
import org.gms.server.CashShop;
import org.gms.server.maps.MapleMap;
import org.gms.server.quest.medal.DynamicHairMedal;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Native Character/BotClient/storage/map references, with explicitly substituted host routing.
 * Map/EIM member collections are fixture-only assembly: no login, map script, timer or V2 module runs.
 */
class BotGameContextsTest {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_690_000_000);
    private final List<Character> fixtures = new ArrayList<>();
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private BotClient client;
    private Character bot;
    private MapleMap map;
    private MockedStatic<Server> singleton;
    private MockedStatic<DatabaseConnection> database;

    @BeforeEach
    void buildNativeRelationshipsWithoutDatasourceOrAcceptanceRuntime() throws Exception {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        worldPlayers = spy(new PlayerStorage());
        channelPlayers = spy(new PlayerStorage());
        when(server.isOnline()).thenReturn(true);
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
        database.when(DatabaseConnection::getConnection).thenAnswer(invocation -> {
            throw new AssertionError("context fixtures must not open JDBC");
        });
        client = new BotClient(0, 1);
        bot = nativeBot(client, NEXT_ID.incrementAndGet());
        map = new MapleMap(10000, 0, 1, 10000, 1f);
        install(bot, map);
        bot.setCanRecvPartySearchInvite(false);
        bot.setEnteredChannelWorld(); // Actual V1 enter hook only clears away; loggedIn stays false.
        BotRegistry.register(bot);
        singleton.clearInvocations();
    }

    @AfterEach
    void releaseOnlyThisTestObjects() {
        fixtures.forEach(BotRegistry::unregister);
        ExecutionScope.tryBeginWorldStop();
        if (database != null) {
            database.verifyNoInteractions();
            database.close();
        }
        if (singleton != null) singleton.close();
    }

    @Test
    void realV1FlagsRemainFalseWhileAnExplicitlyRegisteredHeadlessBindingIsUsable() {
        assertFalse(bot.isLoggedIn());
        assertFalse(bot.isAwayFromWorld());
        assertFalse(bot.isLoggedInWorld());
        assertTrue(client.isLoggedIn());
        assertSame(bot, worldPlayers.getCharacterById(bot.getId()));
        assertSame(bot, channelPlayers.getCharacterById(bot.getId()));
        assertSame(bot, map.getCharacterById(bot.getId()));
        BotGameContexts.Binding binding = bind();
        GameContext.SceneRef scene = BotGameContexts.captureScene(server, binding);
        assertSame(client, binding.characterRef().client());
        assertSame(bot, binding.characterRef().character());
        assertSame(map, scene.map());
        assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, binding));
        assertDoesNotThrow(() -> BotGameContexts.requireSceneCurrent(server, binding, scene));
        assertFalse(bot.isLoggedIn());
        assertFalse(bot.isLoggedInWorld());
        singleton.verifyNoInteractions();
        verify(server, never()).getWorlds();
        verify(world, never()).getChannels();
        verify(worldPlayers, never()).getAllCharacters();
        verify(channelPlayers, never()).getAllCharacters();
        verify(channel, never()).getMapFactory();
    }

    @Test
    void actualTemplateConversionCanBeBoundWithoutChannelLoginOrCashDatabaseLoading() throws Exception {
        AccountService accounts = ServerManager.getApplicationContext().getBean(AccountService.class);
        AccountsDO account = AccountsDO.builder().id(731).name("template_account").characterslots(3).language(3).build();
        when(accounts.findById(731)).thenReturn(account);
        CharacterService characters = ServerManager.getApplicationContext().getBean(CharacterService.class);
        BotClient templateClient = new BotClient(0, 1);
        CharactersDO row = emptyNativeRow(NEXT_ID.incrementAndGet());
        when(characters.findById(row.getId())).thenReturn(row);
        when(characters.loadCharFromDB(row.getId(), templateClient, false)).thenCallRealMethod();
        Character template;
        // fromCharactersDO performs service lookups; the existing context supplies empty in-memory services.
        // CashShop directly loads JDBC inventory; the hair callback loads unrelated WZ quest data.
        // Replace these two loading boundaries without changing the native Character or login flags.
        try (MockedConstruction<CashShop> cash = mockConstruction(CashShop.class);
             MockedStatic<DynamicHairMedal> hair = mockStatic(DynamicHairMedal.class)) {
            template = characters.loadCharFromDB(row.getId(), templateClient, false);
            assertEquals(1, cash.constructed().size());
        }
        fixtures.add(template);
        templateClient.setPlayer(template);
        install(template, new MapleMap(10000, 0, 1, 10000, 1f));
        template.setEnteredChannelWorld();
        BotRegistry.register(template);
        assertFalse(template.isLoggedIn());
        assertFalse(template.isLoggedInWorld());
        assertTrue(templateClient.isLoggedIn());
        BotGameContexts.Binding binding = BotGameContexts.capture(server, template, templateClient, world, channel);
        assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, binding));
        assertFalse(template.isLoggedIn());
    }

    @Test
    void unregisteredBotClientAndOrdinaryClientDoNotObtainBotAuthority() {
        BotRegistry.unregister(bot);
        assertTrue(BotRegistry.isBot(bot));
        invalid(GameContext.Failure.CHARACTER_CHANGED, this::bind);
        Client ordinary = new Client(Client.Type.CHANNEL, -617L, "127.0.0.117", null, 0, 1);
        bot.setClient(ordinary);
        ordinary.setPlayer(bot);
        assertThrows(IllegalArgumentException.class, () -> BotRegistry.register(bot));
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.capture(server, bot, client, world, channel));
        assertFalse(BotRegistry.isRegistered(bot));
    }

    @Test
    void unregisteringAndReplacingTheSameCidInvalidatesOnlyTheOldExactObject() throws Exception {
        BotGameContexts.Binding first = bind();
        BotRegistry.unregister(bot);
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, first));
        BotClient nextClient = new BotClient(0, 1);
        Character next = nativeBot(nextClient, bot.getId());
        members(map).remove(bot);
        install(next, map);
        BotRegistry.register(next);
        assertFalse(BotRegistry.isRegistered(bot));
        assertTrue(BotRegistry.isRegistered(next));
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, first));
        BotGameContexts.Binding second = BotGameContexts.capture(server, next, nextClient, world, channel);
        assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, second));
        assertSame(bot, first.characterRef().character());
    }

    @Test
    void replacingClientOrClientPlayerInvalidatesTheCapturedPairWithoutRefreshingIt() {
        BotGameContexts.Binding binding = bind();
        BotClient next = new BotClient(0, 1);
        bot.setClient(next);
        next.setPlayer(bot);
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        assertSame(client, binding.characterRef().client());
        bot.setClient(client);
        client.setPlayer(Character.getDefault(client));
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
    }

    @Test
    void duplicateStorageCidCannotSubstituteForTheRegisteredCharacterObject() {
        BotGameContexts.Binding binding = bind();
        Character replacement = nativeBot(new BotClient(0, 1), bot.getId());
        worldPlayers.addPlayer(replacement);
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        worldPlayers.addPlayer(bot);
        channelPlayers.addPlayer(replacement);
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        assertTrue(BotRegistry.isRegistered(bot));
    }

    @Test
    void missingStorageMapOrMapMembershipCannotBeRepairedByTheAdapter() throws Exception {
        BotGameContexts.Binding binding = bind();
        when(channel.getPlayerStorage()).thenReturn(null);
        invalid(GameContext.Failure.NOT_IN_WORLD, () -> BotGameContexts.requireCurrent(server, binding));
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        members(map).remove(bot);
        invalid(GameContext.Failure.NOT_IN_WORLD, () -> BotGameContexts.requireCurrent(server, binding));
        assertNull(map.getCharacterById(bot.getId()));
        members(map).add(bot);
        bot.setMap((MapleMap) null);
        invalid(GameContext.Failure.NOT_IN_WORLD, () -> BotGameContexts.requireCurrent(server, binding));
        assertNull(bot.getMap());
    }

    @Test
    void completedMapChangeKeepsCharacterBindingButRejectsOldSceneEvenWithSameMapId() throws Exception {
        BotGameContexts.Binding binding = bind();
        GameContext.SceneRef first = BotGameContexts.captureScene(server, binding);
        MapleMap next = new MapleMap(map.getId(), 0, 1, 10000, 1f);
        members(map).remove(bot);
        install(bot, next);
        assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, binding));
        invalid(GameContext.Failure.SCENE_CHANGED, () -> BotGameContexts.requireSceneCurrent(server, binding, first));
        GameContext.SceneRef second = BotGameContexts.captureScene(server, binding);
        assertSame(next, second.map());
        assertSame(binding.characterRef(), second.actor());
        assertDoesNotThrow(() -> BotGameContexts.requireSceneCurrent(server, binding, second));
    }

    @Test
    void worldChannelAndClientRouteMustRemainTheCapturedObjectsAndIds() {
        BotGameContexts.Binding binding = bind();
        when(server.getWorld(0)).thenReturn(mock(World.class));
        invalid(GameContext.Failure.WORLD_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(mock(Channel.class));
        invalid(GameContext.Failure.WORLD_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        when(world.getChannel(1)).thenReturn(channel);
        client.setChannel(2);
        invalid(GameContext.Failure.WORLD_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        client.setChannel(1);
        client.setWorld(1);
        invalid(GameContext.Failure.WORLD_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        client.setWorld(0);
        bot.setWorld(1);
        invalid(GameContext.Failure.WORLD_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
    }

    @Test
    void oldBindingNeverAcquiresANewEpochEvenWhenNativeObjectsAreReused() {
        BotGameContexts.Binding old = bind();
        WorldEpoch.Token token = old.characterRef().epoch();
        ExecutionScope.tryBeginWorldStop();
        invalid(GameContext.Failure.WORLD_OFFLINE, () -> BotGameContexts.requireCurrent(server, old));
        WorldEpoch.onWorldStarted();
        invalid(GameContext.Failure.WORLD_CHANGED, () -> BotGameContexts.requireCurrent(server, old));
        assertSame(token, old.characterRef().epoch());
        BotGameContexts.Binding next = bind();
        assertNotSame(token, next.characterRef().epoch());
        assertNotEquals(token.value(), next.characterRef().epoch().value());
        assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, next));
    }

    @Test
    void eventMembersAndMapEventsAreDistinctAndTheirFailureDoesNotBlockCharacterIdentity() throws Exception {
        BotGameContexts.Binding binding = bind();
        EventInstanceManager member = event("member");
        EventInstanceManager mapEvent = event("map");
        eventMembers(member).put(bot.getId(), bot);
        bot.setEventInstance(member);
        map.setEventInstance(mapEvent);
        GameContext.SceneRef scene = BotGameContexts.captureScene(server, binding);
        assertSame(member, scene.memberEim());
        assertSame(mapEvent, scene.mapEim());
        assertDoesNotThrow(() -> BotGameContexts.requireSceneCurrent(server, binding, scene));
        eventMembers(member).remove(bot.getId());
        invalid(GameContext.Failure.EVENT_CHANGED, () -> BotGameContexts.requireSceneCurrent(server, binding, scene));
        eventMembers(member).put(bot.getId(), bot);
        synchronized (member) { field(EventInstanceManager.class, "disposed").setBoolean(member, true); }
        invalid(GameContext.Failure.EVENT_CHANGED, () -> BotGameContexts.requireSceneCurrent(server, binding, scene));
        assertDoesNotThrow(() -> BotGameContexts.requireCurrent(server, binding));
        synchronized (member) { field(EventInstanceManager.class, "disposed").setBoolean(member, false); }
        map.setEventInstance(event("replacement"));
        invalid(GameContext.Failure.EVENT_CHANGED, () -> BotGameContexts.requireSceneCurrent(server, binding, scene));
    }

    @Test
    void sceneForAnotherCapturedBindingCannotBorrowThisBindingAuthority() {
        BotGameContexts.Binding first = bind();
        BotGameContexts.Binding second = bind();
        GameContext.SceneRef scene = BotGameContexts.captureScene(server, first);
        invalid(GameContext.Failure.SCENE_CHANGED, () -> BotGameContexts.requireSceneCurrent(server, second, scene));
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, null));
        invalid(GameContext.Failure.SCENE_CHANGED, () -> BotGameContexts.requireSceneCurrent(server, first, null));
    }

    @Test
    void registryIsRecheckedAfterNativeReadsWithoutRecapturingBinding() {
        BotGameContexts.Binding binding = bind();
        when(server.getWorld(0)).thenAnswer(invocation -> { BotRegistry.unregister(bot); return world; });
        invalid(GameContext.Failure.CHARACTER_CHANGED, () -> BotGameContexts.requireCurrent(server, binding));
        assertSame(bot, binding.characterRef().character());
    }

    @Test
    void nativeReadFailuresRemainFixedAndDoNotUnregisterOrRepairTheBot() {
        BotGameContexts.Binding binding = bind();
        when(server.getWorld(0)).thenThrow(new IllegalStateException("private native message"));
        GameContext.Invalid failure = invalid(GameContext.Failure.READ_UNAVAILABLE,
                () -> BotGameContexts.requireCurrent(server, binding));
        assertFalse(failure.getMessage().contains("private"));
        assertNull(failure.getCause());
        assertTrue(BotRegistry.isRegistered(bot));
        assertSame(bot, worldPlayers.getCharacterById(bot.getId()));
    }

    private BotGameContexts.Binding bind() {
        return BotGameContexts.capture(server, bot, client, world, channel);
    }

    private Character nativeBot(BotClient transport, int id) {
        Character character = Character.getDefault(transport);
        character.setId(id);
        character.setName("CtxBot" + id);
        character.setWorld(0);
        transport.setPlayer(character);
        fixtures.add(character);
        assertFalse(character.isLoggedIn());
        return character;
    }

    private void install(Character character, MapleMap target) throws Exception {
        character.setMap(target);
        character.setMapId(target.getId());
        members(target).add(character);
        worldPlayers.addPlayer(character);
        channelPlayers.addPlayer(character);
    }

    private EventInstanceManager event(String name) {
        EventManager manager = mock(EventManager.class);
        when(manager.getWorldServer()).thenReturn(world);
        when(manager.getChannelServer()).thenReturn(channel);
        return new EventInstanceManager(manager, name);
    }

    private static CharactersDO emptyNativeRow(int id) throws Exception {
        CharactersDO row = new CharactersDO();
        for (Field field : CharactersDO.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getType() == Integer.class) field.set(row, 0);
            if (field.getType() == Long.class) field.set(row, 0L);
            if (field.getType() == Boolean.class) field.set(row, false);
        }
        row.setId(id);
        row.setAccountid(731);
        row.setWorld(0);
        row.setName("LoadedCtxBot" + id);
        row.setLevel(1);
        row.setHp(50); row.setMaxhp(50); row.setMp(5); row.setMaxmp(5);
        row.setLastExpGainTime(new Timestamp(0));
        row.setEquipslots(24); row.setUseslots(24); row.setSetupslots(24); row.setEtcslots(24);
        row.setMountexp(0); row.setMountlevel(1); row.setMounttiredness(0);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Character> members(MapleMap map) throws Exception {
        return (Collection<Character>) field(MapleMap.class, "characters").get(map);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Character> eventMembers(EventInstanceManager event) throws Exception {
        return (Map<Integer, Character>) field(EventInstanceManager.class, "chars").get(event);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static GameContext.Invalid invalid(GameContext.Failure reason, Runnable action) {
        GameContext.Invalid failure = assertThrows(GameContext.Invalid.class, action::run);
        assertEquals(reason, failure.reason());
        return failure;
    }
}
