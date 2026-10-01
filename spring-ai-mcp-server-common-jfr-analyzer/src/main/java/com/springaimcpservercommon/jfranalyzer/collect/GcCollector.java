package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.GcEvent;
import com.springaimcpservercommon.jfranalyzer.model.GcGroup;
import com.springaimcpservercommon.jfranalyzer.model.GcReport;
import com.springaimcpservercommon.jfranalyzer.model.TimePoint;
import jdk.jfr.consumer.RecordedEvent;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Garbage collection: {@code jdk.GarbageCollection}, {@code jdk.GCHeapSummary}, {@code jdk.GCConfiguration}.
 */
public final class GcCollector implements EventCollector {

    private static final int RAW_TIMELINE_LIMIT = 1_000;

    private final Limits limits;
    private final Map<Long, Gc> byId = new LinkedHashMap<>();
    private final Map<Long, long[]> heapByGcId = new HashMap<>();
    private @Nullable String youngCollector;
    private @Nullable String oldCollector;

    /** @param limits output limits */
    public GcCollector(Limits limits) {
        this.limits = limits;
    }

    @Override
    public void accept(String type, RecordedEvent e) {
        switch (type) {
            case "jdk.GCConfiguration" -> {
                youngCollector = Fields.string(e, "youngCollector");
                oldCollector = Fields.string(e, "oldCollector");
            }
            case "jdk.GarbageCollection" -> {
                Long id = Fields.longValue(e, "gcId");
                long key = id == null ? -(byId.size() + 1L) : id;
                String name = Fields.string(e, "name");
                String cause = Fields.string(e, "cause");
                byId.put(key, new Gc(key, name == null ? "?" : name, cause == null ? "?" : cause, e.getStartTime(),
                        Fields.nanos(e, "sumOfPauses"), Fields.nanos(e, "longestPause"), Fields.durationNanos(e)));
            }
            case "jdk.GCHeapSummary" -> {
                Long id = Fields.longValue(e, "gcId");
                Long used = Fields.longValue(e, "heapUsed");
                String when = Fields.string(e, "when");
                if (id == null || used == null || when == null) {
                    return;
                }
                long[] beforeAfter = heapByGcId.computeIfAbsent(id, k -> new long[]{-1, -1});
                beforeAfter[when.startsWith("Before") ? 0 : 1] = used;
            }
            default -> {
            }
        }
    }

    /** @return number of collections seen */
    public long count() {
        return byId.size();
    }

    /**
     * @param span recording span
     * @return the GC section
     */
    public GcReport build(Span span) {
        List<GcEvent> all = new ArrayList<>(byId.size());
        for (Gc gc : byId.values()) {
            long[] heap = heapByGcId.get(gc.id);
            Long before = heap == null || heap[0] < 0 ? null : heap[0];
            Long after = heap == null || heap[1] < 0 ? null : heap[1];
            all.add(new GcEvent(gc.id, gc.name, gc.cause, span.offset(gc.start), ms(gc.pauseNanos),
                    ms(gc.longestPauseNanos), ms(gc.durationNanos), before, after,
                    before == null || after == null ? null : before - after));
        }
        double[] pauses = Stats.sorted(all.stream().mapToDouble(GcEvent::pauseMs).toArray());
        double total = all.stream().mapToDouble(GcEvent::pauseMs).sum();
        double max = pauses.length == 0 ? 0 : pauses[pauses.length - 1];
        double totalDuration = all.stream().mapToDouble(GcEvent::durationMs).sum();
        List<GcEvent> longest = all.stream()
                .sorted(Comparator.comparingDouble((GcEvent g) -> -g.pauseMs()).thenComparingLong(GcEvent::gcId))
                .limit(limits.topN())
                .toList();
        List<TimePoint> timeline;
        if (all.size() <= RAW_TIMELINE_LIMIT) {
            timeline = all.stream().map(g -> new TimePoint(g.offsetMillis(), g.pauseMs())).toList();
        } else {
            TimeSeries series = new TimeSeries(TimeSeries.Mode.MAX);
            byId.values().forEach(g -> series.add(g.start, ms(g.pauseNanos)));
            timeline = series.points(span.start(), span.end());
        }
        return new GcReport(youngCollector, oldCollector, all.size(), total, max,
                all.isEmpty() ? 0 : total / all.size(), Stats.percentile(pauses, 50),
                Stats.percentile(pauses, 95), Stats.percentile(pauses, 99), Stats.percent(total, span.millis()),
                totalDuration, all.size() / (span.seconds() / 60.0), group(all, GcEvent::name),
                group(all, GcEvent::cause), longest, timeline);
    }

    private static List<GcGroup> group(List<GcEvent> all, Function<GcEvent, String> by) {
        Map<String, List<GcEvent>> groups = new LinkedHashMap<>();
        all.forEach(g -> groups.computeIfAbsent(by.apply(g), k -> new ArrayList<>()).add(g));
        return groups.entrySet().stream().map(entry -> {
            List<GcEvent> g = entry.getValue();
            double total = g.stream().mapToDouble(GcEvent::pauseMs).sum();
            return new GcGroup(entry.getKey(), g.size(), total,
                    g.stream().mapToDouble(GcEvent::pauseMs).max().orElse(0), total / g.size(),
                    g.stream().mapToDouble(GcEvent::durationMs).sum());
        }).sorted(Comparator.comparingDouble((GcGroup g) -> -g.totalPauseMs()).thenComparing(GcGroup::name))
                .toList();
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record Gc(long id, String name, String cause, Instant start, long pauseNanos, long longestPauseNanos,
                      long durationNanos) {
    }
}
