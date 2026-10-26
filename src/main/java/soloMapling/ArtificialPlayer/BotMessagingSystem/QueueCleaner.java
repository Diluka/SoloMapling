package soloMapling.ArtificialPlayer.BotMessagingSystem;

import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static soloMapling.BotLogger.log;
import static soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;

public class QueueCleaner implements Runnable {

    private static volatile QueueCleaner cleaner;
    private final MessageQueue messageQueue;
    private final long expirationTime;
    private final ScheduledExecutorService scheduler = getScheduledExecutorService();
    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private volatile long generation;

    public QueueCleaner(MessageQueue messageQueue, long expirationTime) {
        this(messageQueue, expirationTime, true);
    }

    private QueueCleaner(MessageQueue messageQueue, long expirationTime, boolean startWorker) {
        log("\n\nQueueCleaner OBJECT Created");
        this.messageQueue = messageQueue;
        this.expirationTime = expirationTime;
        if (startWorker) start();
    }

    static synchronized void startIfNeeded(MessageQueue messageQueue) {
        QueueCleaner current = cleaner;
        if (current == null) cleaner = new QueueCleaner(messageQueue, 10000);
        else current.start();
    }

    private synchronized void start() {
        if (running) return;
        running = true;
        long currentGeneration = ++generation;
        scheduledTask = scheduler.scheduleAtFixedRate(() -> clean(currentGeneration),
                0, 2, TimeUnit.SECONDS);
    }

    public static void stopIfStarted() {
        QueueCleaner current = cleaner;
        if (current != null) current.stop();
    }

    // Static method to access the singleton instance
    public static synchronized QueueCleaner getInstance() {
        log("Queue Cleaner getInstance");
        if (cleaner == null) cleaner = new QueueCleaner(MessageQueue.getInstance(), 10000, false);
        return cleaner;
    }

    @Override
    public void run() {
        clean(generation);
    }

    private void clean(long currentGeneration) {
        if (!running || generation != currentGeneration || Thread.currentThread().isInterrupted()) return;
        long currentTime = System.currentTimeMillis();
        cleanQueue("primary", currentTime);
        cleanQueue("secondary", currentTime);
        cleanQueue("tertiary", currentTime);
    }

    private void cleanQueue(String queueName, long currentTime) {
        Collection<ChatMessage> messages = messageQueue.getQueue(queueName);
        messages.removeIf(message -> currentTime - message.getTimestamp() > expirationTime);
    }

    // Method to stop the scheduler gracefully
    public synchronized void stop() {
        running = false;
        generation++;
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }
    }
}
