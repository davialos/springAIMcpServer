-- =====================================================================================================
-- Migration V2: configuration resources, immutable revisions (versioning), reviews, published snapshots,
-- cluster node state, kill switches, grants, approved MCP clients and consent.
-- Design: docs/lld/09-persistence-and-config-lifecycle.md, docs/lld/15-database-schema.md
-- =====================================================================================================

-- ---------------------------------------------------------------------------------------------------
-- Resources: the stable identity of a configurable object. Its content lives in revisions.
-- The published revision is NOT stored here (one owner per fact): it is the revision in state PUBLISHED,
-- guaranteed unique per resource by uq_resource_revision_one_published.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_resource
(
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    workspace_id     uuid        NOT NULL,
    kind             text        NOT NULL,
    slug             text        NOT NULL,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    suspended_reason text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid        NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid,
    row_version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_resource PRIMARY KEY (id),
    CONSTRAINT uq_resource_slug UNIQUE (workspace_id, kind, slug),
    CONSTRAINT ck_resource_kind CHECK (kind IN ('ENDPOINT', 'QUERY', 'AGENT', 'TOOL_BINDING', 'ROW_POLICY',
                                                'POLICY_OVERLAY', 'MCP_SERVER')),
    CONSTRAINT ck_resource_slug CHECK (slug ~ '^[a-z][a-z0-9_-]{1,62}$'),
    CONSTRAINT ck_resource_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_resource_suspension CHECK ((status = 'SUSPENDED') = (suspended_reason IS NOT NULL)),
    CONSTRAINT fk_resource_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_resource_created_by FOREIGN KEY (created_by) REFERENCES dai_principal (id),
    CONSTRAINT fk_resource_updated_by FOREIGN KEY (updated_by) REFERENCES dai_principal (id)
);
CREATE INDEX ix_resource_kind ON dai_resource (kind, status);
CREATE TRIGGER trg_resource_touch BEFORE UPDATE ON dai_resource FOR EACH ROW EXECUTE FUNCTION dai_touch_updated_at();

