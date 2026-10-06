-- =====================================================================================================
-- Dev-only history for the local rule-engine ecosystem (ADR-0026): makes the dashboards, the admin logs and the Grafana
-- panels show something on first start. NEVER a migration, never loaded in a real environment.
--
-- Apply AFTER scripts/rule-engine/sample-data.sql with search_path = dynamic_ai. Idempotent: does nothing when the tenant
-- already has evaluations. Everything is synthetic and value-free (like the real log): no input value exists to leak.
--   * ~400 evaluations over the last 24 hours, each with per-rule results and a decision that follows the group's policy
--   * a handful of audit trail entries (who created / changed what) over the last days
--   * two AI chat conversations of the Acme tenant (the library's own conversation tables)
-- =====================================================================================================
DO
$seed$
DECLARE
    acme    constant uuid := '11111111-1111-1111-1111-111111111111';
    retail  constant uuid := '22222222-2222-2222-2222-222222222222';
    corp    constant uuid := '22222222-2222-2222-2222-222222222223';
    adm     constant uuid := '33333333-0000-0000-0000-000000000001';
    usr     constant uuid := '33333333-0000-0000-0000-000000000002';
BEGIN
    IF EXISTS (SELECT 1 FROM dai_re_evaluation WHERE tenant_id = acme) THEN
        RAISE NOTICE 'dev-extra: evaluation history already present, skipped';
        RETURN;
    END IF;

    -- Every random() below is drawn in a SELECT LIST, so it is evaluated once per output row. (A LATERAL subquery that does
    -- not reference the outer row would be evaluated once and reused: one dice roll for the whole table.)

    -- one row per synthetic evaluation, spread over the 4 sample groups, last 24 hours
    CREATE TEMP TABLE tmp_eval ON COMMIT DROP AS
    SELECT x.*,
           CASE WHEN x.org_p < 0.55 THEN retail WHEN x.org_p < 0.85 THEN corp END AS org
    FROM (SELECT gen_random_uuid()                                               AS id,
                 g.id                                                            AS group_id,
                 g.evaluation_policy                                             AS policy,
                 g.on_error, g.composite_true_action, g.composite_false_action,
                 now() - (random() * interval '24 hours')                        AS at,
                 (150 + random() * 2400)::bigint                                 AS micros,
                 random()                                                        AS org_p,
                 (ARRAY ['en', 'hi', 'th'])[1 + floor(random() * 3)::int]       AS lang,
                 -- how bad the applicant is: most pass everything, some fail a rule, a few fail several, a few error
                 random()                                                        AS quality
          FROM generate_series(1, 400) n
                   JOIN LATERAL (SELECT id, evaluation_policy, on_error, composite_true_action, composite_false_action
                                 FROM dai_re_rule_group WHERE tenant_id = acme ORDER BY code OFFSET (n % 4) LIMIT 1) g
                        ON true) x;

    -- one row per (evaluation, rule) with its own random draw
    CREATE TEMP TABLE tmp_draw ON COMMIT DROP AS
    SELECT e.id AS evaluation_id, e.quality, e.on_error, r.id AS rule_id, r.code, r.true_action, r.false_action,
           gr.sequence, random() AS q
    FROM tmp_eval e
             JOIN dai_re_rule_group_rule gr ON gr.group_id = e.group_id AND gr.enabled
             JOIN dai_re_rule r ON r.id = gr.rule_id;

    -- per rule outcome; the action is the rule's own (or the group's on_error for an error)
    CREATE TEMP TABLE tmp_result ON COMMIT DROP AS
    SELECT d.evaluation_id, d.rule_id, d.sequence, d.outcome,
           CASE d.outcome WHEN 'TRUE' THEN d.true_action WHEN 'FALSE' THEN d.false_action ELSE d.on_error END AS action,
           CASE WHEN d.outcome = 'ERROR' THEN 'MISSING_PARAMETER' END AS error_code
    FROM (SELECT t.*,
                 CASE
                     WHEN t.quality > 0.97 AND t.q < 0.5 THEN 'ERROR'
                     WHEN t.quality > 0.80 AND t.q < 0.6 THEN 'FALSE'
                     WHEN t.quality > 0.55 AND t.q < 0.2 THEN 'FALSE'
                     -- the "review" rule (false = WARN) fails on its own now and then
                     WHEN t.code = 'HIGH_VALUE_REVIEW' AND t.q < 0.18 THEN 'FALSE'
                     ELSE 'TRUE' END AS outcome
          FROM tmp_draw t) d;

    -- FIRST_MATCH stops at the first rule that does not hold: later rules were never evaluated, so they leave no result
    DELETE FROM tmp_result t
        USING tmp_eval e
    WHERE e.id = t.evaluation_id
      AND e.policy = 'FIRST_MATCH'
      AND EXISTS (SELECT 1 FROM tmp_result p
                  WHERE p.evaluation_id = t.evaluation_id AND p.sequence < t.sequence AND p.outcome <> 'TRUE');

    INSERT INTO dai_re_evaluation (id, tenant_id, organization_id, rule_group_id, policy, decision, language,
                                   duration_micros, evaluated_at)
    SELECT e.id, acme, e.org, e.group_id, e.policy,
           -- COMPOSITE: all true -> its true action, otherwise its false action; the other policies: the strictest action
           CASE WHEN e.policy = 'COMPOSITE' THEN
                    CASE WHEN bool_and(r.outcome = 'TRUE') THEN e.composite_true_action ELSE e.composite_false_action END
                ELSE (ARRAY ['ALLOW', 'WARN', 'BLOCK'])[1 + max(CASE r.action WHEN 'ALLOW' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END)]
               END,
           e.lang, e.micros, e.at
    FROM tmp_eval e
             JOIN tmp_result r ON r.evaluation_id = e.id
    GROUP BY e.id, e.org, e.group_id, e.policy, e.composite_true_action, e.composite_false_action, e.lang, e.micros, e.at;

    -- evaluated_at is the partition key of both tables (V13): a result carries its evaluation's instant
    INSERT INTO dai_re_evaluation_result (evaluation_id, evaluated_at, rule_id, sequence, outcome, action, error_code)
    SELECT t.evaluation_id, e.at, t.rule_id, t.sequence, t.outcome, t.action, t.error_code
    FROM tmp_result t JOIN tmp_eval e ON e.id = t.evaluation_id;

    -- who changed what (value-free), over the last days
    INSERT INTO dai_re_audit_log (tenant_id, organization_id, actor_id, actor_name, actor_role, action, entity_type,
                                  entity_id, entity_code, summary, details, occurred_at)
    SELECT acme, NULL, adm, 'Ada Admin', 'ADMIN', a.action, a.type, a.entity, a.code, a.summary,
           jsonb_build_object('seed', true), now() - a.ago
    FROM (VALUES ('RULE_CREATED', 'RULE', 'dddddddd-0000-0000-0000-000000000001'::uuid, 'ADULT', 'Created rule ADULT', interval '6 days'),
                 ('RULE_CREATED', 'RULE', 'dddddddd-0000-0000-0000-000000000002'::uuid, 'KYC_VERIFIED', 'Created rule KYC_VERIFIED', interval '6 days'),
                 ('RULE_CREATED', 'RULE', 'dddddddd-0000-0000-0000-000000000003'::uuid, 'CREDIT_SCORE_MIN', 'Created rule CREDIT_SCORE_MIN', interval '5 days'),
                 ('RULE_GROUP_CREATED', 'RULE_GROUP', 'eeeeeeee-0000-0000-0000-000000000001'::uuid, 'LOAN_ELIGIBILITY', 'Created rule group LOAN_ELIGIBILITY', interval '5 days'),
                 ('RULE_GROUP_UPDATED', 'RULE_GROUP', 'eeeeeeee-0000-0000-0000-000000000001'::uuid, 'LOAN_ELIGIBILITY', 'Updated rule group LOAN_ELIGIBILITY', interval '3 days'),
                 ('RULE_STATUS_CHANGED', 'RULE', 'dddddddd-0000-0000-0000-000000000005'::uuid, 'HIGH_VALUE_REVIEW', 'Rule HIGH_VALUE_REVIEW: DRAFT → ACTIVE', interval '2 days')
         ) AS a(action, type, entity, code, summary, ago);
    INSERT INTO dai_re_audit_log (tenant_id, organization_id, actor_id, actor_name, actor_role, action, entity_type,
                                  entity_id, entity_code, summary, details, occurred_at)
    SELECT acme, retail, usr, 'Uma User', 'USER', 'RULE_GROUP_EVALUATED', 'EVALUATION', NULL, 'LOAN/LOAN_ELIGIBILITY',
           'Evaluated LOAN/LOAN_ELIGIBILITY → ' || d, jsonb_build_object('source', 'TEST_BENCH', 'decision', d),
           now() - (random() * interval '20 hours')
    FROM generate_series(1, 12) n
             CROSS JOIN LATERAL (SELECT (ARRAY ['ALLOW', 'BLOCK'])[1 + (n % 2)] AS d) x;

    -- AI chat conversations of the Acme tenant (the library's conversation tables)
    INSERT INTO dai_principal (id, subject_type, issuer, external_id, display_name)
    VALUES ('99999999-0000-0000-0000-000000000001', 'USER', 'rule-engine-dev', 'dev-chat-user', 'Dev chat user')
    ON CONFLICT DO NOTHING;
    INSERT INTO dai_workspace (id, slug, name, tenant_id)
    VALUES ('99999999-0000-0000-0000-000000000002', 'acme-rules', 'Acme rules assistant', acme::text)
    ON CONFLICT DO NOTHING;
    INSERT INTO dai_conversation (id, workspace_id, principal_id, channel, conversation_key_hash, title, started_at,
                                  last_activity_at, retention_until)
    VALUES ('99999999-0000-0000-0000-000000000003', '99999999-0000-0000-0000-000000000002',
            '99999999-0000-0000-0000-000000000001', 'CHAT', 'dev-chat-1', 'Why was my loan blocked?',
            now() - interval '5 hours', now() - interval '5 hours', now() + interval '30 days'),
           ('99999999-0000-0000-0000-000000000004', '99999999-0000-0000-0000-000000000002',
            '99999999-0000-0000-0000-000000000001', 'CHAT', 'dev-chat-2', 'Draft a rule for high-value loans',
            now() - interval '2 hours', now() - interval '1 hour', now() + interval '30 days')
    ON CONFLICT DO NOTHING;
    INSERT INTO dai_conversation_message (conversation_id, seq, role, content, redacted, created_at)
    VALUES ('99999999-0000-0000-0000-000000000003', 0, 'USER', 'Why was my loan blocked?', false, now() - interval '5 hours'),
           ('99999999-0000-0000-0000-000000000003', 1, 'ASSISTANT',
            'Your credit score was below the required 650, so the rule CREDIT_SCORE_MIN returned false and the group blocked the application.',
            false, now() - interval '5 hours'),
           ('99999999-0000-0000-0000-000000000003', 2, 'USER', 'What score would I need for 500000?', false, now() - interval '5 hours'),
           ('99999999-0000-0000-0000-000000000003', 3, 'ASSISTANT',
            'The limit is the credit score times 1000, so a score of 500 or more allows 500000, as long as the score itself is at least 650.',
            false, now() - interval '5 hours'),
           ('99999999-0000-0000-0000-000000000004', 0, 'USER', 'Draft a rule for high-value loans', false, now() - interval '2 hours'),
           ('99999999-0000-0000-0000-000000000004', 1, 'ASSISTANT',
            'Try loan.amount < 500000.0 with a WARN when false, so a person reviews anything above that amount.',
            false, now() - interval '1 hour')
    ON CONFLICT DO NOTHING;
END
$seed$;
