package com.springaimcpservercommon.persistence.audit;

import org.jspecify.annotations.Nullable;

/**
 * Result of {@link AuditTrail#verify(String, long, long)}.
 *
 * @param chainId        verified chain
 * @param fromSeq        first sequence number requested
 * @param toSeq          last sequence number requested
 * @param eventsChecked  events whose hash and link were verified before the first break (or in total)
 * @param firstBrokenSeq first sequence number whose link, hash, position or presence is wrong; {@code null} if
 *                       intact
 * @param problem        short description of the break, {@code null} if intact
 * @param anchored       {@code false} if the event before {@code fromSeq} was not available (e.g. dropped by
 *                       retention), so the first event's {@code prev_hash} was trusted as anchor
 */
public record ChainVerification(
        String chainId,
        long fromSeq,
        long toSeq,
        long eventsChecked,
        @Nullable Long firstBrokenSeq,
        @Nullable String problem,
        boolean anchored) {

    /**
     * Whether no break was found.
     *
     * @return {@code true} if intact
     */
    public boolean intact() {
        return firstBrokenSeq == null;
    }
}
