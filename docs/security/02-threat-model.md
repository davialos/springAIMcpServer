# SEC-02: Threat Model (STRIDE + OWASP LLM Top 10)

Owner: access-management-architect · Status: Draft v1

## 1. Assets
Host database rows; host business operations (tool-callable); LLM provider credentials;
config (agents, prompts, policies); conversations & traces (may hold PII); audit log; API keys.

## 2. Trust boundaries
```
[Internet/consumers] ─TB1─ [Host HTTP + our filter chain] ─TB2─ [Our engine] ─TB3─ [Host beans / DB]
                                                                     │
                                                                    TB4─ [LLM provider]   TB5─ [External MCP servers]
[Admins] ─TB1'─ [Admin API/UI]                    [Host code annotations + policy JSON] ─TB6─ [effective catalog]
```

## 3. STRIDE
| # | Threat | Where | Mitigation | Doc |
|---|--------|-------|-----------|-----|
| S1 | Spoofed caller via forged API key | TB1 | Hashed keys, prefix lookup, constant-time compare, expiry, rate limit on auth failures | SEC-01 §9 |
| S2 | MCP client impersonation / unapproved client | TB1 | OAuth 2.1 bearer with audience check (RFC 8707), approved-client registry, session bound to token subject, Origin validation | LLD-07 §5.3/5.5 |
| E7 | Agent/MCP client escalates privileges (read → propose → admin) | TB1/TB3 | Scopes ∩ grants checked on every tool call; `insufficient_scope` step-up; no admin surface in MCP; no server-side scope accumulation | LLD-07 §5.4 |
| I7 | Existence oracle via tool results ("not permitted" vs "empty") | TB3 | RLS-filtered results indistinguishable from empty; logical entity names only | LLD-07 §3a |
| I9 | Audit/log pipeline becomes a copy of regulated data | TB2 | Standard audit stores hashes/IDs only; full content only in encrypted evidence mode with auditor-only access | ADR-0018 |
| I8 | Prompts/PII in URLs and logs; internals in stream errors | TB1 | POST-only streaming; problem codes instead of exception messages | LLD-13 §2–4 |
| T1 | Tampering with published config in DB | TB2 | Spec hash per revision, snapshot manifest hash verified on load, audit hash chain | LLD-09 |
| T2 | Tampered import bundle | Control | Ed25519 signature verification | LLD-09 §5 |
| T3 | Malicious/third-party dependency adds `@Ai*` annotations | TB6 | Scan limited to host base packages; new elements require overlay approval before use in prod (`catalog.require-approval-for-new=true`); golden-file CI diff | LLD-02 §3.2 |
| T5 | Policy JSON used to expose or widen access | TB6 | Policy layers can only disable/restrict/re-describe; invalid file fails closed | LLD-03 §4 |
| E6 | Read action performs hidden writes (hallucinated or buggy tool) | TB3 | Read-only tx + Hibernate write veto in AI scope; auto-disable after repeated violations | ADR-0014 |
| R1 | Admin denies making a change | Control | Immutable, hash-chained audit with actor + IdP subject | LLD-10 §4 |
| I1 | Data exfil via query outside allow-list | TB3 | AST allow-list, Criteria only, row policies, masking | LLD-05 |
| I2 | Agent leaks other users' data | TB3 | Runs-as-caller, row policies on query tools, memory keyed by principal | LLD-06/07 |
| I3 | Sensitive data sent to external LLM | TB4 | Classification-based provider allow-list, PII redaction, on-prem routing | LLD-06 §6/§8 |
| I4 | Secrets in prompts/logs/traces | all | Content recording off by default, redaction pipeline, secret scanner on descriptions | LLD-02 §8 |
| I5 | Error messages reveal SQL/internal structure | TB1 | Problem Details with codes only | LLD-04 §4 |
| D1 | Expensive queries / LLM loops | TB3/TB4 | Timeouts, row caps, join depth, tool-call caps, budgets, bulkheads | LLD-05/06/10 |
| D2 | Publish storm / route churn | Control | Rate limit publishes; route replace without re-registration | LLD-04 §3 |
| E1 | Confused deputy through agent tools | TB3 | Tools run as caller via proxies; per-call re-authz | ADR-0008 |
| E2 | Author approves own change | Control | SoD enforced server-side | SEC-01 §6 |
| E3 | Group-mapping misconfig grants admin broadly | Control | Mapping changes require SECURITY_ADMIN + approval; preview "who gains access" before save | SEC-01 §3 |
| E4 | Model-supplied args override identity-bound params | TB3 | `argConstraints` overwrite principal-bound args server-side | LLD-07 §3 |
| E5 | Prompt injection triggers a data write | TB3 | Model can only create proposals; confirm = user HTTP request with content hash, CSRF, re-authz | LLD-11 §9 |
| T4 | Proposal altered between review and apply | TB2 | Content hash checked at confirm; edits re-validate & re-hash; version-token conflict check | LLD-11 §3 |
| R2 | Write not attributable to a person | TB3 | Apply runs as confirming user ⇒ host AuditorAware/Envers/triggers record them; proposal ↔ host revision link | LLD-11 §6 |
| I6 | Model fabricates data shown in UI as DB data | UI | Display payload rows must hash-match this turn's tool results | LLD-11 §7.1 |

## 4. OWASP Top 10 for LLM Applications (2025) mapping
| Risk | Mitigation |
|------|-----------|
| LLM01 Prompt injection (direct & indirect via tool/RAG output) | Tool outputs framed as data, mutating tools only create user-reviewed proposals (LLD-11), least-privilege tools, output exfil filter, no config-changing tools |
| LLM02 Sensitive information disclosure | Classification, masking, redaction, runs-as-caller, provider allow-list |
| LLM03 Supply chain | Pinned model/provider config, MCP server allow-list + description pinning, SBOM for our JAR |
| LLM04 Data & model poisoning | RAG sources ACL'd and admin-registered; (no training in scope) |
| LLM05 Improper output handling | Output never executed; JSON schema validation; HTML-escaped in UI; no auto-rendering of links/images from model output in admin UI |
| LLM06 Excessive agency | Allow-listed tools per agent, mutating flag ⇒ proposal-only writes, caps, approval for tool additions |
| LLM07 System prompt leakage | No secrets in system prompts (lint on submit); prompts treated as non-secret by design |
| LLM08 Vector & embedding weaknesses | Per-document ACL metadata filters at retrieval; tenant partitioning |
| LLM09 Misinformation | Eval suites, citations of tool results, "I don't know" guidance in templates |
| LLM10 Unbounded consumption | Budgets, rate limits, token caps, timeouts |

## 5. Residual risks (accepted / to revisit)
- Prompt injection cannot be fully prevented; impact limited by least privilege + reviewed proposals (no write without explicit user confirm).
- In-memory rate limits are per node unless shared backend configured.
- Host methods lacking their own method security rely solely on our grants — documented
  recommendation: annotate tool-exposed methods with `@PreAuthorize`; the scanner warns when it is absent on `readOnly=false` actions.
