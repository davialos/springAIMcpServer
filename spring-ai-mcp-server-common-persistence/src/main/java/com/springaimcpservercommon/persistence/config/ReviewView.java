package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a review.
 *
 * @param id         review id
 * @param revisionId reviewed revision
 * @param reviewerId reviewer
 * @param decision   decision
 * @param comment    comment (mandatory unless approved)
 * @param decidedAt  decision time
 */
public record ReviewView(
        UUID id,
        UUID revisionId,
        UUID reviewerId,
        ReviewDecision decision,
        @Nullable String comment,
        Instant decidedAt) {
}
