package com.springaimcpservercommon.security.mcp;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import com.springaimcpservercommon.security.permission.McpScope;
import com.springaimcpservercommon.security.port.McpClientRegistryPort;
import com.springaimcpservercommon.security.principal.ExtractedIdentity;
import com.springaimcpservercommon.security.principal.IdentityClaimSettings;
import com.springaimcpservercommon.security.principal.IdentityExtraction;
import com.springaimcpservercommon.security.principal.ServiceAccountAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Approved-MCP-client gate (LLD-07 §5.3, SEC-02 S2): a token is accepted on the MCP endpoint of a workspace only if
 * its OAuth client ({@code azp}/{@code client_id}/{@code appid}) is {@code APPROVED} for that workspace; otherwise the
 * caller gets 403. A user's first use of an approved client records a consent entry. Tokens without a client id are
 * rejected (fail closed). Our API keys are service accounts owned by a workspace and skip the client gate.
 */
public final class McpClientApproval {

    private static final Logger log = LoggerFactory.getLogger(McpClientApproval.class);

    /** Outcome of the gate. */
    public sealed interface Result permits Approved, ServiceAccount, NotApproved {
    }

    /**
     * The client is approved.
     *
     * @param mcpClientId {@code dai_mcp_client.id}
     * @param clientId    OAuth client id
     */
    public record Approved(UUID mcpClientId, String clientId) implements Result {
    }

    /** API key service account of the workspace (no OAuth client involved). */
    public record ServiceAccount() implements Result {
    }

    /**
     * Rejected: answer 403.
     *
     * @param reason {@code NO_CLIENT_ID}, {@code NOT_APPROVED} or {@code WRONG_WORKSPACE}
     */
    public record NotApproved(String reason) implements Result {
    }

    private final McpClientRegistryPort registry;
    private final IdentityExtraction extraction;
    private final IdentityClaimSettings settings;

    /**
     * Creates the gate.
     *
     * @param registry   client registry port
     * @param extraction identity extraction (to read the client id claim)
     * @param settings   claim settings
     */
    public McpClientApproval(McpClientRegistryPort registry, IdentityExtraction extraction, IdentityClaimSettings settings) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.extraction = Objects.requireNonNull(extraction, "extraction");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Admits (or rejects) a caller on a workspace's MCP endpoint, recording first-use consent for approved clients.
     *
     * @param authentication host authentication of the request
     * @param principal      mapped principal
     * @param workspaceId    workspace served by the endpoint
     * @return the result
     */
    public Result admit(Authentication authentication, DaiPrincipal principal, UUID workspaceId) {
        if (authentication instanceof ServiceAccountAuthentication serviceAccount) {
            return serviceAccount.serviceAccount().workspaceId().equals(workspaceId)
                    ? new ServiceAccount() : new NotApproved("WRONG_WORKSPACE");
        }
        ExtractedIdentity identity = extraction.extract(authentication, settings);
        String clientId = identity.clientId();
        if (clientId == null) {
            return new NotApproved("NO_CLIENT_ID");
        }
        Optional<UUID> client = registry.findApprovedClient(workspaceId, principal.issuer(), clientId);
        if (client.isEmpty()) {
            log.info("MCP client not approved for workspace {} (principal {})", workspaceId, principal.principalId());
            return new NotApproved("NOT_APPROVED");
        }
        UUID mcpClientId = client.get();
        if (principal.type() == SubjectType.USER && !registry.hasActiveConsent(mcpClientId, principal.principalId())) {
            Set<String> scopes = new LinkedHashSet<>();
            for (McpScope scope : McpScope.values()) {
                if (principal.scopes().contains(scope.value())) {
                    scopes.add(scope.value());
                }
            }
            registry.recordConsent(mcpClientId, principal.principalId(), scopes);
        }
        return new Approved(mcpClientId, clientId);
    }
}
