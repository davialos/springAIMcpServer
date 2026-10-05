package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.core.id.Ids;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Writes the value-free audit trail ({@code dai_re_audit_log}, migration V12). Called inside the transaction of the
 * change it records, so the change and its log entry commit or roll back together. Callers pass only ids, codes, counts
 * and decisions in {@code details}: never fact values and never message texts.
 */
@Repository
class AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void record(Caller c, @Nullable UUID organizationId, String action, String entityType, @Nullable UUID entityId,
                @Nullable String entityCode, String summary, Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO dai_re_audit_log (id, tenant_id, organization_id, actor_id, actor_name, actor_role,
                                                      action, entity_type, entity_id, entity_code, summary, details)
                        VALUES (:id, :t, :org, :actor, :name, :role, :action, :type, :entityId, :code, :summary,
                                CAST(:details AS jsonb))
                        """)
                .param("id", Ids.newId()).param("t", c.tenantId()).param("org", organizationId)
                .param("actor", c.userId()).param("name", c.displayName().isBlank() ? c.username() : c.displayName())
                .param("role", c.role()).param("action", action).param("type", entityType)
                .param("entityId", entityId).param("code", entityCode).param("summary", summary)
                .param("details", json.writeValueAsString(details)).update();
    }
}
