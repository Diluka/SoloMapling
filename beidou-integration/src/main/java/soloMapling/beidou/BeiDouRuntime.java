package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.manager.ServerManager;
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
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.concurrent.atomic.AtomicBoolean;

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
            Environment environment = ServerManager.getApplicationContext().getEnvironment();
            SocialLlmService.configure(environment);
            EnvironmentPopulationConfig.setConfigPath(environment.getProperty("solomapling.population-config", ""));
            BotClientHandler.initHeadlessBotClient();
            EquipMetadataCache.initialize();
            DesirableEquipList.load();
            // Manual GM bot creation must receive chat even when startup population
            // is disabled. Only this live lifecycle may restart message workers.
            MessageQueue.start();
            boolean spawn = environment.getProperty("solomapling.spawn-bots-on-startup", Boolean.class, false);
            LOG.info("SoloMapling BeiDou integration ready; spawn-bots-on-startup={}", spawn);
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
        if (!READY.getAndSet(false) && BotRegistry.snapshot().isEmpty()) {
            return;
        }
        SocialLlmService.shutdown();
        try {
            EnvironmentManager.stop();
        } catch (RuntimeException failure) {
            LOG.warn("Unable to stop all SoloMapling environment tasks during shutdown", failure);
        }
        // Do this before the engine tears down its channels and maps. The upstream
        // executor pools are process-wide, so do not destroy them on an in-JVM restart.
        for (Character character : BotRegistry.snapshot()) {
            try {
                BotSM machine = CharacterStorage.getBotById(character.getId());
                if (machine != null) {
                    machine.stopScheduledTask();
                }
            } catch (RuntimeException failure) {
                LOG.warn("Unable to stop SoloMapling bot {} scheduler during shutdown", character.getId(), failure);
            }
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
