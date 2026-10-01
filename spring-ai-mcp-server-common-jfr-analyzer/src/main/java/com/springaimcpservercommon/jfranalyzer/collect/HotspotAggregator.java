package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.HotLine;
import com.springaimcpservercommon.jfranalyzer.model.Hotspot;
import com.springaimcpservercommon.jfranalyzer.model.HotspotReport;
import com.springaimcpservercommon.jfranalyzer.model.StackFrameView;
import com.springaimcpservercommon.jfranalyzer.model.StackPath;
import com.springaimcpservercommon.jfranalyzer.model.WeightedName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates weighted events (CPU samples, allocated bytes, blocked nanoseconds, ...) and attributes each to the
 * method in the requested packages closest to the leaf of its stack. Also keeps, per method, the hottest lines, the
 * leaf frames where the cost was paid, a section-specific breakdown, the threads and the most common call paths.
 */
public final class HotspotAggregator {

    /** Above this many distinct call paths per method, only already-known paths keep accumulating. */
    private static final int MAX_PATHS_PER_METHOD = 4_096;
    /** Leaf frames of events without a stack trace. */
    static final String NO_STACK = "(no stack trace)";
    /** Callee entry when the attributed method was itself the leaf. */
    static final String SELF = "(self)";

    private final String title;
    private final String unit;
    private final String detailLabel;
    private final Limits limits;

    private long events;
    private double totalWeight;
    private double attributedWeight;
    private long truncatedStacks;
    private final Map<String, MethodAcc> methods = new HashMap<>();
    private final Map<String, double[]> inclusive = new HashMap<>();
    private final Map<String, LineAcc> lines = new HashMap<>();
    private final Counter outside = new Counter();
    private final Counter details = new Counter();
    private final Counter threads = new Counter();

    /**
     * @param title       section title
     * @param unit        unit of the weights
     * @param detailLabel what the {@code detail} of {@link #add} means, or empty
     * @param limits      output limits
     */
    public HotspotAggregator(String title, String unit, String detailLabel, Limits limits) {
        this.title = title;
        this.unit = unit;
        this.detailLabel = detailLabel;
        this.limits = limits;
    }

    /**
     * Adds one event.
     *
     * @param attribution its stack, or {@code null} if it has none
     * @param weight      its weight (1 for a sample, bytes, nanoseconds, ...)
     * @param thread      thread name, if known
     * @param detail      section-specific bucket (e.g. allocated type), if any
     * @return whether the event was attributed to a method in the requested packages
     */
    public boolean add(@Nullable Attribution attribution, double weight, @Nullable String thread,
                       @Nullable String detail) {
        if (!(weight >= 0) || Double.isInfinite(weight)) {
            weight = 0;
        }
        events++;
        totalWeight += weight;
        if (thread != null) {
            threads.add(thread, weight);
        }
        if (detail != null) {
            details.add(detail, weight);
        }
        if (attribution == null) {
            outside.add(NO_STACK, weight);
            return false;
        }
        if (attribution.truncated()) {
            truncatedStacks++;
        }
        for (MethodInfo m : attribution.inPackageMethods()) {
            double[] acc = inclusive.computeIfAbsent(m.display(), k -> new double[2]);
            acc[0] += weight;
            acc[1]++;
        }
        ResolvedFrame app = attribution.app();
        if (app == null) {
            ResolvedFrame leaf = attribution.leaf();
            outside.add(leaf == null ? NO_STACK : leaf.method().display(), weight);
            return false;
        }
        attributedWeight += weight;
        MethodAcc acc = methods.computeIfAbsent(app.method().key(), k -> new MethodAcc(app.method()));
        acc.weight += weight;
        acc.count++;
        boolean self = attribution.appIndex() == 0;
        if (self) {
            acc.selfWeight += weight;
        }
        LineAcc line = acc.lines.computeIfAbsent(app.line(), l -> new LineAcc(app.method(), app.line(),
                app.location()));
        line.weight += weight;
        line.count++;
        LineAcc global = lines.computeIfAbsent(line.location, l -> new LineAcc(app.method(), app.line(),
                line.location));
        global.weight += weight;
        global.count++;
        ResolvedFrame leaf = attribution.leaf();
        acc.callees.add(self || leaf == null ? SELF : leaf.method().display(), weight);
        if (detail != null) {
            acc.details.add(detail, weight);
        }
        if (thread != null) {
            acc.threads.add(thread, weight);
        }
        if (limits.stacksPerHotspot() > 0) {
            PathAcc path = acc.paths.get(attribution);
            if (path == null) {
                String key = pathKey(attribution);
                path = acc.pathsByKey.get(key);
                if (path == null && acc.pathsByKey.size() < MAX_PATHS_PER_METHOD) {
                    path = new PathAcc(attribution);
                    acc.pathsByKey.put(key, path);
                }
                if (path != null && acc.paths.size() < MAX_PATHS_PER_METHOD) {
                    acc.paths.put(attribution, path);
                }
            }
            if (path != null) {
                path.weight += weight;
                path.count++;
            }
        }
        return true;
    }

