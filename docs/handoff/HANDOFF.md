# Handoff — state, decisions and what is left (as of 2026-09-30)

For an engineer or agent continuing this repository. Read `CLAUDE.md` first (conventions, module ownership,
non-negotiable design rules), then this file. Design intent is in `docs/`; this file records what changed since,
why, and what remains. Branch: `claude/task-6wrlcn`.

## 1. How to work

- **Build and test** (JDK 25 + Maven 3.9): `scripts/build-offline.sh` (everything, no network, dependencies vendored
  in `offline-repo/`) or `mvn verify`. `-DskipITs` skips the Testcontainers ITs; the ITs need a Docker daemon and
  the `postgres:17-alpine` image. Last full run: all modules green, 1,163 tests.
- **When a pom gains a dependency**, refresh `offline-repo/` in the same commit (procedure: `docs/offline-build.md`).
- **Verify Spring AI / Boot 4 APIs with `javap` on the resolved jars**, never from memory: several plausible 1.x
  APIs do not exist in Spring AI 2.0.1 (e.g. `ChatModel.getDefaultOptions` is deprecated, options come from
  `getOptions()`; tools go through the auto-registered `ToolCallingAdvisor`).
- **Bean-level wiring tests are not enough.** `HostApplicationIT` (autoconfigure module, `com.example.host`) boots a
  real Spring Boot host with the starter, the host's DataSource, JWT security and a scripted `ChatModel`, and drives
  the whole product flow over HTTP. Extend it for anything that touches auto-configuration, security or HTTP. It found
  six bugs that unit tests had missed.
- Commit conventions: `CLAUDE.md`. Record deviations from the LLD/ADR in the commit body and update the doc.

## 2. Decisions taken (product owner unless noted)

| Decision | Where |
|---|---|
| Builds are allowed and the vendored `offline-repo/` is committed (supersedes "do not run Maven") | `CLAUDE.md`, `docs/offline-build.md` |
| **A user's conversation erase keeps the redacted transcript for audit by default** (`conversations.erase-mode=RETAIN_FOR_AUDIT`, `audit-retention` 90d); `HARD` deletes at once; auditors read it (`AUDIT_READ`, reads audited), admins purge (`WORKSPACE_ADMIN`). Hosts must disclose this and confirm a legal basis | OQ-52, integration guide §9 |
| Chat memory (what the model reads) is separate from the transcript: PostgreSQL-backed, redacted, own retention (24h), deleted when a conversation is closed or erased | OQ-45, V9 |
| Model provider failover: per model call (never re-runs tools), stream only before first output, per-provider circuit breaker, per node, no new dependency | OQ-47 |
| JSON_SCHEMA agents' streamed answers are held back (<= 1,000,000 chars), validated at stream end, sent whole or refused | OQ-51 |
| Library controllers stay plain `@Bean`s (no `@Component`/`@Controller`); `DaiControllerRegistrar` maps them into the host's handler mapping | commit e77026a |
| Library security filter chains are registered automatically, ordered after Boot's default chain; opt out with `security.filter-chains.enabled=false` | `DaiWebSecurityAutoConfiguration` |
| Admin writes invalidate the local principal/grant caches and refresh local snapshots; other nodes converge by TTL/poll (ADR-0021) | commit 10714a5 |
| Agent chat authorizes `agent:invoke` | commit 7a1c05a |

## 3. Bugs found and fixed this session (regression tests exist for each)

Streamed turns skipped kill switch/guardrails; agent model options never applied; chat memory lost across replicas;
tool `timeoutSeconds` unenforced; JSON-schema validator bean never activated on networknt 3.x; validator blocked
tool-call rounds; a migration trigger without a pinned `search_path`; library never got its store in a real Boot host
(auto-config ordering); web hosts crashed at startup (missing condition + circular bean); library controllers never
mapped (404 everywhere); security chains never registered; agent chat checked the wrong permission; admin changes
took minutes to apply; the integration guide documented ~15 settings that do not exist.
False alarm worth knowing: `ChatClient.tools(ToolCallback[])` and `toolCallbacks(list)` are equivalent in Spring AI
2.0.1 (verified by experiment, pinned by `ToolRegistrationTest`).

## 4. What is left, in suggested order

**Should do next (product-relevant gaps)**
1. **API keys for service accounts (OQ-37).** `ApiKeyService`, `ApiKeyAuthenticationFilter` exist but have no
   auto-configuration and there is no issuance endpoint, so machine clients need JWTs. Needs an
   `ApiKeyPepperProvider` SPI default, the service bean, an issuance endpoint, and wiring into the security chains
   (`DynamicAiSecurityOptions(apiKeysEnabled=true)`); extend `HostApplicationIT`.
2. **MCP end to end in `HostApplicationIT`** (endpoint enabled, a published tool binding, `tools/list`, `tools/call`
   under a bearer token and the client-approval rule). MCP gaps in OQ-49: stateful sessions and
   `tools/list_changed`, resources/prompts, `insufficient_scope` step-up, per-client rate limits, SDK transport.
3. **Break-glass production override is not configurable (OQ-53).** Bind `environment.production-override.*`.
4. **Per-kind spec validation at authoring time (OQ-41)** and **budget reservation (OQ-40).**
5. **Guardrails follow-ups (OQ-44, OQ-54)** — prompt validation (malicious content, business scope against the
   catalog), PII redaction of prompts/answers/transcripts and the backend-controlled structured display are in
   (`core.guard`, `core.display`, `ai.safety.TurnSafety`, LLD-06 §8); remaining: sync rejections as problem
   responses, output exfiltration filtering, per-workspace redaction policy, authoring-time template validation.

**Smaller / known limits**
- Tool-call messages are not remembered across turns (OQ-45); breaker state has no admin metric beyond
  `GET /dynamic-ai/admin/api/v1/model-providers`; no same-provider retry with backoff (OQ-47).
- Maintenance: audit/telemetry retention beyond partition drop, MCP idle-session sweep (moot until stateful MCP),
  `dai_job_run` admin view (OQ-46). Tracing: content recording (OQ-50); tool rows carry no trace id.
- `clientRequestId` de-duplication and the replay buffer are node-local (OQ-42); `ops:read` permission (OQ-34);
  audit-of-audit reads (OQ-35).
- Proposals: versioning/base-version capture, ENTITY_WRITE, bulk, per-classification approval policy (OQ-48, OQ-36).

**Decisions still needed from a human (`D-BLOCKER` / `D-CONFIG` in `docs/open-questions.md`)**
OQ-02b groupId, OQ-13 distribution (Nexus vs Central), OQ-17 namespace alignment before first release, OQ-29
minimum PostgreSQL, OQ-19/28/30/31 (unknown tier, traceparent to providers, dedicated database, retention). The
`spring-ai-mcp-server-common-spring-boot-starter` publishes an empty JAR by design (dependency aggregator).

**Not started (per the roadmap in `docs/`)**: admin UI (OQ-08/15), WebFlux support (OQ-01), stdio bridge (OQ-24),
stream resumption across nodes (OQ-23).

## 5. About the chat history

The raw session transcripts were deliberately **not** committed: they are ~36 MB of unfiltered tool output and shell
history, and add nothing that the code, commit messages (each explains the why and cites LLD/ADR/OQ ids), the open
questions and this file do not already carry. Git history is the decision log: `git log --format='%h %s%n%b'`.
If a raw archive is still required, keep it outside the repo (private storage), redact it first, and scan it for
credentials and personal data before it goes anywhere shared.
