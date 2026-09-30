package com.springaimcpservercommon.autoconfigure;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs best-effort telemetry writes off the request path: each write gets a virtual thread (ADR-0007) and at most
 * {@code maxInFlight} run at once (release-it bulkhead). A write that finds the bulkhead full is dropped, and a
 * write that throws is dropped too; both are counted ({@code <prefix>.dropped}, {@code <prefix>.failures}) and never
 * reach the caller. Failures are logged once per hundred, with the exception class only (no content).
 */
@NullMarked
final class BoundedAsyncWriter implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore bulkhead;
    private final String metricPrefix;
    private final Logger log;
    private final AtomicLong failures = new AtomicLong();

    BoundedAsyncWriter(int maxInFlight, String metricPrefix, Logger log) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.bulkhead = new Semaphore(maxInFlight);
        this.metricPrefix = Objects.requireNonNull(metricPrefix, "metricPrefix");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Schedules a write.
     *
     * @param what  short description for the failure log (identifiers only, never content)
     * @param write the write
     */
    void submit(String what, Runnable write) {
        if (!bulkhead.tryAcquire()) {
            SafeMetrics.count(metricPrefix + ".dropped");
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    write.run();
                } catch (RuntimeException e) {
                    SafeMetrics.count(metricPrefix + ".failures");
                    if (failures.getAndIncrement() % 100 == 0) {
                        log.warn("{} failed ({}); further failures are logged every 100th", what,
                                e.getClass().getSimpleName());
                    }
                } finally {
                    bulkhead.release();
                }
            });
        } catch (RejectedExecutionException e) {
            bulkhead.release();
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
