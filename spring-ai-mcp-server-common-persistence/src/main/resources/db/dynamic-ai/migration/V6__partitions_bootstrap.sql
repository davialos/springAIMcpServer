-- =====================================================================================================
-- Migration V6: create monthly partitions for the previous month, the current month and 3 months ahead for
-- every partitioned table. After deployment the library's partition-maintenance job (daily, advisory-locked)
-- keeps creating future partitions and drops expired ones (docs/lld/15-database-schema.md §6).
-- The DEFAULT partitions only exist as a safety net: rows landing there raise an alert; they should stay empty.
-- =====================================================================================================

SELECT dai_ensure_monthly_partitions('dai_agent_turn'::regclass, 1, 3);
SELECT dai_ensure_monthly_partitions('dai_model_call'::regclass, 1, 3);
SELECT dai_ensure_monthly_partitions('dai_mcp_request'::regclass, 1, 3);
SELECT dai_ensure_monthly_partitions('dai_tool_invocation'::regclass, 1, 3);
SELECT dai_ensure_monthly_partitions('dai_audit_event'::regclass, 1, 3);
SELECT dai_ensure_monthly_partitions('dai_audit_evidence'::regclass, 1, 3);

-- Registry of partitioned tables and their default retention (months), read by the maintenance job.
-- Hosts override retention with dynamic.ai.agent.store.retention.* properties (OQ-31).
CREATE TABLE dai_partitioned_table
(
    table_name       text     NOT NULL,
    retention_months smallint NOT NULL,
    months_ahead     smallint NOT NULL DEFAULT 3,
    CONSTRAINT pk_partitioned_table PRIMARY KEY (table_name),
    CONSTRAINT ck_partitioned_table_retention CHECK (retention_months BETWEEN 1 AND 120),
    CONSTRAINT ck_partitioned_table_ahead CHECK (months_ahead BETWEEN 1 AND 12)
);
INSERT INTO dai_partitioned_table (table_name, retention_months)
VALUES ('dai_agent_turn', 13),
       ('dai_model_call', 13),
       ('dai_mcp_request', 13),
       ('dai_tool_invocation', 13),
       ('dai_audit_event', 14),     -- ≥ 400 days (LLD-09 §7)
       ('dai_audit_evidence', 14);
