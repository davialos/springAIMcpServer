package com.springaimcpservercommon.jfranalyzer.model;

/**
 * Garbage collections grouped by collector or cause.
 *
 * @param name          collector name or cause
 * @param count         number of collections
 * @param totalPauseMs  sum of stop-the-world pauses
 * @param maxPauseMs    longest single pause
 * @param avgPauseMs    average pause per collection
 * @param totalDurationMs sum of collection durations (concurrent collectors run longer than they pause)
 */
public record GcGroup(String name, long count, double totalPauseMs, double maxPauseMs, double avgPauseMs,
                      double totalDurationMs) {
}
