package com.springaimcpservercommon.security.authz;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.FrameworkRole;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome.Deny;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome.Permit;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome.Via;
import com.springaimcpservercommon.security.authz.condition.ConditionContext;
import com.springaimcpservercommon.security.authz.condition.GrantConditionEvaluator;
import com.springaimcpservercommon.security.authz.condition.ParsingGrantConditionEvaluator;
import com.springaimcpservercommon.security.internal.GlobPattern;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.security.permission.RolePermissionBundles;
import com.springaimcpservercommon.security.port.GrantSource;
import com.springaimcpservercommon.security.port.GrantSource.GrantRecord;
import com.springaimcpservercommon.security.port.KillSwitchView;
import com.springaimcpservercommon.security.port.ResourceStatusView;
import com.springaimcpservercommon.security.principal.GroupPrincipalIds;
import com.springaimcpservercommon.security.principal.ServiceAccountAuthentication;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The framework's authorization decision point, implementing SEC-01 §7 exactly:
 * <ol>
 *   <li>kill switch (data-plane permissions) → {@code DENY(KILL_SWITCH)};</li>
 *   <li>resource published and not suspended (data-plane permissions on a resource);</li>
 *   <li>role bundles (control-plane permissions: global roles, plus workspace roles of the request's workspace), then
 *       grants matching the user, any of its group principals or the service account, with the permission, not
 *       expired, on the resource / a matching pattern / the whole workspace;</li>
 *   <li>ABAC conditions of the matching grant (a grant whose conditions are invalid never matches);</li>
 *   <li>classification: resource classification ≤ principal clearance, else DENY or MASK per policy.</li>
 * </ol>
 * API-key service accounts are additionally limited to their key scopes. Every decision is reported to the
 * {@link AuthorizationAuditListener}s; any internal failure yields {@code DENY(INTERNAL_ERROR)} (fail closed).
 */
public final class AuthorizationEngine {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationEngine.class);

    private final GrantSource grants;
    private final KillSwitchView killSwitches;
    private final ResourceStatusView resourceStatus;
    private final GroupPrincipalIds groupPrincipalIds;
    private final RolePermissionBundles bundles;
    private final GrantConditionEvaluator conditions;
    private final Map<String, Object> environment;
    private final ClassificationPolicy classificationPolicy;
    private final List<AuthorizationAuditListener> listeners;
    private final Clock clock;

    private AuthorizationEngine(Builder b) {
        this.grants = b.grants;
        this.killSwitches = b.killSwitches;
        this.resourceStatus = b.resourceStatus;
        this.groupPrincipalIds = b.groupPrincipalIds;
        this.bundles = b.bundles;
        this.conditions = b.conditions != null ? b.conditions : new ParsingGrantConditionEvaluator(b.clock);
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(b.environment));
        this.classificationPolicy = b.classificationPolicy;
        this.listeners = List.copyOf(b.listeners);
        this.clock = b.clock;
    }

    /**
     * Starts a builder.
     *
     * @param grants            grant port
     * @param killSwitches      kill switch port
     * @param resourceStatus    resource status port
     * @param groupPrincipalIds group principal resolver (share the mapper's instance)
     * @return a builder
     */
    public static Builder builder(GrantSource grants, KillSwitchView killSwitches, ResourceStatusView resourceStatus,
                                  GroupPrincipalIds groupPrincipalIds) {
        return new Builder(grants, killSwitches, resourceStatus, groupPrincipalIds);
    }

    /**
     * Decides and audits.
     *
     * @param request the request
     * @return the outcome
     */
    public AuthorizationOutcome decide(AuthorizationRequest request) {
        Instant now = clock.instant();
        AuthorizationOutcome outcome;
        try {
            outcome = evaluate(request, now);
        } catch (RuntimeException e) {
            log.error("authorization of {} for principal {} failed: denied ({})", request.permission(),
                    request.principal().principalId(), e.getClass().getSimpleName(), e);
            outcome = Deny.of(request.permission(), DenyReason.INTERNAL_ERROR);
        }
        publish(new AuthorizationDecisionEvent(now, request.principal().principalId(), request.principal().type(),
                request.permission().value(), request.workspaceId(),
                request.resource() == null ? null : request.resource().resourceId(), request.toolName(), outcome));
        return outcome;
    }

    /**
     * Records a denial for a caller that could not be authenticated or mapped (no principal available).
     *
     * @param permission  requested permission
     * @param workspaceId workspace, if known
     * @param resourceId  resource, if known
     * @return the denial
     */
    public Deny denyUnauthenticated(Permission permission, @Nullable UUID workspaceId, @Nullable UUID resourceId) {
        Deny deny = Deny.of(permission, DenyReason.NOT_AUTHENTICATED);
        publish(new AuthorizationDecisionEvent(clock.instant(), null, null, permission.value(), workspaceId, resourceId,
                null, deny));
        return deny;
    }

    private AuthorizationOutcome evaluate(AuthorizationRequest request, Instant now) {
        DaiPrincipal principal = request.principal();
        Permission permission = request.permission();
        ResourceRef resource = request.resource();
        UUID workspaceId = request.workspaceId();

        if (permission.kind() == Permission.Kind.KEY_SCOPE) {
            // mcp:* only restrict API keys (McpScopeEvaluator); they are never granted by the engine.
            return Deny.of(permission, DenyReason.INVALID_REQUEST);
        }
        if (isApiKeyPrincipal(principal) && !principal.scopes().contains(permission.value())) {
            return Deny.of(permission, DenyReason.SCOPE_NOT_GRANTED);
        }

        // 1. kill switch, 2. resource status — data plane only
        if (permission.dataPlane()) {
            Optional<KillSwitchView.ActiveKillSwitch> killSwitch = killSwitches.findActive(workspaceId,
                    resource == null ? null : resource.resourceId(), request.toolName(), now);
            if (killSwitch.isPresent()) {
                return new Deny(permission, DenyReason.KILL_SWITCH, killSwitch.get().id());
            }
            if (resource != null) {
                Deny statusDenial = checkStatus(permission, resource, request.draftAccess());
                if (statusDenial != null) {
                    return statusDenial;
                }
            }
        }

        // 3. role bundles, then grants (with 4. ABAC)
        AuthorizationOutcome access = viaRole(principal, permission, workspaceId);
        if (access == null) {
            if (workspaceId == null) {
                return Deny.of(permission, DenyReason.NO_MATCHING_GRANT);
            }
            access = viaGrant(request, principal, permission, workspaceId, resource, now);
        }
        if (!(access instanceof Permit permit)) {
            return access;
        }

        // 5. classification
        if (resource != null && !principal.isCleared(resource.classification())) {
            return classificationPolicy == ClassificationPolicy.MASK
                    ? permit.withMasking()
                    : Deny.of(permission, DenyReason.CLASSIFICATION);
        }
        return permit;
    }

    private @Nullable Deny checkStatus(Permission permission, ResourceRef resource, boolean draftAccess) {
        return switch (resourceStatus.statusOf(resource.resourceId())) {
            case PUBLISHED -> null;
            case NOT_PUBLISHED -> draftAccess ? null : Deny.of(permission, DenyReason.RESOURCE_NOT_PUBLISHED);
            case SUSPENDED, RETIRED -> Deny.of(permission, DenyReason.RESOURCE_SUSPENDED);
            case UNKNOWN -> Deny.of(permission, DenyReason.RESOURCE_NOT_PUBLISHED);
        };
    }

    private @Nullable Permit viaRole(DaiPrincipal principal, Permission permission, @Nullable UUID workspaceId) {
        if (permission.kind() != Permission.Kind.ROLE) {
            return null;
        }
        for (FrameworkRole role : FrameworkRole.values()) {
            if (bundles.grants(role, permission) && principal.hasRole(role, workspaceId)) {
                return new Permit(permission, Via.ROLE, null, role, false);
            }
        }
        return null;
    }

    private AuthorizationOutcome viaGrant(AuthorizationRequest request, DaiPrincipal principal, Permission permission,
                                          UUID workspaceId, @Nullable ResourceRef resource, Instant now) {
        Set<UUID> subjects = new HashSet<>(groupPrincipalIds.resolve(principal.issuer(), principal.externalGroups()));
        subjects.add(principal.principalId());

        List<GrantRecord> candidates = new ArrayList<>();
        for (GrantRecord grant : grants.findGrants(workspaceId, subjects, permission.value())) {
            if (grant.workspaceId().equals(workspaceId)
                    && subjects.contains(grant.principalId())
                    && permission.value().equals(grant.permission())
                    && (grant.expiresAt() == null || grant.expiresAt().isAfter(now))
                    && targets(grant, resource)) {
                candidates.add(grant);
            }
        }
        if (candidates.isEmpty()) {
            return Deny.of(permission, DenyReason.NO_MATCHING_GRANT);
        }
        candidates.sort(Comparator.comparingInt(AuthorizationEngine::specificity).thenComparing(GrantRecord::id));

        ConditionContext context = null;
        DenyReason failure = DenyReason.CONDITION_NOT_MET;
        UUID failedGrant = null;
        for (GrantRecord grant : candidates) {
            String json = grant.conditionsJson();
            if (json == null || json.isBlank()) {
                return new Permit(permission, Via.GRANT, grant.id(), null, false);
            }
            if (context == null) {
                context = new ConditionContext(principal, environment, request.requestAttributes(), resource, now);
            }
            switch (conditions.evaluate(grant.id(), json, context)) {
                case SATISFIED -> {
                    return new Permit(permission, Via.GRANT, grant.id(), null, false);
                }
                case INVALID -> {
                    log.warn("grant {} has invalid conditions and is ignored (fail closed)", grant.id());
                    if (failedGrant == null) {
                        failure = DenyReason.INVALID_CONDITION;
                        failedGrant = grant.id();
                    }
                }
                case NOT_SATISFIED -> {
                    if (failedGrant == null) {
                        failedGrant = grant.id();
                    }
                }
            }
        }
        return new Deny(permission, failure, failedGrant);
    }

    private static boolean targets(GrantRecord grant, @Nullable ResourceRef resource) {
        if (grant.resourceId() != null) {
            return resource != null && grant.resourceId().equals(resource.resourceId());
        }
        if (grant.resourcePattern() != null) {
            if (resource == null) {
                return false;
            }
            try {
                return GlobPattern.compile(grant.resourcePattern(), true).matches(resource.patternKey());
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true; // workspace-wide grant
    }

    private static int specificity(GrantRecord grant) {
        if (grant.resourceId() != null) {
            return 0;
        }
        return grant.resourcePattern() != null ? 1 : 2;
    }

    private static boolean isApiKeyPrincipal(DaiPrincipal principal) {
        return principal.type() == SubjectType.SERVICE_ACCOUNT
                && ServiceAccountAuthentication.ISSUER.equals(principal.issuer());
    }

    private void publish(AuthorizationDecisionEvent event) {
        for (AuthorizationAuditListener listener : listeners) {
            try {
                listener.onDecision(event);
            } catch (RuntimeException e) {
                log.error("authorization audit listener {} failed ({})", listener.getClass().getName(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Builder of {@link AuthorizationEngine}.
     */
    public static final class Builder {
        private final GrantSource grants;
        private final KillSwitchView killSwitches;
        private final ResourceStatusView resourceStatus;
        private final GroupPrincipalIds groupPrincipalIds;
        private RolePermissionBundles bundles = RolePermissionBundles.defaults();
        private @Nullable GrantConditionEvaluator conditions;
        private Map<String, Object> environment = Map.of();
        private ClassificationPolicy classificationPolicy = ClassificationPolicy.DENY;
        private List<AuthorizationAuditListener> listeners = List.of();
        private Clock clock = Clock.systemUTC();

        private Builder(GrantSource grants, KillSwitchView killSwitches, ResourceStatusView resourceStatus,
                        GroupPrincipalIds groupPrincipalIds) {
            this.grants = Objects.requireNonNull(grants, "grants");
            this.killSwitches = Objects.requireNonNull(killSwitches, "killSwitches");
            this.resourceStatus = Objects.requireNonNull(resourceStatus, "resourceStatus");
            this.groupPrincipalIds = Objects.requireNonNull(groupPrincipalIds, "groupPrincipalIds");
        }

        /**
         * Sets the role bundles (default: SEC-01 §5).
         *
         * @param bundles bundles
         * @return this builder
         */
        public Builder bundles(RolePermissionBundles bundles) {
            this.bundles = Objects.requireNonNull(bundles, "bundles");
            return this;
        }

        /**
         * Sets the ABAC condition evaluator (default: {@link ParsingGrantConditionEvaluator}).
         *
         * @param evaluator evaluator
         * @return this builder
         */
        public Builder conditionEvaluator(GrantConditionEvaluator evaluator) {
            this.conditions = Objects.requireNonNull(evaluator, "evaluator");
            return this;
        }

        /**
         * Sets the environment facts ({@code environment.*} in conditions), e.g. {@code tier=PROD}.
         *
         * @param environment facts
         * @return this builder
         */
        public Builder environment(Map<String, Object> environment) {
            this.environment = Map.copyOf(environment);
            return this;
        }

        /**
         * Sets the classification policy (default DENY).
         *
         * @param policy policy
         * @return this builder
         */
        public Builder classificationPolicy(ClassificationPolicy policy) {
            this.classificationPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the audit listeners.
         *
         * @param listeners listeners
         * @return this builder
         */
        public Builder auditListeners(List<AuthorizationAuditListener> listeners) {
            this.listeners = List.copyOf(listeners);
            return this;
        }

        /**
         * Sets the clock.
         *
         * @param clock clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the engine.
         *
         * @return the engine
         */
        public AuthorizationEngine build() {
            return new AuthorizationEngine(this);
        }
    }
}
