package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A user's consent for an MCP client ({@code dai_mcp_client_consent} + {@code dai_mcp_client_consent_scope}).
 * At most one active (not revoked) consent exists per client and principal; changing scopes revokes the current
 * consent and records a new one, so the history stays auditable.
 */
@Entity
@Table(name = "dai_mcp_client_consent")
public class McpClientConsent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "mcp_client_id", nullable = false, updatable = false)
    private UUID mcpClientId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dai_mcp_client_consent_scope", joinColumns = @JoinColumn(name = "consent_id"))
    @Column(name = "scope", nullable = false)
    @Convert(converter = McpConsentScope.DbConverter.class)
    private Set<McpConsentScope> scopes = new HashSet<>();

    /** For JPA only. */
    protected McpClientConsent() {
    }

    private McpClientConsent(UUID mcpClientId, UUID principalId, Set<McpConsentScope> scopes, Instant now) {
        this.id = Ids.newId();
        this.mcpClientId = mcpClientId;
        this.principalId = principalId;
        this.scopes = new HashSet<>(scopes);
        this.grantedAt = now;
    }

    /**
     * Records a consent.
     *
     * @param client      the client; must be APPROVED
     * @param principalId consenting principal
     * @param scopes      consented scopes, not empty
     * @param now         grant time
     * @return the new consent (not yet persisted)
     */
    public static McpClientConsent grant(McpClient client, UUID principalId, Set<McpConsentScope> scopes,
                                         Instant now) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(now, "now");
        if (client.getStatus() != McpClientStatus.APPROVED) {
            throw new IllegalStateException("consent requires an APPROVED MCP client (is " + client.getStatus() + ")");
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("a consent needs at least one scope");
        }
        return new McpClientConsent(client.getId(), principalId, EnumSet.copyOf(scopes), now);
    }

    /**
     * Revokes the consent. Idempotent.
     *
     * @param now revocation time (not before the grant)
     */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now.isBefore(grantedAt) ? grantedAt : now;
        }
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public McpConsentView view() {
        return new McpConsentView(id, mcpClientId, principalId,
                scopes.isEmpty() ? Set.of() : EnumSet.copyOf(scopes), grantedAt, revokedAt);
    }

    public UUID getId() {
        return id;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof McpClientConsent other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
