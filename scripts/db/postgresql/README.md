# PostgreSQL DBA scripts — `dynamic_ai` store

These scripts provision and verify the PostgreSQL store springAIMcpServerCommon uses for configuration,
versioning, AI/MCP telemetry, write proposals, audit and usage/budgets (ADR-0019). They are the DBA-facing
counterpart to `docs/lld/15-database-schema.md` (schema design) and
`docs/integration/host-integration-guide.md` (how a host application wires up to this store). Nothing here
replaces the Flyway migrations in
`spring-ai-mcp-server-common-persistence/src/main/resources/db/dynamic-ai/migration/` — those remain the only
source of the actual schema (tables, constraints, functions). These scripts only create the roles, database,
schema, and grants those migrations run inside, plus a read-only verification script.

## Requirements

- PostgreSQL **15 or newer** (16/17 recommended) — see LLD-15 §2 for why.
- `psql` (any recent client version) with a connection that has `CREATEROLE`/`CREATEDB` on the target
  cluster for scripts 01–02 (a superuser is the simplest choice for a one-time setup).
- These scripts use `psql`-specific syntax (`\set`, `\if`/`\else`/`\endif`, `\gexec`, `\echo`) — they must be
  run with `psql -f`, not piped through a generic SQL client.

## Order of execution

| # | Script | Run as | Run once per | Purpose |
|---|--------|--------|---------------|---------|
| 1 | `01_create_roles_and_database.sql` | superuser / `CREATEROLE`+`CREATEDB` | PostgreSQL **cluster** (roles are cluster-wide) | Creates `dai_owner`, `dai_migrator`, `dai_app`; optionally creates a dedicated database |
| 2 | `02_create_schema_and_privileges.sql` | superuser / `CREATE` on the target database | **database** that will host the store | Creates schema `dynamic_ai`; wires `ALTER DEFAULT PRIVILEGES` so every future migration-created object auto-grants `dai_app` DML |
| — | Flyway migration (app-run or DBA-run — see below) | `dai_migrator` | every schema version bump | Creates/alters the actual tables, functions, triggers |
| 3 | `03_post_migration_grants.sql` | `dai_owner` or `dai_migrator` | any time, especially if step 2 ran *after* Flyway already ran once, or after any manual DDL | Catch-up grant for objects that predate step 2's default privileges |
| 4 | `04_verify.sql` | `dai_app` (or higher) | any time — initial setup, after every restore, routine monitoring | Read-only health report: migration history, partition coverage, audit chain heads, grants |

Re-running 1–3 is always safe (idempotent — see each file's header for the technique used). Script 4 makes no
changes at all.

## Variables

Every script has sensible `\set` defaults at its top; override any of them with `-v NAME=value` on the `psql`
command line rather than editing the file (keeps the scripts identical across environments and out of your
shell history for anything secret):

| Variable | Default | Used in |
|---|---|---|
| `dai_owner_role` | `dai_owner` | 01, 02 |
| `dai_migrator_role` | `dai_migrator` | 01, 02 |
| `dai_migrator_password` | `CHANGE_ME_MIGRATOR` — **always override this** | 01 |
| `dai_app_role` | `dai_app` | 01, 02, 03, 04 (04 connects *as* this role by default) |
| `dai_app_password` | `CHANGE_ME_APP` — **always override this** | 01 |
| `use_dedicated_database` | `true` | 01 |
| `dai_database` | `dai_store` | 01 |
| `dai_schema` | `dynamic_ai` | 02, 03, 04 |

Example, dedicated database:
```bash
psql -h db.internal -U postgres -d postgres \
     -v dai_migrator_password="$(vault kv get -field=password secret/dai/migrator)" \
     -v dai_app_password="$(vault kv get -field=password secret/dai/app)" \
     -v use_dedicated_database=true -v dai_database=dai_store \
     -f 01_create_roles_and_database.sql

psql -h db.internal -U postgres -d dai_store -f 02_create_schema_and_privileges.sql
```

Example, host's own database ("option B"):
```bash
psql -h db.internal -U postgres -d postgres \
     -v dai_migrator_password=... -v dai_app_password=... \
     -v use_dedicated_database=false \
     -f 01_create_roles_and_database.sql

psql -h db.internal -U postgres -d orders_prod -f 02_create_schema_and_privileges.sql
```

## Two operating modes for running the migrations

`dynamic.ai.agent.store.migrate` (default `true`) selects who runs the Flyway migrations in
`spring-ai-mcp-server-common-persistence/.../db/dynamic-ai/migration/`.

### Mode A — the application runs its own migrations (`store.migrate=true`, the default)

The application's `DaiPersistenceUnit` (ADR-0019) runs Flyway itself at startup, connected with the
credentials in `dynamic.ai.agent.store.migration.username`/`.password` if set, otherwise the runtime
datasource credentials (`dynamic.ai.agent.store.datasource.*`). **Point those migration credentials at
`dai_migrator`**, not `dai_app` — `dai_app` has no `CREATE` on the schema and Flyway will fail. This is the
right mode for DEV/TEST, and for STAGE/PROD deployments where the CI/CD pipeline is trusted to run DDL as part
of a rollout.

```yaml
dynamic:
  ai:
    agent:
      store:
        migrate: true
        migration:
          username: dai_migrator
          password: ${DAI_MIGRATOR_PASSWORD}
        datasource:
          username: dai_app
          password: ${DAI_APP_PASSWORD}
```

### Mode B — a DBA runs migrations out-of-band (`store.migrate=false`)

Set `dynamic.ai.agent.store.migrate=false` so the application never attempts DDL at startup (it will still
optionally run `dynamic.ai.agent.store.validate-schema=true` to confirm the schema it finds matches what it
expects, without applying anything). A DBA runs the Flyway CLI directly, as `dai_migrator`, ahead of the
application rollout:

```bash
flyway \
  -url="jdbc:postgresql://db.internal:5432/dai_store" \
  -user=dai_migrator -password="$DAI_MIGRATOR_PASSWORD" \
  -schemas=dynamic_ai -defaultSchema=dynamic_ai -table=dai_schema_history \
  -locations=filesystem:/opt/releases/springaimcpservercommon/db/dynamic-ai/migration \
  migrate
```

Or, if the persistence module's jar is on the classpath of a small runner (e.g. a `flyway-maven-plugin`
invocation or a thin CLI wrapper that has `spring-ai-mcp-server-common-persistence` as a dependency), the same
migrations can be referenced from the classpath instead of the filesystem:

