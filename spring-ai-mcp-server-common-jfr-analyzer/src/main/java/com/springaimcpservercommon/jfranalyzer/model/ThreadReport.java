package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Threads and everything that made them wait. Weights are nanoseconds of waiting.
 *
 * @param peakThreads      peak live thread count
 * @param lastActiveThreads live threads at the last {@code jdk.JavaThreadStatistics}
 * @param daemonThreads    daemon threads at the same point
 * @param startedThreads   threads started since JVM start
 * @param monitorEnter     contended {@code synchronized} entry ({@code jdk.JavaMonitorEnter}), detail = monitor
 *                         class
 * @param monitorWait      {@code Object.wait} ({@code jdk.JavaMonitorWait}), detail = monitor class
 * @param park             {@code LockSupport.park}: {@code j.u.c} locks, queues, futures ({@code jdk.ThreadPark}),
 *                         detail = blocker class
 * @param sleep            {@code Thread.sleep} ({@code jdk.ThreadSleep})
 * @param pinned           virtual threads pinned to their carrier ({@code jdk.VirtualThreadPinned}), detail =
 *                         reason
 * @param lockOwners       threads that held a contended monitor ({@code previousOwner}), weight = time others
 *                         waited
 * @param blockedByThread  per thread, contended monitor entry plus parks attributed to the packages
 */
public record ThreadReport(@Nullable Long peakThreads, @Nullable Long lastActiveThreads,
                           @Nullable Long daemonThreads, @Nullable Long startedThreads,
                           HotspotReport monitorEnter, HotspotReport monitorWait, HotspotReport park,
                           HotspotReport sleep, HotspotReport pinned, List<WeightedName> lockOwners,
                           List<WeightedName> blockedByThread) {
}
