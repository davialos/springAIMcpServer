package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.channel.OutboxWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxWorkerTest {

    private static final Duration BASE = Duration.ofSeconds(10);
    private static final Duration MAX = Duration.ofHours(1);

    @Test
    void backOffDoublesPerAttemptAndIsCapped() {
        assertThat(OutboxWorker.backoff(1, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(OutboxWorker.backoff(2, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(20));
        assertThat(OutboxWorker.backoff(3, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(40));
        assertThat(OutboxWorker.backoff(20, BASE, MAX, 0.5)).isEqualTo(MAX);
        assertThat(OutboxWorker.backoff(1000, BASE, MAX, 0.5)).as("no overflow").isEqualTo(MAX);
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(OutboxWorker.backoff(2, BASE, MAX, 0.0)).isEqualTo(Duration.ofSeconds(16));
        assertThat(OutboxWorker.backoff(2, BASE, MAX, 0.999)).isBetween(Duration.ofMillis(23_900), Duration.ofSeconds(24));
    }
}
