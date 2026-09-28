#!/usr/bin/env bash
# PostgreSQL init script — runs once when the container is first created.
# Creates the roles and schema that the springAIMcpServerCommon Flyway
# migrations expect.  Passwords are taken from the container's environment
# (set via docker-compose.yml) so they are NEVER hardcoded here.
#
# NEVER use these defaults in production — always override via .env.
set -euo pipefail

MIGRATOR_PASSWORD="${DAI_MIGRATOR_PASSWORD:-dai_migrator_secret}"
APP_PASSWORD="${DAI_APP_PASSWORD:-dai_app_secret}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL

    -- ── Roles ──────────────────────────────────────────────────────────────
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dai_owner') THEN
            CREATE ROLE dai_owner NOLOGIN;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dai_migrator') THEN
            CREATE ROLE dai_migrator LOGIN PASSWORD '$MIGRATOR_PASSWORD';
            GRANT dai_owner TO dai_migrator;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dai_app') THEN
            CREATE ROLE dai_app LOGIN PASSWORD '$APP_PASSWORD';
        END IF;
    END
    \$\$;

    GRANT CONNECT ON DATABASE $POSTGRES_DB TO dai_migrator, dai_app;

    -- ── Schema ─────────────────────────────────────────────────────────────
    CREATE SCHEMA IF NOT EXISTS dynamic_ai AUTHORIZATION dai_owner;

    -- Future tables created by Flyway (as dai_migrator) are auto-granted to dai_app.
    ALTER DEFAULT PRIVILEGES FOR ROLE dai_migrator IN SCHEMA dynamic_ai
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dai_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE dai_migrator IN SCHEMA dynamic_ai
        GRANT USAGE, SELECT ON SEQUENCES TO dai_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE dai_migrator IN SCHEMA dynamic_ai
        GRANT EXECUTE ON FUNCTIONS TO dai_app;

    GRANT USAGE ON SCHEMA dynamic_ai TO dai_app;

EOSQL

echo "PostgreSQL: dai roles and dynamic_ai schema initialised."
