package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a resource revision. {@code specJson} is the stored spec (as returned by {@code jsonb}, i.e. not
 * necessarily in canonical form); use {@link CanonicalSpec#of(String)} to recompute the hash.
 *
 * @param id                revision id
 * @param resourceId        resource
 * @param revisionNo        number within the resource (1, 2, …)
 * @param state             lifecycle state
 * @param specJson          spec JSON object
 * @param specSchemaVersion spec schema version
 * @param specHash          {@code sha256:<hex>} of the canonical spec
 * @param scanFingerprint   catalog fingerprint the revision was authored against
 * @param changeSummary     optional summary
 * @param riskScore         optional risk score 0..100
 * @param basedOnId         revision this one was derived from
 * @param authorId          author
 * @param createdAt         creation time
 * @param submittedAt       submit time
 * @param approvedAt        approval time
 * @param publishedAt       time it last went live
 * @param retiredAt         retire time
 * @param rowVersion        optimistic-lock version, to be passed back on draft updates and submit
 */
public record RevisionView(
        UUID id,
        UUID resourceId,
        int revisionNo,
        RevisionState state,
        String specJson,
        int specSchemaVersion,
        String specHash,
        @Nullable String scanFingerprint,
        @Nullable String changeSummary,
        @Nullable Integer riskScore,
        @Nullable UUID basedOnId,
        UUID authorId,
        Instant createdAt,
        @Nullable Instant submittedAt,
        @Nullable Instant approvedAt,
        @Nullable Instant publishedAt,
        @Nullable Instant retiredAt,
        long rowVersion) {

    @Override
    public String toString() {
        return "RevisionView[id=" + id + ", resourceId=" + resourceId + ", revisionNo=" + revisionNo
                + ", state=" + state + ", specHash=" + specHash + "]";
    }
}
