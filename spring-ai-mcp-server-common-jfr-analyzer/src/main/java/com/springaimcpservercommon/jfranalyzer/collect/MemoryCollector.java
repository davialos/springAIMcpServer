package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.HeapPoint;
import com.springaimcpservercommon.jfranalyzer.model.MemoryReport;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedThread;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Heap, metaspace and allocation: {@code jdk.GCHeapConfiguration}, {@code jdk.GCHeapSummary},
 * {@code jdk.GCHeapMemoryUsage}, {@code jdk.MetaspaceSummary}, {@code jdk.ObjectAllocationSample} (or the TLAB
 * events), {@code jdk.ThreadAllocationStatistics}, {@code jdk.AllocationRequiringGC}, {@code jdk.OldObjectSample}.
 */
public final class MemoryCollector implements EventCollector {

    private static final int MAX_HEAP_POINTS = 2_000;

    private final StackResolver stacks;
    private final HotspotAggregator sampled;
    private final HotspotAggregator tlab;
    private final HotspotAggregator requiringGc;
    private final HotspotAggregator oldObjects;
    private final TimeSeries sampledRate = new TimeSeries(TimeSeries.Mode.RATE);
    private final TimeSeries tlabRate = new TimeSeries(TimeSeries.Mode.RATE);
    private final List<Point> heap = new ArrayList<>();
    private final Map<Long, long[]> threadAllocated = new HashMap<>();
    private @Nullable Long heapInitial;
    private @Nullable Long heapMax;
    private @Nullable Long metaspaceUsedMax;
    private @Nullable Long metaspaceCommittedMax;

    /**
     * @param stacks stack resolver
     * @param limits output limits
     */
    public MemoryCollector(StackResolver stacks, Limits limits) {
        this.stacks = stacks;
        this.sampled = new HotspotAggregator("Allocation hot spots (estimated bytes)", "bytes", "Allocated type",
                limits);
        this.tlab = new HotspotAggregator("Allocation hot spots (TLAB events)", "bytes", "Allocated type",
                limits);
        this.requiringGc = new HotspotAggregator("Allocations that triggered a GC", "bytes", "", limits);
        this.oldObjects = new HotspotAggregator("Long-lived objects (old object samples, leak candidates)",
                "bytes", "Object type", limits);
    }

