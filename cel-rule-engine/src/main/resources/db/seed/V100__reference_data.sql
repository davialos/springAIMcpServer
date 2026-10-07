-- Local reference data: one tenant with lending and payment rules, three languages, and every channel type.
-- Remove classpath:db/seed from spring.flyway.locations for an empty production database.

insert into sys_language (code, name, native_name, rtl) values
    ('en', 'English', 'English', false),
    ('hi', 'Hindi', 'हिन्दी', false),
    ('th', 'Thai', 'ไทย', false),
    ('es', 'Spanish', 'Español', false);

insert into re_tenant (code, name, default_language) values ('ACME', 'Acme Financial', 'en');
insert into re_organization (tenant_id, code, name)
    select id, 'HQ', 'Acme Head Office' from re_tenant where code = 'ACME';
insert into re_organization (tenant_id, parent_id, code, name)
    select t.id, o.id, 'TH', 'Acme Thailand'
    from re_tenant t join re_organization o on o.tenant_id = t.id and o.code = 'HQ' where t.code = 'ACME';

insert into re_module (code, name, description) values
    ('LENDING', 'Lending', 'Loan applications and approvals'),
    ('PAYMENTS', 'Payments', 'Funds transfers and card transactions'),
    ('ACCOUNTS', 'Accounts', 'Account maintenance');
insert into re_tenant_module (tenant_id, module_id)
    select t.id, m.id from re_tenant t cross join re_module m where t.code = 'ACME' and m.code in ('LENDING', 'PAYMENTS');

-- ───────────── parameter library: sys objects and their attributes (customer.age, transaction.amount …) ─────────────

insert into sys_object (code, name, description, module_id) values
    ('customer', 'Customer', 'The applicant or account holder', null),
    ('loan', 'Loan', 'The loan being applied for', (select id from re_module where code = 'LENDING')),
    ('account', 'Account', 'The account a transaction is drawn on', (select id from re_module where code = 'PAYMENTS')),
    ('transaction', 'Transaction', 'The funds transfer being submitted', (select id from re_module where code = 'PAYMENTS'));

insert into sys_object_attribute (sys_object_id, code, name, data_type, required)
select o.id, a.code, a.name, a.data_type, a.required
from sys_object o
join (values
    ('customer', 'name', 'Full name', 'STRING', false),
    ('customer', 'email', 'E-mail address', 'STRING', false),
    ('customer', 'age', 'Age in years', 'INTEGER', true),
    ('customer', 'income', 'Yearly income', 'DECIMAL', false),
    ('customer', 'country', 'Country code (ISO 3166-1 alpha-2)', 'STRING', false),
    ('customer', 'kycStatus', 'KYC status: VERIFIED, PENDING, REJECTED', 'STRING', false),
    ('customer', 'creditScore', 'Credit score', 'INTEGER', false),
    ('customer', 'segment', 'Customer segment', 'STRING', false),
    ('customer', 'tags', 'Free tags', 'STRING_LIST', false),
    ('loan', 'principal', 'Requested amount', 'DECIMAL', true),
    ('loan', 'tenureMonths', 'Tenure in months', 'INTEGER', false),
    ('loan', 'purpose', 'Purpose of the loan', 'STRING', false),
    ('account', 'balance', 'Available balance', 'DECIMAL', true),
    ('account', 'status', 'ACTIVE, FROZEN, CLOSED', 'STRING', false),
    ('account', 'openedOn', 'Opening date', 'DATE', false),
    ('transaction', 'amount', 'Amount', 'DECIMAL', true),
    ('transaction', 'currency', 'Currency code', 'STRING', false),
    ('transaction', 'channel', 'WEB, MOBILE, BRANCH', 'STRING', false),
    ('transaction', 'country', 'Destination country code', 'STRING', false),
    ('transaction', 'international', 'Crosses a border', 'BOOLEAN', false),
    ('transaction', 'at', 'When it was submitted', 'TIMESTAMP', false)
) as a(object_code, code, name, data_type, required) on a.object_code = o.code;

-- ───────────── message bundles (one bundle, one text per language) ─────────────

