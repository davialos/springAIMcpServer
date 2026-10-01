package com.springaimcpservercommon.jfranalyzer.model;

/**
 * Heap occupancy at one instant.
 *
 * @param offsetMillis   milliseconds since the start of the recording
 * @param usedBytes      used heap
 * @param committedBytes committed heap
 * @param when           {@code Before GC}, {@code After GC} or {@code Periodic}
 */
public record HeapPoint(long offsetMillis, long usedBytes, long committedBytes, String when) {
}
