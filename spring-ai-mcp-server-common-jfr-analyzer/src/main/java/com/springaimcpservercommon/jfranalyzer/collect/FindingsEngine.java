package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.CpuReport;
import com.springaimcpservercommon.jfranalyzer.model.ExceptionReport;
import com.springaimcpservercommon.jfranalyzer.model.Finding;
import com.springaimcpservercommon.jfranalyzer.model.GcGroup;
import com.springaimcpservercommon.jfranalyzer.model.GcReport;
import com.springaimcpservercommon.jfranalyzer.model.Hotspot;
import com.springaimcpservercommon.jfranalyzer.model.HotspotReport;
import com.springaimcpservercommon.jfranalyzer.model.IoReport;
import com.springaimcpservercommon.jfranalyzer.model.MemoryReport;
import com.springaimcpservercommon.jfranalyzer.model.Severity;
import com.springaimcpservercommon.jfranalyzer.model.ThreadReport;
import com.springaimcpservercommon.jfranalyzer.model.WeightedName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the numbers into findings. Thresholds are deliberately conservative rules of thumb; every finding states
 * the measured value so a reader can judge it, and points at a method in the requested packages when one is
 * responsible.
 */
public final class FindingsEngine {

    static final double HOT_METHOD_WARN_PERCENT = 15;
    static final double HOT_METHOD_CRITICAL_PERCENT = 35;
    static final double GC_OVERHEAD_WARN_PERCENT = 5;
    static final double GC_OVERHEAD_CRITICAL_PERCENT = 15;
    static final double GC_PAUSE_WARN_MS = 200;
    static final double GC_PAUSE_CRITICAL_MS = 1_000;
    static final double LIVE_SET_WARN_PERCENT = 70;
    static final double LIVE_SET_CRITICAL_PERCENT = 85;
    static final double ALLOCATION_RATE_WARN = 1024.0 * 1024 * 1024;
    static final double ALLOCATION_HOTSPOT_WARN_PERCENT = 20;
    static final double BLOCKED_WARN_PERCENT_OF_DURATION = 5;
    static final double IO_WARN_MS = 1_000;
    static final double EXCEPTIONS_WARN_PER_SECOND = 1_000;
    static final double CPU_SATURATION_PERCENT = 90;

    private final List<Finding> findings = new ArrayList<>();

    /**
     * @param restricted   whether packages were given
     * @param span         recording span
     * @param eventCounts  events per type
     * @param cpu          CPU section
     * @param memory       memory section
     * @param gc           GC section
     * @param threads      threads section
     * @param io           I/O section
     * @param exceptions   exceptions section
     * @return findings, most severe first
     */
    public static List<Finding> evaluate(boolean restricted, Span span, Map<String, Long> eventCounts,
                                         CpuReport cpu, MemoryReport memory, GcReport gc, ThreadReport threads,
                                         IoReport io, ExceptionReport exceptions) {
        FindingsEngine engine = new FindingsEngine();
        engine.recording(restricted, eventCounts, cpu, memory);
        engine.cpu(cpu);
        engine.gc(gc, memory);
        engine.memory(memory, span);
        engine.threads(threads, span);
        engine.io(io);
        engine.exceptions(exceptions);
        engine.findings.sort(Comparator.comparing(Finding::severity));
        return List.copyOf(engine.findings);
    }