insert into sys_bundle (code, description) values
    ('LOAN_AGE_OK', 'Applicant age accepted'),
    ('LOAN_AGE_FAIL', 'Applicant age outside the allowed range'),
    ('LOAN_KYC_FAIL', 'KYC not verified'),
    ('LOAN_CREDIT_MIN_FAIL', 'Credit score below the minimum'),
    ('LOAN_CREDIT_GOOD_FAIL', 'Credit score below the preferred level'),
    ('LOAN_AMOUNT_FAIL', 'Amount above 5x the yearly income'),
    ('LOAN_ELIGIBLE', 'Composite: all eligibility rules passed'),
    ('LOAN_NOT_ELIGIBLE', 'Composite: at least one eligibility rule failed'),
    ('TXN_BALANCE_FAIL', 'Insufficient balance'),
    ('TXN_LIMIT_FAIL', 'Daily limit exceeded'),
    ('TXN_LARGE', 'Large transaction'),
    ('TXN_RISK_COUNTRY', 'Destination outside the usual countries'),
    ('TXN_ALLOWED', 'No transaction rule failed'),
    ('TXN_REVIEW', 'Transaction flagged for review');

insert into sys_bundle_text (bundle_id, language_code, text)
select b.id, v.lang, v.txt
from sys_bundle b
join (values
    ('LOAN_AGE_OK', 'en', 'Age {customer.age} is within the allowed range.'),
    ('LOAN_AGE_OK', 'hi', 'आयु {customer.age} अनुमत सीमा के भीतर है।'),
    ('LOAN_AGE_OK', 'th', 'อายุ {customer.age} ปีอยู่ในช่วงที่อนุญาต'),
    ('LOAN_AGE_FAIL', 'en', 'Applicants must be between 18 and 70 years old (age given: {customer.age}).'),
    ('LOAN_AGE_FAIL', 'hi', 'आवेदक की आयु 18 से 70 वर्ष के बीच होनी चाहिए (दी गई आयु: {customer.age})।'),
    ('LOAN_AGE_FAIL', 'th', 'ผู้สมัครต้องมีอายุระหว่าง 18 ถึง 70 ปี (อายุที่ระบุ: {customer.age})'),
    ('LOAN_KYC_FAIL', 'en', 'KYC verification is not complete.'),
    ('LOAN_KYC_FAIL', 'hi', 'केवाईसी सत्यापन पूरा नहीं हुआ है।'),
    ('LOAN_KYC_FAIL', 'th', 'การยืนยันตัวตน (KYC) ยังไม่สมบูรณ์'),
    ('LOAN_CREDIT_MIN_FAIL', 'en', 'Credit score {customer.creditScore} is below the minimum of 550.'),
    ('LOAN_CREDIT_MIN_FAIL', 'hi', 'क्रेडिट स्कोर {customer.creditScore} न्यूनतम 550 से कम है।'),
    ('LOAN_CREDIT_MIN_FAIL', 'th', 'คะแนนเครดิต {customer.creditScore} ต่ำกว่าขั้นต่ำ 550'),
    ('LOAN_CREDIT_GOOD_FAIL', 'en', 'Credit score is below the preferred 700; a guarantor is recommended.'),
    ('LOAN_CREDIT_GOOD_FAIL', 'hi', 'क्रेडिट स्कोर पसंदीदा 700 से कम है; गारंटर की सलाह दी जाती है।'),
    ('LOAN_CREDIT_GOOD_FAIL', 'th', 'คะแนนเครดิตต่ำกว่า 700 ที่แนะนำ ควรมีผู้ค้ำประกัน'),
    ('LOAN_AMOUNT_FAIL', 'en', 'The requested amount {loan.principal} is more than 5 times the yearly income.'),
    ('LOAN_AMOUNT_FAIL', 'hi', 'अनुरोधित राशि {loan.principal} वार्षिक आय के 5 गुना से अधिक है।'),
    ('LOAN_AMOUNT_FAIL', 'th', 'จำนวนเงินที่ขอ {loan.principal} มากกว่า 5 เท่าของรายได้ต่อปี'),
    ('LOAN_ELIGIBLE', 'en', 'The applicant is eligible for this loan.'),
    ('LOAN_ELIGIBLE', 'hi', 'आवेदक इस ऋण के लिए पात्र है।'),
    ('LOAN_ELIGIBLE', 'th', 'ผู้สมัครมีสิทธิ์ได้รับสินเชื่อนี้'),
    ('LOAN_NOT_ELIGIBLE', 'en', 'The applicant is not eligible for this loan.'),
    ('LOAN_NOT_ELIGIBLE', 'hi', 'आवेदक इस ऋण के लिए पात्र नहीं है।'),
    ('LOAN_NOT_ELIGIBLE', 'th', 'ผู้สมัครไม่มีสิทธิ์ได้รับสินเชื่อนี้'),
    ('TXN_BALANCE_FAIL', 'en', 'Insufficient balance: {transaction.amount} requested, {account.balance} available.'),
    ('TXN_BALANCE_FAIL', 'hi', 'अपर्याप्त शेष: {transaction.amount} अनुरोधित, {account.balance} उपलब्ध।'),
    ('TXN_BALANCE_FAIL', 'th', 'ยอดเงินไม่เพียงพอ: ขอ {transaction.amount} คงเหลือ {account.balance}'),
    ('TXN_LIMIT_FAIL', 'en', 'The transaction exceeds the limit of 100000.'),
    ('TXN_LIMIT_FAIL', 'hi', 'लेनदेन 100000 की सीमा से अधिक है।'),
    ('TXN_LIMIT_FAIL', 'th', 'ธุรกรรมเกินวงเงิน 100000'),
    ('TXN_LARGE', 'en', 'Large transaction ({transaction.amount}); it may be reviewed.'),
    ('TXN_LARGE', 'hi', 'बड़ा लेनदेन ({transaction.amount}); इसकी समीक्षा की जा सकती है।'),
    ('TXN_LARGE', 'th', 'ธุรกรรมจำนวนมาก ({transaction.amount}) อาจมีการตรวจสอบ'),
    ('TXN_RISK_COUNTRY', 'en', 'Transfers to {transaction.country} need additional screening.'),
    ('TXN_RISK_COUNTRY', 'hi', '{transaction.country} को स्थानांतरण के लिए अतिरिक्त जांच आवश्यक है।'),
    ('TXN_RISK_COUNTRY', 'th', 'การโอนไปยัง {transaction.country} ต้องมีการตรวจสอบเพิ่มเติม'),
    ('TXN_ALLOWED', 'en', 'No limit or balance problem was found.'),
    ('TXN_ALLOWED', 'hi', 'कोई सीमा या शेष राशि की समस्या नहीं मिली।'),
    ('TXN_ALLOWED', 'th', 'ไม่พบปัญหาเรื่องวงเงินหรือยอดคงเหลือ'),
    ('TXN_REVIEW', 'en', 'The transaction was flagged for review.'),
    ('TXN_REVIEW', 'hi', 'लेनदेन को समीक्षा के लिए चिह्नित किया गया है।'),
    ('TXN_REVIEW', 'th', 'ธุรกรรมถูกทำเครื่องหมายเพื่อตรวจสอบ')
) as v(code, lang, txt) on v.code = b.code;

