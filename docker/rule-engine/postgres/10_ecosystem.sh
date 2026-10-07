#!/usr/bin/env bash
# PostgreSQL init (runs once, after docker/postgres/init.sh which creates the library's roles and the dynamic_ai schema).
# Adds what the rule-engine ecosystem needs: the auth service's own role and schema, and a READ-ONLY role for Grafana.
# Passwords come from the container environment (compose, .env), never from this file.
set -euo pipefail
: "${RE_AUTH_PASSWORD:?RE_AUTH_PASSWORD is required}"
: "${GRAFANA_RO_PASSWORD:?GRAFANA_RO_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v auth_pw="$RE_AUTH_PASSWORD" -v ro_pw="$GRAFANA_RO_PASSWORD" <<'EOSQL'
-- the auth service owns its schema re_auth (its own Flyway history): it can never touch dynamic_ai
SELECT format('CREATE ROLE re_auth LOGIN PASSWORD %L', :'auth_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 're_auth') \gexec
CREATE SCHEMA IF NOT EXISTS re_auth AUTHORIZATION re_auth;
ALTER ROLE re_auth SET search_path = re_auth;

-- Grafana reads through a role that cannot write and cannot run long queries; the tables it may read are granted by seed/grants.sql
-- once they exist (the services create them), and never include users, password hashes or chat messages.
SELECT format('CREATE ROLE grafana_ro LOGIN PASSWORD %L', :'ro_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana_ro') \gexec
ALTER ROLE grafana_ro SET default_transaction_read_only = on;
ALTER ROLE grafana_ro SET statement_timeout = '15s';

SELECT format('GRANT CONNECT ON DATABASE %I TO re_auth, grafana_ro', current_database()) \gexec
EOSQL
echo "PostgreSQL: rule-engine ecosystem roles and schema initialised."