    private void recording(boolean restricted, Map<String, Long> counts, CpuReport cpu, MemoryReport memory) {
        if (!counts.containsKey("jdk.ExecutionSample") && !counts.containsKey("jdk.CPUTimeSample")) {
            add(Severity.INFO, "recording", "No CPU samples in the recording",
                    "Record with method sampling enabled, e.g. -XX:StartFlightRecording:settings=profile.", null);
        }
        if (!counts.containsKey("jdk.ObjectAllocationSample") && !counts.containsKey("jdk.ObjectAllocationInNewTLAB")) {
            add(Severity.INFO, "recording", "No allocation samples in the recording",
                    "jdk.ObjectAllocationSample is on in the default and profile settings; enable it to see "
                            + "which code allocates.", null);
        }
        if (!restricted) {
            add(Severity.INFO, "recording", "No packages given: every frame counts as application code",
                    "Pass --package com.yourcompany to attribute costs to your own methods instead of the JDK or "
                            + "libraries they call.", null);
            return;
        }
        long attributedEvents = cpu.execution().hotspots().size() + memory.allocations().hotspots().size();
        if (attributedEvents == 0 && (cpu.execution().present() || memory.allocations().present())) {
            add(Severity.WARNING, "recording", "No stack frame matched the requested packages",
                    "None of the sampled stacks contains a class in the given packages. Check the package names "
                            + "(prefix match, e.g. com.acme matches com.acme.order.OrderService) or record while "
                            + "the code is busy.", null);
        }
        HotspotReport exec = cpu.execution();
        if (exec.events() > 0 && exec.truncatedStacks() * 100.0 / exec.events() > 20
                && exec.attributedPercent() < 50) {
            add(Severity.INFO, "recording", String.format(Locale.ROOT,
                            "%.0f%% of CPU stacks are truncated", exec.truncatedStacks() * 100.0 / exec.events()),
                    "Deep stacks (Spring, reactive, proxies) were cut at JFR's stack depth (64 by default), which can "
                            + "hide the calling application frame. Record with "
                            + "-XX:FlightRecorderOptions:stackdepth=256.", null);
        }
    }

    private void cpu(CpuReport cpu) {
        HotspotReport exec = cpu.execution();
        if (!exec.hotspots().isEmpty()) {
            for (Hotspot h : exec.hotspots().subList(0, Math.min(3, exec.hotspots().size()))) {
                if (h.percent() >= HOT_METHOD_WARN_PERCENT) {
                    add(h.percent() >= HOT_METHOD_CRITICAL_PERCENT ? Severity.CRITICAL : Severity.WARNING, "cpu",
                            String.format(Locale.ROOT, "%s accounts for %.1f%% of CPU samples",
                                    h.method(), h.percent()),
                            "Cost is spent mostly in " + topName(h.callees()) + ". Self time "
                                    + String.format(Locale.ROOT, "%.0f%%", Stats.percent(h.selfWeight(), h.weight()))
                                    + " of this method's samples. Start at the hottest line.",
                            h.location());
                }
            }
        }
        if (exec.events() >= 100 && exec.attributedPercent() < 10 && !exec.hotspots().isEmpty()) {
            add(Severity.INFO, "cpu", String.format(Locale.ROOT,
                            "Only %.1f%% of CPU samples pass through the requested packages",
                            exec.attributedPercent()),
                    "Most CPU time is in code not called from these packages (framework, JIT, GC threads, other "
                            + "components). See the 'outside' table.", null);
        }
        if (cpu.machineCpuMaxPercent() != null && cpu.machineCpuMaxPercent() >= CPU_SATURATION_PERCENT) {
            add(Severity.WARNING, "cpu", String.format(Locale.ROOT, "Machine CPU peaked at %.0f%%",
                            cpu.machineCpuMaxPercent()),
                    String.format(Locale.ROOT, "JVM average %.0f%%, machine average %.0f%%. A saturated CPU inflates "
                                    + "every latency, including GC.", nz(cpu.jvmCpuAvgPercent()),
                            nz(cpu.machineCpuAvgPercent())), null);
        }
    }