-- ───────────── rules ─────────────

insert into re_rule (tenant_id, module_id, code, name, expression, true_bundle_id, false_bundle_id, true_action, false_action)
select t.id, m.id, r.code, r.name, r.expression,
       (select id from sys_bundle where code = r.true_bundle), (select id from sys_bundle where code = r.false_bundle),
       r.true_action, r.false_action
from re_tenant t
join (values
    ('LENDING', 'LOAN_AGE', 'Applicant age', 'customer.age >= 18 && customer.age <= 70', 'LOAN_AGE_OK', 'LOAN_AGE_FAIL', 'ALLOW', 'BLOCK'),
    ('LENDING', 'LOAN_KYC', 'KYC verified', 'customer.kycStatus == ''VERIFIED''', null, 'LOAN_KYC_FAIL', 'ALLOW', 'BLOCK'),
    ('LENDING', 'LOAN_CREDIT_MIN', 'Minimum credit score', 'customer.creditScore >= 550', null, 'LOAN_CREDIT_MIN_FAIL', 'ALLOW', 'BLOCK'),
    ('LENDING', 'LOAN_CREDIT_GOOD', 'Preferred credit score', 'customer.creditScore >= 700', null, 'LOAN_CREDIT_GOOD_FAIL', 'ALLOW', 'WARN'),
    ('LENDING', 'LOAN_AMOUNT', 'Amount against income', 'loan.principal <= customer.income * 5.0', null, 'LOAN_AMOUNT_FAIL', 'ALLOW', 'BLOCK'),
    ('PAYMENTS', 'TXN_BALANCE', 'Enough balance', 'transaction.amount <= account.balance', null, 'TXN_BALANCE_FAIL', 'ALLOW', 'BLOCK'),
    ('PAYMENTS', 'TXN_LIMIT', 'Daily limit', 'transaction.amount <= 100000.0', null, 'TXN_LIMIT_FAIL', 'ALLOW', 'BLOCK'),
    ('PAYMENTS', 'TXN_LARGE', 'Large transaction', 'transaction.amount >= 50000.0', 'TXN_LARGE', null, 'WARN', 'ALLOW'),
    ('PAYMENTS', 'TXN_RISK_COUNTRY', 'Risky destination', 'transaction.international && !(transaction.country in [''IN'', ''TH'', ''US'', ''GB''])', 'TXN_RISK_COUNTRY', null, 'WARN', 'ALLOW')
) as r(module_code, code, name, expression, true_bundle, false_bundle, true_action, false_action) on true
join re_module m on m.code = r.module_code
where t.code = 'ACME';

