package com.springaimcpservercommon.jfranalyzer.collect;

import java.time.Duration;
import java.time.Instant;

/**
 * The time range the recording covers.
 *
 * @param start first event start
 * @param end   last event end
 */
public record Span(Instant start, Instant end) {

    /** @return length in milliseconds (at least 1, so it can divide) */
    public long millis() {
        return Math.max(1, Duration.between(start, end).toMillis());
    }

    /** @return length in seconds */
    public double seconds() {
        return millis() / 1000.0;
    }

    /**
     * @param t an instant
     * @return milliseconds since {@link #start()}
     */
    public long offset(Instant t) {
        return Duration.between(start, t).toMillis();
    }
}
