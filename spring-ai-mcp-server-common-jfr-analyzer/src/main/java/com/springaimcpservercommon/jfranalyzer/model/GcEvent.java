package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

/**
 * One garbage collection.
 *
 * @param gcId            JVM collection id
 * @param name            collector
 * @param cause           cause, e.g. {@code G1 Evacuation Pause}, {@code System.gc()}
 * @param offsetMillis    start, milliseconds since the start of the recording
 * @param pauseMs         sum of its stop-the-world pauses
 * @param longestPauseMs  its longest single pause
 * @param durationMs      its total duration
 * @param heapBeforeBytes used heap before
 * @param heapAfterBytes  used heap after
 * @param reclaimedBytes  {@code before - after}
 */
public record GcEvent(long gcId, String name, String cause, long offsetMillis, double pauseMs, double longestPauseMs,
                      double durationMs, @Nullable Long heapBeforeBytes, @Nullable Long heapAfterBytes,
                      @Nullable Long reclaimedBytes) {
}
