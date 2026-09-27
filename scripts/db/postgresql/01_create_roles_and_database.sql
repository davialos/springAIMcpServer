-- =====================================================================================================
-- scripts/db/postgresql/01_create_roles_and_database.sql
--
-- Creates the three PostgreSQL roles the springAIMcpServerCommon dynamic_ai store needs, and optionally a
-- dedicated database for it (ADR-0019, OQ-30; see docs/lld/15-database-schema.md and
-- docs/integration/host-integration-guide.md for "option A: dedicated database" vs "option B: host database,
-- own schema"). Run this once per PostgreSQL cluster (roles are cluster-wide); re-run safely any time.
--
-- Run as a PostgreSQL superuser, or a role with CREATEROLE + CREATEDB, connected to any database in the
-- target cluster (the "postgres" maintenance database is the usual choice) for a fresh cluster. Idempotent:
-- every CREATE below is guarded by a WHERE NOT EXISTS check, so re-running this script against a cluster
-- that already has some or all of these objects changes nothing for what already exists (passwords ARE
-- re-applied on every run via ALTER ROLE, so this script also doubles as the credential-rotation script).
--
-- Usage (defaults shown; override anything with -v NAME=value):
--   psql -h <host> -U postgres -d postgres \
--        -v dai_owner_role=dai_owner \
--        -v dai_migrator_role=dai_migrator -v dai_migrator_password='CHANGE_ME_MIGRATOR' \
--        -v dai_app_role=dai_app         -v dai_app_password='CHANGE_ME_APP' \
--        -v use_dedicated_database=true \
--        -v dai_database=dai_store \
--        -f 01_create_roles_and_database.sql
--
-- NEVER commit real passwords into this file or into shell history; pass them via -v from a secrets
-- manager / CI secret injection, or edit the \set defaults on a throwaway copy that is not committed.
--
-- Idempotency technique: CREATE ROLE and CREATE DATABASE have no native "IF NOT EXISTS" in PostgreSQL, and
-- CREATE DATABASE additionally cannot run inside a transaction block (so it cannot be wrapped in a
-- DO $$ ... $$ block, which always runs inside one). We use the standard psql idiom instead: a SELECT that
-- builds the DDL text only `WHERE NOT EXISTS (...)`, piped into `\gexec`, which executes whatever rows come
-- back (zero rows ⇒ nothing runs). Where a check is a simple loop with no dynamic identifiers to
-- parameterize (04_verify.sql), we use a plain `DO $$ ... IF NOT EXISTS ... $$` block instead — see that
-- file's comments for why the two techniques are split this way.
-- =====================================================================================================

\set ON_ERROR_STOP on

-- ---- defaults (override with -v NAME=value on the psql command line) ----------------------------------
\set dai_owner_role dai_owner
\set dai_migrator_role dai_migrator
\set dai_migrator_password 'CHANGE_ME_MIGRATOR'
\set dai_app_role dai_app
\set dai_app_password 'CHANGE_ME_APP'
\set use_dedicated_database true
\set dai_database dai_store

-- ---------------------------------------------------------------------------------------------------
-- Roles (LLD-15 §12, docs/integration/host-integration-guide.md):
--   dai_owner    NOLOGIN — owns the schema and every object in it. Nobody ever connects as this role; it
--                exists purely so ownership (and therefore ALTER DEFAULT PRIVILEGES in 02_) is stable and
--                independent of which human or CI identity happens to run a migration.
--   dai_migrator LOGIN, member of dai_owner — the identity Flyway connects as, whether Flyway runs
--                automatically inside the application (dynamic.ai.agent.store.migrate=true) or is invoked
--                separately by a DBA with the Flyway CLI (README.md "DBA-run migrations"). It inherits
--                dai_owner's rights through role membership, so DDL it issues is owned by dai_owner, not by
--                dai_migrator itself — dai_migrator is a login identity, not a permanent owner.
--   dai_app      LOGIN, runtime — the identity the running application uses for all data-plane traffic.
--                DML only (granted in 02_create_schema_and_privileges.sql); never a member of dai_owner,
--                never granted DDL.
-- ---------------------------------------------------------------------------------------------------
SELECT format('CREATE ROLE %I NOLOGIN', :'dai_owner_role')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'dai_owner_role') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'dai_migrator_role', :'dai_migrator_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'dai_migrator_role') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'dai_app_role', :'dai_app_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'dai_app_role') \gexec

-- dai_migrator acts with dai_owner's rights via role membership (idempotent: GRANT of an already-held
-- membership is a harmless no-op, but we still guard it so the \echo below is accurate on a re-run).
SELECT format('GRANT %I TO %I', :'dai_owner_role', :'dai_migrator_role')
WHERE NOT EXISTS (
    SELECT FROM pg_catalog.pg_auth_members m
    JOIN pg_catalog.pg_roles r ON r.oid = m.roleid
    JOIN pg_catalog.pg_roles g ON g.oid = m.member
    WHERE r.rolname = :'dai_owner_role' AND g.rolname = :'dai_migrator_role'
) \gexec

-- Passwords are re-applied on every run (this makes the script double as a rotation tool). This is the one
-- statement in this file that is not conditional — ALTER ROLE ... PASSWORD is already idempotent by nature
-- (setting the same or a new password is always valid, never an error).
ALTER ROLE :"dai_migrator_role" WITH PASSWORD :'dai_migrator_password';
ALTER ROLE :"dai_app_role"      WITH PASSWORD :'dai_app_password';

-- ---------------------------------------------------------------------------------------------------
-- PostgreSQL 15+ hardening: PG15 already stops granting CREATE on a new database's "public" schema to
-- PUBLIC, and this store does not use the "public" schema at all (schema dynamic_ai, LLD-15 §3) — but we
-- revoke explicitly here too, so this script is correct even against an older cluster upgraded in place, or
-- one where a DBA re-granted PUBLIC CREATE by habit. This runs against whichever database this session is
-- currently connected to; for "option B" (host's own database) that is the host's database, so make sure
-- you invoke psql with -d <host database> in that case, not -d postgres.
-- ---------------------------------------------------------------------------------------------------
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------
-- Optional dedicated database ("option A", recommended for PROD — isolates the store's write/backup volume
-- from the host's OLTP database, LLD-15 §14). Set use_dedicated_database=false to skip this block entirely
-- when the store will live in the host's own database, in its own schema ("option B") — in that case run
-- 02_create_schema_and_privileges.sql directly against the host's database instead.
-- ---------------------------------------------------------------------------------------------------
\if :use_dedicated_database

SELECT format('CREATE DATABASE %I WITH OWNER %I ENCODING ''UTF8'' TEMPLATE template0', :'dai_database', :'dai_owner_role')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_database WHERE datname = :'dai_database') \gexec

-- Only the three roles above may even open a connection to this database (PG15+ default already restricts
-- CONNECT on template1-derived databases somewhat, but we make it explicit and unconditional here).
REVOKE CONNECT ON DATABASE :"dai_database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dai_database" TO :"dai_owner_role", :"dai_migrator_role", :"dai_app_role";

\echo 'Dedicated database "'  :dai_database  '" created/verified. Next: run 02_create_schema_and_privileges.sql'
\echo 'connected to that same database (-d '  :dai_database  ').'

\else
\echo 'use_dedicated_database=false: no database created. Next: run 02_create_schema_and_privileges.sql'
\echo 'connected directly to the host application''s own database.'
\endif
