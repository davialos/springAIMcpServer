-- =====================================================================================================
-- scripts/db/postgresql/03_post_migration_grants.sql
--
-- Catch-up grant for objects that exist in dynamic_ai but predate 02_create_schema_and_privileges.sql's
-- ALTER DEFAULT PRIVILEGES (e.g. Flyway ran once before a DBA ever executed 02_, or a table was created by a
-- manual DBA script instead of a migration). ALTER DEFAULT PRIVILEGES only affects objects created AFTER it
-- runs — it is not retroactive — so this script exists to bring pre-existing objects into line.
--
-- Safe and cheap to run at any time, including routinely after every Flyway run if a DBA prefers belt-and-
-- braces over trusting the default-privilege wiring: GRANT is idempotent (re-granting an already-held
-- privilege is a no-op, never an error), and this script only ever adds privileges dai_app is already
-- supposed to have per 02_ — it never revokes or narrows anything.
--
-- Run as dai_owner, or any role that is a member of dai_owner (dai_migrator qualifies), connected to the
-- database that hosts the store.
--
-- Usage:
--   psql -h <host> -U dai_migrator -d <dai_database-or-host-database> \
--        -v dai_app_role=dai_app -v dai_schema=dynamic_ai \
--        -f 03_post_migration_grants.sql
-- =====================================================================================================

\set ON_ERROR_STOP on

-- ---- defaults (override with -v NAME=value on the psql command line) ----------------------------------
\set dai_app_role dai_app
\set dai_schema dynamic_ai

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA :"dai_schema" TO :"dai_app_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA :"dai_schema" TO :"dai_app_role";
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA :"dai_schema" TO :"dai_app_role";

-- Re-assert PUBLIC has no EXECUTE on any function that already existed before 02_'s default-privilege REVOKE
-- was in place (PostgreSQL grants EXECUTE to PUBLIC by default at CREATE FUNCTION time — see 02_'s comment).
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA :"dai_schema" FROM PUBLIC;

-- Reminder, not enforced here: append-only tables (dai_snapshot, dai_snapshot_entry,
-- dai_change_proposal_event, dai_audit_event, dai_audit_evidence) intentionally still receive the UPDATE/
-- DELETE grant above — they are protected by the dai_forbid_modification() trigger, not by narrower grants
-- (see 02_'s comment for why). Do not remove those five tables from the blanket GRANT above expecting that
-- to add safety; it would not, and it would make this script's idempotent "grant everything dai_app should
-- have" contract table-list-dependent for no benefit.

\echo 'Post-migration grants re-applied for "' :dai_app_role '" on all current objects in schema "' :dai_schema '".'
\echo 'Run 04_verify.sql next to confirm the store is in the expected state.'
