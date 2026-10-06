# ADR-0026: Local rule-engine ecosystem (services, console, observability)
- Status: Accepted
- Date: 2026-10-06
- Deciders: product owner (request of 2026-10-05), lld-chief-architect

## Context
ADR-0025 delivered the CEL rule engine as a library with no HTTP surface (OQ-65). The product owner asked for a complete,
testable local ecosystem started with one command: a login with tenant and organization carried in a Protocol Buffers
message, a few seeded users (admin, user), a console to view the rule setup and create rule groups, administrator-only logs
(evaluations, audit trail, chat), Grafana dashboards over those logs, and every log table and API wired in.

## Decision
- **Deployables live outside the library reactor** in `docker/rule-engine/` (like `docker/demo-app`): `contract` (proto),
  `auth-service`, `rule-engine-service`, `ui`. They depend on the library artifacts (`ruleengine`, `persistence`); the library
  gains only migration **V12** (`dai_re_audit_log`) and the sample-data fix.
- **Protocol Buffers contract** (`session.proto`: `LoginRequest`, `Session`, `LoginResponse`, `ErrorResponse`) is compiled for
  Java (protobuf-maven-plugin) and for the console (protobufjs *static* code, so the CSP needs no `unsafe-eval`). The login
  endpoint speaks `application/x-protobuf` (JSON accepted for tools). A golden-bytes test pins the encoding across languages.
- **Tenant and organization come only from signed token claims** (`tenant_id`, `org_id`, `role`; HS256, key from `.env`).
  Request bodies and parameters never choose a scope. An organization user sees tenant-wide items plus their own organization's;
  a USER changes only items of their own organization, an ADMIN any visible item; sharing with the whole tenant needs ADMIN; a
  tenant-wide group cannot contain organization-private rules. Rules and groups are validated by `compileBoolean` when saved.
- **Roles:** USER authors and evaluates; ADMIN additionally reads `/api/v1/admin/**` (logs). Enforced in the service, not the UI.
- **Logs** are value-free: evaluations (`dai_re_evaluation*`), the authoring/evaluation audit trail (`dai_re_audit_log`, written in
  the same transaction as the change), sign-in attempts (`re_auth_login_event`), a chat log, plus container logs (JSON) in Loki and
  Micrometer metrics in Prometheus. Grafana reads PostgreSQL through a **SELECT-only role** limited to the log and name tables
  (never users, password hashes or chat text).
- **Compose stack** (`name: ruleengine`): postgres, auth, rules, seed, nginx console (one origin, strict CSP owned by one file),
  Prometheus, Loki, Promtail, Grafana, started by `scripts/rule-engine/dev.sh up`. Ports are bound to 127.0.0.1, secrets are
  generated into a git-ignored `.env`, the auth service has its own PostgreSQL role and schema (`re_auth`).
- **UI design:** the Figma file could not be read from the build environment, so the console is built on a token stylesheet
  (`ui/src/styles/tokens.css`) and is **not** a copy of the Figma design (OQ-70).

## Consequences
- + The whole flow (login → setup → authoring → evaluation → admin logs → dashboards) runs and is tested end to end
  (service ITs, UI unit tests, Playwright through nginx, `dev.sh smoke`, `dev.sh dashboards`).
- + One owner per fact: scope from the token; CSP in `security-headers.conf`; wire format in the proto; dashboard JSON generated.
- − Local-stack conveniences are unsafe elsewhere: seeded passwords shown on the login page (`DEV_LOGIN_HINT`), Promtail mounts
  the Docker socket, Grafana admin/admin, services' actuator ports published on loopback. A real deployment replaces the auth
  service with the host's identity provider (the token claims are the contract) and builds the console with the hint off.
- − Account lockout (5 failures / 15 min per username) can be used to lock a known user out; accepted for a dev stack.
- − Channels are read-only in the console and no e-mail/push senders are wired (ports only, LLD-18 §6).
