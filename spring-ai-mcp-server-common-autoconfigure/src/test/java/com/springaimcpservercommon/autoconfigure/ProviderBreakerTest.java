package com.springaimcpservercommon.autoconfigure;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderBreakerTest {

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private final ProviderBreaker breaker = new ProviderBreaker(3, Duration.ofSeconds(30), clock);

    @Test
    void staysClosedBelowTheThresholdAndASuccessResetsTheCount() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        breaker.onFailure();

        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void opensAfterTheThresholdAndRefusesCallsForTheOpenPeriod() {
        for (int i = 0; i < 3; i++) {
            breaker.onFailure();
        }

        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void afterTheOpenPeriodExactlyOneProbeIsAdmittedAndItsSuccessCloses() {
        for (int i = 0; i < 3; i++) {
            breaker.onFailure();
        }
        clock.advance(Duration.ofSeconds(31));

        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.tryAcquire()).isFalse(); // the probe is in flight
        breaker.onSuccess();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aFailedProbeReopensForAFullPeriod() {
        for (int i = 0; i < 3; i++) {
            breaker.onFailure();
        }
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onFailure();

        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.tryAcquire()).isTrue();
    }
}
