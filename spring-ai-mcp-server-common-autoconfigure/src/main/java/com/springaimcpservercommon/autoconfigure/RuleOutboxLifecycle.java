package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.channel.OutboxWorker;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the rule-engine {@link OutboxWorker} on one daemon thread for as long as the application context is running.
 * Several nodes may run it at once (rows are claimed with {@code SKIP LOCKED}). A failing run is logged and the loop
 * continues; the thread never makes the host fail to start or stop.
 */
@NullMarked
final class RuleOutboxLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(RuleOutboxLifecycle.class);

    private final OutboxWorker worker;
    private final Duration pollInterval;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    RuleOutboxLifecycle(OutboxWorker worker, Duration pollInterval) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dai-re-outbox");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runGuarded, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        running = true;
        LOG.info("rule-engine outbox worker started (poll every {})", pollInterval);
    }

    private void runGuarded() {
        try {
            worker.runOnce();
        } catch (RuntimeException e) {
            LOG.warn("rule-engine outbox run failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
