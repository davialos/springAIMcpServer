-- =====================================================================================================
-- Migration V5: tamper-evident audit (hash chains), encrypted evidence mode, usage ledger, budgets, prices,
-- maintenance job runs. Design: LLD-10 §4–6, ADR-0018, docs/lld/15-database-schema.md §5.
-- =====================================================================================================

-- ---------------------------------------------------------------------------------------------------
-- Audit hash chains. One chain per workspace (chain_id = workspace uuid text) plus 'system' and 'global-admin',
-- so appends to different workspaces never contend. The head row is locked (SELECT … FOR UPDATE) while an event
-- is appended, which serialises seq/hash assignment inside one chain.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_audit_chain
(
    chain_id      text        NOT NULL,
    last_seq      bigint      NOT NULL DEFAULT 0,
    last_hash     text        NOT NULL,
    last_event_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_chain PRIMARY KEY (chain_id),
    CONSTRAINT ck_audit_chain_id CHECK (chain_id ~ '^[a-z0-9-]{3,64}$'),
    CONSTRAINT ck_audit_chain_seq CHECK (last_seq >= 0),
    CONSTRAINT ck_audit_chain_hash CHECK (last_hash ~ '^sha256:[0-9a-f]{64}$')
);
COMMENT ON COLUMN dai_audit_chain.last_hash IS 'Genesis value: sha256 of the chain id (written by the application when the chain is created).';

