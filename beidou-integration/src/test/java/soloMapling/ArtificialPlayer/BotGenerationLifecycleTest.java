package soloMapling.ArtificialPlayer;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.HiredMerchant;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.beidou.BotRegistry;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.server.SoloMaplingUtilities;
import soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands;
import soloMapling.FreeMarket.ArtificialFreeMarket;
import soloMapling.FreeMarket.ArtificialShopGenerator;
import soloMapling.FreeMarket.FMEconomyManager;
import soloMapling.FreeMarket.FMShopTypeManager;
import soloMapling.FreeMarket.HiredMerchantArtificial;

import java.awt.Point;
import java.util.Collections;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BotGenerationLifecycleTest {
    private final List<Character> registeredBots = new ArrayList<>();

    @BeforeAll
    static void installNativeContext() {
        CharacterTestContext.install();
    }

    @AfterEach
    void unregisterFixtures() {
        registeredBots.forEach(BotRegistry::unregister);
    }

    @Test
    void consoleCreationSkipsRegisteredOnlineAndPersistedCharactersBeforePublishing() throws Exception {
        try (CreationWorld world = new CreationWorld()) {
            Character console = BotGeneration.getConsoleBot();
            world.assertUnoccupiedIdentity(console);
            assertSame(console, world.channelPlayers.getCharacterById(console.getId()));
            assertSame(console, world.worldPlayers.getCharacterById(console.getId()));
            assertSame(console, BotGeneration.getConsoleBot());
        }
    }

    @Test
    void merchantCreationUsesTheSameCollisionRulesAndPublishesItsOwnOwner() throws Exception {
        try (CreationWorld world = new CreationWorld();
             MockedStatic<ArtificialShopGenerator> stock = mockStatic(ArtificialShopGenerator.class);
             MockedStatic<FMEconomyManager> economy = mockStatic(FMEconomyManager.class);
             MockedStatic<FMShopTypeManager> shopTypes = mockStatic(FMShopTypeManager.class)) {
            int mapId = world.map.getId();
            economy.when(() -> FMEconomyManager.getTierForRoom(mapId)).thenReturn("B");
            shopTypes.when(FMShopTypeManager::selectShopTypeWeightedProbability)
                    .thenReturn(HiredMerchantArtificial.shopTypes.Common);
            shopTypes.when(FMShopTypeManager::getSecondaryShopItemTypes)
                    .thenReturn(List.of(HiredMerchantArtificial.shopTypes.Potion));
            Client requester = mock(Client.class);
            Character player = mock(Character.class);
            when(requester.getPlayer()).thenReturn(player);
            when(player.getMapId()).thenReturn(mapId);
            when(player.getPosition()).thenReturn(new Point(40, 20));

            ArtificialFreeMarket.populateFreeMarketSpot(requester);

            assertEquals(1, world.created.size());
            Character owner = world.created.getFirst();
            world.assertUnoccupiedIdentity(owner);
            var published = org.mockito.ArgumentCaptor.forClass(HiredMerchant.class);
            verify(world.map).addMapObject(published.capture());
            HiredMerchant merchant = published.getValue();
            assertEquals(owner.getId(), merchant.getOwnerId());
            assertSame(world.map, merchant.getMap());
            assertTrue(merchant.isOpen());
            assertSame(world.map, owner.getMap());
            verify(world.world).registerHiredMerchant(merchant);
            verify(world.channel).addHiredMerchant(owner.getId(), merchant);
        }
    }

    @Test
    void crossMapMoveRemovesTheOldMembershipBeforePublishingTheNewOne() {
        Character bot = currentBot(1_700_010_001);
        MapleMap oldMap = mock(MapleMap.class);
        MapleMap newMap = mock(MapleMap.class);
        Set<Character> oldPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Character> newPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
        oldPlayers.add(bot);
        AtomicReference<MapleMap> current = new AtomicReference<>(oldMap);
        when(bot.getMap()).thenAnswer(ignored -> current.get());
        doAnswer(invocation -> {
            assertSame(oldMap, current.get());
            oldPlayers.remove(bot);
            return null;
        }).when(oldMap).removePlayer(bot);
        doAnswer(invocation -> {
            assertFalse(oldPlayers.contains(bot));
            current.set(invocation.getArgument(0));
            return null;
        }).when(bot).setMap(any(MapleMap.class));
        doAnswer(invocation -> {
            assertSame(newMap, current.get());
            assertFalse(oldPlayers.contains(bot));
            assertTrue(newPlayers.add(bot));
            return null;
        }).when(newMap).addPlayer(bot);

        assertTrue(BotGeneration.placeBotOnMap(bot, new Point(30, 40), newMap));

        assertTrue(oldPlayers.isEmpty());
        assertEquals(Set.of(bot), newPlayers);
        var order = inOrder(oldMap, bot, newMap);
        order.verify(oldMap).removePlayer(bot);
        order.verify(bot).setMap(newMap);
        order.verify(bot).setPosition(new Point(30, 40));
        order.verify(bot).setStance(5);
        order.verify(newMap).addPlayer(bot);
    }

    @Test
    void sameMapPlacementRemovesBeforeReaddingExactlyOnce() {
        Character bot = currentBot(1_700_010_002);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);

        assertTrue(BotGeneration.placeBotOnMap(bot, new Point(1, 2), map));

        var order = inOrder(map, bot);
        order.verify(map).removePlayer(bot);
        order.verify(bot).setMap(map);
        order.verify(map).addPlayer(bot);
        verify(map, times(1)).removePlayer(bot);
        verify(map, times(1)).addPlayer(bot);
    }

    @Test
    void firstPlacementNeedsNoOldMap() {
        Character bot = currentBot(1_700_010_003);
        MapleMap map = mock(MapleMap.class);
        assertTrue(BotGeneration.placeBotOnMap(bot, new Point(), map));
        verify(map, never()).removePlayer(any());
        verify(map).addPlayer(bot);
    }

    @Test
    void sameJvmRestartUsesTheReplacementChannelAndWorldForRegistrationAndLookup() {
        Server server = mock(Server.class);
        Channel firstChannel = mock(Channel.class, RETURNS_DEEP_STUBS);
        Channel nextChannel = mock(Channel.class, RETURNS_DEEP_STUBS);
        World firstWorld = mock(World.class, RETURNS_DEEP_STUBS);
        World nextWorld = mock(World.class, RETURNS_DEEP_STUBS);
        AtomicReference<Channel> channel = new AtomicReference<>(firstChannel);
        AtomicReference<World> world = new AtomicReference<>(firstWorld);
        when(server.getChannel(0, 1)).thenAnswer(ignored -> channel.get());
        when(server.getWorld(0)).thenAnswer(ignored -> world.get());
        Character first = bot(1_700_000_001);
        Character next = bot(1_700_000_002);

        try (MockedStatic<Server> singleton = mockStatic(Server.class)) {
            singleton.when(Server::getInstance).thenReturn(server);
            BotGeneration.addBotToServer(first);
            verify(firstChannel).addPlayer(first);
            verify(firstWorld.getPlayerStorage()).addPlayer(first);

            // Simulate doShutdownInternal(true) disposing the old stores and creating new ones.
            when(firstWorld.getPlayerStorage()).thenThrow(new IllegalStateException("old world closed"));
            doThrow(new IllegalStateException("old channel closed")).when(firstChannel).addPlayer(any());
            channel.set(nextChannel);
            world.set(nextWorld);
            when(nextChannel.getPlayerStorage().getCharacterById(next.getId())).thenReturn(next);
            when(nextChannel.getPlayerStorage().getCharacterByName("next")).thenReturn(next);

            assertDoesNotThrow(() -> BotGeneration.addBotToServer(next));
            verify(nextChannel).addPlayer(next);
            verify(nextWorld.getPlayerStorage()).addPlayer(next);
            assertSame(nextChannel, SoloMaplingUtilities.channel());
            assertSame(nextWorld, SoloMaplingUtilities.world());
            assertSame(next, SoloMaplingUtilities.getChr(next.getId()));
            assertSame(next, SoloMaplingUtilities.getChr("next"));
            verify(firstChannel, never()).addPlayer(next);
        } finally {
            BotRegistry.unregister(first);
            BotRegistry.unregister(next);
        }
    }

    @Test
    void newHeadlessObjectIsRegisteredAndStoredBeforeItsFirstMapPublication() {
        Server server = mock(Server.class);
        Channel channel = mock(Channel.class, RETURNS_DEEP_STUBS);
        World world = mock(World.class, RETURNS_DEEP_STUBS);
        Character bot = bot(1_700_010_004);
        when(bot.getClient()).thenReturn(new BotClient(0, 1));
        MapleMap map = mock(MapleMap.class);
        AtomicReference<Character> channelPresence = new AtomicReference<>();
        AtomicReference<Character> worldPresence = new AtomicReference<>();
        List<String> publications = new ArrayList<>();
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(server.getWorld(0)).thenReturn(world);
        when(channel.getPlayerStorage().getCharacterById(bot.getId()))
                .thenAnswer(ignored -> channelPresence.get());
        var worldStorage = world.getPlayerStorage();
        doAnswer(ignored -> {
            assertTrue(BotRegistry.isRegistered(bot));
            channelPresence.set(bot);
            publications.add("channel");
            return null;
        }).when(channel).addPlayer(bot);
        doAnswer(ignored -> {
            assertSame(bot, channelPresence.get());
            worldPresence.set(bot);
            publications.add("world");
            return null;
        }).when(worldStorage).addPlayer(bot);
        doAnswer(ignored -> {
            assertTrue(BotRegistry.isCurrent(bot));
            assertSame(bot, channelPresence.get());
            assertSame(bot, worldPresence.get());
            publications.add("map");
            return null;
        }).when(map).addPlayer(bot);
        try (MockedStatic<Server> singleton = mockStatic(Server.class)) {
            singleton.when(Server::getInstance).thenReturn(server);
            assertFalse(BotGeneration.placeBotOnMap(bot, new Point(), map));
            assertTrue(publications.isEmpty());
            BotGeneration.addBotToServer(bot);
            assertTrue(BotGeneration.placeBotOnMap(bot, new Point(), map));
            assertEquals(List.of("channel", "world", "map"), publications);
        } finally {
            BotRegistry.unregister(bot);
        }
    }

    @Test
    void unregisteredOldObjectCannotWarpOrRegisterItselfAgain() {
        Character stale = currentBot(1_700_010_005);
        MapleMap previous = mock(MapleMap.class);
        MapleMap destination = mock(MapleMap.class);
        Set<Character> newPresence = Collections.newSetFromMap(new IdentityHashMap<>());
        when(stale.getMap()).thenReturn(previous);
        doAnswer(ignored -> {
            newPresence.add(stale);
            return null;
        }).when(destination).addPlayer(stale);
        BotRegistry.unregister(stale);
        try (MockedStatic<BotHelpers> pauses = mockStatic(BotHelpers.class)) {
            assertFalse(BotGeneration.placeBotOnMap(stale, new Point(), destination));
            BotGeneration.warpBotToLocation(stale, new Point(), destination);
            BotGeneration.spawnBotFm(stale, new Point());
            pauses.verifyNoInteractions();
        }
        assertFalse(BotRegistry.isRegistered(stale));
        assertTrue(newPresence.isEmpty());
        verify(previous, never()).removePlayer(any());
        verify(destination, never()).addPlayer(any());
        verify(stale, never()).setMap(any());
    }

    @Test
    void registeredObjectWhoseChannelSlotWasReplacedCannotPublishAnotherPresence() {
        Character stale = currentBot(1_700_010_006);
        Character replacement = bot(stale.getId());
        Channel channel = stale.getClient().getChannelServer();
        when(channel.getPlayerStorage().getCharacterById(stale.getId())).thenReturn(replacement);
        MapleMap destination = mock(MapleMap.class);
        assertTrue(BotRegistry.isRegistered(stale));
        assertFalse(BotRegistry.isCurrent(stale));
        assertFalse(BotGeneration.placeBotOnMap(stale, new Point(), destination));
        BotGeneration.warpBotToLocation(stale, new Point(), destination);
        verify(destination, never()).addPlayer(any());
        verify(stale, never()).setMap(any());
        assertSame(replacement, channel.getPlayerStorage().getCharacterById(stale.getId()));
    }

    @Test
    void unregistrationDuringOldMapRemovalAbortsBeforeNewMapPublication() {
        Character stale = currentBot(1_700_010_007);
        MapleMap previous = mock(MapleMap.class);
        MapleMap destination = mock(MapleMap.class);
        when(stale.getMap()).thenReturn(previous);
        doAnswer(ignored -> {
            BotRegistry.unregister(stale);
            return null;
        }).when(previous).removePlayer(stale);
        assertFalse(BotGeneration.placeBotOnMap(stale, new Point(), destination));
        assertFalse(BotRegistry.isRegistered(stale));
        verify(destination, never()).addPlayer(any());
        verify(stale, never()).setMap(any());
    }

    @Test
    void delayedArrivalStopsAfterItsObjectIsUnregistered() {
        Character bot = currentBot(1_700_010_008);
        MapleMap map = mock(MapleMap.class);
        try (MockedStatic<BotHelpers> pauses = mockStatic(BotHelpers.class);
             MockedStatic<WarpCommands> arrival = mockStatic(WarpCommands.class)) {
            pauses.when(() -> BotHelpers.blockingSleep(anyLong())).thenAnswer(ignored -> {
                BotRegistry.unregister(bot);
                return true;
            });
            BotGeneration.warpBotToLocation(bot, new Point(), map);
            verify(map, times(1)).addPlayer(bot);
            assertFalse(BotRegistry.isRegistered(bot));
            arrival.verifyNoInteractions();
        }
    }

    private Character currentBot(int id) {
        Character bot = bot(id);
        Channel channel = mock(Channel.class, RETURNS_DEEP_STUBS);
        when(bot.getClient().getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage().getCharacterById(id)).thenReturn(bot);
        BotRegistry.register(bot);
        registeredBots.add(bot);
        return bot;
    }

    private static Character bot(int id) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        return bot;
    }

    /** Native world/storage and character-loading boundary; allocation and both consumers are real. */
    private static final class CreationWorld implements AutoCloseable {
        private static final int TEMPLATE_ID = 12345;
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        final Channel channel = mock(Channel.class, RETURNS_DEEP_STUBS);
        final PlayerStorage worldPlayers = spy(new PlayerStorage());
        final PlayerStorage channelPlayers = new PlayerStorage();
        final MapleMap map = mock(MapleMap.class);
        final List<Character> created = new ArrayList<>();
        final List<Character> registered = new ArrayList<>();
        final Set<Integer> persistedIds = new HashSet<>(Set.of(TEMPLATE_ID));
        final MockedStatic<Server> singleton;
        final MockedStatic<DatabaseConnection> database;
        final MockedStatic<Character> characters;
        Character onlinePlayer;

        CreationWorld() throws Exception {
            when(server.getWorlds()).thenReturn(List.of(world));
            when(server.getWorld(0)).thenReturn(world);
            when(server.getChannel(0, 1)).thenReturn(channel);
            when(world.getPlayerStorage()).thenReturn(worldPlayers);
            when(world.getChannel(1)).thenReturn(channel);
            when(channel.getPlayerStorage()).thenReturn(channelPlayers);
            when(map.getId()).thenReturn(100_000_013);
            when(channel.getMapFactory().getMap(map.getId())).thenReturn(map);
            doAnswer(call -> {
                channelPlayers.addPlayer(call.getArgument(0));
                return null;
            }).when(channel).addPlayer(any(Character.class));
            doAnswer(call -> {
                int candidate = call.getArgument(0);
                prepareOccupancy(candidate);
                return call.callRealMethod();
            }).when(worldPlayers).getCharacterById(anyInt());
            Connection connection = mock(Connection.class);
            PreparedStatement template = mock(PreparedStatement.class);
            PreparedStatement occupied = mock(PreparedStatement.class);
            ResultSet templateRows = mock(ResultSet.class);
            ResultSet occupiedRows = mock(ResultSet.class);
            when(connection.prepareStatement("SELECT id FROM characters WHERE name = ? AND world = ?"))
                    .thenReturn(template);
            when(template.executeQuery()).thenReturn(templateRows);
            when(templateRows.next()).thenReturn(true, false);
            when(templateRows.getInt("id")).thenReturn(TEMPLATE_ID);
            when(connection.prepareStatement("SELECT 1 FROM characters WHERE id = ?")).thenReturn(occupied);
            when(occupied.executeQuery()).thenReturn(occupiedRows);
            AtomicInteger checked = new AtomicInteger();
            doAnswer(call -> {
                int candidate = call.getArgument(1);
                prepareOccupancy(candidate);
                checked.set(candidate);
                return null;
            }).when(occupied).setInt(eq(1), anyInt());
            when(occupiedRows.next()).thenAnswer(call -> persistedIds.contains(checked.get()));
            singleton = mockStatic(Server.class);
            singleton.when(Server::getInstance).thenReturn(server);
            database = mockStatic(DatabaseConnection.class);
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            characters = mockStatic(Character.class);
            characters.when(() -> Character.loadCharFromDB(anyInt(), any(Client.class), eq(false)))
                    .thenAnswer(call -> freshCharacter(call.getArgument(1)));
            characters.when(() -> Character.getDefault(any(Client.class)))
                    .thenAnswer(call -> freshCharacter(call.getArgument(0)));
        }

        private void prepareOccupancy(int candidate) {
            if (onlinePlayer != null) return;
            // Both storage boundaries expose the same pre-existing identities, whichever is queried first.
            onlinePlayer = nativeCharacter(mock(Client.class), candidate, "ExistingPlayer");
            worldPlayers.addPlayer(onlinePlayer);
            persistedIds.add(candidate);
            Character existingBot = nativeCharacter(new BotClient(0, 1), candidate - 1, "ExistingBot");
            BotRegistry.register(existingBot);
            registered.add(existingBot);
            persistedIds.add(candidate - 2);
        }

        Character freshCharacter(Client client) {
            Character character = nativeCharacter(client, TEMPLATE_ID, "Template");
            created.add(character);
            return character;
        }

        Character nativeCharacter(Client client, int initialId, String initialName) {
            Character character = mock(Character.class);
            AtomicInteger id = new AtomicInteger(initialId);
            AtomicReference<String> name = new AtomicReference<>(initialName);
            AtomicReference<MapleMap> currentMap = new AtomicReference<>(map);
            AtomicReference<HiredMerchant> merchant = new AtomicReference<>();
            AtomicReference<Point> position = new AtomicReference<>(new Point());
            when(character.getClient()).thenReturn(client);
            when(character.getId()).thenAnswer(call -> id.get());
            doAnswer(call -> { id.set(call.getArgument(0)); return null; }).when(character).setId(anyInt());
            when(character.getName()).thenAnswer(call -> name.get());
            doAnswer(call -> { name.set(call.getArgument(0)); return null; }).when(character).setName(anyString());
            when(character.getWorldServer()).thenReturn(world);
            when(character.getMap()).thenAnswer(call -> currentMap.get());
            doAnswer(call -> { currentMap.set(call.getArgument(0)); return null; })
                    .when(character).setMap(any(MapleMap.class));
            when(character.getPosition()).thenAnswer(call -> position.get());
            doAnswer(call -> { position.set(call.getArgument(0)); return null; })
                    .when(character).setPosition(any(Point.class));
            when(character.getHiredMerchant()).thenAnswer(call -> merchant.get());
            doAnswer(call -> { merchant.set(call.getArgument(0)); return null; })
                    .when(character).setHiredMerchant(any());
            return character;
        }

        void assertUnoccupiedIdentity(Character character) {
            assertFalse(persistedIds.contains(character.getId()), "新角色不能沿用模板或其它既存持久身份");
            assertNotEquals(onlinePlayer.getId(), character.getId());
            assertNotEquals(registered.getFirst().getId(), character.getId());
            assertTrue(character.getId() > 0);
            assertTrue(BotRegistry.isRegistered(character));
            assertSame(onlinePlayer, worldPlayers.getCharacterById(onlinePlayer.getId()));
            assertFalse(BotRegistry.isBot(onlinePlayer));
            assertTrue(BotRegistry.isRegistered(registered.getFirst()));
        }

        @Override
        public void close() {
            created.forEach(BotGeneration::removeBotFromServer);
            registered.forEach(BotRegistry::unregister);
            characters.close();
            database.close();
            singleton.close();
        }
    }
}
