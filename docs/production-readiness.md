# Production Readiness Gates

Owner: production-readiness-reviewer · Status: Draft v1

A release is production-ready only when every gate is ✅ with linked evidence.

## G1 — Security
- [ ] Authz matrix tests (SEC-01 §5) pass for all planes
- [ ] Runs-as-caller tests pass (tool → `@PreAuthorize` method denied for unprivileged user)
- [ ] Query injection fuzz corpus (≥ 1 000 payloads) — zero grammar errors / zero unauthorized rows
- [ ] Prompt-injection corpus (direct + indirect via tool output) — zero writes without a user-confirmed proposal
- [ ] Host history/audit rows (Envers, history tables, `@LastModifiedBy`, triggers) record the confirming user for every applied proposal
- [ ] Proposal conflict (concurrent host edit) and crash-during-apply reconciliation tests pass
- [ ] AI write guard: inserts/updates/deletes (incl. `REQUIRES_NEW`) inside AI read tools are vetoed; auto-disable after repeated violations
- [ ] Policy layers can never expose or widen (property tests); invalid policy file disables AI tools
- [ ] MCP endpoint rejects unauthenticated, wrong-audience, and unapproved-client tokens; `insufficient_scope` step-up works
- [ ] Streams always terminate (`turn.end`/`error`), heartbeat stops with content, client disconnect cancels model + tools
- [ ] Refuses to start without security configured (non-dev)
- [ ] Dependency scan (OWASP DC / Trivy) no HIGH/CRITICAL; SBOM (CycloneDX) published
- [ ] Threat model (SEC-02) reviewed by host security team

## G2 — Resilience
- [ ] Every external call has timeout; breakers on LLM providers & MCP servers
- [ ] LLM provider outage test: endpoints without LLM unaffected; agents 503 within timeout
- [ ] Config DB outage test: data plane keeps serving last-good snapshot
- [ ] Load test: data-plane overhead p99 < 5 ms at target RPS; bulkheads reject fast under overload
- [ ] Budget hard caps enforced under concurrent load (reservation model)
- [ ] LLD-14 §8 performance budgets met (overhead p99, TTFT overhead, parallel fan-out, 1 000 concurrent streams per node)
- [ ] Zero pinned virtual threads in our code (JFR `jdk.VirtualThreadPinned`) under load
- [ ] Provider quota burst test: no unhandled provider 429s reach users; fair queueing across workspaces

## G3 — Consistency & lifecycle
- [ ] Multi-node publish converges ≤ 30 s; kill switch ≤ 10 s
- [ ] No 404/5xx during publish-while-serving (replace scenario)
- [ ] Rollback tested; drift detection suspends affected resources
- [ ] Schema migrations tested upgrade from every released version on all supported DBs

## G4 — Observability
- [ ] Metrics & spans per LLD-10 visible in sample Grafana dashboard
- [ ] Audit events for every action in the event catalog; hash chain verifiable
- [ ] Content recording off by default; redaction verified with seeded PII
- [ ] Standard audit contains no prompt/row content (seeded-PII scan of audit table and log output); evidence mode encrypted, auditor-only, crypto-shredding verified
- [ ] Traces show LLM / tool / query segments inside the host's trace; no `traceparent` sent to external providers by default
- [ ] Provider breaker: opens on failure/slow-call rate, not on 429 bursts; `model_unavailable` localized

## G5 — Host friendliness
- [ ] Sample hosts (OIDC+JPA, LDAP session, multi-module) boot with no collisions
- [ ] `dynamic.ai.agent.enabled=false` ⇒ zero beans contributed
- [ ] Startup overhead < 1.5 s for 2 000-element catalog
- [ ] Every default bean overridable (context-runner tests)
- [ ] Environment matrix tests (LLD-12 §8): authoring/introspection/preview beans absent in PROD and UNKNOWN; tier conflict ⇒ PROD
- [ ] Chaos: exception in each of our initialisers ⇒ host still starts and serves
- [ ] No global side effects: host ObjectMapper, error handling, filters, and MVC config unchanged with our starter present
- [ ] Pool-starvation test: saturated dynamic queries don't break host repository latency SLO
- [ ] Config-store identity mismatch (stage app → prod config DB) refuses to load snapshots

## G6 — Operability & docs
- [ ] Runbooks: LLM outage, budget exhaustion, snapshot lag, compromised API key, bad publish rollback
- [ ] Integration guides: Entra ID, Okta, Keycloak, LDAP/AD, gateway-header auth
- [ ] Upgrade guide & SPI compatibility policy (SemVer; SPI changes only in majors)
- [ ] Data retention & erasure documented (GDPR Art. 17 flow for conversations)

## G7 — AI quality
- [ ] Eval suites for sample agents ≥ threshold; tool-trajectory accuracy tracked per release
- [ ] Model/provider matrix tested (OpenAI, Anthropic, Bedrock, Ollama at minimum)
