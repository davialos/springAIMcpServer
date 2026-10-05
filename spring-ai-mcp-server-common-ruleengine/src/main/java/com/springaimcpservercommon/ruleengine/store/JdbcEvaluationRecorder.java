package com.springaimcpservercommon.ruleengine.store;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.ruleengine.EvaluationRecorder;
import com.springaimcpservercommon.ruleengine.evaluation.GroupResult;
import com.springaimcpservercommon.ruleengine.evaluation.RuleResult;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes {@code dai_re_evaluation} / {@code dai_re_evaluation_result}: group, policy, decision and per-rule outcomes.
 * Input values are never stored. One short transaction per evaluation; failures propagate to the engine, which logs
 * and carries on.
 */
public final class JdbcEvaluationRecorder implements EvaluationRecorder {

    private final DataSource dataSource;
    private final String schema;

    /**
     * Creates the recorder.
     *
     * @param dataSource runtime data source
     * @param schema     schema holding the tables
     */
    public JdbcEvaluationRecorder(DataSource dataSource, String schema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public void record(UUID tenantId, @Nullable UUID organizationId, @Nullable UUID triggerPointId,
                       @Nullable String language, long durationMicros, GroupResult result) {
        UUID id = Ids.newId();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setSchema(schema);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO dai_re_evaluation (id, tenant_id, organization_id, rule_group_id, trigger_point_id,"
                                + " policy, decision, language, duration_micros) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setObject(1, id);
                    ps.setObject(2, tenantId);
                    ps.setObject(3, organizationId);
                    ps.setObject(4, result.group().id());
                    ps.setObject(5, triggerPointId);
                    ps.setString(6, result.group().policy().name());
                    ps.setString(7, result.decision().name());
                    ps.setString(8, language);
                    ps.setLong(9, durationMicros);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO dai_re_evaluation_result (evaluation_id, rule_id, sequence, outcome, action, error_code)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (RuleResult r : result.evaluated()) {
                        ps.setObject(1, id);
                        ps.setObject(2, r.ruleId());
                        ps.setInt(3, r.sequence());
                        ps.setString(4, r.outcome().name());
                        ps.setString(5, r.action().name());
                        ps.setString(6, r.errorCode());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new JdbcRuleStore.RuleStoreException("could not record evaluation: " + e.getMessage(), e);
        }
    }
}