-- ───────────── rule groups (one per evaluation policy) ─────────────

insert into re_rule_group (tenant_id, module_id, code, name, evaluation_policy, match_on, true_bundle_id, false_bundle_id, true_action, false_action)
select t.id, m.id, g.code, g.name, g.policy, g.match_on,
       (select id from sys_bundle where code = g.true_bundle), (select id from sys_bundle where code = g.false_bundle),
       g.true_action, g.false_action
from re_tenant t
join (values
    ('LENDING', 'LOAN_ELIGIBILITY', 'Loan eligibility (all must pass)', 'COMPOSITE', 'TRUE', 'LOAN_ELIGIBLE', 'LOAN_NOT_ELIGIBLE', 'ALLOW', 'BLOCK'),
    ('LENDING', 'LOAN_ADVISORY', 'Loan advisory (every rule reported)', 'EVALUATE_ALL', 'TRUE', null, null, 'ALLOW', 'WARN'),
    ('PAYMENTS', 'TXN_CHECKS', 'Transfer checks (first failure only)', 'FIRST_MATCH', 'FALSE', 'TXN_ALLOWED', null, 'ALLOW', 'BLOCK'),
    ('PAYMENTS', 'TXN_RISK_FLAGS', 'Transfer risk flags (every flag raised)', 'ALL_MATCH', 'TRUE', 'TXN_REVIEW', null, 'WARN', 'ALLOW')
) as g(module_code, code, name, policy, match_on, true_bundle, false_bundle, true_action, false_action) on true
join re_module m on m.code = g.module_code
where t.code = 'ACME';

insert into re_rule_group_member (group_id, rule_id, sequence)
select g.id, r.id, m.sequence
from (values
    ('LOAN_ELIGIBILITY', 'LOAN_AGE', 1), ('LOAN_ELIGIBILITY', 'LOAN_KYC', 2),
    ('LOAN_ELIGIBILITY', 'LOAN_CREDIT_MIN', 3), ('LOAN_ELIGIBILITY', 'LOAN_AMOUNT', 4),
    ('LOAN_ADVISORY', 'LOAN_AGE', 1), ('LOAN_ADVISORY', 'LOAN_CREDIT_GOOD', 2),
    ('TXN_CHECKS', 'TXN_BALANCE', 1), ('TXN_CHECKS', 'TXN_LIMIT', 2),
    ('TXN_RISK_FLAGS', 'TXN_LARGE', 1), ('TXN_RISK_FLAGS', 'TXN_RISK_COUNTRY', 2)
) as m(group_code, rule_code, sequence)
join re_rule_group g on g.code = m.group_code
join re_rule r on r.code = m.rule_code and r.tenant_id = g.tenant_id;

-- ───────────── trigger points: a form, an action, optionally one field ─────────────

insert into re_trigger_point (tenant_id, module_id, code, name, form_code, action_type, field_code)
select t.id, m.id, p.code, p.name, p.form_code, p.action_type, p.field_code
from re_tenant t
join (values
    ('LENDING', 'LOAN_SUBMIT', 'Loan application: submit', 'LOAN_APPLICATION', 'SUBMIT', null),
    ('LENDING', 'LOAN_APPROVE', 'Loan application: approve', 'LOAN_APPLICATION', 'APPROVE', null),
    ('PAYMENTS', 'TRANSFER_SUBMIT', 'Funds transfer: submit', 'FUNDS_TRANSFER', 'SUBMIT', null),
    ('PAYMENTS', 'TRANSFER_AMOUNT_CHANGE', 'Funds transfer: amount field changed', 'FUNDS_TRANSFER', 'CHANGE', 'amount')
) as p(module_code, code, name, form_code, action_type, field_code) on true
join re_module m on m.code = p.module_code
where t.code = 'ACME';

