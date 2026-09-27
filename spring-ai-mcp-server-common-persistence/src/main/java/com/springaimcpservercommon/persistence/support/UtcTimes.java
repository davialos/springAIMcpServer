package com.springaimcpservercommon.persistence.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Timestamp precision rules of the store. PostgreSQL {@code timestamptz} keeps microseconds and <em>rounds</em>
 * extra digits; every instant written by this module is therefore truncated to microseconds in Java first, so the
 * value read back equals the value written (required for audit hashes) and a row never rounds into the next monthly
 * partition.
 */
public final class UtcTimes {

    /**
     * ISO-8601 UTC rendering with exactly six fractional digits, e.g. {@code 2026-09-28T10:15:30.123456Z}; the
     * timestamp format of the audit canonical form.
     */
    public static final DateTimeFormatter MICROS_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private UtcTimes() {
    }

    /**
     * Truncates to microsecond precision.
     *
     * @param instant any instant
     * @return the instant truncated to microseconds
     */
    public static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Current instant of a clock, truncated to microseconds.
     *
     * @param clock the clock
     * @return now, in microsecond precision
     */
    public static Instant now(Clock clock) {
        return micros(clock.instant());
    }

    /**
     * Formats with {@link #MICROS_FORMAT} after truncation to microseconds.
     *
     * @param instant the instant
     * @return canonical text
     */
    public static String format(Instant instant) {
        return MICROS_FORMAT.format(micros(instant));
    }
}
