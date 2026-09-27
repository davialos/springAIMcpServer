package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Encrypted full-content evidence for an audit event ({@code dai_audit_evidence}, partitioned monthly by
 * {@code occurred_at}, append-only by trigger). Readable only while the subject's key exists.
 */
@Entity
@Immutable
@Table(name = "dai_audit_evidence")
public class AuditEvidence {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "audit_event_id", nullable = false, updatable = false)
    private UUID auditEventId;

    @Column(name = "subject_key_id", nullable = false, updatable = false)
    private String subjectKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, updatable = false)
    private EvidenceContentType contentType;

    @Column(name = "nonce", nullable = false, updatable = false)
    private byte[] nonce;

    @Column(name = "ciphertext", nullable = false, updatable = false)
    private byte[] ciphertext;

    @Column(name = "retention_until", nullable = false, updatable = false)
    private Instant retentionUntil;

    /** For JPA only. */
    protected AuditEvidence() {
    }

    static AuditEvidence of(UUID id, Instant occurredAt, UUID auditEventId, String subjectKeyId,
                            EvidenceContentType contentType, SealedEvidence sealed, Instant retentionUntil) {
        AuditEvidence e = new AuditEvidence();
        e.id = Checks.required(id, "id");
        e.occurredAt = UtcTimes.micros(occurredAt);
        e.auditEventId = Checks.required(auditEventId, "auditEventId");
        e.subjectKeyId = Checks.required(subjectKeyId, "subjectKeyId");
        e.contentType = Checks.required(contentType, "contentType");
        e.nonce = sealed.nonce().clone();
        e.ciphertext = sealed.ciphertext().clone();
        e.retentionUntil = UtcTimes.micros(retentionUntil);
        if (!e.retentionUntil.isAfter(e.occurredAt)) {
            throw new IllegalArgumentException("retentionUntil must be after occurredAt");
        }
        return e;
    }

    /**
     * Associated data binding the ciphertext to this row: {@code dai-evidence-v1|<id>|<audit event id>|<subject key
     * id>|<content type>} in UTF-8.
     *
     * @param id           evidence id
     * @param auditEventId audit event id
     * @param subjectKeyId subject key id
     * @param contentType  content type
     * @return associated data bytes
     */
    static byte[] associatedData(UUID id, UUID auditEventId, String subjectKeyId, EvidenceContentType contentType) {
        return ("dai-evidence-v1|" + id + "|" + auditEventId + "|" + subjectKeyId + "|" + contentType.name())
                .getBytes(StandardCharsets.UTF_8);
    }

    byte[] associatedData() {
        return associatedData(id, auditEventId, subjectKeyId, contentType);
    }

    SealedEvidence sealed() {
        return new SealedEvidence(nonce.clone(), ciphertext.clone());
    }

    /** @return evidence id */
    public UUID getId() {
        return id;
    }

    /** @return time (the audit event's time) */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return audit event id */
    public UUID getAuditEventId() {
        return auditEventId;
    }

    /** @return subject key id */
    public String getSubjectKeyId() {
        return subjectKeyId;
    }

    /** @return content type */
    public EvidenceContentType getContentType() {
        return contentType;
    }

    /** @return retention end */
    public Instant getRetentionUntil() {
        return retentionUntil;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof AuditEvidence other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
