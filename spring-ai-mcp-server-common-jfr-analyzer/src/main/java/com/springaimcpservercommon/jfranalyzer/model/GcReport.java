package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Garbage collection.
 *
 * @param youngCollector     young-generation collector
 * @param oldCollector       old-generation collector
 * @param count              number of collections
 * @param totalPauseMs       sum of all stop-the-world pauses
 * @param maxPauseMs         longest pause
 * @param avgPauseMs         average pause per collection
 * @param p50PauseMs         median pause
 * @param p95PauseMs         95th percentile pause
 * @param p99PauseMs         99th percentile pause
 * @param overheadPercent    {@code totalPause / recording duration} (0–100): time the application was stopped
 * @param totalDurationMs    sum of collection durations, including concurrent work
 * @param collectionsPerMinute collection frequency
 * @param byCollector        per collector
 * @param byCause            per cause
 * @param longestPauses      the longest collections
 * @param pauseTimeline      pause (ms) of each collection over time
 */
public record GcReport(@Nullable String youngCollector, @Nullable String oldCollector, long count,
                       double totalPauseMs, double maxPauseMs, double avgPauseMs, double p50PauseMs,
                       double p95PauseMs, double p99PauseMs, double overheadPercent, double totalDurationMs,
                       double collectionsPerMinute, List<GcGroup> byCollector, List<GcGroup> byCause,
                       List<GcEvent> longestPauses, List<TimePoint> pauseTimeline) {
}
