# Database migrations (Liquibase CI/CD)

Properties-configured, tag-and-rollback database deployments. Independent of the library's own
Flyway store (ADR-0019, schema `dynamic_ai`); this is for **application** databases.

## Developer workflow

1. Add one file to `db-migrations/changelog/changes/`, named `YYYYMMDD-NNNN-short-description.sql`
   (alphabetical order = execution order; never edit a file already deployed — add a new one).
2. Use *formatted SQL* and **always write a `--rollback`**:

   ```sql
   --liquibase formatted sql
   --changeset alice:20261014-0001-add-orders-status
   ALTER TABLE orders ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'NEW';
   --rollback ALTER TABLE orders DROP COLUMN status;
   ```
   YAML/XML/JSON changelogs also work if dropped in the same folder.
3. Open a PR. CI validates, migrates a throwaway Postgres, rolls back to empty, migrates again — a
   missing/incorrect rollback fails the PR.
4. Merge to `main` → deployed to **dev**. Staging/prod: run the `db-migrate` workflow manually
   (protect those GitHub Environments with required reviewers).

## Database configuration (no code changes)

`environments/defaults.properties` + `environments/<APP_ENV>.properties`; every key is overridable by
env var (`db.host` → `DB_HOST`). `db.type` ∈ `postgresql|mysql|mariadb|sqlserver|oracle` builds the JDBC URL;
`db.url` overrides it entirely. Credentials only via `DB_USERNAME` / `DB_PASSWORD` (CI secrets).

## Deploy and rollback

`scripts/db/liquibase.sh migrate` = validate → `tag pre-deploy-<DEPLOY_ID>` → `update`.
* A failing changeset → automatic `rollback` to the tag, exit 1 (pipeline stops before app deploy).
* App deploy or health check fails → the workflow's last step runs
  `scripts/db/liquibase.sh rollback pre-deploy-<sha>`.
* Manual: `scripts/db/liquibase.sh rollback <tag>` / `rollback-count <n>` / `status` / `history`.

Run locally: `APP_ENV=dev DB_USERNAME=… DB_PASSWORD=… scripts/db/liquibase.sh status`
(uses the `liquibase` CLI if installed, else `docker run liquibase/liquibase:4.31.1`).

## Caveats

* Rolling the DB back while the *old* app version is serving requires migrations to be
  backward-compatible (expand/contract): add nullable columns first, drop old ones in a later release.
  Rollback of destructive changes (`DROP`) cannot restore data — take a snapshot before prod deploys.
* MySQL/MariaDB/Oracle DDL auto-commits: a changeset that fails midway can leave partial state;
  keep one statement per changeset there.
* Never reuse or edit a deployed changeset id; checksums are validated.
