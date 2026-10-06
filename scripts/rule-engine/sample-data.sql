-- =====================================================================================================
-- Rule-engine sample data for LOCAL development and tests (never a Flyway migration: production stores start empty).
-- Apply with search_path = dynamic_ai after V11. Idempotent only on an empty rule-engine schema.
-- Fixed ids keep the sample readable and let tests refer to them.
--   tenant       11111111-1111-1111-1111-111111111111      organization 22222222-2222-2222-2222-222222222222
-- =====================================================================================================

-- module ------------------------------------------------------------------------------------------
INSERT INTO dai_re_module (id, code, name, description)
VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'LOAN', 'Loan origination', 'Eligibility and credit rules'),
       ('aaaaaaaa-0000-0000-0000-000000000002', 'ORDERS', 'Order management', 'Order and purchase rules');

-- parameter library: object.attribute = CEL variable -------------------------------------------------
INSERT INTO dai_re_sys_object (id, code, name, module_id)
VALUES ('bbbbbbbb-0000-0000-0000-000000000001', 'customer', 'Customer', NULL),
       ('bbbbbbbb-0000-0000-0000-000000000002', 'loan', 'Loan application', 'aaaaaaaa-0000-0000-0000-000000000001'),
       ('bbbbbbbb-0000-0000-0000-000000000003', 'order', 'Order', 'aaaaaaaa-0000-0000-0000-000000000002');

INSERT INTO dai_re_sys_object_attribute (object_id, code, name, data_type, required, sample_value)
VALUES ('bbbbbbbb-0000-0000-0000-000000000001', 'age', 'Age in years', 'INT', true, '34'),
       ('bbbbbbbb-0000-0000-0000-000000000001', 'country', 'Country (ISO 3166)', 'STRING', false, 'IN'),
       ('bbbbbbbb-0000-0000-0000-000000000001', 'kycStatus', 'KYC status', 'STRING', true, 'VERIFIED'),
       ('bbbbbbbb-0000-0000-0000-000000000001', 'creditScore', 'Credit score', 'INT', true, '720'),
       ('bbbbbbbb-0000-0000-0000-000000000001', 'email', 'E-mail address', 'STRING', false, 'a@example.com'),
       ('bbbbbbbb-0000-0000-0000-000000000002', 'amount', 'Requested amount', 'DOUBLE', true, '250000.0'),
       ('bbbbbbbb-0000-0000-0000-000000000002', 'tenureMonths', 'Tenure in months', 'INT', true, '36'),
       ('bbbbbbbb-0000-0000-0000-000000000003', 'total', 'Order total', 'DOUBLE', true, '99.5'),
       ('bbbbbbbb-0000-0000-0000-000000000003', 'itemCount', 'Number of items', 'INT', true, '3');

-- multilingual messages (bundle = identity, one text per language) -------------------------------------
CREATE FUNCTION pg_temp.bundle(p_id uuid, p_code text, p_en text, p_hi text, p_th text) RETURNS void
    LANGUAGE sql AS
$$
INSERT INTO dai_re_sys_bundle (id, tenant_id, code) VALUES (p_id, NULL, p_code);
INSERT INTO dai_re_sys_bundle_message (bundle_id, language, message_text)
VALUES (p_id, 'en', p_en), (p_id, 'hi', p_hi), (p_id, 'th', p_th);
$$;

SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000001', 'loan.age.ok', 'Customer meets the minimum age.',
                      'ग्राहक न्यूनतम आयु आवश्यकता पूरी करता है।', 'ลูกค้ามีอายุตามเกณฑ์ขั้นต่ำ');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000002', 'loan.age.fail', 'Customer must be at least 18 years old.',
                      'ग्राहक की आयु कम से कम 18 वर्ष होनी चाहिए।', 'ลูกค้าต้องมีอายุอย่างน้อย 18 ปี');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000003', 'loan.kyc.ok', 'KYC is verified.',
                      'केवाईसी सत्यापित है।', 'ยืนยัน KYC แล้ว');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000004', 'loan.kyc.fail', 'KYC verification is pending or failed.',
                      'केवाईसी सत्यापन लंबित है या विफल रहा।', 'การยืนยัน KYC ยังไม่เสร็จหรือไม่ผ่าน');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000005', 'loan.credit.ok', 'Credit score is acceptable.',
                      'क्रेडिट स्कोर स्वीकार्य है।', 'คะแนนเครดิตอยู่ในเกณฑ์');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000006', 'loan.credit.fail', 'Credit score is below the required 650.',
                      'क्रेडिट स्कोर आवश्यक 650 से कम है।', 'คะแนนเครดิตต่ำกว่าเกณฑ์ 650');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000007', 'loan.amount.ok', 'Loan amount is within the allowed limit.',
                      'ऋण राशि अनुमत सीमा के भीतर है।', 'จำนวนเงินกู้อยู่ในวงเงินที่อนุญาต');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000008', 'loan.amount.fail',
                      'Loan amount exceeds the limit allowed for this credit score.',
                      'ऋण राशि इस क्रेडिट स्कोर के लिए अनुमत सीमा से अधिक है।', 'จำนวนเงินกู้เกินวงเงินที่อนุญาตตามคะแนนเครดิต');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-000000000009', 'loan.highvalue.warn',
                      'High-value loan: manual review is recommended.',
                      'उच्च मूल्य का ऋण: मैन्युअल समीक्षा अनुशंसित है।', 'สินเชื่อมูลค่าสูง: แนะนำให้ตรวจสอบด้วยตนเอง');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-00000000000a', 'loan.eligibility.ok', 'All eligibility checks passed.',
                      'सभी पात्रता जाँच सफल रहीं।', 'ผ่านการตรวจสอบคุณสมบัติทั้งหมด');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-00000000000b', 'loan.eligibility.fail', 'Loan eligibility checks failed.',
                      'ऋण पात्रता जाँच विफल रही।', 'การตรวจสอบคุณสมบัติสินเชื่อไม่ผ่าน');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-00000000000c', 'push.loan.blocked.title', 'Loan application blocked',
                      'ऋण आवेदन रोका गया', 'คำขอสินเชื่อถูกระงับ');
SELECT pg_temp.bundle('cccccccc-0000-0000-0000-00000000000d', 'push.loan.blocked.body',
                      'Your loan application did not pass the eligibility checks.',
                      'आपका ऋण आवेदन पात्रता जाँच में सफल नहीं हुआ।', 'คำขอสินเชื่อของคุณไม่ผ่านการตรวจสอบคุณสมบัติ');

-- rules (tenant-wide; module LOAN) ----------------------------------------------------------------
INSERT INTO dai_re_rule (id, tenant_id, organization_id, module_id, code, name, cel_expression, status,
                         true_message_bundle_id, false_message_bundle_id, true_action, false_action)
VALUES ('dddddddd-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'ADULT', 'Customer is an adult', 'customer.age >= 18', 'ACTIVE',
        'cccccccc-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000002', 'ALLOW', 'BLOCK'),
       ('dddddddd-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'KYC_VERIFIED', 'KYC is verified', 'customer.kycStatus == "VERIFIED"', 'ACTIVE',
        'cccccccc-0000-0000-0000-000000000003', 'cccccccc-0000-0000-0000-000000000004', 'ALLOW', 'BLOCK'),
       ('dddddddd-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'CREDIT_SCORE_MIN', 'Credit score at least 650', 'customer.creditScore >= 650', 'ACTIVE',
        'cccccccc-0000-0000-0000-000000000005', 'cccccccc-0000-0000-0000-000000000006', 'ALLOW', 'BLOCK'),
       ('dddddddd-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'AMOUNT_WITHIN_LIMIT', 'Amount within score-based limit',
        'loan.amount <= double(customer.creditScore) * 1000.0', 'ACTIVE',
        'cccccccc-0000-0000-0000-000000000007', 'cccccccc-0000-0000-0000-000000000008', 'ALLOW', 'BLOCK'),
       ('dddddddd-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'HIGH_VALUE_REVIEW', 'Loan under 500000 needs no review', 'loan.amount < 500000.0', 'ACTIVE',
        NULL, 'cccccccc-0000-0000-0000-000000000009', 'ALLOW', 'WARN');