```bash
flyway \
  -url="jdbc:postgresql://db.internal:5432/dai_store" \
  -user=dai_migrator -password="$DAI_MIGRATOR_PASSWORD" \
  -schemas=dynamic_ai -defaultSchema=dynamic_ai -table=dai_schema_history \
  -locations=classpath:db/dynamic-ai/migration \
  migrate
```

After the DBA-run migration, run `03_post_migration_grants.sql` and then `04_verify.sql` before starting (or
restarting) the application against that database. Use Mode B for any PROD deployment where the deploying
organisation requires all schema DDL to go through a DBA change-control process separate from the application
rollout.

## What each script does (summary)

- **`01_create_roles_and_database.sql`** — creates `dai_owner` (`NOLOGIN`, owns everything), `dai_migrator`
  (`LOGIN`, member of `dai_owner`, used by Flyway in either mode above), `dai_app` (`LOGIN`, runtime, DML
  only); optionally creates a dedicated database; applies PostgreSQL 15+ hardening (`REVOKE CREATE ON SCHEMA
  public FROM PUBLIC`, `REVOKE CONNECT ... FROM PUBLIC` on a dedicated database).
- **`02_create_schema_and_privileges.sql`** — creates schema `dynamic_ai` owned by `dai_owner`; sets
  `ALTER DEFAULT PRIVILEGES` so `dai_app` automatically gets `SELECT/INSERT/UPDATE/DELETE` on future tables,
  `USAGE/SELECT` on future sequences, `EXECUTE` on future functions (and explicitly denies `PUBLIC EXECUTE`,
  which PostgreSQL grants by default at `CREATE FUNCTION` time); sets `dai_app`'s `search_path`,
  `statement_timeout` and `idle_in_transaction_session_timeout`. Append-only tables
  (`dai_snapshot`, `dai_snapshot_entry`, `dai_change_proposal_event`, `dai_audit_event`, `dai_audit_evidence`)
  are protected by the `dai_forbid_modification()` trigger regardless of these grants — do not attempt to
  narrow `dai_app`'s grants for those five tables specifically; the trigger is the real control (LLD-15 §7).
- **`03_post_migration_grants.sql`** — idempotent catch-up: re-applies the same grants as 02_'s default
  privileges directly onto every object that currently exists, for objects created before 02_ ran (or by
  anything other than a migration).
- **`04_verify.sql`** — read-only report: Flyway migration history, environment identity row, DEFAULT-partition
  emptiness, expected-vs-actual partition coverage against `dai_partitioned_table.months_ahead`, audit chain
  heads vs currently retained event rows, and `dai_app`'s effective grants/role settings.

## Notes for the reviewing DBA

- Every script is safe to read top-to-bottom without running it — comments explain the reasoning inline,
  not just the mechanics.
- Nothing here ever connects with, stores, or logs the actual `dai_migrator`/`dai_app` passwords beyond what
  you pass on the command line; treat your shell history accordingly (prefer `-v pass=$(secret-tool ...)`
  over a literal password on the command line where your shell logs history).
- These scripts never touch any table or schema outside `dynamic_ai` (and, for 01_, the roles/database
  objects that hold it) — they never assume anything about the host application's own schema.
