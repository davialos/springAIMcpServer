-- =====================================================================================================
-- Migration V12: authoring audit trail of the rule engine (ADR-0026, LLD-18 §12).
--
-- dai_re_evaluation (V11) answers "what did the engine decide". This table answers "who changed or ran what": every
-- authoring write (rule, rule group, trigger point, status change) and every evaluation requested through an API, with
-- the actor, the scope and a value-free summary. Evaluation INPUT VALUES are never stored here either (LLD-12): the
-- details document carries counts, ids and codes only.
--
-- Written in the same transaction as the change it records, by the service that exposes the authoring API.
-- tenant_id / organization_id are the opaque host ids of ADR-0005 (no foreign keys), exactly as in V11.
-- =====================================================================================================

CREATE TABLE dai_re_audit_log
(
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL,
    organization_id uuid,
    -- who: the host's user id (opaque), a display name for the log reader, and the role the request ran with
    actor_id        uuid,
    actor_name      text,
    actor_role      text,
    -- what: RULE_CREATED, RULE_GROUP_UPDATED, RULE_GROUP_EVALUATED, TRIGGER_POINT_CREATED ...
    action          text        NOT NULL,
    entity_type     text        NOT NULL,
    entity_id       uuid,
    entity_code     text,
    summary         text,
    -- counts, ids, codes, decision, duration: never fact values, never message texts
    details         jsonb,
    request_id      text,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_re_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_re_audit_log_action CHECK (action ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT ck_re_audit_log_entity_type CHECK (entity_type IN ('RULE', 'RULE_GROUP', 'TRIGGER_POINT', 'EVALUATION')),
    CONSTRAINT ck_re_audit_log_role CHECK (actor_role IS NULL OR actor_role IN ('USER', 'ADMIN'))
);
CREATE INDEX ix_re_audit_log_time ON dai_re_audit_log (tenant_id, occurred_at DESC);
CREATE INDEX ix_re_audit_log_entity ON dai_re_audit_log (tenant_id, entity_type, entity_id, occurred_at DESC);
COMMENT ON TABLE dai_re_audit_log IS 'Value-free audit trail of rule-engine authoring and evaluation requests (who, what, when, in which scope).';

-- Listing evaluations newest-first across every group of a tenant (admin log view, dashboards).
CREATE INDEX ix_re_evaluation_time ON dai_re_evaluation (tenant_id, evaluated_at DESC);
