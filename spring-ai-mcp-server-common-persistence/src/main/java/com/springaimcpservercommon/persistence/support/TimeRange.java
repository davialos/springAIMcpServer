package com.springaimcpservercommon.persistence.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Half-open time range {@code [from, to)}. Every read over a partitioned table takes one, so PostgreSQL prunes
 * partitions instead of scanning all of them.
 *
 * @param from inclusive lower bound
 * @param to   exclusive upper bound, after {@code from}
 */
public record TimeRange(Instant from, Instant to) {

    /** Longest range a single query may span. */
    public static final Duration MAX_SPAN = Duration.ofDays(400);

    /**
     * Validates the bounds.
     */
    public TimeRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("time range end must be after its start");
        }
        if (Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
            throw new IllegalArgumentException("time range must not exceed " + MAX_SPAN.toDays() + " days");
        }
    }

    /**
     * The range ending at {@code end} and spanning {@code span}.
     *
     * @param end  exclusive end
     * @param span duration
     * @return the range
     */
    public static TimeRange lastUntil(Instant end, Duration span) {
        return new TimeRange(end.minus(span), end);
    }
}
