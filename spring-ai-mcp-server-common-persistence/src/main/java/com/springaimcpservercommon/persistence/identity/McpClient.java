package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An MCP client application approved (or pending approval) for a workspace ({@code dai_mcp_client}, LLD-07 §5.3).
 * Lifecycle: PENDING → APPROVED → REVOKED, or PENDING → REVOKED.
 */
@Entity
@Table(name = "dai_mcp_client")
public class McpClient {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "issuer", nullable = false, updatable = false)
    private String issuer;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_type", nullable = false, updatable = false)
    private McpRegistrationType registrationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private McpClientStatus status;

    @Column(name = "approved_by")
    private @Nullable UUID approvedBy;

    @Column(name = "approved_at")
    private @Nullable Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private @Nullable Long rowVersion;

    /** For JPA only. */
    protected McpClient() {
    }

    private McpClient(UUID workspaceId, String issuer, String clientId, String displayName,
                      McpRegistrationType registrationType, UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.workspaceId = workspaceId;
        this.issuer = issuer;
        this.clientId = clientId;
        this.displayName = displayName;
        this.registrationType = registrationType;
        this.status = McpClientStatus.PENDING;
        this.createdAt = now;
        this.createdBy = createdBy;
        this.updatedAt = now;
    }

    /**
     * Registers a client in PENDING state.
     *
     * @param workspaceId      workspace the client is registered for
     * @param issuer           authorization server issuer that knows the client
     * @param clientId         OAuth client id (or CIMD URL)
     * @param displayName      name shown on consent screens
     * @param registrationType how the client was registered
     * @param createdBy        registering principal
     * @param now              registration time
     * @return the new client (not yet persisted)
     */
    public static McpClient register(UUID workspaceId, String issuer, String clientId, String displayName,
                                     McpRegistrationType registrationType, UUID createdBy, Instant now) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(registrationType, "registrationType");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(now, "now");
        return new McpClient(workspaceId, requireText(issuer, "issuer"), requireText(clientId, "clientId"),
                requireText(displayName, "displayName"), registrationType, createdBy, now);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(name + " must have 1..512 characters");
        }
        return value;
    }

    /** @return the principal that registered the client */
    public UUID createdBy() {
        return createdBy;
    }

    /**
     * Approves a pending client.
     *
     * @param by  approving principal
     * @param now approval time
     * @throws IllegalStateException unless PENDING
     */
    public void approve(UUID by, Instant now) {
        if (status != McpClientStatus.PENDING) {
            throw new IllegalStateException("only a PENDING MCP client can be approved (is " + status + ")");
        }
        this.status = McpClientStatus.APPROVED;
        this.approvedBy = Objects.requireNonNull(by, "by");
        this.approvedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Revokes the client. Idempotent.
     *
     * @param now revocation time
     * @return {@code true} if this call changed the status
     */
    public boolean revoke(Instant now) {
        if (status == McpClientStatus.REVOKED) {
            return false;
        }
        this.status = McpClientStatus.REVOKED;
        this.updatedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public McpClientView view() {
        return new McpClientView(id, workspaceId, issuer, clientId, displayName, registrationType, status, approvedBy,
                approvedAt, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public McpClientStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof McpClient other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
