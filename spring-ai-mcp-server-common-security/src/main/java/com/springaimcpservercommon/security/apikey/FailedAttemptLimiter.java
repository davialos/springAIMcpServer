package com.springaimcpservercommon.security.apikey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, in-memory limiter of failed authentication attempts per client (SEC-02 S1): after {@code maxFailures}
 * failures inside a fixed window, the client is blocked until the window ends. Memory is bounded by
 * {@code maxClients} (least recently seen clients are forgotten first). Limits are per node (SEC-02 §5 residual risk).
 */
public final class FailedAttemptLimiter {

    private static final class Window {
        Instant start;
        int failures;

        Window(Instant start) {
            this.start = start;
        }
    }

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Window> windows;

    /**
     * Creates a limiter.
     *
     * @param maxFailures failures allowed per window (≥ 1)
     * @param window      window length
     * @param maxClients  bound of tracked clients
     * @param clock       time source
     */
    public FailedAttemptLimiter(int maxFailures, Duration window, int maxClients, Clock clock) {
        if (maxFailures < 1 || maxClients < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("invalid limiter settings");
        }
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.windows = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                return size() > maxClients;
            }
        };
    }

    /**
     * Default: 10 failures per minute, 10 000 tracked clients.
     *
     * @param clock time source
     * @return a limiter
     */
    public static FailedAttemptLimiter defaults(Clock clock) {
        return new FailedAttemptLimiter(10, Duration.ofMinutes(1), 10_000, clock);
    }

    /**
     * Returns how long the client stays blocked.
     *
     * @param client client key (IP address)
     * @return zero if not blocked
     */
    public Duration blockedFor(String client) {
        Instant now = clock.instant();
        lock.lock();
        try {
            Window w = windows.get(client);
            if (w == null || w.failures < maxFailures) {
                return Duration.ZERO;
            }
            Instant end = w.start.plus(window);
            if (!end.isAfter(now)) {
                windows.remove(client);
                return Duration.ZERO;
            }
            return Duration.between(now, end);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a failed attempt.
     *
     * @param client client key (IP address)
     */
    public void recordFailure(String client) {
        Instant now = clock.instant();
        lock.lock();
        try {
            Window w = windows.get(client);
            if (w == null || !w.start.plus(window).isAfter(now)) {
                w = new Window(now);
                windows.put(client, w);
            }
            w.failures++;
        } finally {
            lock.unlock();
        }
    }
}
