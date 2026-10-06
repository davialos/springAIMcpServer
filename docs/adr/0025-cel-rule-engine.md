# ADR-0025: CEL rule engine as an opt-in module
- Status: Accepted
- Date: 2026-10-05
- Deciders: product owner (request of 2026-10-05), lld-chief-architect

## Context
Applications that embed the starter need business rules that operations staff change at runtime, per tenant and
organization, evaluated against a library of named parameters, answered in the end user's language, and able to
trigger e-mail, push or an API call. The library already has PostgreSQL as its single store (ADR-0019), DB-driven
cache refresh (ADR-0006) and "stateless, no new infrastructure" defaults (ADR-0021).

## Options considered
1. **Google CEL (`dev.cel:cel`, cel-java)** — non-Turing-complete, type-checked, side-effect free, sandboxed by
   construction, expression text is a plain string we can store and validate when it is saved.
2. SpEL — can call arbitrary beans and reflection; unsafe for expressions authored in a UI.
3. Drools / a DMN engine — heavy, its own authoring model and persistence, hard to align with our schema.
4. Hand-written expression language — we would own the parser, type checker and security review.

## Decision
- Use **cel-java 0.14.0**. The parameter library is the CEL environment: each `sys_object.code` + `.` +
  `sys_object_attribute.code` is declared as one typed CEL variable (`customer.age : int`), so rules are
  type-checked when saved and an expression can only read parameters that exist.
- New module `spring-ai-mcp-server-common-ruleengine` (no Spring) and tables `dai_re_*` in migration V11 of the existing
  `dynamic_ai` schema. The module is **not** in the default starter (CEL brings protobuf and Guava); a host adds the
  artifact and sets `dynamic.ai.agent.rule-engine.enabled=true`; `DaiRuleEngineAutoConfiguration` wires it.
- Four evaluation policies per rule group: `FIRST_MATCH`, `ALL_MATCH`, `EVALUATE_ALL`, `COMPOSITE` (semantics in LLD-18).
- Messages are multilingual bundles (`dai_re_sys_bundle` + `dai_re_sys_bundle_message`), resolved per request with a
  language fallback chain.
- Cache: immutable per-node snapshots refreshed from `dai_re_change_marker` rows bumped by database triggers; no
  cross-node messaging, no sticky state (ADR-0021).
- **Fail closed:** a rule that cannot be evaluated is an `ERROR` outcome carrying the group's `on_error` action
  (default `BLOCK`). Input values are never stored, logged or echoed back.
- API channels obey an environment guard (DEV/QA/PROD only call their own environment; an unclassifiable API
  needs a recorded human confirmation); e-mail and push are ports the host implements (ADR-0002).
- This module does **not** execute writes on behalf of a model (ADR-0009 is unaffected); it returns decisions to the
  application, which enforces them.

## Consequences
- + Rules are editable at runtime and validated before they can run; one source of truth in PostgreSQL.
- + Replicas converge within one poll interval (default 10 s) with no extra infrastructure.
- − CEL's type system is strict (`int` vs `double`); rule authors write `double(customer.creditScore)`. The parameter
  data type is therefore a first-class column.
- − The module pulls ~10 MB of dependencies (protobuf, Guava, ANTLR, re2j) into hosts that opt in; `offline-repo/` grows
  accordingly.
- − The UI on top of the authoring API is not part of this decision (OQ-70).

## Addendum 2026-10-06: authoring, lifecycle, outbox, partitioning (OQ-65..68)
- **Authoring is a REST API in `autoconfigure`** backed by plain-JDBC services in the `ruleengine` module
  (`RuleLifecycle`, `RuleConfigAdmin`, `ExpressionTester`), gated by four new permissions (`rules:read|author|publish|library`) and,
  for writes, by the AUTHORING capability (LLD-12). The workspace is the tenant.
- **Lifecycle by revisions.** The live rows keep only published content; drafts live in `dai_re_revision` (jsonb), move
  DRAFT → SUBMITTED → APPROVED → PUBLISHED with a database-enforced reviewer ≠ submitter, and are applied in one
  transaction. Rejected alternative: reuse `dai_resource` / `ConfigStore` (LLD-09) — it is workspace/JPA-centric and
  its publish produces snapshot generations the engine does not read; the rule engine already has its own change markers.
- **Outbox in PostgreSQL** (`dai_re_dispatch`), claimed with `FOR UPDATE SKIP LOCKED` + lease, **database clock** for every time
  comparison, at-least-once with a dispatch id for de-duplication, recipient scrubbed on delivery (ADR-0021: no new
  infrastructure). Rejected: Kafka/Redis queues; a Spring `@Scheduled` per node without claiming (double sends).
- **Evaluation log partitioned monthly** and registered with the existing maintenance job (retention 13 months, overridable).
- Consequence: PROD rule changes need the production override (OQ-72) until a signed-bundle path exists for rules.
