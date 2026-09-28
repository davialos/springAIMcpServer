-- PostgreSQL init script — runs once when the container is first created.
-- Creates the roles and schema that the springAIMcpServerCommon Flyway
-- migrations expect (mirrors scripts/db/postgresql/01 + 02 simplified for dev).
-- NEVER use these credentials in production.

-- ── Roles ────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dai_owner') THEN
        CREATE ROLE dai_owner NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dai_migrator') THEN
        CREATE ROLE dai_migrator LOGIN PASSWORD 'dai_migrator_secret';
        GRANT dai_owner TO dai_migrator;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dai_app') THEN
        CREATE ROLE dai_app LOGIN PASSWORD 'dai_app_secret';
    END IF;
END
$$;

-- Grant connect + usage to both runtime roles
GRANT CONNECT ON DATABASE dai_store TO dai_migrator, dai_app;

-- ── Schema ───────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS dynamic_ai AUTHORIZATION dai_owner;

-- Ensure future tables/sequences created by Flyway (running as dai_migrator)
-- are immediately accessible to dai_app.
ALTER DEFAULT PRIVILEGES FOR ROLE dai_migrator IN SCHEMA dynamic_ai
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE dai_migrator IN SCHEMA dynamic_ai
    GRANT USAGE, SELECT ON SEQUENCES TO dai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE dai_migrator IN SCHEMA dynamic_ai
    GRANT EXECUTE ON FUNCTIONS TO dai_app;

GRANT USAGE ON SCHEMA dynamic_ai TO dai_app;
