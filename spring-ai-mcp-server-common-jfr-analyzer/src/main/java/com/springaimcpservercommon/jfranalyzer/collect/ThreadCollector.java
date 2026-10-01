package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.ThreadReport;
import jdk.jfr.consumer.RecordedEvent;
import org.jspecify.annotations.Nullable;

/**
 * Blocking and waiting: {@code jdk.JavaMonitorEnter}, {@code jdk.JavaMonitorWait}, {@code jdk.ThreadPark},
 * {@code jdk.ThreadSleep}, {@code jdk.VirtualThreadPinned}, plus {@code jdk.JavaThreadStatistics}.
 */
public final class ThreadCollector implements EventCollector {

    private final StackResolver stacks;
    private final Limits limits;
    private final HotspotAggregator monitorEnter;
    private final HotspotAggregator monitorWait;
    private final HotspotAggregator park;
    private final HotspotAggregator sleep;
    private final HotspotAggregator pinned;
    private final Counter lockOwners = new Counter();
    private final Counter blockedByThread = new Counter();
    private @Nullable Long peak;
    private @Nullable Long active;
    private @Nullable Long daemon;
    private @Nullable Long started;

    /**
     * @param stacks stack resolver
     * @param limits output limits
     */
    public ThreadCollector(StackResolver stacks, Limits limits) {
        this.stacks = stacks;
        this.limits = limits;
        this.monitorEnter = new HotspotAggregator("Lock contention (blocked entering synchronized)", "nanos",
                "Monitor class", limits);
        this.monitorWait = new HotspotAggregator("Object.wait()", "nanos", "Monitor class", limits);
        this.park = new HotspotAggregator("Parked threads (j.u.c locks, queues, futures)", "nanos",
                "Blocker class", limits);
        this.sleep = new HotspotAggregator("Thread.sleep()", "nanos", "", limits);
        this.pinned = new HotspotAggregator("Virtual threads pinned to their carrier", "nanos", "Reason", limits);
    }

    @Override
    public void accept(String type, RecordedEvent e) {
        switch (type) {
            case "jdk.JavaMonitorEnter" -> {
                long nanos = Fields.durationNanos(e);
                String thread = Fields.eventThreadName(e);
                monitorEnter.add(stacks.resolve(e.getStackTrace()), nanos, thread,
                        orUnknown(Fields.className(e, "monitorClass")));
                String owner = Fields.threadName(e, "previousOwner");
                if (owner != null) {
                    lockOwners.add(owner, nanos);
                }
                if (thread != null) {
                    blockedByThread.add(thread, nanos);
                }
            }
            case "jdk.JavaMonitorWait" -> monitorWait.add(stacks.resolve(e.getStackTrace()), Fields.durationNanos(e),
                    Fields.eventThreadName(e), orUnknown(Fields.className(e, "monitorClass")));
            case "jdk.ThreadPark" -> {
                long nanos = Fields.durationNanos(e);
                String thread = Fields.eventThreadName(e);
                String blocker = Fields.className(e, "parkedClass");
                boolean attributed = park.add(stacks.resolve(e.getStackTrace()), nanos, thread,
                        blocker == null ? "(no blocker)" : blocker);
                // Idle pool workers park all the time; only parks reached from the packages count as blocking.
                if (attributed && thread != null) {
                    blockedByThread.add(thread, nanos);
                }
            }
            case "jdk.ThreadSleep" -> sleep.add(stacks.resolve(e.getStackTrace()), Fields.durationNanos(e),
                    Fields.eventThreadName(e), null);
            case "jdk.VirtualThreadPinned" -> {
                String reason = Fields.string(e, "pinnedReason");
                if (reason == null || reason.isBlank()) {
                    reason = Fields.string(e, "blockingOperation");
                }
                pinned.add(stacks.resolve(e.getStackTrace()), Fields.durationNanos(e), Fields.eventThreadName(e),
                        reason == null || reason.isBlank() ? "pinned" : reason);
            }
            case "jdk.JavaThreadStatistics" -> {
                Long p = Fields.longValue(e, "peakCount");
                if (p != null) {
                    peak = peak == null ? p : Math.max(peak, p);
                }
                active = Fields.longValue(e, "activeCount");
                daemon = Fields.longValue(e, "daemonCount");
                started = Fields.longValue(e, "accumulatedCount");
            }
            default -> {
            }
        }
    }

    /** @return the threads section */
    public ThreadReport build() {
        double blockedTotal = monitorEnter.totalWeight() + park.totalWeight();
        return new ThreadReport(peak, active, daemon, started, monitorEnter.build(), monitorWait.build(),
                park.build(), sleep.build(), pinned.build(), lockOwners.top(limits.topN(), monitorEnter.totalWeight()),
                blockedByThread.top(limits.topN(), blockedTotal));
    }

    private static String orUnknown(@Nullable String s) {
        return s == null ? "?" : s;
    }
}
