package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.CpuReport;
import com.springaimcpservercommon.jfranalyzer.model.ExceptionReport;
import com.springaimcpservercommon.jfranalyzer.model.ExecutiveSummary;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.model.GcReport;
import com.springaimcpservercommon.jfranalyzer.model.HealthStatus;
import com.springaimcpservercommon.jfranalyzer.model.KeyMetric;
import com.springaimcpservercommon.jfranalyzer.model.MemoryReport;
import com.springaimcpservercommon.jfranalyzer.model.Severity;
import com.springaimcpservercommon.jfranalyzer.model.ThreadReport;
import com.springaimcpservercommon.jfranalyzer.model.TopIssue;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Condenses the findings and numbers into an {@link ExecutiveSummary}. Metric thresholds are the ones
 * {@link FindingsEngine} uses, so a metric shown AMBER or RED always has a matching finding.
 */
public final class ExecutiveSummaryBuilder {

    static final int CRITICAL_PENALTY = 25;
    static final int WARNING_PENALTY = 8;
    static final int MAX_TOP_ISSUES = 6;
    static final int MAX_RECOMMENDATIONS = 6;

    private ExecutiveSummaryBuilder() {
    }

    /**
     * @param span       recording span
     * @param findings   findings, most severe first
     * @param cpu        CPU section
     * @param memory     memory section
     * @param gc         GC section
     * @param threads    threads section
     * @param exceptions exceptions section
     * @return the executive summary
     */
    public static ExecutiveSummary build(Span span, List<Finding> findings, CpuReport cpu, MemoryReport memory,
                                         GcReport gc, ThreadReport threads, ExceptionReport exceptions) {
        long critical = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long warning = findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
        int score = (int) Math.max(0, 100 - critical * CRITICAL_PENALTY - warning * WARNING_PENALTY);
        HealthStatus status = critical > 0 || score < 50 ? HealthStatus.RED
                : warning > 0 || score < 80 ? HealthStatus.AMBER : HealthStatus.GREEN;

        List<TopIssue> issues = findings.stream()
                .filter(f -> f.severity() != Severity.INFO)
                .limit(MAX_TOP_ISSUES)
                .map(f -> new TopIssue(f.severity(), f.category(), f.title(), impact(f.category()), f.detail(),
                        f.location()))
                .toList();

        String duration = Format.millis(span.millis());
        String headline = switch (status) {
            case GREEN -> "Healthy: no measured metric crossed a threshold during the " + duration + " recording.";
            default -> String.format(Locale.ROOT, "%s: %d critical and %d warning findings in a %s recording; "
                            + "most pressing: %s.", status == HealthStatus.RED ? "Action needed" : "Attention",
                    critical, warning, duration, issues.isEmpty() ? "see findings" : issues.getFirst().title());
        };
        return new ExecutiveSummary(status, score, headline, metrics(span, cpu, memory, gc, threads, exceptions),
                issues, recommendations(findings));
    }

