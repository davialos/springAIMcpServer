package com.springaimcpservercommon.ruleengine.admin;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One revision of a rule or group.
 *
 * @param id             revision id
 * @param kind           RULE or GROUP
 * @param subjectId      the rule or group
 * @param revisionNo     1, 2, 3 … per subject
 * @param state          DRAFT, SUBMITTED, APPROVED, REJECTED, PUBLISHED or SUPERSEDED
 * @param content        the definition ({@link RuleContent} / {@link GroupContent} as JSON)
 * @param changeNote     why it changed
 * @param rollbackOf     the revision this one restores, if any
 * @param createdBy      author
 * @param createdAt      creation time
 * @param submittedBy    who asked for review
 * @param reviewedBy     who approved or rejected (never the submitter)
 * @param reviewComment  the reviewer's comment
 * @param publishedBy    who published it
 * @param publishedAt    publication time
 */
public record Revision(UUID id, Kind kind, UUID subjectId, int revisionNo, String state, JsonNode content,
                       @Nullable String changeNote, @Nullable UUID rollbackOf, String createdBy, Instant createdAt,
                       @Nullable String submittedBy, @Nullable String reviewedBy, @Nullable String reviewComment,
                       @Nullable String publishedBy, @Nullable Instant publishedAt) {

    /** What a revision describes. */
    public enum Kind {
        /** A rule. */
        RULE,
        /** A rule group. */
        GROUP
    }
}
