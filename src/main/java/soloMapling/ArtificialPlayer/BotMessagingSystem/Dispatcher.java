package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;

import java.util.Collection;
import java.util.concurrent.*;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.expirePlayerChatCommands;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfInvisibleBot;
import static soloMapling.server.ExecutorServiceManager.getExecutorService;
import static soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;
import static soloMapling.BotLogger.log;

public class Dispatcher implements Runnable {

    private static volatile Dispatcher dispatcher;
    private final MessageQueue messageQueue;
    private final ExecutorService executor = getExecutorService();
    private final ScheduledExecutorService scheduler = getScheduledExecutorService();
    private final java.util.Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private volatile long generation;

    public Dispatcher(MessageQueue messageQueue) {
        this(messageQueue, true);
    }

    private Dispatcher(MessageQueue messageQueue, boolean startWorker) {
        log("Dispatcher OBJECT Created");
        this.messageQueue = messageQueue;
        if (startWorker) start();
    }

    static synchronized void startIfNeeded(MessageQueue messageQueue) {
        Dispatcher current = dispatcher;
        if (current == null) dispatcher = new Dispatcher(messageQueue);
        else current.start();
    }

    private synchronized void start() {
        if (running) return;
        running = true;
        long currentGeneration = ++generation;
        scheduledTask = scheduler.scheduleAtFixedRate(() -> processMessages(currentGeneration),
                0, 2, TimeUnit.SECONDS);
    }

    public static void stopIfStarted() {
        Dispatcher current = dispatcher;
        if (current != null) current.stop();
    }

    private synchronized void stop() {
        running = false;
        generation++;
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }
        pending.forEach(task -> task.cancel(true));
        pending.clear();
    }

    private boolean isCurrent(long currentGeneration) {
        return running && generation == currentGeneration && !Thread.currentThread().isInterrupted();
    }

    // Static method to access the singleton instance
    public static synchronized Dispatcher getInstance() {
        log("Dispatcher getInstance");
        if (dispatcher == null) dispatcher = new Dispatcher(MessageQueue.getInstance(), false);
        return dispatcher;
    }

    @Override
    public void run() {
//        log("Dispatcher: RUN");
        processMessages(generation);
    }

    //    @Override
    private void processMessages(long currentGeneration) {
        if (!isCurrent(currentGeneration)) return;
        try {
            ChatMessage message = messageQueue.getMessageNonBlocking("primary");
            if (message == null) {
                return;
            }
            Collection<Character> chars_on_map = message.getMap().getCharacters();
            final int[] botToCall = new int[1];  // Using an array to hold the bot ID

            boolean characterFound = checkIfCharacterOnMap(chars_on_map, message, botToCall);
            if (!isCurrent(currentGeneration)) return;

            // Determine if the message is for any registered bot
            if (characterFound) {
                handleBotRunning(botToCall, message, currentGeneration);
            } else {
                handleMessageWithNoBotName(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean checkIfCharacterOnMap(Collection<Character> chars_on_map, ChatMessage message, int[] botToCall) {
        boolean characterFound = false;
        for (Character character : chars_on_map) {
            if (message.getContent().contains(character.getName())) {
                if (!checkIfInvisibleBot(character.getId())) {
                    botToCall[0] = character.getId();
                    characterFound = true;
                    break;
                }
            }
        }
        return characterFound;
    }

    private void handleBotRunning(int[] botToCall, ChatMessage message, long currentGeneration) {
        FutureTask<Void> action = new FutureTask<>(() -> {
            if (!isCurrent(currentGeneration)) return;
            BotSM bot = getBotById(botToCall[0]);
            if (bot == null) {
                logBotNotFound(botToCall[0]);
                return;
            }

            if (!bot.getRunning()) {
                startNewBotSession(bot, message);
            } else {
                handleExistingBotSession(bot, message);
            }
        }, null) {
            @Override
            protected void done() {
                pending.remove(this);
            }
        };
        pending.add(action);
        if (isCurrent(currentGeneration)) executor.execute(action);
        else action.cancel(false);
    }

    private void startNewBotSession(BotSM bot, ChatMessage message) {
        log("Bot not running. Start scheduledTask line");
        bot.setRunning(true);
        bot.getInteractors().setRespondant(message.getSender());
        bot.startScheduledTask();
        if (bot instanceof SocialBot socialBot) {
            socialBot.onFirstInteraction(message.getSender());
        }
    }

    private void handleExistingBotSession(BotSM bot, ChatMessage message) {
        if (bot instanceof SocialBot socialBot) {
            handleSocialBotSession(socialBot, message);
            return;
        }
        log("bot already running");
        bot.getInteractors().setInquirer(message.getSender());
        bot.getDialogueHandler().listOptions(message.getSender(), bot);
        // One-line inquiry: a naming message that also carries a keyword ("Tiger wana party?") pre-selects the
        // menu option in the same breath. Strip the bot's name first so a name that itself contains a keyword
        // can't self-match, then re-enqueue the remainder onto the tertiary queue exactly as a stand-alone
        // follow-up would have arrived — BotOptionMenu.poll drains it on the bot's next tick with the same
        // trim/lowercase/contains matcher. The menu is already active (listOptions ran synchronously above),
        // and a non-matching remainder ("yo Tiger") falls through harmlessly like a junk follow-up would.
        String remainder = message.getContent().replace(bot.getChr().getName(), "").trim();
        if (!remainder.isEmpty()) {
            messageQueue.addMessage("tertiary", new ChatMessage(message.getSender(), remainder));
            bot.nudgeSoon(0L); // player is on the bot's map -> pull the pre-selected option forward so it feels instant
        }
    }

    private void handleSocialBotSession(SocialBot socialBot, ChatMessage message) {
        if (socialBot.hasActiveRespondant()) {
            log("[Dispatcher] SocialBot busy, ignoring second player");
            return;
        }
        log("[Dispatcher] SocialBot available, setting respondant");
        socialBot.getInteractors().setRespondant(message.getSender());
        socialBot.onFirstInteraction(message.getSender());
    }

    private void logBotNotFound(int botId) {
        log("No bot found for ID: " + botId);
    }

//    private void handleBotRunning(int[] botToCall, ChatMessage message) {
//        executor.execute(() -> {
//            BotSM bot = getBotById(botToCall[0]);
//            if (bot != null && !bot.getRunning()) {
//                log("Bot not running. Start scheduledTask line");
//                bot.setRunning(true);
//                bot.setRespondant(message.getSender());
//                bot.startScheduledTask();
//            } else if (bot != null & bot.getRunning()) {
//                log("bot already running");
//                bot.setInquirer(message.getSender());
//                bot.listOptions(message.getSender());
//            } else {
//                log("No bot found for ID: " + botToCall[0] + " or bot is already running.");
//            }
//        });
//    }

    private void handleMessageWithNoBotName(ChatMessage message) {
        // message does not contain any info w/ bot names.
        Character respondant = message.getSender();
        if (CharacterStorage.checkIfRespondant(respondant)) { // Check if message contains a respondant
            expirePlayerChatCommands(respondant); // Expires any bubbles for respondants, catch all.
            messageQueue.addMessage(message); // Put into 2nd queue
        } else if (CharacterStorage.checkIfInquirer(respondant)) {
            messageQueue.addMessage("tertiary", message);
        }
    }

    public void shutdown() {
        stop();
    }
}

            /*
            Message:
            - Has Bot Name -> start that bot

            - Doesn't have bot name
                -> contains a respondant -> 2ndary queue
                -> doesn't contain a respondant -> random chat, remove

            2ndary queue:
                - active bots pull from this.
             */
