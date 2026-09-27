# Evaluation: design note "Production-grade starter — protect the host" (2026-09-28)

| # | Proposal | Verdict | Where |
|---|----------|---------|-------|
| 1 | Shade Jackson/Gson, Netty/OkHttp, JSqlParser | **Rejected again**, with a stronger alternative (BOM alignment, Enforcer convergence, compatibility matrix CI) | ADR-0012 (reaffirmed) |
| 2a | No bundled metrics server; inject host `MeterRegistry` | **Already designed** | LLD-10 §2 |
| 2b | `registry.counter("ai.agent.tokens.total", "model", …, "tenant", currentTenant)` | **Adapted** — prefix `dynamic.ai.agent.*`; tenant tag off by default and bounded (cardinality) | LLD-10 §2 |
| 2c | Propagate host trace IDs into LLM HTTP headers | **Adapted** — spans join the host trace (that's what gives the LLM-vs-DB split); header propagation to *external* providers off by default, on for internal endpoints | LLD-10 §3 |
| 3a | Hard 10 s connect/read timeouts | **Adapted** — phase timeouts (connect 5 s, first token 20 s, chunk idle 15 s, turn 60 s); a 10 s read timeout would kill healthy streamed answers | LLD-06 §6.1 |
| 3b | Resilience4j breaker "5 failures in a row" | **Adapted** — failure-rate + slow-call-rate sliding window; 429s go to the rate governor, not the breaker | LLD-06 §6.1 |
| 3c | Localized fallback message instead of 500s to the host's global handler | **Adopted** — `model_unavailable` problem localized via host `MessageSource`; fallback model first | LLD-06 §6.1, LLD-12 §4 |
| 4a | `spring-boot-configuration-processor` metadata | **Already designed**, extended with hints and deprecation metadata | LLD-01 §4 |
| 4b | `@Lazy` on heavy components | **Adapted** — disabled features create no beans; enabled features initialise eagerly (fail at deploy, not first request); only heavy optional resources load on first use | LLD-01 §4.1 |
| 4c | `@ConditionalOnMissingBean` on core interfaces | **Already designed** | LLD-01 §3 |
| 4d | Property prefix `agent.platform.*` | **Not adopted** — `dynamic.ai.agent.*` until OQ-17 | ADR-0010 |
| 5 | Dedicated JSON audit log with exact prompt, query, rows, final prompt | **Adapted** — tiered audit: standard (metadata + result hashes) always; full content only in encrypted, access-controlled evidence mode | LLD-10 §4.1, ADR-0018 |
| — | Tutorial link in the note | Not used as a design input | — |
