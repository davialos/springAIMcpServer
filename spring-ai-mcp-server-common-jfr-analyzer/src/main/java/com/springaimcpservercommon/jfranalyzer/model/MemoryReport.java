package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Heap, metaspace and allocation.
 *
 * @param heapInitialBytes          initial heap ({@code -Xms})
 * @param heapMaxBytes              maximum heap ({@code -Xmx})
 * @param heapPeakUsedBytes         highest used heap observed (usually just before a GC)
 * @param heapPeakCommittedBytes    highest committed heap observed
 * @param liveSetMinBytes           smallest used heap right after a GC (approximate live set)
 * @param liveSetMaxBytes           largest used heap right after a GC
 * @param liveSetAvgBytes           average used heap right after a GC
 * @param liveSetFirstBytes         first after-GC value of the recording
 * @param liveSetLastBytes          last after-GC value of the recording
 * @param liveSetGrowthBytesPerMin  least-squares slope of after-GC used heap; positive and steady = possible leak
 * @param metaspaceUsedMaxBytes     highest metaspace used
 * @param metaspaceCommittedMaxBytes highest metaspace committed
 * @param heapTimeline              heap occupancy over time
 * @param allocatedBytes            total bytes allocated during the recording (best available source)
 * @param allocationSource          which events {@code allocatedBytes} came from
 * @param allocationRateBytesPerSec {@code allocatedBytes / duration}
 * @param allocationRateTimeline    sampled allocation rate (bytes/s) over time
 * @param allocations               allocation attribution (weight = estimated bytes)
 * @param allocationsRequiringGc    allocations that could not be satisfied and triggered a GC
 * @param oldObjects                {@code jdk.OldObjectSample}: long-lived objects, leak candidates
 */
public record MemoryReport(@Nullable Long heapInitialBytes, @Nullable Long heapMaxBytes,
                           @Nullable Long heapPeakUsedBytes, @Nullable Long heapPeakCommittedBytes,
                           @Nullable Long liveSetMinBytes, @Nullable Long liveSetMaxBytes,
                           @Nullable Long liveSetAvgBytes, @Nullable Long liveSetFirstBytes,
                           @Nullable Long liveSetLastBytes, @Nullable Double liveSetGrowthBytesPerMin,
                           @Nullable Long metaspaceUsedMaxBytes, @Nullable Long metaspaceCommittedMaxBytes,
                           List<HeapPoint> heapTimeline,
                           @Nullable Long allocatedBytes, String allocationSource,
                           @Nullable Double allocationRateBytesPerSec, List<TimePoint> allocationRateTimeline,
                           HotspotReport allocations, HotspotReport allocationsRequiringGc,
                           HotspotReport oldObjects) {
}
