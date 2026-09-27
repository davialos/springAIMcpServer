package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One resource of a loaded snapshot generation with the spec of its revision in that generation.
 *
 * @param resourceId           resource
 * @param workspaceId          owning workspace
 * @param kind                 kind
 * @param slug                 slug
 * @param resourceStatus       current operational status (SUSPENDED resources must not be served)
 * @param suspendedReason      reason while suspended
 * @param revisionId           revision of the generation
 * @param revisionNo           its number
 * @param revisionState        its current state (PUBLISHED/DEPRECATED for the latest generation)
 * @param specJson             spec JSON object
 * @param specSchemaVersion    spec schema version
 * @param specHash             spec hash
 * @param publishedAt          time the revision last went live
 */
public record PublishedResource(
        UUID resourceId,
        UUID workspaceId,
        ResourceKind kind,
        String slug,
        ResourceStatus resourceStatus,
        @Nullable String suspendedReason,
        UUID revisionId,
        int revisionNo,
        RevisionState revisionState,
        String specJson,
        int specSchemaVersion,
        String specHash,
        @Nullable Instant publishedAt) {

    /**
     * Whether the stored spec still hashes to {@code specHash}.
     *
     * @return {@code true} when intact
     */
    public boolean specHashMatches() {
        return CanonicalSpec.of(specJson).hash().equals(specHash);
    }

    @Override
    public String toString() {
        return "PublishedResource[" + kind + " " + slug + ", resourceId=" + resourceId + ", revisionId=" + revisionId + "]";
    }
}
