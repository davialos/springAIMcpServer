-- =====================================================================================================
-- scripts/db/postgresql/02_create_schema_and_privileges.sql
--
-- Creates the dynamic_ai schema and wires up default privileges so that every object Flyway creates from
-- this point on automatically grants the runtime role (dai_app) exactly DML — no DDL, no ownership — without
-- a per-migration grant step. Run once per database that will host the store (LLD-15 §3, §12).
--
-- Run as a superuser, or a role with CREATE privilege on the target database, CONNECTED TO THE DATABASE THE
-- STORE WILL USE (the dedicated database created by 01_create_roles_and_database.sql, or the host
-- application's own database for "option B"). Requires 01_create_roles_and_database.sql to have already run
-- against this cluster. Idempotent: safe to re-run.
--
-- Usage:
--   psql -h <host> -U postgres -d <dai_database-or-host-database> \
--        -v dai_owner_role=dai_owner -v dai_migrator_role=dai_migrator -v dai_app_role=dai_app \
--        -v dai_schema=dynamic_ai \
--        -f 02_create_schema_and_privileges.sql
-- =====================================================================================================

\set ON_ERROR_STOP on

-- ---- defaults (override with -v NAME=value on the psql command line) ----------------------------------
\set dai_owner_role dai_owner
\set dai_migrator_role dai_migrator
\set dai_app_role dai_app
\set dai_schema dynamic_ai

-- Schema owned by dai_owner (not by whichever superuser/DBA happens to run this script), so
-- ALTER DEFAULT PRIVILEGES FOR ROLE dai_owner below is stable across who executes migrations.
-- See 01_'s header comment for why this uses the SELECT ... WHERE NOT EXISTS ... \gexec idiom rather than a
-- DO $$ ... $$ block: CREATE SCHEMA *can* run inside a transaction, but parameterizing it with a psql
-- :variable inside a dollar-quoted DO body is a well-known footgun (psql's variable substitution does not
-- reliably treat dollar-quoting as opaque the way it treats plain '...' literals), so we keep every
-- parameterized CREATE in this script family on the simpler, unambiguous \gexec path.
SELECT format('CREATE SCHEMA %I AUTHORIZATION %I', :'dai_schema', :'dai_owner_role')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_namespace WHERE nspname = :'dai_schema') \gexec

-- dai_migrator needs USAGE to reference the schema at all, and CREATE because Flyway issues
-- CREATE TABLE/INDEX/FUNCTION/TRIGGER statements while connected as dai_migrator. 01_ makes every dai_migrator
-- session start with SET ROLE dai_owner, so the objects end up owned by dai_owner (membership alone would leave
-- them owned by dai_migrator and the default privileges below would never apply).
GRANT USAGE, CREATE ON SCHEMA :"dai_schema" TO :"dai_migrator_role";

-- dai_app only ever runs DML: USAGE to see the schema and its objects, nothing else at the schema level.
GRANT USAGE ON SCHEMA :"dai_schema" TO :"dai_app_role";
REVOKE CREATE ON SCHEMA :"dai_schema" FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------
-- Default privileges: every object dai_owner subsequently creates in this schema (i.e. everything Flyway
-- creates while dai_migrator runs as dai_owner via SET ROLE — see 01_) automatically grants dai_app
-- exactly DML on tables/sequences and EXECUTE on functions — no per-migration grant statement needed, and no
-- window where a freshly migrated table is invisible to the running application. This is what makes
-- 03_post_migration_grants.sql a "catch up for pre-existing objects" script, not something every future
-- migration has to remember to extend.
-- ---------------------------------------------------------------------------------------------------
ALTER DEFAULT PRIVILEGES FOR ROLE :"dai_owner_role" IN SCHEMA :"dai_schema"
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"dai_app_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"dai_owner_role" IN SCHEMA :"dai_schema"
    GRANT USAGE, SELECT ON SEQUENCES TO :"dai_app_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"dai_owner_role" IN SCHEMA :"dai_schema"
    GRANT EXECUTE ON FUNCTIONS TO :"dai_app_role";

-- PostgreSQL grants EXECUTE on a newly created function to PUBLIC by default (a long-standing, frequently
-- surprising PostgreSQL default, independent of the ALTER DEFAULT PRIVILEGES grants above). This statement
-- is the documented way to cancel that default for future functions created by dai_owner, so an audit of
-- "what can PUBLIC execute in dynamic_ai" always comes back empty — including for the SECURITY DEFINER
-- partition-maintenance functions (dai_ensure_monthly_partitions, dai_drop_monthly_partitions_before,
-- LLD-15 §7), which must only ever be callable by dai_app, never by an arbitrary authenticated role.
ALTER DEFAULT PRIVILEGES FOR ROLE :"dai_owner_role" IN SCHEMA :"dai_schema"
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------
-- Append-only tables are protected by the dai_forbid_modification() trigger (dai_snapshot,
-- dai_snapshot_entry, dai_change_proposal_event, dai_audit_event, dai_audit_evidence — LLD-15 §7), NOT by
-- narrowing dai_app's grants. dai_app legitimately holds table-level UPDATE/DELETE (it needs both for most
-- other tables in the schema), but the trigger raises insufficient_privilege on any UPDATE/DELETE against
-- those five tables regardless of who the grantee is. Do not "fix" this by hand-carving a narrower grant set
-- for just those five tables: the trigger is the real control, a parallel narrower-grant scheme only adds a
-- second thing that can drift out of sync with the schema (e.g. after a future migration adds a sixth
-- append-only table) while buying no additional protection.
-- ---------------------------------------------------------------------------------------------------

-- ---------------------------------------------------------------------------------------------------
-- Runtime hardening for the application role (release-it: every connection this role opens is bounded).
-- These apply the next time dai_app opens a session (existing connections are unaffected until reconnect).
--   search_path: dai_app never has to schema-qualify dynamic_ai.* in application SQL, and — more importantly
--     — it will never accidentally resolve an unqualified identifier against "public" or another schema it
--     might later be granted USAGE on for an unrelated reason.
--   statement_timeout: bounds any single dai_app statement; the application's own per-call timeouts
--     (dynamic.ai.agent.store.datasource.connection-timeout and the query/tool timeouts in LLD-05/LLD-07)
--     are expected to fire first in normal operation — this is the outer safety net, not the primary control.
--   idle_in_transaction_session_timeout: a connection that opened a transaction and then stalled (a bug, a
--     network partition mid-transaction) releases its locks and returns to the pool instead of holding them
--     indefinitely — directly the "no work on host servlet threads / bound every downstream" rule from
--     docs/lld/12-host-safety-and-environment-containment.md §4, applied at the database layer too.
-- Tune both values for the deployment's real latency profile; these are conservative starting points, not
-- hard requirements.
-- ---------------------------------------------------------------------------------------------------
ALTER ROLE :"dai_app_role" SET search_path = :"dai_schema";
ALTER ROLE :"dai_app_role" SET statement_timeout = '30s';
ALTER ROLE :"dai_app_role" SET idle_in_transaction_session_timeout = '15s';

\echo 'Schema "' :dai_schema '" created/verified with default privileges for "' :dai_app_role '".'
\echo 'Next: run Flyway as "' :dai_migrator_role '" (see README.md for app-run vs DBA-run migrations),'
\echo 'then 03_post_migration_grants.sql as a catch-up grant for any pre-existing objects.'