    private void gc(GcReport gc, MemoryReport memory) {
        if (gc.count() == 0) {
            return;
        }
        if (gc.overheadPercent() >= GC_OVERHEAD_WARN_PERCENT) {
            add(gc.overheadPercent() >= GC_OVERHEAD_CRITICAL_PERCENT ? Severity.CRITICAL : Severity.WARNING, "gc",
                    String.format(Locale.ROOT, "GC pauses stopped the application %.1f%% of the time",
                            gc.overheadPercent()),
                    String.format(Locale.ROOT, "%d collections, %.0f ms paused in total. Reduce allocation "
                            + "(see the allocation hot spots) or give the heap more room.", gc.count(),
                            gc.totalPauseMs()), topLocation(memory.allocations()));
        }
        if (gc.maxPauseMs() >= GC_PAUSE_WARN_MS) {
            add(gc.maxPauseMs() >= GC_PAUSE_CRITICAL_MS ? Severity.CRITICAL : Severity.WARNING, "gc",
                    String.format(Locale.ROOT, "Longest GC pause %.0f ms", gc.maxPauseMs()),
                    String.format(Locale.ROOT, "p95 %.1f ms, p99 %.1f ms. Cause of the longest: %s.",
                            gc.p95PauseMs(), gc.p99PauseMs(),
                            gc.longestPauses().isEmpty() ? "?" : gc.longestPauses().getFirst().cause()), null);
        }
        for (GcGroup cause : gc.byCause()) {
            String c = cause.name();
            if (c.contains("System.gc")) {
                add(Severity.WARNING, "gc", cause.count() + " explicit System.gc() collections",
                        "Something calls System.gc(); each one is usually a full, stop-the-world collection. Find "
                                + "the caller or run with -XX:+DisableExplicitGC.", null);
            } else if (c.contains("Humongous")) {
                add(Severity.WARNING, "gc", cause.count() + " collections caused by humongous allocations",
                        "Objects larger than half a G1 region are allocated directly in the old generation. See "
                                + "the largest allocated types; consider -XX:G1HeapRegionSize or smaller buffers.",
                        topLocation(memory.allocations()));
            } else if (c.contains("Metadata GC Threshold")) {
                add(Severity.INFO, "gc", cause.count() + " collections triggered by metaspace growth",
                        "Class loading (proxies, lambdas, scripting) grew metaspace. Set -XX:MetaspaceSize to the "
                                + "steady-state size to avoid these.", null);
            }
        }
        long full = gc.byCollector().stream()
                .filter(g -> g.name().contains("Full") || g.name().equals("SerialOld")
                        || g.name().equals("ParallelOld") || g.name().contains("MarkSweep"))
                .mapToLong(GcGroup::count).sum();
        if (full > 0) {
            add(full > 3 ? Severity.CRITICAL : Severity.WARNING, "gc", full + " full (old generation) collections",
                    "Full collections are the longest pauses. They usually mean the old generation filled up: "
                            + "check the live set trend and long-lived objects.", null);
        }
    }

    private void memory(MemoryReport memory, Span span) {
        Long max = memory.heapMaxBytes();
        if (max != null && max > 0 && memory.liveSetMaxBytes() != null) {
            double pct = Stats.percent(memory.liveSetMaxBytes(), max);
            if (pct >= LIVE_SET_WARN_PERCENT) {
                add(pct >= LIVE_SET_CRITICAL_PERCENT ? Severity.CRITICAL : Severity.WARNING, "memory",
                        String.format(Locale.ROOT, "Heap after GC reached %.0f%% of the maximum heap", pct),
                        "The live set leaves little headroom: expect frequent or full collections and risk of "
                                + "OutOfMemoryError. Increase -Xmx or reduce retained data.",
                        topLocation(memory.oldObjects()));
            }
        }
        Double growth = memory.liveSetGrowthBytesPerMin();
        if (growth != null && growth > 0 && memory.liveSetFirstBytes() != null && memory.liveSetLastBytes() != null
                && memory.liveSetLastBytes() > memory.liveSetFirstBytes() * 1.2 && span.millis() >= 60_000) {
            add(Severity.WARNING, "memory",
                    String.format(Locale.ROOT, "Heap after GC grows by %s per minute", Format.bytes(growth)),
                    "Used heap after collections climbed from " + Format.bytes(memory.liveSetFirstBytes()) + " to "
                            + Format.bytes(memory.liveSetLastBytes())
                            + ". If it keeps growing under steady load this is a leak; check long-lived objects "
                            + "(record with jdk.OldObjectSample#cutoff=infinity to get reference chains).",
                    topLocation(memory.oldObjects()));
        }
        if (memory.allocationRateBytesPerSec() != null && memory.allocationRateBytesPerSec() >= ALLOCATION_RATE_WARN) {
            add(Severity.WARNING, "memory", "Allocation rate " + Format.bytes(memory.allocationRateBytesPerSec())
                            + "/s",
                    "High allocation rates drive GC frequency. Start with the top allocation hot spots.",
                    topLocation(memory.allocations()));
        }
        HotspotReport alloc = memory.allocations();
        if (!alloc.hotspots().isEmpty()) {
            Hotspot top = alloc.hotspots().getFirst();
            if (top.percent() >= ALLOCATION_HOTSPOT_WARN_PERCENT) {
                add(Severity.WARNING, "memory", String.format(Locale.ROOT,
                                "%s allocates %.1f%% of sampled bytes", top.method(), top.percent()),
                        "Mostly " + topName(top.details()) + ", " + Format.bytes(top.weight())
                                + " estimated during the recording.", top.location());
            }
        }
        HotspotReport old = memory.oldObjects();
        if (!old.hotspots().isEmpty()) {
            Hotspot top = old.hotspots().getFirst();
            add(Severity.INFO, "memory", "Long-lived objects allocated by " + top.method(),
                    top.count() + " old-object samples (" + topName(top.details())
                            + ") survived until the end of the recording. Leak candidate if this keeps growing.",
                    top.location());
        }
    }