-- rule groups: one per evaluation policy -----------------------------------------------------------
INSERT INTO dai_re_rule_group (id, tenant_id, organization_id, module_id, code, name, status, evaluation_policy, match_on,
                               composite_true_bundle_id, composite_false_bundle_id, composite_true_action, composite_false_action)
VALUES ('eeeeeeee-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'LOAN_ELIGIBILITY', 'Loan eligibility (composite)', 'ACTIVE', 'COMPOSITE', 'TRUE',
        'cccccccc-0000-0000-0000-00000000000a', 'cccccccc-0000-0000-0000-00000000000b', 'ALLOW', 'BLOCK'),
       ('eeeeeeee-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'LOAN_FIRST_FAILURE', 'Loan: first failing rule', 'ACTIVE', 'FIRST_MATCH', 'FALSE',
        NULL, NULL, 'ALLOW', 'BLOCK'),
       ('eeeeeeee-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'LOAN_ALL_FAILURES', 'Loan: every failing rule', 'ACTIVE', 'ALL_MATCH', 'FALSE',
        NULL, NULL, 'ALLOW', 'BLOCK'),
       ('eeeeeeee-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', NULL,
        'aaaaaaaa-0000-0000-0000-000000000001', 'LOAN_FULL_REPORT', 'Loan: full report', 'ACTIVE', 'EVALUATE_ALL', 'TRUE',
        NULL, NULL, 'ALLOW', 'BLOCK');

INSERT INTO dai_re_rule_group_rule (group_id, rule_id, sequence)
SELECT g.id, r.id, r.seq
FROM (VALUES ('dddddddd-0000-0000-0000-000000000001'::uuid, 10), ('dddddddd-0000-0000-0000-000000000002'::uuid, 20),
             ('dddddddd-0000-0000-0000-000000000003'::uuid, 30), ('dddddddd-0000-0000-0000-000000000004'::uuid, 40),
             ('dddddddd-0000-0000-0000-000000000005'::uuid, 50)) AS r(id, seq)
         CROSS JOIN dai_re_rule_group g;

