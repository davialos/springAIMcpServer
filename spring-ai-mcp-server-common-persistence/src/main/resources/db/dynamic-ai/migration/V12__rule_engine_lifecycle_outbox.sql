-- =====================================================================================================
-- Migration V12: rule-engine lifecycle, outbox and evaluation-log partitioning (OQ-66, OQ-67, OQ-68; LLD-18).
--   1. dai_re_evaluation / dai_re_evaluation_result become monthly range partitions registered with the
--      maintenance job (creation of future partitions, retention drop).                       (OQ-68)
--   2. dai_re_revision: draft -> submitted -> approved -> published history of every rule and rule group,
--      with reviewer separation and rollback.                                                  (OQ-66)
--   3. dai_re_dispatch: transactional-outbox style delivery queue for e-mail / push / API with retry and
--      back-off, claimed with FOR UPDATE SKIP LOCKED so any number of nodes can drain it.      (OQ-67)
-- =====================================================================================================

-- ---------------------------------------------------------------------------------------------------
-- 1. Partition the evaluation log by evaluated_at (same pattern as dai_agent_turn, LLD-15 §6)
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE tmp_re_evaluation AS SELECT * FROM dai_re_evaluation;
CREATE TEMP TABLE tmp_re_evaluation_result AS
SELECT r.*, e.evaluated_at FROM dai_re_evaluation_result r JOIN dai_re_evaluation e ON e.id = r.evaluation_id;
DROP TABLE dai_re_evaluation_result;
DROP TABLE dai_re_evaluation;

CREATE TABLE dai_re_evaluation
(
    id               uuid        NOT NULL,
    evaluated_at     timestamptz NOT NULL,
    tenant_id        uuid        NOT NULL,
    organization_id  uuid,
    rule_group_id    uuid        NOT NULL,
    trigger_point_id uuid,
    policy           text        NOT NULL,
    decision         text        NOT NULL,
    language         text,
    duration_micros  bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_re_evaluation PRIMARY KEY (id, evaluated_at),
    CONSTRAINT ck_re_evaluation_decision CHECK (decision IN ('ALLOW', 'WARN', 'BLOCK'))
) PARTITION BY RANGE (evaluated_at);
CREATE INDEX ix_re_evaluation_group ON dai_re_evaluation (tenant_id, rule_group_id, evaluated_at DESC);
CREATE TABLE dai_re_evaluation_pdefault PARTITION OF dai_re_evaluation DEFAULT;
COMMENT ON TABLE dai_re_evaluation IS 'One row per group evaluation. No input values are stored. Monthly partitions, dropped by retention (dai_partitioned_table).';

CREATE TABLE dai_re_evaluation_result
(
    evaluation_id uuid        NOT NULL,
    evaluated_at  timestamptz NOT NULL,
    rule_id       uuid        NOT NULL,
    sequence      integer     NOT NULL,
    outcome       text        NOT NULL,
    action        text        NOT NULL,
    error_code    text,
    -- no foreign key to dai_re_evaluation: a partitioned parent cannot be referenced without its partition key and
    -- both tables are dropped together by month
    CONSTRAINT pk_re_evaluation_result PRIMARY KEY (evaluation_id, rule_id, evaluated_at),
    CONSTRAINT ck_re_evaluation_result_outcome CHECK (outcome IN ('TRUE', 'FALSE', 'ERROR')),
    CONSTRAINT ck_re_evaluation_result_action CHECK (action IN ('ALLOW', 'WARN', 'BLOCK'))
) PARTITION BY RANGE (evaluated_at);
CREATE TABLE dai_re_evaluation_result_pdefault PARTITION OF dai_re_evaluation_result DEFAULT;

-- create partitions back to the oldest migrated row so nothing lands in the DEFAULT partition
DO
$$
    DECLARE
        v_back integer;
    BEGIN
        SELECT GREATEST(1, COALESCE((extract(year FROM age(now(), min(evaluated_at))) * 12
            + extract(month FROM age(now(), min(evaluated_at))))::integer + 1, 1))
          INTO v_back FROM tmp_re_evaluation;
        PERFORM dai_ensure_monthly_partitions('dai_re_evaluation'::regclass, v_back, 3);
        PERFORM dai_ensure_monthly_partitions('dai_re_evaluation_result'::regclass, v_back, 3);
    END
