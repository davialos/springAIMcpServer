package com.springaimcpservercommon.persistence.audit;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evidence to store for an audit event (evidence mode, ADR-0018).
 *
 * @param auditEventId   the audit event it belongs to
 * @param occurredAt     the audit event's time (selects the partition)
 * @param subjectKeyId   data subject whose key encrypts it (created on first use)
 * @param principalId    principal the subject corresponds to, if any (recorded when the key is created)
 * @param contentType    kind of content
 * @param content        plaintext content (not copied; wiped by the caller if needed)
 * @param retentionUntil end of retention (default 400 days, LLD-10 §4.1)
 */
public record NewEvidence(
        UUID auditEventId,
        Instant occurredAt,
        String subjectKeyId,
        @Nullable UUID principalId,
        EvidenceContentType contentType,
        byte[] content,
        Instant retentionUntil) {

    /**
     * Validates required components.
     */
    public NewEvidence {
        Objects.requireNonNull(auditEventId, "auditEventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(subjectKeyId, "subjectKeyId");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(retentionUntil, "retentionUntil");
    }
}
