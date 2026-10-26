package soloMapling.beidou;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.manager.ServerManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import soloMapling.ArtificialPlayer.BotClientHandler;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.LlmSystem.SocialLlmService;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.server.BotTickService;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeiDouRuntimeLifecycleTest {
    @Test
    void stoppingSignalsAndUnblocksMachinesBeforeTakingTheirCleanupLocks() {
        CharacterTestContext.install();
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(716);
        when(character.getClient()).thenReturn(mock(BotClient.class));
        BotSM machine = mock(BotSM.class);
        BotRegistry.register(character);
        try (MockedStatic<SocialLlmService> llm = mockStatic(SocialLlmService.class);
             MockedStatic<EnvironmentManager> environment = mockStatic(EnvironmentManager.class);
             MockedStatic<BotGeneration> generation = mockStatic(BotGeneration.class);
             MockedStatic<CharacterStorage> machines = mockStatic(CharacterStorage.class);
             MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class)) {
            machines.when(() -> CharacterStorage.getBotById(716)).thenReturn(machine);
            doAnswer(ignored -> {
                ticks.verify(BotTickService::interruptCancelledRunning);
                environment.verify(EnvironmentManager::stop);
                return null;
            }).when(machine).stopScheduledTask();
            ticks.when(() -> BotTickService.awaitQuiescence(10, TimeUnit.SECONDS)).thenAnswer(ignored -> {
                verify(machine).setRunning(false);
                verify(machine).stopScheduledTask();
                return true;
            });
            generation.when(() -> BotGeneration.removeBotFromServer(character)).thenAnswer(ignored -> {
                ticks.verify(() -> BotTickService.awaitQuiescence(10, TimeUnit.SECONDS));
                return null;
            });
            assertDoesNotThrow(BeiDouRuntime::onServerStopping);
            assertFalse(BotRegistry.isRegistered(character));
        } finally {
            BotRegistry.unregister(character);
        }
    }

    @Test
    void macroDrainFailureReportsFailureWithoutBlockingNativeCleanup() {
        CharacterTestContext.install();
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(714);
        when(character.getClient()).thenReturn(mock(BotClient.class));
        BotRegistry.register(character);
        try (MockedStatic<SocialLlmService> llm = mockStatic(SocialLlmService.class);
             MockedStatic<EnvironmentManager> environment = mockStatic(EnvironmentManager.class);
             MockedStatic<BotGeneration> generation = mockStatic(BotGeneration.class);
             MockedStatic<CharacterStorage> machines = mockStatic(CharacterStorage.class);
             MockedStatic<BotTickService> ticks = mockStatic(BotTickService.class)) {
            ticks.when(() -> BotTickService.awaitQuiescence(10, TimeUnit.SECONDS)).thenReturn(false);
            assertDoesNotThrow(BeiDouRuntime::onServerStopping);
            ticks.verify(BotTickService::stop);
            ticks.verify(() -> BotTickService.awaitQuiescence(10, TimeUnit.SECONDS));
            generation.verify(() -> BotGeneration.removeBotFromServer(character));
            assertFalse(BotRegistry.isRegistered(character));
        } finally {
            BotRegistry.unregister(character);
        }
    }

    @Test
    void failedStartupRemovesPartialBotsAndAllowsANewStart() {
        CharacterTestContext.install();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("solomapling.spawn-bots-on-startup", false)));
        when(ServerManager.getApplicationContext().getEnvironment()).thenReturn(environment);
        Character partial = mock(Character.class);
        when(partial.getId()).thenReturn(713);
        when(partial.getClient()).thenReturn(mock(BotClient.class));
        AtomicInteger starts = new AtomicInteger();
        try (MockedStatic<BotClientHandler> clients = mockStatic(BotClientHandler.class);
             MockedStatic<EquipMetadataCache> equips = mockStatic(EquipMetadataCache.class);
             MockedStatic<DesirableEquipList> desirable = mockStatic(DesirableEquipList.class);
             MockedStatic<SocialLlmService> llm = mockStatic(SocialLlmService.class);
             MockedStatic<EnvironmentManager> environmentTasks = mockStatic(EnvironmentManager.class);
             MockedStatic<MessageQueue> messages = mockStatic(MessageQueue.class);
             MockedStatic<BotGeneration> generation = mockStatic(BotGeneration.class);
             MockedStatic<CharacterStorage> machines = mockStatic(CharacterStorage.class)) {
            equips.when(EquipMetadataCache::initialize).thenAnswer(ignored -> {
                if (starts.getAndIncrement() == 0) {
                    BotRegistry.register(partial);
                    throw new IllegalStateException("Simulated partial startup");
                }
                return null;
            });
            assertThrows(IllegalStateException.class, BeiDouRuntime::onServerReady);
            assertFalse(BotRegistry.isRegistered(partial));
            generation.verify(() -> BotGeneration.removeBotFromServer(partial), times(1));
            environmentTasks.verify(EnvironmentManager::stop, times(1));
            llm.verify(SocialLlmService::shutdown, times(1));
            BeiDouRuntime.onServerReady();
            assertEquals(2, starts.get());
            clients.verify(BotClientHandler::initHeadlessBotClient, times(2));
            messages.verify(MessageQueue::start, times(1));
            BeiDouRuntime.onServerStopping();
            llm.verify(SocialLlmService::shutdown, times(2));
            environmentTasks.verify(EnvironmentManager::stop, times(2));
            BeiDouRuntime.onServerStopping();
            llm.verify(SocialLlmService::shutdown, times(2));
        } finally {
            BotRegistry.unregister(partial);
        }
    }
}
