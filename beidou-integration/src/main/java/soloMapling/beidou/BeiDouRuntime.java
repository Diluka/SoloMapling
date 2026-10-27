package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.manager.ServerManager;
import org.gms.soloacceptance.AcceptanceObservationRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import soloMapling.ArtificialPlayer.BotClientHandler;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.LlmSystem.SocialLlmService;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.Environment.EnvironmentPopulationConfig;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.server.BotTickService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.List;

/** Entry points invoked by woven engine lifecycle methods, after Spring is available. */
public final class BeiDouRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(BeiDouRuntime.class);
    private static final AtomicBoolean READY = new AtomicBoolean();

    private BeiDouRuntime() { }

    public static void onServerReady() {
        if (!READY.compareAndSet(false, true)) {
            return;
        }
        try {
            AcceptanceObservationRuntime.onWorldStarted();
            Environment environment = ServerManager.getApplicationContext().getEnvironment();
            SoloMaplingLanguageConfig.configure(environment);
            SocialLlmService.configure(environment);
            EnvironmentPopulationConfig.setConfigPath(environment.getProperty("solomapling.population-config", ""));
            BotClientHandler.initHeadlessBotClient();
            EquipMetadataCache.initialize();
            DesirableEquipList.load();
            BotTickService.start();
            // Manual GM bot creation must receive chat even when startup population
            // is disabled. Only this live lifecycle may restart message workers.
            MessageQueue.start();
            boolean spawn = environment.getProperty("solomapling.spawn-bots-on-startup", Boolean.class, false);
            LOG.info("SoloMapling BeiDou integration ready; spawn-bots-on-startup={} language={} dialogue-pack={}",
                    spawn, SoloMaplingLanguageConfig.languageTag(), SoloMaplingLanguageConfig.dialoguePackDirectoryName());
            if (spawn) {
                EnvironmentManager.environmentLoadStartup();
            }
        } catch (RuntimeException failure) {
            try {
                onServerStopping();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static void onServerStopping() {
        AcceptanceObservationRuntime.onWorldStopped();
        if (!READY.getAndSet(false) && BotRegistry.snapshot().isEmpty()) {
            return;
        }
        BotTickService.stop();
        List<Character> characters = BotRegistry.snapshot();
        // First publish the non-blocking stop flag. Subclass cleanup can acquire
        // a bot monitor held by its callback, so unblock waits before taking it.
        for (Character character : characters) {
            try {
                BotSM machine = CharacterStorage.getBotById(character.getId());
                if (machine != null) {
                    machine.setRunning(false);
                }
            } catch (RuntimeException failure) {
                LOG.warn("Unable to stop SoloMapling bot {} scheduler during shutdown", character.getId(), failure);
            }
        }
        BotTickService.interruptCancelledRunning();
        SocialLlmService.shutdown();
        try {
            EnvironmentManager.stop();
        } catch (RuntimeException failure) {
            LOG.warn("Unable to stop all SoloMapling environment tasks during shutdown", failure);
        }
        for (Character character : characters) {
            try {
                BotSM machine = CharacterStorage.getBotById(character.getId());
                if (machine != null) machine.stopScheduledTask();
            } catch (RuntimeException failure) {
                LOG.warn("Unable to clean SoloMapling bot {} scheduler during shutdown", character.getId(), failure);
            }
        }
        if (!BotTickService.awaitQuiescence(10, TimeUnit.SECONDS)) {
            // Preserve native player saving/closure even if a misbehaving AI
            // callback or a self-initiated shutdown cannot satisfy this barrier.
            LOG.error("SoloMapling macro callbacks did not drain before shutdown; continuing native player saving and closure: {}",
                    BotTickService.runningDiagnostics());
        }
        // Do this before the engine tears down its channels and maps. The upstream
        // executor pools are process-wide, so do not destroy them on an in-JVM restart.
        for (Character character : characters) {
            try {
                BotGeneration.removeBotFromServer(character);
            } catch (RuntimeException failure) {
                LOG.warn("Unable to remove SoloMapling bot {} during shutdown", character.getId(), failure);
            } finally {
                BotRegistry.unregister(character);
            }
        }
    }
}
