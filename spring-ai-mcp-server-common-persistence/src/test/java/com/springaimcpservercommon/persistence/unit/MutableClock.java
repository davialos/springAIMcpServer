package com.springaimcpservercommon.persistence.unit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock that tests can move forward. */
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
     * Moves the clock.
     *
     * @param duration amount to advance
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
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
