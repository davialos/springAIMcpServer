package com.springaimcpservercommon.persistence.unit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Time-zone-sensitive constraints must behave in UTC whatever the session {@code TimeZone} is. Regression test for
 * {@code ck_usage_hourly_bucket}, which used session-zone {@code date_trunc} and rejected valid UTC hour buckets on
 * sessions with a half-hour offset (Asia/Kolkata, +05:30).
 */
class SessionTimeZoneIT {

    private static DaiPersistenceUnit unit;
    private static UUID workspace;

    @BeforeAll
    static void start() {
        unit = PostgresTestSupport.startFreshUnit();
        workspace = PostgresTestSupport.workspace(unit.schema());
    }

    @AfterAll
    static void stop() {
        unit.close();
    }

    private void insertBucket(Connection c, OffsetDateTime bucket) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + unit.schema() + ".dai_usage_hourly "
                + "(bucket_start, workspace_id, provider, model) VALUES (?, ?, 'openai', ?)")) {
            ps.setObject(1, bucket);
            ps.setObject(2, workspace);
            ps.setString(3, "m-" + UUID.randomUUID());
            ps.executeUpdate();
        }
    }

    @Test
    void utcHourBucketIsAcceptedInAHalfHourOffsetSession() throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("SET TIME ZONE 'Asia/Kolkata'");
            }
            try {
                insertBucket(c, OffsetDateTime.parse("2026-09-28T10:00:00Z"));
                assertThatThrownBy(() -> insertBucket(c, OffsetDateTime.parse("2026-09-28T10:30:00Z")))
                        .isInstanceOfSatisfying(SQLException.class,
                                e -> assertThat(e.getSQLState()).isEqualTo("23514"));
            } finally {
                try (Statement s = c.createStatement()) {
                    s.execute("RESET TIME ZONE");
                }
            }
        }
    }
}
