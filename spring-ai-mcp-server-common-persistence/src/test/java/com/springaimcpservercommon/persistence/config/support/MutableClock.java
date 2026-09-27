package com.springaimcpservercommon.persistence.config.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock that tests move forward explicitly. Thread-safe. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    /**
     * Creates the clock.
     *
     * @param start initial instant
     */
    public MutableClock(Instant start) {
        this.now = start;
    }

    /**
     * Moves the clock forward.
     *
     * @param duration how far
     */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("UTC only");
    }

    @Override
    public Instant instant() {
        return now;
    }
}
