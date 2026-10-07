package com.springaimcpservercommon.ruleengine.repo;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** The evaluation audit and the channel dispatch log. */
@Repository
public class LogRepository {

    /** A stored evaluation. */
    public record EvaluationRow(UUID id, long tenantId, String moduleCode, @Nullable String triggerRef,
                                String overallResult, String action, String language, String results) { }

    /** A stored dispatch attempt. */
    public record DispatchRow(long id, UUID evaluationId, long channelId, String channelType, String status,
                              @Nullable String detail) { }

    private final JdbcClient jdbc;

    public LogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void saveEvaluation(UUID id, long tenantId, @Nullable Long organizationId, String moduleCode,
                               @Nullable String triggerRef, String overall, String action, String language,
                               @Nullable String contextKeys, String resultsJson) {
        jdbc.sql("insert into re_evaluation_log (id, tenant_id, organization_id, module_code, trigger_ref, "
                        + "overall_result, action, language, context_keys, results) values (:i, :t, :o, :m, :r, :x, "
                        + ":a, :l, :k, :j)")
                .param("i", id).param("t", tenantId).param("o", organizationId).param("m", moduleCode)
                .param("r", triggerRef).param("x", overall).param("a", action).param("l", language)
                .param("k", contextKeys).param("j", resultsJson).update();
    }

    public List<EvaluationRow> evaluations(long tenantId, int limit) {
        return jdbc.sql("select id, tenant_id, module_code, trigger_ref, overall_result, action, language, results "
                        + "from re_evaluation_log where tenant_id = :t order by created_at desc limit :n")
                .param("t", tenantId).param("n", limit).query(EvaluationRow.class).list();
    }

    public void saveDispatch(UUID evaluationId, long channelId, String channelType, String status,
                             @Nullable String detail) {
        jdbc.sql("insert into re_dispatch_log (evaluation_id, channel_id, channel_type, status, detail) "
                        + "values (:e, :c, :t, :s, :d)")
                .param("e", evaluationId).param("c", channelId).param("t", channelType).param("s", status)
                .param("d", detail).update();
    }

    public List<DispatchRow> dispatches(UUID evaluationId) {
        return jdbc.sql("select id, evaluation_id, channel_id, channel_type, status, detail from re_dispatch_log "
                + "where evaluation_id = :e order by id").param("e", evaluationId).query(DispatchRow.class).list();
    }
}
