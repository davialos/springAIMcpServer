package com.springaimcpservercommon.persistence.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * Half-open time window [from, to) over hourly usage buckets.
 *
 * @param from inclusive start
 * @param to   exclusive end, after {@code from}
 */
public record UsageWindow(Instant from, Instant to) {

    /** Validates components. */
    public UsageWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("usage window end must be after its start");
        }
    }
}
