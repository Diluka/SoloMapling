package soloMapling.ArtificialPlayer;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotChatterSystem.BotChatter;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Language is configured before starting the actual ambient-chat consumers. */
class AmbientConversationLifecycleTest {
    @BeforeAll
    static void installNativeContext() {
        CharacterTestContext.install();
    }

    @AfterEach
    void resetLanguage() {
        ConversationManager.stopIfStarted();
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void startedManagerUsesTheConfiguredLanguageForAutomaticAndDebugConversations() {
        for (String language : List.of("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(language);
            try (Scene scene = new Scene()) {
                ConversationManager manager = ConversationManager.getInstance();
                manager.start();
                scene.ticks.remove().run();
                assertLanguage(scene.spoken, language);

                scene.spoken.clear();
                manager.triggerOnMap(scene.observer);
                assertLanguage(scene.spoken, language);
                manager.stop();
            }
        }
    }

    @Test
    void stoppedManagerRejectsDebugInputAndAlreadyScheduledAutomaticTicks() {
        try (Scene scene = new Scene()) {
            ConversationManager manager = ConversationManager.getInstance();
            manager.start();
            Runnable scheduled = scene.ticks.remove();
            manager.stop();
            manager.triggerOnMap(scene.observer);
            scheduled.run();
            assertTrue(scene.spoken.isEmpty());
            assertFalse(manager.isInConversation(scene.first.getId()));
            assertFalse(manager.isInConversation(scene.second.getId()));
        }
    }

    @Test
    void failedStartupReadCanRecoverThroughThePublicDebugTrigger() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        try (Scene scene = new Scene();
             MockedStatic<DialoguePackPaths> resources = mockStatic(DialoguePackPaths.class, CALLS_REAL_METHODS)) {
            resources.when(() -> DialoguePackPaths.openDialogueReader("ConversationDialogue.yaml"))
                    .thenThrow(new IOException("fixture unavailable resource"))
                    .thenCallRealMethod();
            ConversationManager manager = ConversationManager.getInstance();
            manager.start();
            manager.triggerOnMap(scene.observer);
            assertLanguage(scene.spoken, "zh-CN");
            manager.stop();
        }
    }

    @Test
    void townChatterPublishesLocalizedTurnsThroughItsPublicTrigger() {
        long oldMin = BotChatter.TURN_PAUSE_MIN_MS;
        long oldMax = BotChatter.TURN_PAUSE_MAX_MS;
        BotChatter.TURN_PAUSE_MIN_MS = 0;
        BotChatter.TURN_PAUSE_MAX_MS = 0;
        try {
            for (String language : List.of("en-US", "zh-CN")) {
                SoloMaplingLanguageConfig.setLanguageTag(language);
                try (Scene scene = new Scene(); MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
                    movement.when(() -> GCMovement.isMapObserved(Scene.MAP_ID)).thenReturn(true);
                    assertTrue(BotChatter.forceChatter(scene.firstBot));
                    assertLanguage(scene.spoken, language);
                }
            }
        } finally {
            BotChatter.TURN_PAUSE_MIN_MS = oldMin;
            BotChatter.TURN_PAUSE_MAX_MS = oldMax;
        }
    }

    private static void assertLanguage(List<String> spoken, String language) {
        assertFalse(spoken.isEmpty(), "No ambient chat was published");
        assertEquals("zh-CN".equals(language), spoken.stream().anyMatch(text ->
                text.codePoints().anyMatch(point -> point >= 0x4e00 && point <= 0x9fff)));
    }

    private static final class Scene implements AutoCloseable {
        static final int MAP_ID = 100000000;
        final List<String> spoken = new ArrayList<>();
        final Queue<Runnable> ticks = new ArrayDeque<>();
        final Character observer = mock(Character.class);
        final Character first = mock(Character.class);
        final Character second = mock(Character.class);
        final SocialBot firstBot = mock(SocialBot.class);
        final SocialBot secondBot = mock(SocialBot.class);
        final MockedStatic<Server> server = mockStatic(Server.class);
        final MockedStatic<CharacterStorage> storage = mockStatic(CharacterStorage.class);
        final MockedStatic<ExecutorServiceManager> execution = mockStatic(ExecutorServiceManager.class);
        final MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class, CALLS_REAL_METHODS);
        final MockedStatic<MovementCommands> movement = mockStatic(MovementCommands.class);
        final MockedStatic<SocialCommands> social = mockStatic(SocialCommands.class);

        Scene() {
            Server nativeServer = mock(Server.class, RETURNS_DEEP_STUBS);
            MapleMap map = mock(MapleMap.class);
            when(map.getAllPlayers()).thenReturn(List.of(observer, first, second));
            when(observer.getMapId()).thenReturn(MAP_ID);
            when(first.getId()).thenReturn(1_700_020_001);
            when(second.getId()).thenReturn(1_700_020_002);
            for (Character character : List.of(first, second)) {
                when(character.getClient()).thenReturn(new BotClient(0, 1));
                when(character.getMap()).thenReturn(map);
                when(character.getMapId()).thenReturn(MAP_ID);
                when(character.getPosition()).thenReturn(new Point(0, 0));
            }
            when(firstBot.getChr()).thenReturn(first);
            when(secondBot.getChr()).thenReturn(second);
            when(firstBot.isAvailableForAmbientActions()).thenReturn(true);
            when(secondBot.isAvailableForAmbientActions()).thenReturn(true);
            storage.when(() -> CharacterStorage.getBotById(first.getId())).thenReturn(firstBot);
            storage.when(() -> CharacterStorage.getBotById(second.getId())).thenReturn(secondBot);
            server.when(Server::getInstance).thenReturn(nativeServer);
            when(nativeServer.getChannel(0, 1).getMapFactory().getMap(MAP_ID)).thenReturn(map);
            when(nativeServer.getChannel(0, 1).getPlayerStorage().getAllCharacters())
                    .thenReturn(List.of(observer, first, second));
            helpers.when(() -> BotHelpers.blockingSleep(anyLong())).thenReturn(true);
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
                ticks.add(call.getArgument(0));
                return mock(ScheduledFuture.class);
            });
            ExecutorService worker = mock(ExecutorService.class);
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                    .when(worker).execute(any(Runnable.class));
            execution.when(ExecutorServiceManager::getScheduledExecutorService).thenReturn(scheduler);
            execution.when(ExecutorServiceManager::getVirtualThreadExecutorService).thenReturn(worker);
            execution.when(() -> ExecutorServiceManager.runAsync(any(Runnable.class))).thenAnswer(call -> {
                ((Runnable) call.getArgument(0)).run();
                return null;
            });
            social.when(() -> SocialCommands.BotSpeak(any(Character.class), anyString())).thenAnswer(call -> {
                spoken.add(call.getArgument(1));
                return null;
            });
        }

        @Override
        public void close() {
            ConversationManager.stopIfStarted();
            BotChatter.forget(first);
            BotChatter.forget(second);
            social.close();
            movement.close();
            helpers.close();
            execution.close();
            storage.close();
            server.close();
        }
    }
}