CREATE TABLE dai_resource_revision
(
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    resource_id         uuid        NOT NULL,
    revision_no         integer     NOT NULL,
    state               text        NOT NULL DEFAULT 'DRAFT',
    spec                jsonb       NOT NULL,
    spec_schema_version integer     NOT NULL DEFAULT 1,
    spec_hash           text        NOT NULL,
    scan_fingerprint    text,
    change_summary      text,
    risk_score          smallint,
    based_on_id         uuid,
    author_id           uuid        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    submitted_at        timestamptz,
    approved_at         timestamptz,
    published_at        timestamptz,
    retired_at          timestamptz,
    row_version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_resource_revision PRIMARY KEY (id),
    CONSTRAINT uq_resource_revision_no UNIQUE (resource_id, revision_no),
    CONSTRAINT ck_resource_revision_no CHECK (revision_no > 0),
    CONSTRAINT ck_resource_revision_state CHECK (state IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'SUPERSEDED',
                                                           'REJECTED', 'STALE', 'DEPRECATED', 'RETIRED')),
    CONSTRAINT ck_resource_revision_spec_object CHECK (jsonb_typeof(spec) = 'object'),
    CONSTRAINT ck_resource_revision_hash CHECK (spec_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_resource_revision_risk CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_resource_revision_published_at CHECK (state NOT IN ('PUBLISHED', 'SUPERSEDED') OR published_at IS NOT NULL),
    CONSTRAINT fk_resource_revision_resource FOREIGN KEY (resource_id) REFERENCES dai_resource (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_revision_based_on FOREIGN KEY (based_on_id) REFERENCES dai_resource_revision (id),
    CONSTRAINT fk_resource_revision_author FOREIGN KEY (author_id) REFERENCES dai_principal (id)
);
-- exactly one live published revision per resource
CREATE UNIQUE INDEX uq_resource_revision_one_published ON dai_resource_revision (resource_id) WHERE state = 'PUBLISHED';
-- review inbox
CREATE INDEX ix_resource_revision_pending ON dai_resource_revision (state, submitted_at) WHERE state IN ('IN_REVIEW', 'APPROVED');
CREATE INDEX ix_resource_revision_author ON dai_resource_revision (author_id, created_at DESC);

-- Revisions are immutable once they leave DRAFT (versioning guarantee, LLD-09 §2).
CREATE OR REPLACE FUNCTION dai_revision_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.state <> 'DRAFT' AND (NEW.spec IS DISTINCT FROM OLD.spec
        OR NEW.spec_hash IS DISTINCT FROM OLD.spec_hash
        OR NEW.spec_schema_version IS DISTINCT FROM OLD.spec_schema_version
        OR NEW.author_id IS DISTINCT FROM OLD.author_id
        OR NEW.resource_id IS DISTINCT FROM OLD.resource_id
        OR NEW.revision_no IS DISTINCT FROM OLD.revision_no) THEN
        RAISE EXCEPTION 'revision % is immutable in state %', OLD.id, OLD.state USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_resource_revision_immutable
    BEFORE UPDATE ON dai_resource_revision FOR EACH ROW EXECUTE FUNCTION dai_revision_immutable();

-- Catalog elements a revision depends on, pinned with their signature hash for drift detection (LLD-03 §6).
CREATE TABLE dai_revision_reference
(
    revision_id    uuid NOT NULL,
    element_ref    text NOT NULL,
    signature_hash text NOT NULL,
    CONSTRAINT pk_revision_reference PRIMARY KEY (revision_id, element_ref),
    CONSTRAINT ck_revision_reference_ref CHECK (element_ref ~ '^(entity|attr|op|ctx):'),
    CONSTRAINT fk_revision_reference_revision FOREIGN KEY (revision_id) REFERENCES dai_resource_revision (id) ON DELETE CASCADE
);
CREATE INDEX ix_revision_reference_element ON dai_revision_reference (element_ref);

-- Other resources a revision uses (endpoint → query, agent → tool binding) for impact analysis and publish ordering.
CREATE TABLE dai_revision_dependency
(
    revision_id            uuid NOT NULL,
    depends_on_resource_id uuid NOT NULL,
    CONSTRAINT pk_revision_dependency PRIMARY KEY (revision_id, depends_on_resource_id),
    CONSTRAINT fk_revision_dependency_revision FOREIGN KEY (revision_id) REFERENCES dai_resource_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_revision_dependency_resource FOREIGN KEY (depends_on_resource_id) REFERENCES dai_resource (id)
);
CREATE INDEX ix_revision_dependency_target ON dai_revision_dependency (depends_on_resource_id);

-- ---------------------------------------------------------------------------------------------------
-- Reviews (four-eyes). Reviewer must differ from the author — enforced here as defence in depth.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_review
(
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    revision_id uuid        NOT NULL,
    reviewer_id uuid        NOT NULL,
    decision    text        NOT NULL,
    comment     text,
    decided_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_review PRIMARY KEY (id),
    CONSTRAINT uq_review_reviewer UNIQUE (revision_id, reviewer_id),
    CONSTRAINT ck_review_decision CHECK (decision IN ('APPROVED', 'REJECTED', 'CHANGES_REQUESTED')),
    CONSTRAINT ck_review_comment CHECK (decision = 'APPROVED' OR comment IS NOT NULL),
    CONSTRAINT fk_review_revision FOREIGN KEY (revision_id) REFERENCES dai_resource_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES dai_principal (id)
);

CREATE OR REPLACE FUNCTION dai_review_segregation_of_duties() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM dai_resource_revision r WHERE r.id = NEW.revision_id AND r.author_id = NEW.reviewer_id) THEN
        RAISE EXCEPTION 'segregation of duties: the author cannot review revision %', NEW.revision_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_review_sod BEFORE INSERT OR UPDATE ON dai_review FOR EACH ROW EXECUTE FUNCTION dai_review_segregation_of_duties();