$$;

INSERT INTO dai_re_evaluation (id, evaluated_at, tenant_id, organization_id, rule_group_id, trigger_point_id, policy,
                               decision, language, duration_micros)
SELECT id, evaluated_at, tenant_id, organization_id, rule_group_id, trigger_point_id, policy, decision, language,
       duration_micros
FROM tmp_re_evaluation;
INSERT INTO dai_re_evaluation_result (evaluation_id, evaluated_at, rule_id, sequence, outcome, action, error_code)
SELECT evaluation_id, evaluated_at, rule_id, sequence, outcome, action, error_code
FROM tmp_re_evaluation_result;
DROP TABLE tmp_re_evaluation_result;
DROP TABLE tmp_re_evaluation;

INSERT INTO dai_partitioned_table (table_name, retention_months)
VALUES ('dai_re_evaluation', 13),
       ('dai_re_evaluation_result', 13);

-- ---------------------------------------------------------------------------------------------------
-- 2. Rule / rule-group lifecycle: revisions
--    The dai_re_rule / dai_re_rule_group rows keep the PUBLISHED content (the only thing the engine reads);
--    edits happen in a revision and reach the row only when the revision is published.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_re_revision
(
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL,
    kind          text        NOT NULL,
    subject_id    uuid        NOT NULL,
    revision_no   integer     NOT NULL,
    state         text        NOT NULL DEFAULT 'DRAFT',
    -- the whole definition: rule = name, description, expression, bundles, actions;
    -- group = name, description, policy, match_on, composite bundles/actions, on_error, members[]
    content       jsonb       NOT NULL,
    change_note   text,
    rollback_of   uuid,
    created_by    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    submitted_by  text,
    submitted_at  timestamptz,
    reviewed_by   text,
    reviewed_at   timestamptz,
    review_comment text,
    published_by  text,
    published_at  timestamptz,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_re_revision PRIMARY KEY (id),
    CONSTRAINT uq_re_revision_no UNIQUE (kind, subject_id, revision_no),
    CONSTRAINT ck_re_revision_kind CHECK (kind IN ('RULE', 'GROUP')),
    CONSTRAINT ck_re_revision_state CHECK (state IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_re_revision_no CHECK (revision_no >= 1),
    -- four eyes: whoever submitted a revision cannot also review it
    CONSTRAINT ck_re_revision_reviewer CHECK (reviewed_by IS NULL OR submitted_by IS NULL OR reviewed_by <> submitted_by),
    CONSTRAINT fk_re_revision_rollback FOREIGN KEY (rollback_of) REFERENCES dai_re_revision (id)
);
-- at most one revision in flight per subject; history (PUBLISHED / SUPERSEDED) is unlimited
CREATE UNIQUE INDEX uq_re_revision_open ON dai_re_revision (kind, subject_id)
    WHERE state IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED');
CREATE INDEX ix_re_revision_subject ON dai_re_revision (kind, subject_id, revision_no DESC);
CREATE INDEX ix_re_revision_queue ON dai_re_revision (tenant_id, state) WHERE state IN ('SUBMITTED', 'APPROVED');
CREATE TRIGGER trg_re_revision_touch BEFORE UPDATE ON dai_re_revision
    FOR EACH ROW EXECUTE FUNCTION dai_touch_updated_at();
COMMENT ON TABLE dai_re_revision IS 'Revision history of rules and rule groups: DRAFT -> SUBMITTED -> APPROVED -> PUBLISHED (SUPERSEDED when replaced); rollback publishes a copy of an older revision.';

ALTER TABLE dai_re_rule ADD COLUMN published_revision_id uuid REFERENCES dai_re_revision (id);
ALTER TABLE dai_re_rule_group ADD COLUMN published_revision_id uuid REFERENCES dai_re_revision (id);

-- Rules and groups that exist already (V11 content) get a PUBLISHED revision 1 when they are ACTIVE, otherwise a DRAFT.
INSERT INTO dai_re_revision (tenant_id, kind, subject_id, revision_no, state, content, created_by, published_by, published_at)
SELECT r.tenant_id, 'RULE', r.id, 1, CASE WHEN r.status = 'ACTIVE' THEN 'PUBLISHED' ELSE 'DRAFT' END,
       jsonb_build_object('name', r.name, 'description', r.description, 'expression', r.cel_expression,
                          'trueMessageBundleId', r.true_message_bundle_id, 'falseMessageBundleId', r.false_message_bundle_id,
                          'trueAction', r.true_action, 'falseAction', r.false_action),
       'migration-v12', CASE WHEN r.status = 'ACTIVE' THEN 'migration-v12' END,
       CASE WHEN r.status = 'ACTIVE' THEN now() END
FROM dai_re_rule r WHERE r.status IN ('ACTIVE', 'DRAFT');
UPDATE dai_re_rule r SET published_revision_id = v.id
FROM dai_re_revision v WHERE v.kind = 'RULE' AND v.subject_id = r.id AND v.state = 'PUBLISHED';

INSERT INTO dai_re_revision (tenant_id, kind, subject_id, revision_no, state, content, created_by, published_by, published_at)
SELECT g.tenant_id, 'GROUP', g.id, 1, CASE WHEN g.status = 'ACTIVE' THEN 'PUBLISHED' ELSE 'DRAFT' END,
       jsonb_build_object('name', g.name, 'description', g.description, 'policy', g.evaluation_policy,
                          'matchOn', g.match_on, 'compositeTrueBundleId', g.composite_true_bundle_id,
                          'compositeFalseBundleId', g.composite_false_bundle_id,
                          'compositeTrueAction', g.composite_true_action, 'compositeFalseAction', g.composite_false_action,
                          'onError', g.on_error,
                          'members', COALESCE((SELECT jsonb_agg(jsonb_build_object('ruleId', m.rule_id, 'sequence', m.sequence,
                                                                                    'enabled', m.enabled) ORDER BY m.sequence)
                                               FROM dai_re_rule_group_rule m WHERE m.group_id = g.id), '[]'::jsonb)),
       'migration-v12', CASE WHEN g.status = 'ACTIVE' THEN 'migration-v12' END,
       CASE WHEN g.status = 'ACTIVE' THEN now() END
FROM dai_re_rule_group g WHERE g.status IN ('ACTIVE', 'DRAFT');
UPDATE dai_re_rule_group g SET published_revision_id = v.id
FROM dai_re_revision v WHERE v.kind = 'GROUP' AND v.subject_id = g.id AND v.state = 'PUBLISHED';

-- ---------------------------------------------------------------------------------------------------
-- 3. Delivery outbox
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_re_dispatch
(
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    channel_type    text        NOT NULL,
    binding_id      uuid,
    api_endpoint_id uuid,
    -- what to send. EMAIL/PUSH payloads hold the recipient (personal data): they are replaced by '{}' on delivery
    -- and purged with dead rows after the retention below.
    payload         jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL DEFAULT 6,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    -- lease: a worker that dies leaves a lease that expires, the row is then claimable again
    locked_until    timestamptz,
    locked_by       text,
    last_error      text,
    delivered_at    timestamptz,
    CONSTRAINT pk_re_dispatch PRIMARY KEY (id),
    CONSTRAINT ck_re_dispatch_channel CHECK (channel_type IN ('EMAIL', 'PUSH', 'API')),
    CONSTRAINT ck_re_dispatch_status CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    CONSTRAINT ck_re_dispatch_attempts CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 50),
    CONSTRAINT ck_re_dispatch_api CHECK (channel_type <> 'API' OR api_endpoint_id IS NOT NULL)
);
CREATE INDEX ix_re_dispatch_due ON dai_re_dispatch (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_re_dispatch_tenant ON dai_re_dispatch (tenant_id, status, created_at DESC);
CREATE INDEX ix_re_dispatch_cleanup ON dai_re_dispatch (updated_at) WHERE status <> 'PENDING';
CREATE TRIGGER trg_re_dispatch_touch BEFORE UPDATE ON dai_re_dispatch
    FOR EACH ROW EXECUTE FUNCTION dai_touch_updated_at();
COMMENT ON TABLE dai_re_dispatch IS 'Outbox of rule-engine communications: at-least-once delivery with retry/back-off; the dispatch id is sent so receivers can de-duplicate.';
