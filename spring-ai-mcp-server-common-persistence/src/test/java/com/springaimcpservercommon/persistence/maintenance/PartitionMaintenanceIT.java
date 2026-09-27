package com.springaimcpservercommon.persistence.maintenance;

import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
import com.springaimcpservercommon.persistence.telemetry.TelemetryStore;
import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Partition maintenance against PostgreSQL: future partitions, retention, advisory lock, job runs. */
class PartitionMaintenanceIT {

    private static DaiPersistenceUnit unit;
    private static PartitionMaintenance maintenance;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        unit = PostgresTestSupport.startFreshUnit();
        Clock clock = Clock.systemUTC();
        maintenance = new PartitionMaintenance(unit, new ChangeProposalStore(unit, clock, Duration.ofDays(7)),
                new TelemetryStore(unit, clock), clock, "node-it", Map.of("dai_model_call", 2));
        jdbc = PostgresTestSupport.jdbc();
    }

    @AfterAll
    static void stop() {
        unit.close();
    }

    private static boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                unit.schema() + "." + table));
    }

    private static String month(YearMonth month) {
        return month.format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    @Test
    void createsFuturePartitionsDropsExpiredOnesAndRecordsTheRun() {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        jdbc.update("UPDATE " + unit.schema() + ".dai_partitioned_table SET months_ahead = 5 WHERE table_name = 'dai_agent_turn'");
        YearMonth old = current.minusMonths(6);
        String oldPartition = "dai_model_call_p" + month(old);
        jdbc.execute("CREATE TABLE " + unit.schema() + "." + oldPartition + " PARTITION OF " + unit.schema()
                + ".dai_model_call FOR VALUES FROM ('" + old.atDay(1) + " 00:00:00+00') TO ('"
                + old.plusMonths(1).atDay(1) + " 00:00:00+00')");
        assertThat(tableExists("dai_agent_turn_p" + month(current.plusMonths(5)))).isFalse();

        MaintenanceResult result = maintenance.run();

        assertThat(result.outcome()).as(result.failures().toString()).isEqualTo(JobOutcome.SUCCESS);
        assertThat(tableExists("dai_agent_turn_p" + month(current.plusMonths(4)))).isTrue();
        assertThat(tableExists("dai_agent_turn_p" + month(current.plusMonths(5)))).isTrue();
        assertThat(result.partitionsCreated()).isGreaterThanOrEqualTo(2);
        // retention override of 2 months for dai_model_call drops the 6-month-old partition
        assertThat(tableExists(oldPartition)).isFalse();
        assertThat(result.partitionsDropped()).isGreaterThanOrEqualTo(1);
        // registry retention (13 months) keeps last month's turn partition
        assertThat(tableExists("dai_agent_turn_p" + month(current.minusMonths(1)))).isTrue();

        Map<String, Object> run = jdbc.queryForMap("SELECT job_name, node_id, outcome, items, finished_at FROM "
                + unit.schema() + ".dai_job_run WHERE id = ?", result.jobRunId());
        assertThat(run).containsEntry("job_name", "partition-maintenance").containsEntry("node_id", "node-it")
                .containsEntry("outcome", "SUCCESS");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(((Number) run.get("items")).intValue()).isEqualTo(result.items());

        MaintenanceResult second = maintenance.run();
        assertThat(second.outcome()).isEqualTo(JobOutcome.SUCCESS);
        assertThat(second.partitionsCreated()).isZero();
    }

    @Test
    void reportsRowsInTheDefaultPartition() {
        // a row far in the future has no monthly partition and lands in the DEFAULT partition
        UUID workspace = PostgresTestSupport.workspace(unit.schema());
        UUID principal = PostgresTestSupport.principal(unit.schema());
        jdbc.update("INSERT INTO " + unit.schema() + ".dai_agent_turn (id, started_at, ended_at, workspace_id, principal_id, "
                + "channel, finish_reason, outcome) VALUES (?, now() + interval '5 years', now() + interval '5 years', ?, ?, "
                + "'CHAT', 'STOP', 'SUCCESS')", UUID.randomUUID(), workspace, principal);

        MaintenanceResult result = maintenance.run();

        assertThat(result.nonEmptyDefaultPartitions()).contains("dai_agent_turn_pdefault");
    }

    @Test
    void skipsWhenAnotherNodeHoldsTheLock() throws Exception {
        try (Connection other = PostgresTestSupport.dataSource().getConnection();
             Statement s = other.createStatement()) {
            s.execute("SELECT pg_advisory_lock(" + PartitionMaintenance.LOCK_KEY + ")");
            try {
                MaintenanceResult result = maintenance.run();
                assertThat(result.outcome()).isEqualTo(JobOutcome.SKIPPED);
                assertThat(jdbc.queryForObject("SELECT outcome FROM " + unit.schema() + ".dai_job_run WHERE id = ?",
                        String.class, result.jobRunId())).isEqualTo("SKIPPED");
            } finally {
                s.execute("SELECT pg_advisory_unlock(" + PartitionMaintenance.LOCK_KEY + ")");
            }
        }
    }
}