-- communication ---------------------------------------------------------------------------------------
INSERT INTO dai_re_email_template (id, tenant_id, template_ref, name)
VALUES ('f0f0f0f0-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'TPL-1001', 'Loan application declined');

INSERT INTO dai_re_api_endpoint (id, tenant_id, name, url, environment)
VALUES ('f1f1f1f1-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'loan-decision-webhook-dev',
        'http://localhost:8081/hooks/loan-decision', 'DEV'),
       ('f1f1f1f1-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'loan-decision-webhook-qa',
        'https://qa.example.test/hooks/loan-decision', 'QA'),
       ('f1f1f1f1-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'loan-decision-webhook-prod',
        'https://api.example.test/hooks/loan-decision', 'PROD');

INSERT INTO dai_re_outcome_channel (tenant_id, owner_type, owner_id, on_result, channel_type, sequence, email_template_id,
                                    recipient_expression)
VALUES ('11111111-1111-1111-1111-111111111111', 'GROUP', 'eeeeeeee-0000-0000-0000-000000000001', 'FALSE', 'EMAIL', 10,
        'f0f0f0f0-0000-0000-0000-000000000001', 'customer.email');
INSERT INTO dai_re_outcome_channel (tenant_id, owner_type, owner_id, on_result, channel_type, sequence,
                                    push_title_bundle_id, push_body_bundle_id, recipient_expression)
VALUES ('11111111-1111-1111-1111-111111111111', 'GROUP', 'eeeeeeee-0000-0000-0000-000000000001', 'FALSE', 'PUSH', 20,
        'cccccccc-0000-0000-0000-00000000000c', 'cccccccc-0000-0000-0000-00000000000d', 'customer.email');
INSERT INTO dai_re_outcome_channel (tenant_id, owner_type, owner_id, on_result, channel_type, sequence, api_endpoint_id)
VALUES ('11111111-1111-1111-1111-111111111111', 'GROUP', 'eeeeeeee-0000-0000-0000-000000000001', 'ANY', 'API', 30,
        'f1f1f1f1-0000-0000-0000-000000000001');

-- trigger points: a "loan application" form --------------------------------------------------------------
INSERT INTO dai_re_trigger_point (tenant_id, application, module_id, trigger_type, form_code, action_code, field_code, rule_group_id)
VALUES ('11111111-1111-1111-1111-111111111111', 'loan-portal', 'aaaaaaaa-0000-0000-0000-000000000001',
        'FORM_ACTION', 'LOAN_APPLICATION', 'SUBMIT', NULL, 'eeeeeeee-0000-0000-0000-000000000001'),
       ('11111111-1111-1111-1111-111111111111', 'loan-portal', 'aaaaaaaa-0000-0000-0000-000000000001',
        'FORM_ACTION', 'LOAN_APPLICATION', 'APPROVE', NULL, 'eeeeeeee-0000-0000-0000-000000000004'),
       ('11111111-1111-1111-1111-111111111111', 'loan-portal', 'aaaaaaaa-0000-0000-0000-000000000001',
        'FORM_FIELD', 'LOAN_APPLICATION', 'ON_CHANGE', 'amount', 'eeeeeeee-0000-0000-0000-000000000002');

-- revisions: every ACTIVE sample rule/group starts as PUBLISHED revision 1 (what the admin API would have created) ---
INSERT INTO dai_re_revision (tenant_id, kind, subject_id, revision_no, state, content, created_by, published_by, published_at)
SELECT r.tenant_id, 'RULE', r.id, 1, 'PUBLISHED',
       jsonb_build_object('name', r.name, 'description', r.description, 'expression', r.cel_expression,
                          'trueMessageBundleId', r.true_message_bundle_id, 'falseMessageBundleId', r.false_message_bundle_id,
                          'trueAction', r.true_action, 'falseAction', r.false_action),
       'sample-data', 'sample-data', now()
FROM dai_re_rule r WHERE r.status = 'ACTIVE' AND r.published_revision_id IS NULL;
UPDATE dai_re_rule r SET published_revision_id = v.id
FROM dai_re_revision v WHERE v.kind = 'RULE' AND v.subject_id = r.id AND v.state = 'PUBLISHED';

INSERT INTO dai_re_revision (tenant_id, kind, subject_id, revision_no, state, content, created_by, published_by, published_at)
SELECT g.tenant_id, 'GROUP', g.id, 1, 'PUBLISHED',
       jsonb_build_object('name', g.name, 'description', g.description, 'policy', g.evaluation_policy,
                          'matchOn', g.match_on, 'compositeTrueBundleId', g.composite_true_bundle_id,
                          'compositeFalseBundleId', g.composite_false_bundle_id,
                          'compositeTrueAction', g.composite_true_action, 'compositeFalseAction', g.composite_false_action,
                          'onError', g.on_error,
                          'members', COALESCE((SELECT jsonb_agg(jsonb_build_object('ruleId', m.rule_id, 'sequence', m.sequence,
                                                                                    'enabled', m.enabled) ORDER BY m.sequence)
                                               FROM dai_re_rule_group_rule m WHERE m.group_id = g.id), '[]'::jsonb)),
       'sample-data', 'sample-data', now()
FROM dai_re_rule_group g WHERE g.status = 'ACTIVE' AND g.published_revision_id IS NULL;
UPDATE dai_re_rule_group g SET published_revision_id = v.id
FROM dai_re_revision v WHERE v.kind = 'GROUP' AND v.subject_id = g.id AND v.state = 'PUBLISHED';