    private static List<KeyMetric> metrics(Span span, CpuReport cpu, MemoryReport memory, GcReport gc,
                                           ThreadReport threads, ExceptionReport exceptions) {
        List<KeyMetric> m = new ArrayList<>();
        boolean cpuRecorded = cpu.jvmCpuAvgPercent() != null;
        m.add(new KeyMetric("cpu.jvmAvgPercent", "JVM CPU (average)", cpu.jvmCpuAvgPercent(), "percent",
                cpuRecorded ? Format.percent(cpu.jvmCpuAvgPercent()) : "–",
                !cpuRecorded ? HealthStatus.UNKNOWN : level(cpu.machineCpuMaxPercent(),
                        FindingsEngine.CPU_SATURATION_PERCENT, Double.MAX_VALUE),
                "Share of the machine's CPU the application used; AMBER when the machine itself saturated."));
        Double topShare = cpu.execution().hotspots().isEmpty() ? null
                : cpu.execution().hotspots().getFirst().percent();
        m.add(new KeyMetric("cpu.hottestMethodPercent", "Hottest method (CPU share)", topShare, "percent",
                topShare == null ? "–" : Format.percent(topShare), topShare == null ? HealthStatus.UNKNOWN
                : level(topShare, FindingsEngine.HOT_METHOD_WARN_PERCENT, FindingsEngine.HOT_METHOD_CRITICAL_PERCENT),
                "One method dominating CPU is the cheapest place to win back capacity."));
        boolean gcRecorded = gc.count() > 0;
        m.add(new KeyMetric("gc.overheadPercent", "Time paused for GC", gcRecorded ? gc.overheadPercent() : null,
                "percent", gcRecorded ? Format.percent(gc.overheadPercent()) : "–", gcRecorded
                ? level(gc.overheadPercent(), FindingsEngine.GC_OVERHEAD_WARN_PERCENT,
                FindingsEngine.GC_OVERHEAD_CRITICAL_PERCENT) : HealthStatus.UNKNOWN,
                "While the collector pauses the application, no request makes progress."));
        m.add(new KeyMetric("gc.maxPauseMs", "Longest GC pause", gcRecorded ? gc.maxPauseMs() : null, "ms",
                gcRecorded ? Format.millis(gc.maxPauseMs()) : "–", gcRecorded
                ? level(gc.maxPauseMs(), FindingsEngine.GC_PAUSE_WARN_MS, FindingsEngine.GC_PAUSE_CRITICAL_MS)
                : HealthStatus.UNKNOWN, "Adds directly to the latency of every request in flight at that moment."));
        m.add(new KeyMetric("gc.p99PauseMs", "GC pause p99", gcRecorded ? gc.p99PauseMs() : null, "ms",
                gcRecorded ? Format.millis(gc.p99PauseMs()) : "–", gcRecorded
                ? level(gc.p99PauseMs(), FindingsEngine.GC_PAUSE_WARN_MS, FindingsEngine.GC_PAUSE_CRITICAL_MS)
                : HealthStatus.UNKNOWN, "The pause 1 in 100 collections exceeds."));
        Double livePct = memory.heapMaxBytes() == null || memory.heapMaxBytes() <= 0
                || memory.liveSetMaxBytes() == null ? null
                : Stats.percent(memory.liveSetMaxBytes(), memory.heapMaxBytes());
        m.add(new KeyMetric("heap.liveSetPercentOfMax", "Heap after GC (of max)", livePct, "percent",
                livePct == null ? "–" : Format.percent(livePct) + " of " + Format.bytes(memory.heapMaxBytes()),
                livePct == null ? HealthStatus.UNKNOWN : level(livePct, FindingsEngine.LIVE_SET_WARN_PERCENT,
                        FindingsEngine.LIVE_SET_CRITICAL_PERCENT),
                "Memory still in use after collection; near 100% means OutOfMemoryError risk."));
        Long peak = memory.heapPeakUsedBytes();
        m.add(new KeyMetric("heap.peakUsedBytes", "Peak heap used", peak == null ? null : peak.doubleValue(),
                "bytes", peak == null ? "–" : Format.bytes(peak), peak == null ? HealthStatus.UNKNOWN
                : HealthStatus.GREEN, "Highest heap occupancy, usually just before a collection."));
        Double rate = memory.allocationRateBytesPerSec();
        m.add(new KeyMetric("allocation.rateBytesPerSec", "Allocation rate", rate, "bytesPerSecond",
                rate == null ? "–" : Format.bytes(rate) + "/s", rate == null ? HealthStatus.UNKNOWN
                : level(rate, FindingsEngine.ALLOCATION_RATE_WARN, Double.MAX_VALUE),
                "Every allocated byte must later be collected; high rates mean frequent GC."));
        double blockedMs = threads.monitorEnter().totalWeight() / 1e6;
        boolean lockRecorded = threads.monitorEnter().present();
        m.add(new KeyMetric("threads.lockBlockedMs", "Time blocked on locks", lockRecorded ? blockedMs : null, "ms",
                lockRecorded ? Format.millis(blockedMs) : "–", lockRecorded
                ? level(Stats.percent(blockedMs, span.millis()), FindingsEngine.BLOCKED_WARN_PERCENT_OF_DURATION,
                Double.MAX_VALUE) : HealthStatus.UNKNOWN,
                "Thread time spent waiting to enter synchronized code, summed over all threads."));
        Double exc = exceptions.throwablesPerSecond();
        m.add(new KeyMetric("exceptions.perSecond", "Exceptions per second", exc, "perSecond",
                exc == null ? "–" : String.format(Locale.ROOT, "%.1f/s", exc), exc == null ? HealthStatus.UNKNOWN
                : level(exc, FindingsEngine.EXCEPTIONS_WARN_PER_SECOND, Double.MAX_VALUE),
                "Exceptions cost stack walks and often hide failed operations."));
        long pinned = threads.pinned().events();
        m.add(new KeyMetric("threads.virtualPinnedEvents", "Virtual-thread pinning", (double) pinned, "count",
                Format.count(pinned), pinned > 0 ? HealthStatus.AMBER : HealthStatus.GREEN,
                "A pinned virtual thread blocks its carrier, so fewer requests run concurrently."));
        return List.copyOf(m);
    }

