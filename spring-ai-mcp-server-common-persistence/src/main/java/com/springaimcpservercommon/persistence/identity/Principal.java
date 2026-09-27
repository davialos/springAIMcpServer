package com.springaimcpservercommon.persistence.identity;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.core.principal.SubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reference to an external subject ({@code dai_principal}): IdP user or group, framework service account or MCP
 * client. Never deleted, only disabled.
 *
 * <p>Rows for IdP subjects are normally created by {@link PrincipalDirectory#resolve} with a single native upsert;
 * this entity is used for reads, status changes and for service-account principals created in Java.
 */
@Entity
@Table(name = "dai_principal")
public class Principal {

    /** Issuer used for principals owned by the framework itself (service accounts). */
    public static final String FRAMEWORK_ISSUER = "dai";

    static final int MAX_ISSUER_LENGTH = 512;
    static final int MAX_EXTERNAL_ID_LENGTH = 512;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false)
    private SubjectType subjectType;

    @Column(name = "issuer", nullable = false, updatable = false)
    private String issuer;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "display_name")
    private @Nullable String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PrincipalStatus status;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** For JPA only. */
    protected Principal() {
    }

    private Principal(SubjectType subjectType, String issuer, String externalId, @Nullable String displayName,
                      Instant now) {
        this.id = Ids.newId();
        this.subjectType = subjectType;
        this.issuer = issuer;
        this.externalId = externalId;
        this.displayName = displayName;
        this.status = PrincipalStatus.ACTIVE;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    /**
     * Creates a new, active principal reference.
     *
     * @param subjectType kind of subject
     * @param issuer      IdP issuer or {@link #FRAMEWORK_ISSUER}
     * @param externalId  stable external subject id (never an e-mail address)
     * @param displayName optional display name
     * @param now         creation time
     * @return the new principal (not yet persisted)
     */
    public static Principal create(SubjectType subjectType, String issuer, String externalId,
                                   @Nullable String displayName, Instant now) {
        Objects.requireNonNull(subjectType, "subjectType");
        requireSubjectKey(issuer, externalId);
        Objects.requireNonNull(now, "now");
        return new Principal(subjectType, issuer, externalId, displayName, now);
    }

    static void requireSubjectKey(String issuer, String externalId) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(externalId, "externalId");
        if (issuer.isEmpty() || issuer.length() > MAX_ISSUER_LENGTH) {
            throw new IllegalArgumentException("issuer must have 1.." + MAX_ISSUER_LENGTH + " characters");
        }
        if (externalId.isEmpty() || externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw new IllegalArgumentException("externalId must have 1.." + MAX_EXTERNAL_ID_LENGTH + " characters");
        }
    }

    /** Blocks the principal. Idempotent. */
    public void disable() {
        this.status = PrincipalStatus.DISABLED;
    }

    /** Re-activates the principal. Idempotent. */
    public void enable() {
        this.status = PrincipalStatus.ACTIVE;
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public PrincipalView view() {
        return new PrincipalView(id, subjectType, issuer, externalId, displayName, status, firstSeenAt, lastSeenAt);
    }

    public UUID getId() {
        return id;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getExternalId() {
        return externalId;
    }

    public @Nullable String getDisplayName() {
        return displayName;
    }

    public PrincipalStatus getStatus() {
        return status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Principal other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
