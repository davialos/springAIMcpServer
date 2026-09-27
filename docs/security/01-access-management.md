# SEC-01: Access Management (integration with existing team IAM)

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | access-management-architect |
| Module(s) | `security`, `webmvc`, `autoconfigure` |
| Related features | F-60 … F-69 |
| Related ADRs | ADR-0005, ADR-0008, ADR-0009 |

## 1. Principles
1. **Identity is the host's.** We consume `Authentication` from Spring Security 7; we
   never store passwords, never run a login page, never mint user sessions.
2. **Teams already exist in the IdP.** Workspace membership and roles are expressed in
   terms of the host's groups/claims/authorities, mapped — not duplicated.
3. **Default deny** on every plane; explicit grants only.
4. **Runs-as-caller.** No capability reachable through an agent/endpoint that the caller
   could not reach directly (host method security stays authoritative).
5. **Segregation of duties** for anything reaching production data.
6. **Everything audited**, including denials.

## 2. Supported host identity setups
| Host setup | How we get the principal | Groups/roles source |
|------------|--------------------------|---------------------|
| OIDC login (Entra ID, Okta, Keycloak, Auth0, Ping) — `oauth2Login` | `OidcUser` | `groups`/`roles` claim, `wids`, custom claims |
| Resource server (JWT bearer) — SPA/BFF or service-to-service | `JwtAuthenticationToken` | `scope`/`scp`, `roles`, `groups` claims |
| Opaque token introspection | `BearerTokenAuthentication` | introspection attributes |
| LDAP / Active Directory (form/basic/Kerberos) | `LdapUserDetails` | memberOf DNs → authorities |
| SAML 2.0 | `Saml2Authentication` | assertion attributes |
| Custom (`UserDetailsService`, header-based via gateway) | any `Authentication` | `GrantedAuthority` set |
| Our API keys (service accounts) | `DaiApiKeyAuthentication` (our filter) | scopes on key |

Entra ID note: when a user has > 200 groups the token carries an overage claim —
`GroupResolver` SPI can call Microsoft Graph (host-configured) or use app roles instead;
recommended: **App Roles** assigned to groups in Entra, emitted as `roles` claim.

## 3. Principal model
```java
// design sketch
public record DaiPrincipal(String subjectId,              // stable IdP subject (sub / objectId / DN), never email
        SubjectType type,                                  // USER | GROUP | SERVICE_ACCOUNT | MCP_CLIENT (GROUP only as a membership/grant subject, never as a caller)
        String displayName, String issuer,
        Set<String> externalGroups,                        // raw, normalized (issuer-qualified)
        Set<FrameworkRole> globalRoles,                    // after mapping
        Map<WorkspaceId, Set<FrameworkRole>> workspaceRoles,
        Map<String, Object> attributes,                    // ABAC: tenantId, region, department, clearance...
        Classification clearance,
        Instant authenticatedAt, Optional<String> actingFor /* delegation, v2 */) {}

public interface AuthorityMapper {                         // SPI — host may replace
    DaiPrincipal map(Authentication authentication);
}
public interface PrincipalAttributeResolver {              // SPI — e.g. tenantId from DB/HR system
    Map<String, Object> resolve(Authentication auth);
}
```
Default `AuthorityMapper` = rule engine over `dai_role_mapping` + static config:
```yaml
dynamic:
  ai:
    agent:
      security:
        identity:
          subject-claim: sub            # or oid for Entra
          groups-claim: groups          # or roles
          clearance-claim: data_clearance
          attribute-claims: { tenantId: tid, region: region }
        role-mappings:                  # bootstrap mappings (DB mappings added via UI)
          - match: { authority: "ROLE_PLATFORM_ADMIN" }         → role: PLATFORM_ADMIN
          - match: { group: "sg-sales-engineering" }            → { role: WORKSPACE_OWNER, workspace: sales }
          - match: { group: "sg-sales-analysts" }               → { role: AUTHOR, workspace: sales }
          - match: { ldapGroup: "cn=data-owners,ou=groups,dc=acme,dc=com" } → role: APPROVER
          - match: { scope: "dai.agents.invoke" }               → role: CONSUMER
```
Mapping result cached per (issuer, subject, token hash / session id) for its lifetime; cache TTL ≤ 5 min.

## 4. Role catalog (framework roles)
| Role | Scope | Intent |
|------|-------|--------|
| `PLATFORM_ADMIN` | global | Enable features, models, price tables, global mappings, bundles, global kill switch |
| `SECURITY_ADMIN` | global | Role mappings, service-account policy, audit config (separate from platform admin) |
| `AUDITOR` | global/workspace | Read-only audit, access reviews |
| `WORKSPACE_OWNER` | workspace | Members, grants, budgets, workspace kill switch |
| `AUTHOR` | workspace | Create/edit drafts, preview, playground, submit |
| `APPROVER` | workspace (or global data-owner) | Approve/reject revisions; publish (if policy allows) |
| `OPERATOR` | workspace/global | View usage, set kill switches, no config edits |
| `CONSUMER` | resource-level (via grants) | Invoke endpoints/agents/tools |

