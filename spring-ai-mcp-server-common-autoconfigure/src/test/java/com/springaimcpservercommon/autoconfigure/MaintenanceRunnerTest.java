package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.config.NodeHeartbeat;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceRunnerTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final MaintenanceRunner.Identity IDENTITY =
            new MaintenanceRunner.Identity("node-1", "billing", "1.2.3", "0.1.0");

    private static DaiProperties.Maintenance settings(String cron) {
        return new DaiProperties.Maintenance(true, cron, Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofDays(30), Duration.ofMinutes(10), null);
    }

    private static MaintenanceRunner.Steps steps(List<NodeHeartbeat> beats, long applied) {
        return new MaintenanceRunner.Steps(() -> {}, () -> applied, beats::add, () -> 0, () -> 0, () -> 0, () -> {});
    }

    private static MaintenanceRunner runner(MaintenanceRunner.Steps steps, String cron) {
        return new MaintenanceRunner(steps, IDENTITY, settings(cron), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void heartbeatReportsIdentityAndTheServedGeneration() {
        List<NodeHeartbeat> beats = new ArrayList<>();
        try (var runner = runner(steps(beats, 7), "0 17 3 * * *")) {
            runner.heartbeat();
        }
        assertThat(beats).singleElement().satisfies(b -> {
            assertThat(b.nodeId()).isEqualTo("node-1");
            assertThat(b.appliedGeneration()).isEqualTo(7L);
            assertThat(b.hostApplication()).isEqualTo("billing");
            assertThat(b.hostVersion()).isEqualTo("1.2.3");
            assertThat(b.libraryVersion()).isEqualTo("0.1.0");
            assertThat(b.startedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aNodeThatLoadedNothingReportsNoGeneration() {
        List<NodeHeartbeat> beats = new ArrayList<>();
        try (var runner = runner(steps(beats, 0), "0 17 3 * * *")) {
            runner.heartbeat();
        }
        assertThat(beats).singleElement().extracting(NodeHeartbeat::appliedGeneration).isNull();
    }

    @Test
    void aFailingStepNeverPropagatesAndNeverStopsTheOthers() {
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger failedApplies = new AtomicInteger();
        var steps = new MaintenanceRunner.Steps(
                () -> { throw new IllegalStateException("secret row data"); },
                () -> 1L,
                beat -> { throw new IllegalStateException("db down"); },
                () -> { throw new IllegalStateException("db down"); },
                () -> { expired.incrementAndGet(); return 2; },
                () -> { failedApplies.incrementAndGet(); return 1; },
                () -> { throw new IllegalStateException("db down"); });
        try (var runner = runner(steps, "0 17 3 * * *")) {
            runner.pollSnapshots();
            runner.heartbeat();
            runner.sweep();
            runner.partitionMaintenance();
        }
        assertThat(expired).hasValue(1);
        assertThat(failedApplies).hasValue(1);
    }

    @Test
    void theCronIsEvaluatedInUtc() {
        try (var runner = runner(steps(new ArrayList<>(), 1), "0 17 3 * * *")) {
            // 10:00 UTC -> next 03:17 UTC is 17h17m away
            assertThat(runner.untilNextCron(NOW)).isEqualTo(Duration.ofHours(17).plusMinutes(17));
            // exactly at the fire time -> the next day's run, not an immediate re-run
            assertThat(runner.untilNextCron(Instant.parse("2026-09-29T03:17:00Z"))).isEqualTo(Duration.ofDays(1));
        }
    }

    @Test
    void settingsAreValidated() {
        assertThatThrownBy(() -> settings("not a cron")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cron");
        assertThatThrownBy(() -> new DaiProperties.Maintenance(true, "0 17 3 * * *", Duration.ofMillis(10),
                Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofDays(30),
                Duration.ofMinutes(10), null)).hasMessageContaining("snapshot-poll-interval");
        assertThatThrownBy(() -> new DaiProperties.Maintenance(true, "0 17 3 * * *", Duration.ofSeconds(5),
                Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofDays(30),
                Duration.ofMinutes(10), " ")).hasMessageContaining("node-id");
    }
}