-- ---------------------------------------------------------------------------------------------------
-- Published snapshots: monotonic generations with a normalized manifest (LLD-09 §4, ADR-0006)
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_snapshot
(
    generation             bigint GENERATED ALWAYS AS IDENTITY,
    published_by           uuid        NOT NULL,
    published_at           timestamptz NOT NULL DEFAULT now(),
    reason                 text,
    rollback_of_generation bigint,
    manifest_hash          text        NOT NULL,
    CONSTRAINT pk_snapshot PRIMARY KEY (generation),
    CONSTRAINT ck_snapshot_manifest_hash CHECK (manifest_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT fk_snapshot_published_by FOREIGN KEY (published_by) REFERENCES dai_principal (id),
    CONSTRAINT fk_snapshot_rollback_of FOREIGN KEY (rollback_of_generation) REFERENCES dai_snapshot (generation)
);
CREATE TRIGGER trg_snapshot_append_only BEFORE UPDATE OR DELETE ON dai_snapshot FOR EACH ROW EXECUTE FUNCTION dai_forbid_modification();

CREATE TABLE dai_snapshot_entry
(
    generation  bigint NOT NULL,
    resource_id uuid   NOT NULL,
    revision_id uuid   NOT NULL,
    CONSTRAINT pk_snapshot_entry PRIMARY KEY (generation, resource_id),
    CONSTRAINT fk_snapshot_entry_snapshot FOREIGN KEY (generation) REFERENCES dai_snapshot (generation),
    CONSTRAINT fk_snapshot_entry_resource FOREIGN KEY (resource_id) REFERENCES dai_resource (id),
    CONSTRAINT fk_snapshot_entry_revision FOREIGN KEY (revision_id) REFERENCES dai_resource_revision (id)
);
CREATE INDEX ix_snapshot_entry_revision ON dai_snapshot_entry (revision_id);
CREATE TRIGGER trg_snapshot_entry_append_only BEFORE UPDATE OR DELETE ON dai_snapshot_entry FOR EACH ROW EXECUTE FUNCTION dai_forbid_modification();

-- Per-node applied generation and heartbeat (cluster convergence, F-73, LLD-09 §4)
CREATE TABLE dai_node_state
(
    node_id            text        NOT NULL,
    applied_generation bigint,
    host_application   text        NOT NULL,
    host_version       text,
    library_version    text        NOT NULL,
    started_at         timestamptz NOT NULL,
    heartbeat_at       timestamptz NOT NULL,
    CONSTRAINT pk_node_state PRIMARY KEY (node_id),
    CONSTRAINT fk_node_state_generation FOREIGN KEY (applied_generation) REFERENCES dai_snapshot (generation)
);
CREATE INDEX ix_node_state_heartbeat ON dai_node_state (heartbeat_at);

-- ---------------------------------------------------------------------------------------------------
-- Kill switches (F-73). Active = cleared_at IS NULL AND (expires_at IS NULL OR expires_at > now()).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_kill_switch
(
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    scope        text        NOT NULL,
    workspace_id uuid,
    resource_id  uuid,
    tool_name    text,
    reason       text        NOT NULL,
    set_by       uuid        NOT NULL,
    set_at       timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz,
    cleared_by   uuid,
    cleared_at   timestamptz,
    CONSTRAINT pk_kill_switch PRIMARY KEY (id),
    CONSTRAINT ck_kill_switch_scope CHECK (
        (scope = 'GLOBAL' AND workspace_id IS NULL AND resource_id IS NULL AND tool_name IS NULL)
            OR (scope = 'WORKSPACE' AND workspace_id IS NOT NULL AND resource_id IS NULL AND tool_name IS NULL)
            OR (scope = 'RESOURCE' AND resource_id IS NOT NULL AND tool_name IS NULL)
            OR (scope = 'TOOL' AND tool_name IS NOT NULL AND resource_id IS NULL)),
    CONSTRAINT ck_kill_switch_tool_name CHECK (tool_name IS NULL OR tool_name ~ '^[a-z][a-z0-9_]{2,63}$'),
    CONSTRAINT ck_kill_switch_clear CHECK ((cleared_at IS NULL) = (cleared_by IS NULL)),
    CONSTRAINT fk_kill_switch_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_kill_switch_resource FOREIGN KEY (resource_id) REFERENCES dai_resource (id),
    CONSTRAINT fk_kill_switch_set_by FOREIGN KEY (set_by) REFERENCES dai_principal (id),
    CONSTRAINT fk_kill_switch_cleared_by FOREIGN KEY (cleared_by) REFERENCES dai_principal (id)
);
-- polled every ~2 s by every node: keep the active set tiny and indexed
CREATE INDEX ix_kill_switch_active ON dai_kill_switch (scope, set_at) WHERE cleared_at IS NULL;

-- ---------------------------------------------------------------------------------------------------
-- Grants: who may do what on which resource (SEC-01 §7). Workspace-wide when resource_id and pattern are NULL.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_grant
(
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    workspace_id     uuid        NOT NULL,
    principal_id     uuid        NOT NULL,
    permission       text        NOT NULL,
    resource_id      uuid,
    resource_pattern text,
    conditions       jsonb,
    expires_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid        NOT NULL,
    CONSTRAINT pk_grant PRIMARY KEY (id),
    CONSTRAINT ck_grant_permission CHECK (permission ~ '^[a-z][a-z-]*:[a-z][a-z-]*$'),
    CONSTRAINT ck_grant_target CHECK (num_nonnulls(resource_id, resource_pattern) <= 1),
    CONSTRAINT ck_grant_conditions CHECK (conditions IS NULL OR jsonb_typeof(conditions) = 'object'),
    CONSTRAINT fk_grant_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_grant_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_grant_resource FOREIGN KEY (resource_id) REFERENCES dai_resource (id) ON DELETE CASCADE,
    CONSTRAINT fk_grant_created_by FOREIGN KEY (created_by) REFERENCES dai_principal (id)
);
CREATE UNIQUE INDEX uq_grant ON dai_grant (workspace_id, principal_id, permission,
                                           coalesce(resource_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                           coalesce(resource_pattern, ''));
CREATE INDEX ix_grant_principal ON dai_grant (principal_id, permission);
CREATE INDEX ix_grant_resource ON dai_grant (resource_id) WHERE resource_id IS NOT NULL;
CREATE INDEX ix_grant_expiry ON dai_grant (expires_at) WHERE expires_at IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------
-- Approved MCP clients per workspace and per-user consent (LLD-07 §5.3)
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_mcp_client
(
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    workspace_id      uuid        NOT NULL,
    issuer            text        NOT NULL,
    client_id         text        NOT NULL,
    display_name      text        NOT NULL,
    registration_type text        NOT NULL,
    status            text        NOT NULL DEFAULT 'PENDING',
    approved_by       uuid,
    approved_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid        NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    row_version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mcp_client PRIMARY KEY (id),
    CONSTRAINT uq_mcp_client UNIQUE (workspace_id, issuer, client_id),
    CONSTRAINT ck_mcp_client_registration CHECK (registration_type IN ('PRE_REGISTERED', 'CIMD', 'DCR')),
    CONSTRAINT ck_mcp_client_status CHECK (status IN ('PENDING', 'APPROVED', 'REVOKED')),
    CONSTRAINT ck_mcp_client_approval CHECK (status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT fk_mcp_client_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_mcp_client_approved_by FOREIGN KEY (approved_by) REFERENCES dai_principal (id),
    CONSTRAINT fk_mcp_client_created_by FOREIGN KEY (created_by) REFERENCES dai_principal (id)
);
CREATE INDEX ix_mcp_client_lookup ON dai_mcp_client (issuer, client_id) WHERE status = 'APPROVED';
CREATE TRIGGER trg_mcp_client_touch BEFORE UPDATE ON dai_mcp_client FOR EACH ROW EXECUTE FUNCTION dai_touch_updated_at();

CREATE TABLE dai_mcp_client_consent
(
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    mcp_client_id uuid        NOT NULL,
    principal_id  uuid        NOT NULL,
    granted_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    CONSTRAINT pk_mcp_client_consent PRIMARY KEY (id),
    CONSTRAINT ck_mcp_client_consent_revoke CHECK (revoked_at IS NULL OR revoked_at >= granted_at),
    CONSTRAINT fk_mcp_client_consent_client FOREIGN KEY (mcp_client_id) REFERENCES dai_mcp_client (id),
    CONSTRAINT fk_mcp_client_consent_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id)
);
CREATE UNIQUE INDEX uq_mcp_client_consent_active ON dai_mcp_client_consent (mcp_client_id, principal_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_mcp_client_consent_principal ON dai_mcp_client_consent (principal_id);

CREATE TABLE dai_mcp_client_consent_scope
(
    consent_id uuid NOT NULL,
    scope      text NOT NULL,
    CONSTRAINT pk_mcp_client_consent_scope PRIMARY KEY (consent_id, scope),
    CONSTRAINT ck_mcp_client_consent_scope CHECK (scope IN ('dai.mcp.read', 'dai.mcp.propose', 'dai.mcp.agents')),
    CONSTRAINT fk_mcp_client_consent_scope_consent FOREIGN KEY (consent_id) REFERENCES dai_mcp_client_consent (id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------------------------------
-- Convenience view: currently published revision per resource
-- ---------------------------------------------------------------------------------------------------
CREATE VIEW dai_v_published_resource AS
SELECT r.id           AS resource_id,
       r.workspace_id,
       r.kind,
       r.slug,
       r.status,
       rev.id         AS revision_id,
       rev.revision_no,
       rev.spec,
       rev.spec_hash,
       rev.published_at
  FROM dai_resource r
  JOIN dai_resource_revision rev ON rev.resource_id = r.id AND rev.state = 'PUBLISHED';