## 5. Permission matrix (default role → permission bundles)
| Permission | P_ADMIN | SEC_ADMIN | AUDITOR | WS_OWNER | AUTHOR | APPROVER | OPERATOR | CONSUMER |
|------------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `catalog:read` | ✔ | | | ✔ | ✔ | ✔ | | |
| `catalog:annotate` (overlays) | ✔ | | | ✔ | | | | |
| `catalog:declassify` | ✔* | | | | | | | |
| `workspace:admin` | ✔ | | | ✔ | | | | |
| `endpoint/query/agent/tool:author` | | | | ✔ | ✔ | | | |
| `query:preview`, `agent:playground` | | | | ✔ | ✔ | ✔ | | |
| `query:explain` | ✔ | | | ✔ | ✔ | | | |
| `review:approve` | | | | | | ✔ | | |
| `*:publish` | ✔ | | | ✔** | | ✔ | | |
| `grant:manage` | ✔ | | | ✔ | | | | |
| `rolemapping:manage` | | ✔ | | | | | | |
| `serviceaccount:manage` | | ✔ | | ✔ | | | | |
| `budget:manage` | ✔ | | | ✔ | | | | |
| `ops:killswitch` | ✔ | | | ✔ | | | ✔ | |
| `data:write-propose` (agent/endpoint may create proposals for the caller) | via grant | | | via grant | via grant | | | via grant |
| `data:write-confirm` (confirm own proposal) | via grant | | | via grant | via grant | | | via grant |
| `data:write-approve` (second-person approval) | | | | | | ✔ | | |
| `audit:read` | | ✔ | ✔ | own ws | | | | |
| `endpoint/agent/tool:invoke` | via grant | | | via grant | via grant (playground: draft only) | | | via grant |

\* requires approval by a second `PLATFORM_ADMIN`/`SECURITY_ADMIN`. ** only when publish policy says approval not required.
Bundles are data (overridable), permissions are code constants.

## 6. Publish/approval policy
| Change characteristic | Approval |
|-----------------------|----------|
| Touches RESTRICTED/CONFIDENTIAL entities or ops | 1 approver with clearance ≥ classification (four-eyes) |
| Adds mutating tool / write endpoint (config publish) | 2 approvers, one `APPROVER` from data-owner group |
| **Runtime data write** (each proposal, LLD-11 §9) | Always explicit confirm by the proposal owner; + approver when RESTRICTED entity, DELETE, or BULK > 10 records |
| Changes row policy, lowers limits, model/provider change to external | 1 approver |
| Only description/prompt text changes on PUBLIC/INTERNAL resources | optional (workspace setting) |
| Any change in `prod` environment when `environment.require-approval=true` | ≥ 1 approver |
Enforced server-side: approver ≠ author, approver ≠ last editor, approval invalidated by any edit.

## 7. Invocation authorization (data, agent, MCP planes)
```
decide(principal, action, resource, context):
  1. kill switch?                                  → DENY(disabled)
  2. resource published & not suspended?           → else DENY
  3. grants = grantsFor(resource) ∪ grantsFor(resource pattern) ∪ workspace defaults
     match subject: user subjectId | any externalGroup | service account id
     permission includes action; not expired
  4. ABAC conditions on the matching grant (e.g. principal.region in ['EU'], env == 'prod', time window)
  5. classification: resource.maxClassification ≤ principal.clearance  (else DENY or MASK per policy)
  6. rate/budget (separate stage, not authz)
  → PERMIT(grantId) | DENY(reason)
Every decision → audit (decision, grantId/reason).
```
Tool calls re-run `decide(..., tool:invoke, binding)` **per call**, then the host method
security runs on the proxy. Query/row policies further constrain data.

Implemented on Spring Security 7 `AuthorizationManager<InvocationTarget>`; for the admin API
via `@PreAuthorize("@daiAuthz.can(authentication, 'endpoint:author', #ws)")`-style
method security inside our controllers (bean name namespaced).

## 8. Filter chain integration
```java
// design sketch
@Bean @Order(Ordered.HIGHEST_PRECEDENCE + 50) @ConditionalOnMissingBean(name = "dynamicAiSecurityFilterChain")
SecurityFilterChain dynamicAiSecurityFilterChain(HttpSecurity http) // securityMatcher("/dynamic-ai/**")
```
- Default: **reuse the host's authentication mechanisms** — `oauth2ResourceServer` if a
  `JwtDecoder` bean exists, `oauth2Login` session if `ClientRegistrationRepository`
  exists, else form/basic as configured by host; plus our `ApiKeyAuthenticationFilter`.
- Alternative mode `security.chain=host`: we contribute **no** chain; host secures
  `/dynamic-ai/**` itself and we only do resource-level authz (for hosts with strict
  central security config).
- Admin UI: CSRF on (cookie session), strict CSP, `SameSite=Lax`, frame-ancestors none.
- Refuse to start admin/data plane if no security is configured (unless
  `dynamic.ai.agent.security.allow-insecure=true`, dev profile only, loud warning).

## 9. Service accounts & API keys (F-65)
- Owned by a workspace; key format `dai_<env>_<keyId>_<secret>`; stored as prefix +
  argon2id hash; shown once. Scopes = permissions subset; mandatory expiry (≤ 1 y);
  optional IP allow-list; last-used tracking; revocation immediate (cache TTL ≤ 30 s).
- Preferred for service-to-service: host's OAuth2 client-credentials tokens mapped via
  `scope` claims; API keys are the fallback for systems without OAuth.

## 10. Access reviews & lifecycle (F-67)
- Because membership is group-based, joiner/mover/leaver is handled by the IdP automatically.
- Direct user grants flagged in reviews; grants support expiry; quarterly review export
  (who can invoke/author/approve what, last used).
- Deleted IdP groups: mappings referencing unseen groups for N days flagged.

## 11. Multi-tenancy
If the host is multi-tenant, `tenantId` is a principal attribute; row policies and
conversation/memory keys include it; workspaces may be tenant-scoped
(`workspace.tenant`). Cross-tenant admin requires `PLATFORM_ADMIN`.

## 12. Test strategy
Authorization matrix tests generated from §5 (every permission × role × plane); IdP
fixture tokens for Entra/Okta/Keycloak/LDAP shapes; SoD tests; runs-as-caller tests; key rotation tests.
