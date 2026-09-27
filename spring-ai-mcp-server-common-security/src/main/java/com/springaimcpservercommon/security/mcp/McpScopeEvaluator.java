package com.springaimcpservercommon.security.mcp;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.authz.AuthorizationEngine;
import com.springaimcpservercommon.security.authz.AuthorizationOutcome;
import com.springaimcpservercommon.security.authz.AuthorizationRequest;
import com.springaimcpservercommon.security.authz.DenyReason;
import com.springaimcpservercommon.security.permission.McpScope;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.security.principal.ServiceAccountAuthentication;
import com.springaimcpservercommon.security.web.WwwAuthenticateHeaders;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Per-call MCP authorization (LLD-07 §5.4, SEC-02 E7): effective permission = token scopes ∩ the caller's grants
 * (SEC-01 §7) ∩ the tool's requirement. Evaluated on <em>every</em> tool call (the {@code SecuredToolCallback} calls
 * it), never cached across calls or sessions, so scopes cannot accumulate server-side.
 *
 * <ul>
 *   <li>OAuth tokens: the tool kind's scope ({@code dai.mcp.read|propose|agents}) must be in the token; otherwise
 *       {@link McpScopeDecision.InsufficientScope} with a ready {@code 403 insufficient_scope} challenge listing the
 *       token's relevant MCP scopes plus the missing one (the spec's "recommended approach").</li>
 *   <li>API keys: the scope's API-key permission ({@code mcp:read|propose|agents}) must be a key scope; there is no
 *       step-up, so a missing one is {@link McpScopeDecision.Denied} ({@code SCOPE_NOT_GRANTED}).</li>
 *   <li>Then every permission of the tool kind is decided by the {@link AuthorizationEngine} (kill switch, status,
 *       grants, ABAC, classification).</li>
 * </ul>
 */
public final class McpScopeEvaluator {

    private final AuthorizationEngine engine;
    private final @Nullable String resourceMetadataUrl;

    /**
     * Creates the evaluator.
     *
     * @param engine              authorization engine
     * @param resourceMetadataUrl RFC 9728 metadata URL to include in challenges, or {@code null}
     */
    public McpScopeEvaluator(AuthorizationEngine engine, @Nullable String resourceMetadataUrl) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (resourceMetadataUrl != null) {
            WwwAuthenticateHeaders.bearer().resourceMetadata(resourceMetadataUrl);
        }
        this.resourceMetadataUrl = resourceMetadataUrl;
    }

    /**
     * Whether the caller's credential carries a scope (used to filter {@code tools/list}; listing needs
     * {@code dai.mcp.read}).
     *
     * @param principal caller
     * @param scope     scope
     * @return {@code true} if present
     */
    public boolean hasScope(DaiPrincipal principal, McpScope scope) {
        return isApiKey(principal)
                ? principal.scopes().contains(scope.apiKeyPermission().value())
                : principal.scopes().contains(scope.value());
    }

    /**
     * Evaluates one tool call.
     *
     * @param principal   caller
     * @param requirement the tool's requirement
     * @return the decision
     */
    public McpScopeDecision evaluate(DaiPrincipal principal, McpToolRequirement requirement) {
        McpScope scope = requirement.kind().scope();
        Permission first = requirement.kind().permissions().getFirst();
        if (!hasScope(principal, scope)) {
            AuthorizationRequest request = AuthorizationRequest.onResource(principal, first, requirement.resource())
                    .withToolName(requirement.toolName());
            AuthorizationOutcome.Deny deny = engine.recordDenial(request, DenyReason.SCOPE_NOT_GRANTED);
            if (isApiKey(principal)) {
                return new McpScopeDecision.Denied(deny);
            }
            return insufficientScope(principal, scope);
        }
        List<AuthorizationOutcome.Permit> permits = new ArrayList<>();
        for (Permission permission : requirement.kind().permissions()) {
            AuthorizationOutcome outcome = engine.decide(
                    AuthorizationRequest.onResource(principal, permission, requirement.resource())
                            .withToolName(requirement.toolName()));
            switch (outcome) {
                case AuthorizationOutcome.Permit permit -> permits.add(permit);
                case AuthorizationOutcome.Deny deny -> {
                    return new McpScopeDecision.Denied(deny);
                }
            }
        }
        return new McpScopeDecision.Allowed(permits);
    }

    private McpScopeDecision insufficientScope(DaiPrincipal principal, McpScope missing) {
        Set<String> required = new LinkedHashSet<>();
        for (McpScope scope : McpScope.values()) {
            if (principal.scopes().contains(scope.value())) {
                required.add(scope.value());
            }
        }
        required.add(missing.value());
        WwwAuthenticateHeaders challenge = WwwAuthenticateHeaders.bearer()
                .error(WwwAuthenticateHeaders.BearerError.INSUFFICIENT_SCOPE)
                .scopes(required);
        if (resourceMetadataUrl != null) {
            challenge.resourceMetadata(resourceMetadataUrl);
        }
        challenge.errorDescription("Additional scope " + missing.value() + " required");
        return new McpScopeDecision.InsufficientScope(List.copyOf(required), challenge.build());
    }

    private static boolean isApiKey(DaiPrincipal principal) {
        return principal.type() == SubjectType.SERVICE_ACCOUNT
                && ServiceAccountAuthentication.ISSUER.equals(principal.issuer());
    }
}
