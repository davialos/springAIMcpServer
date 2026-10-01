package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

/**
 * Headline numbers.
 *
 * @param durationMillis            recording span
 * @param cpuSamples                execution samples
 * @param cpuPercentInPackages      share of samples attributed to the packages (0–100)
 * @param topCpuLocation            hottest line in the packages
 * @param jvmCpuAvgPercent          average JVM CPU
 * @param heapMaxBytes              maximum heap
 * @param heapPeakUsedBytes         peak used heap
 * @param liveSetLastBytes          used heap after the last GC
 * @param allocatedBytes            bytes allocated
 * @param allocationRateBytesPerSec allocation rate
 * @param topAllocationLocation     line in the packages allocating the most
 * @param gcCount                   collections
 * @param gcTotalPauseMs            total pause
 * @param gcMaxPauseMs              longest pause
 * @param gcOverheadPercent         share of the recording spent paused
 * @param monitorBlockedMs          time spent blocked on contended monitors
 * @param parkedInPackagesMs        time parked in calls from the packages
 * @param topBlockingLocation       line in the packages with the most blocked + parked time
 * @param pinnedEvents              virtual-thread pinning events
 * @param exceptionsThrown          throwables created
 * @param criticalFindings          findings with severity CRITICAL
 * @param warningFindings           findings with severity WARNING
 */
public record Summary(long durationMillis, long cpuSamples, double cpuPercentInPackages,
                      @Nullable String topCpuLocation, @Nullable Double jvmCpuAvgPercent,
                      @Nullable Long heapMaxBytes, @Nullable Long heapPeakUsedBytes, @Nullable Long liveSetLastBytes,
                      @Nullable Long allocatedBytes, @Nullable Double allocationRateBytesPerSec,
                      @Nullable String topAllocationLocation, long gcCount, double gcTotalPauseMs,
                      double gcMaxPauseMs, double gcOverheadPercent, double monitorBlockedMs,
                      double parkedInPackagesMs, @Nullable String topBlockingLocation, long pinnedEvents,
                      @Nullable Long exceptionsThrown, long criticalFindings, long warningFindings) {
}