    private void threads(ThreadReport threads, Span span) {
        HotspotReport enter = threads.monitorEnter();
        double blockedMs = enter.totalWeight() / 1e6;
        if (enter.present() && Stats.percent(blockedMs, span.millis()) >= BLOCKED_WARN_PERCENT_OF_DURATION) {
            Hotspot top = enter.hotspots().isEmpty() ? null : enter.hotspots().getFirst();
            add(Severity.WARNING, "threads", String.format(Locale.ROOT,
                            "Threads spent %s blocked on contended locks", Format.millis(blockedMs)),
                    top == null ? "Contention is outside the requested packages; see the monitor classes."
                            : "Hottest: " + top.method() + " waiting on " + topName(top.details())
                            + ". Shrink the synchronized section or use a concurrent structure.",
                    top == null ? null : top.location());
        }
        HotspotReport park = threads.park();
        if (!park.hotspots().isEmpty()) {
            Hotspot top = park.hotspots().getFirst();
            double ms = top.weight() / 1e6;
            if (Stats.percent(ms, span.millis()) >= BLOCKED_WARN_PERCENT_OF_DURATION) {
                add(Severity.WARNING, "threads", String.format(Locale.ROOT, "%s parked for %s in total",
                                top.method(), Format.millis(ms)),
                        "Waiting on " + topName(top.details()) + " (j.u.c lock, queue or future). Check the parked "
                                + "call: lock contention, a slow downstream call or an undersized pool.",
                        top.location());
            }
        }
        HotspotReport pinned = threads.pinned();
        if (pinned.present()) {
            Hotspot top = pinned.hotspots().isEmpty() ? null : pinned.hotspots().getFirst();
            add(Severity.WARNING, "threads", pinned.events() + " virtual-thread pinning events",
                    "A virtual thread blocked while pinned to its carrier (" + topName(pinned.topDetails())
                            + "), so the carrier could not run other virtual threads.",
                    top == null ? null : top.location());
        }
    }

    private void io(IoReport io) {
        for (HotspotReport r : List.of(io.socketRead(), io.socketWrite(), io.fileRead(), io.fileWrite())) {
            if (r.hotspots().isEmpty()) {
                continue;
            }
            Hotspot top = r.hotspots().getFirst();
            double ms = top.weight() / 1e6;
            if (ms >= IO_WARN_MS) {
                add(Severity.WARNING, "io", String.format(Locale.ROOT, "%s: %s spent in %s by %s",
                                r.title(), Format.millis(ms), topName(top.details()), top.method()),
                        top.count() + " slow calls (each over JFR's threshold). Cache, batch or make the call "
                                + "asynchronous, and check the remote side's latency.", top.location());
            }
        }
    }

    private void exceptions(ExceptionReport exceptions) {
        Double rate = exceptions.throwablesPerSecond();
        if (rate != null && rate >= EXCEPTIONS_WARN_PER_SECOND) {
            add(Severity.WARNING, "exceptions", String.format(Locale.ROOT, "%.0f exceptions created per second",
                    rate), "Exceptions used for control flow cost stack walks. Enable jdk.JavaExceptionThrow to "
                    + "see where they are thrown.", topLocation(exceptions.throwSites()));
        }
        HotspotReport sites = exceptions.throwSites();
        if (!sites.hotspots().isEmpty()) {
            Hotspot top = sites.hotspots().getFirst();
            add(Severity.INFO, "exceptions", top.count() + " throwables created in " + top.method(),
                    "Mostly " + topName(top.details()) + ".", top.location());
        }
    }

    private void add(Severity severity, String category, String title, String detail, @Nullable String location) {
        findings.add(new Finding(severity, category, title, detail, location));
    }

    private static @Nullable String topLocation(HotspotReport r) {
        return r.hotspots().isEmpty() ? null : r.hotspots().getFirst().location();
    }

    private static String topName(List<WeightedName> names) {
        return names.isEmpty() ? "?" : names.getFirst().name();
    }

    private static double nz(@Nullable Double d) {
        return d == null ? 0 : d;
    }
}
