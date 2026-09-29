package com.springaimcpservercommon.autoconfigure;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtlSnapshotTest {

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    @Test
    void loadsOnceWithinTheTtlAndAgainAfterIt() {
        AtomicInteger loads = new AtomicInteger();
        TtlSnapshot<Integer> snapshot = new TtlSnapshot<>("test", loads::incrementAndGet, Duration.ofSeconds(5), clock);

        assertThat(snapshot.get()).isEqualTo(1);
        assertThat(snapshot.get()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(6));
        assertThat(snapshot.get()).isEqualTo(2);
        assertThat(loads).hasValue(2);
    }

    @Test
    void aFailedReloadKeepsServingTheLastGoodValue() {
        AtomicInteger calls = new AtomicInteger();
        TtlSnapshot<String> snapshot = new TtlSnapshot<>("test", () -> {
            if (calls.incrementAndGet() > 1) {
                throw new IllegalStateException("store down");
            }
            return "good";
        }, Duration.ofSeconds(5), clock);

        assertThat(snapshot.get()).isEqualTo("good");
        clock.advance(Duration.ofSeconds(6));
        assertThat(snapshot.get()).isEqualTo("good");
        assertThat(snapshot.get()).isEqualTo("good");
        assertThat(calls).hasValue(2);
    }

    @Test
    void aFailureBeforeAnyValueWasLoadedReachesTheCaller() {
        TtlSnapshot<String> snapshot = new TtlSnapshot<>("test", () -> {
            throw new IllegalStateException("store down");
        }, Duration.ofSeconds(5), clock);

        assertThatThrownBy(snapshot::get).isInstanceOf(IllegalStateException.class);
    }
}