    private static List<String> recommendations(List<Finding> findings) {
        Set<String> out = new LinkedHashSet<>();
        for (Finding f : findings) {
            if (f.severity() == Severity.INFO || out.size() >= MAX_RECOMMENDATIONS) {
                continue;
            }
            out.add(f.location() == null ? f.title() + ": " + firstSentence(f.detail())
                    : f.title() + ": start at " + shortLocation(f.location()) + ".");
        }
        for (Finding f : findings) {
            if (f.severity() == Severity.INFO && "recording".equals(f.category())
                    && out.size() < MAX_RECOMMENDATIONS) {
                out.add("Improve the next recording: " + firstSentence(f.detail()));
            }
        }
        if (out.isEmpty()) {
            out.add("No action needed. Re-record under peak production load to confirm the result holds.");
        }
        return List.copyOf(out);
    }

    /**
     * Drops the package from a stack-trace style location: {@code com.acme.order.OrderService.place(OrderService.java:42)}
     * becomes {@code OrderService.place(OrderService.java:42)}.
     *
     * @param location stack-trace style location
     * @return the location without the package
     */
    public static String shortLocation(String location) {
        int paren = location.indexOf('(');
        String head = paren < 0 ? location : location.substring(0, paren);
        int method = head.lastIndexOf('.');
        int pkg = method <= 0 ? -1 : head.lastIndexOf('.', method - 1);
        return pkg < 0 ? location : location.substring(pkg + 1);
    }

    /**
     * Drops package prefixes from qualified names in prose: {@code com.acme.order.OrderService.place(int)} becomes
     * {@code OrderService.place(int)}, for readers who do not need the full name.
     *
     * @param text text that may contain qualified names
     * @return the text with lower-case package prefixes removed
     */
    public static String withoutPackages(String text) {
        return PACKAGE_PREFIX.matcher(text).replaceAll("");
    }

    private static final Pattern PACKAGE_PREFIX =
            Pattern.compile("\\b(?:[a-z_][a-z0-9_]*\\.)+(?=[A-Z])");

    static String impact(String category) {
        return switch (category) {
            case "cpu" -> "Requests compete for CPU: latency rises and more instances are needed for the same load.";
            case "gc" -> "The application stops while the collector runs; every request in flight waits.";
            case "memory" -> "Heap pressure drives garbage collection and risks OutOfMemoryError outages.";
            case "threads" -> "Threads wait on each other instead of serving requests, capping throughput.";
            case "io" -> "Requests wait on disk or network calls.";
            case "exceptions" -> "Exceptions are expensive and often hide failing operations.";
            default -> "The recording lacks data needed for a complete analysis.";
        };
    }

    private static String firstSentence(String text) {
        int dot = text.indexOf(". ");
        return dot < 0 ? text : text.substring(0, dot + 1);
    }

    private static HealthStatus level(@Nullable Double value, double warn, double critical) {
        if (value == null) {
            return HealthStatus.UNKNOWN;
        }
        return value >= critical ? HealthStatus.RED : value >= warn ? HealthStatus.AMBER : HealthStatus.GREEN;
    }
}