    /** @return events added so far */
    public long events() {
        return events;
    }

    /** @return total weight added so far */
    public double totalWeight() {
        return totalWeight;
    }

    /** @return the report */
    public HotspotReport build() {
        int nested = limits.nested();
        List<MethodAcc> ranked = methods.values().stream()
                .sorted(Comparator.comparingDouble((MethodAcc m) -> -m.weight).thenComparing(m -> m.method.key()))
                .limit(limits.topN())
                .toList();
        List<Hotspot> hotspots = new ArrayList<>(ranked.size());
        for (MethodAcc m : ranked) {
            List<LineAcc> mLines = m.lines.values().stream()
                    .sorted(Comparator.comparingDouble((LineAcc l) -> -l.weight).thenComparingInt(l -> l.line))
                    .toList();
            String location = mLines.isEmpty() ? m.method.location(-1, "") : mLines.getFirst().location;
            hotspots.add(new Hotspot(hotspots.size() + 1, m.method.display(), m.method.className(),
                    m.method.methodName(), location, m.weight, m.count, Stats.percent(m.weight, totalWeight),
                    m.selfWeight, mLines.stream().limit(nested).map(this::hotLine).toList(),
                    m.callees.top(nested, totalWeight), m.details.top(nested, totalWeight),
                    m.threads.top(nested, totalWeight), paths(m)));
        }
        List<WeightedName> inclusiveRanked = inclusive.entrySet()
                .stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -e.getValue()[0])
                        .thenComparing(Map.Entry::getKey))
                .limit(limits.topN())
                .map(e -> new WeightedName(e.getKey(),
                        e.getValue()[0], (long) e.getValue()[1], Stats.percent(e.getValue()[0], totalWeight)))
                .toList();
        List<HotLine> hotLines = lines.values().stream()
                .sorted(Comparator.comparingDouble((LineAcc l) -> -l.weight).thenComparing(l -> l.location))
                .limit(limits.topN())
                .map(this::hotLine)
                .toList();
        return new HotspotReport(title, unit, detailLabel, events, totalWeight, attributedWeight,
                Stats.percent(attributedWeight, totalWeight), truncatedStacks, hotspots, inclusiveRanked, hotLines,
                outside.top(limits.topN(), totalWeight), details.top(limits.topN(), totalWeight),
                threads.top(limits.topN(), totalWeight));
    }

    private HotLine hotLine(LineAcc l) {
        return new HotLine(l.location, l.method.display(), l.line > 0 ? l.line : -1, l.weight, l.count,
                Stats.percent(l.weight, totalWeight));
    }

    private List<StackPath> paths(MethodAcc m) {
        return m.pathsByKey.values().stream()
                .sorted(Comparator.comparingDouble((PathAcc p) -> -p.weight))
                .limit(limits.stacksPerHotspot())
                .map(p -> {
                    List<ResolvedFrame> frames = p.attribution.frames();
                    int depth = Math.min(frames.size(), limits.stackDepth());
                    List<StackFrameView> views = frames.subList(0, depth).stream()
                            .map(f -> new StackFrameView(f.location(), f.method().inPackage(), f.frameType()))
                            .toList();
                    return new StackPath(p.weight, p.count, Stats.percent(p.weight, totalWeight), views,
                            p.attribution.truncated() || frames.size() > depth);
                })
                .toList();
    }

    private String pathKey(Attribution a) {
        List<ResolvedFrame> frames = a.frames();
        int depth = Math.min(frames.size(), limits.stackDepth());
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            key.append(frames.get(i).location()).append('\n');
        }
        return key.toString();
    }

    private static final class MethodAcc {
        final MethodInfo method;
        double weight;
        double selfWeight;
        long count;
        final Map<Integer, LineAcc> lines = new HashMap<>();
        final Counter callees = new Counter();
        final Counter details = new Counter();
        final Counter threads = new Counter();
        /** Identity of the resolved stack → its path bucket (fast path); several stacks can share a bucket. */
        final Map<Attribution, PathAcc> paths = new IdentityHashMap<>();
        final Map<String, PathAcc> pathsByKey = new HashMap<>();

        MethodAcc(MethodInfo method) {
            this.method = method;
        }
    }

    private static final class LineAcc {
        final MethodInfo method;
        final int line;
        final String location;
        double weight;
        long count;

        LineAcc(MethodInfo method, int line, String location) {
            this.method = method;
            this.line = line;
            this.location = location;
        }
    }

    private static final class PathAcc {
        final Attribution attribution;
        double weight;
        long count;

        PathAcc(Attribution attribution) {
            this.attribution = attribution;
        }
    }
}