CREATE TABLE dai_audit_event
(
    id                 uuid        NOT NULL,
    occurred_at        timestamptz NOT NULL,
    chain_id           text        NOT NULL,
    chain_seq          bigint      NOT NULL,
    category           text        NOT NULL,
    action             text        NOT NULL,
    plane              text        NOT NULL,
    actor_id           uuid,
    actor_type         text        NOT NULL,
    on_behalf_of_id    uuid,
    workspace_id       uuid,
    resource_type      text,
    resource_id        text,
    decision           text        NOT NULL,
    reason             text,
    environment_id     text        NOT NULL,
    trace_id           text,
    turn_id            uuid,
    tool_invocation_id uuid,
    proposal_id        uuid,
    mcp_session_id     uuid,
    details            jsonb,
    prev_hash          text        NOT NULL,
    hash               text        NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_audit_event_category CHECK (category IN ('ADMIN', 'SECURITY', 'INVOCATION', 'DATA_WRITE', 'SYSTEM')),
    CONSTRAINT ck_audit_event_action CHECK (action ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT ck_audit_event_plane CHECK (plane IN ('CONTROL', 'DATA', 'AGENT', 'MCP', 'SYSTEM')),
    CONSTRAINT ck_audit_event_actor_type CHECK (actor_type IN ('USER', 'GROUP', 'SERVICE_ACCOUNT', 'MCP_CLIENT', 'SYSTEM')),
    CONSTRAINT ck_audit_event_actor CHECK ((actor_type = 'SYSTEM') = (actor_id IS NULL)),
    CONSTRAINT ck_audit_event_decision CHECK (decision IN ('PERMIT', 'DENY', 'NOT_APPLICABLE')),
    CONSTRAINT ck_audit_event_deny_reason CHECK (decision <> 'DENY' OR reason IS NOT NULL),
    CONSTRAINT ck_audit_event_seq CHECK (chain_seq > 0),
    CONSTRAINT ck_audit_event_hashes CHECK (prev_hash ~ '^sha256:[0-9a-f]{64}$' AND hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_event_details CHECK (details IS NULL OR jsonb_typeof(details) = 'object'),
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_audit_event_on_behalf_of FOREIGN KEY (on_behalf_of_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_audit_event_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX ix_audit_event_chain ON dai_audit_event (chain_id, chain_seq);
CREATE INDEX ix_audit_event_workspace ON dai_audit_event (workspace_id, occurred_at DESC);
CREATE INDEX ix_audit_event_actor ON dai_audit_event (actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX ix_audit_event_action ON dai_audit_event (action, occurred_at DESC);
CREATE INDEX ix_audit_event_resource ON dai_audit_event (resource_type, resource_id, occurred_at DESC) WHERE resource_id IS NOT NULL;
CREATE INDEX ix_audit_event_denied ON dai_audit_event (occurred_at DESC) WHERE decision = 'DENY';
CREATE INDEX ix_audit_event_proposal ON dai_audit_event (proposal_id) WHERE proposal_id IS NOT NULL;
CREATE INDEX ix_audit_event_turn ON dai_audit_event (turn_id) WHERE turn_id IS NOT NULL;
CREATE INDEX ix_audit_event_trace ON dai_audit_event (trace_id) WHERE trace_id IS NOT NULL;
CREATE TABLE dai_audit_event_pdefault PARTITION OF dai_audit_event DEFAULT;
CREATE TRIGGER trg_audit_event_append_only BEFORE UPDATE OR DELETE ON dai_audit_event
    FOR EACH ROW EXECUTE FUNCTION dai_forbid_modification();
COMMENT ON TABLE dai_audit_event IS 'Standard audit tier (ADR-0018): actors, decisions, filters, counts, IDs and result hashes; never prompt or row content.';
COMMENT ON COLUMN dai_audit_event.hash IS 'sha256(prev_hash || canonical event fields); verify a chain by recomputing in chain_seq order.';

-- ---------------------------------------------------------------------------------------------------
-- Evidence mode (opt-in, ADR-0018): envelope-encrypted full content, keyed per data subject for crypto-shredding
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_evidence_subject_key
(
    subject_key_id text        NOT NULL,
    principal_id   uuid,
    kek_ref        text        NOT NULL,
    wrapped_key    bytea,
    created_at     timestamptz NOT NULL DEFAULT now(),
    shredded_at    timestamptz,
    shredded_by    uuid,
    CONSTRAINT pk_evidence_subject_key PRIMARY KEY (subject_key_id),
    CONSTRAINT ck_evidence_subject_key_shred CHECK ((shredded_at IS NULL) = (wrapped_key IS NOT NULL)),
    CONSTRAINT fk_evidence_subject_key_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_evidence_subject_key_shredded_by FOREIGN KEY (shredded_by) REFERENCES dai_principal (id)
);
COMMENT ON COLUMN dai_evidence_subject_key.wrapped_key IS 'Data key wrapped by the host KMS key (kek_ref). Set to NULL to crypto-shred all evidence of the subject.';

CREATE TABLE dai_audit_evidence
(
    id              uuid        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    audit_event_id  uuid        NOT NULL,
    subject_key_id  text        NOT NULL,
    content_type    text        NOT NULL,
    nonce           bytea       NOT NULL,
    ciphertext      bytea       NOT NULL,
    retention_until timestamptz NOT NULL,
    CONSTRAINT pk_audit_evidence PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_audit_evidence_content_type CHECK (content_type IN ('PROMPT', 'COMPLETION', 'TOOL_ARGUMENTS', 'TOOL_RESULT',
                                                                      'PROPOSAL_PAYLOAD')),
    CONSTRAINT fk_audit_evidence_subject_key FOREIGN KEY (subject_key_id) REFERENCES dai_evidence_subject_key (subject_key_id)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX ix_audit_evidence_event ON dai_audit_evidence (audit_event_id);
CREATE INDEX ix_audit_evidence_subject ON dai_audit_evidence (subject_key_id);
CREATE TABLE dai_audit_evidence_pdefault PARTITION OF dai_audit_evidence DEFAULT;
CREATE TRIGGER trg_audit_evidence_append_only BEFORE UPDATE OR DELETE ON dai_audit_evidence
    FOR EACH ROW EXECUTE FUNCTION dai_forbid_modification();

CREATE TABLE dai_evidence_legal_hold
(
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    subject_key_id text        NOT NULL,
    reference      text        NOT NULL,
    placed_by      uuid        NOT NULL,
    placed_at      timestamptz NOT NULL DEFAULT now(),
    released_by    uuid,
    released_at    timestamptz,
    CONSTRAINT pk_evidence_legal_hold PRIMARY KEY (id),
    CONSTRAINT ck_evidence_legal_hold_release CHECK ((released_at IS NULL) = (released_by IS NULL)),
    CONSTRAINT fk_evidence_legal_hold_subject FOREIGN KEY (subject_key_id) REFERENCES dai_evidence_subject_key (subject_key_id),
    CONSTRAINT fk_evidence_legal_hold_placed_by FOREIGN KEY (placed_by) REFERENCES dai_principal (id),
    CONSTRAINT fk_evidence_legal_hold_released_by FOREIGN KEY (released_by) REFERENCES dai_principal (id)
);
CREATE INDEX ix_evidence_legal_hold_active ON dai_evidence_legal_hold (subject_key_id) WHERE released_at IS NULL;

-- ---------------------------------------------------------------------------------------------------
-- Prices, budgets and hourly usage ledger (LLD-10 §5–6, F-70)
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_model_price
(
    provider                      text        NOT NULL,
    model                         text        NOT NULL,
    valid_from                    timestamptz NOT NULL,
    currency                      char(3)     NOT NULL,
    input_per_mtok_micros         bigint      NOT NULL,
    output_per_mtok_micros        bigint      NOT NULL,
    cached_input_per_mtok_micros  bigint      NOT NULL DEFAULT 0,
    created_at                    timestamptz NOT NULL DEFAULT now(),
    created_by                    uuid,
    CONSTRAINT pk_model_price PRIMARY KEY (provider, model, valid_from),
    CONSTRAINT ck_model_price_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_model_price_values CHECK (input_per_mtok_micros >= 0 AND output_per_mtok_micros >= 0
        AND cached_input_per_mtok_micros >= 0),
    CONSTRAINT fk_model_price_created_by FOREIGN KEY (created_by) REFERENCES dai_principal (id)
);
COMMENT ON TABLE dai_model_price IS 'Versioned by valid_from: the price for a call is the row with the greatest valid_from <= call time.';

CREATE TABLE dai_budget
(
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    scope             text        NOT NULL,
    workspace_id      uuid,
    agent_resource_id uuid,
    principal_id      uuid,
    period            text        NOT NULL,
    limit_tokens      bigint,
    limit_cost_micros bigint,
    currency          char(3),
    soft_limit_pct    smallint    NOT NULL DEFAULT 80,
    hard_limit        boolean     NOT NULL DEFAULT true,
    enabled           boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        uuid,
    row_version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_budget PRIMARY KEY (id),
    CONSTRAINT uq_budget UNIQUE NULLS NOT DISTINCT (scope, workspace_id, agent_resource_id, principal_id, period),
    CONSTRAINT ck_budget_scope CHECK (
        (scope = 'GLOBAL' AND workspace_id IS NULL AND agent_resource_id IS NULL AND principal_id IS NULL)
            OR (scope = 'WORKSPACE' AND workspace_id IS NOT NULL AND agent_resource_id IS NULL AND principal_id IS NULL)
            OR (scope = 'AGENT' AND workspace_id IS NOT NULL AND agent_resource_id IS NOT NULL AND principal_id IS NULL)
            OR (scope = 'PRINCIPAL' AND principal_id IS NOT NULL AND agent_resource_id IS NULL)),
    CONSTRAINT ck_budget_period CHECK (period IN ('DAY', 'MONTH')),
    CONSTRAINT ck_budget_limit CHECK (limit_tokens IS NOT NULL OR limit_cost_micros IS NOT NULL),
    CONSTRAINT ck_budget_limit_values CHECK ((limit_tokens IS NULL OR limit_tokens > 0) AND (limit_cost_micros IS NULL OR limit_cost_micros > 0)),
    CONSTRAINT ck_budget_currency CHECK ((limit_cost_micros IS NULL) = (currency IS NULL) AND (currency IS NULL OR currency ~ '^[A-Z]{3}$')),
    CONSTRAINT ck_budget_soft_limit CHECK (soft_limit_pct BETWEEN 1 AND 100),
    CONSTRAINT fk_budget_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_budget_agent FOREIGN KEY (agent_resource_id) REFERENCES dai_resource (id),
    CONSTRAINT fk_budget_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_budget_created_by FOREIGN KEY (created_by) REFERENCES dai_principal (id),
    CONSTRAINT fk_budget_updated_by FOREIGN KEY (updated_by) REFERENCES dai_principal (id)
);
CREATE TRIGGER trg_budget_touch BEFORE UPDATE ON dai_budget FOR EACH ROW EXECUTE FUNCTION dai_touch_updated_at();

-- Aggregated per hour for budgets and dashboards; upserted (ON CONFLICT … DO UPDATE) by the metering writer.
-- Derived data: fully rebuildable from dai_model_call for the partitions still retained.
CREATE TABLE dai_usage_hourly
(
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    bucket_start        timestamptz NOT NULL,
    workspace_id        uuid        NOT NULL,
    agent_resource_id   uuid,
    principal_id        uuid,
    provider            text        NOT NULL,
    model               text        NOT NULL,
    currency            char(3),
    calls               integer     NOT NULL DEFAULT 0,
    input_tokens        bigint      NOT NULL DEFAULT 0,
    output_tokens       bigint      NOT NULL DEFAULT 0,
    cached_input_tokens bigint      NOT NULL DEFAULT 0,
    cost_micros         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_usage_hourly PRIMARY KEY (id),
    CONSTRAINT uq_usage_hourly UNIQUE NULLS NOT DISTINCT (bucket_start, workspace_id, agent_resource_id, principal_id, provider, model, currency),
    CONSTRAINT ck_usage_hourly_bucket CHECK (bucket_start = date_trunc('hour', bucket_start)),
    CONSTRAINT ck_usage_hourly_values CHECK (calls >= 0 AND input_tokens >= 0 AND output_tokens >= 0
        AND cached_input_tokens >= 0 AND cost_micros >= 0),
    CONSTRAINT fk_usage_hourly_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_usage_hourly_agent FOREIGN KEY (agent_resource_id) REFERENCES dai_resource (id),
    CONSTRAINT fk_usage_hourly_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id)
);
CREATE INDEX ix_usage_hourly_workspace ON dai_usage_hourly (workspace_id, bucket_start DESC);
CREATE INDEX ix_usage_hourly_principal ON dai_usage_hourly (principal_id, bucket_start DESC) WHERE principal_id IS NOT NULL;
CREATE INDEX ix_usage_hourly_agent ON dai_usage_hourly (agent_resource_id, bucket_start DESC) WHERE agent_resource_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------
-- Maintenance job runs (partition creation, retention, proposal expiry, reconciliation) for observability.
-- Jobs coordinate across nodes with pg_try_advisory_lock; this table only records outcomes.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_job_run
(
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    job_name    text        NOT NULL,
    node_id     text        NOT NULL,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    outcome     text,
    items       integer,
    message     text,
    CONSTRAINT pk_job_run PRIMARY KEY (id),
    CONSTRAINT ck_job_run_name CHECK (job_name ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT ck_job_run_outcome CHECK (outcome IS NULL OR outcome IN ('SUCCESS', 'PARTIAL', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_job_run_finish CHECK ((finished_at IS NULL) = (outcome IS NULL))
);
CREATE INDEX ix_job_run_name ON dai_job_run (job_name, started_at DESC);
