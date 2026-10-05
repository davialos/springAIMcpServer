package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.memory.ChatMemoryStore;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes conversations whose retention ended (F-44), including their messages (cascade). Runs on one daemon
 * thread at a fixed interval, in bounded batches; rows locked by another node are skipped
 * ({@code FOR UPDATE SKIP LOCKED}), so every node can run it without coordination (ADR-0021). A failed run is
 * logged and retried at the next interval; nothing about the deleted content is read or logged.
 *
 * <p>It runs whenever the telemetry store exists, not only while recording is enabled, so turning recording off
 * never leaves old transcripts beyond their retention.
 */
@NullMarked
final class ConversationRetentionJob implements AutoCloseable {

    static final int BATCH = 500;
    static final int MAX_BATCHES_PER_RUN = 20;

    private static final Logger LOG = LoggerFactory.getLogger(ConversationRetentionJob.class);

    private final TelemetryStore store;
    private final @Nullable ChatMemoryStore memoryStore;
    private final com.springaimcpservercommon.persistence.chat.@Nullable ChatUiStore chatUiStore;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dai-conversation-retention");
        t.setDaemon(true);
        return t;
    });

    ConversationRetentionJob(TelemetryStore store, @Nullable ChatMemoryStore memoryStore, Duration interval) {
        this(store, memoryStore, null, interval);
    }

    ConversationRetentionJob(TelemetryStore store, @Nullable ChatMemoryStore memoryStore,
                             com.springaimcpservercommon.persistence.chat.@Nullable ChatUiStore chatUiStore,
                             Duration interval) {
        this.store = Objects.requireNonNull(store, "store");
        this.memoryStore = memoryStore;
        this.chatUiStore = chatUiStore;
        Objects.requireNonNull(interval, "interval");
        if (interval.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("interval must be at least one minute");
        }
        long millis = interval.toMillis();
        // Start after a random part of the interval so nodes started together do not run in lockstep.
        long initial = java.util.concurrent.ThreadLocalRandom.current().nextLong(millis / 2, millis);
        scheduler.scheduleWithFixedDelay(this::runOnce, initial, millis, TimeUnit.MILLISECONDS);
    }

    /** Purges up to {@value #MAX_BATCHES_PER_RUN} batches; package-private for tests. */
    void runOnce() {
        try {
            int total = 0;
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                int deleted = store.purgeExpiredConversations(BATCH);
                total += deleted;
                if (deleted < BATCH) {
                    break;
                }
            }
            if (total > 0) {
                LOG.info("Conversation retention purged {} conversation(s)", total);
                SafeMetrics.count("dynamic.ai.agent.conversation.purge.runs");
            }
            purgeMemory();
            purgeChatUi();
        } catch (RuntimeException e) {
            LOG.warn("Conversation retention run failed ({}); retrying at the next interval",
                    e.getClass().getSimpleName());
        }
    }

    /** Deletes expired chat-memory rows (OQ-45), in bounded batches like the conversations. */
    private void purgeMemory() {
        if (memoryStore == null) {
            return;
        }
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = memoryStore.purgeExpired(BATCH);
            total += deleted;
            if (deleted < BATCH) {
                break;
            }
        }
        if (total > 0) {
            LOG.info("Chat memory retention purged {} message(s)", total);
        }
    }

    /** Deletes expired chat components, answers and feedback (V14), in bounded batches. */
    private void purgeChatUi() {
        if (chatUiStore == null) {
            return;
        }
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = chatUiStore.purgeExpired(BATCH);
            total += deleted;
            if (deleted < BATCH) {
                break;
            }
        }
        if (total > 0) {
            LOG.info("Chat UI state retention purged {} row(s)", total);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