insert into re_trigger_binding (trigger_point_id, rule_group_id, sequence)
select tp.id, g.id, b.sequence
from (values
    ('LOAN_SUBMIT', 'LOAN_ELIGIBILITY', 1), ('LOAN_SUBMIT', 'LOAN_ADVISORY', 2),
    ('LOAN_APPROVE', 'LOAN_ELIGIBILITY', 1),
    ('TRANSFER_SUBMIT', 'TXN_CHECKS', 1), ('TRANSFER_SUBMIT', 'TXN_RISK_FLAGS', 2),
    ('TRANSFER_AMOUNT_CHANGE', 'TXN_CHECKS', 1)
) as b(trigger_code, group_code, sequence)
join re_trigger_point tp on tp.code = b.trigger_code
join re_rule_group g on g.code = b.group_code and g.tenant_id = tp.tenant_id;

-- ───────────── channels: e-mail template, API endpoint, push ─────────────

insert into re_email_template (tenant_id, external_template_id, name, description)
select id, 'CREDIT_REVIEW_V1', 'Credit review notification', 'Sent to the credit team when an application is not eligible'
from re_tenant where code = 'ACME';
insert into re_email_template (tenant_id, external_template_id, name, description)
select id, 'TXN_ALERT_V2', 'Transaction alert', 'Sent to the customer when a large transaction is made'
from re_tenant where code = 'ACME';

-- the local environment: this deployment's own webhook is a same-environment API
insert into re_api_endpoint (tenant_id, name, url, http_method, headers, environment_class)
select id, 'Local rule-events webhook', 'http://localhost:8080/hooks/rule-events', 'POST',
       '{"X-Source": "cel-rule-engine"}', 'SAME_ENVIRONMENT'
from re_tenant where code = 'ACME';

insert into re_channel (tenant_id, name, channel_type, email_template_id, api_endpoint_id, config)
select t.id, 'Credit team e-mail', 'EMAIL', (select id from re_email_template where external_template_id = 'CREDIT_REVIEW_V1'), null,
       '{"recipients": ["credit-team@acme.test"]}'
from re_tenant t where t.code = 'ACME';
insert into re_channel (tenant_id, name, channel_type, email_template_id, api_endpoint_id, config)
select t.id, 'Customer transaction alert', 'EMAIL', (select id from re_email_template where external_template_id = 'TXN_ALERT_V2'), null,
       '{"recipientPath": "customer.email"}'
from re_tenant t where t.code = 'ACME';
insert into re_channel (tenant_id, name, channel_type, email_template_id, api_endpoint_id, config)
select t.id, 'Applicant push', 'PUSH', null, null, '{"title": "Loan application", "topicPath": "customer.email"}'
from re_tenant t where t.code = 'ACME';
insert into re_channel (tenant_id, name, channel_type, email_template_id, api_endpoint_id, config)
select t.id, 'Risk webhook', 'API', null, (select id from re_api_endpoint where name = 'Local rule-events webhook'), '{}'
from re_tenant t where t.code = 'ACME';

-- "when this evaluates to TRUE / FALSE, use this channel"
insert into re_action_binding (tenant_id, rule_group_id, on_outcome, channel_id)
select g.tenant_id, g.id, b.outcome, c.id
from (values
    ('LOAN_ELIGIBILITY', 'FALSE', 'Credit team e-mail'),
    ('LOAN_ELIGIBILITY', 'FALSE', 'Applicant push'),
    ('TXN_RISK_FLAGS', 'TRUE', 'Risk webhook')
) as b(group_code, outcome, channel_name)
join re_rule_group g on g.code = b.group_code
join re_channel c on c.name = b.channel_name and c.tenant_id = g.tenant_id;

insert into re_action_binding (tenant_id, rule_id, on_outcome, channel_id)
select r.tenant_id, r.id, 'TRUE', c.id
from re_rule r join re_channel c on c.name = 'Customer transaction alert' and c.tenant_id = r.tenant_id
where r.code = 'TXN_LARGE';