    @Override
    public void accept(String type, RecordedEvent e) {
        switch (type) {
            case "jdk.GCHeapConfiguration" -> {
                heapInitial = Fields.longValue(e, "initialSize");
                heapMax = Fields.longValue(e, "maxSize");
            }
            case "jdk.GCHeapSummary" -> {
                Long used = Fields.longValue(e, "heapUsed");
                Long committed = Fields.longValue(Fields.object(e, "heapSpace"), "committedSize");
                String when = Fields.string(e, "when");
                if (used != null) {
                    heap.add(new Point(e.getStartTime(), used, committed == null ? 0 : committed,
                            when == null ? "?" : when));
                }
            }
            case "jdk.GCHeapMemoryUsage" -> {
                Long used = Fields.longValue(e, "used");
                Long committed = Fields.longValue(e, "committed");
                Long max = Fields.longValue(e, "max");
                if (used != null) {
                    heap.add(new Point(e.getStartTime(), used, committed == null ? 0 : committed, "Periodic"));
                }
                if (heapMax == null && max != null && max > 0) {
                    heapMax = max;
                }
            }
            case "jdk.MetaspaceSummary" -> {
                RecordedObject metaspace = Fields.object(e, "metaspace");
                metaspaceUsedMax = max(metaspaceUsedMax, Fields.longValue(metaspace, "used"));
                metaspaceCommittedMax = max(metaspaceCommittedMax, Fields.longValue(metaspace, "committed"));
            }
            case "jdk.ObjectAllocationSample" -> {
                Long weight = Fields.longValue(e, "weight");
                double bytes = weight == null ? 0 : weight;
                sampled.add(stacks.resolve(e.getStackTrace()), bytes, Fields.eventThreadName(e),
                        Fields.className(e, "objectClass"));
                sampledRate.add(e.getStartTime(), bytes);
            }
            case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" -> {
                Long size = Fields.longValue(e, type.endsWith("InNewTLAB") ? "tlabSize" : "allocationSize");
                double bytes = size == null ? 0 : size;
                tlab.add(stacks.resolve(e.getStackTrace()), bytes, Fields.eventThreadName(e),
                        Fields.className(e, "objectClass"));
                tlabRate.add(e.getStartTime(), bytes);
            }
            case "jdk.ThreadAllocationStatistics" -> {
                Long allocated = Fields.longValue(e, "allocated");
                Object thread = e.hasField("thread") ? e.getValue("thread") : null;
                if (allocated == null || !(thread instanceof RecordedThread t)) {
                    return;
                }
                long[] range = threadAllocated.computeIfAbsent(t.getId(), k -> new long[]{Long.MAX_VALUE, 0});
                range[0] = Math.min(range[0], allocated);
                range[1] = Math.max(range[1], allocated);
            }
            case "jdk.AllocationRequiringGC" -> {
                Long size = Fields.longValue(e, "size");
                requiringGc.add(stacks.resolve(e.getStackTrace()), size == null ? 0 : size,
                        Fields.eventThreadName(e), null);
            }
            case "jdk.OldObjectSample" -> {
                Long size = Fields.longValue(e, "objectSize");
                RecordedObject object = Fields.object(e, "object");
                String objectType = Fields.className(object, "type");
                oldObjects.add(stacks.resolve(e.getStackTrace()), size == null ? 0 : size,
                        Fields.eventThreadName(e), objectType == null ? "?" : objectType);
            }
            default -> {
            }
        }
    }

    /**
     * @param span recording span
     * @return the memory section
     */
    public MemoryReport build(Span span) {
        heap.sort((a, b) -> a.time.compareTo(b.time));
        Long peakUsed = heap.stream().mapToLong(Point::used).boxed().max(Long::compare).orElse(null);
        Long peakCommitted = heap.stream().mapToLong(Point::committed).boxed().max(Long::compare).orElse(null);
        List<Point> afterGc = heap.stream().filter(p -> p.when.startsWith("After")).toList();
        Long liveMin = afterGc.stream().mapToLong(Point::used).boxed().min(Long::compare).orElse(null);
        Long liveMax = afterGc.stream().mapToLong(Point::used).boxed().max(Long::compare).orElse(null);
        Long liveAvg = afterGc.isEmpty() ? null
                : (long) afterGc.stream().mapToLong(Point::used).average().orElse(0);
        Double growth = null;
        if (afterGc.size() >= 3) {
            double[] minutes = afterGc.stream().mapToDouble(p -> span.offset(p.time) / 60_000.0).toArray();
            double[] used = afterGc.stream().mapToDouble(Point::used).toArray();
            growth = Stats.slope(minutes, used);
        }
        int stride = Math.max(1, (heap.size() + MAX_HEAP_POINTS - 1) / MAX_HEAP_POINTS);
        List<HeapPoint> timeline = IntStream.range(0, heap.size())
                .filter(i -> i % stride == 0)
                .mapToObj(heap::get)
                .map(p -> new HeapPoint(span.offset(p.time), p.used, p.committed, p.when))
                .toList();

        boolean useSamples = sampled.events() > 0 || tlab.events() == 0;
        HotspotAggregator allocations = useSamples ? sampled : tlab;
        long threadTotal = threadAllocated.values().stream().mapToLong(r -> r[1] - r[0]).sum();
        // jdk.ThreadAllocationStatistics is cumulative per thread but, in the stock settings, only emitted at chunk
        // boundaries: threads that live between two emissions are missed and it under-counts. The sampled weights
        // estimate the same total from the other side, so the larger of the two is the better figure.
        long sampledTotal = (long) allocations.totalWeight();
        Long allocated;
        String source;
        if (threadTotal <= 0 && sampledTotal <= 0) {
            allocated = null;
            source = "none recorded";
        } else if (threadTotal >= sampledTotal) {
            allocated = threadTotal;
            source = "jdk.ThreadAllocationStatistics";
        } else {
            allocated = sampledTotal;
            source = useSamples ? "jdk.ObjectAllocationSample (estimated)" : "jdk.ObjectAllocationInNewTLAB/OutsideTLAB";
        }
        return new MemoryReport(heapInitial, heapMax, peakUsed, peakCommitted, liveMin, liveMax, liveAvg,
                afterGc.isEmpty() ? null : afterGc.getFirst().used, afterGc.isEmpty() ? null : afterGc.getLast().used,
                growth, metaspaceUsedMax, metaspaceCommittedMax, timeline, allocated, source,
                allocated == null ? null : allocated / span.seconds(),
                (useSamples ? sampledRate : tlabRate).points(span.start(), span.end()), allocations.build(),
                requiringGc.build(), oldObjects.build());
    }

    private static @Nullable Long max(@Nullable Long a, @Nullable Long b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.max(a, b);
    }

    private record Point(Instant time, long used, long committed, String when) {
    }
}
