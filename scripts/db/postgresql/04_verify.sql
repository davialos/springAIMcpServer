-- =====================================================================================================
-- scripts/db/postgresql/04_verify.sql
--
-- Read-only health checks for a dynamic_ai store: migration history, partition coverage vs configured
-- lookahead, DEFAULT-partition emptiness, environment identity, audit chain heads, and dai_app's effective
-- grants. Safe to run at any time, against any environment, by anyone with SELECT on the schema — it makes
-- no changes. Intended for use after initial provisioning, after every restore (LLD-15 §14 restore drill),
-- and as a routine part of on-call/DBA monitoring.
--
-- Run as any role with USAGE + SELECT on dynamic_ai (dai_app itself is sufficient; a superuser or dai_owner
-- also works and additionally sees the role-grant queries in §6 without restriction).
--
-- Usage:
--   psql -h <host> -U dai_app -d <dai_database-or-host-database> -v dai_schema=dynamic_ai -f 04_verify.sql
--
-- Idempotency note: unlike 01_/02_/03_, this script performs no DDL/DML at all, so "idempotent" here simply
-- means "produces the same report given the same data" — there is nothing to guard with WHERE NOT EXISTS.
-- The one exception is §3's loop, which is read-only DML wrapped in a DO $$ ... $$ block purely because
-- per-partition row counts need a dynamic SELECT per table — the block contains no psql :variables, so none
-- of the dollar-quoting/substitution caveats from 01_/02_'s comments apply here.
-- =====================================================================================================

\set ON_ERROR_STOP on
\set dai_schema dynamic_ai
\pset pager off

-- ---------------------------------------------------------------------------------------------------
-- 1. Flyway migration history — confirms which version is applied and that every entry succeeded.
-- ---------------------------------------------------------------------------------------------------
\echo '=== 1. Flyway migration history (dai_schema_history) ==='
SELECT installed_rank, version, description, type, checksum, success, installed_by, installed_on, execution_time
FROM dynamic_ai.dai_schema_history
ORDER BY installed_rank;

-- ---------------------------------------------------------------------------------------------------
-- 2. Environment identity — the LLD-12 §3 cross-environment guard: exactly one row, and its tier/id must
--    match the deployment you believe you are connected to. Zero rows means the application has never
--    started against this store yet (or the store was restored from a backup taken before the first start).
-- ---------------------------------------------------------------------------------------------------
\echo ''
\echo '=== 2. Environment identity (dai_environment) — verify tier/environment_id match this deployment ==='
SELECT singleton, environment_id, tier, created_at FROM dynamic_ai.dai_environment;

-- ---------------------------------------------------------------------------------------------------
-- 3. DEFAULT-partition emptiness — every "<table>_pdefault" partition should have zero rows. A non-zero
--    count means rows are landing outside the monthly partitions the maintenance job creates: either the
--    maintenance job has not run recently enough, is failing, or a row was inserted with a timestamp far
--    outside the expected window (clock skew, a backfill/import, or a bug).
-- ---------------------------------------------------------------------------------------------------
\echo ''
\echo '=== 3. DEFAULT partitions (should all report 0 rows) ==='
DO $$
DECLARE
    r   record;
    cnt bigint;
BEGIN
    FOR r IN
        SELECT c.relname AS default_partition
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'dynamic_ai' AND c.relname LIKE '%\_pdefault'
        ORDER BY c.relname
    LOOP
        EXECUTE format('SELECT count(*) FROM dynamic_ai.%I', r.default_partition) INTO cnt;
        IF cnt = 0 THEN
            RAISE NOTICE '%: OK (0 rows)', r.default_partition;
        ELSE
            RAISE WARNING '%: % row(s) — investigate before this grows further', r.default_partition, cnt;
        END IF;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------------------------------
-- 4. Partition coverage vs configured months_ahead (dai_partitioned_table) — lists the monthly partition
--    every partitioned table SHOULD have for "this month + months_ahead" and whether it actually exists.
--    A "false" row this close to month-end means the maintenance job is behind schedule or disabled; the
--    application will hard-fail inserts for that month once it arrives if no partition and no matching
--    DEFAULT-partition capacity exists.
-- ---------------------------------------------------------------------------------------------------
\echo ''
\echo '=== 4. Expected partition coverage (dai_partitioned_table.months_ahead) ==='
WITH expected AS (
    SELECT pt.table_name, pt.retention_months, pt.months_ahead, gs.offset_months
      FROM dynamic_ai.dai_partitioned_table pt
      CROSS JOIN LATERAL generate_series(0, pt.months_ahead) AS gs(offset_months)
)
SELECT e.table_name,
       to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => e.offset_months), 'YYYY-MM') AS month,
       e.table_name || '_p' || to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => e.offset_months), 'YYYYMM') AS expected_partition,
       (to_regclass('dynamic_ai.' || e.table_name || '_p' ||
                     to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => e.offset_months), 'YYYYMM')
                    ) IS NOT NULL) AS exists
  FROM expected e
 ORDER BY e.table_name, month;

-- ---------------------------------------------------------------------------------------------------
-- 5. Audit chain heads — one row per chain (a workspace, plus "system"/"global-admin"). event_rows can be
--    LOWER than chain_head_seq once older monthly partitions have been dropped by retention (expected, not a
--    problem: the chain's cryptographic head is independent of how much history is still stored). A full
--    tamper-evidence check (recomputing every hash from chain_seq=1 forward) is deliberately NOT part of this
--    routine script — it is an O(chain length) scan best run as a dedicated, scheduled audit job, not on
--    every ad hoc verify call.
-- ---------------------------------------------------------------------------------------------------
\echo ''
\echo '=== 5. Audit chain heads (dai_audit_chain) vs currently retained event rows ==='
SELECT ac.chain_id,
       ac.last_seq       AS chain_head_seq,
       ac.last_event_at,
       count(ae.*)        AS event_rows_retained
  FROM dynamic_ai.dai_audit_chain ac
  LEFT JOIN dynamic_ai.dai_audit_event ae ON ae.chain_id = ac.chain_id
 GROUP BY ac.chain_id, ac.last_seq, ac.last_event_at
 ORDER BY ac.chain_id;

-- ---------------------------------------------------------------------------------------------------
-- 6. dai_app's effective grants — confirms the runtime role has exactly DML (no DDL, no ownership) and that
--    its role-level connection settings (search_path, timeouts, from 02_) are in place.
-- ---------------------------------------------------------------------------------------------------
\echo ''
\echo '=== 6a. dai_app table/sequence/function grants in dynamic_ai ==='
SELECT table_name, privilege_type
  FROM information_schema.role_table_grants
 WHERE grantee = 'dai_app' AND table_schema = 'dynamic_ai'
 ORDER BY table_name, privilege_type;

\echo ''
\echo '=== 6b. dai_app role-level settings (expect search_path, statement_timeout, idle_in_transaction_session_timeout) ==='
SELECT rolname, rolconfig FROM pg_catalog.pg_roles WHERE rolname = 'dai_app';

\echo ''
\echo '=== 6c. dai_migrator role membership (expect member of dai_owner) ==='
SELECT g.rolname AS member_role, r.rolname AS member_of
  FROM pg_catalog.pg_auth_members m
  JOIN pg_catalog.pg_roles r ON r.oid = m.roleid
  JOIN pg_catalog.pg_roles g ON g.oid = m.member
 WHERE g.rolname = 'dai_migrator';

\echo ''
\echo 'Verification complete. Any WARNING above (section 3) or an empty/unexpected result in sections 1, 2,'
\echo '5, or 6c needs investigation before the store is considered healthy.'
