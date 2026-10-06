-- What Grafana's read-only role may read: decisions, per-rule outcomes, the authoring audit trail, rule/group/module names (to name
-- things in panels), tenant names and the login attempt log. NOT users, password hashes, organizations' members or chat messages.
-- Idempotent; run as the database owner after the services have created the tables.
GRANT USAGE ON SCHEMA dynamic_ai, re_auth TO grafana_ro;
GRANT SELECT ON dynamic_ai.dai_re_evaluation, dynamic_ai.dai_re_evaluation_result, dynamic_ai.dai_re_audit_log,
                dynamic_ai.dai_re_rule_group, dynamic_ai.dai_re_rule, dynamic_ai.dai_re_module TO grafana_ro;
GRANT SELECT ON re_auth.re_auth_tenant, re_auth.re_auth_login_event TO grafana_ro;
