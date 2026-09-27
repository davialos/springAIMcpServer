-- =====================================================================================================
-- Migration V4: reviewed write proposals (ADR-0009, LLD-11). Every write requested through AI assistance,
-- MCP or a write endpoint is a proposal until its owner explicitly confirms it; applying runs through the
-- host's own write path so the host's versioning/audit tables record the change. These tables hold the
-- decision trail only; data history stays owned by the host (one owner per fact).
-- =====================================================================================================

CREATE TABLE dai_change_proposal
(
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    workspace_id         uuid        NOT NULL,
    origin               text        NOT NULL,
    channel              text        NOT NULL,
    conversation_id      uuid,
    turn_id              uuid,
    tool_invocation_id   uuid,
    mcp_session_id       uuid,
    owner_id             uuid        NOT NULL,
    target_kind          text        NOT NULL,
    target_ref           text        NOT NULL,
    target_args          jsonb,
    change_kind          text        NOT NULL,
    state                text        NOT NULL DEFAULT 'PROPOSED',
    approval_requirement text        NOT NULL DEFAULT 'SELF_CONFIRM',
    required_approvals   smallint    NOT NULL DEFAULT 0,
    content_hash         text        NOT NULL,
    summary              text        NOT NULL,
    validation           jsonb,
    idempotency_key      text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    expires_at           timestamptz NOT NULL,
    confirmed_at         timestamptz,
    confirmed_by         uuid,
    applied_at           timestamptz,
    host_revision_ref    text,
    failure_code         text,
    failure_message      text,
    retention_until      timestamptz NOT NULL,
    row_version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_change_proposal PRIMARY KEY (id),
    CONSTRAINT ck_change_proposal_origin CHECK (origin IN ('AGENT_TOOL', 'MCP_TOOL', 'WRITE_ENDPOINT')),
    CONSTRAINT ck_change_proposal_channel CHECK (channel IN ('CHAT', 'PLAYGROUND', 'MCP', 'ENDPOINT')),
    CONSTRAINT ck_change_proposal_target_kind CHECK (target_kind IN ('HOST_OPERATION', 'ENTITY_WRITE')),
    CONSTRAINT ck_change_proposal_target_ref CHECK (
        (target_kind = 'HOST_OPERATION' AND target_ref LIKE 'op:%')
            OR (target_kind = 'ENTITY_WRITE' AND target_ref LIKE 'entity:%')),
    CONSTRAINT ck_change_proposal_change_kind CHECK (change_kind IN ('CREATE', 'UPDATE', 'DELETE', 'BULK')),
    CONSTRAINT ck_change_proposal_state CHECK (state IN ('PROPOSED', 'EDITED', 'AWAITING_APPROVAL', 'CONFIRMED', 'APPLYING',
                                                         'APPLIED', 'REJECTED', 'EXPIRED', 'CONFLICT', 'FAILED')),
    CONSTRAINT ck_change_proposal_approval CHECK (approval_requirement IN ('SELF_CONFIRM', 'SELF_CONFIRM_PLUS_APPROVER')),
    CONSTRAINT ck_change_proposal_required_approvals CHECK (
        (approval_requirement = 'SELF_CONFIRM' AND required_approvals = 0)
            OR (approval_requirement = 'SELF_CONFIRM_PLUS_APPROVER' AND required_approvals BETWEEN 1 AND 5)),
    CONSTRAINT ck_change_proposal_hash CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_change_proposal_expiry CHECK (expires_at > created_at),
    -- only the owner can confirm their own proposal (LLD-11 §9)
    CONSTRAINT ck_change_proposal_confirmer CHECK (confirmed_by IS NULL OR confirmed_by = owner_id),
    CONSTRAINT ck_change_proposal_confirmed CHECK (
        state NOT IN ('CONFIRMED', 'APPLYING', 'APPLIED') OR (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)),
    CONSTRAINT ck_change_proposal_applied CHECK ((state = 'APPLIED') = (applied_at IS NOT NULL)),
    CONSTRAINT ck_change_proposal_failure CHECK (state NOT IN ('FAILED', 'CONFLICT') OR failure_code IS NOT NULL),
    CONSTRAINT fk_change_proposal_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_change_proposal_conversation FOREIGN KEY (conversation_id) REFERENCES dai_conversation (id) ON DELETE SET NULL,
    CONSTRAINT fk_change_proposal_mcp_session FOREIGN KEY (mcp_session_id) REFERENCES dai_mcp_session (id),
    CONSTRAINT fk_change_proposal_owner FOREIGN KEY (owner_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_change_proposal_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES dai_principal (id)
);
CREATE INDEX ix_change_proposal_owner ON dai_change_proposal (owner_id, state, created_at DESC);
CREATE INDEX ix_change_proposal_workspace ON dai_change_proposal (workspace_id, created_at DESC);
CREATE INDEX ix_change_proposal_pending_expiry ON dai_change_proposal (expires_at)
    WHERE state IN ('PROPOSED', 'EDITED', 'AWAITING_APPROVAL');
CREATE INDEX ix_change_proposal_awaiting_approval ON dai_change_proposal (workspace_id, created_at)
    WHERE state = 'AWAITING_APPROVAL';
-- crash reconciliation scans proposals stuck in APPLYING (LLD-11 §10)
CREATE INDEX ix_change_proposal_applying ON dai_change_proposal (confirmed_at) WHERE state = 'APPLYING';
CREATE INDEX ix_change_proposal_target ON dai_change_proposal (target_ref, created_at DESC);
CREATE INDEX ix_change_proposal_conversation ON dai_change_proposal (conversation_id) WHERE conversation_id IS NOT NULL;
CREATE INDEX ix_change_proposal_retention ON dai_change_proposal (retention_until);
CREATE UNIQUE INDEX uq_change_proposal_idempotency ON dai_change_proposal (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- One row per record touched (1 for single changes, up to max-records-per-proposal for BULK).
CREATE TABLE dai_change_proposal_record
(
    proposal_id        uuid    NOT NULL,
    seq                integer NOT NULL,
    entity_ref         text    NOT NULL,
    entity_id          text,
    before_values      jsonb,
    after_values       jsonb,
    base_version_kind  text,
    base_version_value text,
    CONSTRAINT pk_change_proposal_record PRIMARY KEY (proposal_id, seq),
    CONSTRAINT ck_change_proposal_record_seq CHECK (seq >= 0),
    CONSTRAINT ck_change_proposal_record_entity CHECK (entity_ref LIKE 'entity:%'),
    CONSTRAINT ck_change_proposal_record_values CHECK (before_values IS NOT NULL OR after_values IS NOT NULL),
    CONSTRAINT ck_change_proposal_record_version_kind CHECK (base_version_kind IS NULL OR base_version_kind IN
        ('JPA_VERSION', 'ENVERS_REVISION', 'HISTORY_TABLE', 'TEMPORAL', 'ROW_HASH', 'CUSTOM')),
    CONSTRAINT ck_change_proposal_record_version CHECK ((base_version_kind IS NULL) = (base_version_value IS NULL)),
    CONSTRAINT fk_change_proposal_record_proposal FOREIGN KEY (proposal_id) REFERENCES dai_change_proposal (id) ON DELETE CASCADE
);
CREATE INDEX ix_change_proposal_record_entity ON dai_change_proposal_record (entity_ref, entity_id) WHERE entity_id IS NOT NULL;
COMMENT ON COLUMN dai_change_proposal_record.before_values IS 'Masked view of exposed attributes only; never sensitive values.';

-- State machine history (who moved the proposal, when, with what details — e.g. edited fields).
CREATE TABLE dai_change_proposal_event
(
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    proposal_id uuid        NOT NULL,
    seq         integer     NOT NULL,
    from_state  text,
    to_state    text        NOT NULL,
    actor_id    uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    details     jsonb,
    CONSTRAINT pk_change_proposal_event PRIMARY KEY (id),
    CONSTRAINT uq_change_proposal_event_seq UNIQUE (proposal_id, seq),
    CONSTRAINT ck_change_proposal_event_state CHECK (to_state IN ('PROPOSED', 'EDITED', 'AWAITING_APPROVAL', 'CONFIRMED', 'APPLYING',
                                                                  'APPLIED', 'REJECTED', 'EXPIRED', 'CONFLICT', 'FAILED')),
    CONSTRAINT fk_change_proposal_event_proposal FOREIGN KEY (proposal_id) REFERENCES dai_change_proposal (id) ON DELETE CASCADE,
    CONSTRAINT fk_change_proposal_event_actor FOREIGN KEY (actor_id) REFERENCES dai_principal (id)
);
CREATE TRIGGER trg_change_proposal_event_append_only
    BEFORE UPDATE ON dai_change_proposal_event FOR EACH ROW EXECUTE FUNCTION dai_forbid_modification();

-- Second-person approvals (four-eyes for sensitive writes). Approver must differ from the owner.
CREATE TABLE dai_change_proposal_approval
(
    proposal_id uuid        NOT NULL,
    approver_id uuid        NOT NULL,
    decision    text        NOT NULL,
    comment     text,
    decided_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_change_proposal_approval PRIMARY KEY (proposal_id, approver_id),
    CONSTRAINT ck_change_proposal_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_change_proposal_approval_comment CHECK (decision = 'APPROVED' OR comment IS NOT NULL),
    CONSTRAINT fk_change_proposal_approval_proposal FOREIGN KEY (proposal_id) REFERENCES dai_change_proposal (id) ON DELETE CASCADE,
    CONSTRAINT fk_change_proposal_approval_approver FOREIGN KEY (approver_id) REFERENCES dai_principal (id)
);

CREATE OR REPLACE FUNCTION dai_proposal_approval_segregation_of_duties() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM dai_change_proposal p WHERE p.id = NEW.proposal_id AND p.owner_id = NEW.approver_id) THEN
        RAISE EXCEPTION 'segregation of duties: the proposal owner cannot approve proposal %', NEW.proposal_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_change_proposal_approval_sod
    BEFORE INSERT OR UPDATE ON dai_change_proposal_approval FOR EACH ROW EXECUTE FUNCTION dai_proposal_approval_segregation_of_duties();
